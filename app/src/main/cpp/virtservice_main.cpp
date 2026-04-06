/*
 * virtservice - Minimal IVirtualizationServiceInternal stub
 *
 * Registers as "android.system.virtualizationservice" to ServiceManager (needs root).
 * Implements only setDisplayService / clearDisplayService / waitDisplayService.
 * All other methods are no-ops.
 *
 * crosvm (NDK C++ AIDL client) calls:
 *   AServiceManager_waitForService("android.system.virtualizationservice")
 *   -> IVirtualizationServiceInternal::fromBinder(...)
 *   -> setDisplayService(ICrosvmAndroidDisplayService binder)
 *
 * App (Java Binder client) calls:
 *   ServiceManager.waitForService("android.system.virtualizationservice")
 *   -> IVirtualizationServiceInternal.Stub.asInterface(binder)
 *   -> waitDisplayService()  [returns the binder crosvm deposited]
 *
 * Compile with NDK, link against: libbinder_ndk (present on every Android device)
 */

#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_process.h>
#include <android/binder_status.h>

#include <android/log.h>
#include <condition_variable>
#include <mutex>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <cstdlib>

#define LOG_TAG "virtservice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ─── Transaction codes ────────────────────────────────────────────────────────
// FIRST_CALL_TRANSACTION = 1; method index 0 → code 1
// Must match IVirtualizationServiceInternal.aidl method order exactly.
static constexpr transaction_code_t TRANSACTION_removeMemlockRlimit      = 1;
static constexpr transaction_code_t TRANSACTION_allocateVmContext        = 2;
static constexpr transaction_code_t TRANSACTION_registerVirtualMachine   = 3;
static constexpr transaction_code_t TRANSACTION_unregisterVirtualMachine = 4;
static constexpr transaction_code_t TRANSACTION_atomVmBooted             = 5;
static constexpr transaction_code_t TRANSACTION_atomVmCreationRequested  = 6;
static constexpr transaction_code_t TRANSACTION_atomVmExited             = 7;
static constexpr transaction_code_t TRANSACTION_forwardAtom              = 8;
static constexpr transaction_code_t TRANSACTION_debugListVms             = 9;
static constexpr transaction_code_t TRANSACTION_requestAttestation       = 10;
static constexpr transaction_code_t TRANSACTION_enableTestAttestation    = 11;
static constexpr transaction_code_t TRANSACTION_isRemoteAttestationSupported = 12;
static constexpr transaction_code_t TRANSACTION_getAssignableDevices     = 13;
static constexpr transaction_code_t TRANSACTION_bindDevicesToVfioDriver  = 14;
static constexpr transaction_code_t TRANSACTION_getDtboFile              = 15;
static constexpr transaction_code_t TRANSACTION_allocateInstanceId       = 16;
static constexpr transaction_code_t TRANSACTION_removeVmInstance         = 17;
static constexpr transaction_code_t TRANSACTION_claimVmInstance          = 18;
static constexpr transaction_code_t TRANSACTION_setDisplayService        = 19;
static constexpr transaction_code_t TRANSACTION_clearDisplayService      = 20;
static constexpr transaction_code_t TRANSACTION_waitDisplayService       = 21;
static constexpr transaction_code_t TRANSACTION_createTapInterface       = 22;
static constexpr transaction_code_t TRANSACTION_deleteTapInterface       = 23;
static constexpr transaction_code_t TRANSACTION_claimSecretkeeperEntry   = 24;

// ─── Interface descriptor ─────────────────────────────────────────────────────
// This MUST match exactly what both NDK (crosvm) and Java (App) clients send.
static const char* kDescriptor =
    "android.system.virtualizationservice_internal.IVirtualizationServiceInternal";

static const char* kServiceName = "android.system.virtualizationservice";

// ─── Shared state ─────────────────────────────────────────────────────────────
static std::mutex              g_mutex;
static std::condition_variable g_cv;
static AIBinder*               g_display_service_binder = nullptr;

// ─── Binder class callbacks ───────────────────────────────────────────────────
static void* binder_onCreate(void* args) { (void)args; return nullptr; }
static void  binder_onDestroy(void* /*userData*/) {}
static bool  binder_onDump(AIBinder* /*b*/, int /*fd*/,
                            const char** /*args*/, uint32_t /*n*/) { return true; }

static AIBinder_Class* gClass = nullptr;

// ─── Helper: write empty exception (STATUS_OK for Java callers) ──────────────
// Java Binder.onTransact reads an exception code first (writeNoException = writeInt32(0))
static inline void writeNoException(AParcel* out) {
    AParcel_writeInt32(out, 0);
}

