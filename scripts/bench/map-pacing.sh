#!/usr/bin/env bash
#
# Measure map-frame pacing from Perfetto `BufferTX` latches during a fixed
# gesture run, and store the late-interval summary per commit.
#
# MapLibre draws into a SurfaceView, which carries no FrameTimeline vsync id,
# so `dumpsys gfxinfo` never sees its frames. This script traces SurfaceFlinger
# and reads the map layer's queued-buffer counter instead.
#
# Emulator numbers are trends only. The Moto g32 acceptance gate for "no dropped
# frames" is measured on the phone with the same counter method.
#
# On this machine the script takes the `gm-emulator` slot itself and boots the
# AVD when no device is attached.
#
# Usage:
#   ./scripts/bench/map-pacing.sh [APK]
#
# Environment:
#   PACING_SERIAL          use this device instead of booting the AVD
#   PACING_RUNS            traces to capture and pool (default 3)
#   PACING_SECONDS         trace length bound (default 15)
#   PACING_OUTPUT          output directory (default build/benchmarks/map-pacing)
#   PACING_EMULATOR_PORT   emulator port (default 5581)
#   PACING_AVD_NAME        AVD to boot (default grassmaps-bench)
#   PACING_GPU_MODE        emulator -gpu value (default host)
#   TRACE_PROCESSOR        Perfetto trace_processor binary
#   PACING_NO_LOCK=1       do not take the gm-emulator slot

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../ci/lib/device.sh
source "$SCRIPT_DIR/../ci/lib/device.sh"

APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
RUNS="${PACING_RUNS:-3}"
SECONDS_BOUND="${PACING_SECONDS:-15}"
OUTPUT="${PACING_OUTPUT:-$ROOT/build/benchmarks/map-pacing}"
PORT="${PACING_EMULATOR_PORT:-5581}"
AVD="${PACING_AVD_NAME:-grassmaps-bench}"
GPU="${PACING_GPU_MODE:-host}"
BOOT_DEADLINE_SECONDS="${PACING_BOOT_DEADLINE_SECONDS:-240}"
DEVICE_DEADLINE_SECONDS="${PACING_DEVICE_DEADLINE_SECONDS:-180}"
PACKAGE="nl.r1a.grassmap"
ACTIVITY="nl.r1a.grassmap.app.MainActivity"
TRACE_DIR="/data/misc/perfetto-traces"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/dotfiles/local/share/android/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[[ -f "$APK" ]] || fail "APK not found: $APK
Build it first through the build slot:
  JAVA_HOME=\$HOME/dotfiles/local/share/jdk agent-lock run gm-build --timeout 30m -- ./gradlew :app:assembleDebug"
command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"

SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
if [[ -z "${CI:-}" && -z "${GRASS_MAPS_IN_EMULATOR_SLOT:-}" && -z "${PACING_NO_LOCK:-}" ]] &&
    command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_EMULATOR_SLOT=1
    exec agent-lock run gm-emulator --timeout 30m -- "$SCRIPT_PATH" "$@"
fi

if [[ "$GPU" == "host" ]]; then
    export __NV_PRIME_RENDER_OFFLOAD="${__NV_PRIME_RENDER_OFFLOAD:-1}"
    export __VK_LAYER_NV_optimus="${__VK_LAYER_NV_optimus:-NVIDIA_only}"
    export __GLX_VENDOR_LIBRARY_NAME="${__GLX_VENDOR_LIBRARY_NAME:-nvidia}"
    export __EGL_VENDOR_LIBRARY_FILENAMES="${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/10_nvidia.json}"
fi

emulator_pid=""
SERIAL="${PACING_SERIAL:-${ANDROID_SERIAL:-}}"
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

if [[ -z "$SERIAL" ]]; then
    [[ -x "$ANDROID_HOME/emulator/emulator" ]] ||
        fail "the Android emulator is not installed under $ANDROID_HOME"
    [[ -d "$ANDROID_AVD_HOME" ]] || fail "the AVD directory does not exist: $ANDROID_AVD_HOME"
    emulator_log="$ROOT/build/benchmarks/emulator-$PORT.log"
    mkdir -p "$(dirname "$emulator_log")"
    SERIAL="emulator-$PORT"
    echo "== booting $AVD as $SERIAL ($GPU) ==" >&2
    "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" \
        -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$GPU" \
        >"$emulator_log" 2>&1 &
    emulator_pid=$!
    deadline=$((SECONDS + BOOT_DEADLINE_SECONDS))
    while true; do
        boot_state="$(timeout 10 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        [[ "$boot_state" == "1" ]] && break
        if ! kill -0 "$emulator_pid" 2>/dev/null; then
            tail -n 20 "$emulator_log" >&2 || true
            fail "the emulator process died before boot; log: $emulator_log"
        fi
        ((SECONDS < deadline)) || fail "the emulator did not boot in ${BOOT_DEADLINE_SECONDS}s"
        sleep 3
    done
    echo "== $SERIAL booted in $SECONDS s ==" >&2
fi

wait_for_device "$DEVICE_DEADLINE_SECONDS" -s "$SERIAL"
wait_for_package_manager 120 -s "$SERIAL"
field() {
    adb -s "$SERIAL" shell "$@" | tr -d '\r'
}

refresh_hz="$(field dumpsys display | grep -oE 'fps=[0-9.]+' | head -1 | cut -d= -f2)"
[[ -n "$refresh_hz" ]] || refresh_hz=60.0

echo "== installing $(basename "$APK") ==" >&2
result="$(timeout 300 adb -s "$SERIAL" install -r "$APK")"
[[ "$result" == *Success* ]] || fail "adb install did not report Success: $result"

# One fixed gesture run: a pan in both directions four times, then a
# double-tap zoom. Pinch and rotate are covered by the camera trace test; the
# pacing number needs continuous map frames, which panning provides.
gestures() {
    for _ in 1 2 3 4; do
        adb -s "$SERIAL" shell input swipe 540 1600 540 900 400
        adb -s "$SERIAL" shell input swipe 540 900 540 1600 400
    done
    adb -s "$SERIAL" shell "input tap 540 1200; input tap 540 1200"
}

mkdir -p "$OUTPUT"
traces=()
for run in $(seq 1 "$RUNS"); do
    adb -s "$SERIAL" shell am force-stop "$PACKAGE"
    adb -s "$SERIAL" logcat -c
    adb -s "$SERIAL" shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
    deadline=$((SECONDS + 90))
    until adb -s "$SERIAL" logcat -d -s GrassMapsMap:I | grep -q "map-ready"; do
        ((SECONDS < deadline)) || fail "the map did not report ready within 90 s"
        sleep 1
    done
    sleep 2
    remote="$TRACE_DIR/map-pacing-$run.pftrace"
    adb -s "$SERIAL" shell rm -f "$remote"
    pid="$(field perfetto --background -o "$remote" -t "${SECONDS_BOUND}s" -b 64mb sched gfx view wm am)"
    [[ -n "$pid" ]] || fail "perfetto did not start"
    gestures
    adb -s "$SERIAL" shell kill -TERM "$pid" >/dev/null 2>&1 || true
    sleep 1
    local_trace="$OUTPUT/map-pacing-run-$run.pftrace"
    adb -s "$SERIAL" pull "$remote" "$local_trace" >/dev/null
    [[ -s "$local_trace" ]] || fail "the trace is empty: $local_trace"
    traces+=("$local_trace")
    echo "== captured $local_trace ==" >&2
done

python3 "$SCRIPT_DIR/map-frame-timeline.py" \
    --refresh-hz "$refresh_hz" \
    --output "$OUTPUT" \
    "${traces[@]}"
