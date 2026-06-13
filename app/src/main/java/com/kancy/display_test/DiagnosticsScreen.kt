package com.kancy.display_test

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kancy.display_test.MainViewModel.ConnectionState
import com.kancy.display_test.ui.theme.SurfaceContainerLowest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("诊断") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> StateTab(viewModel, onViewLogs = { tab = 2 })
                1 -> InfoTab(viewModel)
                else -> LogsTab(viewModel)
            }
        }

        NavigationBar(containerColor = com.kancy.display_test.ui.theme.SurfaceContainer) {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null) },
                label = { Text("状态") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                label = { Text("信息") },
            )
            NavigationBarItem(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                label = { Text("日志") },
            )
        }
    }
}

@Composable
private fun StateTab(viewModel: MainViewModel, onViewLogs: () -> Unit) {
    val cs = viewModel.connectionState
    val err = cs == ConnectionState.ERROR
    val reachedOrdinal = if (err) -1 else cs.ordinal
    fun step(target: ConnectionState, active: ConnectionState): StepState = when {
        reachedOrdinal >= target.ordinal -> StepState.Ok
        cs == active -> StepState.Active
        else -> StepState.Pending
    }

    val rootState = if (viewModel.hasRoot) StepState.Ok
        else if (cs == ConnectionState.ROOT_BINDING) StepState.Active else StepState.Pending
    val binderState = step(ConnectionState.DISPLAY_BINDER_GOT, ConnectionState.DISPLAY_BINDER_WAIT)
    val mainState = step(ConnectionState.MAIN_SURFACE_SENT, ConnectionState.MAIN_SURFACE_SENDING)
    val cursorState = step(ConnectionState.CURSOR_SURFACE_SENT, ConnectionState.CURSOR_SURFACE_SENDING)
    val inputState = if (viewModel.inputForwarder != null) StepState.Ok else StepState.Pending

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "连接状态") {
            StateListItem(Icons.Filled.Check, "Root", if (viewModel.hasRoot) "已授权" else "未授权", state = rootState)
            StateListItem(Icons.Filled.DesktopWindows, "Display binder", "${viewModel.serviceName}", state = binderState, topDivider = true)
            StateListItem(Icons.Filled.DesktopWindows, "Main surface", viewModel.surfaceInfo.ifEmpty { "等待" }, state = mainState, topDivider = true)
            StateListItem(Icons.Filled.Mouse, "Cursor surface", if (viewModel.cursorStreamActive) "active" else "等待", state = cursorState, topDivider = true)
            StateListItem(Icons.Filled.Keyboard, "Input", if (viewModel.inputForwarder != null) "sockets ready" else "等待", state = inputState, topDivider = true)
        }

        if (viewModel.errorMessage != null) {
            SectionCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "下一步：" + viewModel.statusText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    viewModel.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.start() }) { Text("重试连接") }
                    TextButton(onClick = onViewLogs) { Text("查看日志") }
                }
            }
        }
    }
}

@Composable
private fun InfoTab(viewModel: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "会话信息 · 由 crosvm 上报（只读）") {
            InfoRow("Service", viewModel.serviceName)
            InfoRow("Resolution", "${viewModel.displayWidth} × ${viewModel.displayHeight}")
            InfoRow("DPI / 刷新率", "${viewModel.displayDpi.takeIf { it > 0 } ?: "—"} · ${viewModel.displayRefreshRate.takeIf { it > 0 }?.let { "$it Hz" } ?: "—"}")
            InfoRow("Cursor stream", if (viewModel.cursorStreamActive) "active" else "inactive", divider = false)
        }

        SectionCard(title = "环境检查") {
            val report = viewModel.environmentReport
            if (report == null) {
                Text(
                    "尚未运行环境检查",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { viewModel.checkEnvironment() }) { Text("运行检查") }
            } else {
                EnvRow("Root shell", report.rootAccess.passed, report.rootAccess.message)
                EnvRow("Hidden API 豁免", report.hiddenApiPolicy.passed, report.hiddenApiPolicy.message, top = true)
                EnvRow("SELinux permissive", report.selinuxStatus.passed, report.selinuxStatus.message, top = true)
                EnvRow("crosvm_display 注册", report.crosvmService.passed, report.crosvmService.message, top = true)
                EnvRow("crosvm 进程", report.crosvmProcess.passed, report.crosvmProcess.message, top = true)
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { viewModel.checkEnvironment() }) { Text("重新检查") }
            }
        }
    }
}

@Composable
private fun EnvRow(name: String, passed: Boolean, message: String, top: Boolean = false) {
    StateListItem(
        icon = if (passed) Icons.Filled.Check else Icons.Filled.Close,
        title = name,
        subtitle = message,
        state = if (passed) StepState.Ok else StepState.Pending,
        topDivider = top,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.logMessages.size) {
        if (viewModel.logMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.logMessages.size - 1)
    }

    val filters = listOf(
        "全部" to null,
        "disp" to LogCategory.DISPLAY,
        "root" to LogCategory.ROOT,
        "crosvm" to LogCategory.CROSVM,
        "input" to LogCategory.INPUT,
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日志（分级）", style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("display_app logs", viewModel.exportLogs()))
                }) { Text("导出") }
                TextButton(onClick = { viewModel.clearLogs() }) { Text("清空") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.forEach { (label, cat) ->
                FilterChip(
                    selected = viewModel.selectedLogCategory == cat,
                    onClick = { viewModel.setLogFilter(cat) },
                    label = { Text(label) },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(viewModel.logMessages) { msg ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceContainerLowest)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
