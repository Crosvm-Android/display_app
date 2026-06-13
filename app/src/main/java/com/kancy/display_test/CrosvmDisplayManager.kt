package com.kancy.display_test

import android.crosvm.ICrosvmAndroidDisplayService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Looks up crosvm's ICrosvmAndroidDisplayService binder via a root service,
 * then uses it to pass surfaces for VM display.
 *
 * Architecture:
 *   1. App binds to RootDisplayService (runs as uid=0 via libsu RootService)
 *   2. Root service calls ServiceManager.waitForService("crosvm_display"), the service that
 *      standalone crosvm registers directly (via --android-display-service crosvm_display)
 *   3. Root service returns ICrosvmAndroidDisplayService binder to the app
 *   4. App uses ICrosvmAndroidDisplayService.setSurface() directly
 */
class CrosvmDisplayManager {

    companion object {
        private const val TAG = "CrosvmDisplayManager"
    }

    private var displayService: ICrosvmAndroidDisplayService? = null
    private var cursorStreamPfd: ParcelFileDescriptor? = null
    private var rootServiceBinder: IRootDisplayService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Root ──────────────────────────────────────────────────────────────

    fun initRoot(): Boolean {
        Shell.enableVerboseLogging = true
        val result = Shell.cmd("id").exec()
        val ok = result.isSuccess && result.out.any { it.contains("uid=0") }
        Log.i(TAG, "Root: ok=$ok output=${result.out}")
        return ok
    }

