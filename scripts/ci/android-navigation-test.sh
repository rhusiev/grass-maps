#!/usr/bin/env bash
#
# Runs the instrumented Ferrostar replay on the emulator the workflow booted.
#
# The Ferrostar core ships Android ABIs only, so this is the only job that runs
# the production follower; every JVM test uses GeometryRouteFollower instead.
#
# A transient "Broken pipe (32)" right after boot is the same failure the
# benchmark job sees. The readiness gate covers it; if it still happens while
# Gradle installs the APKs, the run is repeated once. A test assertion failure
# never matches the transient patterns and is not retried.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

DEVICE_DEADLINE_SECONDS="${NAV_TEST_DEVICE_DEADLINE_SECONDS:-240}"
TEST_LOG="${NAV_TEST_LOG:-$ROOT/build/ci/navigation-connected-test.log}"
TRANSIENT_PATTERN='Broken pipe|Failure calling service|device offline|device not found'

cd "$ROOT"
mkdir -p "$(dirname "$TEST_LOG")"
# The workflow reads this to tell a failed emulator boot from a failed test.
: >"$ROOT/build/ci/navigation-attempt-started"

wait_for_device "$DEVICE_DEADLINE_SECONDS"
wait_for_package_manager 240
wait_for_system_services 180

run_tests() {
    ./gradlew --console=plain :navigation:connectedDebugAndroidTest
}

if run_tests 2>&1 | tee "$TEST_LOG"; then
    exit 0
fi

if ! grep -qE "$TRANSIENT_PATTERN" "$TEST_LOG"; then
    exit 1
fi

wait_for_device "$DEVICE_DEADLINE_SECONDS"
wait_for_package_manager 240
wait_for_system_services 180
{
    echo "== transient device failure, retrying the connected test once =="
    run_tests
} 2>&1 | tee -a "$TEST_LOG"
