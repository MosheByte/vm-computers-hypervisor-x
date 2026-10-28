#!/usr/bin/env python3
"""
Motherboard assembly art for the PC screen (the "Motherboard" page), in Minecraft-style pixel art:
one texel per board unit (BU) and per GUI pixel, hard edges, light from the top-left, noisy
surfaces. Every part gets visible thickness too (lit top/left edges, a darker side band, a cast
shadow) so it looks like a chunky block and not a flat sticker. The big labels (CPU model, chipset
logo) get drawn in-game with the Minecraft font, the silkscreen uses a 3x5 pixel font.

Coordinates match the socket layout in BoardView.java. Writes textures/gui/board/*.png.
"""
import math
import os
import random

from PIL import Image, ImageDraw

from pixelkit import Canvas, hexc, shade, RNG, text_width

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "hypervisorx", "textures", "gui", "board")
BW, BH = 180, 204


def save(c, name):
    os.makedirs(OUT, exist_ok=True)
    c.img.save(os.path.join(OUT, name + ".png"), optimize=True)
    print("wrote", name, c.img.size)


def fill(c, x, y, w, h, col):
    """Rect by position + size (pixelkit's rect is inclusive)."""
    if w > 0 and h > 0:
        c.rect(int(x), int(y), int(x + w - 1), int(y + h - 1), col)


def block(c, x, y, w, h, base, hi=1.3, lo=0.6, noise=0.0, side=0, side_f=0.55):
    """Raised block: lit top/left edge, dark bottom/right edge, plus an optional side band below
    (its thickness, seen from slightly in front)."""
    c.box(x, y, x + w - 1, y + h - 1 - side, base, hi, lo, noise)
    if side:
        fill(c, x, y + h - side, w, side, shade(base, side_f))
        fill(c, x, y + h - side, 1, side, shade(base, side_f * 1.2))


def poly(c, pts, col):
    """Crisp polygon (no anti-aliasing), composited onto the canvas."""
    layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).polygon(pts, fill=col)
    c.img.alpha_composite(layer)
    c.px = c.img.load()


def grain(c, x0, y0, x1, y1, amount, seed=None):
    """Minecraft-style per-pixel noise over whatever's already there."""
    rnd = RNG if seed is None else random.Random(seed)
    for y in range(max(0, y0), min(c.h, y1 + 1)):
        for x in range(max(0, x0), min(c.w, x1 + 1)):
            p = c.px[x, y]
            if p[3] == 0:
                continue
            c.px[x, y] = shade(p, 1 + rnd.uniform(-amount, amount))


def shadow(c, x, y, w, h, a=90, off=1):
    fill(c, x + off, y + off, w, h, (0, 0, 0, a))


def label(c, x, y, s, col=hexc("8f97a3")):
    c.text(x, y, s, col)


# ---------------------------------------------------------------------------------------------- components

def cap(c, x, y, top=hexc("2b2e35")):
    """Solid capacitor seen from above, 4x4."""
    fill(c, x + 1, y + 1, 4, 4, (0, 0, 0, 90))
    fill(c, x, y + 1, 4, 2, hexc("8e949c"))
    fill(c, x + 1, y, 2, 4, hexc("8e949c"))
    fill(c, x + 1, y + 1, 2, 2, top)
    c.set(x + 1, y + 1, shade(top, 1.6))
    c.set(x + 3, y + 2, hexc("5d636b"))
    c.set(x + 2, y + 3, hexc("5d636b"))


def choke(c, x, y):
    shadow(c, x, y, 5, 5)
    block(c, x, y, 5, 5, hexc("4a4e55"), 1.35, 0.55)
    fill(c, x + 1, y + 1, 3, 3, hexc("3a3d43"))
    c.set(x + 2, y + 2, hexc("6a6f77"))


def ic(c, x, y, w, h, body=hexc("1c1e22"), pin=hexc("a9adb4")):
    for i in range(x + 1, x + w - 1, 2):
        c.set(i, y - 1, pin)
        c.set(i, y + h, pin)
    shadow(c, x, y, w, h, 80)
    block(c, x, y, w, h, body, 1.6, 0.7)
    c.set(x + 1, y + 1, shade(body, 2.2))


def smd_field(c, keep, count, palette, area=(3, 3, BW - 4, BH - 4)):
    """Tiny resistors and caps (2x1 / 1x2) on free board space."""
    placed = 0
    tries = 0
    while placed < count and tries < count * 30:
        tries += 1
        horiz = RNG.random() < 0.6
        w, h = (2, 1) if horiz else (1, 2)
        x, y = RNG.randint(area[0], area[2] - w), RNG.randint(area[1], area[3] - h)
        if any(k[0] - 1 <= x <= k[2] + 1 and k[1] - 1 <= y <= k[3] + 1 for k in keep):
            continue
        col = RNG.choice(palette)
        fill(c, x, y, w, h, col)
        c.set(x + w - 1, y + h - 1, shade(col, 1.35))
        placed += 1


