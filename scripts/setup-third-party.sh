#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Prepares third_party/whisper.cpp: makes sure the submodule is checked out at the pinned version and that our patches
# are applied. Safe to run again. The patches live in third_party/patches and are described in third_party/README.md.
set -euo pipefail
cd "$(dirname "$0")/.."

EXPECTED_COMMIT="d1be6fde11ac6e0407606b4e42fe72d34add8037"   # tag v1.9.5
# A fresh clone has an empty folder: fetch the submodule. An existing checkout (for example in the original
# development tree, before the first commit) is used as it is.
if [ ! -e third_party/whisper.cpp/.git ]; then git submodule update --init third_party/whisper.cpp; fi

cd third_party/whisper.cpp
head="$(git rev-parse HEAD)"
if [ "$head" != "$EXPECTED_COMMIT" ]; then
  echo "whisper.cpp is at $head, but this project is pinned to $EXPECTED_COMMIT (v1.9.5)." >&2
  echo "Check out the pinned commit (git -C third_party/whisper.cpp checkout $EXPECTED_COMMIT) or update the pin on purpose." >&2
  exit 1
fi

for patch in ../patches/*.patch; do
  if git apply --check --reverse "$patch" >/dev/null 2>&1; then
    echo "already applied: $(basename "$patch")"
  elif git apply --check "$patch" >/dev/null 2>&1; then
    git apply "$patch"; echo "applied: $(basename "$patch")"
  else
    echo "cannot apply $(basename "$patch"): the source has local changes that conflict with it" >&2; exit 1
  fi
done
