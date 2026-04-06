package com.kancy.display_test

import android.os.Bundle
import android.util.Log
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
    }
}

@Composable
fun CrosvmDisplayTestScreen(
    viewModel: MainViewModel,
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    modifier: Modifier = Modifier,
) {
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
                onClick = { viewModel.toggleFullscreen() },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Exit Fullscreen", tint = Color.White)
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
                OutlinedButton(onClick = { viewModel.listServices() }, modifier = Modifier.weight(1f)) {
                    Text("List", fontSize = 12.sp)
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

            // VM Display header with fullscreen button
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VM Display (1920×1080):", style = MaterialTheme.typography.titleSmall)
                IconButton(
                    onClick = { viewModel.toggleFullscreen() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 16:9 display area — reuses the same SurfaceViews
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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