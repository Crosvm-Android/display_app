package com.kancy.display_test

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.MotionEvent
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Centralized input forwarding to the guest VM.
 *
 * Events are encoded with [EvdevEncoder] and written to the per-device unix sockets that
 * crosvm reads from (`--input multi-touch/keyboard/mouse/switches[path=...]`). The host ends
 * of those sockets are obtained from [IRootDisplayService.getInputSockets] (see
 * [InputSocketHost]). This path requires NO changes to crosvm and does NOT go through
 * ICrosvmAndroidDisplayService (that binder lives inside crosvm and cannot inject input).
 *
 * Writes are serialized on a single background thread so the UI thread never blocks on a
 * socket, mirroring VirtualMachine.java's input executor. MotionEvents are copied before
 * being handed to the worker because the framework recycles the originals.
 *
 * @param sockets 4-element array indexed by [InputSocketHost] channel constants; null
 *   elements mean crosvm has not connected that channel yet (those events are dropped).
 */
class InputForwarder(
    sockets: Array<ParcelFileDescriptor?>,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "InputForwarder"
    }

    private fun openStream(pfd: ParcelFileDescriptor): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(pfd)

    private val touchOut = sockets.getOrNull(InputSocketHost.MULTITOUCH)?.let(::openStream)
    private val keyOut = sockets.getOrNull(InputSocketHost.KEYBOARD)?.let(::openStream)
    private val mouseOut = sockets.getOrNull(InputSocketHost.MOUSE)?.let(::openStream)
    private val switchesOut = sockets.getOrNull(InputSocketHost.SWITCHES)?.let(::openStream)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InputForwarder").apply { isDaemon = true }
    }

    val isTouchReady get() = touchOut != null
    val isKeyboardReady get() = keyOut != null
    val isMouseReady get() = mouseOut != null
    val isSwitchesReady get() = switchesOut != null

    private inline fun submit(name: String, crossinline block: () -> Unit) {
        try {
            worker.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "$name failed", e)
                    logger("⚠️ $name failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Executor rejected (shutting down) — ignore.
            Log.d(TAG, "$name dropped: ${e.message}")
        }
    }

    // ── Touch ──────────────────────────────────────────────────────────────

    /**
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     */
    fun sendTouchEvent(event: MotionEvent, scaleX: Float, scaleY: Float) {
        val out = touchOut ?: return
        val copy = MotionEvent.obtainNoHistory(event)
        submit("sendTouchEvent") {
            try {
                EvdevEncoder.sendMultiTouch(out, copy, scaleX, scaleY)
            } finally {
                copy.recycle()
            }
        }
    }

    // ── Mouse ──────────────────────────────────────────────────────────────

    /**
     * @param isRelative True for pointer-capture (relative) mode. The crosvm `mouse` device is
     *   relative; under capture, MotionEvent x/y are already deltas. Absolute (non-capture)
     *   mouse should be routed through the touch device by the caller; relative deltas are
     *   sent here regardless.
     */
    fun sendMouseEvent(event: MotionEvent, isRelative: Boolean) {
        val out = mouseOut ?: return
        val copy = MotionEvent.obtainNoHistory(event)
        submit("sendMouseEvent") {
            try {
                EvdevEncoder.sendMouse(out, copy)
            } finally {
                copy.recycle()
            }
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    /**
     * Forwards a hardware/virtual key by Android key code.
     * @return true if the key code is mapped, false otherwise.
     */
    fun sendKeyEvent(keyCode: Int, pressed: Boolean): Boolean {
        if (keyOut == null) return false

        val synthesis = KeyCodeMapper.needsShiftSynthesis(keyCode)
        if (synthesis != null) {
            val (baseKey, _) = synthesis
            val base = KeyCodeMapper.androidToEvdev(baseKey) ?: return false
            if (pressed) {
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true)
                sendRawKeyEvent(base, true)
            } else {
                sendRawKeyEvent(base, false)
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false)
            }
            return true
        }

        val scanCode = KeyCodeMapper.androidToEvdev(keyCode) ?: return false
        sendRawKeyEvent(scanCode, pressed)
        return true
    }

    /** Sends a raw Linux evdev KEY_* scan code. */
    fun sendRawKeyEvent(scanCode: Int, pressed: Boolean) {
        val out = keyOut ?: return
        submit("sendRawKeyEvent") {
            EvdevEncoder.sendKey(out, scanCode.toShort(), pressed)
        }
    }

    // ── Tablet/Desktop mode ────────────────────────────────────────────────

    fun sendTabletModeEvent(isTablet: Boolean) {
        val out = switchesOut ?: return
        submit("sendTabletModeEvent") {
            EvdevEncoder.sendTabletMode(out, isTablet)
            logger("📱 Tablet mode: $isTablet")
        }
    }

    /** Closes the worker and all sockets. */
    fun close() {
        worker.shutdownNow()
        listOf(touchOut, keyOut, mouseOut, switchesOut).forEach { runCatching { it?.close() } }
    }
}
