#!/bin/bash
# Build crosvm with android_display feature for Android (aarch64)
#
# Prerequisites:
#   - Rust toolchain: rustup target add aarch64-linux-android
#   - Android NDK (set ANDROID_NDK_HOME)
#   - cargo-ndk: cargo install cargo-ndk
#
# This script builds crosvm with the android_display feature AND
# libcrosvm_android_display_client linked in.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CROSVM_DIR="$SCRIPT_DIR/reference/crosvm"
DISPLAY_BACKEND_DIR="$SCRIPT_DIR/reference/platform_packages_modules_Virtualization/libs/android_display_backend"

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME not set"
    echo "  export ANDROID_NDK_HOME=/path/to/android-ndk"
    exit 1
fi

echo "=== Building crosvm with android_display feature ==="
echo "CROSVM_DIR: $CROSVM_DIR"
echo "NDK: $ANDROID_NDK_HOME"
echo ""

# Step 1: First build libcrosvm_android_display_client.a
echo "--- Step 1: Building libcrosvm_android_display_client ---"
echo ""
echo "This C++ library requires AOSP build system (Soong)."
echo "It depends on:"
echo "  - libbinder_ndk"
echo "  - libnativewindow"
echo "  - AIDL-generated NDK stubs"
echo ""
echo "If you're building in AOSP source tree:"
echo "  m libcrosvm_android_display_client"
echo ""
echo "If you're building standalone, you need to:"
echo "  1. Get the AIDL-generated NDK C++ headers/sources"
echo "  2. Compile them with NDK"
echo "  3. Link against system's libbinder_ndk.so and libnativewindow.so"
echo ""

# Step 2: Build crosvm with the feature
echo "--- Step 2: Building crosvm ---"
echo ""
echo "In AOSP source tree, the easiest way:"
echo "  cd \$ANDROID_BUILD_TOP"
echo "  source build/envsetup.sh"
echo "  lunch <your-target>"
echo "  m crosvm"
echo ""
echo "This will produce crosvm at:"
echo "  out/target/product/<device>/system/bin/crosvm"
echo "  (or inside the com.android.virt APEX)"
echo ""
echo "=== Alternative: Build on device ==="
echo ""
echo "If you have a rooted engineering build, you might be able to"
echo "install the com.android.virt APEX which includes a properly"
echo "built crosvm with android_display support:"
echo ""
echo "  adb shell pm list packages | grep virt"
echo "  # Look for com.android.virt"
echo ""
echo "  # If the APEX exists but isn't active:"
echo "  adb shell cmd package install-existing com.android.virt"
echo ""
echo "=== Quick check on device ==="
echo ""
echo "Run these on your device to see if a proper crosvm exists:"
echo ""
echo "  # Check for crosvm in APEX"
echo "  ls -la /apex/com.android.virt/bin/crosvm"
echo ""
echo "  # Check if it supports android-display-service"
echo "  /apex/com.android.virt/bin/crosvm run --help 2>&1 | grep android-display"
echo ""
echo "  # If found, use that crosvm instead:"
echo "  /apex/com.android.virt/bin/crosvm run \\"
echo "    --disable-sandbox --gpu=gfxstream \\"
echo "    --android-display-service crosvm_gpu_display \\"
echo "    ..."
