"""The detailed block models in HyperVisor X, based on real hardware.

References used for proportions and details (photos, not copied): NZXT H9 / S5 panoramic-glass
cases (glass front + side, vertical side fans, top radiator, PSU shroud), beige AT mini-towers and
14" CRTs of the early 90s, 2U rack servers with 2.5" hot-swap caddies, Samsung/LG 49" ultrawides,
Panasonic Toughbook rugged laptops, full-size RGB mechanical keyboards and bookshelf speakers.

Every model faces north (front at z = 0); x grows toward the viewer's left.
"""
import json
import math
import os

from PIL import Image

from model_kit import Model, Ref
from pixelkit import Canvas, alpha, hexc, hsv, mix, shade, RNG

MOD = "hypervisorx"


# ============================================================================================ painters
def paint(base, noise=0.035, edge=True, hi=1.14, lo=0.72):
    def p(c):
        for y in range(c.h):
            for x in range(c.w):
                c.set(x, y, shade(base, 1 + RNG.uniform(-noise, noise)))
        if edge and c.w > 2 and c.h > 2:
            c.hline(0, c.w - 1, 0, shade(base, hi))
            c.vline(0, 0, c.h - 1, shade(base, hi * 0.98))
            c.hline(0, c.w - 1, c.h - 1, shade(base, lo))
            c.vline(c.w - 1, 0, c.h - 1, shade(base, lo * 1.05))
    return p


def flat(color):
    def p(c):
        c.rect(0, 0, c.w - 1, c.h - 1, color)
    return p


def brushed(base, vertical=False):
    def p(c):
        for y in range(c.h):
            for x in range(c.w):
                k = x if vertical else y
                f = 1.0 + 0.05 * math.sin(k * 2.3 + (x if not vertical else y) * 0.07) + RNG.uniform(-0.02, 0.02)
                c.set(x, y, shade(base, f))
        if c.w > 2 and c.h > 2:
            c.hline(0, c.w - 1, 0, shade(base, 1.18))
            c.hline(0, c.w - 1, c.h - 1, shade(base, 0.7))
    return p


def mesh(base, hole, step=2, border=1, hex_offset=True):
    def p(c):
        paint(base, 0.03)(c)
        for j, y in enumerate(range(border, c.h - border, step)):
            off = 1 if (hex_offset and j % 2) else 0
            for x in range(border + off, c.w - border, step):
                c.set(x, y, hole)
    return p


def glass(tint=(150, 185, 215, 46), frame=None):
    def p(c):
        c.rect(0, 0, c.w - 1, c.h - 1, tint)
        for k in range(0, c.w + c.h, 11):
            for t in range(3):
                x, y = k + t, 0
                while x >= 0 and y < c.h:
                    if x < c.w:
                        c.set(x, y, (255, 255, 255, 30 - t * 8))
                    x -= 1
                    y += 1
        if frame:
            c.frame(0, 0, c.w - 1, c.h - 1, frame)
    return p


def chain(*ps):
    def p(c):
        for q in ps:
            q(c)
    return p


def rot180(painter):
    def p(c):
        painter(c)
        c.img = c.img.rotate(180)
        c.px = c.img.load()
    return p


# ============================================================================================ palette
BEIGE = hexc("d8cfb8")
BEIGE_D = hexc("c4baa0")
CASE_BLACK = hexc("1b1d22")
STEEL_DARK = hexc("2a2e35")
GRAPHITE = hexc("3a3f48")
PCB_DARK = hexc("161a1f")
ALU = hexc("c8cdd5")
LED_G = hexc("4dff88")
LED_A = hexc("ffb02e")
LED_B = hexc("38c6ff")
WOOD = hexc("6b4429")


# ============================================================================================ animated & shared textures
def fan_frame(phase, on, accent_h=None):
    c = Canvas(32)
    c.rect(0, 0, 31, 31, hexc("15171b"))
    c.frame(0, 0, 31, 31, hexc("23262c"))
    for (x, y) in ((2, 2), (29, 2), (2, 29), (29, 29)):
        c.disc(x + 0.5, y + 0.5, 1.4, hexc("0b0c0f"))
    cx = cy = 16
    if on:
        def ring_col(x, y, d):
            a = math.atan2(y - cy, x - cx) / (2 * math.pi)
            return hsv(a + phase, 0.75, 1.0)
        c.ring(cx, cy, 13.0, 15.0, ring_col)
        c.ring(cx, cy, 12.2, 13.0, lambda x, y, d: alpha(hsv(math.atan2(y - cy, x - cx) / (2 * math.pi) + phase, 0.6, 1.0), 140))
    else:
        c.ring(cx, cy, 13.0, 15.0, hexc("2c3038"))
    c.disc(cx, cy, 12.1, hexc("0d0e11"))
    blades = 9
    for k in range(blades):
        a0 = phase * 2 * math.pi + k * 2 * math.pi / blades
        for t in range(40):
            r = 4.5 + 7.2 * t / 40
            a = a0 + 0.55 * t / 40
            for w in range(3):
                aa = a + w * 0.06
                c.set(int(cx + math.cos(aa) * r), int(cy + math.sin(aa) * r),
                      hexc("2b2f37") if not on else mix(hexc("2b2f37"), hsv(phase + k / blades, 0.4, 0.9), 0.18))
    c.disc(cx, cy, 4.6, hexc("1d2026"))
    c.ring(cx, cy, 3.4, 4.6, hexc("2a2e35"))
    c.disc(cx, cy, 2.0, hexc("2f343c") if not on else hsv(phase + 0.5, 0.5, 0.9))
    return c.img


def rgb_strip_frame(phase, on):
    c = Canvas(16)
    for x in range(16):
        col = hsv(x / 16.0 + phase, 0.8, 1.0) if on else hexc("2e3138")
        for y in range(16):
            c.set(x, y, mix(col, (255, 255, 255, 255), 0.25) if (y < 5 and on) else col)
    return c.img


