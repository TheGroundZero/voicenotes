#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Checks that every file we publish states its licence: an SPDX header in the first lines, or a REUSE.toml entry
(https://reuse.software). Uses `git ls-files` so ignored files and the whisper.cpp submodule are skipped."""
import re
import subprocess
import sys
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
files = subprocess.run(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=root, capture_output=True, text=True, check=True
).stdout.split("\n")
submodules = re.findall(r"path = (.+)", (root / ".gitmodules").read_text()) if (root / ".gitmodules").exists() else []

def pattern(glob: str) -> re.Pattern:
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**", i):
            out += ".*"; i += 2
            if glob.startswith("/", i): out = out[:-2] + "(?:.*/)?"; i += 1
        elif glob[i] == "*": out += "[^/]*"; i += 1
        else: out += re.escape(glob[i]); i += 1
    return re.compile(out + "$")

annotations = tomllib.loads((root / "REUSE.toml").read_text()).get("annotations", [])
covered = [pattern(g) for a in annotations for g in a["path"]]

missing = []
for name in filter(None, files):
    if any(name == s or name.startswith(s + "/") for s in submodules) or not (root / name).is_file():
        continue
    if (root / name).is_symlink() or name.startswith("LICENSES/"):  # the licence texts themselves
        continue
    try:
        head = (root / name).read_text(errors="ignore")[:600]
    except OSError:
        continue
    if "SPDX-License-Identifier:" in head or any(p.match(name) for p in covered):
        continue
    missing.append(name)

for name in missing:
    print("no licence information:", name)
for d in {"GPL-3.0-or-later", "MIT", "Apache-2.0"}:
    if not (root / "LICENSES" / f"{d}.txt").exists():
        print("missing licence text: LICENSES/%s.txt" % d); missing.append(d)
print("licence check:", "FAILED" if missing else "ok")
sys.exit(1 if missing else 0)
