package com.kancy.display_test

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice

/**
 * Monitors physical keyboard connectivity and reports tablet/desktop mode changes.
 *
 * - No physical keyboard detected → tablet mode
 * - Physical keyboard connected → desktop mode
 */
class KeyboardMonitor(
    private val context: Context,
    private val onModeChanged: (isTablet: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "KeyboardMonitor"
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private var isTabletMode = true

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            Log.d(TAG, "Device added: $deviceId")
            checkKeyboardState()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            Log.d(TAG, "Device removed: $deviceId")
            checkKeyboardState()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            Log.d(TAG, "Device changed: $deviceId")
            checkKeyboardState()
        }
    }

    /**
     * Starts monitoring keyboard changes.
     * Immediately checks current state and reports it.
     */
    fun start() {
        inputManager.registerInputDeviceListener(deviceListener, null)
        checkKeyboardState()
        Log.d(TAG, "KeyboardMonitor started")
    }

    /**
     * Stops monitoring keyboard changes.
     */
    fun stop() {
        inputManager.unregisterInputDeviceListener(deviceListener)
        Log.d(TAG, "KeyboardMonitor stopped")
    }

    /**
     * Checks if a full physical keyboard is connected.
     * Updates tablet mode state and notifies listener if changed.
     */
    private fun checkKeyboardState() {
        val hasFullKeyboard = hasFullPhysicalKeyboard()
        val newTabletMode = !hasFullKeyboard

        if (newTabletMode != isTabletMode) {
            isTabletMode = newTabletMode
            Log.i(TAG, "Mode changed: ${if (isTabletMode) "tablet" else "desktop"}")
            onModeChanged(isTabletMode)
        }
    }

    /**
     * Returns true if a full physical keyboard is connected.
     */
    private fun hasFullPhysicalKeyboard(): Boolean {
        val deviceIds = inputManager.inputDeviceIds
        for (deviceId in deviceIds) {
            val device = inputManager.getInputDevice(deviceId) ?: continue

            // Check if it's a full keyboard (not a virtual keyboard)
            if (device.isFullKeyboard) {
                Log.d(TAG, "Found full keyboard: ${device.name} (id=$deviceId)")
                return true
            }
        }
        Log.d(TAG, "No full keyboard found")
        return false
    }

    /**
     * Extension to check if an InputDevice is a full physical keyboard.
     */
    private val InputDevice.isFullKeyboard: Boolean
        get() {
            // Must be a physical device (not virtual)
            if (isVirtual) return false

            // Must have keyboard source
            if (!sources.hasKeyboardSource) return false

            // Must have a full set of keys (check for alphanumeric capability)
            return hasKeys(
                android.view.KeyEvent.KEYCODE_Q,
                android.view.KeyEvent.KEYCODE_W,
                android.view.KeyEvent.KEYCODE_E,
                android.view.KeyEvent.KEYCODE_R,
                android.view.KeyEvent.KEYCODE_T,
                android.view.KeyEvent.KEYCODE_Y
            ).all { it }
        }

    private val Int.hasKeyboardSource: Boolean
        get() = (this and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
}
