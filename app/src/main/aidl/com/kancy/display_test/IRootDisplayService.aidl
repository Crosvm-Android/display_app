package com.kancy.display_test;

/**
 * AIDL interface for the RootService that obtains the display binder
 * from virtualizationservice (as root, bypassing permission checks).
 */
interface IRootDisplayService {
    /**
     * Calls ServiceManager.waitForService("android.system.virtualizationservice"),
     * then IVirtualizationServiceInternal.waitDisplayService().
     * Runs as root (uid=0) so permission checks pass.
     * Blocks until crosvm deposits its display service binder.
     * Returns the ICrosvmAndroidDisplayService binder.
     */
    IBinder waitForDisplayBinder();
}
