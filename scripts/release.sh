#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Prepares a release: bumps versionName and versionCode in app/build.gradle.kts, adds the dated section to CHANGELOG.md
# and a store changelog stub, commits and tags. By default it only shows what it would do.
#   scripts/release.sh 0.2.0            # preview
#   scripts/release.sh 0.2.0 --apply    # do it (needs a clean working tree and passing checks)
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:-}"; mode="${2:-preview}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: $0 X.Y.Z [--apply]" >&2; exit 1; }
gradle=app/build.gradle.kts
old_name="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$gradle")"
old_code="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$gradle")"
new_code=$((old_code + 1))
today="$(date +%F)"

echo "version:      $old_name -> $version"
echo "versionCode:  $old_code -> $new_code"
echo "files:        $gradle, CHANGELOG.md, fastlane/metadata/android/en-US/changelogs/$new_code.txt"
echo "git:          commit \"chore(release): $version\" and annotated tag v$version"
if [ "$mode" != "--apply" ]; then echo "(preview only; add --apply to do it)"; exit 0; fi

git rev-parse --verify HEAD >/dev/null 2>&1 || { echo "There are no commits yet." >&2; exit 1; }
[ -z "$(git status --porcelain --ignore-submodules=dirty)" ] || { echo "Working tree is not clean." >&2; exit 1; }
git rev-parse "v$version" >/dev/null 2>&1 && { echo "Tag v$version exists." >&2; exit 1; }
scripts/check.sh

sed -i "s/^\( *versionCode = \)$old_code/\1$new_code/; s/^\( *versionName = \)\"$old_name\"/\1\"$version\"/" "$gradle"
python3 - "$version" "$today" <<'PY'
import sys, re
version, today = sys.argv[1:]
text = open("CHANGELOG.md").read()
text = text.replace("## [Unreleased]\n", f"## [Unreleased]\n\n## [{version}] - {today}\n", 1)
open("CHANGELOG.md", "w").write(text)
PY
notes="fastlane/metadata/android/en-US/changelogs/$new_code.txt"
[ -e "$notes" ] || echo "See the changelog for $version." > "$notes"
echo "Edit CHANGELOG.md and $notes, then review the diff and commit:"
echo "  git add -A && git commit -s -m \"chore(release): $version\" && git tag -a v$version -m \"Voice Notes $version\""
