package com.kancy.display_test

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val manager = CrosvmDisplayManager()

    /** Must be set from Activity before calling start() */
    var appContext: Context? = null

    // ── Observable UI state ────────────────────────────────────────────────────

    var statusText      by mutableStateOf("Not started")
        private set
    var isConnected     by mutableStateOf(false)
        private set
    var errorMessage    by mutableStateOf<String?>(null)
        private set
    var surfaceInfo     by mutableStateOf("")
        private set
    /** True once DisplayProvider has been created (service is connecting/connected). */
    var surfaceSent     by mutableStateOf(false)
        private set
    var logMessages     by mutableStateOf(listOf<String>())
        private set
    var currentStep     by mutableStateOf(0)
        private set
    var hasRoot         by mutableStateOf(false)
        private set
    var isFullscreen    by mutableStateOf(false)
        private set
    var isPointerCaptured by mutableStateOf(false)
    var displayWidth    by mutableStateOf(1920)
        private set
    var displayHeight   by mutableStateOf(1080)
        private set
    var displayDpi      by mutableStateOf(0)
        private set
    var displayRefreshRate by mutableStateOf(0)
        private set

    // SurfaceView refs — set by Composable AndroidView factories
    var mainSurfaceView: SurfaceView? = null
    var cursorSurfaceView: SurfaceView? = null

    private var displayProvider: DisplayProvider? = null
    var inputForwarder: InputForwarder? = null
        private set
    private var keyboardMonitor: KeyboardMonitor? = null

    private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun clearLogs() { logMessages = emptyList() }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    private fun addLog(message: String) {
        val ts = dateFmt.format(Date())
        logMessages = logMessages + "[$ts] $message"
        Log.d(TAG, message)
    }

    // ── Init root ───────────────────────────────────────────────────────────────

    fun initRoot() {
        viewModelScope.launch {
            addLog("🔑 Requesting root...")
            hasRoot = withContext(Dispatchers.IO) { manager.initRoot() }
            if (hasRoot) addLog("✅ Root granted")
            else { addLog("❌ Root denied"); errorMessage = "Root access required" }
        }
    }

    // ── Start ──────────────────────────────────────────────────────────────────

    /**
     * Starts the connection process.
     * 1. Exempts hidden APIs and sets SELinux permissive (via root shell).
     * 2. Binds to RootDisplayService (background thread, blocks until bound).
     * 3. Creates DisplayProvider with both SurfaceViews.
     *    DisplayProvider.displayService is lazy — it will block inside surfaceCreated
     *    (called on the main thread by the system) until crosvm's binder appears.
     *    This exactly mirrors the reference TerminalApp DisplayProvider pattern.
     */
    fun start() {
        if (!hasRoot) { addLog("❌ No root"); errorMessage = "Root required"; return }

        viewModelScope.launch {
            errorMessage = null

            currentStep = 1
            statusText = "Exempting hidden APIs + SELinux…"
            addLog("🔓 Exempting hidden APIs + setting SELinux permissive…")
            val exempted = withContext(Dispatchers.IO) { manager.exemptHiddenApis() }
            if (!exempted) { fail("Failed to exempt hidden APIs / SELinux"); return@launch }
            addLog("✅ Hidden API restrictions lifted + SELinux permissive")

            currentStep = 2
            statusText = "Binding root service…"
            addLog("⏳ Binding RootDisplayService…")

            val ctx = appContext ?: run {
                fail("appContext not set"); return@launch
            }

            // Pre-bind the root service so the binder is ready when surfaceCreated fires.
            val bound = withContext(Dispatchers.IO) { manager.bindRootServiceOnly(ctx) }
            if (!bound) {
                fail("Failed to bind RootDisplayService: ${manager.lastError}")
                return@launch
            }
            addLog("✅ RootDisplayService bound — will wait for crosvm in surfaceCreated")

            currentStep = 3
            statusText = "Surface ready — waiting for crosvm…"
            isConnected = true  // allow UI to show the display area

            // Create DisplayProvider — it attaches callbacks to the SurfaceViews.
            // When surfaceCreated fires (main thread), displayService lazy will block
            // calling waitForDisplayBinder() in the root process.
            val mainSv = mainSurfaceView
            val cursorSv = cursorSurfaceView
            if (mainSv == null || cursorSv == null) {
                addLog("⚠️ SurfaceViews not ready yet — will retry when surfaces appear")
                // The SurfaceViews should be in the composition already; they'll be set
                // before surfaceCreated fires. If not, trySendMainSurface handles it.
            }
            if (mainSv != null && cursorSv != null) {
                createDisplayProvider(mainSv, cursorSv)
            } else {
                addLog("⏳ Waiting for SurfaceViews to be attached…")
                statusText = "Waiting for SurfaceViews…"
            }
        }
    }

    private fun createDisplayProvider(main: SurfaceView, cursor: SurfaceView) {
        displayProvider?.shutdown()
        addLog("🔗 Creating DisplayProvider (${displayWidth}×${displayHeight})…")
        surfaceSent = true
        displayProvider = DisplayProvider(
            mainView = main,
            cursorView = cursor,
            width = displayWidth,
            height = displayHeight,
            logger = { msg -> viewModelScope.launch { addLog(msg) } },
            onConnected = { connected ->
                viewModelScope.launch {
                    if (connected) {
                        statusText = "Connected — VM display active"
                        addLog("✅ VM display active")
                        // Create InputForwarder now that service is connected
                        manager.waitForDisplayBinder()?.let { binder ->
                            val svc = android.crosvm.ICrosvmAndroidDisplayService.Stub.asInterface(binder)
                            inputForwarder = InputForwarder(svc) { msg -> addLog(msg) }
                            addLog("✅ InputForwarder ready")

                            // Start keyboard monitor for tablet/desktop mode
                            val ctx = appContext
                            if (ctx != null) {
                                keyboardMonitor = KeyboardMonitor(ctx) { isTablet ->
                                    inputForwarder?.sendTabletModeEvent(isTablet)
                                }
                                keyboardMonitor?.start()
                                addLog("✅ KeyboardMonitor started")
                            }
                        }
                    } else {
                        // Connection lost or failed
                        keyboardMonitor?.stop()
                        keyboardMonitor = null
                        inputForwarder = null
                        isConnected = false
                        surfaceSent = false
                        statusText = if (currentStep > 0) "Connection lost" else "Failed to connect"
                        errorMessage = "Display service disconnected"
                        addLog("❌ Display service disconnected — click Connect to retry")
                        currentStep = 0  // Allow reconnection
                    }
                }
            },
            onDisplayConfig = { config ->
                viewModelScope.launch {
                    displayWidth = config.width
                    displayHeight = config.height
                    displayDpi = config.dpi
                    displayRefreshRate = config.refreshRate
                    addLog("📐 Display config updated: ${config.width}×${config.height}")
                }
            },
            binderProvider = { manager.waitForDisplayBinder() }
        )
        addLog("✅ DisplayProvider created — waiting for surfaceCreated to fire…")
    }

    fun stop() {
        viewModelScope.launch {
            addLog("🔌 Stopping…")
            keyboardMonitor?.stop()
            keyboardMonitor = null
            displayProvider?.shutdown()
            displayProvider = null
            inputForwarder = null
            withContext(Dispatchers.IO) { manager.disconnect() }
            isConnected = false; currentStep = 0; surfaceSent = false
            statusText = "Stopped"; errorMessage = null
            addLog("✅ Stopped")
        }
    }

    fun listServices() {
        viewModelScope.launch {
            addLog("📋 Scanning services…")
            val check = withContext(Dispatchers.IO) {
                manager.shellCheckService("crosvm_display")
            }
            addLog("service check: $check")
            val services = withContext(Dispatchers.IO) { manager.listRelevantServices() }
            if (services.isNotEmpty()) {
                addLog("🔍 Found:")
                services.forEach { addLog("   • $it") }
            } else {
                addLog("⚠️ No relevant services found")
            }
        }
    }

    private fun fail(msg: String) {
        statusText = "Failed"; errorMessage = msg; currentStep = 0; isConnected = false
        addLog("❌ $msg")
    }

    // ── Surface lifecycle — update UI only ─────────────────────────────────────
    // Actual surface sending is handled inside DisplayProvider callbacks.

    fun onMainSurfaceInfo(width: Int, height: Int) {
        surfaceInfo = "${width}x${height}"
    }

    // ── Pause/Resume ───────────────────────────────────────────────────────────

    fun onPause() {
        if (!isConnected) return
        // No-op: SurfaceHolder.Callback.surfaceDestroyed handles cleanup
    }

    fun onResume() {
        if (!isConnected) return
        // No-op: SurfaceHolder.Callback.surfaceCreated handles reconnect
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        keyboardMonitor?.stop()
        keyboardMonitor = null
        displayProvider?.shutdown()
        displayProvider = null
        inputForwarder = null
        mainSurfaceView = null
        cursorSurfaceView = null
        manager.disconnect()
    }
}
