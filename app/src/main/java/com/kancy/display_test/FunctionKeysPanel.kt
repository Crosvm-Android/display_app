package com.kancy.display_test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Function keys panel (Overlay HUD) for virtual keyboard input.
 * Displays F1-F12, Ctrl, Alt, Esc, Tab, Arrow keys, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionKeysPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "Function Keys",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // F1-F6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 1..6) {
                        FunctionKeyButton(
                            label = "F$i",
                            scanCode = KeyCodeMapper.KEY_F1 + (i - 1),
                            onKeyDown = onKeyDown,
                            onKeyUp = onKeyUp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // F7-F12
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 7..12) {
                        FunctionKeyButton(
                            label = "F$i",
                            scanCode = KeyCodeMapper.KEY_F1 + (i - 1),
                            onKeyDown = onKeyDown,
                            onKeyUp = onKeyUp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Modifiers: Ctrl, Alt, Esc, Tab
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FunctionKeyButton("Ctrl", KeyCodeMapper.KEY_LEFTCTRL, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("Alt", KeyCodeMapper.KEY_LEFTALT, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("Esc", KeyCodeMapper.KEY_ESC, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("Tab", KeyCodeMapper.KEY_TAB, onKeyDown, onKeyUp, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Arrow keys
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FunctionKeyButton("↑", KeyCodeMapper.KEY_UP, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("↓", KeyCodeMapper.KEY_DOWN, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("←", KeyCodeMapper.KEY_LEFT, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("→", KeyCodeMapper.KEY_RIGHT, onKeyDown, onKeyUp, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Navigation keys
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FunctionKeyButton("Home", KeyCodeMapper.KEY_HOME, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("End", KeyCodeMapper.KEY_END, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("PgUp", KeyCodeMapper.KEY_PAGEUP, onKeyDown, onKeyUp, Modifier.weight(1f))
                    FunctionKeyButton("PgDn", KeyCodeMapper.KEY_PAGEDOWN, onKeyDown, onKeyUp, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FunctionKeyButton(
    label: String,
    scanCode: Int,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { /* Click for instant tap, but real handling is via touch listener below */ },
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isPressed)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

