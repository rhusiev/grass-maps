#!/usr/bin/env bash
#
# Runs the instrumented Ferrostar replay on the emulator the workflow booted.
#
# The Ferrostar core ships Android ABIs only, so this is the only job that runs
# the production follower; every JVM test uses GeometryRouteFollower instead.
#
# A post-boot "Broken pipe (32)" is the runner failure the benchmark job sees.
# The readiness gate below covers the window before Gradle installs the APKs;
# if a service dies after that, the job fails and the log names it. The test is
# not retried.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=lib/device.sh
source "$SCRIPT_DIR/lib/device.sh"

DEVICE_DEADLINE_SECONDS="${NAV_TEST_DEVICE_DEADLINE_SECONDS:-240}"
TEST_LOG="${NAV_TEST_LOG:-$ROOT/build/ci/navigation-connected-test.log}"

cd "$ROOT"
mkdir -p "$(dirname "$TEST_LOG")"

wait_for_device "$DEVICE_DEADLINE_SECONDS"
wait_for_package_manager 240
wait_for_system_services 180

./gradlew --console=plain :navigation:connectedDebugAndroidTest 2>&1 | tee "$TEST_LOG"
