#!/usr/bin/env bash
#
# Measure automatic-reroute latency on the Moto g32, using the app's own
# timing.
#
# What one sample measures: the navigation session's `reroute_ms`, which is the
# wall time from the fix that spends the last metre of the off-route budget to
# the moment the replacement route is installed. It includes the Valhalla
# `/route` call and the route-model rebuild; it excludes the 20 s travel gate,
# the fix pacing, the map redraw and the planner. One fix stream is one sample.
#
# The command `core.nav.test_follow` plans a route in Lviv, builds a GPX replay
# that leaves that exact line, and follows it with the real navigation session.
# Fix timestamps in the replay advance with distance at the profile's gate
# speed, so "20 s of travel" is 22 m walking and 56 m cycling whatever the wall
# tick is. One warm-up run per profile is discarded: it mounts the Valhalla
# graph, and a reroute at the start of a real trip pays that cost once, not on
# every recalc.
#
# Preconditions:
#   - a debug APK from this branch (`:app:assembleDebug`)
#   - the Moto g32 attached, authorised and unlocked; this script refuses an
#     emulator, because the acceptance number is a phone number
#   - the Valhalla routing pack installed on the phone and covering Lviv, with
#     the walk and bike profiles enabled
#   - the debug control channel reachable through `tools/control/grassctl.py`
#   - the `gm-phone` slot free, or REROUTE_NO_LOCK=1
#
# Usage:
#   ./scripts/bench/reroute-latency.sh [APK]
#
# Environment:
#   REROUTE_SERIAL     device serial (default: the single attached device)
#   REROUTE_RUNS       measured samples per profile (default 20)
#   REROUTE_PROFILE    walk, bike or both (default both)
#   REROUTE_AREA       lviv or fixture (default lviv)
#   REROUTE_TICK_MS    wall pacing between replay fixes (default 250)
#   REROUTE_DETOUR_M   sideways offset of the replay's detour (default 60)
#   REROUTE_WARMUP     warm-up runs per profile, discarded (default 1)
#   REROUTE_OUTPUT     output directory (default build/benchmarks/reroute-latency)
#   REROUTE_NO_LOCK=1  do not take the gm-phone slot

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../ci/lib/device.sh
source "$SCRIPT_DIR/../ci/lib/device.sh"

APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
RUNS="${REROUTE_RUNS:-20}"
PROFILES="${REROUTE_PROFILE:-both}"
AREA="${REROUTE_AREA:-lviv}"
TICK_MS="${REROUTE_TICK_MS:-250}"
DETOUR_METERS="${REROUTE_DETOUR_M:-60}"
WARMUP="${REROUTE_WARMUP:-1}"
OUTPUT="${REROUTE_OUTPUT:-$ROOT/build/benchmarks/reroute-latency}"
PACKAGE="nl.r1a.grassmap"
ACTIVITY="nl.r1a.grassmap.app.MainActivity"
GRASSCTL="$ROOT/tools/control/grassctl.py"
COMMAND="core.nav.test_follow"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[[ -f "$APK" ]] || fail "APK not found: $APK
Build it through the build slot first:
  JAVA_HOME=\$HOME/dotfiles/local/share/jdk agent-lock run gm-build --timeout 30m -- ./gradlew :app:assembleDebug"
command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || fail "python3 is not on PATH"
[[ -f "$GRASSCTL" ]] || fail "control client not found: $GRASSCTL"

SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
if [[ -z "${CI:-}" && -z "${GRASS_MAPS_IN_PHONE_SLOT:-}" && -z "${REROUTE_NO_LOCK:-}" ]] &&
    command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_PHONE_SLOT=1
    exec agent-lock run gm-phone --timeout 1h -- "$SCRIPT_PATH" "$@"
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

SERIAL="${REROUTE_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$SERIAL" ]]; then
    devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    count="$(printf '%s\n' "$devices" | grep -c . || true)"
    [[ "$count" == "1" ]] ||
        fail "expected exactly one attached device, found ${count:-0}; set REROUTE_SERIAL"
    SERIAL="$devices"