def traces(c, keep, count, col, area=(2, 2, BW - 3, BH - 3)):
    """Copper traces: straight runs with 45-degree bends, 1px wide, in small parallel bundles."""
    def free(x, y):
        return area[0] <= x <= area[2] and area[1] <= y <= area[3] and not any(
            k[0] - 1 <= x <= k[2] + 1 and k[1] - 1 <= y <= k[3] + 1 for k in keep)
    dirs = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
    for _ in range(count):
        x, y = RNG.randint(area[0], area[2]), RNG.randint(area[1], area[3])
        if not free(x, y):
            continue
        di = RNG.choice([0, 2, 4, 6])
        path = [(x, y)]
        for _ in range(RNG.randint(10, 46)):
            if RNG.random() < 0.1:
                di = (di + RNG.choice([1, -1])) % 8
                if RNG.random() < 0.6:
                    di = (di + (1 if di % 2 else 0)) % 8
            d = dirs[di]
            nx, ny = path[-1][0] + d[0], path[-1][1] + d[1]
            if not free(nx, ny):
                break
            path.append((nx, ny))
        if len(path) < 6:
            continue
        n = RNG.randint(1, 3)
        d = dirs[di]
        for i in range(n):
            ox, oy = (0, i * 2) if d[1] == 0 else (i * 2, 0)
            for px, py in path:
                if free(px + ox, py + oy):
                    c.set(px + ox, py + oy, col)
        c.set(path[-1][0], path[-1][1], shade(col, 1.5))


def mount(c, cx, cy):
    c.disc(cx, cy, 3, hexc("b49a5a"))
    c.disc(cx, cy, 2, hexc("d6d8dc"))
    c.disc(cx, cy, 1.2, hexc("0b0c0e"))
    c.set(int(cx) - 2, int(cy) - 2, hexc("e8d9a8"))


def header(c, x, y, cols, rows, body=hexc("1a1b1f"), pin=hexc("d9b04d")):
    w, h = cols * 2 + 1, rows * 2 + 1
    shadow(c, x, y, w, h, 80)
    block(c, x, y, w, h, body, 1.8, 0.6)
    for i in range(cols):
        for j in range(rows):
            c.set(x + 1 + i * 2, y + 1 + j * 2, pin)


