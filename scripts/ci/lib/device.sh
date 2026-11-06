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
