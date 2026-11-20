#!/usr/bin/env bash
#
# Builds the release APK, aligns it, signs it with the release key named by the
# environment, verifies the signature and prints the certificate digest. It
# refuses to run when a variable or the keystore is missing, and it never
# prints a password: apksigner reads both from the environment.
#
# Gradle builds the APK unsigned for this script, so the alignment and
# signature below are the only ones applied. `./gradlew :app:assembleRelease`
# with the same variables set signs the APK itself, without this script.
#
# Usage: scripts/release/build-signed-apk.sh [OUTPUT_APK]
#   OUTPUT_APK  where to write the signed APK; default
#               app/build/outputs/apk/release/grassmaps-release-signed.apk
#
# Variables, all required:
#   GRASSMAPS_KEYSTORE           path to the keystore, outside the repository
#   GRASSMAPS_KEYSTORE_PASSWORD  keystore password
#   GRASSMAPS_KEY_ALIAS          key alias inside the keystore
#   GRASSMAPS_KEY_PASSWORD       key password
#
# ANDROID_BUILD_TOOLS may name a build-tools directory; otherwise the newest
# one with apksigner and zipalign is used.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/dotfiles/local/share/jdk}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export ANDROID_HOME

if [[ $# -gt 1 ]]; then
    sed -n '/^# Usage:/,/^#   OUTPUT_APK/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

OUTPUT="${1:-$ROOT/app/build/outputs/apk/release/grassmaps-release-signed.apk}"
if [[ "$OUTPUT" != /* ]]; then
    OUTPUT="$PWD/$OUTPUT"
fi

missing=()
for variable in GRASSMAPS_KEYSTORE GRASSMAPS_KEYSTORE_PASSWORD GRASSMAPS_KEY_ALIAS GRASSMAPS_KEY_PASSWORD; do
    if [[ -z "${!variable:-}" ]]; then
        missing+=("$variable")
    fi
done
if (( ${#missing[@]} > 0 )); then
    printf 'build-signed-apk.sh: missing %s\n' "${missing[*]}" >&2
    exit 1
fi
if [[ ! -f "$GRASSMAPS_KEYSTORE" ]]; then
    echo "build-signed-apk.sh: the keystore named by GRASSMAPS_KEYSTORE is not a file" >&2
    exit 1
fi
if [[ ! -r "$GRASSMAPS_KEYSTORE" ]]; then
    echo "build-signed-apk.sh: the keystore named by GRASSMAPS_KEYSTORE is not readable" >&2
    exit 1
fi

build_tools="${ANDROID_BUILD_TOOLS:-}"
if [[ -z "$build_tools" ]]; then
    while IFS= read -r candidate; do
        if [[ -x "$candidate/apksigner" && -x "$candidate/zipalign" ]]; then
            build_tools="$candidate"
        fi
    done < <(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V)
fi
if [[ -z "$build_tools" || ! -x "$build_tools/apksigner" || ! -x "$build_tools/zipalign" ]]; then
    echo "build-signed-apk.sh: no build-tools with apksigner and zipalign under $ANDROID_HOME" >&2
    exit 1
fi
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"

unsigned="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
aligned="$OUTPUT.aligned.apk"
mkdir -p "$(dirname "$OUTPUT")"
trap 'rm -f "$aligned"' EXIT

echo "== Building the unsigned release APK =="
(
    cd "$ROOT"
    env -u GRASSMAPS_KEYSTORE \
        -u GRASSMAPS_KEYSTORE_PASSWORD \
        -u GRASSMAPS_KEY_ALIAS \
        -u GRASSMAPS_KEY_PASSWORD \
        ./gradlew --console=plain :app:assembleRelease
)

if [[ ! -f "$unsigned" ]]; then
    echo "build-signed-apk.sh: expected the unsigned APK at $unsigned" >&2
    exit 1
fi

alignment=(-p)
if "$zipalign" 2>&1 | grep -q -- '-P <pagesize_kb>'; then
    alignment=(-P 16)
fi

echo "== Aligning =="
"$zipalign" -f "${alignment[@]}" 4 "$unsigned" "$aligned"

echo "== Signing =="
rm -f "$OUTPUT"
"$apksigner" sign \
    --ks "$GRASSMAPS_KEYSTORE" \
    --ks-key-alias "$GRASSMAPS_KEY_ALIAS" \
    --ks-pass "env:GRASSMAPS_KEYSTORE_PASSWORD" \
    --key-pass "env:GRASSMAPS_KEY_PASSWORD" \
    --v4-signing-enabled false \
    --out "$OUTPUT" \
    "$aligned"

echo "== Verifying =="
verify_output="$("$apksigner" verify --print-certs "$OUTPUT")"
certificate="$(printf '%s\n' "$verify_output" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
if [[ -z "$certificate" ]]; then
    echo "build-signed-apk.sh: apksigner verify printed no certificate digest" >&2
    exit 1
fi

printf 'Certificate SHA-256: %s\n' "$certificate"
printf 'APK: %s\n' "$OUTPUT"
