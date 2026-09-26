#!/usr/bin/env bash
set -Eeuo pipefail

# Build the standalone FloatCalc Android app as a signed release APK.
#
# The project does not need a preconfigured machine. When Java or the Android
# SDK is missing, this script downloads them into the user's cache directory
# without requiring root access. Gradle dependencies are downloaded by the
# Gradle wrapper as part of the build.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$ROOT_DIR"
USER_CACHE="${XDG_CACHE_HOME:-${HOME:-$ROOT_DIR}/.cache}"
CACHE_ROOT="${FLOATCALC_CACHE_DIR:-$USER_CACHE/floatcalc}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$CACHE_ROOT/android-sdk}}"

JDK_VERSION="17"
CMDLINE_TOOLS_VERSION="${FLOATCALC_CMDLINE_TOOLS_VERSION:-13114758}"
COMPILE_SDK="${FLOATCALC_COMPILE_SDK:-34}"
BUILD_TOOLS_VERSION="${FLOATCALC_BUILD_TOOLS_VERSION:-34.0.0}"

JDK_DIR="$CACHE_ROOT/jdk-$JDK_VERSION"
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
PROJECT_KEYSTORE="$ROOT_DIR/my-release-key.jks"

trap 'printf "\nBuild failed at line %s. See the output above for the first error.\n" "$LINENO" >&2' ERR

die() {
    echo "Error: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' is required but is not installed."
}

host_platform() {
    case "$(uname -s)" in
        Linux*)  printf 'linux' ;;
        Darwin*) printf 'mac' ;;
        MINGW*|MSYS*|CYGWIN*) printf 'win' ;;
        *) die "Unsupported operating system: $(uname -s). Use Linux, macOS, or Git Bash on Windows." ;;
    esac
}

host_arch() {
    case "$(uname -m)" in
        x86_64|amd64) printf 'x64' ;;
        aarch64|arm64) printf 'aarch64' ;;
        *) die "Unsupported CPU architecture: $(uname -m). Use x86_64 or ARM64." ;;
    esac
}

HOST_PLATFORM="$(host_platform)"
HOST_ARCH="$(host_arch)"

download_file() {
    local url="$1"
    local destination="$2"
    mkdir -p "$(dirname "$destination")"
    echo "Downloading $(basename "$destination")..." >&2

    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 5 --retry-delay 2 --connect-timeout 30 \
            -o "$destination" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --tries=5 --timeout=30 -O "$destination" "$url"
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$url" "$destination" <<'PY'
import pathlib
import sys
import urllib.request

url, destination = sys.argv[1], sys.argv[2]
request = urllib.request.Request(url, headers={"User-Agent": "FloatCalc-build"})
with urllib.request.urlopen(request, timeout=120) as response:
    pathlib.Path(destination).write_bytes(response.read())
PY
    else
        die "Cannot download files. Install curl, wget, or Python 3, then run this script again."
    fi

    [[ -s "$destination" ]] || die "Downloaded file is empty: $destination"
}

java_major_version() {
    local java_bin="$1"
    local version
    version="$("$java_bin" -version 2>&1 |
        sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1 || true)"
    [[ -n "$version" ]] || return 1
    if [[ "$version" == 1.* ]]; then
        printf '%s\n' "${version#1.}" | cut -d. -f1
    else
        printf '%s\n' "$version" | cut -d. -f1
    fi
}

java_is_suitable() {
    local java_bin="$1"
    local major
    [[ -x "$java_bin" ]] || return 1
    major="$(java_major_version "$java_bin" || true)"
    [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 17 ))
}

java_home_from_bin() {
    local java_bin="$1"
    local java_path="$java_bin"
    if command -v readlink >/dev/null 2>&1 && readlink -f "$java_bin" >/dev/null 2>&1; then
        java_path="$(readlink -f "$java_bin")"
    fi
    cd -- "$(dirname "$java_path")/.." && pwd -P
}

