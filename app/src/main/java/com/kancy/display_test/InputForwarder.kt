package com.kancy.display_test

import android.util.Log
import android.view.MotionEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Centralized input forwarding to the guest VM.
 *
 * Events are encoded with [EvdevEncoder] into a byte buffer and shipped to the root process via
 * [InputSink] (backed by [IRootDisplayService.writeInput]); the root process owns the per-device
 * unix sockets crosvm reads from (`--input multi-touch/keyboard/mouse/switches[path=...]`) and
 * does the actual socket write. Writing in the root (permissive su/magisk) domain avoids an
 * untrusted_app -> unix_stream_socket SELinux crossing, so SELinux can stay enforcing.
 *
 * Each high-level event is encoded once and sent in a single writeInput() call (one binder hop
 * per MotionEvent/key, not per evdev record). Work is serialized on a single background thread so
 * the UI thread never blocks; MotionEvents are copied first because the framework recycles the
 * originals.
 *
 * @param ready per-channel connection snapshot at construction (for logging only — writes always
 *   attempt and the root side reports failure if a channel isn't connected yet).
 * @param sink ships encoded bytes to the root writer.
 */
class InputForwarder(
    private val ready: BooleanArray,
    private val sink: InputSink,
    private val logger: (String) -> Unit = {}
) {
    /** Writes pre-encoded evdev bytes for a channel in the root process; false if not delivered. */
    fun interface InputSink {
        fun write(channel: Int, data: ByteArray): Boolean
    }

    companion object {
        private const val TAG = "InputForwarder"
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InputForwarder").apply { isDaemon = true }
    }

    val isTouchReady get() = ready.getOrElse(InputSocketHost.MULTITOUCH) { false }
    val isKeyboardReady get() = ready.getOrElse(InputSocketHost.KEYBOARD) { false }
    val isMouseReady get() = ready.getOrElse(InputSocketHost.MOUSE) { false }
    val isSwitchesReady get() = ready.getOrElse(InputSocketHost.SWITCHES) { false }

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
        val copy = MotionEvent.obtainNoHistory(event)
        submit("sendTouchEvent") {
            val buf = ByteArrayOutputStream(256)
            try {
                EvdevEncoder.sendMultiTouch(buf, copy, scaleX, scaleY)
            } finally {
                copy.recycle()
            }
            sink.write(InputSocketHost.MULTITOUCH, buf.toByteArray())
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
        val copy = MotionEvent.obtainNoHistory(event)
        submit("sendMouseEvent") {
            val buf = ByteArrayOutputStream(64)
            try {
                EvdevEncoder.sendMouse(buf, copy)
            } finally {
                copy.recycle()
            }
            sink.write(InputSocketHost.MOUSE, buf.toByteArray())
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    /**
     * Forwards a hardware/virtual key by Android key code.
     * @return true if the key code is mapped, false otherwise.
     */
    fun sendKeyEvent(keyCode: Int, pressed: Boolean): Boolean {
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
        submit("sendRawKeyEvent") {
            val buf = ByteArrayOutputStream(24)
            EvdevEncoder.sendKey(buf, scanCode.toShort(), pressed)
            sink.write(InputSocketHost.KEYBOARD, buf.toByteArray())
        }
    }

    // ── Tablet/Desktop mode ────────────────────────────────────────────────

    fun sendTabletModeEvent(isTablet: Boolean) {
        submit("sendTabletModeEvent") {
            val buf = ByteArrayOutputStream(24)
            EvdevEncoder.sendTabletMode(buf, isTablet)
            if (sink.write(InputSocketHost.SWITCHES, buf.toByteArray())) {
                logger("📱 Tablet mode: $isTablet")
            }
        }
    }

    /** Shuts down the worker. The sockets are owned by the root process, not closed here. */
    fun close() {
        worker.shutdownNow()
    }
}
