package com.kancy.display_test

import android.content.Intent
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kancy.display_test.ui.theme.Display_testTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // SurfaceViews created once here — never recreated on recomposition/fullscreen toggle
    private lateinit var mainSurfaceView: SurfaceView
    private lateinit var cursorSurfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // Create SurfaceViews exactly once
        mainSurfaceView = SurfaceView(this).also { sv ->
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val f = holder.surfaceFrame
                    Log.d("MainActivity", "🖥️ Main surface created ${f.width()}x${f.height()}")
                    viewModel.onMainSurfaceInfo(f.width(), f.height())
                }
                override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                    Log.d("MainActivity", "🖥️ Main surface changed ${w}x${h}")
                    viewModel.onMainSurfaceInfo(w, h)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d("MainActivity", "🖥️ Main surface destroyed")
                }
            })

            // Input handling: touch, keyboard focus
            sv.isFocusable = true
            sv.isFocusableInTouchMode = true
            sv.requestFocus()

            // Touch listener
            sv.setOnTouchListener { _, event ->
                viewModel.inputForwarder?.let { forwarder ->
                    val transform = TouchScaleCalculator.compute(
                        viewModel.displayWidth,
                        viewModel.displayHeight,
                        sv.width,
                        sv.height
                    )
                    forwarder.sendTouchEvent(event, transform.scaleX, transform.scaleY)
                    true
                } ?: false
            }

            // Mouse listener (absolute mode when pointer not captured)
            sv.setOnGenericMotionListener { _, event ->
                if (event.source and android.view.InputDevice.SOURCE_MOUSE != 0) {
                    viewModel.inputForwarder?.let { forwarder ->
                        if (!viewModel.isPointerCaptured) {
                            // Absolute mouse mode
                            forwarder.sendMouseEvent(event, false)
                            true
                        } else {
                            false
                        }
                    } ?: false
                } else {
                    false
                }
            }

            // Captured pointer listener (relative mouse mode)
            sv.setOnCapturedPointerListener { _, event ->
                viewModel.inputForwarder?.sendMouseEvent(event, true)
                true
            }

            // Keyboard listener
            sv.setOnKeyListener { _, keyCode, event ->
                viewModel.inputForwarder?.let { forwarder ->
                    when (event.action) {
                        android.view.KeyEvent.ACTION_DOWN -> forwarder.sendKeyEvent(keyCode, true)
                        android.view.KeyEvent.ACTION_UP -> forwarder.sendKeyEvent(keyCode, false)
                        else -> false
                    }
                } ?: false
            }
        }

        cursorSurfaceView = SurfaceView(this).also { sv ->
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d("MainActivity", "🔲 Cursor surface created")
                }
                override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d("MainActivity", "🔲 Cursor surface destroyed")
                }
            })
        }

        // Give ViewModel stable references before start() can be called
        viewModel.mainSurfaceView = mainSurfaceView
        viewModel.cursorSurfaceView = cursorSurfaceView
        viewModel.appContext = applicationContext
        viewModel.initRoot()

        setContent {
            Display_testTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CrosvmDisplayTestScreen(
                        viewModel = viewModel,
                        mainSurfaceView = mainSurfaceView,
                        cursorSurfaceView = cursorSurfaceView,
                        onToggleFullscreen = { toggleFullscreen() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        // Restore immersive mode if we were in fullscreen
        if (viewModel.isFullscreen) {
            enterImmersiveMode()
        }
    }

    private fun enterImmersiveMode() {
        window.insetsController?.let { controller ->
            controller.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersiveMode() {
        window.insetsController?.show(
            android.view.WindowInsets.Type.statusBars() or
            android.view.WindowInsets.Type.navigationBars()
        )
    }

    fun toggleFullscreen() {
        viewModel.toggleFullscreen()
        if (viewModel.isFullscreen) {
            enterImmersiveMode()
        } else {
            exitImmersiveMode()
        }
    }

    fun togglePointerCapture() {
        val sv = mainSurfaceView
        if (viewModel.isPointerCaptured) {
            sv.releasePointerCapture()
            viewModel.isPointerCaptured = false
            Log.d("MainActivity", "Pointer capture released")
        } else {
            sv.requestPointerCapture()
            viewModel.isPointerCaptured = true
            Log.d("MainActivity", "Pointer capture requested")
        }
    }

    fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                val aspectRatio = Rational(viewModel.displayWidth, viewModel.displayHeight)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user presses home button (if connected)
        if (viewModel.isConnected && !viewModel.isFullscreen) {
            enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.isInPipMode = isInPictureInPictureMode
        Log.d("MainActivity", "PiP mode: $isInPictureInPictureMode")
    }
}

@Composable
fun CrosvmDisplayTestScreen(
    viewModel: MainViewModel,
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFunctionKeys by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    if (viewModel.isFullscreen) {
        // ── Fullscreen — same SurfaceViews, just placed full-screen ─────────────
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            CrosvmSurfaceViews(
                mainSurfaceView = mainSurfaceView,
                cursorSurfaceView = cursorSurfaceView,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Exit Fullscreen", tint = Color.White)
            }
            // Function keys button in fullscreen
            if (viewModel.inputForwarder != null) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pointer lock button
                    OutlinedButton(
                        onClick = { activity?.togglePointerCapture() }
                    ) {
                        Text(
                            if (viewModel.isPointerCaptured) "🔒 Unlock" else "🖱️ Lock",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                    // Function keys button
                    OutlinedButton(
                        onClick = { showFunctionKeys = true }
                    ) {
                        Text("Keys", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    } else {
        // ── Normal mode ──────────────────────────────────────────────────────────
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // Title + root indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Crosvm Display Test",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape)
                            .background(if (viewModel.hasRoot) Color.Green else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (viewModel.hasRoot) "root" else "no root",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = viewModel.hasRoot && viewModel.currentStep == 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Connect") }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = viewModel.currentStep > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Secondary actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.listServices() }, modifier = Modifier.weight(1f)) {
                    Text("List", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { viewModel.checkEnvironment() }, modifier = Modifier.weight(1f)) {
                    Text("Check", fontSize = 12.sp)
                }
                if (viewModel.isConnected) {
                    OutlinedButton(onClick = { viewModel.saveFrame() }, modifier = Modifier.weight(1f)) {
                        Text("Save", fontSize = 12.sp)
                    }
                }
                if (viewModel.inputForwarder != null) {
                    OutlinedButton(onClick = { showFunctionKeys = true }, modifier = Modifier.weight(1f)) {
                        Text("Keys", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        viewModel.errorMessage != null -> MaterialTheme.colorScheme.errorContainer
                        viewModel.isConnected          -> MaterialTheme.colorScheme.primaryContainer
                        viewModel.currentStep > 0      -> MaterialTheme.colorScheme.secondaryContainer
                        else                           -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(12.dp).clip(CircleShape).background(
                                when {
                                    viewModel.errorMessage != null -> Color.Red
                                    viewModel.surfaceSent          -> Color.Green
                                    viewModel.isConnected          -> Color(0xFF4CAF50)
                                    viewModel.currentStep > 0      -> Color.Yellow
                                    else                           -> Color.Gray
                                }
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(viewModel.statusText, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (viewModel.surfaceInfo.isNotEmpty()) {
                        Text("Surface: ${viewModel.surfaceInfo}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (viewModel.errorMessage != null) {
                        Text(viewModel.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Environment check results
            viewModel.environmentReport?.let { report ->
                if (!report.allPassed()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Environment Issues",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            report.getFailures().forEach { (name, result) ->
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        "❌ $name: ${result.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    result.suggestion?.let { suggestion ->
                                        Text(
                                            "   → $suggestion",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Session info card (read-only display config)
            if (viewModel.isConnected && viewModel.surfaceSent) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Session Info", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))

                        // Service name
                        Text("Service: ${viewModel.serviceName}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

                        // Display config
                        Text("Resolution: ${viewModel.displayWidth} × ${viewModel.displayHeight}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (viewModel.displayDpi > 0) {
                            Text("DPI: ${viewModel.displayDpi}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        if (viewModel.displayRefreshRate > 0) {
                            Text("Refresh Rate: ${viewModel.displayRefreshRate} Hz",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }

                        // Cursor stream status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text("Cursor Stream: ",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier.size(8.dp).clip(CircleShape)
                                    .background(if (viewModel.cursorStreamActive) Color.Green else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (viewModel.cursorStreamActive) "Active" else "Inactive",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }

                        Text("(Reported by crosvm)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // VM Display header with fullscreen button
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "VM Display (${viewModel.displayWidth}×${viewModel.displayHeight}):",
                        style = MaterialTheme.typography.titleSmall
                    )
                    // Pointer lock indicator
                    if (viewModel.isPointerCaptured) {
                        Text(
                            "🔒",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // PiP button
                    if (viewModel.inputForwarder != null && !viewModel.isInPipMode) {
                        IconButton(
                            onClick = { activity?.enterPictureInPicture() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("⬜", fontSize = 16.sp)
                        }
                    }
                    // Pointer lock toggle
                    if (viewModel.inputForwarder != null) {
                        IconButton(
                            onClick = { activity?.togglePointerCapture() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text(
                                if (viewModel.isPointerCaptured) "🔒" else "🖱️",
                                fontSize = 16.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 16:9 display area — reuses the same SurfaceViews
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(viewModel.displayWidth.toFloat() / viewModel.displayHeight.toFloat())
                    .background(Color.Black)
            ) {
                CrosvmSurfaceViews(
                    mainSurfaceView = mainSurfaceView,
                    cursorSurfaceView = cursorSurfaceView,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Log card
            Card(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(viewModel.logMessages.size) {
                    if (viewModel.logMessages.isNotEmpty())
                        listState.animateScrollToItem(viewModel.logMessages.size - 1)
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Log", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { viewModel.clearLogs() }, modifier = Modifier.height(28.dp)) {
                            Text("Clear", fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(viewModel.logMessages) { msg ->
                            Text(msg, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                lineHeight = 14.sp, modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        }

        // Function keys panel
        FunctionKeysPanel(
            visible = showFunctionKeys,
            onDismiss = { showFunctionKeys = false },
            onKeyDown = { scanCode ->
                viewModel.inputForwarder?.sendRawKeyEvent(scanCode, true)
            },
            onKeyUp = { scanCode ->
                viewModel.inputForwarder?.sendRawKeyEvent(scanCode, false)
            }
        )
    }
}

/**
 * Places the two pre-existing SurfaceViews into the Compose layout.
 * Uses AndroidView with the existing view instances — they are NEVER recreated.
 * The Box stacks cursor on top of main.
 */
@Composable
fun CrosvmSurfaceViews(
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Main display surface
        AndroidView(
            factory = { mainSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
        // Cursor overlay — on top
        AndroidView(
            factory = { cursorSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
    }
}