#!/usr/bin/env bash
#
# Installs the Android NDK that buildSrc pins in RustNative.NDK_VERSION.
#
# CI images carry whichever NDK revisions they were built with, and the build
# now refuses a different revision instead of taking the newest one. Read the
# version from the one source so a bump does not have to edit every workflow.

set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT/buildSrc/src/main/kotlin/buildlogic/RustNative.kt"
VERSION="$(sed -n 's/.*const val NDK_VERSION: String = "\([^"]*\)".*/\1/p' "$SOURCE" | head -n 1)"
if [[ -z "$VERSION" ]]; then
    echo "Cannot read NDK_VERSION from $SOURCE" >&2
    exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    SDKMANAGER="$(command -v sdkmanager || true)"
fi
if [[ -z "$SDKMANAGER" || ! -x "$SDKMANAGER" ]]; then
    echo "No sdkmanager: install Android command-line tools under $ANDROID_HOME" >&2
    exit 1
fi

if [[ "$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_HOME/ndk/$VERSION/source.properties" 2>/dev/null)" == "$VERSION" ]]; then
    echo "Android NDK $VERSION is already installed"
    exit 0
fi

yes | "$SDKMANAGER" --install "ndk;$VERSION"
if [[ "$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_HOME/ndk/$VERSION/source.properties" 2>/dev/null)" != "$VERSION" ]]; then
    echo "sdkmanager did not install Android NDK $VERSION under $ANDROID_HOME/ndk" >&2
    exit 1
fi
echo "Installed Android NDK $VERSION"
