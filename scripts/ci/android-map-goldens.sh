#!/usr/bin/env bash
#
# Capture or compare the T-0102 map goldens on one emulator.
#
# The fixture profile is the deterministic CI run: the app uses the committed
# T-0015 fixture asset, no region package and no archive. The oblast profile is
# local acceptance: pass --region-package with an installed T-0015-shaped
# package built from the bench archives. The 59 MB archive never enters CI.
#
# On this machine the script re-executes under the `gm-emulator` slot and boots
# the AVD when no device is attached. CI boots its own emulator.
#
# Usage:
#   ./scripts/ci/android-map-goldens.sh --profile fixture [--update] [APK]
#   ./scripts/ci/android-map-goldens.sh --profile standard [--style light|dark|both] [--update] [APK]
#   ./scripts/ci/android-map-goldens.sh --profile oblast \
#       --region-package /hdd/t-0102-evidence/region/ua-46/2026-11-05-bench \
#       --output /hdd/t-0102-evidence/oblast [APK]
#
# Environment:
#   GOLDEN_SERIAL          use this device instead of booting an AVD
#   GOLDEN_EMULATOR_PORT   local emulator port (default 5580)
#   GOLDEN_AVD_NAME        AVD to boot (default grassmaps-bench)
#   GOLDEN_GPU_MODE        emulator -gpu value (default host; pass
#                          swiftshader_indirect to reproduce the CI renderer)
#   GOLDEN_NO_LOCK=1       do not take the gm-emulator slot
#   GOLDEN_SKIP_REBOOT=1   do not reboot after setting the dialog suppression
#   GOLDEN_TIMEOUT         emulator run limit (default 25m)
#
# Every wait is bounded: a device that never boots or attaches fails the script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

ORIGINAL_ARGS=("$@")
PROFILE=""
OUTPUT=""
REFERENCE=""
REGION_PACKAGE=""
STYLE=""
UPDATE=0
APK=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile) PROFILE="$2"; shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        --reference) REFERENCE="$2"; shift 2 ;;
        --region-package) REGION_PACKAGE="$2"; shift 2 ;;
        --style) STYLE="$2"; shift 2 ;;
        --update) UPDATE=1; shift ;;
        -h | --help)
            sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        -*)
            echo "android-map-goldens.sh: unknown option: $1" >&2
            exit 2
            ;;
        *)
            APK="$1"
            shift
            ;;
    esac
done

[[ -n "$PROFILE" ]] || {
    echo "android-map-goldens.sh: --profile fixture|oblast is required" >&2
    exit 2
}
if [[ "$PROFILE" == "standard" ]]; then
    STYLE="${STYLE:-both}"
fi
if [[ -n "$STYLE" && "$PROFILE" != "standard" ]]; then
    echo "android-map-goldens.sh: --style applies to --profile standard" >&2
    exit 2
fi
case "$STYLE" in
    ""|light|dark|both) ;;
    *)
        echo "android-map-goldens.sh: --style must be light, dark or both" >&2
        exit 2
        ;;
esac
if [[ "$PROFILE" == "oblast" && -z "$REGION_PACKAGE" ]]; then
    echo "android-map-goldens.sh: oblast capture needs --region-package DIR" >&2
    exit 2
fi
if [[ "$PROFILE" == "oblast" ]]; then
    OUTPUT="${OUTPUT:-/hdd/t-0102-evidence/oblast}"
    REFERENCE="${REFERENCE:-/hdd/t-0102-evidence/oblast-reference}"
fi
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PORT="${GOLDEN_EMULATOR_PORT:-5580}"
GPU="${GOLDEN_GPU_MODE:-host}"
AVD="${GOLDEN_AVD_NAME:-grassmaps-bench}"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/dotfiles/local/share/android/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

if [[ ! -f "$APK" ]]; then
    fail "APK not found: $APK
Build it first: agent-lock run gm-build --timeout 30m -- ./gradlew :app:assembleDebug"
fi
command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"

if [[ -z "${CI:-}" && -z "${GRASS_MAPS_IN_EMULATOR_SLOT:-}" && -z "${GOLDEN_NO_LOCK:-}" ]] &&
    command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_EMULATOR_SLOT=1
    exec agent-lock run gm-emulator --timeout "${GOLDEN_TIMEOUT:-25m}" -- "$0" "${ORIGINAL_ARGS[@]}"
fi

if [[ "$GPU" == "host" ]]; then
    egl_vendor_file="${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/10_nvidia.json}"
    export __NV_PRIME_RENDER_OFFLOAD="${__NV_PRIME_RENDER_OFFLOAD:-1}"
    export __VK_LAYER_NV_optimus="${__VK_LAYER_NV_optimus:-NVIDIA_only}"
    export __GLX_VENDOR_LIBRARY_NAME="${__GLX_VENDOR_LIBRARY_NAME:-nvidia}"
    export __EGL_VENDOR_LIBRARY_FILENAMES="$egl_vendor_file"
fi

emulator_pid=""
SERIAL="${GOLDEN_SERIAL:-${ANDROID_SERIAL:-}}"

