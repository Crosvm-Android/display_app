package com.kancy.display_test

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceView
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thread that reads cursor position (x: u32, y: u32) from a stream written by crosvm,
 * and moves the cursor SurfaceView overlay to match.
 *
 * This mirrors the CursorHandler from the reference TerminalApp DisplayProvider.
 */
class CursorHandler(
    private val stream: ParcelFileDescriptor,
    private val cursorView: SurfaceView,
    private val mainView: SurfaceView
) : Thread("CursorHandler") {

    companion object {
        private const val TAG = "CursorHandler"
    }

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            // Reparent cursor surface control to the main surface control
            // so cursor moves relative to the main display
            val cursorSc: SurfaceControl = cursorView.surfaceControl ?: run {
                Log.e(TAG, "cursorView.surfaceControl is null")
                return
            }
            val mainSc: SurfaceControl = mainView.surfaceControl ?: run {
                Log.e(TAG, "mainView.surfaceControl is null")
                return
            }
            SurfaceControl.Transaction().apply {
                reparent(cursorSc, mainSc)
                apply()
            }

            val transaction = SurfaceControl.Transaction()
            val byteBuffer = ByteBuffer.allocate(8) // (x: u32, y: u32)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val fis = FileInputStream(stream.fileDescriptor)

            Log.i(TAG, "Cursor handler started, reading position stream...")

            while (!isInterrupted) {
                byteBuffer.clear()
                val bytes = fis.read(byteBuffer.array(), 0, 8)
                if (bytes == -1) {
                    Log.w(TAG, "Cursor stream EOF, stopping handler")
                    return
                }
                if (bytes < 8) {
                    // partial read - shouldn't happen with socket pair but handle gracefully
                    continue
                }

                val x = (byteBuffer.getInt() and 0xFFFFFFFF.toInt()).toFloat()
                val y = (byteBuffer.getInt() and 0xFFFFFFFF.toInt()).toFloat()

                if (!cursorSc.isValid) {
                    Log.d(TAG, "SurfaceControl for cursor is released, stopping")
                    return
                }

                transaction.setPosition(cursorSc, x, y).apply()
            }
        } catch (e: IOException) {
            if (!isInterrupted) {
                Log.e(TAG, "CursorHandler IO error", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CursorHandler error", e)
        } finally {
            Log.i(TAG, "CursorHandler stopped")
        }
    }

    fun shutdown() {
        interrupt()
        try {
            stream.close()
        } catch (_: Exception) {}
    }
}
