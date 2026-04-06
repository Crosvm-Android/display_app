/*
 * Stub header for android/binder_process.h
 *
 * These functions exist in libbinder_ndk.so on every Android device
 * but are not exposed through the public NDK headers (NDK 27).
 * We declare them here so we can compile against them and link
 * at runtime against the device's libbinder_ndk.so.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

__BEGIN_DECLS

/**
 * Set the maximum number of threads in the binder thread pool.
 * Must be called before ABinderProcess_startThreadPool().
 */
bool ABinderProcess_setThreadPoolMaxThreadCount(uint32_t numThreads);

/**
 * Start the binder thread pool. The current thread is NOT part of the pool.
 */
void ABinderProcess_startThreadPool(void);

/**
 * Block the calling thread, joining it into the binder thread pool.
 * This does not return until the binder driver is shut down.
 */
void ABinderProcess_joinThreadPool(void);

__END_DECLS