find_existing_java() {
    local candidate
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] &&
        java_is_suitable "$JAVA_HOME/bin/java"; then
        java_home_from_bin "$JAVA_HOME/bin/java"
        return 0
    fi

    if command -v java >/dev/null 2>&1; then
        candidate="$(command -v java)"
        if java_is_suitable "$candidate"; then
            java_home_from_bin "$candidate"
            return 0
        fi
    fi

    for candidate in \
        /usr/lib/jvm/*/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
        "${HOME:-}/.sdkman/candidates/java"/*/bin/java
    do
        if java_is_suitable "$candidate"; then
            java_home_from_bin "$candidate"
            return 0
        fi
    done
    return 1
}

extract_tarball() {
    local archive="$1"
    local destination="$2"
    if command -v tar >/dev/null 2>&1; then
        tar -xzf "$archive" -C "$destination"
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$archive" "$destination" <<'PY'
import pathlib
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as archive:
    archive.extractall(pathlib.Path(sys.argv[2]))
PY
    else
        die "Cannot extract $archive. Install tar or Python 3, then run this script again."
    fi
}

extract_zip() {
    local archive="$1"
    local destination="$2"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$archive" -d "$destination"
    elif [[ -x "$JAVA_HOME/bin/jar" ]]; then
        (cd "$destination" && "$JAVA_HOME/bin/jar" xf "$archive")
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$archive" "$destination" <<'PY'
import pathlib
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    archive.extractall(pathlib.Path(sys.argv[2]))
PY
    else
        die "Cannot extract $archive. Install unzip or Python 3, then run this script again."
    fi
}

install_jdk() {
    local archive extension staging extracted
    local jdk_url="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${HOST_PLATFORM}/${HOST_ARCH}/jdk/hotspot/normal/eclipse"

    case "$HOST_PLATFORM" in
        win) extension="zip" ;;
        *) extension="tar.gz" ;;
    esac

    archive="$CACHE_ROOT/jdk-${JDK_VERSION}-${HOST_PLATFORM}-${HOST_ARCH}.${extension}"
    staging="$(mktemp -d "${CACHE_ROOT}/jdk-stage.XXXXXX")"
    mkdir -p "$CACHE_ROOT"

    if [[ ! -f "$archive" ]]; then
        download_file "$jdk_url" "$archive"
    fi

    echo "Installing Java ${JDK_VERSION} into $JDK_DIR..." >&2
    rm -rf "$staging"
    mkdir -p "$staging"
    if [[ "$extension" == "zip" ]]; then
        extract_zip "$archive" "$staging"
    else
        extract_tarball "$archive" "$staging"
    fi

    extracted="$(find "$staging" -type f -path '*/bin/java' -print -quit | sed 's#/bin/java$##')"
    [[ -n "$extracted" && -x "$extracted/bin/java" ]] \
        || die "Downloaded Java archive has an unexpected layout."

    rm -rf "$JDK_DIR"
    mv "$extracted" "$JDK_DIR"
    rm -rf "$staging"
}

setup_java() {
    local java_home
    if java_home="$(find_existing_java)"; then
        export JAVA_HOME="$java_home"
    else
        echo "Java 17 or newer was not found; downloading a private Java 17 runtime..."
        mkdir -p "$CACHE_ROOT"
        if [[ ! -x "$JDK_DIR/bin/java" ]] || ! java_is_suitable "$JDK_DIR/bin/java"; then
            install_jdk
        fi
        export JAVA_HOME="$JDK_DIR"
    fi

    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using Java $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
}

find_sdkmanager() {
    local candidate
    for candidate in \
        "$SDK/cmdline-tools/latest/bin/sdkmanager" \
        "$SDK/tools/bin/sdkmanager"
    do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    if [[ -d "$SDK/cmdline-tools" ]]; then
        candidate="$(find "$SDK/cmdline-tools" -type f -name sdkmanager -perm -u+x -print |
            sort | tail -n 1 || true)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    return 1
}

