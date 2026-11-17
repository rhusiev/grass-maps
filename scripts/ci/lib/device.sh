#!/usr/bin/env bash
#
# Bounded waits for a connected device or emulator, shared by the CI scripts.
#
# Source this file. Each function takes the wait limit in seconds, followed by
# any `adb` target arguments (`-s SERIAL`). They fail instead of hanging when
# no device attaches.

# wait_for_device SECONDS [adb target args...]
wait_for_device() {
    local seconds="$1"
    shift
    local deadline=$((SECONDS + seconds))
    until timeout 10 adb "$@" shell true >/dev/null 2>&1; do
        if ((SECONDS >= deadline)); then
            echo "FAIL: no device or emulator became available in ${seconds}s" >&2
            timeout 10 adb "$@" devices >&2 || true
            return 1
        fi
        sleep 2
    done
}

# wait_for_package_manager SECONDS [adb target args...]
wait_for_package_manager() {
    local seconds="$1"
    shift
    local deadline=$((SECONDS + seconds))
    until timeout 10 adb "$@" shell pm path android >/dev/null 2>&1; do
        if ((SECONDS >= deadline)); then
            echo "FAIL: package manager was not ready in ${seconds}s" >&2
            return 1
        fi
        sleep 2
    done
}

# wait_for_device_ready SECONDS EXPECTED_API [adb target args...]
#
# The full gate before any install. All probes must pass together
# DEVICE_READY_STABLE_PROBES times in a row:
#   1. `sys.boot_completed` is 1
#   2. `ro.build.version.sdk` is EXPECTED_API (any number of two digits or
#      more when EXPECTED_API is empty)
#   3. the package manager answers
#   4. the activity manager answers
#   5. the settings service answers
# The package manager answers `pm path android` before the settings and
# install services are ready. Calls made in that window fail with "cmd:
# Failure calling service ...: Broken pipe (32)", which failed the benchmark
# and smoke jobs right after boot. One answer is not enough: on the hosted
# API 35 runner the system server answered, then restarted, and `adb install`
# failed (run 43863749676). Hence the consecutive passes.
# Gradle's connected test reads `ro.build.version.sdk` through ddmlib. When
# that read finds nothing, ddmlib reports "API level=1" and refuses to
# install ("Cannot install split APKs with API level < 21", runs
# 44119043551 and 44155583864). On timeout the message names the probe that
# failed last.
DEVICE_READY_STABLE_PROBES=3
DEVICE_POLL_INTERVAL_SECONDS="${DEVICE_POLL_INTERVAL_SECONDS:-2}"

# _device_probe_failure EXPECTED_API [adb target args...]
# Prints the failed probe and returns 1, or prints nothing and returns 0.
_device_probe_failure() {
    local expected="$1"
    shift
    local value
    value="$(timeout 10 adb "$@" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || true
    if [[ "$value" != "1" ]]; then
        echo "sys.boot_completed is '${value}', expected '1'"
        return 1
    fi
    value="$(timeout 10 adb "$@" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" || true
    if [[ -n "$expected" && "$value" != "$expected" ]] ||
        [[ -z "$expected" && ! "$value" =~ ^[0-9]{2,}$ ]]; then
        echo "ro.build.version.sdk is '${value}', expected '${expected:-a number}'"
        return 1
    fi
    if ! timeout 10 adb "$@" shell pm path android >/dev/null 2>&1; then
        echo "package manager did not answer 'pm path android'"
        return 1
    fi
    if ! timeout 10 adb "$@" shell am get-current-user >/dev/null 2>&1; then
        echo "activity manager did not answer 'am get-current-user'"
        return 1
    fi
    if ! timeout 10 adb "$@" shell settings get global window_animation_scale >/dev/null 2>&1; then
        echo "settings service did not answer 'settings get global'"
        return 1
    fi
}

wait_for_device_ready() {
    local seconds="$1"
    local expected="$2"
    shift 2
    local deadline=$((SECONDS + seconds))
    local answered=0
    local reason="no probe ran"
    while ((answered < DEVICE_READY_STABLE_PROBES)); do
        if reason="$(_device_probe_failure "$expected" "$@")"; then
            answered=$((answered + 1))
        else
            answered=0
        fi
        if ((answered < DEVICE_READY_STABLE_PROBES)); then
            if ((SECONDS >= deadline)); then
                echo "FAIL: device not ready after ${seconds}s, needed ${DEVICE_READY_STABLE_PROBES} passes in a row. Last failed probe: ${reason}" >&2
                return 1
            fi
            sleep "$DEVICE_POLL_INTERVAL_SECONDS"
        fi
    done
}

# set_animation_scales_zero SECONDS [adb target args...]
#
# Same three settings the emulator runner disables. Doing it here, after the
# system-service probe, keeps the runner's own boot-time call out of the
# fragile window.
set_animation_scales_zero() {
    local seconds="$1"
    shift
    local deadline=$((SECONDS + seconds))
    local scale
    for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
        until timeout 30 adb "$@" shell settings put global "$scale" 0.0 >/dev/null 2>&1; do
            if ((SECONDS >= deadline)); then
                echo "FAIL: could not disable $scale in ${seconds}s" >&2
                return 1
            fi
            sleep 2
        done
    done
}
