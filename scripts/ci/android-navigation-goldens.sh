#!/usr/bin/env bash
#
# Capture or compare the T-0320 navigation screen goldens on one emulator.
#
# The debug build's `core.nav.show_screen` command fills the real navigation
# screen with the committed `test-data/navigation/screen-route.json`, so a live
# session cannot drift the capture. Light and dark are the system night mode and
# the matching Standard style. References live in
# test-data/navigation-goldens/<theme>/.
#
# On this machine the script re-executes under the `gm-emulator` slot and boots
# the AVD when no emulator is attached. CI boots its own emulator and the script
# uses it.
#
# Usage:
#   ./scripts/ci/android-navigation-goldens.sh [--update] [APK]
#
# Environment:
#   GOLDEN_SERIAL          use this device instead of booting an AVD
#   GOLDEN_EMULATOR_PORT   local emulator port (default 5580)
#   GOLDEN_AVD_NAME        AVD to boot (default grassmaps-goldens-api29)
#   GOLDEN_GPU_MODE        emulator -gpu value (default swangle)
#   GOLDEN_NO_LOCK=1       do not take the gm-emulator slot
#   GOLDEN_SKIP_REBOOT=1   do not reboot after setting the dialog suppression
#   GOLDEN_TIMEOUT         emulator run limit (default 25m)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

ORIGINAL_ARGS=("$@")
UPDATE=0
APK=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --update) UPDATE=1; shift ;;
        -h | --help)
            sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        -*)
            echo "android-navigation-goldens.sh: unknown option: $1" >&2
            exit 2
            ;;
        *)
            APK="$1"
            shift
            ;;
    esac
done

APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUTPUT="${GOLDEN_OUTPUT:-$ROOT/build/map-goldens/navigation}"
PORT="${GOLDEN_EMULATOR_PORT:-5580}"
GPU="${GOLDEN_GPU_MODE:-swangle}"
AVD="${GOLDEN_AVD_NAME:-grassmaps-goldens-api29}"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/dotfiles/local/share/android/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[[ -f "$APK" ]] || fail "APK not found: $APK
Build it first: agent-lock run gm-build --timeout 30m -- ./gradlew :app:assembleDebug"
command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"

if [[ -z "${CI:-}" && -z "${GRASS_MAPS_IN_EMULATOR_SLOT:-}" && -z "${GOLDEN_NO_LOCK:-}" ]] &&
    command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_EMULATOR_SLOT=1
    exec agent-lock run gm-emulator --timeout "${GOLDEN_TIMEOUT:-25m}" -- "$0" "${ORIGINAL_ARGS[@]}"
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
    attached="$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }' || true)"
    SERIAL="$(printf '%s\n' "$attached" | awk '/^emulator-/ { print; exit }')"
    if [[ -z "$SERIAL" && "$(printf '%s\n' "$attached" | grep -c .)" -eq 1 ]]; then
        SERIAL="$attached"
    fi
    [[ -n "$SERIAL" ]] || fail "CI attached no single emulator: $(printf '%s ' $attached)"
else
    [[ -x "$ANDROID_HOME/emulator/emulator" ]] ||
        fail "the Android emulator is not installed under $ANDROID_HOME"
    [[ -d "$ANDROID_AVD_HOME" ]] || fail "the AVD directory does not exist: $ANDROID_AVD_HOME"
    # A graphical host GPU needs the X cookie; the session can leave
    # XAUTHORITY pointing at a file that no longer exists.
    if [[ -z "${XAUTHORITY:-}" || ! -f "${XAUTHORITY}" ]]; then
        for candidate in /run/user/"$(id -u)"/xauth_*; do
            if [[ -f "$candidate" ]]; then
                export XAUTHORITY="$candidate"
                break
            fi
        done
    fi
    unset EGL_PLATFORM
    SERIAL="emulator-$PORT"
    emulator_log="$OUTPUT/emulator-$PORT.log"
    mkdir -p "$(dirname "$emulator_log")"
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

export ANDROID_SERIAL="$SERIAL"
wait_for_device 180 -s "$SERIAL"
wait_for_package_manager 120 -s "$SERIAL"

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
    sleep 5
    echo "== rebooted, dialogs suppressed ==" >&2
fi

mkdir -p "$OUTPUT"
python3 "$SCRIPT_DIR/navigation_goldens.py" capture \
    --serial "$SERIAL" \
    --apk "$APK" \
    --output "$OUTPUT"

COMPARE=(python3 "$SCRIPT_DIR/navigation_goldens.py" compare --captured "$OUTPUT" --output "$OUTPUT")
if [[ $UPDATE -eq 1 ]]; then
    COMPARE+=(--update)
fi
"${COMPARE[@]}"

if [[ $UPDATE -eq 1 ]]; then
    echo "Updated references in $ROOT/test-data/navigation-goldens"
fi
