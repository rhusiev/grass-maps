#!/usr/bin/env bash
#
# The local gate. Run this before merging; CI runs the same checks.
#
# On this machine the script re-executes itself under the `gm-build` slot, so
# Gradle never runs outside the slot. Export GRASS_MAPS_IN_BUILD_SLOT=1 to
# suppress that (CI sets CI=true, which also suppresses it).
#
# All steps run even when one fails, so a single run names every gap. The exit
# code is non-zero when any step failed.
#
# Usage: ./check.sh [--fast|--skip-repro] [--with-device]
#   --fast         skip the two reproducible release builds (the slow part)
#   --skip-repro   alias for --fast
#   --with-device  also install and launch the debug APK on a connected device

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_REPRO=0
WITH_DEVICE=0
failures=0

for arg in "$@"; do
    case "$arg" in
        --fast | --skip-repro) SKIP_REPRO=1 ;;
        --with-device) WITH_DEVICE=1 ;;
        -h | --help)
            cat <<'EOF'
Usage: ./check.sh [--fast|--skip-repro] [--with-device]
  --fast         skip the two reproducible release builds (the slow part)
  --skip-repro   alias for --fast
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

step_start=$SECONDS
script_start=$SECONDS

step() {
    if [[ -n "${step_start:-}" ]]; then
        printf '  (previous step: %ss)\n' "$((SECONDS - step_start))"
    fi
    printf '\n== %s ==\n' "$1"
    step_start=$SECONDS
}

fail() {
    failures=$((failures + 1))
    echo "FAILED: $1" >&2
}

step "Format (Spotless with ktlint)"
./gradlew --console=plain spotlessCheck || fail "format"

step "Android lint"
./gradlew --console=plain lint || fail "lint"

step "Build :app:assembleDebug"
./gradlew --console=plain :app:assembleDebug || fail "build"

step "Unit tests"
./gradlew --console=plain test || fail "unit tests (Gradle test task)"
scripts/ci/test-count.sh || fail "unit tests (empty suite)"

step "Script tests (map golden capture)"
python3 scripts/ci/map_goldens_test.py || fail "script tests (map goldens)"

step "Translations (English and Ukrainian)"
./gradlew --console=plain checkTranslations || fail "translations"

step "Plugin boundaries"
./gradlew --console=plain checkPluginBoundaries || fail "plugin boundaries"

step "Plugin manifests"
./gradlew --console=plain checkPluginManifests || fail "plugin manifests"

step "Example plugin"
./gradlew --console=plain :plugins:example:check || fail "example plugin"

step "Privacy guards"
./gradlew --console=plain checkPrivacyGuards || fail "privacy guards"

step "Build logic tests"
./gradlew --console=plain -p buildSrc test || fail "build logic tests"

if [[ $SKIP_REPRO -eq 0 ]]; then
    step "Reproducibility (two release builds in separate paths)"
    scripts/ci/reproducibility.sh || fail "reproducibility"
else
    step "Reproducibility: skipped by --fast/--skip-repro"
    echo "CI runs this in the reproducibility job; do not treat this run as complete evidence."
fi

if [[ $WITH_DEVICE -eq 1 ]]; then
    step "API 29 emulator smoke and strict mode"
    scripts/ci/android-smoke.sh app/build/outputs/apk/debug/app-debug.apk || fail "device smoke"
else
    step "Device smoke: not run"
    echo "Pass --with-device with an emulator or phone connected; CI runs it on API 29."
fi

if [[ $failures -ne 0 ]]; then
    printf '\n%d check step(s) failed (%ss total)\n' "$failures" "$((SECONDS - script_start))"
    exit 1
fi

printf '\nAll requested checks passed (%ss total)\n' "$((SECONDS - script_start))"
