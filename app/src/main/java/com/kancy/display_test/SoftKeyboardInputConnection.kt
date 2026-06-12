package com.kancy.display_test

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Custom InputConnection for handling soft keyboard input.
 * Converts character input to scan code sequences.
 */
class SoftKeyboardInputConnection(
    targetView: View,
    fullEditor: Boolean,
    private val onKeyEvent: (scanCode: Int, pressed: Boolean) -> Unit
) : BaseInputConnection(targetView, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.forEach { char ->
            sendCharacterAsScanCode(char)
        }
        return super.commitText(text, newCursorPosition)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val scanCode = KeyCodeMapper.androidToEvdev(event.keyCode)
        if (scanCode != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyEvent(scanCode, true)
                KeyEvent.ACTION_UP -> onKeyEvent(scanCode, false)
            }
            return true
        }
        return super.sendKeyEvent(event)
    }

    private fun sendCharacterAsScanCode(char: Char) {
        when (char) {
            in 'a'..'z' -> {
                val scanCode = KeyCodeMapper.KEY_A + (char - 'a')
                onKeyEvent(scanCode, true)
                onKeyEvent(scanCode, false)
            }
            in 'A'..'Z' -> {
                // Shift + letter
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true)
                val scanCode = KeyCodeMapper.KEY_A + (char - 'A')
                onKeyEvent(scanCode, true)
                onKeyEvent(scanCode, false)
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false)
            }
            in '0'..'9' -> {
                val scanCode = KeyCodeMapper.KEY_1 + (char - '1')
                if (char == '0') {
                    onKeyEvent(KeyCodeMapper.KEY_0, true)
                    onKeyEvent(KeyCodeMapper.KEY_0, false)
                } else {
                    onKeyEvent(scanCode, true)
                    onKeyEvent(scanCode, false)
                }
            }
            ' ' -> {
                onKeyEvent(KeyCodeMapper.KEY_SPACE, true)
                onKeyEvent(KeyCodeMapper.KEY_SPACE, false)
            }
            '\n' -> {
                onKeyEvent(KeyCodeMapper.KEY_ENTER, true)
                onKeyEvent(KeyCodeMapper.KEY_ENTER, false)
            }
            '.' -> {
                onKeyEvent(KeyCodeMapper.KEY_DOT, true)
                onKeyEvent(KeyCodeMapper.KEY_DOT, false)
            }
            ',' -> {
                onKeyEvent(KeyCodeMapper.KEY_COMMA, true)
                onKeyEvent(KeyCodeMapper.KEY_COMMA, false)
            }
            '-' -> {
                onKeyEvent(KeyCodeMapper.KEY_MINUS, true)
                onKeyEvent(KeyCodeMapper.KEY_MINUS, false)
            }
            '=' -> {
                onKeyEvent(KeyCodeMapper.KEY_EQUAL, true)
                onKeyEvent(KeyCodeMapper.KEY_EQUAL, false)
            }
            '/' -> {
                onKeyEvent(KeyCodeMapper.KEY_SLASH, true)
                onKeyEvent(KeyCodeMapper.KEY_SLASH, false)
            }
            '@' -> {
                // Shift + 2
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true)
                onKeyEvent(KeyCodeMapper.KEY_2, true)
                onKeyEvent(KeyCodeMapper.KEY_2, false)
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false)
            }
            '#' -> {
                // Shift + 3
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true)
                onKeyEvent(KeyCodeMapper.KEY_3, true)
                onKeyEvent(KeyCodeMapper.KEY_3, false)
                onKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false)
            }
            // Add more characters as needed
        }
    }
}

/**
 * InputConnection provider for SurfaceView.
 */
class SoftKeyboardInputConnectionProvider(
    private val view: View,
    private val onKeyEvent: (scanCode: Int, pressed: Boolean) -> Unit
) {
    fun createInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return SoftKeyboardInputConnection(view, true, onKeyEvent)
    }
}
