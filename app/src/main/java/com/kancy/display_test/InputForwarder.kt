package com.kancy.display_test

import android.crosvm.ICrosvmAndroidDisplayService
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Centralized input forwarding to the guest VM via ICrosvmAndroidDisplayService.
 *
 * Handles:
 * - Touch events (multi-touch with coordinate scaling)
 * - Mouse events (absolute and relative pointer capture)
 * - Keyboard events (hardware keyboard + virtual function keys)
 * - Tablet/desktop mode switching
 */
class InputForwarder(
    private val displayService: ICrosvmAndroidDisplayService,
    private val logger: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "InputForwarder"
    }

    // ── Touch ──────────────────────────────────────────────────────────────

    /**
     * Forwards a touch event to the guest.
     * @param event The MotionEvent from the touch view
     * @param scaleX Scale factor: guestWidth / viewWidth
     * @param scaleY Scale factor: guestHeight / viewHeight
     */
    fun sendTouchEvent(event: MotionEvent, scaleX: Float, scaleY: Float) {
        try {
            displayService.sendTouchEvent(event, scaleX, scaleY)
        } catch (e: Exception) {
            Log.e(TAG, "sendTouchEvent failed", e)
            logger("⚠️ Touch event failed: ${e.message}")
        }
    }

    // ── Mouse ──────────────────────────────────────────────────────────────

    /**
     * Forwards a mouse event to the guest.
     * @param event The MotionEvent
     * @param isRelative True for relative (pointer capture) mode, false for absolute
     */
    fun sendMouseEvent(event: MotionEvent, isRelative: Boolean) {
        try {
            displayService.sendMouseEvent(event, isRelative)
        } catch (e: Exception) {
            Log.e(TAG, "sendMouseEvent failed", e)
            logger("⚠️ Mouse event failed: ${e.message}")
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    /**
     * Forwards a hardware keyboard key event.
     * @param keyCode Android KeyEvent.KEYCODE_*
     * @param pressed True for key down, false for key up
     * @return True if the key was handled
     */
    fun sendKeyEvent(keyCode: Int, pressed: Boolean): Boolean {
        // Check if this key needs Shift synthesis (e.g., @ = Shift+2)
        val synthesis = KeyCodeMapper.needsShiftSynthesis(keyCode)
        if (synthesis != null) {
            val (baseKey, needsShift) = synthesis
            if (pressed) {
                // Press: Shift down → base key down
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true)
                sendRawKeyEvent(KeyCodeMapper.androidToEvdev(baseKey) ?: return false, true)
            } else {
                // Release: base key up → Shift up
                sendRawKeyEvent(KeyCodeMapper.androidToEvdev(baseKey) ?: return false, false)
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false)
            }
            return true
        }

        // Normal key mapping
        val scanCode = KeyCodeMapper.androidToEvdev(keyCode) ?: return false
        sendRawKeyEvent(scanCode, pressed)
        return true
    }

    /**
     * Sends a raw evdev scan code to the guest.
     * @param scanCode Linux evdev KEY_* code
     * @param pressed True for key down, false for key up
     */
    fun sendRawKeyEvent(scanCode: Int, pressed: Boolean) {
        try {
            displayService.sendKeyEvent(scanCode, pressed)
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyEvent(scanCode=$scanCode, pressed=$pressed) failed", e)
            logger("⚠️ Key event failed: ${e.message}")
        }
    }

    // ── Tablet/Desktop mode ────────────────────────────────────────────────

    /**
     * Sends tablet/desktop mode state to the guest.
     * @param isTablet True for tablet mode (no physical keyboard), false for desktop mode
     */
    fun sendTabletModeEvent(isTablet: Boolean) {
        try {
            displayService.sendTabletModeEvent(isTablet)
            logger("📱 Tablet mode: $isTablet")
        } catch (e: Exception) {
            Log.e(TAG, "sendTabletModeEvent failed", e)
            logger("⚠️ Tablet mode event failed: ${e.message}")
        }
    }
}
