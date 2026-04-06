/*
 * Stub header for android/binder_manager.h
 *
 * These functions exist in libbinder_ndk.so on every Android device
 * but are not exposed through the public NDK headers (NDK 27).
 * We declare them here so we can compile against them and link
 * at runtime against the device's libbinder_ndk.so.
 */
#pragma once

#include <android/binder_ibinder.h>
#include <android/binder_status.h>

__BEGIN_DECLS

/**
 * Register a binder service with the ServiceManager.
 * Requires appropriate permissions (root / selinux).
 */
binder_status_t AServiceManager_addService(AIBinder* binder, const char* instance);

/**
 * Wait for a binder service to appear in ServiceManager.
 * Blocks until the service is registered.
 */
AIBinder* AServiceManager_waitForService(const char* instance);

/**
 * Check if a service is registered. Returns nullptr if not found.
 */
AIBinder* AServiceManager_checkService(const char* instance);

__END_DECLS
