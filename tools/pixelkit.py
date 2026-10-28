"""Tiny pixel-art toolkit the HyperVisor X asset generators share.

It all works on PIL RGBA images at native res with no anti-aliasing, so stuff stays crisp in
Minecraft. Light always comes from the top-left: highlights on the top/left edges, shadows on the
bottom/right.
"""
import colorsys
import math
import random

from PIL import Image

RNG = random.Random(4242)


def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def shade(c, f):
    """f > 1 brightens toward white, f < 1 darkens toward black (keeps hue better than plain scaling)."""
    if f >= 1:
        return mix(c, (255, 255, 255, c[3]), min(1.0, f - 1))
    return (int(c[0] * f), int(c[1] * f), int(c[2] * f), c[3])


def alpha(c, a):
    return (c[0], c[1], c[2], a)


def hsv(h, s=1.0, v=1.0, a=255):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return (int(r * 255), int(g * 255), int(b * 255), a)


def new(w, h=None, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h if h is not None else w), color)


class Canvas:
    """Pixel canvas with clipping and a few drawing primitives."""

    def __init__(self, w, h=None, color=(0, 0, 0, 0)):
        self.img = new(w, h, color)
        self.w, self.h = self.img.size
        self.px = self.img.load()

    # ------------------------------------------------------------------ basics
    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            if c[3] == 0:  # fully transparent colour erases
                self.px[x, y] = (0, 0, 0, 0)
            elif c[3] == 255:
                self.px[x, y] = c
            elif c[3] > 0:
                base = self.px[x, y]
                t = c[3] / 255.0
                out_a = max(base[3], c[3])
                self.px[x, y] = (int(base[0] + (c[0] - base[0]) * t), int(base[1] + (c[1] - base[1]) * t),
                                 int(base[2] + (c[2] - base[2]) * t), out_a)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[x, y]
        return (0, 0, 0, 0)

    def rect(self, x0, y0, x1, y1, c):
        """Filled rectangle, inclusive corners."""
        for y in range(max(0, y0), min(self.h, y1 + 1)):
            for x in range(max(0, x0), min(self.w, x1 + 1)):
                self.set(x, y, c)

    def hline(self, x0, x1, y, c):
        self.rect(x0, y, x1, y, c)

    def vline(self, x, y0, y1, c):
        self.rect(x, y0, x, y1, c)

    def frame(self, x0, y0, x1, y1, c):
        self.hline(x0, x1, y0, c)
        self.hline(x0, x1, y1, c)
        self.vline(x0, y0, y1, c)
        self.vline(x1, y0, y1, c)

    def bevel(self, x0, y0, x1, y1, light, dark):
        self.hline(x0, x1 - 1, y0, light)
        self.vline(x0, y0, y1 - 1, light)
        self.hline(x0 + 1, x1, y1, dark)
        self.vline(x1, y0 + 1, y1, dark)

    def box(self, x0, y0, x1, y1, base, hi=1.25, lo=0.65, noise=0.0, rounded=False):
        """Shaded panel: base fill, light top/left edge, dark bottom/right edge."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if rounded and (x in (x0, x1)) and (y in (y0, y1)):
                    continue
                f = 1.0 + (RNG.uniform(-noise, noise) if noise else 0)
                self.set(x, y, shade(base, f))
        light, dark = shade(base, hi), shade(base, lo)
        self.hline(x0 + (1 if rounded else 0), x1 - (1 if rounded else 0), y0, light)
        self.vline(x0, y0 + (1 if rounded else 0), y1 - 1, light)
        self.hline(x0 + 1, x1 - (1 if rounded else 0), y1, dark)
        self.vline(x1, y0 + 1, y1 - (1 if rounded else 0), dark)

    def vgrad(self, x0, y0, x1, y1, top, bottom):
        for y in range(y0, y1 + 1):
            t = 0 if y1 == y0 else (y - y0) / (y1 - y0)
            self.hline(x0, x1, y, mix(top, bottom, t))

    def hgrad(self, x0, y0, x1, y1, left, right):
        for x in range(x0, x1 + 1):
            t = 0 if x1 == x0 else (x - x0) / (x1 - x0)
            self.vline(x, y0, y1, mix(left, right, t))

    def disc(self, cx, cy, r, c):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                    self.set(x, y, c)

    def ring(self, cx, cy, r0, r1, c):
        for y in range(int(cy - r1 - 1), int(cy + r1 + 2)):
            for x in range(int(cx - r1 - 1), int(cx + r1 + 2)):
                d = math.sqrt((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2)
                if r0 <= d <= r1:
                    self.set(x, y, c(x, y, d) if callable(c) else c)

    def line(self, x0, y0, x1, y1, c):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.set(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def dots(self, x0, y0, x1, y1, step, c, offset_rows=False):
        for j, y in enumerate(range(y0, y1 + 1, step)):
            off = (step // 2) if (offset_rows and j % 2) else 0
            for x in range(x0 + off, x1 + 1, step):
                self.set(x, y, c)

    def outline(self, c, only_empty=True):
        """Adds a 1px outline around every opaque pixel (selective outline look)."""
        src = [[self.px[x, y][3] > 0 for x in range(self.w)] for y in range(self.h)]
        for y in range(self.h):
            for x in range(self.w):
                if src[y][x]:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and src[ny][nx]:
                        self.px[x, y] = c
                        break

    def text(self, x, y, s, c, font=None):
        font = font or FONT_3x5
        cx = x
        for ch in s.upper():
            glyph = font.get(ch)
            if glyph is None:
                cx += 2
                continue
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "#":
                        self.set(cx + gx, y + gy, c)
            cx += len(glyph[0]) + 1
        return cx - x - 1

    def paste(self, other, x, y):
        self.img.alpha_composite(other.img if isinstance(other, Canvas) else other, (x, y))
        self.px = self.img.load()


def text_width(s, font=None):
    font = font or FONT_3x5
    w = 0
    for ch in s.upper():
        g = font.get(ch)
        w += (len(g[0]) if g else 1) + 1
    return max(0, w - 1)


def _glyphs(spec):
    out = {}
    for ch, rows in spec.items():
        out[ch] = rows.split("|")
    return out


FONT_3x5 = _glyphs({
    "0": "###|#.#|#.#|#.#|###", "1": ".#.|##.|.#.|.#.|###", "2": "##.|..#|.#.|#..|###", "3": "##.|..#|.#.|..#|##.",
    "4": "#.#|#.#|###|..#|..#", "5": "###|#..|##.|..#|##.", "6": ".##|#..|###|#.#|###", "7": "###|..#|.#.|.#.|.#.",
    "8": "###|#.#|###|#.#|###", "9": "###|#.#|###|..#|##.",
    "A": ".#.|#.#|###|#.#|#.#", "B": "##.|#.#|##.|#.#|##.", "C": ".##|#..|#..|#..|.##", "D": "##.|#.#|#.#|#.#|##.",
    "E": "###|#..|##.|#..|###", "F": "###|#..|##.|#..|#..", "G": ".##|#..|#.#|#.#|.##", "H": "#.#|#.#|###|#.#|#.#",
    "I": "###|.#.|.#.|.#.|###", "J": "..#|..#|..#|#.#|.#.", "K": "#.#|#.#|##.|#.#|#.#", "L": "#..|#..|#..|#..|###",
    "M": "#.#|###|###|#.#|#.#", "N": "##.|#.#|#.#|#.#|#.#", "O": ".#.|#.#|#.#|#.#|.#.", "P": "##.|#.#|##.|#..|#..",
    "Q": ".#.|#.#|#.#|##.|.##", "R": "##.|#.#|##.|#.#|#.#", "S": ".##|#..|.#.|..#|##.", "T": "###|.#.|.#.|.#.|.#.",
    "U": "#.#|#.#|#.#|#.#|###", "V": "#.#|#.#|#.#|#.#|.#.", "W": "#.#|#.#|###|###|#.#", "X": "#.#|#.#|.#.|#.#|#.#",
    "Y": "#.#|#.#|.#.|.#.|.#.", "Z": "###|..#|.#.|#..|###", "-": "...|...|###|...|...", ".": ".|.|.|.|#",
    "+": "...|.#.|###|.#.|...", "/": "..#|..#|.#.|#..|#..",
})

# 3x3 micro digits for very small labels
FONT_MICRO = _glyphs({
    "0": "###|#.#|###", "1": "##.|.#.|###", "2": "##.|.#.|.##", "3": "###|.##|###", "4": "#.#|###|..#",
    "5": ".##|.#.|##.", "6": "#..|###|###", "7": "###|..#|..#", "8": "###|###|###", "9": "###|###|..#",
    "G": "##.|#.#|###", "B": "##.|###|###", "T": "###|.#.|.#.", "X": "#.#|.#.|#.#",
})
