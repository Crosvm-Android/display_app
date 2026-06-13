package com.kancy.display_test;

/**
 * AIDL interface for the RootService that:
 *   1. Obtains the display binder from the service manager (as root, using hidden APIs).
 *   2. Owns the host-side input sockets crosvm connects to (--input ...[path=...]) AND writes
 *      events to them on the app's behalf.
 *
 * Input is written by the root process (which runs in a permissive su/magisk domain) rather than
 * by the app process (untrusted_app, enforcing). The app encodes evdev bytes and ships them here;
 * root does the actual socket write. This avoids an untrusted_app -> unix_stream_socket SELinux
 * crossing, so SELinux can stay enforcing (no global setenforce 0).
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
     * Per-channel connection status, indexed by InputSocketHost channel constants
     * [MULTITOUCH, KEYBOARD, MOUSE, SWITCHES]: true once crosvm has connected that socket.
     * Informational (for the UI); writeInput consults the live socket regardless.
     */
    boolean[] getInputChannelsReady();

    /**
     * Writes pre-encoded evdev bytes (8-byte records: type:u16 LE, code:u16 LE, value:i32 LE)
     * to the given input channel's socket, in the root process. Returns true if written, false
     * if that channel isn't connected yet or the write failed.
     */
    boolean writeInput(int channel, in byte[] data);
}
