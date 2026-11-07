#!/usr/bin/env bash
#
# The CI golden run: Standard light and dark first, then the T-0102 fixture set
# on the same booted emulator. A compare failure does not stop the next set,
# because a capture-only run is how the references are promoted; the exit code
# still reports any failure.
#
# Usage: android-goldens-ci.sh [APK]

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
status=0

cd "$ROOT"

if ! ./scripts/ci/android-map-goldens.sh --profile standard --style both "$APK"; then
    status=1
fi
if ! GOLDEN_SKIP_REBOOT=1 ./scripts/ci/android-map-goldens.sh --profile fixture \
    --output "$ROOT/build/map-goldens/fixture" "$APK"; then
    status=1
fi
exit "$status"
