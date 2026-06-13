package com.kancy.display_test

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kancy.display_test.ui.theme.SurfaceContainerHigh
import com.kancy.display_test.ui.theme.SurfaceContainerLow

/**
 * Overlay-HUD function key tray. F1–F12 / modifiers / arrows / navigation keys.
 * Press and release are both forwarded (via [onKeyDown]/[onKeyUp]) so combos like Ctrl+C work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionKeysPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = SurfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Esc + F1–F12
            KeyRow {
                Key("Esc", KeyCodeMapper.KEY_ESC, onKeyDown, onKeyUp)
                for (i in 1..12) Key("F$i", KeyCodeMapper.KEY_F1 + (i - 1), onKeyDown, onKeyUp)
            }
            // Modifiers + Tab + arrows + navigation
            KeyRow {
                Key("Ctrl", KeyCodeMapper.KEY_LEFTCTRL, onKeyDown, onKeyUp, mod = true)
                Key("Alt", KeyCodeMapper.KEY_LEFTALT, onKeyDown, onKeyUp, mod = true)
                Key("Shift", KeyCodeMapper.KEY_LEFTSHIFT, onKeyDown, onKeyUp, mod = true)
                Key("Tab", KeyCodeMapper.KEY_TAB, onKeyDown, onKeyUp)
                Key("↑", KeyCodeMapper.KEY_UP, onKeyDown, onKeyUp)
                Key("↓", KeyCodeMapper.KEY_DOWN, onKeyDown, onKeyUp)
                Key("←", KeyCodeMapper.KEY_LEFT, onKeyDown, onKeyUp)
                Key("→", KeyCodeMapper.KEY_RIGHT, onKeyDown, onKeyUp)
                Key("Home", KeyCodeMapper.KEY_HOME, onKeyDown, onKeyUp)
                Key("End", KeyCodeMapper.KEY_END, onKeyDown, onKeyUp)
                Key("PgUp", KeyCodeMapper.KEY_PAGEUP, onKeyDown, onKeyUp)
                Key("PgDn", KeyCodeMapper.KEY_PAGEDOWN, onKeyDown, onKeyUp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) { content() }
}

@Composable
private fun Key(
    label: String,
    scanCode: Int,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    mod: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        pressed -> MaterialTheme.colorScheme.primaryContainer
        mod -> MaterialTheme.colorScheme.secondaryContainer
        else -> SurfaceContainerHigh
    }
    val fg = when {
        pressed -> MaterialTheme.colorScheme.onPrimaryContainer
        mod -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 9.dp)
            .pointerInput(scanCode) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onKeyDown(scanCode)
                        tryAwaitRelease()
                        onKeyUp(scanCode)
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = fg)
    }
}
