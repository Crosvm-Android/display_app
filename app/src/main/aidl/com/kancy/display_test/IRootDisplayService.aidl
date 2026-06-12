package com.kancy.display_test;

/**
 * AIDL interface for the RootService that obtains the display binder
 * from the service manager (as root, using hidden ServiceManager APIs).
 */
interface IRootDisplayService {
    /**
     * Calls ServiceManager.waitForService("crosvm_display") — the service that standalone
     * crosvm registers directly via --android-display-service crosvm_display.
     * Runs as root (uid=0).
     * Returns the ICrosvmAndroidDisplayService binder, or null if not found.
     */
    IBinder waitForDisplayBinder();
}
