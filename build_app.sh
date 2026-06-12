#!/usr/bin/env bash
#
# One-click build for the display_app Android APK.
#
# Handles the environment setup this machine needs:
#   - locates a JDK 17 (JAVA_HOME)
#   - points Gradle at a writable Android SDK dir and lets AGP auto-download
#     the required platform / build-tools (license is auto-accepted)
#   - forwards the system HTTP(S) proxy to all Gradle JVMs (the JVM does NOT
#     read http_proxy env vars on its own), so dependency downloads work
#
# Usage:
#   ./build_app.sh                # assembleDebug (default)
#   ./build_app.sh assembleRelease # or any other gradle task(s)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GRADLE_TASKS=("${@:-assembleDebug}")

# ── 1. JDK ────────────────────────────────────────────────────────────────────
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
    for cand in \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-1.17.0-openjdk-amd64 \
        /usr/lib/jvm/openjdk-17 \
        /usr/lib/jvm/default-java; do
        if [ -x "$cand/bin/javac" ]; then
            export JAVA_HOME="$cand"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
    echo "ERROR: No JDK found. Install one, e.g.: sudo apt install -y openjdk-17-jdk" >&2
    exit 1
fi
echo "JAVA_HOME=$JAVA_HOME"

# ── 2. Android SDK location (writable, so AGP can auto-download components) ────
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
mkdir -p "$SDK_DIR/licenses"
# Accept the standard SDK license hashes so AGP can install platform/build-tools.
LIC="$SDK_DIR/licenses/android-sdk-license"
for hash in \
    24333f8a63b6825ea9c5514f83c2829b004d1fee \
    8933bad161af4178b1185d1a37fbf41ea5269c55 \
    d56f5187479451eabf01fb78af6dfcb131a6481e; do
    grep -qx "$hash" "$LIC" 2>/dev/null || printf '\n%s\n' "$hash" >> "$LIC"
done
# Copy any distro-provided license too (harmless if absent).
cp -n /usr/lib/android-sdk/licenses/* "$SDK_DIR/licenses/" 2>/dev/null || true
printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
echo "sdk.dir=$SDK_DIR"

# ── 3. Proxy → Gradle JVMs ────────────────────────────────────────────────────
# Prefer https_proxy, fall back to http_proxy. Format: http://host:port
PROXY="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"
GRADLE_PROXY_ARGS=()
if [ -n "$PROXY" ]; then
    hostport="${PROXY#*://}"; hostport="${hostport%/}"
    phost="${hostport%:*}"; pport="${hostport##*:}"
    if [ -n "$phost" ] && [ -n "$pport" ]; then
        echo "Proxy: $phost:$pport"
        GRADLE_PROXY_ARGS=(
            -Dhttp.proxyHost="$phost"  -Dhttp.proxyPort="$pport"
            -Dhttps.proxyHost="$phost" -Dhttps.proxyPort="$pport"
            -Dhttp.nonProxyHosts="localhost|127.0.0.1|::1"
        )
    fi
fi

# ── 4. Build ──────────────────────────────────────────────────────────────────
echo "Running: gradlew ${GRADLE_TASKS[*]}"
bash ./gradlew "${GRADLE_TASKS[@]}" --no-daemon "${GRADLE_PROXY_ARGS[@]}"

APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    echo ""
    echo "✅ APK: $APK"
    ls -la "$APK"
fi
