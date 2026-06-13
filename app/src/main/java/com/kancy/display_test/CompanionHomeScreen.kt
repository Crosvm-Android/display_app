package com.kancy.display_test

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kancy.display_test.ui.theme.StatusOk
import com.kancy.display_test.ui.theme.SurfaceContainerLow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionHomeScreen(
    viewModel: MainViewModel,
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDisplay: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Crosvm Display") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Session status + live/last frame card ──────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SurfaceContainerLow,
                shadowElevation = 1.dp,
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                viewModel.displayWidth.toFloat() /
                                    viewModel.displayHeight.toFloat()
                            )
                            .background(Color.Black),
                    ) {
                        if (!viewModel.isFullscreen) {
                            CrosvmSurfaceViews(
                                mainSurfaceView = mainSurfaceView,
                                cursorSurfaceView = cursorSurfaceView,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (!viewModel.isConnected) {
                            Text(
                                text = "未连接",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusDot(if (viewModel.isConnected) StatusOk else MaterialTheme.colorScheme.outline)
                            Text(
                                text = if (viewModel.isConnected) "显示服务在线" else "显示服务离线",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = "${viewModel.serviceName} · ${viewModel.statusText}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                        )

                        val connecting = viewModel.currentStep > 0 && !viewModel.isConnected
                        val label = when {
                            !viewModel.hasRoot -> "需要 Root 权限"
                            viewModel.isConnected -> "打开显示"
                            connecting -> "连接中…"
                            else -> "连接"
                        }
                        Button(
                            onClick = {
                                when {
                                    viewModel.isConnected -> onOpenDisplay()
                                    viewModel.hasRoot && !connecting -> viewModel.start()
                                }
                            },
                            enabled = viewModel.hasRoot && !connecting,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.DesktopWindows,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(label)
                        }
                    }
                }
            }

            // ── Read-only session info (reported by crosvm) ────────────────────
            SectionCard(title = "会话信息 · 由 crosvm 上报（只读）") {
                InfoRow("分辨率", "${viewModel.displayWidth} × ${viewModel.displayHeight}")
                InfoRow(
                    "DPI / 刷新率",
                    buildString {
                        append(if (viewModel.displayDpi > 0) "${viewModel.displayDpi}" else "—")
                        append(" · ")
                        append(if (viewModel.displayRefreshRate > 0) "${viewModel.displayRefreshRate} Hz" else "—")
                    },
                )
                InfoRow("GPU backend（来自启动参数）", viewModel.gpuBackend ?: "—")
                InfoRow(
                    "Cursor stream",
                    if (viewModel.cursorStreamActive) "active" else "inactive",
                    divider = false,
                )
            }

            // ── Entries ────────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenDiagnostics) { Text("诊断") }
                OutlinedButton(
                    onClick = { viewModel.listServices() },
                    enabled = viewModel.hasRoot,
                ) { Text("重新查找服务") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("设置（app 侧）") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "连接") {
                Text(
                    "显示服务名（高级）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        viewModel.serviceName,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                SettingRow("启动时自动连接", null, viewModel.autoConnect) { viewModel.autoConnect = it }
                SettingRow(
                    "强制 SELinux permissive（兜底）",
                    "默认关，保持 enforcing；若 enforcing 下连不通再打开",
                    viewModel.selinuxForcePermissive,
                ) { viewModel.selinuxForcePermissive = it }
            }

            SectionCard(title = "输入默认值") {
                SettingRow("打开即锁定指针", "进入显示自动 pointer capture", viewModel.lockPointerOnOpen) {
                    viewModel.lockPointerOnOpen = it
                }
                SettingRow("显示功能键面板", "F1–F12 / 方向键托盘", viewModel.showFunctionKeysPref) {
                    viewModel.showFunctionKeysPref = it
                }
                SettingRow("无物理键盘发 tablet mode", null, viewModel.tabletModeOnNoKeyboard) {
                    viewModel.tabletModeOnNoKeyboard = it
                }
            }

            SectionCard(title = "显示") {
                SettingRow("保持屏幕常亮", null, viewModel.keepScreenOn) { viewModel.keepScreenOn = it }
                SettingRow("断连显示最后一帧", "saveFrame / drawSavedFrame", viewModel.showLastFrameOnDisconnect) {
                    viewModel.showLastFrameOnDisconnect = it
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