cleanup() {
    if [[ -n "$emulator_pid" ]]; then
        timeout 20 adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        sleep 2
        kill "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [[ -n "$SERIAL" ]]; then
    :
elif [[ -n "${CI:-}" ]]; then
    SERIAL="$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }' || true)"
    [[ -n "$SERIAL" ]] || fail "CI attached no emulator: $(adb devices | tr '\n' ' ')"
else
    [[ -x "$ANDROID_HOME/emulator/emulator" ]] ||
        fail "the Android emulator is not installed under $ANDROID_HOME"
    [[ -d "$ANDROID_AVD_HOME" ]] || fail "the AVD directory does not exist: $ANDROID_AVD_HOME"
    SERIAL="emulator-$PORT"
    emulator_log="$OUTPUT/emulator-$PORT.log"
    mkdir -p "$OUTPUT"
    echo "== booting $AVD as $SERIAL ($GPU) ==" >&2
    "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" \
        -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$GPU" \
        >"$emulator_log" 2>&1 &
    emulator_pid=$!
    deadline=$((SECONDS + 240))
    while true; do
        boot_state="$(timeout 10 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        if [[ "$boot_state" == "1" ]]; then
            break
        fi
        if ! kill -0 "$emulator_pid" 2>/dev/null; then
            tail -n 20 "$emulator_log" >&2 || true
            fail "the emulator process died before boot; log: $emulator_log"
        fi
        if ((SECONDS >= deadline)); then
            tail -n 20 "$emulator_log" >&2 || true
            fail "the emulator did not boot in 240s; log: $emulator_log"
        fi
        sleep 3
    done
    echo "== $SERIAL booted in $SECONDS s ==" >&2
fi

wait_for_device 180 -s "$SERIAL"
wait_for_package_manager 120 -s "$SERIAL"

# `hide_error_dialogs` suppresses only new ANR dialogs. Under swiftshader the
# CI SystemUI trips its watchdog during boot, so set the flags and reboot once:
# the dialog that is already up then never appears on the fresh boot either.
if [[ "${GOLDEN_SKIP_REBOOT:-0}" != "1" ]]; then
    echo "== suppressing system dialogs and rebooting once ==" >&2
    adb -s "$SERIAL" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
    adb -s "$SERIAL" reboot || fail "adb reboot failed"
    sleep 5
    adb -s "$SERIAL" wait-for-device || fail "adb wait-for-device failed after the dialog-suppression reboot"
    wait_for_device 240 -s "$SERIAL" || fail "the device did not return after the dialog-suppression reboot"
    boot_deadline=$((SECONDS + 240))
    until [[ "$(timeout 10 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        ((SECONDS >= boot_deadline)) && fail "sys.boot_completed was not 1 after the dialog-suppression reboot"
        sleep 3
    done
    wait_for_package_manager 180 -s "$SERIAL" || fail "the package manager did not return after the dialog-suppression reboot"
    adb -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
    # The package manager is still finishing its own startup right after boot;
    # give the install path a moment before the harness runs.
    sleep 5
    echo "== rebooted, dialogs suppressed ==" >&2
fi

styles=()
case "$STYLE" in
    "") styles=("") ;;
    both) styles=(light dark) ;;
    *) styles=("$STYLE") ;;
esac

compare_failed=0
for style in "${styles[@]}"; do
    if [[ -n "$style" ]]; then
        run_output="${OUTPUT:+$OUTPUT/}${OUTPUT:+$style}"
        run_output="${run_output:-$ROOT/build/map-goldens/$PROFILE/$style}"
        run_reference="${REFERENCE:+$REFERENCE/}${REFERENCE:+$style}"
        run_reference="${run_reference:-$ROOT/test-data/map-goldens/$PROFILE/$style}"
    else
        run_output="${OUTPUT:-$ROOT/build/map-goldens/$PROFILE}"
        run_reference="${REFERENCE:-$ROOT/test-data/map-goldens/$PROFILE}"
    fi
    COMMAND=(python3 "$SCRIPT_DIR/map_goldens.py" capture --serial "$SERIAL" --profile "$PROFILE" --apk "$APK" --output "$run_output")
    if [[ -n "$REGION_PACKAGE" ]]; then
        COMMAND+=(--region-package "$REGION_PACKAGE")
    fi
    if [[ -n "$style" ]]; then
        COMMAND+=(--style "$style")
    fi
    "${COMMAND[@]}"

    if [[ $UPDATE -eq 1 ]]; then
        COMPARE=(python3 "$SCRIPT_DIR/map_goldens.py" compare --profile "$PROFILE" --captured "$run_output" --reference "$run_reference" --update)
        if [[ -n "$style" ]]; then
            COMPARE+=(--style "$style")
        fi
        "${COMPARE[@]}"
        echo "Updated references in $run_reference"
        continue
    fi

    COMPARE=(python3 "$SCRIPT_DIR/map_goldens.py" compare --profile "$PROFILE" --captured "$run_output" --reference "$run_reference" --output "$run_output")
    if [[ -n "$style" ]]; then
        COMPARE+=(--style "$style")
    fi
    if ! "${COMPARE[@]}"; then
        compare_failed=1
    fi
done
exit "$compare_failed"
