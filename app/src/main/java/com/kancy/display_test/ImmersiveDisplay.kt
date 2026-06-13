package com.kancy.display_test

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kancy.display_test.ui.theme.GlassScrim
import com.kancy.display_test.ui.theme.StatusOk

/**
 * Fullscreen immersive VM display (Fluid Canvas): surface constrained to the guest aspect
 * ratio (letterbox/pillarbox), a glass top bar with status pills, a floating FAB column, a
 * pointer-lock chip, and the Overlay-HUD function-key sheet.
 */
@Composable
fun ImmersiveDisplay(
    viewModel: MainViewModel,
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    onExitFullscreen: () -> Unit,
    onTogglePointerCapture: () -> Unit,
    onShowKeyboard: () -> Unit,
    onEnterPip: () -> Unit,
) {
    var showKeys by remember { mutableStateOf(false) }
    val ratio = viewModel.displayWidth.toFloat() / viewModel.displayHeight.toFloat()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Letterbox/pillarbox the surface to the guest ratio.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cw = maxWidth
            val ch = maxHeight
            val (dw, dh) = if (cw / ch > ratio) (ch * ratio) to ch else cw to (cw / ratio)
            Box(modifier = Modifier.align(Alignment.Center).size(dw, dh)) {
                CrosvmSurfaceViews(
                    mainSurfaceView = mainSurfaceView,
                    cursorSurfaceView = cursorSurfaceView,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Glass top bar with status pills.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(
                text = if (viewModel.isConnected) "已连接" else "离线",
                dotColor = if (viewModel.isConnected) StatusOk else MaterialTheme.colorScheme.outline,
                container = GlassScrim,
            )
            Pill("${viewModel.displayWidth}×${viewModel.displayHeight}", container = GlassScrim)
        }

        // Pointer-lock chip (center).
        if (viewModel.isPointerCaptured) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(999.dp),
                color = GlassScrim,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "指针已锁定 · 返回键释放",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Floating FAB column.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val locked = viewModel.isPointerCaptured
            SmallFloatingActionButton(
                onClick = onTogglePointerCapture,
                containerColor = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (locked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = "Pointer lock")
            }
            if (viewModel.showFunctionKeysPref) {
                SmallFloatingActionButton(onClick = { showKeys = true }) {
                    Icon(Icons.Filled.Apps, contentDescription = "Function keys")
                }
            }
            SmallFloatingActionButton(onClick = onShowKeyboard) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Soft keyboard")
            }
            SmallFloatingActionButton(onClick = onEnterPip) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture in picture")
            }
            SmallFloatingActionButton(onClick = onExitFullscreen) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit fullscreen")
            }
        }

        // Overlay-HUD function key sheet.
        FunctionKeysPanel(
            visible = showKeys,
            onDismiss = { showKeys = false },
            onKeyDown = { scanCode -> viewModel.inputForwarder?.sendRawKeyEvent(scanCode, true) },
            onKeyUp = { scanCode -> viewModel.inputForwarder?.sendRawKeyEvent(scanCode, false) },
        )
    }
}

