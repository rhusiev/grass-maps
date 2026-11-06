#!/usr/bin/env bash
#
# Installs the debug APK on the connected device or emulator, launches it and
# checks that the process stays alive. It fails on a crash and on any
# StrictMode policy violation logged by the UI thread.
#
# The debug build installs the StrictMode policy through DebugApplication.
# The `GrassMapsStrictMode` marker is required, so a build without the policy
# cannot pass this check by logging nothing.
#
# Every wait is bounded: a device that never attaches fails the script instead
# of hanging the job.

set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="nl.r1a.grassmap"
ACTIVITY="${SMOKE_ACTIVITY:-nl.r1a.grassmap.app.MainActivity}"
SETTLE_SECONDS="${SMOKE_SETTLE_SECONDS:-8}"
DEVICE_DEADLINE_SECONDS="${SMOKE_DEVICE_DEADLINE_SECONDS:-180}"

if [[ ! -f "$APK" ]]; then
    echo "FAIL: APK not found: $APK" >&2
    exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
    echo "FAIL: adb is not on PATH" >&2
    exit 1
fi

# The emulator runner boots the device before this script; a bounded wait keeps
# a broken attach from blocking the job.
deadline=$((SECONDS + DEVICE_DEADLINE_SECONDS))
until timeout 10 adb shell true >/dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
        echo "FAIL: no device or emulator became available in ${DEVICE_DEADLINE_SECONDS}s" >&2
        timeout 10 adb devices >&2 || true
        exit 1
    fi
    sleep 2
done

# Package manager readiness, also bounded.
deadline=$((SECONDS + 120))
until timeout 10 adb shell pm path android >/dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
        echo "FAIL: package manager was not ready in 120s" >&2
        exit 1
    fi
    sleep 2
done

echo "== Installing $APK =="
timeout 300 adb install -r "$APK"

installed="$(timeout 30 adb shell pm list packages "$PKG" | tr -d '\r')"
if [[ "$installed" != "package:$PKG" ]]; then
    echo "FAIL: $PKG is not installed after adb install; got: ${installed:-nothing}" >&2
    exit 1
fi

timeout 30 adb shell am force-stop "$PKG" || true
timeout 30 adb logcat -c
echo "== Launching $PKG/$ACTIVITY =="
if ! timeout 120 adb shell am start -W -n "$PKG/$ACTIVITY"; then
    echo "FAIL: am start did not launch $PKG/$ACTIVITY" >&2
    timeout 30 adb shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER "$PKG" >&2 || true
    timeout 30 adb shell dumpsys package "$PKG" | head -n 60 >&2 || true
    exit 1
fi
sleep "$SETTLE_SECONDS"

pid="$(timeout 30 adb shell pidof "$PKG" | tr -d '\r')"
if [[ -z "$pid" ]]; then
    echo "FAIL: $PKG is not running after launch" >&2
    timeout 30 adb logcat -d -t 400 >&2 || true
    exit 1
fi
echo "OK: $PKG is running with pid $pid"

log="$(timeout 60 adb logcat -d --pid="$pid")"

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
