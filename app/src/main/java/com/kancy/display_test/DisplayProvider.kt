package com.kancy.display_test

import android.crosvm.ICrosvmAndroidDisplayService
import android.graphics.PixelFormat
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Provides crosvm display surfaces.
 *
 * Key invariants:
 *  1. Binder fetch on background thread — main thread never blocks.
 *  2. Main surface MUST be sent before cursor surface.
 *  3. setSurface is called AT MOST ONCE per surface lifetime (surfaceCreated → surfaceDestroyed).
 *     surfaceChanged alone (layout resize, position change, fullscreen toggle) must NEVER
 *     re-call setSurface — crosvm throws IllegalArgumentException if called twice.
 *  4. Surface is only sent after surfaceChanged confirms the surface is ready and stable.
 *  5. surfaceDestroyed → removeSurface, flags reset. Next surfaceCreated starts a new session.
 */
internal class DisplayProvider(
    private val mainView: SurfaceView,
    private val cursorView: SurfaceView,
    private var width: Int,
    private var height: Int,
    /** Called with log messages so the ViewModel can show them in the UI. */
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
    /** Called when the display service is connected/disconnected. */
    private val onConnected: (Boolean) -> Unit = {},
    /** Called when display config is received from crosvm. */
    private val onDisplayConfig: (android.crosvm.DisplayConfig) -> Unit = {},
    /** Called on a background thread; blocks until binder is available (or returns null). */
    private val binderProvider: () -> IBinder?,
) {
    companion object {
        private const val TAG = "DisplayProvider"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DisplayProvider-bg") }

    // All fields accessed only on main thread
    private var displayService: ICrosvmAndroidDisplayService? = null

    // Per-surface state: needsSend is set by surfaceCreated, cleared after successful setSurface.
    // Once cleared (sent), surfaceChanged won't re-send even if layout changes.
    private var mainNeedsSend = false
    private var cursorNeedsSend = false
    private var mainSurfaceSent = false   // true while crosvm holds our main surface

    private var cursorHandlerThread: CursorHandlerThread? = null

    init {
        mainView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        mainView.holder.addCallback(Callback(SurfaceKind.MAIN))

        cursorView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        cursorView.holder.addCallback(Callback(SurfaceKind.CURSOR))
        cursorView.holder.setFormat(PixelFormat.RGBA_8888)
        cursorView.holder.setFixedSize(64, 64)  // Cursor surface fixed at 64x64
        cursorView.setZOrderMediaOverlay(true)

        // Surfaces already live at construction time (DisplayProvider created after surfaceCreated
        // already fired). Pre-set needsSend flags so surfaceChanged will pick them up,
        // OR applyPendingSurfaces if binder not ready yet.
        if (mainView.holder.surface?.isValid == true) {
            Log.d(TAG, "init: main surface already live")
            mainView.holder.setFixedSize(width, height)
            mainNeedsSend = true
        }
        if (cursorView.holder.surface?.isValid == true) {
            Log.d(TAG, "init: cursor surface already live")
            cursorNeedsSend = true
        }

        // Fetch binder on background thread — never blocks main thread
        executor.submit {
            logger("⏳ Waiting for crosvm display binder (background)…")
            val b = try { binderProvider() } catch (e: Exception) {
                Log.e(TAG, "binderProvider threw", e); null
            }
            mainHandler.post {
                if (b == null) {
                    logger("❌ Display binder is null — crosvm not running?")
                    onConnected(false)
                } else {
                    logger("🎉 Got crosvm display binder")
                    displayService = ICrosvmAndroidDisplayService.Stub.asInterface(b)

                    // Try to get display config from crosvm
                    try {
                        val config = displayService?.displayConfig
                        if (config != null && config.width > 0 && config.height > 0) {
                            logger("📐 Display config from crosvm: ${config.width}×${config.height} @${config.dpi}dpi ${config.refreshRate}Hz")
                            width = config.width
                            height = config.height
                            mainView.holder.setFixedSize(width, height)
                            onDisplayConfig(config)
                        } else {
                            logger("⚠️ Display config unavailable, using default ${width}×${height}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get display config, using default", e)
                        logger("⚠️ Could not query display config, using ${width}×${height}")
                    }

                    onConnected(true)
                    // Send any surfaces that are already live
                    applyPendingSurfaces()
                }
            }
        }
    }

    /** Called on main thread once binder arrives. Sends surfaces that are already live. */
    private fun applyPendingSurfaces() {
        val svc = displayService ?: return

        if (mainNeedsSend) {
            val mainHolder = mainView.holder
            val sent = trySendSurface(svc, mainHolder, forCursor = false)
            if (!sent) {
                logger("⚠️ Main surface not ready yet — will send when surfaceChanged fires")
                return  // cursor must wait for main
            }
        }

        if (mainSurfaceSent && cursorNeedsSend) {
            trySendSurface(svc, cursorView.holder, forCursor = true)
        }
    }

    /**
     * Sends a surface to crosvm. Returns true on success.
     * On success, clears the needsSend flag for that surface kind.
     */
    private fun trySendSurface(
        svc: ICrosvmAndroidDisplayService,
        holder: SurfaceHolder,
        forCursor: Boolean,
    ): Boolean {
        val label = if (forCursor) "cursor" else "main"
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            logger("⚠️ $label surface not valid — skipping")
            return false
        }
        return try {
            svc.setSurface(surface, forCursor)
            if (!forCursor) {
                mainNeedsSend = false
                mainSurfaceSent = true
            } else {
                cursorNeedsSend = false
            }
            logger("✅ $label surface sent")
            if (forCursor) setupCursorStream(svc)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setSurface($label) failed", e)
            logger("⚠️ setSurface($label) failed: ${e.javaClass.simpleName}: ${e.message}")
            // Hide the SurfaceView to prevent surfaceflinger from compositing an
            // uninitialised buffer, which can crash RenderEngine and reboot the device.
            val view = if (forCursor) cursorView else mainView
            view.visibility = android.view.View.INVISIBLE
            if (!forCursor) {
                cursorView.visibility = android.view.View.INVISIBLE
            }
            false
        }
    }

    private fun setupCursorStream(svc: ICrosvmAndroidDisplayService) {
        cursorHandlerThread?.interrupt()
        cursorHandlerThread = null
        try {
            val pfds = ParcelFileDescriptor.createSocketPair()
            svc.setCursorStream(pfds[1])
            pfds[1].close()
            cursorHandlerThread = CursorHandlerThread(pfds[0], cursorView, mainView).also { it.start() }
            logger("✅ Cursor stream started")
        } catch (e: Exception) {
            Log.e(TAG, "setCursorStream failed", e)
            logger("⚠️ setCursorStream failed: ${e.message}")
        }
    }

    enum class SurfaceKind { MAIN, CURSOR }

    inner class Callback(private val surfaceKind: SurfaceKind) : SurfaceHolder.Callback {
        private fun isForCursor() = surfaceKind == SurfaceKind.CURSOR

        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceCreated: $surfaceKind")
            if (surfaceKind == SurfaceKind.MAIN) {
                holder.setFixedSize(width, height)
                mainNeedsSend = true   // fresh surface — must be sent on next surfaceChanged
            } else {
                cursorNeedsSend = true
            }
            // Actual sending is deferred to surfaceChanged (surface stable there)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            Log.d(TAG, "surfaceChanged: $surfaceKind ${w}x${h}, needsSend=${if (surfaceKind == SurfaceKind.MAIN) mainNeedsSend else cursorNeedsSend}, mainSent=$mainSurfaceSent")

            val svc = displayService ?: run {
                logger("⏳ [${surfaceKind.name}] surfaceChanged but binder not ready yet — will send when binder arrives")
                return
            }

            when (surfaceKind) {
                SurfaceKind.MAIN -> {
                    if (!mainNeedsSend) {
                        // Already sent for this surface lifetime — ignore layout-driven changes
                        Log.d(TAG, "surfaceChanged: MAIN already sent, ignoring resize/reposition")
                        return
                    }
                    val sent = trySendSurface(svc, holder, forCursor = false)
                    if (sent && cursorNeedsSend) {
                        // Cursor was waiting for main — send it now
                        trySendSurface(svc, cursorView.holder, forCursor = true)
                    }
                }
                SurfaceKind.CURSOR -> {
                    if (!cursorNeedsSend) {
                        Log.d(TAG, "surfaceChanged: CURSOR already sent, ignoring")
                        return
                    }
                    if (!mainSurfaceSent) {
                        logger("⏳ Cursor surfaceChanged but main not sent yet — will wait")
                        return
                    }
                    trySendSurface(svc, holder, forCursor = true)
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceDestroyed: $surfaceKind")
            if (surfaceKind == SurfaceKind.MAIN) {
                mainNeedsSend = false
                mainSurfaceSent = false
            } else {
                cursorNeedsSend = false
            }

            val svc = displayService ?: return
            try {
                svc.removeSurface(isForCursor())
            } catch (e: DeadObjectException) {
                Log.w(TAG, "Display service already dead on surfaceDestroyed")
            } catch (e: RemoteException) {
                Log.e(TAG, "removeSurface failed for $surfaceKind", e)
            }
            if (surfaceKind == SurfaceKind.CURSOR) {
                cursorHandlerThread?.interrupt()
                cursorHandlerThread = null
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        cursorHandlerThread?.interrupt()
        cursorHandlerThread = null
        displayService = null
        mainNeedsSend = false
        cursorNeedsSend = false
        mainSurfaceSent = false
    }
}

/**
 * Thread that reads cursor (x, y) from crosvm and repositions the cursor SurfaceControl.
 */
private class CursorHandlerThread(
    private val stream: ParcelFileDescriptor,
    private val cursorView: SurfaceView,
    private val mainView: SurfaceView,
) : Thread("CursorHandler") {

    companion object { private const val TAG = "CursorHandler" }

    init { isDaemon = true }

    override fun run() {
        val cursor: SurfaceControl = cursorView.surfaceControl
        val transaction = SurfaceControl.Transaction()
        // Reparent cursor under main so coordinates are relative to it
        transaction.reparent(cursor, mainView.surfaceControl).apply()

        try {
            val fis = FileInputStream(stream.fileDescriptor)
            val buf = ByteBuffer.allocate(8)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                if (isInterrupted) { Log.d(TAG, "CursorHandler interrupted"); return }
                buf.clear()
                var offset = 0
                while (offset < 8) {
                    val read = fis.read(buf.array(), offset, 8 - offset)
                    if (read == -1) { Log.e(TAG, "Cursor stream EOF"); return }
                    offset += read
                }
                buf.rewind()
                val x = (buf.getInt() and -0x1).toFloat()
                val y = (buf.getInt() and -0x1).toFloat()
                if (!cursor.isValid) { Log.d(TAG, "Cursor SurfaceControl released"); return }
                transaction.setPosition(cursor, x, y).apply()
            }
        } catch (e: IOException) {
            Log.e(TAG, "CursorHandler IO error", e)
        }
    }
}