// ─── Interface token note ─────────────────────────────────────────────────────
// crosvm uses the NDK AIDL-generated proxy. When AIBinder_Class_disableInterfaceTokenHeader
// is set on our class, the NDK machinery does NOT write a token header for outgoing
// calls, AND does NOT expect one for incoming calls. So crosvm's setDisplayService
// parcel contains ONLY the binder argument.
//
// Our App uses the Java AIDL-generated proxy (IVirtualizationServiceInternal.Stub.asInterface).
// Java proxies ALWAYS write an interface token header consisting of:
//   writeInterfaceToken(descriptor)
// which the Stub.onTransact() reads via data.enforceInterface().
// Since we're a native binder with disableInterfaceTokenHeader, the Java-side header
// bytes arrive in our `in` parcel and we must skip them manually for Java callers.
//
// Java Parcel.writeInterfaceToken() writes (in order):
//   int32  strictModePolicy
//   int32  workSourceUid   (added in Android P)
//   String16 descriptor    (int32 char-count, then UTF-16 chars, then 0-pad to 4-byte align)
//
// We skip this block by reading exactly those fields before reading real parameters.
// Buffer used by the string allocator callback below.
static thread_local char g_skip_buf[512];

static void skipJavaInterfaceTokenHeader(const AParcel* in) {
    int32_t dummy = 0;
    AParcel_readInt32(in, &dummy); // strictModePolicy
    AParcel_readInt32(in, &dummy); // workSourceUid
    // String16: read length + chars.  The allocator MUST return a valid buffer;
    // returning nullptr causes STATUS_NO_MEMORY which aborts the transaction.
    AParcel_readString(in, g_skip_buf,
        [](void* ctx, int32_t len) -> char* {
            // If the descriptor fits in our static buffer, use it; otherwise malloc.
            if (len + 1 <= (int32_t)sizeof(g_skip_buf)) {
                return static_cast<char*>(ctx);
            }
            // Oversized — just allocate and leak; this path is unlikely.
            return static_cast<char*>(malloc(len + 1));
        });
}

