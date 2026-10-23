#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the git hooks from scripts/git-hooks into this clone (hooks are not part of a clone by themselves).
set -euo pipefail
cd "$(dirname "$0")/.."
for hook in scripts/git-hooks/*; do
  install -m 755 "$hook" ".git/hooks/$(basename "$hook")"
  echo "installed $(basename "$hook")"
done
