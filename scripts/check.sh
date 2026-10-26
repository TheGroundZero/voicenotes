#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Everything that can be checked without a phone: run before sending a change, and by CI.
#   - licence information on every file
#   - the Gradle wrapper is the genuine one
#   - shell scripts parse; the third-party patch applies; the bundled model has its pinned checksum
#   - the native core tests (g++ only), Android Lint, unit tests, and the privacy guards (network only in ModelDownloader, no tracker libraries)
# With --light only the checks that need no Android tools run (for a CI host without the SDK).
# Needs the build tools otherwise: source env.sh (see docs/BUILDING.md).
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
step() { printf '\n== %s\n' "$1"; }
run()  { "$@" || { echo "FAILED: $*"; fail=1; }; }

step "licence information"
run python3 scripts/check-licenses.py

step "Gradle wrapper checksum (published at services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256)"
echo "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046  gradle/wrapper/gradle-wrapper.jar" | sha256sum -c --quiet - && echo ok || { echo "FAILED: wrapper jar differs"; fail=1; }

step "scripts"
for f in scripts/*.sh env.sh; do bash -n "$f" && echo "ok $f" || { echo "FAILED: $f"; fail=1; }; done

# (scripts/verify-reproducible.sh is not part of this: it needs a second full build; run it before a release.)
step "whisper.cpp patches"
run scripts/setup-third-party.sh

step "bundled voice-activity model"
echo "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf  app/src/main/assets/ggml-silero-v5.1.2.bin" | sha256sum -c --quiet - && echo ok || { echo "FAILED: model checksum"; fail=1; }

step "no personal paths in published files"
if git ls-files --cached --others --exclude-standard | grep -v -E '^third_party/whisper.cpp$|\.(jar|bin)$' | xargs grep -n -I -E '/home/[a-z]+/' 2>/dev/null | grep -v '^scripts/.*\$HOME'; then
  echo "FAILED: absolute home paths above"; fail=1
else echo ok; fi

step "no audio recordings in published files"
if git ls-files --cached --others --exclude-standard | grep -i -E '\.(wav|m4a|mp3|ogg|opus|flac|aac|3gp|amr)$'; then
  echo "FAILED: audio files above (personal recordings must never be published)"; fail=1
else echo ok; fi

step "native core tests (audio-window sizing, JSON; need only a C++ compiler)"
if [ "${1:-}" = "--light" ]; then echo "skipped (--light)"
elif command -v g++ >/dev/null; then
  core_test="$(mktemp)"
  if g++ -std=c++17 -Wall -Wextra -I app/src/main/cpp benchmark/tests/core_test.cpp -o "$core_test" && "$core_test"; then echo ok
  else echo "FAILED: native core tests"; fail=1; fi
  rm -f "$core_test"
else echo "skipped (no g++)"; fi

step "lint, unit tests, privacy guards"
if [ "${1:-}" = "--light" ]; then echo "skipped (--light)"
elif [ -x ./gradlew ]; then run ./gradlew --no-daemon -q lintDebug testDebugUnitTest verifyNetworkIsolation verifyNoTrackers; fi

echo
if [ "$fail" = 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
