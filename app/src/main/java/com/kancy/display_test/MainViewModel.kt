package com.kancy.display_test

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val manager = CrosvmDisplayManager()

    private val settings = SettingsStore(app)

    /**
     * Compose-observable property that also persists to [SettingsStore] on every change. Reads
     * the stored value (or [default]) at construction, so toggles survive app restarts.
     */
    private inner class PrefBool(private val key: String, default: Boolean) :
        ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(settings.getBool(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            if (state.value == value) return
            state.value = value
            settings.setBool(key, value)
        }
    }

    /** String counterpart of [PrefBool]: Compose-observable and persisted on every change. */
    private inner class PrefString(private val key: String, default: String) :
        ReadWriteProperty<Any?, String> {
        private val state = mutableStateOf(settings.getString(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            if (state.value == value) return
            state.value = value
            settings.setString(key, value)
        }
    }

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
    /**
     * Display log lines, observed by the Logs UI. A [SnapshotStateList] so new entries are
     * appended incrementally — Compose recomposes only the added row instead of re-diffing a
     * freshly allocated list. Rebuilt wholesale only on filter change / clear (see
     * [updateLogDisplay]), never per log line.
     */
    val logMessages = mutableStateListOf<LogEntry>()
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
    /** GPU backend parsed from the running crosvm's launch args; null until known. Read-only. */
    var gpuBackend by mutableStateOf<String?>(null)
        private set
    /** The crosvm display service name = per-VM key. Editable in Settings, persisted, drives both
     * the display-binder lookup and the input socket namespace (see [CrosvmDisplayManager]). */
    var serviceName by PrefString("serviceName", "crosvm_display")
    var environmentReport by mutableStateOf<EnvironmentChecker.EnvironmentReport?>(null)
        private set
    /** crosvm display service names discovered via the service manager — one per running VM. */
    val discoveredServices = mutableStateListOf<String>()
    var isDiscovering by mutableStateOf(false)
        private set

    // ── App-side UI preferences (persisted via SettingsStore, wired to behavior below) ──
    var autoConnect by PrefBool("autoConnect", true)
    var lockPointerOnOpen by PrefBool("lockPointerOnOpen", false)
    var showFunctionKeysPref by PrefBool("showFunctionKeysPref", true)
    var tabletModeOnNoKeyboard by PrefBool("tabletModeOnNoKeyboard", true)
    var keepScreenOn by PrefBool("keepScreenOn", true)
    var showLastFrameOnDisconnect by PrefBool("showLastFrameOnDisconnect", true)
    /** Fallback: globally `setenforce 0`. Off by default — we try to run with SELinux enforcing. */
    var selinuxForcePermissive by PrefBool("selinuxForcePermissive", false)

    // SurfaceView refs — set by Composable AndroidView factories
    var mainSurfaceView: SurfaceView? = null
    var cursorSurfaceView: SurfaceView? = null

    private var displayProvider: DisplayProvider? = null
    var inputForwarder: InputForwarder? = null
        private set
    private var keyboardMonitor: KeyboardMonitor? = null

    fun clearLogs() {
        logManager.clearLogs()
        updateLogDisplay()
    }

    fun setLogFilter(category: LogCategory?) {
        selectedLogCategory = category
        updateLogDisplay()
    }

    fun exportLogs(): String = logManager.exportToString()

    /** Full rebuild of the display list — only on filter change / clear, not per log line. */
    private fun updateLogDisplay() {
        val entries = selectedLogCategory?.let { logManager.getLogsByCategory(it) }
            ?: logManager.getAllLogs()
        logMessages.clear()
        logMessages.addAll(entries)
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    private fun addLog(message: String) {
        val entry = logManager.addLog(message)
        Log.d(TAG, message)
        // Incremental UI update: append only the new line (O(1)) instead of reformatting the
        // whole buffer. Honor the active filter, and mirror LogManager's capacity bound so the
        // display list drops its oldest line in lockstep with the backing buffer.
        if (selectedLogCategory == null || entry.category == selectedLogCategory) {
            logMessages.add(entry)
            while (logMessages.size > logManager.capacity) logMessages.removeAt(0)
        }
    }

    // ── Init root ───────────────────────────────────────────────────────────────

    fun initRoot() {
        viewModelScope.launch {
            addLog("🔑 Requesting root...")
            hasRoot = withContext(Dispatchers.IO) { manager.initRoot() }
            if (hasRoot) {
                addLog("✅ Root granted")
                if (autoConnect && !isConnected && currentStep == 0) {
                    addLog("⚡ Auto-connect enabled — starting…")
                    start()
                }
            } else { addLog("❌ Root denied"); errorMessage = "Root access required" }
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

            // Pin the per-VM key for this whole connection: display lookup + input sockets use it.
            manager.serviceName = serviceName.trim()

            currentStep = 1
            val seMode = if (selinuxForcePermissive) "SELinux→permissive (fallback)" else "SELinux enforcing"
            statusText = "Exempting hidden APIs…"
            addLog("🔓 Exempting hidden APIs ($seMode)…")
            val exempted = withContext(Dispatchers.IO) { manager.exemptHiddenApis(selinuxForcePermissive) }
            if (!exempted) { fail("Failed to exempt hidden APIs / SELinux"); return@launch }
            addLog("✅ Hidden APIs exempted; $seMode")

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

            // Open this VM's input listeners now, before its crosvm connects them at startup.
            // Per-VM socket namespace, so concurrent VMs never collide (see InputSocketHost).
            withContext(Dispatchers.IO) { manager.ensureInputListening() }
            addLog("🎧 Input listeners ready for '${manager.serviceName}'")

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
                        addLog("   ✅ Display binder: obtained")

                        connectionState = ConnectionState.MAIN_SURFACE_SENDING
                        statusText = "Sending main surface…"
                        addLog("   3. Main surface: sending...")

                        statusText = "Connected — VM display active"
                        addLog("✅ VM display active")

                        // crosvm is now running; read its GPU backend from the launch args
                        // (crosvm doesn't report it over the display AIDL, so this is read-only
                        // and reflects what `--gpu backend=` was set to, not live state).
                        val gpu = withContext(Dispatchers.IO) { manager.readGpuBackend(serviceName) }
                        gpuBackend = gpu
                        addLog(if (gpu != null) "🖼️ GPU backend: $gpu (from launch args)" else "ℹ️ GPU backend: unknown (no --gpu in args)")
                        // Build the InputForwarder. The root process owns the input sockets and
                        // does the writes; the app only ships encoded bytes (so untrusted_app
                        // never touches the socket — keeps SELinux enforcing). crosvm connects its
                        // sockets at startup, so a channel may not be ready yet; writes still go
                        // through once crosvm connects (the root side consults the live socket).
                        val ready = withContext(Dispatchers.IO) { manager.getInputChannelsReady() }
                        if (ready != null) {
                            inputForwarder?.close()
                            val sink = InputForwarder.InputSink { ch, data -> manager.writeInput(ch, data) }
                            val fwd = InputForwarder(ready, sink) { msg -> addLog(msg) }
                            inputForwarder = fwd
                            val readyNames = listOfNotNull(
                                if (fwd.isTouchReady) "touch" else null,
                                if (fwd.isKeyboardReady) "key" else null,
                                if (fwd.isMouseReady) "mouse" else null,
                                if (fwd.isSwitchesReady) "switches" else null,
                            )
                            addLog("✅ InputForwarder ready (channels: ${if (readyNames.isEmpty()) "none yet — will connect when crosvm starts" else readyNames.joinToString()})")

                            // Start keyboard monitor for tablet/desktop mode (only if enabled).
                            val ctx = appContext
                            if (ctx != null && tabletModeOnNoKeyboard) {
                                keyboardMonitor = KeyboardMonitor(ctx) { isTablet ->
                                    inputForwarder?.sendTabletModeEvent(isTablet)
                                }
                                keyboardMonitor?.start()
                                addLog("✅ KeyboardMonitor started")
                            } else if (!tabletModeOnNoKeyboard) {
                                addLog("ℹ️ Tablet-mode auto-switch disabled in settings")
                            }
                        } else {
                            addLog("⚠️ Input sockets unavailable — input forwarding disabled")
                        }
                    } else {
                        // Connection lost or failed
                        keyboardMonitor?.stop()
                        keyboardMonitor = null
                        inputForwarder?.close()
                        inputForwarder = null
                        gpuBackend = null
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
            // Save the last frame before disconnecting, so it can be shown while offline.
            if (showLastFrameOnDisconnect) {
                manager.saveFrame(forCursor = false)
                addLog("📸 Saved display frame")
            }
            keyboardMonitor?.stop()
            keyboardMonitor = null
            displayProvider?.shutdown()
            displayProvider = null
            inputForwarder?.close()
            inputForwarder = null
            withContext(Dispatchers.IO) { manager.disconnect() }
            isConnected = false; currentStep = 0; surfaceSent = false
            gpuBackend = null
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
                manager.shellCheckService(serviceName.trim())
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

    /**
     * Scans the service manager for registered crosvm display services (one per VM) and updates
     * [discoveredServices]. If the current [serviceName] isn't among them but exactly one was
     * found, adopt it — the common single-VM case where the user needn't know the name.
     */
    fun discoverServices() {
        if (!hasRoot || isDiscovering) return
        viewModelScope.launch {
            isDiscovering = true
            val found = withContext(Dispatchers.IO) { manager.discoverDisplayServices() }
            discoveredServices.clear()
            discoveredServices.addAll(found)
            isDiscovering = false
            when {
                found.isEmpty() -> addLog("🔍 未发现已注册的 crosvm 显示服务")
                else -> addLog("🔍 发现 ${found.size} 个显示服务: ${found.joinToString()}")
            }
            if (found.size == 1 && serviceName !in found) {
                serviceName = found.first()
                addLog("➡️ 已自动选中唯一服务: $serviceName")
            }
        }
    }

    /**
     * The crosvm launch flags this VM must be started with so it matches the app: the display
     * service name plus the four per-VM input socket paths (width/height = current display size).
     * crosvm is launched to match the app (the app owns the input listeners), so this is the
     * artifact the user actually needs — not service discovery.
     */
    fun crosvmLaunchArgs(): String {
        val name = serviceName.trim().ifEmpty { "crosvm_display" }
        val p = InputSocketHost.pathsFor(name)
        return buildString {
            append("--android-display-service $name \\\n")
            append("--input multi-touch[path=${p[InputSocketHost.MULTITOUCH]},width=$displayWidth,height=$displayHeight] \\\n")
            append("--input keyboard[path=${p[InputSocketHost.KEYBOARD]}] \\\n")
            append("--input mouse[path=${p[InputSocketHost.MOUSE]}] \\\n")
            append("--input switches[path=${p[InputSocketHost.SWITCHES]}]")
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

    /** Dumps recent SELinux denials to the log so targeted allow rules can be written. */
    fun collectSelinuxDenials() {
        viewModelScope.launch {
            addLog("🔎 Collecting SELinux denials (policy tool: ${withContext(Dispatchers.IO) { manager.detectPolicyTool() }})…")
            val denials = withContext(Dispatchers.IO) { manager.collectSelinuxDenials() }
            if (denials.isEmpty()) {
                addLog("✅ No avc:denied found (nothing blocked, or already permissive)")
            } else {
                addLog("⚠️ ${denials.size} SELinux denial line(s):")
                denials.forEach { addLog("   $it") }
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
        inputForwarder?.close()
        inputForwarder = null
        mainSurfaceView = null
        cursorSurfaceView = null
        manager.disconnect()
    }
}
