package com.kancy.display_test

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        viewModel = viewModel,
                        mainSurfaceView = mainSurfaceView,
                        cursorSurfaceView = cursorSurfaceView,
                        activity = this@MainActivity,
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
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            exitImmersiveMode()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            viewModel.isFullscreen -> toggleFullscreen()
            viewModel.isConnected && !viewModel.isInPipMode -> enterPictureInPicture()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
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
                val location = IntArray(2)
                mainSurfaceView.getLocationInWindow(location)
                val sourceRect = android.graphics.Rect(
                    location[0],
                    location[1],
                    location[0] + mainSurfaceView.width,
                    location[1] + mainSurfaceView.height
                )
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .setSourceRectHint(sourceRect)
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    fun showSoftKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        mainSurfaceView.requestFocus()
        imm.showSoftInput(mainSurfaceView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        Log.d("MainActivity", "Soft keyboard requested")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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

private enum class AppScreen { Home, Settings, Diagnostics }

/**
 * Top-level UI: a fullscreen immersive display when [MainViewModel.isFullscreen], otherwise
 * the Companion Home / Settings / Diagnostics screens. The two pre-created SurfaceViews live
 * on exactly one screen at a time (Home preview or fullscreen), never both.
 */
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    mainSurfaceView: SurfaceView,
    cursorSurfaceView: SurfaceView,
    activity: MainActivity,
) {
    if (viewModel.isFullscreen) {
        ImmersiveDisplay(
            viewModel = viewModel,
            mainSurfaceView = mainSurfaceView,
            cursorSurfaceView = cursorSurfaceView,
            onExitFullscreen = { activity.toggleFullscreen() },
            onTogglePointerCapture = { activity.togglePointerCapture() },
            onShowKeyboard = { activity.showSoftKeyboard() },
            onEnterPip = { activity.enterPictureInPicture() },
        )
        return
    }

    var screen by remember { mutableStateOf(AppScreen.Home) }
    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        when (screen) {
            AppScreen.Home -> CompanionHomeScreen(
                viewModel = viewModel,
                mainSurfaceView = mainSurfaceView,
                cursorSurfaceView = cursorSurfaceView,
                onOpenSettings = { screen = AppScreen.Settings },
                onOpenDiagnostics = { screen = AppScreen.Diagnostics },
                onOpenDisplay = { activity.toggleFullscreen() },
            )
            AppScreen.Settings -> SettingsScreen(viewModel, onBack = { screen = AppScreen.Home })
            AppScreen.Diagnostics -> DiagnosticsScreen(viewModel, onBack = { screen = AppScreen.Home })
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
        AndroidView(
            factory = { mainSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
        AndroidView(
            factory = { cursorSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
