package com.kancy.display_test;

import android.os.ParcelFileDescriptor;

/**
 * AIDL interface for the RootService that:
 *   1. Obtains the display binder from the service manager (as root, using hidden APIs).
 *   2. Provides host-side input sockets that crosvm connects to via --input ... [path=...].
 */
interface IRootDisplayService {
    /**
     * Calls ServiceManager.waitForService("crosvm_display") — the service that standalone
     * crosvm registers directly via --android-display-service crosvm_display.
     * Runs as root (uid=0).
     * Returns the ICrosvmAndroidDisplayService binder, or null if not found.
     */
    IBinder waitForDisplayBinder();

    /**
     * Returns the host-side socket FDs for input forwarding. The root service binds unix
     * domain sockets at well-known paths; crosvm connects to them via --input ...[path=...].
     * Once crosvm connects, this returns the host end. Each FD is an OutputStream sink for
     * 8-byte evdev records (type:u16 LE, code:u16 LE, value:i32 LE).
     *
     * Returns a 4-element array: [multitouch, keyboard, mouse, switches], or null on error.
     * Caller should write to these FDs via EvdevEncoder. FDs remain valid until disconnect.
     */
    ParcelFileDescriptor[] getInputSockets();
}