def dimm(c, x, y0, y1, body, latch):
    """Vertical DIMM slot, 5 wide; latches drawn open (the game draws closed ones over them)."""
    shadow(c, x, y0, 5, y1 - y0 + 1)
    block(c, x, y0, 5, y1 - y0 + 1, body, 1.5, 0.55)
    for y in range(y0 + 2, y1 - 1):
        c.set(x + 2, y, hexc("060708") if y % 2 else hexc("b48a3a"))
    c.set(x + 2, y0 + (y1 - y0) * 43 // 100, body)
    for (yy, up) in ((y0 - 3, True), (y1 + 1, False)):
        fill(c, x, yy, 5, 3, latch)
        fill(c, x, yy + (2 if up else 0), 5, 1, shade(latch, 0.7))
        c.set(x + 4, yy + (0 if up else 2), shade(latch, 1.3))


def slot(c, x0, x1, y, body, armor=None, latch=True, h=5):
    if armor:
        block(c, x0 - 1, y - 1, x1 - x0 + 3, h + 2, armor, 1.4, 0.6)
    shadow(c, x0, y, x1 - x0 + 1, h)
    block(c, x0, y, x1 - x0 + 1, h, body, 1.5, 0.55)
    mid = y + h // 2
    key = x0 + min(12, (x1 - x0) * 35 // 100)
    for x in range(x0 + 1, x1):
        if x in (key, key + 1):
            continue
        c.set(x, mid, hexc("b48a3a") if x % 2 else hexc("060708"))
    if latch:
        block(c, x1 + 2, y - 1, 3, h + 2, hexc("d6d9dd"), 1.2, 0.7)


def m2_socket(c, x0, y0, x1, y1, name):
    for x in range(x0 + 4, x1 + 1, 2):
        c.set(x, y0, hexc("3d424a"))
        c.set(x, y1, hexc("3d424a"))
    block(c, x0, y0 + 1, 4, y1 - y0 - 1, hexc("1b1c20"), 1.7, 0.6)
    for y in range(y0 + 2, y1 - 1):
        c.set(x0 + 2, y, hexc("b48a3a") if y % 2 else hexc("060708"))
    c.disc(x1 - 1.5, (y0 + y1) / 2 + 0.5, 1.6, hexc("c9a959"))
    c.set(x1 - 2, (y0 + y1) // 2, hexc("2a2b2f"))
    label(c, x0 + 6, y1 + 2, name)


def lga(c, x0, y0, s):
    shadow(c, x0, y0, s, s, 110, 2)
    block(c, x0, y0, s, s, hexc("3a3e45"), 1.4, 0.55)
    fill(c, x0 + 3, y0 + 3, s - 6, s - 6, hexc("3a2c1a"))
    for y in range(y0 + 3, y0 + s - 3):
        for x in range(x0 + 3, x0 + s - 3):
            inner = x0 + s * 0.42 <= x < x0 + s * 0.58 and y0 + s * 0.42 <= y < y0 + s * 0.58
            if inner:
                c.set(x, y, hexc("22252b"))
            elif (x + y) % 2 == 0:
                c.set(x, y, hexc("d4a64c") if (x * 7 + y * 3) % 11 else hexc("f0cd7a"))
    c.bevel(x0 + 3, y0 + 3, x0 + s - 4, y0 + s - 4, hexc("1c160c"), hexc("6b5232"))
    for i in range(4):
        fill(c, x0 + 3, y0 + s - 4 - i, 4 - i, 1, hexc("e8c25e"))


def zif(c, x0, y0, s):
    shadow(c, x0, y0, s, s, 110, 2)
    block(c, x0, y0, s, s, hexc("e7dfc4"), 1.08, 0.72, 0.02)
    fill(c, x0 + s - 7, y0 + 1, 6, s - 2, hexc("d8cdaa"))
    for y in range(y0 + 3, y0 + s - 3, 2):
        for x in range(x0 + 3, x0 + s - 8, 2):
            if not (x0 + s * 0.36 <= x < x0 + s * 0.56 and y0 + s * 0.38 <= y < y0 + s * 0.62):
                c.set(x, y, hexc("4a4436"))
    label(c, x0 + 14, y0 + 20, "ZIF", hexc("8c836a"))


def fins(c, x, y, w, h, base, vertical=True, step=2):
    block(c, x, y, w, h, base, 1.35, 0.5)
    grain(c, x, y, x + w - 1, y + h - 1, 0.05)
    if vertical:
        for i in range(x + 2, x + w - 2, step):
            c.vline(i, y + 2, y + h - 3, shade(base, 0.5))
            c.vline(i + 1, y + 2, y + h - 3, shade(base, 1.25))
    else:
        for j in range(y + 2, y + h - 2, step):
            c.hline(x + 2, x + w - 3, j, shade(base, 0.5))
            c.hline(x + 2, x + w - 3, j + 1, shade(base, 1.25))


# ---------------------------------------------------------------------------------------------- tower board

def tower():
    c = Canvas(BW, BH)
    fill(c, 0, 0, BW, BH, hexc("23262c"))
    grain(c, 0, 0, BW - 1, BH - 1, 0.06)
    c.hline(0, BW - 1, BH - 1, hexc("121317"))
    c.vline(BW - 1, 0, BH - 1, hexc("121317"))
    c.hline(0, BW - 2, 0, hexc("33373e"))
    c.vline(0, 0, BH - 2, hexc("33373e"))
    keep = [(0, 0, 42, 80), (42, 0, 118, 18), (120, 0, 140, 13), (54, 22, 106, 76), (112, 12, 145, 107), (148, 28, 172, 104),
            (28, 78, 98, 98), (8, 100, 128, 114), (8, 120, 130, 136), (64, 152, 130, 174), (8, 148, 44, 162), (8, 174, 44, 188),
            (128, 114, 180, 184), (10, 184, 180, 203), (74, 176, 94, 196)]
    traces(c, keep, 260, hexc("2d3138"))
    smd_field(c, list(keep), 230, [hexc("15161a"), hexc("3a3128"), hexc("2a2620")])
    for (x, y, w, h) in [(46, 80, 6, 5), (106, 80, 5, 5), (20, 86, 6, 6), (100, 138, 8, 6), (52, 168, 6, 6), (60, 140, 5, 5),
                         (112, 182, 5, 5)]:
        ic(c, x, y, w, h)
    for (x, y) in [(5, 5), (174, 5), (5, 110), (110, 116), (174, 110), (5, 198), (110, 198), (174, 198)]:
        mount(c, x, y)

    # VRM chokes and caps around the socket
    for i in range(7):
        choke(c, 44 + i * 7, 19)
    for i in range(1, 6):
        choke(c, 46, 26 + i * 7)
    for i in range(5):
        cap(c, 102, 28 + i * 7)

    # rear I/O column (the USB ports here are the USB sockets)
    block(c, 0, 2, 8, 78, hexc("9ba0a8"), 1.25, 0.6)
    for (y, kind) in [(4, "audio"), (14, "usb"), (24, "usb"), (34, "eth"), (44, "hdmi"), (54, "dp"), (64, "usb")]:
        if kind == "usb":
            block(c, 0, y, 8, 8, hexc("c3c7cd"), 1.2, 0.6)
            fill(c, 1, y + 1, 6, 2, hexc("2f6fe0"))
            fill(c, 1, y + 5, 6, 2, hexc("2f6fe0"))
            c.hline(1, 6, y + 1, hexc("6f9cf0"))
            c.hline(1, 6, y + 5, hexc("6f9cf0"))
        elif kind == "audio":
            for k, col in enumerate(["3fb950", "e05aa8", "4aa3ff"]):
                c.disc(4, y + 1.5 + k * 3, 1.3, hexc(col))
                c.set(3, y + 1 + k * 3, hexc("101114"))
        elif kind == "eth":
            block(c, 0, y, 8, 8, hexc("c8cbd0"), 1.2, 0.6)
            fill(c, 1, y + 2, 6, 5, hexc("1d1f23"))
            c.set(1, y + 2, hexc("e3a72f"))
            c.set(6, y + 2, hexc("3ccf62"))
        else:
            block(c, 0, y, 8, 7, hexc("b4b8be"), 1.2, 0.6)
            fill(c, 1, y + 2, 6, 3, hexc("17181b"))
    # I/O shroud: angled armour, an RGB window and its side face
    shadow(c, 8, 0, 34, 80, 120, 2)
    poly(c, [(8, 0), (39, 0), (41, 3), (41, 72), (35, 79), (8, 79)], hexc("2c3037"))
    grain(c, 8, 0, 41, 79, 0.05)
    poly(c, [(39, 0), (41, 3), (41, 72), (39, 72)], hexc("1c1f24"))
    c.hline(8, 38, 0, hexc("5a606a"))
    c.vline(8, 0, 78, hexc("464b54"))
    poly(c, [(12, 6), (35, 6), (35, 64), (29, 70), (12, 70)], hexc("1a1c21"))
    poly(c, [(14, 8), (33, 8), (33, 63), (28, 68), (14, 68)], hexc("0f1013"))
    for k in range(4):
        c.hline(36, 40, 10 + k * 3, hexc("434852"))

    # VRM heatsink and the EPS header
    shadow(c, 42, 1, 76, 17, 120, 2)
    fins(c, 42, 1, 76, 15, hexc("343840"), True, 2)
    fill(c, 42, 16, 76, 2, hexc("1d2025"))
    poly(c, [(42, 1), (53, 1), (47, 15), (42, 15)], hexc("40454e"))
    shadow(c, 122, 2, 18, 9)
    block(c, 122, 2, 18, 9, hexc("17181b"), 1.8, 0.6)
    for i in range(4):
        fill(c, 123 + i * 4, 3, 3, 3, hexc("060708"))
        fill(c, 123 + i * 4, 7, 3, 3, hexc("060708"))
    label(c, 142, 4, "EPS")

    # CPU socket and the CPU fan header
    lga(c, 56, 26, 44)
    label(c, 56, 72, "LGA1851")
    header(c, 46, 17, 4, 1, hexc("e6e6e2"), hexc("b48a3a"))

    # memory
    for i in range(4):
        dimm(c, 116 + i * 7, 18, 97, hexc("30343a") if i % 2 == 0 else hexc("1e2025"), hexc("aeb2b9"))
    for i, n in enumerate(["A1", "A2", "B1", "B2"]):
        label(c, 116 + i * 7, 102 + (i % 2) * 6, n)

    # 24-pin power and the Q-LEDs
    shadow(c, 158, 30, 12, 46, 110, 2)
    block(c, 158, 30, 12, 46, hexc("18191c"), 1.8, 0.6)
    for j in range(11):
        for i in range(2):
            fill(c, 159 + i * 5, 32 + j * 4, 4, 3, hexc("060708"))
            c.set(159 + i * 5, 32 + j * 4, hexc("2a2c31"))
    label(c, 158, 78, "ATX")
    for k, n in enumerate(["CPU", "DRAM", "VGA", "BOOT"]):
        fill(c, 150, 82 + k * 5, 3, 2, hexc("3a2c2c"))
        label(c, 155, 81 + k * 5, n)

    # storage, graphics and expansion slots
    m2_socket(c, 30, 80, 95, 91, "M2-1")
    m2_socket(c, 66, 156, 127, 167, "M2-2")
    slot(c, 14, 119, 104, hexc("1d1f24"), armor=hexc("b0b5bc"))
    label(c, 14, 112, "PCIEX16-1")
    for i, y in enumerate([152, 178]):
        slot(c, 14, 39, y, hexc("1d1f24"), latch=False)
        label(c, 14, y + 7, "PCIEX1-" + str(i + 1))

    # chipset heatsink: angled plate, RGB stripe, side face
    shadow(c, 130, 116, 40, 62, 120, 2)
    poly(c, [(140, 116), (169, 116), (169, 177), (130, 177), (130, 127)], hexc("2c3037"))
    grain(c, 130, 116, 169, 177, 0.05)
    poly(c, [(130, 174), (169, 174), (169, 177), (130, 177)], hexc("1a1c21"))
    c.hline(140, 168, 116, hexc("5a606a"))
    c.line(130, 127, 140, 116, hexc("5a606a"))
    poly(c, [(135, 132), (165, 122), (165, 169), (135, 169)], hexc("1a1c21"))
    poly(c, [(137, 134), (163, 125), (163, 167), (137, 167)], hexc("101114"))
    label(c, 142, 160, "Z990")
    for k in range(6):
        y = 120 + k * 9
        shadow(c, 171, y, 9, 8, 90)
        block(c, 171, y, 9, 8, hexc("17181b"), 1.8, 0.6)
        fill(c, 172, y + 2, 6, 1, hexc("060708"))
        fill(c, 172, y + 5, 6, 1, hexc("060708"))
    label(c, 162, 179, "SATA")

    # bottom edge: audio, battery, headers
    shadow(c, 14, 186, 16, 12, 90)
    block(c, 14, 186, 16, 12, hexc("d3d7dd"), 1.1, 0.7)
    label(c, 16, 190, "ALC", hexc("5a606a"))
    for k in range(6):
        cap(c, 33 + (k % 3) * 5, 187 + (k // 3) * 5)
    c.disc(84, 186, 8, hexc("7d838b"))
    c.disc(84, 186, 7, hexc("c9ced5"))
    c.disc(85.5, 187.5, 4, hexc("b4b9c0"))
    c.disc(82, 184, 3, hexc("e3e7ec"))
    label(c, 80, 184, "3V", hexc("6b7079"))
    for k, (hx, n) in enumerate([(98, "USB"), (116, "PANEL"), (138, "FAN"), (156, "PUMP")]):
        header(c, hx, 192, 5 if k < 2 else 4, 2 if k < 2 else 1)
        label(c, hx, 198, n)

    # silkscreen
    label(c, 14, 124, "HVX Z990-E GAMING", hexc("aab1bb"))
    label(c, 14, 130, "WIFI 7 ORBITTECH")
    for i, ch in enumerate("PCIE5"):
        label(c, 2, 120 + i * 6, ch)
    save(c, "tower")

    # RGB mask: hard lit pixels (stripes, window rim) with a dim 1px halo
    g = Canvas(BW, BH)
    poly(g, [(14, 8), (33, 8), (33, 63), (28, 68), (14, 68)], (255, 255, 255, 26))
    g.vline(14, 8, 68, (255, 255, 255, 200))
    g.vline(40, 6, 70, (255, 255, 255, 255))
    g.vline(39, 6, 70, (255, 255, 255, 90))
    g.line(135, 132, 165, 122, (255, 255, 255, 255))
    g.line(135, 133, 165, 123, (255, 255, 255, 110))
    poly(g, [(137, 134), (163, 125), (163, 167), (137, 167)], (255, 255, 255, 20))
    save(g, "tower_rgb")


# ---------------------------------------------------------------------------------------------- retro board

def retro():
    c = Canvas(BW, BH)
    fill(c, 0, 0, BW, BH, hexc("2a6b3e"))
    grain(c, 0, 0, BW - 1, BH - 1, 0.07)
    c.hline(0, BW - 1, BH - 1, hexc("173d23"))
    c.vline(BW - 1, 0, BH - 1, hexc("173d23"))
    c.hline(0, BW - 2, 0, hexc("3f8a56"))
    c.vline(0, 0, BH - 2, hexc("3f8a56"))
    keep = [(0, 0, 14, 104), (46, 28, 94, 82), (114, 14, 132, 104), (142, 12, 174, 40), (18, 104, 102, 120), (18, 126, 102, 176),
            (152, 100, 178, 154), (16, 180, 144, 198), (106, 134, 160, 178), (12, 28, 40, 50), (116, 104, 144, 130), (142, 56, 154, 96),
            (96, 30, 104, 52), (38, 28, 46, 42), (10, 120, 90, 134)]
    traces(c, keep, 300, hexc("3a8452"))
    smd_field(c, list(keep), 120, [hexc("3a2c1a"), hexc("1b1b1b"), hexc("6b5a3a")])
    for (x, y, w, h) in [(60, 86, 10, 6), (78, 86, 8, 6), (106, 60, 6, 8), (150, 44, 6, 8), (40, 92, 9, 6), (108, 186, 7, 5)]:
        ic(c, x, y, w, h, hexc("161616"), hexc("c9c9c9"))
    for (x, y) in [(5, 5), (174, 5), (5, 108), (174, 98), (5, 198), (174, 198), (104, 126)]:
        mount(c, x, y)
    for (x, y, col) in [(98, 32, "2c5fd0"), (98, 38, "2c5fd0"), (98, 44, "2c5fd0"), (40, 30, "d0a42c"), (40, 36, "d0a42c"),
                        (104, 102, "2c5fd0"), (140, 104, "2c5fd0"), (146, 104, "2c5fd0")]:
        fill(c, x + 1, y + 1, 5, 5, (0, 0, 0, 90))
        c.disc(x + 2.5, y + 2.5, 2.6, hexc(col))
        c.set(x + 1, y + 1, shade(hexc(col), 1.5))
        c.set(x + 2, y + 2, shade(hexc(col), 0.6))
        c.set(x + 3, y + 2, shade(hexc(col), 0.6))
        c.set(x + 2, y + 3, shade(hexc(col), 0.6))
    # rear ports: PS/2, serial, parallel, USB
    block(c, 0, 4, 10, 82, hexc("a2a7ad"), 1.2, 0.6)
    c.disc(5, 12, 3.3, hexc("6a4fb0"))
    c.disc(5, 12, 1.5, hexc("1d1d1d"))
    block(c, 1, 20, 8, 18, hexc("2e7d5b"), 1.3, 0.6)
    block(c, 1, 42, 8, 30, hexc("b03a6e"), 1.3, 0.6)
    for k in range(9):
        c.set(4, 23 + k * 2, hexc("111111"))
        c.set(4, 45 + k * 3, hexc("111111"))
    block(c, 0, 75, 9, 8, hexc("c3c7cd"), 1.2, 0.6)
    fill(c, 1, 77, 7, 3, hexc("1b1b1b"))
    label(c, 11, 77, "USB", hexc("cfe8d6"))
    zif(c, 48, 30, 44)
    label(c, 48, 76, "SOCKET 7", hexc("cfe8d6"))
    fins(c, 14, 30, 22, 16, hexc("a5abb3"), True, 2)
    for i in range(2):
        dimm(c, 118 + i * 8, 20, 95, hexc("161616"), hexc("eeeeee"))
        label(c, 118 + i * 8, 100 + i * 6, "D" + str(i + 1), hexc("cfe8d6"))
    shadow(c, 144, 14, 28, 18, 100, 2)
    block(c, 144, 14, 28, 18, hexc("efece2"), 1.05, 0.75)
    for j in range(2):
        for i in range(9):
            fill(c, 146 + i * 3, 16 + j * 8, 2, 6, hexc("cbc6b4"))
    label(c, 144, 34, "ATX", hexc("cfe8d6"))
    shadow(c, 112, 154, 20, 18, 100, 2)
    block(c, 112, 154, 20, 18, hexc("161616"), 1.8, 0.6)
    fill(c, 113, 156, 18, 14, hexc("e8e2c6"))
    label(c, 113, 158, "AWARD", hexc("a3262b"))
    label(c, 115, 164, "BIOS", hexc("333333"))
    c.disc(146, 166, 7.5, hexc("7d838b"))
    c.disc(146, 166, 6.5, hexc("d1d5db"))
    c.disc(144, 164, 2.5, hexc("eef1f4"))
    for k in range(4):
        header(c, 112 + k * 5, 138, 1, 3, hexc("161616"), hexc("d8c070"))
        fill(c, 112 + k * 5, 138 + (k % 2) * 2, 3, 3, hexc("1d4fd0"))
    slot(c, 22, 97, 108, hexc("6b3f22"), latch=False)
    label(c, 22, 115, "AGP", hexc("cfe8d6"))
    for i, y in enumerate([130, 148, 166]):
        slot(c, 22, 97, y, hexc("eeece4"), latch=False)
        label(c, 22, y + 6, "PCI" + str(i + 1), hexc("cfe8d6"))
    slot(c, 20, 139, 184, hexc("1a1a1a"), latch=False, h=6)
    label(c, 20, 192, "ISA", hexc("cfe8d6"))
    for (ix, n) in [(156, "IDE1"), (167, "IDE2")]:
        shadow(c, ix, 104, 8, 42, 100, 2)
        block(c, ix, 104, 8, 42, hexc("1b1b1b"), 1.8, 0.6)
        for j in range(20):
            c.set(ix + 2, 106 + j * 2, hexc("d8c070"))
            c.set(ix + 5, 106 + j * 2, hexc("d8c070"))
        fill(c, ix - 1, 122, 1, 5, hexc("1b1b1b"))
        label(c, ix - 1, 148 + (6 if n == "IDE2" else 0), n, hexc("cfe8d6"))
    shadow(c, 144, 58, 8, 30, 100, 2)
    block(c, 144, 58, 8, 30, hexc("1b1b1b"), 1.8, 0.6)
    label(c, 143, 90, "FDD", hexc("cfe8d6"))
    fins(c, 120, 108, 20, 20, hexc("aab0b8"), False, 2)
    label(c, 14, 122, "HVX-430TX PCISET", hexc("e7f5ea"))
    label(c, 14, 128, "REV 1.3 1998", hexc("9cc8a8"))
    save(c, "retro")
    g = Canvas(BW, BH)
    fill(g, 151, 6, 3, 2, (255, 255, 255, 255))
    save(g, "retro_rgb")


# ---------------------------------------------------------------------------------------------- parts

ACCENT = {1: "8a93a2", 2: "3bd1ff", 3: "7c5cff", 4: "ff4d6a"}


def cpu(tier):
    c = Canvas(34, 34)
    sub = hexc("23784a")
    block(c, 0, 0, 34, 34, sub, 1.3, 0.55, 0.04)
    for i in range(2, 32, 2):
        c.set(i, 0, hexc("e0b456"))
        c.set(i, 33, hexc("e0b456"))
        c.set(0, i, hexc("e0b456"))
        c.set(33, i, hexc("e0b456"))
    # raised nickel heat spreader: lit rim, 2px side band (its thickness) and a cast shadow
    fill(c, 4, 5, 28, 27, (0, 0, 0, 110))
    ihs = hexc("c3c8cf")
    c.vgrad(3, 3, 30, 28, hexc("e2e6eb"), hexc("aab0b8"))
    grain(c, 3, 3, 30, 28, 0.035, 11 + tier)
    c.bevel(3, 3, 30, 28, hexc("f4f6f8"), hexc("8a9099"))
    fill(c, 3, 29, 28, 2, shade(ihs, 0.62))
    c.hline(3, 30, 31, shade(ihs, 0.45))
    for i in range(3):
        fill(c, 4, 26 - i, 3 - i, 1, hexc("e0b456"))
    name = "ORBIT" if tier < 4 else "RIPPER"
    label(c, 6, 5, name, hexc("4a5059"))
    c.hline(6, 6 + text_width(name) - 1, 11, hexc(ACCENT[tier]))
    label(c, 6, 22, ["2C 3.2", "4C 4.1", "8C 5.0", "16C 5.4"][tier - 1], hexc("6a7079"))
    save(c, f"cpu_{tier}")


def cpu_retro():
    c = Canvas(34, 34)
    block(c, 0, 0, 34, 31, hexc("dcd8cc"), 1.08, 0.7, 0.03)
    fill(c, 0, 31, 34, 3, hexc("9d9a90"))
    for y in range(2, 30, 2):
        for x in range(2, 32, 2):
            if not (9 <= x <= 24 and 8 <= y <= 23):
                c.set(x, y, hexc("bdb8a8"))
    block(c, 10, 9, 14, 14, hexc("d5ad5c"), 1.25, 0.6)
    c.vgrad(11, 10, 22, 21, hexc("ecc777"), hexc("b88d3e"))
    label(c, 6, 25, "233MHZ", hexc("5a564c"))
    save(c, "cpu_retro")


def plate():
    c = Canvas(44, 44)
    st = hexc("b9bec5")
    block(c, 0, 0, 44, 44, st, 1.3, 0.55, 0.03)
    c.rect(7, 7, 36, 36, (0, 0, 0, 0))
    c.bevel(6, 6, 37, 37, hexc("7c828a"), hexc("e3e7ec"))
    for (x, y) in [(2, 2), (40, 2), (2, 40), (40, 40)]:
        fill(c, x, y, 2, 2, hexc("6b7179"))
    fill(c, 14, 0, 16, 2, hexc("8f959d"))
    save(c, "plate")


def lever():
    c = Canvas(4, 42)
    fill(c, 1, 3, 2, 39, hexc("d4d8de"))
    c.vline(2, 3, 41, hexc("8d939b"))
    block(c, 0, 0, 4, 4, hexc("a4aab2"), 1.3, 0.6)
    save(c, "lever")


def ram_side(tier):
    """Standing memory stick seen from the side: 82 long, 18 tall, gold fingers at the bottom."""
    c = Canvas(82, 18)
    fill(c, 0, 16, 82, 2, hexc("d9b04d"))
    if tier == 1:
        block(c, 0, 0, 82, 16, hexc("23784a"), 1.3, 0.6)
        for k in range(8):
            block(c, 2 + k * 10, 4, 7, 9, hexc("17181b"), 1.8, 0.6)
    else:
        body = {2: hexc("3a3e46"), 3: hexc("1f2227"), 4: hexc("1f2227")}[tier]
        block(c, 0, 0, 82, 16, body, 1.55, 0.6, 0.03)
        for k in range(9):
            c.line(4 + k * 9, 13, 10 + k * 9, 4, shade(body, 1.7))
        fill(c, 28, 7, 26, 4, hexc("101114"))
        c.hline(29, 52, 8, hexc(ACCENT[tier]))
        if tier >= 3:
            fill(c, 0, 0, 82, 3, hexc("e9edf3") if tier == 4 else hexc("3a3e46"))
            c.hline(0, 81, 3, hexc("0c0d10"))
    save(c, f"ram_side_{tier}")


def ram_retro():
    c = Canvas(78, 16)
    fill(c, 0, 14, 78, 2, hexc("d9b04d"))
    block(c, 0, 0, 78, 14, hexc("23784a"), 1.3, 0.6)
    for k in range(8):
        block(c, 2 + k * 9, 3, 6, 8, hexc("141414"), 1.8, 0.6)
    save(c, "ram_side_retro")


def ram_rgb():
    g = Canvas(82, 18)
    fill(g, 0, 0, 82, 3, (255, 255, 255, 255))
    save(g, "ram_side_rgb")


def latch():
    c = Canvas(5, 3)
    block(c, 0, 0, 5, 3, hexc("cdd1d7"), 1.2, 0.6)
    save(c, "latch")


FANS = {1: [46], 2: [40, 89], 3: [32, 65, 98], 4: [32, 65, 98]}


def fan_frames():
    """Four frames of a 9-blade fan (blades repeat every 40 degrees, so 10 degrees per frame)."""
    for f in range(4):
        c = Canvas(28, 28)
        c.disc(14, 14, 13.5, hexc("101114"))
        for k in range(9):
            a = 2 * math.pi * k / 9 + math.radians(f * 10)
            pts = []
            for t in range(9):
                r = 4 + t * 1.1
                pts.append((14 + math.cos(a + t * 0.07) * r, 14 + math.sin(a + t * 0.07) * r))
            for t in range(8, -1, -1):
                r = 4 + t * 1.1
                pts.append((14 + math.cos(a + 0.45 + t * 0.09) * r, 14 + math.sin(a + 0.45 + t * 0.09) * r))
            poly(c, pts, hexc("2c3036"))
            ex, ey = 14 + math.cos(a + 0.6) * 10, 14 + math.sin(a + 0.6) * 10
            c.set(int(ex), int(ey), hexc("4a4f57"))
        c.disc(14, 14, 4.2, hexc("23262b"))
        c.disc(14, 14, 3.2, hexc("3a3e45"))
        c.set(12, 12, hexc("6a6f78"))
        save(c, f"fan_{f}")


def gpu(tier):
    """Standing graphics card, fan side: bracket on the left, gold fingers at the bottom."""
    w, h = (86, 34) if tier == 1 else (124, 40)
    c = Canvas(w, h)
    fill(c, 6, h - 4, w - 8, 4, hexc("1d4a2e"))
    for x in range(8, w - 6, 2):
        fill(c, x, h - 2, 1, 2, hexc("e0b456"))
    block(c, 0, 0, 4, h - 2, hexc("c4c8ce"), 1.2, 0.6)
    for k in range(3):
        fill(c, 1, 4 + k * 9, 2, 5, hexc("17181b"))
    body = {1: hexc("3a3e46"), 2: hexc("30343b"), 3: hexc("262a30"), 4: hexc("1d2025")}[tier]
    block(c, 4, 0, w - 4, h - 4, body, 1.5, 0.6, 0.03)
    poly(c, [(5, 1), (20, 1), (13, h - 6), (5, h - 6)], shade(body, 1.3))
    poly(c, [(w - 18, 1), (w - 2, 1), (w - 2, h - 6), (w - 10, h - 6)], shade(body, 1.25))
    c.line(20, 1, 13, h - 6, hexc(ACCENT[tier]))
    c.line(w - 18, 1, w - 10, h - 6, hexc(ACCENT[tier]))
    cy = (h - 4) // 2
    for cx in FANS[tier]:
        c.disc(cx, cy, 13.6 if tier > 1 else 12.6, shade(body, 0.55))
        c.disc(cx, cy, 12.8 if tier > 1 else 11.8, hexc("08090b"))
    name = ["GT610", "OT GT", "OT GX", "TITAN"][tier - 1]
    label(c, w - 5 - text_width(name), h - 11, name, hexc("d5dae2"))
    save(c, f"gpu_{tier}")
    # top edge of the cooler (what you see from above)
    t = Canvas(w, 14 if tier > 1 else 10)
    block(t, 0, 0, w, t.h, shade(body, 1.15), 1.4, 0.6, 0.04)
    for x in range(6, w - 6, 3):
        t.vline(x, 2, t.h - 3, shade(body, 0.75))
    t.hline(4, w - 5, t.h - 2, hexc("ffffff"))
    label(t, 6, (t.h - 5) // 2, "ORBITFORCE", hexc("aab1bb"))
    save(t, f"gpu_top_{tier}")


def gpu_retro():
    w, h = 84, 30
    c = Canvas(w, h)
    block(c, 0, 0, 4, h - 2, hexc("c4c8ce"), 1.2, 0.6)
    block(c, 4, 0, w - 4, h - 2, hexc("23784a"), 1.3, 0.55, 0.04)
    for x in range(8, w - 4, 2):
        fill(c, x, h - 2, 1, 2, hexc("e0b456"))
    for k in range(4):
        block(c, 46 + (k % 2) * 9, 5 + (k // 2) * 9, 7, 7, hexc("141414"), 1.8, 0.6)
    fins(c, 16, 4, 24, 20, hexc("aab0b8"), False, 2)
    label(c, 64, 22, "VDX", hexc("e8e2c6"))
    save(c, "gpu_retro")


def m2(kind):
    w = 62
    if kind == "hdd":
        h = 14
        c = Canvas(w, h)
        block(c, 0, 0, w, h, hexc("b9bec5"), 1.25, 0.55, 0.03)
        c.disc(15, 6, 5, hexc("d4d8de"))
        c.disc(15, 6, 4, hexc("aab0b8"))
        c.disc(15, 6, 1.2, hexc("5e646c"))
        fill(c, 26, 2, 32, 8, hexc("f2f2f2"))
        fill(c, 26, 2, 32, 2, hexc("2d5fd8"))
        label(c, 28, 5, "HDD 7200", hexc("333333"))
        for (x, y) in [(1, 1), (w - 2, 1), (1, h - 4), (w - 2, h - 4)]:
            c.set(x, y, hexc("5e646c"))
        save(c, "hdd")
        return
    h = 10
    c = Canvas(w, h)
    pcb = hexc("1f2227") if kind == "nvme" else hexc("23784a")
    block(c, 0, 0, w, h, pcb, 1.3, 0.55, 0.0)
    for y in range(2, h - 2):
        c.set(0, y, hexc("e0b456"))
    c.disc(w - 2, h / 2, 1.4, hexc("e0b456"))
    if kind == "nvme":
        block(c, 4, 0, w - 8, h, hexc("3a3f47"), 1.5, 0.55, 0.03)
        for x in range(8, w - 8, 3):
            c.vline(x, 2, h - 4, hexc("23262b"))
        fill(c, 20, 3, 22, 3, hexc("101114"))
        c.hline(21, 40, 4, hexc("3bd1ff"))
    else:
        for i in range(2):
            block(c, 10 + i * 17, 2, 13, 5, hexc("141518"), 1.8, 0.6)
        block(c, 46, 3, 6, 4, hexc("141518"), 1.8, 0.6)
        c.hline(30, 38, h - 3, hexc("e9edf3"))
    save(c, "m2_" + kind)


def ide():
    c = Canvas(30, 44)
    for j in range(44):
        col = hexc("c0392b") if j < 2 else hexc("bcbfc4") if j % 2 == 0 else hexc("a4a7ac")
        fill(c, 9, j, 21, 1, col)
    fill(c, 1, 1, 9, 43, (0, 0, 0, 110))
    block(c, 0, 0, 10, 44, hexc("2d2e32"), 1.7, 0.55)
    fill(c, 2, 2, 6, 40, hexc("222326"))
    c.vline(8, 1, 42, hexc("17181b"))
    save(c, "ide")


def card(kind):
    """Standing expansion card, component side: bracket on the left, gold fingers at the bottom."""
    w, h = 52, 16
    c = Canvas(w, h)
    pcb = {"wifi": hexc("1f2227"), "gpio": hexc("6a2125"), "retro": hexc("23784a")}[kind]
    block(c, 0, 0, 3, h - 1, hexc("c4c8ce"), 1.2, 0.6)
    block(c, 3, 0, w - 3, h - 2, pcb, 1.35, 0.55, 0.04)
    for x in range(7, w - 4, 2):
        fill(c, x, h - 2, 1, 2, hexc("e0b456"))
    if kind == "wifi":
        block(c, 14, 3, 16, 7, hexc("3a3f47"), 1.5, 0.55)
        label(c, 15, 4, "WIFI", hexc("d5dae2"))
        for k in range(2):
            c.disc(1.5, 3.5 + k * 6, 1.4, hexc("e0b456"))
        block(c, 36, 4, 9, 5, hexc("141518"), 1.8, 0.6)
    elif kind == "gpio":
        block(c, 10, 3, 14, 7, hexc("141518"), 1.8, 0.6)
        label(c, 12, 4, "IO", hexc("ff8a8a"))
        for k in range(8):
            fill(c, 28 + k * 2, 3, 1, 3, hexc("ff3b3b"))
            fill(c, 28 + k * 2, 7, 1, 2, hexc("e0b456"))
    else:
        for k in range(3):
            block(c, 9 + k * 12, 3, 9, 7, hexc("141414"), 1.8, 0.6)
    save(c, "card_" + kind)


def usb():
    c = Canvas(24, 8)
    block(c, 16, 1, 8, 6, hexc("cdd1d7"), 1.2, 0.6)
    fill(c, 17, 3, 6, 2, hexc("8f959d"))
    block(c, 0, 0, 17, 8, hexc("2f63e0"), 1.35, 0.55, 0.03)
    label(c, 4, 1, "32G", hexc("e9edf3"))
    c.set(1, 3, hexc("9ff0b0"))
    save(c, "usb")


def screw():
    c = Canvas(3, 3)
    fill(c, 0, 0, 3, 3, hexc("aab0b8"))
    for (x, y) in [(1, 0), (1, 1), (1, 2), (0, 1), (2, 1)]:
        c.set(x, y, hexc("4a4f57"))
    c.set(0, 0, hexc("dfe3e8"))
    save(c, "screw")


def main():
    # leftover sprites from the old flat views, the 3D board doesn't use them anymore
    for old in ["fan", "ram_rgb", "ram_retro"] + [f"ram_{t}" for t in range(1, 5)] + [f"ram_top_{t}" for t in range(1, 5)]:
        p = os.path.join(OUT, old + ".png")
        if os.path.exists(p):
            os.remove(p)
    tower()
    retro()
    for t in range(1, 5):
        cpu(t)
        ram_side(t)
        gpu(t)
    cpu_retro()
    plate()
    lever()
    ram_retro()
    ram_rgb()
    latch()
    fan_frames()
    gpu_retro()
    for k in ("nvme", "sata", "hdd"):
        m2(k)
    ide()
    for k in ("wifi", "gpio", "retro"):
        card(k)
    usb()
    screw()


if __name__ == "__main__":
    main()
