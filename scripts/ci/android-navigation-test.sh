#!/usr/bin/env bash
#
# Runs the instrumented Ferrostar replay on the emulator the workflow booted.
#
# The Ferrostar core ships Android ABIs only, so this is the only job that runs
# the production follower; every JVM test uses GeometryRouteFollower instead.
#
# A post-boot "Broken pipe (32)" is the runner failure the benchmark job sees.
# The gate runs after the Gradle build and right before the install. A cold
# build takes about five minutes on the runner, and the device state at the start
# of it says nothing about the moment ddmlib reads `ro.build.version.sdk` for
# the install (runs 44119043551 and 44155583864: "API level=1"). If a service
# dies after the gate, the job fails and the log names it. The test is not
# retried.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

DEVICE_DEADLINE_SECONDS="${NAV_TEST_DEVICE_DEADLINE_SECONDS:-240}"
DEVICE_READY_SECONDS="${NAV_TEST_DEVICE_READY_SECONDS:-300}"
EXPECTED_API_LEVEL="${EXPECTED_API_LEVEL:-29}"
TEST_LOG="${NAV_TEST_LOG:-$ROOT/build/ci/navigation-connected-test.log}"

cd "$ROOT"
mkdir -p "$(dirname "$TEST_LOG")"

wait_for_device "$DEVICE_DEADLINE_SECONDS"

./gradlew --console=plain :navigation:assembleDebugAndroidTest

wait_for_device_ready "$DEVICE_READY_SECONDS" "$EXPECTED_API_LEVEL"

./gradlew --console=plain :navigation:connectedDebugAndroidTest 2>&1 | tee "$TEST_LOG"
