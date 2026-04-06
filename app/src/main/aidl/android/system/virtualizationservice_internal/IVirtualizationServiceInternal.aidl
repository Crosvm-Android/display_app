/*
 * Stub of IVirtualizationServiceInternal.
 *
 * IMPORTANT: Method order MUST match the real AIDL exactly because binder
 * transaction codes are assigned sequentially. crosvm (NDK client) will call
 * setDisplayService with transaction code FIRST_CALL_TRANSACTION + 18.
 *
 * Methods we don't implement still need placeholder declarations so the
 * compiler assigns the correct transaction codes to the display methods.
 */
package android.system.virtualizationservice_internal;

interface IVirtualizationServiceInternal {
    // 0
    void removeMemlockRlimit();

    // 1 – nested parcelable removed, use a simple replacement
    // VmContext allocateVmContext() → we declare it but stub will throw
    @nullable IBinder allocateVmContext();

    // 2
    void registerVirtualMachine(int cid, IBinder vm);

    // 3
    void unregisterVirtualMachine(int cid);

    // 4
    void atomVmBooted(IBinder atom);

    // 5
    void atomVmCreationRequested(IBinder atom);

    // 6
    void atomVmExited(IBinder atom);

    // 7
    void forwardAtom(IBinder atom, int requesterUid, String vmIdentifier);

    // 8
    @nullable IBinder[] debugListVms();

    // 9
    @nullable IBinder[] requestAttestation(in byte[] csr, int requesterUid, boolean testMode);

    // 10
    void enableTestAttestation();

    // 11
    boolean isRemoteAttestationSupported();

    // 12
    @nullable IBinder[] getAssignableDevices();

    // 13
    @nullable IBinder[] bindDevicesToVfioDriver(in String[] devices);

    // 14
    @nullable ParcelFileDescriptor getDtboFile();

    // 15
    @nullable byte[] allocateInstanceId();

    // 16
    void removeVmInstance(in byte[] instanceId);

    // 17
    void claimVmInstance(in byte[] instanceId);

    // 18 ← THE ONE CROSVM CALLS
    void setDisplayService(IBinder ibinder);

    // 19
    void clearDisplayService();

    // 20
    @nullable IBinder waitDisplayService();

    // 21
    @nullable ParcelFileDescriptor createTapInterface(String ifaceNameSuffix);

    // 22
    void deleteTapInterface(in ParcelFileDescriptor tapFd);

    // 23
    void claimSecretkeeperEntry(in byte[] id);
}
