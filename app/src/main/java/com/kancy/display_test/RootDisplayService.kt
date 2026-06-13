package com.kancy.display_test

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService as RootServiceBase

/**
 * A service that runs as root (uid=0) via libsu RootService.
 *
 * Because it runs as root:
 *   - SELinux context is u:r:magisk:s0 or u:r:su:s0 (binder calls allowed)
 *   - It can call the hidden ServiceManager APIs to look up services
 *
 * The app binds to this service and calls waitForDisplayBinder() which looks up the
 * "crosvm_display" service that standalone crosvm registers directly to the service
 * manager (via --android-display-service), and returns its ICrosvmAndroidDisplayService
 * binder to the app.
 */
class RootDisplayService : RootServiceBase() {

    companion object {
        private const val TAG = "RootDisplayService"

        // ServiceManager access via reflection (hidden API)
        private val smClass: Class<*> by lazy { Class.forName("android.os.ServiceManager") }

        private fun smWaitForService(name: String): IBinder? {
            return try {
                val m = smClass.getMethod("waitForService", String::class.java)
                m.invoke(null, name) as? IBinder
            } catch (e: Exception) {
                Log.w(TAG, "waitForService reflection failed: ${e.message}")
                null
            }
        }

        private fun smCheckService(name: String): IBinder? {
            return try {
                val m = smClass.getMethod("checkService", String::class.java)
                m.invoke(null, name) as? IBinder
            } catch (e: Exception) {
                Log.w(TAG, "checkService reflection failed: ${e.message}")
                null
            }
        }

        private fun smWaitForServiceWithTimeout(name: String, timeoutMs: Long): IBinder? {
            var result: IBinder? = null
            val thread = Thread {
                result = smWaitForService(name)
            }
            thread.name = "WaitSvc-$name"
            thread.isDaemon = true
            thread.start()
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) { }
            return result
        }
    }

    private val inputSocketHost = InputSocketHost()

    override fun onCreate() {
        super.onCreate()
        // Input listeners are now per-VM and opened on demand via ensureInputListening(vmKey):
        // the root service can't know which VM names to listen for until the app asks. The app
        // calls ensureInputListening before launching that VM's crosvm to preserve the ordering
        // (listeners must exist before crosvm connects at its startup).
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "onBind: root service starting, uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}")
        return object : IRootDisplayService.Stub() {
            override fun waitForDisplayBinder(serviceName: String): IBinder? {
                return doWaitForDisplayBinder(serviceName)
            }

            override fun ensureInputListening(vmKey: String) {
                inputSocketHost.ensureListening(vmKey)
            }

            override fun getInputChannelsReady(vmKey: String): BooleanArray {
                return inputSocketHost.readyChannels(vmKey)
            }

            override fun writeInput(vmKey: String, channel: Int, data: ByteArray): Boolean {
                return inputSocketHost.write(vmKey, channel, data)
            }
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        inputSocketHost.closeAll()
        return super.onUnbind(intent)
    }

    private fun doWaitForDisplayBinder(serviceName: String): IBinder? {
        Log.i(TAG, "waitForDisplayBinder('$serviceName'): uid=${android.os.Process.myUid()}")

        // Look up the display service that standalone crosvm registers directly to the service
        // manager (via --android-display-service <serviceName>). serviceName is the per-VM key.
        Log.i(TAG, "Trying direct ServiceManager lookup for '$serviceName'...")
        val directBinder = smCheckService(serviceName)
        if (directBinder != null) {
            Log.i(TAG, "OK: got display binder directly from ServiceManager")
            return directBinder
        }
        Log.i(TAG, "Not found, waiting up to 5s...")
        val directWait = smWaitForServiceWithTimeout(serviceName, 5000L)
        if (directWait != null) {
            Log.i(TAG, "OK: got display binder via waitForService")
            return directWait
        }

        Log.e(TAG, "'$serviceName' not found — is crosvm running with --android-display-service $serviceName?")
        return null
    }
}
