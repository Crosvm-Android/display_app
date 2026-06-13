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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    var showSessionInfo by remember { mutableStateOf(false) }

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
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusDot(if (viewModel.isConnected) StatusOk else MaterialTheme.colorScheme.outline)
                            Text(
                                text = if (viewModel.isConnected) "显示服务在线" else "显示服务离线",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showSessionInfo = true }) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "会话信息",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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

    if (showSessionInfo) {
        ModalBottomSheet(
            onDismissRequest = { showSessionInfo = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "会话信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "由 crosvm 上报（只读）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
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
                // Auto-scan registered crosvm display services once root is available.
                LaunchedEffect(viewModel.hasRoot) {
                    if (viewModel.hasRoot) viewModel.discoverServices()
                }

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = viewModel.serviceName,
                        onValueChange = { viewModel.serviceName = it },
                        label = { Text("显示服务名（每台 VM 唯一）") },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        if (viewModel.discoveredServices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(if (viewModel.isDiscovering) "扫描中…" else "未发现服务，可手动输入") },
                                onClick = { expanded = false },
                                enabled = false,
                            )
                        }
                        viewModel.discoveredServices.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    viewModel.serviceName = name
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                ) {
                    Text(
                        "多台 VM 各用不同名字，显示与输入按此名隔离。下拉/扫描用于" +
                            "重连已在运行的 VM（仅显示；输入需 crosvm 在 app 之后启动）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { viewModel.discoverServices() },
                        enabled = viewModel.hasRoot && !viewModel.isDiscovering,
                    ) { Text("扫描") }
                }
                SettingRow("启动时自动连接", null, viewModel.autoConnect) { viewModel.autoConnect = it }
                SettingRow(
                    "强制 SELinux permissive（兜底）",
                    "默认关，保持 enforcing；若 enforcing 下连不通再打开",
                    viewModel.selinuxForcePermissive,
                ) { viewModel.selinuxForcePermissive = it }
            }

            // ── crosvm launch args for the current VM name (copy → start crosvm to match) ──────
            SectionCard(title = "crosvm 启动参数 · 先点连接，再用此命令启动") {
                val clipboard = LocalClipboardManager.current
                val args = viewModel.crosvmLaunchArgs()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceContainerLow,
                ) {
                    Text(
                        args,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "拼到 crosvm run 命令里。input socket 须与服务名一致；" +
                            "width/height 取当前显示尺寸（${viewModel.displayWidth}×${viewModel.displayHeight}）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(args)) }) {
                        Text("复制")
                    }
                }
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
