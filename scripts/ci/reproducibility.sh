#!/usr/bin/env bash
#
# Proves that the release APK is byte-identical across build paths.
#
# Method proven in grass-maps-bench RESULTS.md (T-0008): two copies of the
# tree are built at different absolute paths on the same host, JDK, SDK and
# dependency cache, with AGP dependency metadata disabled
# (`dependenciesInfo` in app/build.gradle.kts). The unsigned release APK is
# compared by sha256 and byte for byte.
#
# The builds are intentionally not cached: --no-build-cache, and each copy is
# a fresh tree, so both APKs are produced by real compilation.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK_REL="app/build/outputs/apk/release/app-release-unsigned.apk"
WORK="$ROOT/build/reproducibility"

export JAVA_HOME="${JAVA_HOME:-$HOME/dotfiles/local/share/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"

cd "$ROOT"

# A copy must not contain previous build outputs, VCS data or Gradle state.
copy_tree() {
    local dest="$1"
    tar -C "$ROOT" \
        --exclude=./.git \
        --exclude=./.gradle \
        --exclude=./.kotlin \
        --exclude=./build \
        --exclude='./*/build' \
        --exclude='./*/.*' \
        --exclude='./*/*/build' \
        -cf - . | tar -C "$dest" -xf -
}

rm -rf "$WORK"
mkdir -p "$WORK/a" "$WORK/b"

for variant in a b; do
    echo "== Building release APK in $WORK/$variant =="
    copy_tree "$WORK/$variant"
    (
        cd "$WORK/$variant"
        ./gradlew --console=plain --no-build-cache :app:assembleRelease
    )
done

apk_a="$WORK/a/$APK_REL"
apk_b="$WORK/b/$APK_REL"

for apk in "$apk_a" "$apk_b"; do
    if [[ ! -f "$apk" ]]; then
        echo "FAIL: expected unsigned release APK at $apk" >&2
        exit 1
    fi
done

sha_a="$(sha256sum "$apk_a" | cut -d' ' -f1)"
sha_b="$(sha256sum "$apk_b" | cut -d' ' -f1)"
size_a="$(stat -c%s "$apk_a")"
size_b="$(stat -c%s "$apk_b")"

echo "build a: sha256 $sha_a, $size_a bytes"
echo "build b: sha256 $sha_b, $size_b bytes"

if [[ "$sha_a" != "$sha_b" ]] || ! cmp -s "$apk_a" "$apk_b"; then
    echo "FAIL: the two unsigned release APKs are not byte-identical." >&2
    echo "First differing bytes (offset, value in a, value in b):" >&2
    cmp -l "$apk_a" "$apk_b" | head -n 10 >&2 || true
    exit 1
fi

echo "REPRODUCIBLE: both unsigned release APKs have sha256 $sha_a"