install_command_line_tools() {
    local sdkmanager archive staging extracted
    local tools_url="https://dl.google.com/android/repository/commandlinetools-${HOST_PLATFORM}-${CMDLINE_TOOLS_VERSION}_latest.zip"

    if sdkmanager="$(find_sdkmanager)"; then
        printf '%s\n' "$sdkmanager"
        return 0
    fi

    archive="$CACHE_ROOT/commandlinetools-${HOST_PLATFORM}-${CMDLINE_TOOLS_VERSION}.zip"
    staging="$(mktemp -d "${CACHE_ROOT}/android-tools-stage.XXXXXX")"
    mkdir -p "$SDK/cmdline-tools" "$CACHE_ROOT"

    if [[ ! -f "$archive" ]]; then
        download_file "$tools_url" "$archive"
    fi

    echo "Installing Android command-line tools into $SDK..." >&2
    rm -rf "$staging"
    mkdir -p "$staging"
    extract_zip "$archive" "$staging"
    [[ -x "$staging/cmdline-tools/bin/sdkmanager" ]] \
        || die "Downloaded Android command-line tools have an unexpected layout."

    rm -rf "$SDK/cmdline-tools/latest"
    mv "$staging/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$staging"
    find_sdkmanager
}

setup_android_sdk() {
    local sdkmanager sdk_dir_value
    sdkmanager="$(install_command_line_tools)" \
        || die "Android SDK command-line tools could not be installed."

    export ANDROID_SDK_ROOT="$SDK"
    export ANDROID_HOME="$SDK"
    export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

    echo "Accepting Android SDK licenses..."
    set +o pipefail
    yes | "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
    set -o pipefail

    echo "Installing Android SDK packages..."
    "$sdkmanager" --sdk_root="$SDK" \
        "platform-tools" \
        "platforms;android-${COMPILE_SDK}" \
        "build-tools;${BUILD_TOOLS_VERSION}"

    [[ -f "$SDK/platforms/android-${COMPILE_SDK}/android.jar" ]] \
        || die "Android platform android-${COMPILE_SDK} was not installed."
    [[ -x "$SDK/build-tools/${BUILD_TOOLS_VERSION}/aapt2" ]] \
        || die "Android build-tools ${BUILD_TOOLS_VERSION} were not installed."

    sdk_dir_value="$SDK"
    if [[ "$HOST_PLATFORM" == "win" ]]; then
        sdk_dir_value="${sdk_dir_value//\\/\\\\}"
    fi
    printf 'sdk.dir=%s\n' "$sdk_dir_value" > "$PROJECT_DIR/local.properties"
}

setup_signing_key() {
    local key_path="${KEYSTORE_FILE:-}"
    local key_password="${KEYSTORE_PASSWORD:-Sh@090609}"
    local key_alias="${KEY_ALIAS:-my-key}"
    local key_key_password="${KEY_PASSWORD:-Sh@090609}"

    if [[ -n "$key_path" ]]; then
        [[ -f "$key_path" ]] || die "KEYSTORE_FILE points to a missing file: $key_path"
    elif [[ -f "$PROJECT_KEYSTORE" ]]; then
        key_path="$PROJECT_KEYSTORE"
    else
        key_path="$CACHE_ROOT/floatcalc-release-key.jks"
        if [[ ! -f "$key_path" ]]; then
            echo "Project signing key not found; generating a local fallback signing key..."
            mkdir -p "$CACHE_ROOT"
            "$JAVA_HOME/bin/keytool" -genkeypair -v \
                -keystore "$key_path" \
                -storepass "$key_password" \
                -keypass "$key_key_password" \
                -alias "$key_alias" \
                -keyalg RSA \
                -keysize 2048 \
                -validity 10000 \
                -dname "CN=FloatCalc, OU=Build, O=FloatCalc, L=Local, ST=Local, C=IN" \
                >/dev/null
        fi
    fi

    export KEYSTORE_FILE="$key_path"
    export KEYSTORE_PASSWORD="$key_password"
    export KEY_ALIAS="$key_alias"
    export KEY_PASSWORD="$key_key_password"
}

main() {
    [[ -f "$ROOT_DIR/gradlew" ]] || die "Gradle wrapper not found at $ROOT_DIR/gradlew."
    require_command sed
    require_command find
    require_command mktemp
    require_command yes
    chmod +x "$ROOT_DIR/gradlew"

    mkdir -p "$CACHE_ROOT"
    setup_java
    setup_android_sdk
    setup_signing_key

    echo "Building signed FloatCalc release APK..."
    "$ROOT_DIR/gradlew" -p "$PROJECT_DIR" assembleRelease --no-daemon --stacktrace

    [[ -f "$APK" ]] || die "Gradle completed but the release APK was not created."
    printf "\nRelease APK:\n"
    ls -lh "$APK"
}

main "$@"