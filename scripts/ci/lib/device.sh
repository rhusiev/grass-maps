#!/usr/bin/env bash
#
# Bounded waits for a connected device or emulator, shared by the CI scripts.
#
# Source this file. Both functions take the wait limit in seconds, followed by
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

# wait_for_system_services SECONDS [adb target args...]
#
# The package manager answers `pm path android` before the settings and
# install services are ready. Calls made in that window fail with
# "cmd: Failure calling service ...: Broken pipe (32)", which is what made
# the benchmark and smoke jobs fail right after boot. Probe both services.
wait_for_system_services() {
    local seconds="$1"
    shift
    local deadline=$((SECONDS + seconds))
    until timeout 10 adb "$@" shell pm path android >/dev/null 2>&1 &&
        timeout 10 adb "$@" shell settings get global window_animation_scale >/dev/null 2>&1; do
        if ((SECONDS >= deadline)); then
            echo "FAIL: system services were not ready in ${seconds}s" >&2
            return 1
        fi
        sleep 2
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
