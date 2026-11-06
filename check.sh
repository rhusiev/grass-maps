#!/usr/bin/env bash
#
# The local gate. Run this before merging; CI runs the same checks.
#
# On this machine the script re-executes itself under the `gm-build` slot, so
# Gradle never runs outside the slot. Export GRASS_MAPS_IN_BUILD_SLOT=1 to
# suppress that (CI sets CI=true, which also suppresses it).
#
# Usage: ./check.sh [--skip-repro] [--with-device]
#   --skip-repro   skip the two reproducible release builds (the slow part)
#   --with-device  also install and launch the debug APK on a connected device

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_REPRO=0
WITH_DEVICE=0

for arg in "$@"; do
    case "$arg" in
        --skip-repro) SKIP_REPRO=1 ;;
        --with-device) WITH_DEVICE=1 ;;
        -h | --help)
            cat <<'EOF'
Usage: ./check.sh [--skip-repro] [--with-device]
  --skip-repro   skip the two reproducible release builds (the slow part)
  --with-device  also install and launch the debug APK on a connected device
EOF
            exit 0
            ;;
        *)
            echo "check.sh: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

export JAVA_HOME="${JAVA_HOME:-$HOME/dotfiles/local/share/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"

cd "$ROOT"

if [[ -z "${GRASS_MAPS_IN_BUILD_SLOT:-}" && -z "${CI:-}" ]] && command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_BUILD_SLOT=1
    exec agent-lock run gm-build --timeout 30m -- "$0" "$@"
fi

step() {
    printf '\n== %s ==\n' "$1"
}

step "Format (Spotless with ktlint)"
./gradlew --console=plain spotlessCheck

step "Android lint"
./gradlew --console=plain lint

step "Build :app:assembleDebug"
./gradlew --console=plain :app:assembleDebug

step "Unit tests"
./gradlew --console=plain test
scripts/ci/test-count.sh

step "Plugin boundaries"
./gradlew --console=plain checkPluginBoundaries

if [[ $SKIP_REPRO -eq 0 ]]; then
    step "Reproducibility (two release builds in separate paths)"
    scripts/ci/reproducibility.sh
else
    step "Reproducibility: skipped by --skip-repro"
    echo "CI runs this in the reproducibility job; do not treat this run as complete evidence."
fi

if [[ $WITH_DEVICE -eq 1 ]]; then
    step "API 29 emulator smoke and strict mode"
    scripts/ci/android-smoke.sh app/build/outputs/apk/debug/app-debug.apk
else
    step "Device smoke: not run"
    echo "Pass --with-device with an emulator or phone connected; CI runs it on API 29."
fi

printf '\nAll requested checks passed\n'
