package com.kancy.display_test

import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService as RootServiceBase

/**
 * A service that runs as root (uid=0) via libsu RootService.
 *
 * Because it runs as root:
 *   - SELinux context is u:r:magisk:s0 or u:r:su:s0 (binder calls allowed)
 *   - Permission checks (MANAGE_VIRTUAL_MACHINE etc.) pass (uid=0 bypasses)
 *
 * The app binds to this service and calls waitForDisplayBinder() which:
 *   1. Gets the virtualizationservice binder from ServiceManager
 *   2. Calls waitDisplayService() — either via the system's own AIDL proxy class
 *      (correct transaction codes for this device) or by probing transaction codes
 *   3. Returns the ICrosvmAndroidDisplayService binder to the app
 */
class RootDisplayService : RootServiceBase() {

    companion object {
        private const val TAG = "RootDisplayService"

        private const val DIRECT_SERVICE_NAME = "crosvm_display"
        private const val VIRT_SERVICE_NAME = "android.system.virtualizationservice"
        private const val DESCRIPTOR =
            "android.system.virtualizationservice_internal.IVirtualizationServiceInternal"

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

        /**
         * Try to use the system's own IVirtualizationServiceInternal class
         * (compiled into the device's framework) which has the correct
         * transaction codes for THIS device.
         */
        private fun trySystemAidlProxy(virtBinder: IBinder): IBinder? {
            try {
                val clazz = Class.forName(
                    "android.system.virtualizationservice_internal.IVirtualizationServiceInternal\$Stub"
                )
                val asInterface = clazz.getMethod("asInterface", IBinder::class.java)
                val proxy = asInterface.invoke(null, virtBinder) ?: return null
                Log.i(TAG, "Got system AIDL proxy: ${proxy.javaClass.name}")

                // Call waitDisplayService() on the proxy
                val waitMethod = proxy.javaClass.getMethod("waitDisplayService")
                Log.i(TAG, "Calling system proxy waitDisplayService()...")
                val result = waitMethod.invoke(proxy)
                return result as? IBinder
            } catch (e: Exception) {
                Log.w(TAG, "System AIDL proxy approach failed: ${e::class.simpleName}: ${e.message}")
                // Unwrap InvocationTargetException to see the real cause
                val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
                Log.w(TAG, "  Root cause: ${cause?.javaClass?.name}: ${cause?.message}")
                return null
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "onBind: root service starting, uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}")
        return object : IRootDisplayService.Stub() {
            override fun waitForDisplayBinder(): IBinder? {
                return doWaitForDisplayBinder()
            }
        }
    }

    private fun doWaitForDisplayBinder(): IBinder? {
        Log.i(TAG, "waitForDisplayBinder: uid=${android.os.Process.myUid()}")

        // Step 0: Try direct ServiceManager lookup (standalone crosvm registers as "crosvm_display").
        Log.i(TAG, "Step 0: Trying direct ServiceManager lookup for 'crosvm_display'...")
        val directBinder = smCheckService(DIRECT_SERVICE_NAME)
        if (directBinder != null) {
            Log.i(TAG, "Step 0 OK: got display binder directly from ServiceManager")
            return directBinder
        }
        Log.i(TAG, "Step 0: not found, waiting up to 5s...")
        val directWait = smWaitForServiceWithTimeout(DIRECT_SERVICE_NAME, 5000L)
        if (directWait != null) {
            Log.i(TAG, "Step 0 OK: got display binder via waitForService")
            return directWait
        }
        Log.i(TAG, "Step 0: 'crosvm_display' not in ServiceManager, falling back to VirtualizationService...")

        // Step 1: Get virtualizationservice binder via ServiceManager.
        Log.i(TAG, "Step 1: Getting virtualizationservice binder...")
        val virtBinder: IBinder = smWaitForService(VIRT_SERVICE_NAME)
            ?: smCheckService(VIRT_SERVICE_NAME)
            ?: run {
                Log.e(TAG, "virtualizationservice not found via ServiceManager!")
                return null
            }

        val desc = try { virtBinder.interfaceDescriptor } catch (_: Exception) { null }
        Log.i(TAG, "Step 1 OK: got binder, descriptor='$desc'")
        val descriptor = desc ?: DESCRIPTOR

        // Step 2: Call waitDisplayService.
        // Try Code 18 first — confirmed working on this device from previous probing.
        Log.i(TAG, "Step 2: Calling waitDisplayService (code=18 direct)...")
        val result = tryRawTransact(virtBinder, descriptor, 18, blockingProbe = true)
        if (result != null) {
            Log.i(TAG, "Step 2 OK: got display binder via code=18")
            return result
        }

        // Fallback: try system AIDL proxy
        Log.i(TAG, "  Code 18 failed, trying system AIDL proxy...")
        val proxyResult = trySystemAidlProxy(virtBinder)
        if (proxyResult != null) {
            Log.i(TAG, "Step 2 OK: got display binder via system proxy")
            return proxyResult
        }

        // Last resort: probe other codes (skip 8 which is a different blocking method)
        Log.i(TAG, "  Probing other codes...")
        for (code in listOf(19, 20, 21, 22, 23, 24, 25, 17, 16, 15, 14, 13)) {
            val r = tryRawTransactWithTimeout(virtBinder, descriptor, code, timeoutMs = 2000L)
            if (r != null) {
                Log.i(TAG, "Step 2 OK: got display binder via code=$code")
                return r
            }
        }

        Log.e(TAG, "All approaches failed to get display binder")
        return null
    }

    /**
     * Try a raw transact call with a timeout.
     * Only returns non-null binders - void methods or methods returning null are skipped.
     * For blocking methods, use a longer timeout but not excessively long.
     */
    private fun tryRawTransactWithTimeout(
        virtBinder: IBinder,
        descriptor: String,
        code: Int,
        timeoutMs: Long
    ): IBinder? {
        // Use a mutable box to capture the result from the thread
        var result: IBinder? = null
        val thread = Thread {
            result = tryRawTransact(virtBinder, descriptor, code, blockingProbe = true)
        }
        thread.name = "ProbeCode$code"
        thread.isDaemon = true
        thread.start()

        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Log.w(TAG, "  Code $code: interrupted")
            return null
        }

        if (thread.isAlive) {
            Log.w(TAG, "  Code $code: timeout (blocking) - continuing to wait...")
            // For blocking codes, don't give up immediately.
            // This might be waitDisplayService() waiting for crosvm to connect.
            // Keep polling the result but with a reasonable total time limit (15s).
            val maxWaitMs = 15000L // Total max wait: 15 seconds
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (System.currentTimeMillis() < deadline && thread.isAlive) {
                try {
                    thread.join(500) // Check every 500ms if thread completed
                } catch (e: InterruptedException) {
                    break
                }
                // Check if result was set
                if (result != null) {
                    Log.i(TAG, "  Code $code: SUCCESS! Got binder from blocking call after ${System.currentTimeMillis() - (deadline - maxWaitMs)}ms")
                    return result
                }
            }

            if (thread.isAlive) {
                Log.w(TAG, "  Code $code: still blocking after 15s total, giving up and trying next code")
                return null
            }
        }

        // Return the result if it was set by the thread
        if (result != null) {
            Log.i(TAG, "  Code $code: SUCCESS! Got non-null binder")
            return result
        }
        return null
    }

    private fun tryRawTransact(virtBinder: IBinder, descriptor: String, code: Int, blockingProbe: Boolean = false): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            val ok = virtBinder.transact(code, data, reply, 0)
            if (!ok) {
                if (blockingProbe) Log.w(TAG, "  Code $code: transact returned false")
                return null
            }

            // Try to read exception and binder
            try {
                reply.readException()
                val binder = reply.readStrongBinder()
                // Only log and return if binder is non-null
                if (binder != null) {
                    Log.i(TAG, "  Code $code: SUCCESS! Got non-null binder")
                    return binder
                } else {
                    if (blockingProbe) Log.w(TAG, "  Code $code: returned null binder")
                    return null
                }
            } catch (e: Exception) {
                if (blockingProbe && e !is java.io.EOFException) {
                    Log.w(TAG, "  Code $code: read failed - ${e::class.simpleName}")
                }
                return null
            }
        } catch (e: Exception) {
            if (blockingProbe) {
                Log.w(TAG, "  Code $code: transact failed - ${e::class.simpleName}")
            }
            return null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
