#!/usr/bin/env bash
#
# Installs the debug APK and the committed fixture region pack on the connected
# device or emulator, launches the app, and checks that the map reports ready,
# renders a full frame, and that the process survives the compositions after
# it. It fails on a crash and on any StrictMode policy violation logged by the
# UI thread.
#
# Why the pack: the bare APK falls back to the bundled fixture archive, which
# is one source at one camera. The installed pack goes through the region
# store: the `current` pointer, the manifest and the pack's own tiles. The
# committed `test-data/regions/fixture-mini` version directory is 192 KB, and
# its map and contours already ship as the app's debug assets, so the check
# reuses it instead of adding a second fixture. The copy uses `run-as`, the
# same mechanism the oblast goldens use. The readiness line must name the
# mount, so a failed copy cannot pass on the bundled asset.
#
# What each assertion proves, in order:
# - `GrassMapsMap: map-ready region=<name> contours=<bool>` means the surface
#   has a style bundle and `setStyle` and `applyStyleDiff` returned.
# - `first-full-frame-ms=` is the debug harness's own report that MapLibre
#   rendered a full frame. `map-ready` is written before a frame is drawn, so a
#   device that renders nothing still writes it; this line does not.
# - Five seconds after that frame the process must still be the same pid, with
#   no fatal exception and no composition error. That window is where T-0512's
#   `LayoutNode cannot be cast to MapNode` fired: 0.8 s after `map-ready` and
#   0.2 s after the first full frame on the local emulator. The applier crash
#   happens on a recomposition, never on the `map-ready` composition.
#
# The crash and StrictMode checks keep the log sample they always had: one read
# 8 s after the launch. A violation logged after that read is not in it.
# Reading later widens the window into the region load and catches an
# emulator's cold page-cache reads on the UI thread: the run that tried it
# failed on two DiskReadViolations of about 9 s. The post-frame check reads the
# log again but judges only fatals and composition errors, never StrictMode, so
# that flake stays out.
#
# The debug build installs the StrictMode policy through DebugApplication.
# The `GrassMapsStrictMode` marker is required, so a build without the policy
# cannot pass this check by logging nothing.
#
# Every wait is bounded: a device that never attaches fails the script instead
# of hanging the job.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="nl.r1a.grassmap"
ACTIVITY="${SMOKE_ACTIVITY:-nl.r1a.grassmap.app.MainActivity}"
REGION_PACK="${SMOKE_REGION_PACK:-$ROOT/test-data/regions/fixture-mini}"
SETTLE_SECONDS="${SMOKE_SETTLE_SECONDS:-8}"
MAP_READY_SECONDS="${SMOKE_MAP_READY_SECONDS:-120}"
# The goldens wait 180 s for the same line. It is later than `map-ready`:
# swiftshader measured style-loaded at 46 s and a full frame just after 60 s
# on the CI emulator (T-0108), and the smoke boots under the same renderer.
FIRST_FRAME_SECONDS="${SMOKE_FIRST_FRAME_SECONDS:-180}"
FRAME_SETTLE_SECONDS="${SMOKE_FRAME_SETTLE_SECONDS:-5}"
DEVICE_DEADLINE_SECONDS="${SMOKE_DEVICE_DEADLINE_SECONDS:-180}"
LOGCAT_CLEAR_ATTEMPTS="${SMOKE_LOGCAT_CLEAR_ATTEMPTS:-5}"
LOGCAT_CLEAR_RETRY_SECONDS="${SMOKE_LOGCAT_CLEAR_RETRY_SECONDS:-2}"