def server_front_frame(frame, on, seed=0):
    """2U server bezel, drawn into a 32x32 frame (only the top 32x18 is used)."""
    rnd = __import__("random").Random(seed * 1000 + frame)
    c = Canvas(32)
    c.rect(0, 0, 31, 17, hexc("23262c"))
    c.hline(0, 31, 0, hexc("4a505b"))
    c.hline(0, 31, 17, hexc("101215"))
    # ears with handles
    for x0 in (0, 29):
        c.rect(x0, 1, x0 + 2, 16, hexc("3b4049"))
        c.vline(x0 + 1, 3, 14, hexc("16181c"))
    # 8 hot-swap 2.5" caddies
    for i in range(8):
        x = 4 + i * 3
        c.rect(x, 2, x + 1, 13, hexc("17191d"))
        c.vline(x, 2, 13, hexc("2e3239"))
        busy = on and rnd.random() < 0.55
        c.set(x + 1, 3, LED_B if on else hexc("1e2a33"))
        c.set(x + 1, 5, (LED_G if busy else hexc("1f6b3a")) if on else hexc("1f2a22"))
    # vents + status
    for y in range(3, 14, 2):
        for x in range(28, 29):
            c.set(x, y, hexc("111316"))
    c.rect(4, 15, 26, 16, hexc("1b1d22"))
    c.set(5, 15, LED_B if on else hexc("1e2a33"))
    c.set(7, 15, LED_G if on else hexc("1f2a22"))
    c.set(9, 15, (LED_A if (on and frame % 4 == 0) else hexc("3a2f1a")))
    c.hline(16, 25, 15, hexc("2e3239"))
    return c.img


