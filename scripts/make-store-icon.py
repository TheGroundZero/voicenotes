#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Draws the 512 x 512 store icon (fastlane/metadata/android/en-US/images/icon.png) without any imaging library.

It is the launcher icon: the Material "mic" glyph (Apache-2.0, as in res/drawable/ic_mic.xml) in white on the
launcher background colour, at the same proportions as res/drawable/ic_launcher_foreground.xml (glyph scaled 2x
and centred in the 108-unit adaptive-icon canvas). Shapes are drawn geometrically and anti-aliased by supersampling.

  python3 scripts/make-store-icon.py [output.png]
"""
import math
import struct
import sys
import zlib

SIZE = 512
SS = 3  # samples per axis per pixel
BG = (0x2B, 0x5C, 0xB0)  # res/values/colors.xml launcher_background
FG = (255, 255, 255)
UNIT = SIZE / 108 * 2  # pixels per glyph unit (24-unit glyph, scale 2 on a 108 canvas)
ORIGIN = SIZE / 108 * 30  # glyph offset (translate 30 on a 108 canvas)


def inside(x, y):
    """x, y in glyph units (24 x 24)."""
    # capsule: x 9..15, y 2..14, radius 3
    if 9 <= x <= 15:
        if 5 <= y <= 11:
            return True
        if math.hypot(x - 12, y - 5) <= 3 and y < 5:
            return True
        if math.hypot(x - 12, y - 11) <= 3 and y > 11 and y <= 14:
            return True
    # holder: lower half ring around the capsule, centre (12, 11), outer 7 x 6.9, inner 5.3 x 5.1
    if y >= 11:
        outer = ((x - 12) / 7) ** 2 + ((y - 11) / 6.9) ** 2 <= 1
        inner = ((x - 12) / 5.3) ** 2 + ((y - 11) / 5.1) ** 2 < 1
        if outer and not inner:
            return True
    # stem
    return 11 <= x <= 13 and 17.4 <= y <= 21


def pixel(px, py):
    hits = 0
    for sy in range(SS):
        for sx in range(SS):
            x = (px + (sx + 0.5) / SS - ORIGIN) / UNIT
            y = (py + (sy + 0.5) / SS - ORIGIN) / UNIT
            hits += inside(x, y)
    a = hits / (SS * SS)
    return tuple(round(b + (f - b) * a) for b, f in zip(BG, FG))


def png(rows):
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in rows)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


out = sys.argv[1] if len(sys.argv) > 1 else "fastlane/metadata/android/en-US/images/icon.png"
with open(out, "wb") as f:
    f.write(png([[pixel(x, y) for x in range(SIZE)] for y in range(SIZE)]))
print("wrote", out)