# wait_for_map_ready SECONDS EXPECTED_REGION
#
# Polls the app's own readiness line. The deadline is the bound on a slow first
# frame; the line arrives well before it on a healthy device. A style the host
# rejected leaves the map empty and still writes the readiness line, so the
# rejection is a failure here, from the same stream. The region name proves the
# installed pack was mounted rather than skipped in favour of the asset.
wait_for_map_ready() {
    local seconds="$1"
    local expected="$2"
    local deadline=$((SECONDS + seconds))
    local log
    while true; do
        log="$(timeout 30 adb logcat -d -s GrassMapsMap:I 2>/dev/null || true)"
        if grep -q 'map-ready' <<<"$log"; then
            echo "OK: the map reported ready"
            grep 'map-ready' <<<"$log" | tail -n 1
            if [[ -n "$expected" ]] && ! grep -q "map-ready region=$expected " <<<"$log"; then
                echo "FAIL: the map did not open on the installed region pack:" >&2
                echo "  expected region=$expected" >&2
                echo "The installed-pack path is the one this check is for; the" >&2
                echo "bundled asset still reports ready, so it cannot pass here." >&2
                grep 'map-ready' <<<"$log" >&2
                return 1
            fi
            if grep -q 'style rejected\|style layers rejected' <<<"$log"; then
                echo "FAIL: the map host rejected the style or the layer diff:" >&2
                grep 'style rejected\|style layers rejected' <<<"$log" >&2
                echo "The map-ready line is written in that case too, so it is checked here." >&2
                return 1
            fi
            return 0
        fi
        if ((SECONDS >= deadline)); then
            echo "FAIL: the map did not report ready in ${seconds}s" >&2
            echo "The process may be alive with no map surface, style or layers." >&2
            timeout 30 adb logcat -d -t 400 >&2 || true
            return 1
        fi
        sleep 2
    done
}

# wait_for_first_frame SECONDS PID
#
# Waits for the debug harness's own first full frame. The goldens use the same
# line, so its absence fails for the same reason there: map-ready proves the
# style and layers were accepted, not that the engine drew anything. A process
# that dies in this window is named as such instead of waiting out the bound; a
# process that restarts has a live pid but no frame from the current run.
wait_for_first_frame() {
    local seconds="$1"
    local pid="$2"
    local deadline=$((SECONDS + seconds))
    local log
    local pid_now
    while true; do
        log="$(timeout 30 adb logcat -d 2>/dev/null || true)"
        if grep -q 'first-full-frame-ms=' <<<"$log"; then
            echo "OK: the map reported its first full frame"
            grep 'first-full-frame-ms=' <<<"$log" | tail -n 1
            return 0
        fi
        pid_now="$(timeout 30 adb shell pidof "$PKG" | tr -d '\r')"
        if [[ -z "$pid_now" ]]; then
            echo "FAIL: $PKG died before reporting a first full frame" >&2
            timeout 30 adb logcat -d -t 400 >&2 || true
            return 1
        fi
        if [[ "$pid_now" != "$pid" ]]; then
            echo "FAIL: $PKG restarted before reporting a first full frame (pid $pid -> $pid_now)" >&2
            timeout 30 adb logcat -d -t 400 >&2 || true
            return 1
        fi
        if ((SECONDS >= deadline)); then
            echo "FAIL: the map did not report a first full frame in ${seconds}s" >&2
            echo "The map surface, style and layers may be ready with no frame drawn." >&2
            timeout 30 adb logcat -d -t 400 >&2 || true
            return 1
        fi
        sleep 2
    done
}

