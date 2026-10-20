#!/usr/bin/env python3
"""Render a simple, dependency-free launcher icon set (mipmap-*/ic_launcher.png).

The icon is a rounded square with a "play" triangle. It is intentionally generic so that no
third-party logo or trademark is redistributed. Usage:

    python3 tools/make_icon.py <res-dir> [--color RRGGBB]
"""
from __future__ import annotations

import argparse
import struct
import zlib
from pathlib import Path

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def _png(width: int, pixels: list[bytes]) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + row for row in pixels)
    header = struct.pack(">IIBBBBB", width, width, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def render(size: int, color: tuple[int, int, int]) -> bytes:
    samples = 4  # supersampling per axis for smooth edges
    radius = size * 0.22
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            bg = fg = 0
            for sy in range(samples):
                for sx in range(samples):
                    px = x + (sx + 0.5) / samples
                    py = y + (sy + 0.5) / samples
                    # rounded square
                    dx = max(radius - px, 0, px - (size - radius))
                    dy = max(radius - py, 0, py - (size - radius))
                    if dx * dx + dy * dy <= radius * radius:
                        bg += 1
                        # play triangle, centred
                        u, v = px / size, py / size
                        if 0.36 <= u <= 0.72 and abs(v - 0.5) <= (0.72 - u) * 0.62:
                            fg += 1
            total = samples * samples
            if bg == 0:
                row += bytes((0, 0, 0, 0))
                continue
            t = fg / bg
            r, g, b = (round(c * (1 - t) + 255 * t) for c in color)
            row += bytes((r, g, b, round(255 * bg / total)))
        rows.append(bytes(row))
    return _png(size, rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("res_dir", type=Path)
    parser.add_argument("--color", default="37474F", help="background colour, hex RRGGBB")
    args = parser.parse_args()
    color = tuple(int(args.color[i:i + 2], 16) for i in (0, 2, 4))
    for density, size in DENSITIES.items():
        out = args.res_dir / f"mipmap-{density}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(render(size, color))
        print(out)


if __name__ == "__main__":
    main()
