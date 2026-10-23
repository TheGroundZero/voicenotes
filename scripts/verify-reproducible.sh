#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the unsigned release APK twice from scratch, once here and once from a copy of the source in a different
# directory, and checks that the two files are byte-identical. Both builds are complete rebuilds (--rerun-tasks):
# an incremental build can differ from a clean one, and a clean build is what a release and F-Droid use. If they are, anyone building the same commit with the same tools gets
# the same app (which is what F-Droid's reproducible-build check relies on).
#   scripts/verify-reproducible.sh                    # uses ~/.cache/voicenotes-repro for the copy
#   REPRO_DIR=/big/disk/copy scripts/verify-reproducible.sh
# Needs the build tools (source env.sh). The copy takes about 4 GB with its build output and is removed afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
work="${REPRO_DIR:-$HOME/.cache/voicenotes-repro}"
apk=app/build/outputs/apk/release/app-release-unsigned.apk

echo "== building here (complete rebuild)"
./gradlew --no-daemon -q assembleRelease --rerun-tasks
mine="$(sha256sum "$apk" | cut -d' ' -f1)"

echo "== building a copy in $work/src"
rm -rf "$work"; mkdir -p "$work/src"
# Our files as git would publish them, plus the whisper.cpp checkout (a submodule in a real clone).
git ls-files --cached --others --exclude-standard -z | grep -zv '^third_party/whisper.cpp' | tar --null -T - -cf - | tar -xf - -C "$work/src"
cp -a third_party/whisper.cpp "$work/src/third_party/whisper.cpp"
( cd "$work/src" && ./gradlew --no-daemon -q assembleRelease )
copy="$(sha256sum "$work/src/$apk" | cut -d' ' -f1)"

echo "here: $mine"
echo "copy: $copy"
if [ "$mine" = "$copy" ]; then
  echo "REPRODUCIBLE: byte-identical"; rm -rf "$work"
else
  echo "NOT REPRODUCIBLE. Compare the two files entry by entry (unzip -l, cmp); the copy is kept in $work" >&2
  exit 1
fi