# assert_survives_frame PID SECONDS
#
# The applier crash this guards against happens on a recomposition after the
# first frame, so a live pid at the 8 s sample and a map-ready line are not
# enough. The pid must be unchanged (a fatal exception plus an automatic
# restart leaves a live pid but no map), and the log must hold neither a fatal
# exception for this package nor a captured composition error.
assert_survives_frame() {
    local pid="$1"
    local seconds="$2"
    local pid_after log_after fatal
    sleep "$seconds"
    pid_after="$(timeout 30 adb shell pidof "$PKG" | tr -d '\r')"
    if [[ -z "$pid_after" ]]; then
        echo "FAIL: $PKG died within ${seconds}s of its first full frame" >&2
        timeout 30 adb logcat -d -t 400 >&2 || true
        return 1
    fi
    if [[ "$pid_after" != "$pid" ]]; then
        echo "FAIL: $PKG restarted after its first full frame (pid $pid -> $pid_after)" >&2
        echo "A fatal exception and an automatic restart leaves a live pid but no map." >&2
        timeout 30 adb logcat -d -t 400 >&2 || true
        return 1
    fi
    log_after="$(timeout 60 adb logcat -d --pid="$pid" 2>/dev/null || true)"
    fatal="$(grep -A 3 'FATAL EXCEPTION' <<<"$log_after" || true)"
    if grep -q "Process: $PKG" <<<"$fatal"; then
        echo "FAIL: the app logged a fatal exception after its first full frame:" >&2
        grep -A 20 'FATAL EXCEPTION' <<<"$log_after" | head -n 40 >&2
        return 1
    fi
    if grep -q 'Error was captured in composition' <<<"$log_after"; then
        echo "FAIL: a composition error was logged after the first full frame:" >&2
        grep -A 8 'Error was captured in composition' <<<"$log_after" | head -n 40 >&2
        return 1
    fi
    echo "OK: $PKG survived the first full frame and the compositions after it"
}

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
wait_for_device "$DEVICE_DEADLINE_SECONDS"

# Full readiness gate before the install, also bounded. EXPECTED_API_LEVEL is
# set by the workflow; empty accepts any API level.
wait_for_device_ready 240 "${EXPECTED_API_LEVEL:-}"

echo "== Installing $APK =="
timeout 300 adb install -r "$APK"

installed="$(timeout 30 adb shell pm list packages "$PKG" | tr -d '\r')"
if [[ "$installed" != "package:$PKG" ]]; then
    echo "FAIL: $PKG is not installed after adb install; got: ${installed:-nothing}" >&2
    exit 1
fi

# The pack goes into the app's private region store, so it is in place before
# the first launch; the install above does not touch app data. A copy that
# fails fails the script here, because the readiness check would otherwise pass
# on the bundled asset and attest nothing.
if [[ ! -e "$REGION_PACK" ]]; then
    echo "FAIL: region pack not found: $REGION_PACK" >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "FAIL: python3 is not on PATH; it installs the region pack" >&2
    exit 1
fi
echo "== Installing region pack $REGION_PACK =="
timeout 30 adb shell run-as "$PKG" rm -rf files/regions || true
EXPECTED_REGION="$(timeout 300 python3 "$SCRIPT_DIR/map_goldens.py" install-region \
    --region-package "$REGION_PACK")"
if [[ -z "$EXPECTED_REGION" ]]; then
    echo "FAIL: the region pack installer printed no region name" >&2
    exit 1
fi
echo "OK: the region pack is installed as $EXPECTED_REGION"

timeout 30 adb shell am force-stop "$PKG" || true

# The emulator occasionally refuses to clear the system log right after boot
# (seen on API 29 under swiftshader). Retry a bounded number of times; if it
# still fails, the script exits non-zero as before, so the check is not weakened.
log_cleared=0
for attempt in $(seq 1 "$LOGCAT_CLEAR_ATTEMPTS"); do
    if timeout 30 adb logcat -c; then
        log_cleared=1
        break
    fi
    if [[ "$attempt" -lt "$LOGCAT_CLEAR_ATTEMPTS" ]]; then
        echo "logcat clear failed (attempt $attempt), retrying" >&2
        sleep "$LOGCAT_CLEAR_RETRY_SECONDS"
    else
        echo "logcat clear failed (attempt $attempt)" >&2
    fi
done
if [[ "$log_cleared" -ne 1 ]]; then
    echo "FAIL: could not clear the logcat buffer" >&2
    exit 1
fi

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

# The crash and StrictMode checks read the log at this point, 8 s after the
# launch, exactly as they did before the map assertions. Reading it later would
# widen the window and turn an emulator's slow cold read on the UI thread into
# an intermittent failure of this job. The post-frame check has its own read
# and judges only fatals and composition errors.
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

wait_for_map_ready "$MAP_READY_SECONDS" "$EXPECTED_REGION"
wait_for_first_frame "$FIRST_FRAME_SECONDS" "$pid"
assert_survives_frame "$pid" "$FRAME_SETTLE_SECONDS"