// ─── onTransact ───────────────────────────────────────────────────────────────
static binder_status_t onTransact(
        AIBinder* /*binder*/, transaction_code_t code,
        const AParcel* in, AParcel* out)
{
    // Both NDK (crosvm) and Java (App) clients write an interface token header.
    // AIBinder_Class_disableInterfaceTokenHeader is set below so the NDK layer
    // will NOT prepend a token for NDK-to-NDK calls; but Java clients always
    // prepend it. We must handle both.
    // Strategy: just don't enforce — we know who's calling based on transaction code.
    // (enforceInterface would reject Java callers if class has token disabled, and
    //  vice versa. Skip enforcement entirely for this stub.)

    switch (code) {

    // ── setDisplayService: crosvm deposits its ICrosvmAndroidDisplayService ──
    case TRANSACTION_setDisplayService: {
        // NDK client (crosvm) does NOT write interface token (we disabled it).
        // But Java clients (App accidentally calling this) would. We just try to
        // read a binder and hope for the best; crosvm is the only real caller.
        AIBinder* binder = nullptr;
        binder_status_t st = AParcel_readNullableStrongBinder(in, &binder);
        if (st != STATUS_OK) {
            LOGE("setDisplayService: AParcel_readNullableStrongBinder failed: %d", st);
            return st;
        }
        LOGI("setDisplayService: got binder=%p", binder);
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            if (g_display_service_binder) {
                AIBinder_decStrong(g_display_service_binder);
            }
            g_display_service_binder = binder; // owns the strong ref from AParcel_read
        }
        g_cv.notify_all();
        writeNoException(out);
        return STATUS_OK;
    }

    // ── clearDisplayService ───────────────────────────────────────────────────
    case TRANSACTION_clearDisplayService: {
        // May be called by crosvm (NDK, no token) or App (Java, has token).
        // We skip the header optimistically; if crosvm calls it the reads are
        // harmless no-ops on an empty-ish parcel.
        skipJavaInterfaceTokenHeader(in);
        LOGI("clearDisplayService called");
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            if (g_display_service_binder) {
                AIBinder_decStrong(g_display_service_binder);
                g_display_service_binder = nullptr;
            }
        }
        g_cv.notify_all();
        writeNoException(out);
        return STATUS_OK;
    }

    // ── waitDisplayService: App waits here until crosvm has connected ─────────
    case TRANSACTION_waitDisplayService: {
        // Called only by App (Java proxy) — skip interface token header.
        skipJavaInterfaceTokenHeader(in);
        LOGI("waitDisplayService: waiting for crosvm...");
        AIBinder* binder = nullptr;
        {
            std::unique_lock<std::mutex> lk(g_mutex);
            g_cv.wait_for(lk, std::chrono::seconds(120),
                          [] { return g_display_service_binder != nullptr; });
            binder = g_display_service_binder;
            if (binder) AIBinder_incStrong(binder);
        }
        LOGI("waitDisplayService: returning binder=%p", binder);
        writeNoException(out);
        AParcel_writeNullableStrongBinder(out, binder);
        if (binder) AIBinder_decStrong(binder);
        return STATUS_OK;
    }

    // ── No-op stubs for all other methods ────────────────────────────────────
    case TRANSACTION_removeMemlockRlimit:
    case TRANSACTION_unregisterVirtualMachine:
    case TRANSACTION_atomVmBooted:
    case TRANSACTION_atomVmCreationRequested:
    case TRANSACTION_atomVmExited:
    case TRANSACTION_enableTestAttestation:
    case TRANSACTION_removeVmInstance:
    case TRANSACTION_claimVmInstance:
    case TRANSACTION_deleteTapInterface:
    case TRANSACTION_claimSecretkeeperEntry:
        writeNoException(out);
        return STATUS_OK;

    case TRANSACTION_allocateVmContext:
        writeNoException(out);
        AParcel_writeNullableStrongBinder(out, nullptr);
        return STATUS_OK;

    case TRANSACTION_registerVirtualMachine:
    case TRANSACTION_forwardAtom:
    case TRANSACTION_createTapInterface:
        writeNoException(out);
        return STATUS_OK;

    case TRANSACTION_isRemoteAttestationSupported:
        writeNoException(out);
        AParcel_writeInt32(out, 0); // false
        return STATUS_OK;

    case TRANSACTION_debugListVms:
    case TRANSACTION_requestAttestation:
    case TRANSACTION_getAssignableDevices:
    case TRANSACTION_bindDevicesToVfioDriver:
        writeNoException(out);
        AParcel_writeInt32(out, -1); // null array
        return STATUS_OK;

    case TRANSACTION_getDtboFile:
        writeNoException(out);
        AParcel_writeInt32(out, 0); // null ParcelFileDescriptor
        return STATUS_OK;

    case TRANSACTION_allocateInstanceId:
        writeNoException(out);
        AParcel_writeInt32(out, -1); // null byte array
        return STATUS_OK;

    default:
        LOGW("Unknown transaction code: %u", code);
        return STATUS_UNKNOWN_TRANSACTION;
    }
}

// ─── main ─────────────────────────────────────────────────────────────────────
int main() {
    LOGI("virtservice starting...");
    LOGI("Service name: %s", kServiceName);
    LOGI("Descriptor:   %s", kDescriptor);

    // Must start thread pool before registering service
    ABinderProcess_setThreadPoolMaxThreadCount(4);
    ABinderProcess_startThreadPool();

    // Define the binder class.
    // We call AIBinder_Class_disableInterfaceTokenHeader so that NDK clients
    // (crosvm using libbinder_ndk AIDL) do NOT prepend a token when calling us.
    // Note: Java clients (our App) ALWAYS prepend a token.
    // For the three methods we care about:
    //   - setDisplayService: only crosvm calls this (NDK) → no token
    //   - waitDisplayService: only App calls this (Java) → has token
    //   - clearDisplayService: only crosvm calls this (NDK) → no token
    // Since we don't call enforceInterface, this mismatch doesn't matter.
    gClass = AIBinder_Class_define(kDescriptor, binder_onCreate, binder_onDestroy, onTransact);
    if (!gClass) {
        LOGE("AIBinder_Class_define failed");
        return 1;
    }
    AIBinder_Class_disableInterfaceTokenHeader(gClass);

    // Create our service instance
    AIBinder* service = AIBinder_new(gClass, nullptr);
    if (!service) {
        LOGE("AIBinder_new failed");
        return 1;
    }

    // Register to ServiceManager — requires root / selinux permission
    binder_status_t st = AServiceManager_addService(service, kServiceName);
    AIBinder_decStrong(service); // ServiceManager holds its own ref now
    if (st != STATUS_OK) {
        LOGE("AServiceManager_addService('%s') failed: status=%d", kServiceName, st);
        return 1;
    }
    LOGI("✅ Registered '%s' to ServiceManager", kServiceName);
    LOGI("Waiting for crosvm to call setDisplayService...");

    // Block here serving binder calls forever
    ABinderProcess_joinThreadPool();
    return 0;
}
