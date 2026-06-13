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

        private const val DIRECT_SERVICE_NAME = "crosvm_display"

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
        // Open the input listeners as early as possible: crosvm connects to them at ITS
        // startup, so they must exist before crosvm launches. Binding this root service first
        // (then launching crosvm with InputSocketHost.LAUNCH_ARGS) yields the right ordering.
        inputSocketHost.ensureListening()
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "onBind: root service starting, uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}")
        return object : IRootDisplayService.Stub() {
            override fun waitForDisplayBinder(): IBinder? {
                return doWaitForDisplayBinder()
            }

            override fun getInputChannelsReady(): BooleanArray {
                return inputSocketHost.readyChannels()
            }

            override fun writeInput(channel: Int, data: ByteArray): Boolean {
                return inputSocketHost.write(channel, data)
            }
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        inputSocketHost.close()
        return super.onUnbind(intent)
    }

    private fun doWaitForDisplayBinder(): IBinder? {
        Log.i(TAG, "waitForDisplayBinder: uid=${android.os.Process.myUid()}")

        // Look up the "crosvm_display" service that standalone crosvm registers directly to
        // the service manager (via --android-display-service crosvm_display).
        Log.i(TAG, "Trying direct ServiceManager lookup for '$DIRECT_SERVICE_NAME'...")
        val directBinder = smCheckService(DIRECT_SERVICE_NAME)
        if (directBinder != null) {
            Log.i(TAG, "OK: got display binder directly from ServiceManager")
            return directBinder
        }
        Log.i(TAG, "Not found, waiting up to 5s...")
        val directWait = smWaitForServiceWithTimeout(DIRECT_SERVICE_NAME, 5000L)
        if (directWait != null) {
            Log.i(TAG, "OK: got display binder via waitForService")
            return directWait
        }

        Log.e(TAG, "'$DIRECT_SERVICE_NAME' not found — is crosvm running with --android-display-service $DIRECT_SERVICE_NAME?")
        return null
    }
}
