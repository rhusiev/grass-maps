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
# Usage: ./check.sh [--fast|--skip-repro] [--micro|--skip-lint] [--with-device]
#   --fast         skip the two reproducible release builds (the slow part)
#   --skip-repro   alias for --fast
#   --micro        --fast and skip Android lint, the iteration loop
#   --skip-lint    skip Android lint only
#   --with-device  also install and launch the debug APK on a connected device

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIP_REPRO=0
SKIP_LINT=0
WITH_DEVICE=0
failures=0

for arg in "$@"; do
    case "$arg" in
        --fast | --skip-repro) SKIP_REPRO=1 ;;
        --micro) SKIP_REPRO=1; SKIP_LINT=1 ;;
        --skip-lint) SKIP_LINT=1 ;;
        --with-device) WITH_DEVICE=1 ;;
        -h | --help)
            cat <<'EOF'
Usage: ./check.sh [--fast|--skip-repro] [--micro|--skip-lint] [--with-device]
  --fast         skip the two reproducible release builds (the slow part)
  --skip-repro   alias for --fast
  --micro        --fast and skip Android lint, the iteration loop
  --skip-lint    skip Android lint only
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

# The Rust reader and the OSM extractor have their own tests. Keep their build
# directory under the repository so a check run does not fill the home
# partition, and resolve the cargo home physically: the crate's uniffi
# templates fail when it is reached through a symlink.
CARGO="${CARGO:-$(command -v cargo || true)}"
if [[ -z "$CARGO" ]]; then
    echo "check.sh: cargo is required" >&2
    exit 1
fi
if [[ -n "${CARGO_HOME:-}" ]]; then
    CARGO_HOME="$(cd "$CARGO_HOME" && pwd -P)"
    export CARGO_HOME
fi
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/rust-test-target}"

cd "$ROOT"

if [[ -z "${GRASS_MAPS_IN_BUILD_SLOT:-}" && -z "${CI:-}" ]] && command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_BUILD_SLOT=1
    exec agent-lock run gm-build --timeout 30m -- "$0" "$@"
fi

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

if [[ $SKIP_LINT -eq 0 ]]; then
    step "Android lint"
    ./gradlew --console=plain lint || fail "lint"
else
    step "Android lint: skipped by --micro/--skip-lint"
    echo "Lint runs at pre-push and in CI; do not treat this run as merge evidence."
fi

step "Build :app:assembleDebug"
./gradlew --console=plain :app:assembleDebug || fail "build"

step "Unit tests"
./gradlew --console=plain test || fail "unit tests (Gradle test task)"
scripts/ci/test-count.sh || fail "unit tests (empty suite)"

step "Rust tests"
( cd "$ROOT/rust/grass-core" && "$CARGO" test --locked ) || fail "rust tests (grass-core)"
( cd "$ROOT/tools/pipeline/osm-extract" && "$CARGO" test --locked ) || fail "rust tests (osm-extract)"

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

step "Map style rules"
./gradlew --console=plain lintStyleRules || fail "map style rules"

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