fi
case "$SERIAL" in
    emulator-*)
        fail "this measurement is phone-only; $SERIAL is an emulator. The emulator trend is T-0025"
        ;;
esac
echo "== device $SERIAL ==" >&2

wait_for_device 60 -s "$SERIAL"
wait_for_package_manager 120 -s "$SERIAL"

echo "== installing $(basename "$APK") ==" >&2
result="$(timeout 300 adb -s "$SERIAL" install -r "$APK")"
[[ "$result" == *Success* ]] || fail "adb install did not report Success: $result"

adb -s "$SERIAL" shell am force-stop "$PACKAGE"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
deadline=$((SECONDS + 90))
until adb -s "$SERIAL" logcat -d -s GrassMapsMap:I | grep -q "map-ready"; do
    ((SECONDS < deadline)) || fail "the map did not report ready within 90 s"
    sleep 1
done
echo "== map ready; checking the control channel ==" >&2
python3 "$GRASSCTL" --serial "$SERIAL" --timeout 30 commands | grep -q "$COMMAND" ||
    fail "the control channel does not list $COMMAND; is this a debug build?"

if [[ "$PROFILES" == "both" ]]; then
    profile_list=(walk bike)
else
    profile_list=("$PROFILES")
fi
for profile in "${profile_list[@]}"; do
    [[ "$profile" == "walk" || "$profile" == "bike" ]] || fail "unknown profile: $profile"
done

# Runs the command once and prints the measured `reroute_ms`; fails when the
# replay produced no measured reroute.
run_once() {
    local profile="$1"
    local params output reroutes millis
    params="{\"area\":\"$AREA\",\"profile\":\"$profile\",\"tick_ms\":$TICK_MS,\"detour_m\":$DETOUR_METERS}"
    if ! output="$(timeout 300 python3 "$GRASSCTL" --serial "$SERIAL" --timeout 240 invoke "$COMMAND" "$params" 2>&1)"; then
        echo "invoke failed: $output" >&2
        return 1
    fi
    reroutes="$(sed -n 's/.*reroutes=\([0-9][0-9]*\).*/\1/p' <<<"$output" | tail -1)"
    millis="$(sed -n 's/.*reroute_ms=\([0-9][0-9]*\).*/\1/p' <<<"$output" | tail -1)"
    [[ -n "$reroutes" && -n "$millis" ]] || {
        echo "unparsable result: $output" >&2
        return 1
    }
    [[ "$reroutes" -ge 1 && "$millis" -ge 0 ]] || {
        echo "the replay produced no reroute: $output" >&2
        return 1
    }
    echo "$output" >&2
    echo "$millis"
}

mkdir -p "$OUTPUT"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
report="$OUTPUT/reroute-latency-$stamp.txt"
{
    echo "device=$SERIAL apk=$(basename "$APK") area=$AREA tick_ms=$TICK_MS detour_m=$DETOUR_METERS"
    echo "runs=$RUNS warmup=$WARMUP"
} | tee "$report"

for profile in "${profile_list[@]}"; do
    if ((WARMUP > 0)); then
        echo "== $profile warm-up ==" >&2
        for _ in $(seq 1 "$WARMUP"); do
            run_once "$profile" >/dev/null
        done
    fi

    samples=()
    for run in $(seq 1 "$RUNS"); do
        echo "== $profile sample $run/$RUNS ==" >&2
        samples+=("$(run_once "$profile")")
    done
    python3 - "$profile" "${samples[@]}" <<'PY' | tee -a "$report"
import math
import statistics
import sys

profile = sys.argv[1]
samples = sorted(int(value) for value in sys.argv[2:])
count = len(samples)
median = statistics.median(samples)
p95 = samples[max(0, math.ceil(0.95 * count) - 1)]
print(
    f"profile={profile} n={count} median_ms={median:.1f} p95_ms={p95} "
    f"min_ms={samples[0]} max_ms={samples[-1]}"
)
if median >= 300:
    print(f"FAIL: {profile} median {median:.1f} ms is not under 300 ms", file=sys.stderr)
    sys.exit(1)
PY
done

echo "report: $report" >&2
