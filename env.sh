# SPDX-License-Identifier: GPL-3.0-or-later
# Source me: puts the pinned build tools (see docs/BUILDING.md, scripts/setup-toolchain.sh) on the PATH.
# TOOLCHAIN_DIR can point elsewhere; the default is ~/toolchain. JDK 17 is required: the Android Gradle Plugin
# used here does not support newer JDKs.
export TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/toolchain}"
export JAVA_HOME="$TOOLCHAIN_DIR/jdk"
export ANDROID_HOME="$TOOLCHAIN_DIR/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Temurin's bundled certificate store lacked a CA that Gradle downloads needed on one Debian machine. If Debian's
# system store exists, use it (certificate checking stays on).
if [ -f /etc/ssl/certs/java/cacerts ]; then
  export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
