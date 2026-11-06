#!/usr/bin/env bash
#
# Installs the debug APK on the connected device or emulator, launches it and
# checks that the process stays alive. It fails on a crash and on any
# StrictMode policy violation logged by the UI thread.
#
# The debug build installs the StrictMode policy through DebugApplication.
# The `GrassMapsStrictMode` marker is required, so a build without the policy
# cannot pass this check by logging nothing.

set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="nl.r1a.grassmap"
SETTLE_SECONDS="${SMOKE_SETTLE_SECONDS:-8}"

if [[ ! -f "$APK" ]]; then
    echo "FAIL: APK not found: $APK" >&2
    exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
    echo "FAIL: adb is not on PATH" >&2
    exit 1
fi

adb wait-for-device
until adb shell pm path android >/dev/null 2>&1; do
    sleep 2
done

echo "== Installing $APK =="
adb install -r "$APK"

adb shell am force-stop "$PKG" || true
adb logcat -c
echo "== Launching $PKG =="
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG"
sleep "$SETTLE_SECONDS"

pid="$(adb shell pidof "$PKG" | tr -d '\r')"
if [[ -z "$pid" ]]; then
    echo "FAIL: $PKG is not running after launch" >&2
    adb logcat -d -t 400 >&2 || true
    exit 1
fi
echo "OK: $PKG is running with pid $pid"

log="$(adb logcat -d --pid="$pid")"

if ! grep -q 'GrassMapsStrictMode.*StrictMode thread policy enabled' <<<"$log"; then
    echo "FAIL: the debug APK did not install the StrictMode policy." >&2
    echo "The strict-mode check would be vacuous, so it is a failure." >&2
    exit 1
fi

if grep -q 'FATAL EXCEPTION' <<<"$log"; then
    echo "FAIL: the app logged a fatal exception:" >&2
    grep -A 20 'FATAL EXCEPTION' <<<"$log" >&2
    exit 1
fi

violations="$(grep -c 'StrictMode policy violation' <<<"$log" || true)"
if [[ "$violations" != "0" ]]; then
    echo "FAIL: $violations StrictMode policy violation(s) on the UI thread:" >&2
    grep -A 3 'StrictMode policy violation' <<<"$log" | head -n 60 >&2
    exit 1
fi

echo "OK: no StrictMode policy violations logged"
