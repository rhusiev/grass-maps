#!/usr/bin/env bash
#
# The CI golden run, as named sets: `meadow` (Meadow light and dark;
# `meadow-light` and `meadow-dark` run one style each), `trail` (the same
# cases in the Trail style; `trail-light` and `trail-dark` run one each),
# `contours` (the fixture's contour archive on), `fixture` (the T-0102 set,
# contours off), `navigation`, and `raster` (overlay and underlay over the
# loopback tile fixture; the one set that turns the radios on, so it runs last).
#
# With no set named, every set runs in that order on the one booted emulator,
# which is the local use. CI runs one set per job, each on its own emulator, so
# the sets run in parallel (one job took 2-3.5 h and hit its 210 min bound;
# each Meadow style alone took ~45 min, so CI runs the two styles apart).
# A compare failure does not stop the next set, because a capture-only run is
# how the references are promoted; the exit code still reports any failure.
#
# Usage: android-goldens-ci.sh [APK] [SET...]

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
shift || true
readonly ALL_SETS=(meadow trail contours fixture navigation raster)
readonly KNOWN_SETS=(meadow meadow-light meadow-dark trail trail-light trail-dark contours fixture navigation raster)
if (($# > 0)); then
    SETS=("$@")
else
    SETS=("${ALL_SETS[@]}")
fi
status=0

cd "$ROOT"

# The first set on a freshly booted emulator does the dialog-suppression
# reboot; later sets on the same emulator skip it.
skip_reboot=0

run_set() {
    case "$1" in
        meadow)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile meadow \
                --style both "$APK"
            ;;
        meadow-light | meadow-dark)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile meadow \
                --style "${1#meadow-}" --output "$ROOT/build/map-goldens/$1" "$APK"
            ;;
        trail)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile trail \
                --style trail "$APK"
            ;;
        trail-light | trail-dark)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile trail \
                --style "$1" --output "$ROOT/build/map-goldens/$1" "$APK"
            ;;
        contours)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile contours \
                --style all --contours on "$APK"
            ;;
        fixture)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile fixture \
                --output "$ROOT/build/map-goldens/fixture" "$APK"
            ;;
        navigation)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-navigation-goldens.sh "$APK"
            ;;
        raster)
            GOLDEN_SKIP_REBOOT="$skip_reboot" ./scripts/ci/android-map-goldens.sh --profile raster \
                --output "$ROOT/build/map-goldens/raster" "$APK"
            ;;
        *)
            echo "android-goldens-ci.sh: unknown set '$1' (known: ${KNOWN_SETS[*]})" >&2
            return 2
            ;;
    esac
}

for set in "${SETS[@]}"; do
    if ! run_set "$set"; then
        status=1
    fi
    skip_reboot=1
done
exit "$status"
