#!/usr/bin/env bash
#
# Runs the emulator cold-start and frame-pacing benchmark and prints its JSON.
#
# These are trend numbers, not the Moto g32 acceptance gate. The measurement
# itself lives in android_benchmark.py; this script owns the device: on this
# machine it re-executes under the `gm-emulator` slot and boots the AVD when no
# device is attached. CI boots the emulator in the workflow job instead.
#
# Usage:
#   ./scripts/ci/android-benchmark.sh [APK]
#
# Environment:
#   BENCH_SERIAL            use this device instead of booting the AVD
#   BENCH_RUNS              cold-start runs (default 5)
#   BENCH_EMULATOR_PORT     local emulator port (default 5580)
#   BENCH_AVD_NAME          AVD to boot (default grassmaps-bench)
#   BENCH_GPU_MODE          emulator -gpu value (default host; CI sets
#                           swiftshader_indirect)
#   BENCH_NO_LOCK=1         do not take the gm-emulator slot
#
# Every wait is bounded: a device that never boots or attaches fails the script
# instead of hanging the job.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PORT="${BENCH_EMULATOR_PORT:-5580}"
AVD="${BENCH_AVD_NAME:-grassmaps-bench}"
GPU="${BENCH_GPU_MODE:-host}"
RUNS="${BENCH_RUNS:-5}"
BOOT_DEADLINE_SECONDS="${BENCH_BOOT_DEADLINE_SECONDS:-240}"
DEVICE_DEADLINE_SECONDS="${BENCH_DEVICE_DEADLINE_SECONDS:-180}"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/dotfiles/local/share/android/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"

if [[ ! -f "$APK" ]]; then
    fail "APK not found: $APK
Build it first, through the build slot:
  JAVA_HOME=\$HOME/dotfiles/local/share/jdk agent-lock run gm-build --timeout 30m -- ./gradlew :app:assembleDebug"
fi
command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"

# On this machine Gradle and emulators run in named slots. CI boots the
# emulator in its own job, so only the local path takes the lock.
if [[ -z "${CI:-}" && -z "${GRASS_MAPS_IN_EMULATOR_SLOT:-}" && -z "${BENCH_NO_LOCK:-}" ]] &&
    command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_EMULATOR_SLOT=1
    exec agent-lock run gm-emulator --timeout 20m -- "$SCRIPT_PATH" "$@"
fi

if [[ "$GPU" == "host" ]]; then
    # The bench host uses the NVIDIA GPU through PRIME offload. Keep the values
    # already set by the caller; these are only the defaults for this machine.
    egl_vendor_file="${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/10_nvidia.json}"
    export __NV_PRIME_RENDER_OFFLOAD="${__NV_PRIME_RENDER_OFFLOAD:-1}"
    export __VK_LAYER_NV_optimus="${__VK_LAYER_NV_optimus:-NVIDIA_only}"
    export __GLX_VENDOR_LIBRARY_NAME="${__GLX_VENDOR_LIBRARY_NAME:-nvidia}"
    export __EGL_VENDOR_LIBRARY_FILENAMES="$egl_vendor_file"
fi

emulator_pid=""
SERIAL="${BENCH_SERIAL:-${ANDROID_SERIAL:-}}"
DEVICE_SOURCE="${BENCH_DEVICE_SOURCE:-}"

cleanup() {
    if [[ -n "$emulator_pid" ]]; then
        timeout 20 adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        sleep 2
        kill "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [[ -z "$SERIAL" && -n "${CI:-}" ]]; then
    SERIAL="$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }' || true)"
    [[ -n "$SERIAL" ]] || fail "CI attached no emulator: $(adb devices | tr '\n' ' ')"
fi

if [[ -n "$SERIAL" ]]; then
    DEVICE_SOURCE="${DEVICE_SOURCE:-attached-device}"
else
    [[ -x "$ANDROID_HOME/emulator/emulator" ]] ||
        fail "the Android emulator is not installed under $ANDROID_HOME"
    [[ -d "$ANDROID_AVD_HOME" ]] || fail "the AVD directory does not exist: $ANDROID_AVD_HOME"
    mkdir -p "$ROOT/build/benchmarks"
    emulator_log="$ROOT/build/benchmarks/emulator-$PORT.log"
    SERIAL="emulator-$PORT"
    DEVICE_SOURCE="${DEVICE_SOURCE:-local-avd}"
    echo "== booting $AVD as $SERIAL ($GPU) ==" >&2
    "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" \
        -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$GPU" \
        >"$emulator_log" 2>&1 &
    emulator_pid=$!
    deadline=$((SECONDS + BOOT_DEADLINE_SECONDS))
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
            fail "the emulator did not boot in ${BOOT_DEADLINE_SECONDS}s; log: $emulator_log"
        fi
        sleep 3
    done
    echo "== $SERIAL booted in $SECONDS s ==" >&2
fi

wait_for_device "$DEVICE_DEADLINE_SECONDS" -s "$SERIAL"
wait_for_package_manager 120 -s "$SERIAL"

python3 "$SCRIPT_DIR/android_benchmark.py" \
    --apk "$APK" \
    --serial "$SERIAL" \
    --runs "$RUNS" \
    --gpu-mode "$GPU" \
    --device-source "$DEVICE_SOURCE" \
    --repo-root "$ROOT"
