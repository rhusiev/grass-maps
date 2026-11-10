#!/usr/bin/env bash
#
# The T-0203 keystroke budget on a device: the committed probe set typed letter
# by letter through the search screen's own session, timed in the app.
#
# Each keystroke runs `SearchScreenSession.submit` on the device, so the number
# includes the session publish, the command dispatcher and the uniffi bridge
# into the Rust reader. It does not include the adb round trip: the timing is
# taken inside the app and the control channel returns one summary.
#
# The g32 acceptance run is the orchestrator's: connect the phone, then
#
#   SEARCH_SERIAL=<serial> ./scripts/bench/search-keystrokes.sh \
#       app/build/outputs/apk/debug/app-debug.apk
#
# The debug APK carries the control channel. No phone setting is changed: the
# script installs the build, copies the committed Lviv section into the app's
# external files directory, starts the activity and invokes one command.
#
# Environment:
#   SEARCH_SERIAL     adb serial; default: the only attached device
#   SEARCH_INDEX      index directory name (default lviv-addresses)
#   SEARCH_RUNS       replay passes to pool (default 3)
#   SEARCH_LIMIT      results per keystroke (default 5)
#   SEARCH_BOOT_SECONDS  how long to wait for the app's control channel (default 60)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../ci/lib/device.sh
source "$SCRIPT_DIR/../ci/lib/device.sh"

PACKAGE="nl.r1a.grassmap"
ACTIVITY="$PACKAGE.app.MainActivity"
DEVICE_SEARCH_DIR="/sdcard/Android/data/$PACKAGE/files/search"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
INDEX="${SEARCH_INDEX:-lviv-addresses}"
RUNS="${SEARCH_RUNS:-3}"
LIMIT="${SEARCH_LIMIT:-5}"
BOOT_SECONDS="${SEARCH_BOOT_SECONDS:-60}"
SOURCE_SECTION="$ROOT/places/src/test/resources/search/$INDEX"
QUERIES="$ROOT/places/src/test/resources/search/queries.tsv"
POSITION='{"lat":49.8419,"lon":24.0316}'

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
ADB=(adb)
GRASSCTL=("$ROOT/tools/control/grassctl.py")
if [[ -n "${SEARCH_SERIAL:-}" ]]; then
    ADB+=(-s "$SEARCH_SERIAL")
    GRASSCTL+=(--serial "$SEARCH_SERIAL")
fi

if [[ ! -f "$APK" ]]; then
    echo "search-keystrokes: no APK at $APK" >&2
    exit 1
fi
if [[ ! -d "$SOURCE_SECTION" ]]; then
    echo "search-keystrokes: no committed index at $SOURCE_SECTION" >&2
    exit 1
fi
if [[ ! -f "$QUERIES" ]]; then
    echo "search-keystrokes: no committed probe set at $QUERIES" >&2
    exit 1
fi

wait_for_device 120 "${ADB[@]:1}"
wait_for_package_manager 120 "${ADB[@]:1}"

echo "device: $(timeout 10 "${ADB[@]}" shell getprop ro.product.model | tr -d '\r') " \
    "sdk=$(timeout 10 "${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r') " \
    "abi=$(timeout 10 "${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"

timeout 300 "${ADB[@]}" install -r -t "$APK" >/dev/null
timeout 30 "${ADB[@]}" shell am force-stop "$PACKAGE"

# The index lives under the app's external files directory, where PlacesAccess
# looks for it. Copying the committed section keeps the probe set and the index
# in step with the repository.
timeout 30 "${ADB[@]}" shell mkdir -p "$DEVICE_SEARCH_DIR/$INDEX"
timeout 120 "${ADB[@]}" push "$SOURCE_SECTION/." "$DEVICE_SEARCH_DIR/$INDEX/" >/dev/null

timeout 30 "${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null

deadline=$((SECONDS + BOOT_SECONDS))
until timeout 20 "${GRASSCTL[@]}" commands >/dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
        echo "search-keystrokes: the control channel was not ready in ${BOOT_SECONDS}s" >&2
        exit 1
    fi
    sleep 2
done

PARAMS="$(
    INDEX="$INDEX" RUNS="$RUNS" LIMIT="$LIMIT" POSITION="$POSITION" QUERIES="$QUERIES" python3 - <<'PY'
import json
import os

queries = [
    line.split("\t")[0].strip()
    for line in open(os.environ["QUERIES"], encoding="utf-8")
    if line.strip() and not line.startswith("#")
]
print(
    json.dumps(
        {
            "queries": queries,
            "index": os.environ["INDEX"],
            "runs": int(os.environ["RUNS"]),
            "limit": int(os.environ["LIMIT"]),
            "position": json.loads(os.environ["POSITION"]),
        }
    )
)
PY
)"

echo "replay: index=$INDEX runs=$RUNS probes=$(grep -vc '^#' "$QUERIES")"
timeout 600 "${GRASSCTL[@]}" --timeout 120 invoke core.search.replay "$PARAMS"
