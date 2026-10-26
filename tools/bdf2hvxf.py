#!/usr/bin/env python3
"""Turns a fixed-cell BDF font (like Spleen 8x16, BSD-2-Clause) into the small HVXF glyph table
the embedded terminal renderer reads.

Format: b"HVXF", u8 cell width, u8 cell height, u32 glyph count, then for each glyph a u32 code
point and `height` row bytes (MSB = leftmost pixel). Every int is big-endian.
"""
import struct
import sys


def parse(path):
    glyphs = {}
    ascent = None
    cell_w = cell_h = None
    with open(path, encoding="latin-1") as f:
        lines = iter(f.read().splitlines())
    for line in lines:
        if line.startswith("FONTBOUNDINGBOX"):
            _, w, h, _, _ = line.split()
            cell_w, cell_h = int(w), int(h)
        elif line.startswith("FONT_ASCENT"):
            ascent = int(line.split()[1])
        elif line.startswith("STARTCHAR"):
            enc = None
            bbx = None
            rows = []
            for inner in lines:
                if inner.startswith("ENCODING"):
                    enc = int(inner.split()[1])
                elif inner.startswith("BBX"):
                    bbx = tuple(int(v) for v in inner.split()[1:])
                elif inner == "BITMAP":
                    for bm in lines:
                        if bm == "ENDCHAR":
                            break
                        rows.append(int(bm, 16))
                    break
            if enc is None or enc < 0 or bbx is None:
                continue
            w, h, xoff, yoff = bbx
            cell = [0] * cell_h
            nbytes = (w + 7) // 8
            for i, value in enumerate(rows):
                y = ascent - yoff - h + i
                if 0 <= y < cell_h:
                    bits = value >> (nbytes * 8 - 8) if nbytes > 1 else value
                    cell[y] = (bits >> xoff) & 0xFF
            glyphs[enc] = cell
    return cell_w, cell_h, glyphs


def main(src, dst):
    cell_w, cell_h, glyphs = parse(src)
    with open(dst, "wb") as out:
        out.write(b"HVXF")
        out.write(struct.pack(">BBI", cell_w, cell_h, len(glyphs)))
        for cp in sorted(glyphs):
            out.write(struct.pack(">I", cp))
            out.write(bytes(glyphs[cp]))
    print(f"wrote {len(glyphs)} glyphs ({cell_w}x{cell_h}) to {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