    /**
     * Exempts hidden APIs and handles SELinux.
     *
     * Does NOT globally disable SELinux by default. Root does not bypass SELinux, but crosvm
     * launched from a root/su shell inherits a permissive su/magisk domain, so the crosvm→socket
     * path usually works while enforcing; only [forcePermissive] (a user fallback) reverts to the
     * old global `setenforce 0`. When left enforcing, [collectSelinuxDenials] surfaces any
     * `avc: denied` so targeted allow rules can be written instead of the global switch.
     */
    fun exemptHiddenApis(forcePermissive: Boolean): Boolean {
        try {
            Shell.cmd(
                "settings put global hidden_api_policy 1",
                "settings put global hidden_api_blacklist_exemptions '*'",
            ).exec()
            if (forcePermissive) {
                Shell.cmd("setenforce 0").exec()
                Log.i(TAG, "SELinux: forced global permissive (fallback enabled)")
            } else {
                Log.i(TAG, "SELinux: left enforcing (policy tool: ${detectPolicyTool()})")
            }
            val enforceResult = Shell.cmd("getenforce").exec()
            Log.i(TAG, "SELinux: ${enforceResult.out.joinToString("")}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "exemptHiddenApis failed", e)
            return false
        }
    }

    /** Detects which SELinux policy-injection tool is available (for targeted allow rules). */
    fun detectPolicyTool(): String {
        return try {
            val out = Shell.cmd(
                "command -v magiskpolicy ksud sepolicy-inject 2>/dev/null | head -1"
            ).exec().out.firstOrNull()?.trim().orEmpty()
            out.ifEmpty { "none" }
        } catch (_: Exception) { "none" }
    }

    /** Returns recent SELinux `avc: denied` lines (kernel log + logcat) for diagnosis. */
    fun collectSelinuxDenials(): List<String> {
        return try {
            Shell.cmd(
                "(dmesg 2>/dev/null; logcat -d -b all 2>/dev/null) | " +
                    "grep -iE 'avc: *denied' | tail -60"
            ).exec().out.filter { it.isNotBlank() }
        } catch (e: Exception) {
            listOf("collectSelinuxDenials failed: ${e.message}")
        }
    }

    // ── Shell-based diagnostics ───────────────────────────────────────────

    fun shellCheckService(name: String): String {
        return try {
            val result = Shell.cmd("service check $name").exec()
            result.out.joinToString("\n").ifEmpty { "(no output)" }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun shellListServices(): List<String> {
        return try {
            val result = Shell.cmd("service list").exec()
            result.out.filter {
                it.contains("virtual", ignoreCase = true) ||
                it.contains("crosvm", ignoreCase = true) ||
                it.contains("display", ignoreCase = true)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isServiceAvailable(): Boolean {
        return try {
            val result = Shell.cmd("service check crosvm_display").exec()
            result.out.any { it.contains("found") }
        } catch (_: Exception) { false }
    }

    fun listRelevantServices(): List<String> = shellListServices()

    /**
     * Reads the GPU backend from the running crosvm process's command line — crosvm-zero-change.
     *
     * The backend is fixed at launch via `--gpu backend=<x>` and is NOT exposed over the display
     * AIDL, so we parse the launch args of the crosvm process that registered [serviceName].
     * crosvm starts AFTER this app, so this is only meaningful once a display connection exists.
     *
     * Resolves PIDs with `pidof crosvm` (targeted — no full /proc scan), then reads each
     * /proc/<pid>/cmdline directly (NUL-separated) rather than `ps`, which truncates the long
     * crosvm command line and would drop `--gpu`. crosvm forks device/child processes; those that
     * are also named "crosvm" inherit the parent's argv (same cmdline → same answer), and any that
     * don't carry the launch args are filtered out by the `--android-display-service` match. So we
     * take the first matching line. Returns the backend name, "default" if `--gpu` is present
     * without an explicit backend, or null if no matching process / no `--gpu`.
     */
    fun readGpuBackend(serviceName: String = "crosvm_display"): String? {
        return try {
            // For each crosvm PID, print its full cmdline (args space-joined) only if it carries
            // the display-service launch flag — i.e. the VMM process, not an unrelated child.
            val cmd = "for p in \$(pidof crosvm 2>/dev/null); do " +
                "c=\$(tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null); " +
                "case \"\$c\" in *--android-display-service*) echo \"\$c\";; esac; " +
                "done"
            val result = Shell.cmd(cmd).exec()
            val line = result.out.firstOrNull { it.contains(serviceName) }
                ?: result.out.firstOrNull()
                ?: return null
            parseGpuBackend(line)
        } catch (e: Exception) {
            Log.e(TAG, "readGpuBackend failed", e)
            null
        }
    }

    /** Extracts `backend=` from a crosvm command line's `--gpu` argument. See [readGpuBackend]. */
    private fun parseGpuBackend(cmdline: String): String? {
        val tokens = cmdline.trim().split(Regex("\\s+"))
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "--gpu" || t.startsWith("--gpu=")) {
                val params = if (t.startsWith("--gpu=")) t.removePrefix("--gpu=")
                    else tokens.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }
                val backend = params?.split(",")
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("backend=") }
                    ?.removePrefix("backend=")
                return backend ?: "default"
            }
            i++
        }
        return null
    }

    // ── Connect via RootService ──────────────────────────────────────────

    var lastError: String? = null
        private set

    /**
     * Binds to RootDisplayService and returns true once bound.
     * Does NOT call waitForDisplayBinder() — that is deferred to [waitForDisplayBinder].
     * MUST be called from a background thread (e.g. Dispatchers.IO).
     */
    fun bindRootServiceOnly(context: Context): Boolean {
        lastError = null
        Log.i(TAG, "bindRootServiceOnly: binding…")
        val svc = bindRootService(context)
        if (svc == null) {
            lastError = "Failed to bind RootDisplayService (timeout or error)"
            Log.e(TAG, lastError!!)
            return false
        }
        Log.i(TAG, "bindRootServiceOnly OK")
        return true
    }

    /**
     * Calls waitForDisplayBinder() in the root process, blocking until crosvm's
     * display binder is available.  Can be called from ANY thread (including main).
     * Returns the raw IBinder (caller wraps it as ICrosvmAndroidDisplayService).
     */
    fun waitForDisplayBinder(): IBinder? {
        val svc = rootServiceBinder ?: run {
            Log.e(TAG, "waitForDisplayBinder: root service not bound")
            return null
        }
        return try {
            Log.i(TAG, "waitForDisplayBinder: calling root service (blocks until crosvm)…")
            val b = svc.waitForDisplayBinder()
            Log.i(TAG, "waitForDisplayBinder: got binder=$b")
            b
        } catch (e: Exception) {
            Log.e(TAG, "waitForDisplayBinder failed", e)
            null
        }
    }

    /**
     * Obtains the host-end input socket FDs from the root process. The root service binds
     * unix sockets that crosvm connects to via `--input ...[path=...]`; this returns the
     * accepted host ends. Channels crosvm hasn't connected yet are null. Returns null if the
     * root service isn't bound or the call fails.
     */
    fun getInputSockets(): Array<ParcelFileDescriptor?>? {
        val svc = rootServiceBinder ?: run {
            Log.e(TAG, "getInputSockets: root service not bound")
            return null
        }
        return try {
            svc.getInputSockets()
        } catch (e: Exception) {
            Log.e(TAG, "getInputSockets failed", e)
            null
        }
    }

    /**
     * Step 1: Bind to RootDisplayService.
     * RootService.bind() MUST be called on the main thread.
     * This method posts the bind call to the main handler and blocks
     * the calling thread until the connection is established.
     *
     * Call from a background thread (e.g. Dispatchers.IO).
     */
    private fun bindRootService(context: Context): IRootDisplayService? {
        val latch = CountDownLatch(1)
        var rootSvc: IRootDisplayService? = null

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "RootDisplayService connected")
                rootSvc = IRootDisplayService.Stub.asInterface(service)
                rootServiceBinder = rootSvc
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "RootDisplayService disconnected")
                rootServiceBinder = null
            }
        }

        val intent = Intent(context, RootDisplayService::class.java)

        // RootService.bind() must be called on the main thread
        mainHandler.post {
            try {
                RootService.bind(intent, conn)
            } catch (e: Exception) {
                Log.e(TAG, "RootService.bind failed", e)
                latch.countDown() // unblock with null
            }
        }

        // Block this (IO) thread until connected or timeout
        if (!latch.await(30, TimeUnit.SECONDS)) {
            Log.e(TAG, "RootDisplayService bind timeout (30s)")
            return null
        }
        return rootSvc
    }

    /**
     * Binds to RootDisplayService (root process), which looks up the "crosvm_display"
     * service registered directly by standalone crosvm.
     * Blocks until crosvm's display service is available.
     *
     * MUST be called from a background thread (e.g. Dispatchers.IO).
     */
    fun connectAndWaitForDisplay(context: Context): Boolean {
        lastError = null

        // Step 1: Bind to RootDisplayService (bind on main thread, wait here)
        Log.i(TAG, "Step 1: Binding to RootDisplayService...")
        val svc = bindRootService(context)
        if (svc == null) {
            lastError = "Failed to bind RootDisplayService (timeout or error)"
            Log.e(TAG, lastError!!)
            return false
        }
        Log.i(TAG, "Step 1 OK: RootDisplayService bound")

        // Step 2: Call waitForDisplayBinder() in the root process
        try {
            Log.i(TAG, "Step 2: Calling waitForDisplayBinder() in root process (blocks until crosvm connects)...")
            val displayBinder = svc.waitForDisplayBinder()
            if (displayBinder == null) {
                lastError = "waitForDisplayBinder() returned null — crosvm not connected?"
                Log.e(TAG, lastError!!)
                return false
            }
            displayService = ICrosvmAndroidDisplayService.Stub.asInterface(displayBinder)
            Log.i(TAG, "Step 2 OK: Got ICrosvmAndroidDisplayService from root process")
            return true
        } catch (e: DeadObjectException) {
            lastError = "RootService died: ${e.message}"
            Log.e(TAG, lastError!!, e)
            return false
        } catch (e: SecurityException) {
            lastError = "Permission denied even as root: ${e.message}"
            Log.e(TAG, lastError!!, e)
            return false
        } catch (e: RemoteException) {
            lastError = "RPC failed: ${e::class.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
            return false
        } catch (e: Exception) {
            lastError = "Unexpected error: ${e::class.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
            return false
        }
    }

    fun setSurface(surface: Surface, forCursor: Boolean = false): String? {
        val svc = displayService ?: return "Display service not connected"
        return try {
            if (!surface.isValid) {
                Log.w(TAG, "setSurface: surface is NOT valid! (forCursor=$forCursor)")
                return "Surface is not valid"
            }
            Log.i(TAG, "setSurface: sending (forCursor=$forCursor)")
            svc.setSurface(surface, forCursor)
            Log.i(TAG, "setSurface OK (forCursor=$forCursor)")
            null
        } catch (e: IllegalArgumentException) {
            // Known issue — reference DisplayProvider also gets this sometimes
            Log.e(TAG, "setSurface IllegalArgumentException", e)
            "setSurface failed: IllegalArgumentException"
        } catch (e: DeadObjectException) {
            Log.e(TAG, "setSurface: display service died", e)
            "Display service is dead"
        } catch (e: Exception) {
            Log.e(TAG, "setSurface failed", e)
            "setSurface failed: ${e::class.simpleName}: ${e.message}"
        }
    }

    fun removeSurface(forCursor: Boolean = false): String? {
        val svc = displayService ?: return "Not connected"
        return try { svc.removeSurface(forCursor); null }
        catch (e: DeadObjectException) { null }
        catch (e: RemoteException) { "removeSurface failed: ${e.message}" }
    }

    // ── Cursor stream ──────────────────────────────────────────────────────

    fun setupCursorStream(): ParcelFileDescriptor? {
        val svc = displayService ?: return null
        return try {
            val pfds = ParcelFileDescriptor.createSocketPair()
            svc.setCursorStream(pfds[1])
            pfds[1].close()
            cursorStreamPfd = pfds[0]
            Log.i(TAG, "Cursor stream set up")
            pfds[0]
        } catch (e: Exception) {
            Log.e(TAG, "setupCursorStream failed", e)
            null
        }
    }

    // ── Save/restore frame ────────────────────────────────────────────────

    fun saveFrame(forCursor: Boolean = false): String? {
        val svc = displayService ?: return "Not connected"
        return try { svc.saveFrameForSurface(forCursor); null }
        catch (_: DeadObjectException) { null }
        catch (e: Exception) { "saveFrame failed: ${e.message}" }
    }

    fun drawSavedFrame(forCursor: Boolean = false): String? {
        val svc = displayService ?: return "Not connected"
        return try { svc.drawSavedFrameForSurface(forCursor); null }
        catch (_: DeadObjectException) { null }
        catch (e: Exception) { "drawSavedFrame failed: ${e.message}" }
    }

    // ── Display config ─────────────────────────────────────────────────────

    fun getDisplayConfig(): android.crosvm.DisplayConfig? {
        val svc = displayService ?: return null
        return try {
            svc.displayConfig
        } catch (e: Exception) {
            Log.e(TAG, "getDisplayConfig failed", e)
            null
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    fun disconnect() {
        try { cursorStreamPfd?.close() } catch (_: Exception) {}
        cursorStreamPfd = null
        displayService = null
        rootServiceBinder = null
        Log.i(TAG, "Disconnected")
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    fun diagnoseConnection(): String {
        val sb = StringBuilder()
        sb.append("=== CONNECTION DIAGNOSTICS ===\n")

        // Check if crosvm's display service is registered
        sb.append("1. crosvm_display service status:\n")
        val displayServiceStatus = shellCheckService("crosvm_display")
        sb.append("   $displayServiceStatus\n")

        // Check if crosvm is running
        sb.append("2. Crosvm process status:\n")
        try {
            val psResult = Shell.cmd("ps | grep -i crosvm").exec()
            if (psResult.out.isNotEmpty()) {
                sb.append("   Found crosvm processes:\n")
                psResult.out.forEach { sb.append("   $it\n") }
            } else {
                sb.append("   ❌ NO CROSVM PROCESS FOUND\n")
            }
        } catch (e: Exception) {
            sb.append("   Error checking crosvm: ${e.message}\n")
        }

        // List relevant services
        sb.append("3. Related services:\n")
        val services = shellListServices()
        if (services.isEmpty()) {
            sb.append("   (none)\n")
        } else {
            services.forEach { sb.append("   $it\n") }
        }

        // Check display service connection status
        sb.append("4. Display service status:\n")
        if (displayService != null) {
            sb.append("   ✅ Connected to ICrosvmAndroidDisplayService\n")
        } else {
            sb.append("   ❌ NOT connected\n")
        }

        sb.append("5. Last error: ${lastError ?: "none"}\n")

        return sb.toString()
    }

    fun diagnosticsLog(): String {
        val diagnostics = diagnoseConnection()
        Log.i(TAG, "\n$diagnostics")
        return diagnostics
    }
}
