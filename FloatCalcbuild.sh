#!/usr/bin/env bash
set -Eeuo pipefail

# Build the standalone FloatCalc Android app as a signed release APK.
# This script is self-contained and builds only the project containing it.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$ROOT_DIR"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/tmp/android-sdk}}"
CMDLINE_TOOLS_VERSION="13114758"
TOOLS_ARCHIVE="${TMPDIR:-/tmp}/commandlinetools-${CMDLINE_TOOLS_VERSION}.zip"
TOOLS_STAGE="${TMPDIR:-/tmp}/android-cmdline-tools-${CMDLINE_TOOLS_VERSION}"
KEYSTORE="$ROOT_DIR/my-release-key.jks"
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"

trap 'printf "\nBuild failed at line %s. See the output above for the first error.\n" "$LINENO" >&2' ERR

die() {
    echo "Error: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' is required but is not installed."
}

setup_java() {
    require_command java
    local java_bin
    java_bin="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$java_bin")")}"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using Java: $JAVA_HOME"
}

setup_android_sdk() {
    require_command curl
    require_command unzip
    mkdir -p "$SDK/cmdline-tools"

    local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "$sdkmanager" ]]; then
        echo "Android command-line tools not found; downloading them..."
        rm -rf "$SDK/cmdline-tools/latest" "$TOOLS_STAGE"
        mkdir -p "$TOOLS_STAGE"
        curl -fsSL --retry 3 --retry-delay 2 \
            -o "$TOOLS_ARCHIVE" \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
        unzip -q "$TOOLS_ARCHIVE" -d "$TOOLS_STAGE"
        [[ -x "$TOOLS_STAGE/cmdline-tools/bin/sdkmanager" ]] \
            || die "Downloaded Android command-line tools have an unexpected layout."
        mv "$TOOLS_STAGE/cmdline-tools" "$SDK/cmdline-tools/latest"
        rm -rf "$TOOLS_STAGE" "$TOOLS_ARCHIVE"
    fi

    export ANDROID_SDK_ROOT="$SDK"
    export ANDROID_HOME="$SDK"
    export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

    echo "Accepting Android SDK licenses..."
    yes | "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
    echo "Installing Android SDK packages..."
    "$sdkmanager" --sdk_root="$SDK" \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0"
}

main() {
    [[ -f "$ROOT_DIR/gradlew" ]] || die "Gradle wrapper not found at $ROOT_DIR/gradlew."
    [[ -f "$KEYSTORE" ]] || die "Signing keystore not found: $KEYSTORE"

    setup_java
    setup_android_sdk

    printf 'sdk.dir=%s\n' "$SDK" > "$PROJECT_DIR/local.properties"
    export KEYSTORE_FILE="$KEYSTORE"

    echo "Building signed FloatCalc release APK..."
    "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" assembleRelease --no-daemon --stacktrace

    [[ -f "$APK" ]] || die "Gradle completed but the release APK was not created."
    printf "\nRelease APK:\n"
    ls -lh "$APK"
}

main "$@"