def keyboard_frame(phase, on):
    """Full-size 104-key layout drawn as the typist sees it (rotated later for the up face)."""
    W, H = 64, 24
    c = Canvas(64)
    c.rect(0, 0, W - 1, H - 1, hexc("0d0e11"))
    rows = [
        [1.0, 0.5] + [1.0] * 4 + [0.5] + [1.0] * 4 + [0.5] + [1.0] * 4 + [0.5, 1, 1, 1],
        [1.0] * 13 + [2.0, 0.5, 1, 1, 1, 0.5, 1, 1, 1, 1],
        [1.5] + [1.0] * 12 + [1.5, 0.5, 1, 1, 1, 0.5, 1, 1, 1, 1],
        [1.75] + [1.0] * 11 + [2.25, 0.5, 0.0, 0.0, 0.0, 0.5, 1, 1, 1, 1],
        [2.25] + [1.0] * 10 + [2.75, 0.5, 0.0, 1, 0.0, 0.5, 1, 1, 1, 1],
        [1.25, 1.25, 1.25, 6.25, 1.25, 1.25, 1.25, 1.25, 0.5, 1, 1, 1, 0.5, 2, 1, 1],
    ]
    unit = 64 / 23.5
    ys = [1, 5, 9, 13, 17, 20]
    hs = [2, 3, 3, 3, 3, 3]
    for r, row in enumerate(rows):
        x = 0.6
        for wdt in row:
            if wdt in (0.5,) or wdt == 0.0:
                x += (wdt if wdt else 1.0) * unit
                continue
            x0 = int(round(x))
            x1 = int(round(x + wdt * unit)) - 2
            y0, y1 = ys[r], ys[r] + hs[r] - 1
            glow = hsv(phase + x / 64.0 * 0.9 + r * 0.03, 0.85, 1.0) if on else hexc("2b2e35")
            # backlight bleeding around the cap
            c.rect(x0 - 1, y0 - 1 if r else y0, x1 + 1, y1 + 1, mix(hexc("0d0e11"), glow, 0.55 if on else 0.15))
            c.rect(x0, y0, x1, y1, hexc("202329"))
            c.hline(x0, x1, y0, hexc("2f333b"))
            if on and x1 - x0 >= 1:
                c.set(x0 + (x1 - x0) // 2, y0 + (1 if hs[r] > 2 else 0), mix(glow, (255, 255, 255, 255), 0.35))
            x += wdt * unit
    return c.img


def mouse_strip_frame(phase, on):
    return rgb_strip_frame(phase, on)


def aio_lcd():
    c = Canvas(32)
    c.disc(16, 16, 15.5, hexc("101216"))
    c.ring(16, 16, 13.5, 15.5, hexc("2b2f37"))
    c.disc(16, 16, 13.4, hexc("06070a"))
    for k in range(64):
        a = -math.pi * 1.25 + k / 63 * math.pi * 1.5
        if k < 44:
            col = hsv(0.55 + k / 160.0, 0.8, 1.0)
            c.set(int(16 + math.cos(a) * 11), int(16 + math.sin(a) * 11), col)
            c.set(int(16 + math.cos(a) * 10), int(16 + math.sin(a) * 10), col)
    from pixelkit import FONT_3x5
    c.text(12, 12, "32", hexc("f2f5f9"))
    c.text(13, 19, "C", hexc("8a94a6"))
    return c.img


def write_anim(tex_dir, name, frames, frametime, interpolate=False):
    os.makedirs(tex_dir, exist_ok=True)
    w, h = frames[0].size
    strip = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    strip.save(os.path.join(tex_dir, name + ".png"))
    with open(os.path.join(tex_dir, name + ".png.mcmeta"), "w", encoding="utf-8") as f:
        json.dump({"animation": {"frametime": frametime, "interpolate": interpolate}}, f)


def shared_textures(tex_dir):
    d = os.path.join(tex_dir, "fx")
    os.makedirs(d, exist_ok=True)
    write_anim(d, "fan_rgb", [fan_frame(i / 8.0, True) for i in range(8)], 1)
    fan_frame(0, False).save(os.path.join(d, "fan_off.png"))
    write_anim(d, "rgb_strip", [rgb_strip_frame(i / 24.0, True) for i in range(24)], 2, True)
    rgb_strip_frame(0, False).save(os.path.join(d, "rgb_off.png"))
    for s in range(2):
        write_anim(d, f"server_front_on_{s}", [server_front_frame(i, True, s) for i in range(8)], 6)
    server_front_frame(0, False).save(os.path.join(d, "server_front_off.png"))
    write_anim(d, "keyboard_keys", [keyboard_frame(i / 20.0, True) for i in range(20)], 3, True)
    keyboard_frame(0, False).save(os.path.join(d, "keyboard_keys_off.png"))
    aio_lcd().save(os.path.join(d, "aio_lcd.png"))


def fx(name):
    return f"{MOD}:block/fx/{name}"


# ============================================================================================ face painters (specific)
def retro_bezel(on):
    def p(c):
        paint(BEIGE, 0.025, hi=1.12, lo=0.8)(c)
        w, h = c.w, c.h
        # bottom intake grille
        for y in range(h - 5, h - 1, 2):
            c.hline(2, w - 3, y, shade(BEIGE, 0.72))
        # 7-segment MHz display + LEDs + turbo/reset + key lock
        dx, dy = 2, h - 14
        c.rect(dx, dy, dx + 8, dy + 6, hexc("140c0c"))
        seg = hexc("ff3b2f") if on else hexc("3a1714")
        c.text(dx + 1, dy + 1, "66", seg)
        c.set(dx + 10, dy + 1, LED_G if on else hexc("2f4a35"))
        c.set(dx + 10, dy + 4, LED_A if on else hexc("4a3d24"))
        c.rect(dx, dy + 8, dx + 1, dy + 9, shade(BEIGE, 0.7))
        c.rect(dx + 3, dy + 8, dx + 4, dy + 9, shade(BEIGE, 0.7))
        c.disc(w - 4.5, dy + 1.5, 1.3, hexc("9aa0a8"))
        c.set(w - 5, dy + 1, hexc("2b2f36"))
        # badge
        c.rect(2, 3, 6, 4, hexc("3b82f6"))
        c.rect(7, 3, 8, 4, hexc("ef4444"))
    return p


def cdrom(c):
    paint(hexc("cfc6ae"), 0.02, hi=1.15, lo=0.75)(c)
    w, h = c.w, c.h
    c.hline(1, w - 2, 1, shade(BEIGE, 0.68))
    c.rect(w - 4, h - 2, w - 2, h - 2, shade(BEIGE, 0.62))
    c.set(w - 6, h - 2, LED_A)
    c.rect(2, h - 2, 5, h - 2, shade(BEIGE, 0.8))


def bay_cover(c):
    paint(hexc("d2c9b1"), 0.02, hi=1.15, lo=0.78)(c)
    c.hline(1, c.w - 2, c.h // 2, shade(BEIGE, 0.85))


def floppy(on):
    def p(c):
        paint(hexc("cac1a8"), 0.02, hi=1.15, lo=0.75)(c)
        c.hline(1, c.w - 3, 0 if c.h < 3 else 1, hexc("2a2a2a"))
        c.set(c.w - 2, c.h - 1, LED_G if on else hexc("2f4a35"))
        c.rect(c.w - 3, c.h - 1, c.w - 3, c.h - 1, shade(BEIGE, 0.6))
    return p


def retro_side(c):
    paint(BEIGE, 0.025, hi=1.1, lo=0.8)(c)
    # front-bottom intake slots near the front edge (east/west faces: left/right depends on side)
    for y in range(c.h - 9, c.h - 2, 2):
        c.hline(2, 8, y, shade(BEIGE, 0.74))
    c.vline(c.w - 2, 1, c.h - 2, shade(BEIGE, 0.9))


def retro_back(c):
    paint(BEIGE_D, 0.03)(c)
    w, h = c.w, c.h
    c.rect(1, 1, w - 2, 9, hexc("8f949b"))  # PSU
    c.ring(w / 2 + 2, 5, 2.2, 3.6, hexc("2b2f36"))
    c.disc(w / 2 + 2, 5, 2.0, hexc("1b1d22"))
    c.rect(2, 3, 4, 6, hexc("2b2f36"))
    for i in range(4):
        c.rect(2 + i * 3, 13, 3 + i * 3, h - 5, hexc("a8adb4"))
    c.rect(2, 11, w - 3, 11, hexc("2b2f36"))


def crt_glass(c):
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("0e110f"))
    for y in range(c.h):
        for x in range(c.w):
            d = ((x - c.w / 2) / (c.w / 2)) ** 2 + ((y - c.h / 2) / (c.h / 2)) ** 2
            c.set(x, y, mix(hexc("1a201c"), hexc("070908"), min(1, d)))


def crt_chin(c):
    paint(BEIGE, 0.025, hi=1.1, lo=0.75)(c)
    w, h = c.w, c.h
    for i in range(5):
        c.rect(w // 2 - 8 + i * 3, h // 2, w // 2 - 7 + i * 3, h // 2, shade(BEIGE, 0.6))
    c.rect(w - 6, h // 2 - 1, w - 3, h // 2 + 1, shade(BEIGE, 0.7))
    c.set(w - 8, h // 2, LED_G)
    c.rect(3, h // 2, 9, h // 2, shade(BEIGE, 0.78))


def crt_back(c):
    paint(BEIGE_D, 0.03)(c)
    for y in range(2, c.h - 2, 2):
        c.hline(2, c.w - 3, y, shade(BEIGE_D, 0.8))


def vents_top(base, step=2):
    def p(c):
        paint(base, 0.03)(c)
        for x in range(3, c.w - 3, step):
            c.vline(x, 2, c.h - 3, shade(base, 0.72))
    return p


def bezel_front(c):
    """Thin-bezel monitor front: the renderer covers the inner area with the live screen."""
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("0b0c0f"))
    c.frame(0, 0, c.w - 1, c.h - 1, hexc("1a1c21"))
    c.rect(1, 1, c.w - 2, c.h - 2, hexc("040506"))
    c.set(c.w // 2, c.h - 1, hexc("38c6ff"))


def monitor_back(base):
    def p(c):
        paint(base, 0.03)(c)
        for y in range(2, max(3, c.h // 3), 2):
            c.hline(c.w // 4, c.w - c.w // 4, y, shade(base, 0.75))
    return p


def vesa(c):
    paint(hexc("2b2f36"), 0.03)(c)
    for (x, y) in ((1, 1), (c.w - 2, 1), (1, c.h - 2), (c.w - 2, c.h - 2)):
        c.set(x, y, hexc("0e0f12"))


# ============================================================================================ models
def retro_pc(on):
    m = Model("retro_pc" + ("_on" if on else ""), density=2)
    body = paint(BEIGE, 0.025)
    side = retro_side
    m.box([4.5, 0.5, 1.6], [11.5, 15, 15], north=body, south=retro_back, east=side, west=side, up=vents_top(BEIGE, 3), down=flat(hexc("8a8577")))
    m.box([4, 0.5, 1], [12, 15.5, 1.6], north=retro_bezel(on), east=paint(BEIGE_D), west=paint(BEIGE_D), up=paint(BEIGE_D), down=paint(BEIGE_D))
    m.box([4.6, 12.4, 0.75], [11.4, 14.1, 1], north=cdrom, east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    m.box([4.6, 10.6, 0.85], [11.4, 12.2, 1], north=bay_cover, east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    m.box([6, 8.9, 0.8], [10, 10.1, 1], north=floppy(on), east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    # power button + feet
    m.box([9.6, 2.4, 0.6], [11.2, 4, 1], north=paint(hexc("cbc2aa"), 0.01, hi=1.2, lo=0.6), east=flat(BEIGE_D), west=flat(BEIGE_D),
          up=flat(BEIGE), down=flat(BEIGE_D))
    for x in (4.6, 10.4):
        for z in (2, 13.6):
            m.box([x, 0, z], [x + 1, 0.5, z + 1], all=flat(hexc("2a2a2a")))
    return m


def crt_monitor():
    m = Model("crt_monitor", density=2)
    bz = paint(BEIGE, 0.025, hi=1.12, lo=0.78)
    # bezel frame around the tube (screen opening x 2.5..13.5, y 2.5..11)
    m.box([0.5, 11, 1.5], [15.5, 13.5, 2], north=bz, up=bz, east=bz, west=bz, down=flat(BEIGE_D))
    m.box([0.5, 0.5, 1.5], [15.5, 2.5, 2], north=crt_chin, east=bz, west=bz, down=bz, up=flat(BEIGE_D))
    m.box([13.5, 2.5, 1.5], [15.5, 11, 2], north=bz, east=bz, west=flat(BEIGE_D))
    m.box([0.5, 2.5, 1.5], [2.5, 11, 2], north=bz, west=bz, east=flat(BEIGE_D))
    # tube face + housing
    m.box([1, 0.5, 2], [15, 13.3, 9], north=crt_glass, east=paint(BEIGE), west=paint(BEIGE), up=vents_top(BEIGE, 3), down=flat(BEIGE_D),
          south=paint(BEIGE_D))
    m.box([2.5, 1.5, 9], [13.5, 11.8, 12], all=crt_back, north=None)
    m.box([4, 3, 12], [12, 10, 14.5], all=crt_back)
    m.box([3, 0, 3], [13, 0.5, 11], all=paint(hexc("b9b09a")))
    return m


def flat_monitor():
    m = Model("monitor", density=2)
    shell = hexc("1e2126")
    m.box([0, 3, 7], [16, 12.5, 7.75], north=bezel_front, south=monitor_back(shell), east=flat(shell), west=flat(shell),
          up=flat(hexc("2a2e35")), down=flat(hexc("15171a")))
    m.box([2, 4, 7.75], [14, 11.5, 8.75], all=monitor_back(hexc("23262c")), north=None)
    m.box([6.5, 6, 8.75], [9.5, 9, 9.25], all=vesa)
    m.box([7, 0.75, 9.25], [9, 8.5, 10.25], all=brushed(hexc("8e959f"), vertical=True))
    m.box([4.5, 0, 6.5], [11.5, 0.75, 12], all=brushed(hexc("9aa1ab")))
    m.box([7.5, 2.5, 9.2], [8.5, 3.5, 9.3], north=flat(hexc("2a2e35")))
    return m


def ultrawide_stand():
    m = Model("curved_monitor", density=2, particle=None)
    m.box([4, 0, 7], [12, 0.6, 12.5], all=brushed(hexc("2f333b")))
    m.box([3, 0, 10.5], [5, 0.6, 12.5], all=brushed(hexc("2f333b")))
    m.box([11, 0, 10.5], [13, 0.6, 12.5], all=brushed(hexc("2f333b")))
    m.box([7, 0.6, 9.2], [9, 9, 10.6], all=brushed(hexc("383d46"), vertical=True))
    m.box([7.4, 3, 9.0], [8.6, 4.2, 9.2], north=flat(hexc("1b1d22")))
    return m


def arm_stand(name, span):
    m = Model(name, density=2)
    metal = brushed(hexc("30343c"))
    m.box([6, 0, 10], [10, 1, 14], all=metal)
    m.box([7, 1, 11], [9, 12.5, 13], all=brushed(hexc("3a3f48"), vertical=True))
    m.box([6.5, 12.5, 10.5], [9.5, 13.2, 13.5], all=metal)
    m.box([span[0], 9.5, 10.6], [span[1], 10.5, 11.6], all=metal)
    m.box([7.2, 3, 10.9], [8.8, 3.6, 11], north=flat(hexc("38c6ff")))
    return m


def video_wall():
    m = Model("video_wall_panel", density=2)
    def front(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("050607"))
        c.frame(0, 0, c.w - 1, c.h - 1, hexc("15171b"))
    m.box([0, 0, 13], [16, 16, 14], north=front, east=flat(hexc("1b1d22")), west=flat(hexc("1b1d22")), up=flat(hexc("1b1d22")),
          down=flat(hexc("1b1d22")))
    m.box([1, 1, 14], [15, 15, 15.5], all=monitor_back(hexc("26292f")), north=None)
    m.box([3, 0.5, 15.5], [4, 15.5, 16], all=flat(hexc("4a505a")))
    m.box([12, 0.5, 15.5], [13, 15.5, 16], all=flat(hexc("4a505a")))
    return m


# ---------------------------------------------------------------------------------------- gaming tower
def tower_side_panel(c):
    paint(hexc("1c1f24"), 0.03)(c)
    for y in range(3, c.h - 3, 3):
        c.hline(c.w - 7, c.w - 3, y, hexc("0f1114"))


def tower_top(c):
    paint(hexc("1a1c21"), 0.02)(c)
    for y in range(2, c.h - 2):
        for x in range(2, c.w - 2):
            if (x + y) % 2 == 0:
                c.set(x, y, hexc("0d0e11"))


def plinth(c):
    paint(hexc("17191d"), 0.02)(c)
    for y in range(1, c.h - 1, 1):
        for x in range(1, c.w - 1, 2):
            c.set(x + (y % 2), y, hexc("0c0d10"))


def tower_back(c):
    paint(hexc("1c1f24"), 0.02)(c)
    w, h = c.w, c.h
    # rear fan cut-out (upper), I/O shield, PCIe slot covers, PSU
    c.ring(w * 0.62, h * 0.28, 3.2, 5.6, hexc("101216"))
    c.rect(2, 4, 5, 13, hexc("8b9099"))
    for i in range(7):
        c.rect(2 + i * 2, h - 12, 2 + i * 2, h - 6, hexc("50555e"))
    c.rect(2, h - 4, w - 3, h - 2, hexc("101216"))


def mobo(c):
    """Motherboard seen from the glass side (east face): PCB, VRM heatsinks, socket, DIMM slots, PCIe."""
    w, h = c.w, c.h
    c.rect(0, 0, w - 1, h - 1, hexc("22282f"))
    for _ in range(40):
        x, y = RNG.randrange(w), RNG.randrange(h)
        c.hline(x, min(w - 1, x + RNG.randrange(2, 6)), y, hexc("2d353e"))
    # VRM heatsinks (top and rear edge) - rear is toward +z which is the viewer's... east face: left = south
    c.rect(1, 1, w - 2, 3, hexc("3a3f48"))
    c.hline(1, w - 2, 1, hexc("6b727d"))
    c.rect(1, 1, 3, h // 2, hexc("3a3f48"))
    # CPU socket
    sx, sy = w // 2 - 4, 5
    c.rect(sx, sy, sx + 6, sy + 6, hexc("2a2e35"))
    c.frame(sx, sy, sx + 6, sy + 6, hexc("50555e"))
    # DIMM slots toward the front (east face: front = right side)
    for i in range(4):
        x = w - 9 + i * 2
        c.rect(x, 4, x, 13, hexc("0e0f12"))
    # chipset heatsink with RGB accent + M.2 cover
    c.rect(w - 9, h - 8, w - 3, h - 3, hexc("343942"))
    c.hline(w - 9, w - 3, h - 8, hexc("6b727d"))
    c.rect(4, h // 2 + 2, w - 12, h // 2 + 3, hexc("343942"))
    # PCIe slots
    for k in range(2):
        y = h // 2 + 6 + k * 5
        c.rect(3, y, w // 2 + 3, y, hexc("0b0c0e"))
        c.set(w // 2 + 3, y, hexc("8b9099"))


def shroud(c):
    paint(hexc("23262c"), 0.02)(c)
    w, h = c.w, c.h
    for y in range(2, h - 2, 2):
        for x in range(3 + (y % 4) // 2, w // 3, 2):
            c.set(x, y, hexc("0a0b0d"))
    c.hline(w // 2, w - 3, h // 2, hexc("2a2e35"))
    if w > 20:
        c.text(w - 14, 1, "ORBIT", hexc("2b2f36"))


def tower(on):
    m = Model("gaming_tower" + ("_on" if on else ""), density=2, render_type="minecraft:translucent")
    fan = Ref("fan", (0, 0, 16, 16))
    m.texture("fan", fx("fan_rgb" if on else "fan_off"))
    m.texture("rgb", fx("rgb_strip" if on else "rgb_off"))
    case = paint(CASE_BLACK, 0.02)
    # chassis: plinth, top, rear, solid right side (west), interior back wall
    m.box([3.5, 0.5, 0.5], [12.5, 1.5, 15.5], north=plinth, east=plinth, west=case, south=case, up=flat(hexc("101216")), down=case)
    for x in (3.7, 11.3):
        for z in (0.8, 14.2):
            m.box([x, 0, z], [x + 1, 0.5, z + 1], all=flat(hexc("0c0d0f")))
    m.box([3.5, 15, 0.5], [12.5, 16, 15.5], up=tower_top, north=case, east=case, west=case, south=case, down=flat(hexc("101216")))
    m.box([3.5, 1.5, 14.5], [12.5, 15, 15.5], south=tower_back, east=case, west=case, north=flat(hexc("14161a")))
    m.box([3.5, 1.5, 0.5], [4.1, 15, 14.5], west=tower_side_panel, north=case, east=paint(hexc("1f232a"), 0.02))
    # RGB edge strip along the front-top
    m.box([4.1, 14.6, 0.55], [12.4, 15, 0.9], north=Ref("rgb", (0, 0, 16, 4)), down=Ref("rgb", (0, 4, 16, 6)), shade=False)
    # interior: motherboard, PSU shroud, vertical intake fans, top radiator fans
    m.box([4.1, 4.2, 3.5], [4.4, 14.4, 13.8], east=mobo, north=flat(PCB_DARK), up=flat(PCB_DARK))
    m.box([4.1, 1.5, 0.7], [12.3, 4.0, 14.5], east=shroud, north=shroud, up=paint(hexc("1d2025"), 0.02), west=None)
    for i in range(3):
        y0 = 4.4 + i * 3.4
        m.box([4.1, y0, 0.8], [5.2, y0 + 3.3, 4.1], east=fan, north=flat(hexc("15171b")), up=flat(hexc("15171b")), down=flat(hexc("15171b")),
              shade=False)
    for i in range(3):
        z0 = 4.4 + i * 3.3
        m.box([5.4, 14.2, z0], [12.2, 15, z0 + 3.2], down=fan, east=flat(hexc("15171b")), north=flat(hexc("15171b")), shade=False)
    m.box([5.4, 13.9, 4.3], [12.2, 14.2, 14.3], east=Ref("rgb", (0, 8, 16, 9)), down=flat(hexc("101216")), shade=False)
    # glass: front + side (panoramic, no corner pillar)
    m.box([4.1, 1.5, 0.5], [12.5, 15, 0.6], north=glass(), south=glass())
    m.box([12.4, 1.5, 0.6], [12.5, 15, 14.5], east=glass(), west=glass())
    return m


def tower_parts(models):
    """Standalone models the tower renderer places for installed parts (same block coordinates)."""
    # graphics cards
    for tier, accent, fans in (("gt", hexc("3b82f6"), 2), ("gx", hexc("22c55e"), 2), ("titan", hexc("f59e0b"), 3)):
        m = Model("part/gpu_" + tier, density=2)
        m.texture("fan", fx("fan_rgb" if tier == "titan" else "fan_off"))
        m.texture("rgb", fx("rgb_strip"))
        def side(c, accent=accent, tier=tier):
            paint(hexc("24272d"), 0.02)(c)
            w, h = c.w, c.h
            c.hline(1, w - 2, h - 2, accent)
            for x in range(2, w - 2, 5):
                c.line(x, 1, x + 2, h - 3, hexc("30343c"))
            if tier == "titan":
                c.text(w // 2 - 7, 1, "TITAN", hexc("f2c46b"))
        def backplate(c):
            paint(hexc("2a2e35"), 0.02)(c)
            c.hline(0, c.w - 1, 0, hexc("6b727d"))
            c.hline(0, c.w - 1, c.h - 1, hexc("6b727d"))
        m.box([4.4, 5.0, 2.0], [9.6, 6.9, 13.6], east=side, north=paint(hexc("24272d")), up=backplate,
              down=flat(hexc("15171b")), south=flat(hexc("15171b")))
        span = 11.6 / fans
        for i in range(fans):
            z0 = 2.0 + i * span + 0.15
            m.box([4.7, 4.8, z0], [9.3, 5.0, z0 + span - 0.3], down=Ref("fan", (0, 0, 16, 16)), east=flat(hexc("15171b")), shade=False)
        if tier != "gt":
            m.box([9.6, 6.2, 2.4], [9.65, 6.6, 13.2], east=Ref("rgb", (0, 0, 16, 2)), shade=False)
        models.append(m)
    # RAM stick (RGB top), placed per slot by translating along z
    m = Model("part/ram", density=4)
    m.texture("rgb", fx("rgb_strip"))
    def spreader(c):
        paint(hexc("2b2f36"), 0.02)(c)
        c.hline(0, c.w - 1, 0, hexc("4a515c"))
    m.box([4.4, 9.2, 0], [5.8, 12.4, 0.35], east=spreader, west=spreader, north=flat(hexc("2b2f36")), south=flat(hexc("2b2f36")))
    m.box([4.4, 12.4, 0], [5.8, 12.9, 0.35], all=Ref("rgb", (0, 0, 16, 16)), shade=False)
    models.append(m)
    # AIO pump head + tubes
    m = Model("part/cooler", density=4)
    m.texture("lcd", fx("aio_lcd"))
    m.box([4.4, 9.6, 7.6], [5.6, 12.6, 10.6], east=Ref("lcd", (0, 0, 16, 16)), north=flat(hexc("1b1d22")), south=flat(hexc("1b1d22")),
          up=flat(hexc("2a2e35")), down=flat(hexc("1b1d22")))
    tube = paint(hexc("1e2126"), 0.04)
    m.box([5.0, 12.6, 8.2], [5.5, 14.0, 8.7], all=tube)
    m.box([5.0, 12.6, 9.5], [5.5, 14.0, 10.0], all=tube)
    models.append(m)
    # PCIe add-in card
    m = Model("part/card", density=4)
    def card_side(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("1d5a39"))
        c.rect(2, 1, c.w // 2, c.h - 2, hexc("2a2e35"))
        c.rect(c.w // 2 + 2, 1, c.w // 2 + 4, c.h - 2, hexc("b3261e"))
    m.box([4.4, 7.5, 7.0], [8.5, 8.3, 13.6], east=card_side, west=card_side, up=flat(hexc("1d5a39")), north=flat(hexc("1d5a39")))
    models.append(m)
    # 2.5" drive on the shroud
    m = Model("part/drive", density=4)
    m.box([6.5, 4.0, 10.0], [10.5, 4.4, 13.8], up=paint(hexc("23262c"), 0.02), east=flat(hexc("38c6ff")), north=flat(hexc("1b1d22")))
    models.append(m)


# ---------------------------------------------------------------------------------------- server rack
def rack_post(c):
    paint(hexc("15171b"), 0.02)(c)
    for y in range(1, c.h, 3):
        c.set(c.w // 2, y, hexc("050607"))


def rack_side(c):
    paint(hexc("1a1c21"), 0.02)(c)
    for y in range(2, c.h - 2, 2):
        for x in range(2 + (y // 2) % 2, c.w - 2, 2):
            c.set(x, y, hexc("0e0f12"))


def server_rack(on):
    m = Model("server_rack" + ("_on" if on else ""), density=2)
    for s in range(2):
        m.texture(f"front{s}", fx(f"server_front_on_{s}" if on else "server_front_off"))
    post = rack_post
    for x in (0, 15):
        for z in (0, 15):
            m.box([x, 0, z], [x + 1, 16, z + 1], all=post, up=None, down=None)
    m.box([0, 0, 1], [0.5, 16, 15], west=rack_side, east=flat(hexc("101215")))
    m.box([15.5, 0, 1], [16, 16, 15], east=rack_side, west=flat(hexc("101215")))
    m.box([1, 0, 15.5], [15, 16, 16], south=mesh(hexc("1a1c21"), hexc("08090b")), north=flat(hexc("0d0e10")))
    # mounting rails with U holes
    for x in (1, 14.4):
        m.box([x, 0, 1], [x + 0.6, 16, 1.6], north=rack_post, east=flat(hexc("15171b")), west=flat(hexc("15171b")))
    # two 2U servers
    for s, (y0, y1) in enumerate(((0.4, 7.75), (8.25, 15.6))):
        top = brushed(hexc("5a616c"))
        m.box([1.2, y0, 1.2], [14.8, y1, 15.2], north=Ref(f"front{s}", (0, 0, 16, 9)), up=top, down=flat(hexc("2a2e35")),
              east=paint(hexc("4a505a")), west=paint(hexc("4a505a")))
    return m


# ---------------------------------------------------------------------------------------- laptops
def laptop_keys(rugged):
    def p(c):
        base = hexc("2b2f25") if rugged else hexc("c9ced6")
        paint(base, 0.015, hi=1.06, lo=0.9)(c)
        w, h = c.w, c.h
        # keyboard well (top ~60%) as the user sees it; rotated 180 by the caller
        kx0, kx1, ky0, ky1 = 3, w - 4, 2, int(h * 0.58)
        c.rect(kx0 - 1, ky0 - 1, kx1 + 1, ky1 + 1, shade(base, 0.82))
        rows = 6
        rh = max(2, (ky1 - ky0 + 1) // rows)
        for r in range(rows):
            y = ky0 + r * rh
            n = 14 if r < 5 else 9
            kw = (kx1 - kx0 + 1) / n
            for k in range(n):
                xa = kx0 + int(k * kw)
                xb = kx0 + int((k + 1) * kw) - 2
                if r == 5 and k == 3:
                    xb = kx0 + int((k + 4) * kw) - 2
                if r == 5 and 3 < k < 7:
                    continue
                c.rect(xa, y, xb, y + rh - 2, hexc("1b1d22") if not rugged else hexc("16180f"))
                c.hline(xa, xb, y, hexc("2a2e35") if not rugged else hexc("23261a"))
        # trackpad
        tw = w // 3
        c.rect(w // 2 - tw // 2, ky1 + 4, w // 2 + tw // 2, h - 3, shade(base, 0.92 if not rugged else 0.75))
        c.frame(w // 2 - tw // 2, ky1 + 4, w // 2 + tw // 2, h - 3, shade(base, 0.8))
        # speaker grilles
        for y in range(ky0, ky1, 2):
            c.set(1, y, shade(base, 0.7))
            c.set(w - 2, y, shade(base, 0.7))
        c.img = c.img.rotate(180)
        c.px = c.img.load()
    return p


def lid_back(rugged):
    def p(c):
        base = hexc("2e3227") if rugged else ALU
        brushed(base)(c) if not rugged else paint(base, 0.03)(c)
        cx, cy = c.w / 2, c.h / 2
        if rugged:
            c.rect(int(cx) - 6, int(cy) - 2, int(cx) + 5, int(cy) + 2, hexc("1c1f17"))
            c.text(int(cx) - 5, int(cy) - 2, "ORB", hexc("c9b45a"))
        else:
            c.ring(cx, cy, 2.6, 4.0, hexc("e6f6ff"))
            c.disc(cx, cy, 1.2, hexc("e6f6ff"))
    return p


def lid_front(c):
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("0b0c0f"))
    c.frame(0, 0, c.w - 1, c.h - 1, hexc("2a2e35"))
    c.set(c.w // 2, 1, hexc("1f3a4d"))


def laptop(name, rugged):
    m = Model(name, density=4)
    lid_rot = {"origin": [8, 1.0, 12.4], "axis": "x", "angle": 22.5}
    if rugged:
        body = paint(hexc("2b2f25"), 0.03)
        m.box([1.5, 0, 2.5], [14.5, 1.0, 13], up=laptop_keys(True), north=paint(hexc("23261e")), east=body, west=body, south=body,
              down=flat(hexc("1a1c16")))
        bump = paint(hexc("15171a"), 0.04)
        for x in (1.2, 13.6):
            for z in (2.2, 11.9):
                m.box([x, 0, z], [x + 1.2, 1.4, z + 1.2], all=bump)
        # carry handle
        m.box([5, 0.3, 1.2], [5.6, 1.0, 2.5], all=bump)
        m.box([10.4, 0.3, 1.2], [11, 1.0, 2.5], all=bump)
        m.box([5, 0.3, 1.0], [11, 1.0, 1.6], all=bump)
        m.box([2, 1.0, 11.9], [14, 10.0, 12.9], north=lid_front, south=lid_back(True), east=bump, west=bump, up=bump, rotation=lid_rot)
        m.box([1.6, 9.2, 11.7], [3, 10.4, 13.1], all=bump, rotation=lid_rot)
        m.box([13, 9.2, 11.7], [14.4, 10.4, 13.1], all=bump, rotation=lid_rot)
        m.box([7, 9.6, 11.6], [9, 10.3, 13.0], all=paint(hexc("c9b45a")), rotation=lid_rot)
    else:
        body = brushed(ALU)
        m.box([2, 0.2, 3], [14, 1.0, 12.6], up=laptop_keys(False), north=body, east=body, west=body, south=body, down=flat(hexc("9aa1ab")))
        for x in (2.5, 12.8):
            for z in (3.4, 11.6):
                m.box([x, 0, z], [x + 0.7, 0.2, z + 0.7], all=flat(hexc("2a2e35")))
        m.box([2, 1.0, 11.9], [14, 9.8, 12.35], north=lid_front, south=lid_back(False), east=body, west=body, up=body, rotation=lid_rot)
        m.box([3, 0.7, 11.8], [13, 1.4, 12.5], all=flat(hexc("2a2e35")))
    return m


# ---------------------------------------------------------------------------------------- peripherals
def keyboard():
    m = Model("mechanical_keyboard", density=4)
    m.texture("keys", fx("keyboard_keys"))
    case = brushed(hexc("23262c"))
    m.box([0.6, 0, 4.2], [15.4, 0.8, 10.2], north=case, south=case, east=case, west=case, up=paint(hexc("1a1c21"), 0.02),
          down=flat(hexc("101215")))
    m.box([0.9, 0.8, 4.6], [15.1, 1.35, 9.6], up=Ref("keys", (16, 6, 0, 0)), north=paint(hexc("202329"), 0.02),
          east=flat(hexc("202329")), west=flat(hexc("202329")), south=flat(hexc("202329")))
    m.box([1.2, 0.8, 9.65], [2.4, 1.5, 10.05], all=brushed(hexc("6b727d")))
    m.box([0.6, 0, 10.2], [15.4, 0.35, 10.5], north=Ref("rgb", (0, 0, 16, 2)), east=Ref("rgb", (0, 0, 16, 2)),
          west=Ref("rgb", (0, 0, 16, 2)), shade=False)
    m.texture("rgb", fx("rgb_strip"))
    return m


def mouse():
    m = Model("gaming_mouse", density=4)
    m.texture("rgb", fx("rgb_strip"))
    shell = paint(hexc("1c1e23"), 0.03, hi=1.25)
    m.box([6.6, 0, 5.2], [9.4, 0.6, 10.8], all=shell)
    m.box([6.4, 0.6, 5.6], [9.6, 1.3, 10.4], all=shell)
    m.box([6.8, 1.3, 6.6], [9.2, 1.75, 10.0], all=paint(hexc("23262c"), 0.03, hi=1.3))
    m.box([7.75, 1.3, 9.0], [8.25, 1.95, 10.0], all=flat(hexc("3a3f48")))
    m.box([7.95, 1.3, 7.4], [8.05, 1.76, 8.95], up=flat(hexc("0b0c0f")))
    m.box([6.35, 0.7, 5.8], [6.45, 1.1, 7.2], west=Ref("rgb", (0, 0, 16, 4)), shade=False)
    m.box([9.55, 0.7, 5.8], [9.65, 1.1, 7.2], east=Ref("rgb", (0, 0, 16, 4)), shade=False)
    m.box([7.3, 0.95, 5.3], [8.7, 1.2, 5.65], north=Ref("rgb", (0, 0, 16, 4)), shade=False)
    m.box([9.6, 0.6, 7.0], [9.75, 1.0, 8.5], all=flat(hexc("2a2e35")))
    return m


def speaker_front(c):
    w, h = c.w, c.h
    c.rect(0, 0, w - 1, h - 1, hexc("141518"))
    c.frame(0, 0, w - 1, h - 1, hexc("22252a"))
    # tweeter
    tx, ty = w / 2, h * 0.2
    c.disc(tx, ty, w * 0.2, hexc("0b0c0e"))
    c.ring(tx, ty, w * 0.13, w * 0.2, hexc("9aa1ab"))
    c.disc(tx, ty, w * 0.1, hexc("3a3f48"))
    c.set(int(tx - 1), int(ty - 1), hexc("8a929d"))
    # woofer
    wx, wy = w / 2, h * 0.56
    R = w * 0.42
    c.disc(wx, wy, R, hexc("0b0c0e"))
    c.ring(wx, wy, R * 0.82, R, hexc("2e3238"))
    for k in range(int(R * 0.82)):
        f = k / (R * 0.82)
        c.ring(wx, wy, k, k + 1, mix(hexc("1c1e22"), hexc("34383f"), f * (1 - f) * 3))
    c.disc(wx, wy, R * 0.25, hexc("26292f"))
    c.set(int(wx - 2), int(wy - 2), hexc("50555e"))
    # bass port + power LED
    c.rect(int(w * 0.3), h - 4, int(w * 0.7), h - 3, hexc("050506"))
    c.set(w - 3, h - 3, LED_B)


def wood(c):
    for y in range(c.h):
        for x in range(c.w):
            g = math.sin(y * 0.9 + math.sin(x * 0.15) * 2.0) * 0.06
            c.set(x, y, shade(WOOD, 1 + g + RNG.uniform(-0.03, 0.03)))


def speakers():
    m = Model("desktop_speakers", density=4)
    for x0 in (0.5, 11.5):
        m.box([x0, 0, 5], [x0 + 4, 8, 10.5], north=speaker_front, east=wood, west=wood, up=wood, south=paint(hexc("141518")),
              down=flat(hexc("0e0f11")))
    return m


def crate():
    m = Model("delivery_crate", density=2)
    panel = paint(hexc("aeb5be"), 0.04)
    def side(c):
        panel(c)
        w, h = c.w, c.h
        for x in range(0, w, 4):
            c.rect(x, 1, x + 1, 2, hexc("ff8a1f"))
            c.rect(x + 2, 1, x + 3, 2, hexc("1b1d22"))
        c.text(w // 2 - 9, h // 2 - 2, "ORBIT", hexc("2b2f36"))
        c.frame(0, 0, w - 1, h - 1, hexc("5b636e"))
    m.box([1.5, 0.5, 1.5], [14.5, 13.5, 14.5], north=side, south=side, east=side, west=side, up=panel, down=panel)
    edge = paint(hexc("ff7a1a"), 0.03)
    for (x, z) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        m.box([x, 0, z], [x + 1, 14, z + 1], all=edge)
    for y in (0, 13.5):
        m.box([1, y, 1], [15, y + 0.5, 2], all=paint(hexc("4a505a")))
        m.box([1, y, 14], [15, y + 0.5, 15], all=paint(hexc("4a505a")))
        m.box([1, y, 2], [2, y + 0.5, 14], all=paint(hexc("4a505a")))
        m.box([14, y, 2], [15, y + 0.5, 14], all=paint(hexc("4a505a")))
    m.box([6, 13.5, 6], [10, 14.2, 10], all=paint(hexc("38c6ff")))
    return m


def pod():
    m = Model("supply_pod", density=2)
    hull = paint(hexc("e4e7eb"), 0.03)
    m.box([3, 2, 3], [13, 12, 13], all=hull)
    m.box([4, 12, 4], [12, 14, 12], all=hull)
    m.box([5.5, 14, 5.5], [10.5, 15.5, 10.5], all=paint(hexc("ff7a1a")))
    m.box([3.2, 6, 2.8], [12.8, 7.5, 3], north=flat(hexc("ff7a1a")))
    m.box([5, 8.5, 2.7], [11, 11, 3], north=paint(hexc("0b2033")))
    m.box([4, 0, 4], [12, 2, 12], all=paint(hexc("2b2f36")))
    for (x0, z0, x1, z1) in ((7.5, 0, 8.5, 3), (7.5, 13, 8.5, 16), (0, 7.5, 3, 8.5), (13, 7.5, 16, 8.5)):
        m.box([x0, 0, z0], [x1, 7, z1], all=paint(hexc("3a3f48")))
    return m


# ============================================================================================ entry
def build(assets):
    tex_dir = os.path.join(assets, "textures", "block")
    model_tex = os.path.join(tex_dir, "model")
    model_dir = os.path.join(assets, "models", "block")
    shared_textures(tex_dir)
    models = [
        retro_pc(False), retro_pc(True), crt_monitor(), flat_monitor(), ultrawide_stand(),
        arm_stand("dual_monitor_arm", (0, 16)), arm_stand("triple_monitor_arm", (-6, 22)), video_wall(),
        tower(False), tower(True), server_rack(False), server_rack(True),
        laptop("pro_laptop", False), laptop("rugged_laptop", True), keyboard(), mouse(), speakers(), crate(), pod(),
    ]
    tower_parts(models)
    for m in models:
        if "/" in m.name:
            sub, base = m.name.split("/")
            m.name = base
            m.bake(MOD, os.path.join(model_tex, sub), os.path.join(model_dir, sub), atlas_name=base)
            # fix texture path for the sub-folder
            p = os.path.join(model_dir, sub, base + ".json")
            with open(p, encoding="utf-8") as f:
                j = json.load(f)
            if "atlas" in j["textures"]:
                j["textures"]["atlas"] = f"{MOD}:block/model/{sub}/{base}"
                if j["textures"].get("particle", "").endswith("/" + base) or j["textures"]["particle"] == f"{MOD}:block/model/{base}":
                    j["textures"]["particle"] = j["textures"]["atlas"]
            with open(p, "w", encoding="utf-8") as f:
                json.dump(j, f, indent=1)
                f.write("\n")
        else:
            m.bake(MOD, model_tex, model_dir)
    return models
