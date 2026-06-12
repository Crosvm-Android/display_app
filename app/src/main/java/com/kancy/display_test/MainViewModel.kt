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
    var selectedLogCategory by mutableStateOf<LogCategory?>(null)
        private set

    private val logManager = LogManager()

    var currentStep     by mutableStateOf(0)
        private set

    // Connection state machine steps
    enum class ConnectionState {
        DISCONNECTED,           // Not connected
        ROOT_BINDING,           // Binding to root service
        ROOT_BOUND,             // Root service bound
        DISPLAY_BINDER_WAIT,    // Waiting for display binder
        DISPLAY_BINDER_GOT,     // Display binder obtained
        MAIN_SURFACE_SENDING,   // Sending main surface
        MAIN_SURFACE_SENT,      // Main surface sent
        CURSOR_SURFACE_SENDING, // Sending cursor surface
        CURSOR_SURFACE_SENT,    // Cursor surface sent (fully connected)
        ERROR                   // Connection error
    }

    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var hasRoot         by mutableStateOf(false)
        private set
    var isFullscreen    by mutableStateOf(false)
        private set
    var isPointerCaptured by mutableStateOf(false)
    var isInPipMode by mutableStateOf(false)
    var displayWidth    by mutableStateOf(1920)
        private set
    var displayHeight   by mutableStateOf(1080)
        private set
    var displayDpi      by mutableStateOf(0)
        private set
    var displayRefreshRate by mutableStateOf(0)
        private set
    var cursorStreamActive by mutableStateOf(false)
        private set
    var serviceName by mutableStateOf("crosvm_display")
        private set
    var environmentReport by mutableStateOf<EnvironmentChecker.EnvironmentReport?>(null)
        private set

    // SurfaceView refs — set by Composable AndroidView factories
    var mainSurfaceView: SurfaceView? = null
    var cursorSurfaceView: SurfaceView? = null

    private var displayProvider: DisplayProvider? = null
    var inputForwarder: InputForwarder? = null
        private set
    private var keyboardMonitor: KeyboardMonitor? = null

    private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun clearLogs() {
        logManager.clearLogs()
        updateLogDisplay()
    }

    fun setLogFilter(category: LogCategory?) {
        selectedLogCategory = category
        updateLogDisplay()
    }

    fun exportLogs(): String = logManager.exportToString()

    private fun updateLogDisplay() {
        logMessages = if (selectedLogCategory == null) {
            logManager.getAllLogs().map { it.format() }
        } else {
            logManager.getLogsByCategory(selectedLogCategory!!).map { it.format() }
        }
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    private fun addLog(message: String) {
        val ts = dateFmt.format(Date())
        val formattedMsg = "[$ts] $message"
        logManager.addLog(message)
        updateLogDisplay()
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
                        connectionState = ConnectionState.DISPLAY_BINDER_GOT
                        statusText = "Display binder obtained"
                        addLog("✅ Display binder obtained")

                        connectionState = ConnectionState.MAIN_SURFACE_SENDING
                        statusText = "Sending main surface…"

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
                        connectionState = ConnectionState.ERROR
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
            onCursorStreamChanged = { active ->
                viewModelScope.launch {
                    cursorStreamActive = active
                    if (active) addLog("✅ Cursor stream active")
                    else addLog("⚠️ Cursor stream closed")
                }
            },
            binderProvider = { manager.waitForDisplayBinder() }
        )
        addLog("✅ DisplayProvider created — waiting for surfaceCreated to fire…")
    }

    fun stop() {
        viewModelScope.launch {
            addLog("🔌 Stopping…")
            // Save frame before disconnecting
            manager.saveFrame(forCursor = false)
            addLog("📸 Saved display frame")
            keyboardMonitor?.stop()
            keyboardMonitor = null
            displayProvider?.shutdown()
            displayProvider = null
            inputForwarder = null
            withContext(Dispatchers.IO) { manager.disconnect() }
            isConnected = false; currentStep = 0; surfaceSent = false
            connectionState = ConnectionState.DISCONNECTED
            statusText = "Stopped"; errorMessage = null
            addLog("✅ Stopped")
        }
    }

    fun saveFrame() {
        viewModelScope.launch {
            val error = manager.saveFrame(forCursor = false)
            if (error == null) {
                addLog("📸 Frame saved")
            } else {
                addLog("⚠️ Save frame failed: $error")
            }
        }
    }

    fun drawSavedFrame() {
        viewModelScope.launch {
            val error = manager.drawSavedFrame(forCursor = false)
            if (error == null) {
                addLog("🖼️ Restored saved frame")
            } else {
                addLog("⚠️ Draw frame failed: $error")
            }
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

    fun checkEnvironment() {
        viewModelScope.launch {
            addLog("🔍 Checking environment…")
            val ctx = appContext ?: return@launch
            val checker = EnvironmentChecker(ctx)
            val report = withContext(Dispatchers.IO) {
                checker.checkEnvironment(manager)
            }
            environmentReport = report

            // Log results
            if (report.rootAccess.passed) addLog("✅ ${report.rootAccess.message}")
            else addLog("❌ ${report.rootAccess.message}")

            if (report.hiddenApiPolicy.passed) addLog("✅ ${report.hiddenApiPolicy.message}")
            else addLog("❌ ${report.hiddenApiPolicy.message}")

            if (report.selinuxStatus.passed) addLog("✅ ${report.selinuxStatus.message}")
            else addLog("❌ ${report.selinuxStatus.message}")

            if (report.crosvmService.passed) addLog("✅ ${report.crosvmService.message}")
            else addLog("❌ ${report.crosvmService.message}")

            if (report.crosvmProcess.passed) addLog("✅ ${report.crosvmProcess.message}")
            else addLog("⚠️ ${report.crosvmProcess.message}")

            if (report.allPassed()) {
                addLog("✅ Environment check passed")
            } else {
                addLog("⚠️ Environment check found issues")
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
