#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the build tools this project is developed with into $TOOLCHAIN_DIR (default ~/toolchain), without sudo:
# JDK 17 (Temurin), the Android command-line tools, and the pinned SDK packages (platform, build-tools, NDK, CMake).
# Every download is verified against a SHA-256 value written down here. Linux x86_64 only; on other systems install
# the same versions with your package manager or Android Studio (see docs/BUILDING.md).
#
#   scripts/setup-toolchain.sh               # install what is missing
#   ACCEPT_ANDROID_SDK_LICENSES=yes ...      # accept the Android SDK licences non-interactively (read them first)
#   WITH_DHU=yes scripts/setup-toolchain.sh  # also install the Android Auto Desktop Head Unit (bench testing)
#
# Needs: bash, curl, tar, unzip, sha256sum.
set -euo pipefail

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/toolchain}"
DL="$TOOLCHAIN_DIR/dl"

# --- Pinned versions. Change them together with docs/BUILDING.md and the checks in scripts/check.sh. ---
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
JDK_SHA256="3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e"   # published next to the download by Adoptium
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
SDK_PACKAGES=("platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1")
# Gradle itself comes from the Gradle wrapper (gradle/wrapper/gradle-wrapper.properties pins version and checksum).

fetch() { # url sha256 file
  mkdir -p "$DL"
  if [ ! -f "$3" ]; then echo "Downloading $(basename "$3")"; curl -fL --retry 3 -o "$3.part" "$1"; mv "$3.part" "$3"; fi
  echo "$2  $3" | sha256sum -c --quiet - || { echo "Checksum mismatch for $3: refusing to use it" >&2; rm -f "$3"; exit 1; }
}

for tool in curl tar unzip sha256sum; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
[ "$(uname -sm)" = "Linux x86_64" ] || { echo "This script supports Linux x86_64 only. See docs/BUILDING.md." >&2; exit 1; }

if [ ! -x "$TOOLCHAIN_DIR/jdk/bin/java" ]; then
  fetch "$JDK_URL" "$JDK_SHA256" "$DL/jdk17.tar.gz"
  mkdir -p "$TOOLCHAIN_DIR/jdk"; tar -xzf "$DL/jdk17.tar.gz" -C "$TOOLCHAIN_DIR/jdk" --strip-components=1
fi

SDK="$TOOLCHAIN_DIR/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch "$CMDLINE_URL" "$CMDLINE_SHA256" "$DL/cmdline-tools.zip"
  mkdir -p "$SDK/cmdline-tools"; unzip -q -o "$DL/cmdline-tools.zip" -d "$SDK/cmdline-tools/tmp"
  rm -rf "$SDK/cmdline-tools/latest"; mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"; rmdir "$SDK/cmdline-tools/tmp"
fi

export JAVA_HOME="$TOOLCHAIN_DIR/jdk"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ "${ACCEPT_ANDROID_SDK_LICENSES:-}" = "yes" ]; then
  yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
elif [ ! -d "$SDK/licenses" ]; then
  echo "The Android SDK licences have not been accepted yet. Read them with:  $SDKMANAGER --sdk_root=$SDK --licenses" >&2
  echo "or re-run this script with ACCEPT_ANDROID_SDK_LICENSES=yes if you accept them." >&2
  exit 1
fi
PACKAGES=("${SDK_PACKAGES[@]}"); [ "${WITH_DHU:-}" = "yes" ] && PACKAGES+=("extras;google;auto")
"$SDKMANAGER" --sdk_root="$SDK" "${PACKAGES[@]}"

echo
echo "Done. In each new shell run:  source env.sh"
echo "Then build with:              ./gradlew assembleDebug"
