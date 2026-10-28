"""The detailed block models in HyperVisor X, based on real hardware.

What I looked at for proportions and details (photos only, nothing copied): NZXT H9 / S5
panoramic-glass cases (glass front + side, vertical side fans, top radiator, PSU shroud), beige AT
mini-towers and 14" CRTs from the early 90s, 2U rack servers with 2.5" hot-swap caddies, 1U switches
and patch panels, Samsung/LG 49" ultrawides, Panasonic Toughbook rugged laptops, full-size RGB
mechanical keyboards and bookshelf speakers.

Every model faces north (front at z = 0), x grows toward the viewer's left. Same texel density
everywhere: 2 texels per model unit (32 per block). Shared strip textures are 32 px wide, so a Ref
UV written in model units matches that density.
"""
import json
import math
import os
import random

from PIL import Image

from model_kit import Model, Ref, display
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
            off = (step // 2 if step > 2 else 1) if (hex_offset and j % 2) else 0
            for x in range(border + off, c.w - border, step):
                c.set(x, y, hole)
    return p


def glass(tint=(176, 206, 232, 34), frame=(214, 228, 242, 110), pitch=9):
    """Tempered glass: tint, a light frame and soft diagonal reflection streaks."""
    def p(c):
        c.rect(0, 0, c.w - 1, c.h - 1, tint)
        for k in range(0, c.w + c.h, pitch):
            for t, a in enumerate((30, 16, 8)):
                x, y = k + t, 0
                while x >= 0 and y < c.h:
                    if x < c.w:
                        c.set(x, y, mix(tint, (255, 255, 255, 255), a / 255.0)[:3] + (tint[3] + a,))
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
CASE_BLACK = hexc("1f2228")
WALL = hexc("2b3038")
PCB = hexc("26303b")
HEAT = hexc("8e96a3")
HEAT_D = hexc("5c636e")
ALU = hexc("c8cdd5")
LED_G = hexc("4dff88")
LED_A = hexc("ffb02e")
LED_B = hexc("38c6ff")
WOOD = hexc("6b4429")


# ============================================================================================ animated & shared textures
FAN_FRAMES = 48


def fan_frame(i, on):
    """7-blade fan, 8x8: blades advance 360/7/6 degrees per frame (loop every 6), hue every 48."""
    c = Canvas(8)
    c.rect(0, 0, 7, 7, hexc("15171b"))
    cx = cy = 4.0
    hue = i / FAN_FRAMES
    if on:
        c.ring(cx, cy, 2.9, 4.2, lambda x, y, d: hsv(math.atan2(y + 0.5 - cy, x + 0.5 - cx) / (2 * math.pi) + hue, 0.6, 1.0))
    else:
        c.ring(cx, cy, 2.9, 4.2, hexc("2c3038"))
    for (x, y) in ((0, 0), (7, 0), (0, 7), (7, 7)):
        c.set(x, y, hexc("0d0e11"))
    c.disc(cx, cy, 2.9, hexc("0d0e11"))
    rot = i * (2 * math.pi / 7) / 6
    blade = hexc("454b55") if on else hexc("2f343c")
    for k in range(7):
        a = rot + k * 2 * math.pi / 7
        for r in (1.5, 2.4):
            aa = a + r * 0.3
            c.set(int(cx + math.cos(aa) * r), int(cy + math.sin(aa) * r), blade)
    c.disc(cx, cy, 1.0, hexc("4a505a") if on else hexc("2a2e35"))
    return c.img


def rgb_strip_frame(phase, on):
    """32 px wide so a strip face's UV in model units gives 2 px/unit. Hue runs along u."""
    c = Canvas(32)
    for x in range(32):
        col = hsv(x / 64.0 + phase, 0.75, 1.0) if on else hexc("2e3138")
        c.vline(x, 0, 31, col)
    return c.img


def solid_strip(color):
    c = Canvas(32)
    c.rect(0, 0, 31, 31, color)
    return c.img


def keyboard_frame(phase, on):
    """Full-size layout in the top-left 28x10 texels: 1-px key rows with 1-px gaps (typist's view)."""
    c = Canvas(32)
    gap = hexc("0d0e11")
    cap = hexc("202329")
    c.rect(0, 0, 31, 31, gap)
    # cap segments per row (x ranges): main block, nav cluster, numpad
    rows = [
        [(0, 17), (19, 21), (23, 27)],
        [(0, 17), (19, 21), (23, 27)],
        [(0, 17), (23, 27)],
        [(0, 17), (20, 20), (23, 27)],
        [(0, 3), (4, 12), (13, 17), (19, 21), (23, 25), (26, 27)],
    ]
    stagger = [0, 1, 1, 0, 0]
    for r, segs in enumerate(rows):
        y = 1 + r * 2
        for (x0, x1) in segs:
            for x in range(x0, x1 + 1):
                if r < 4 and x1 - x0 > 3 and (x - x0 + stagger[r]) % 2 == 1:
                    continue  # key separator
                light = hsv(phase + x / 40.0 + r * 0.03, 0.6, 1.0)
                c.set(x, y, mix(cap, light, 0.3) if on else cap)
                if on:
                    # under-row light bleeding from the switches
                    c.set(x, y + 1, mix(gap, light, 0.38))
    return c.img


def aio_lcd():
    """Pump LCD: 6x6 texels used (UV 0..12)."""
    c = Canvas(8)
    c.rect(0, 0, 7, 7, hexc("06070a"))
    c.disc(3, 3, 3.0, hexc("0b0d12"))
    for k in range(12):
        a = -math.pi * 1.25 + k / 11 * math.pi * 1.5
        c.set(int(3 + math.cos(a) * 2.6), int(3 + math.sin(a) * 2.6), hsv(0.55 + k / 50.0, 0.7, 1.0))
    c.rect(2, 2, 3, 3, hexc("f2f5f9"))
    return c.img


# rack front: gear rows inside one 32x32 frame (rows in texels; model units = rows / 2)
RACK_ROWS = {"storage": (0, 9), "server_a": (9, 15), "server_b": (15, 21), "switch": (21, 24), "patch": (24, 27), "blank": (27, 31)}
RACK_FRAMES = (7, 9, 11, 13)


def rack_front_frame(frame, on, seed):
    rnd = random.Random(seed * 7919 + frame * 104729)
    c = Canvas(32)
    c.rect(0, 0, 31, 31, hexc("101215"))
    W = 27
    # 3U storage: 12 vertical 3.5" bays with activity LEDs
    y0, y1 = RACK_ROWS["storage"]
    c.rect(0, y0, W, y1 - 1, hexc("2a2e35"))
    c.hline(0, W, y0, hexc("4a505a"))
    for i in range(12):
        x = 1 + i * 2
        c.rect(x, y0 + 1, x, y1 - 2, hexc("191b1f"))
        c.set(x, y1 - 2, (LED_G if rnd.random() < 0.6 else hexc("1f6b3a")) if on else hexc("1f2a22"))
    c.vline(25, y0 + 1, y1 - 2, hexc("3b4049"))
    c.set(26, y0 + 2, LED_B if on else hexc("1e2a33"))
    # 2U servers: 8 hot-swap caddies, status LEDs, vents
    for key, salt in (("server_a", 1), ("server_b", 2)):
        y0, y1 = RACK_ROWS[key]
        c.rect(0, y0, W, y1 - 1, hexc("23262c") if salt == 1 else hexc("262a31"))
        c.hline(0, W, y0, hexc("4a505b"))
        for i in range(8):
            x = 2 + i * 2
            c.rect(x, y0 + 1, x, y1 - 2, hexc("17191d"))
            c.set(x, y0 + 1, LED_B if on else hexc("1e2a33"))
            if on and rnd.random() < 0.5:
                c.set(x, y0 + 3, LED_G)
        for x in range(19, 25, 2):
            c.vline(x, y0 + 1, y1 - 2, hexc("111316"))
        c.set(25, y0 + 2, LED_G if on else hexc("1f2a22"))
        c.set(26, y0 + 2, (LED_A if on and (frame + salt) % 4 == 0 else hexc("3a2f1a")))
    # 1U switch: 24 ports with link/activity LEDs above and below
    y0, _ = RACK_ROWS["switch"]
    c.rect(0, y0, W, y0 + 2, hexc("1d2025"))
    for i in range(12):
        x = 1 + i * 2
        c.set(x, y0 + 1, hexc("07080a"))
        for row in (y0, y0 + 2):
            lit = on and rnd.random() < 0.7
            c.set(x, row, (LED_G if rnd.random() < 0.8 else LED_A) if lit else hexc("16201a"))
    c.rect(25, y0, 26, y0 + 2, hexc("2f343c"))
    # 1U patch panel with coloured patch cables
    y0, _ = RACK_ROWS["patch"]
    c.rect(0, y0, W, y0 + 2, hexc("2b2f36"))
    cables = [hexc("3b82f6"), hexc("f59e0b"), hexc("22c55e"), hexc("ef4444"), hexc("e5e7eb")]
    prnd = random.Random(seed + 99)
    for i in range(12):
        x = 1 + i * 2
        c.set(x, y0 + 1, prnd.choice(cables) if prnd.random() < 0.75 else hexc("0b0c0e"))
    # blanking plate
    y0, y1 = RACK_ROWS["blank"]
    c.rect(0, y0, W, y1 - 1, hexc("1a1c21"))
    for y in range(y0 + 1, y1 - 1):
        for x in range(1 + (y % 2), W, 2):
            c.set(x, y, hexc("0d0e10"))
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
    for old in ("server_front_on_0", "server_front_on_1", "server_front_off", "tower_glass"):
        for ext in (".png", ".png.mcmeta"):
            if os.path.exists(os.path.join(d, old + ext)):
                os.remove(os.path.join(d, old + ext))
    write_anim(d, "fan_rgb", [fan_frame(i, True) for i in range(FAN_FRAMES)], 1)
    fan_frame(0, False).save(os.path.join(d, "fan_off.png"))
    write_anim(d, "rgb_strip", [rgb_strip_frame(i / 24.0, True) for i in range(24)], 2, True)
    rgb_strip_frame(0, False).save(os.path.join(d, "rgb_off.png"))
    solid_strip(hexc("f2c46b")).save(os.path.join(d, "rgb_gold.png"))
    for v, n in enumerate(RACK_FRAMES):
        write_anim(d, f"rack_front_{v}", [rack_front_frame(i, True, v) for i in range(n)], 4)
    rack_front_frame(0, False, 0).save(os.path.join(d, "rack_front_off.png"))
    write_anim(d, "keyboard_keys", [keyboard_frame(i / 20.0, True) for i in range(20)], 3, True)
    keyboard_frame(0, False).save(os.path.join(d, "keyboard_keys_off.png"))
    aio_lcd().save(os.path.join(d, "aio_lcd.png"))
    # tower glass, drawn by the block entity renderer at 2 px/unit (front 8.4 x 13.5, side 13.9 x 13.5 units)
    for name, w in (("tower_glass_front", 17), ("tower_glass_side", 28)):
        c = Canvas(w, 27)
        glass()(c)
        c.img.save(os.path.join(d, name + ".png"))


def fx(name):
    return f"{MOD}:block/fx/{name}"


# ============================================================================================ face painters (specific)
def retro_bezel(on):
    def p(c):
        paint(BEIGE, 0.025, hi=1.12, lo=0.8)(c)
        w, h = c.w, c.h
        # intake grille above the bottom edge
        c.hline(2, w - 3, h - 3, shade(BEIGE, 0.72))
        # brand badge under the floppy drive
        c.hline(2, 4, 14, hexc("3b82f6"))
        c.hline(5, 6, 14, hexc("ef4444"))
        # turbo display: 9x7 smoked window with a 3x5 "66"
        dx, dy = 2, 16
        c.rect(dx, dy, dx + 8, dy + 6, hexc("1a1010"))
        c.frame(dx, dy, dx + 8, dy + 6, shade(BEIGE, 0.62))
        c.text(dx + 1, dy + 1, "66", hexc("ff3b2f") if on else hexc("3a1714"))
        # power / HDD / turbo LEDs
        c.set(12, 17, LED_G if on else hexc("2f4a35"))
        c.set(12, 19, LED_A if on else hexc("4a3d24"))
        c.set(12, 21, hexc("ffd23f") if on else hexc("4a4424"))
        # turbo + reset buttons, key lock
        c.rect(6, 24, 7, 25, shade(BEIGE, 0.7))
        c.rect(9, 24, 10, 25, shade(BEIGE, 0.7))
        c.disc(13.5, 24.5, 1.3, hexc("9aa0a8"))
        c.set(13, 24, hexc("2b2f36"))
    return p


def cdrom(c):
    paint(hexc("cfc6ae"), 0.02, hi=1.15, lo=0.75)(c)
    w, h = c.w, c.h
    c.hline(1, w - 2, 1, shade(BEIGE, 0.68))
    c.hline(w - 4, w - 2, h - 2, shade(BEIGE, 0.62))
    c.set(w - 6, h - 2, LED_A)
    c.hline(2, 5, h - 2, shade(BEIGE, 0.8))


def bay_cover(c):
    paint(hexc("d2c9b1"), 0.02, hi=1.15, lo=0.78)(c)
    c.hline(1, c.w - 2, c.h // 2, shade(BEIGE, 0.85))


def floppy(on):
    def p(c):
        paint(hexc("cac1a8"), 0.02, hi=1.15, lo=0.75)(c)
        c.hline(1, c.w - 3, 0 if c.h < 3 else 1, hexc("2a2a2a"))
        c.set(c.w - 2, c.h - 1, LED_G if on else hexc("2f4a35"))
        c.set(c.w - 3, c.h - 1, shade(BEIGE, 0.6))
    return p


def retro_side(c):
    paint(BEIGE, 0.025, hi=1.1, lo=0.8)(c)
    for y in range(c.h - 9, c.h - 2, 2):
        c.hline(2, 8, y, shade(BEIGE, 0.74))
    c.vline(c.w - 2, 1, c.h - 2, shade(BEIGE, 0.9))


def retro_back(c):
    paint(BEIGE_D, 0.03)(c)
    w, h = c.w, c.h
    # PSU with fan grille and power socket
    c.rect(1, 1, w - 2, 9, hexc("a3a8ae"))
    c.frame(1, 1, w - 2, 9, hexc("7d8289"))
    c.ring(w / 2 + 2, 5, 2.2, 3.6, hexc("5b6068"))
    c.disc(w / 2 + 2, 5, 2.0, hexc("3a3e44"))
    c.hline(int(w / 2), int(w / 2) + 3, 5, hexc("7d8289"))
    c.rect(2, 3, 4, 6, hexc("2b2f36"))
    # I/O plate and expansion slot covers
    c.rect(2, 11, w - 3, 12, hexc("8f949b"))
    c.set(4, 11, hexc("6f4fa8"))
    c.set(6, 11, hexc("2d7a46"))
    for i in range(4):
        c.rect(2 + i * 3, 14, 3 + i * 3, h - 5, hexc("b4b9c0"))
        c.set(2 + i * 3, 14, hexc("6b7078"))


def crt_glass(c):
    for y in range(c.h):
        for x in range(c.w):
            d = ((x - c.w / 2) / (c.w / 2)) ** 2 + ((y - c.h / 2) / (c.h / 2)) ** 2
            c.set(x, y, mix(hexc("2c3531"), hexc("0b0e0c"), min(1, d * 0.9)))


def crt_chin(c):
    paint(BEIGE, 0.025, hi=1.1, lo=0.75)(c)
    w, h = c.w, c.h
    for i in range(5):
        c.hline(w // 2 - 8 + i * 3, w // 2 - 7 + i * 3, h // 2, shade(BEIGE, 0.6))
    c.rect(w - 6, h // 2 - 1, w - 3, h // 2 + 1, shade(BEIGE, 0.7))
    # power LED (unlit here, the renderer lights it while the machine runs)
    c.set(w - 8, h // 2, hexc("1f4a2c"))
    c.hline(3, 9, h // 2, shade(BEIGE, 0.78))


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
    # power LED in the chin (unlit here, the renderer lights it while the linked machine runs)
    c.set(c.w // 2, c.h - 1, hexc("12303d"))


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
    m = Model("retro_pc" + ("_on" if on else ""))
    body = paint(BEIGE, 0.025)
    side = retro_side
    m.box([4.5, 0.5, 1.6], [11.5, 15, 15], north=body, south=retro_back, east=side, west=side, up=vents_top(BEIGE, 3), down=flat(hexc("8a8577")))
    m.box([4, 0.5, 1], [12, 15.5, 1.6], north=retro_bezel(on), east=paint(BEIGE_D), west=paint(BEIGE_D), up=paint(BEIGE_D), down=paint(BEIGE_D))
    m.box([4.6, 12.4, 0.75], [11.4, 14.1, 1], north=cdrom, east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    m.box([4.6, 10.6, 0.85], [11.4, 12.2, 1], north=bay_cover, east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    m.box([6, 8.9, 0.8], [10, 10.1, 1], north=floppy(on), east=flat(BEIGE_D), west=flat(BEIGE_D), up=flat(BEIGE), down=flat(BEIGE_D))
    m.box([9.6, 2.4, 0.6], [11.2, 4, 1], north=paint(hexc("cbc2aa"), 0.01, hi=1.2, lo=0.6), east=flat(BEIGE_D), west=flat(BEIGE_D),
          up=flat(BEIGE), down=flat(BEIGE_D))
    for x in (4.6, 10.4):
        for z in (2, 13.6):
            m.box([x, 0, z], [x + 1, 0.5, z + 1], all=flat(hexc("2a2a2a")))
    return m


def crt_monitor():
    m = Model("crt_monitor")
    bz = paint(BEIGE, 0.025, hi=1.12, lo=0.78)
    # bezel frame around the tube (screen opening x 2.5..13.5, y 2.5..11)
    m.box([0.5, 11, 1.5], [15.5, 13.5, 2], north=bz, up=bz, east=bz, west=bz, down=flat(BEIGE_D))
    m.box([0.5, 0.5, 1.5], [15.5, 2.5, 2], north=crt_chin, east=bz, west=bz, down=bz, up=flat(BEIGE_D))
    m.box([13.5, 2.5, 1.5], [15.5, 11, 2], north=bz, east=bz, west=flat(BEIGE_D))
    m.box([0.5, 2.5, 1.5], [2.5, 11, 2], north=bz, west=bz, east=flat(BEIGE_D))
    # inner chamfer: a darker step between bezel and tube gives the opening depth (the picture starts inside it)
    step = paint(hexc("b3aa93"), 0.02)
    m.box([2.5, 10.6, 1.75], [13.5, 11, 2], north=step, down=step)
    m.box([2.5, 2.5, 1.75], [13.5, 2.9, 2], north=step, up=step)
    m.box([13.1, 2.9, 1.75], [13.5, 10.6, 2], north=step, west=step)
    m.box([2.5, 2.9, 1.75], [2.9, 10.6, 2], north=step, east=step)
    m.box([1, 0.5, 2], [15, 13.3, 9], north=crt_glass, east=paint(BEIGE), west=paint(BEIGE), up=vents_top(BEIGE, 3), down=flat(BEIGE_D),
          south=paint(BEIGE_D))
    m.box([2.5, 1.5, 9], [13.5, 11.8, 12], all=crt_back, north=None)
    m.box([4, 3, 12], [12, 10, 14.5], all=crt_back)
    m.box([3, 0, 3], [13, 0.5, 11], all=paint(hexc("b9b09a")))
    return m


def flat_monitor():
    m = Model("monitor")
    shell = hexc("1e2126")
    m.box([0, 3, 7], [16, 12.5, 7.75], north=bezel_front, south=monitor_back(shell), east=flat(shell), west=flat(shell),
          up=flat(hexc("2a2e35")), down=flat(hexc("15171a")))
    m.box([2, 4, 7.75], [14, 11.5, 8.75], all=monitor_back(hexc("23262c")), north=None)
    m.box([6.5, 6, 8.75], [9.5, 9, 9.25], all=vesa)
    m.box([7, 0.75, 9.25], [9, 8.5, 10.25], all=brushed(hexc("8e959f"), vertical=True))
    m.box([4.5, 0, 6.5], [11.5, 0.75, 12], all=brushed(hexc("9aa1ab")))
    return m


def ultrawide_stand():
    m = Model("curved_monitor")
    base = brushed(hexc("2f333b"))
    m.box([4, 0, 7], [12, 0.6, 12.5], all=base)
    # feet beside (not inside) the base plate
    m.box([3, 0, 10.5], [4, 0.6, 12.5], all=base)
    m.box([12, 0, 10.5], [13, 0.6, 12.5], all=base)
    m.box([7, 0.6, 8.6], [9, 9, 10.0], all=brushed(hexc("383d46"), vertical=True))
    m.box([7.4, 3, 8.45], [8.6, 4.2, 8.6], north=flat(hexc("1b1d22")))
    return m


def arm_stand(name, panels):
    """Desk clamp + pole, articulated links and VESA plates that meet the code-drawn panels (back at z 8.6)."""
    m = Model(name)
    metal = brushed(hexc("3a3f48"))
    joint = paint(hexc("4a505a"), 0.02)
    plate = vesa
    m.box([6, 0, 8], [10, 1, 12], all=brushed(hexc("30343c")))
    m.box([7, 1, 9], [9, 12.5, 11], all=brushed(hexc("3a3f48"), vertical=True))
    m.box([6.5, 12.5, 8.5], [9.5, 13.2, 11.5], all=joint)
    m.box([7.2, 3, 8.9], [8.8, 3.6, 9.0], north=flat(LED_B), emissive=True)
    y0, y1 = 10.34, 11.14

    def bx(sign, x0, x1, ya, yb, z0, z1, painter):
        a, b = sorted((8 + sign * (x0 - 8), 8 + sign * (x1 - 8)))
        m.box([a, ya, z0], [b, yb, z1], all=painter)

    for sign in (1, -1):
        if panels == 2:
            bx(sign, 9, 12.5, y0, y1, 9.6, 10.4, metal)
            bx(sign, 12.5, 13.7, y0 - 0.2, y1 + 0.2, 8.9, 10.4, joint)
            bx(sign, 13.7, 15.52, y0, y1, 8.9, 9.6, metal)
            bx(sign, 14.52, 16.52, 9.74, 11.74, 8.6, 8.9, plate)
        else:
            bx(sign, 9, 15.3, y0, y1, 9.6, 10.4, metal)
            bx(sign, 15.3, 16.5, y0 - 0.2, y1 + 0.2, 8.9, 10.4, joint)
            bx(sign, 16.5, 22.2, y0, y1, 8.9, 9.6, metal)
            bx(sign, 21.8, 23.0, y0 - 0.2, y1 + 0.2, 8.6, 9.8, joint)
            bx(sign, 22.2, 23.0, y0, y1, 5.4, 8.6, metal)
    if panels == 3:
        m.box([7, 9.74, 8.6], [9, 11.74, 9.0], all=plate)
    return m


def panel_face(c, left=True, right=True):
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("0b0c0f"))
    x0 = 1 if left else 0
    x1 = c.w - 2 if right else c.w - 1
    c.vgrad(x0, 1, x1, c.h - 2, hexc("1d3b5c"), hexc("0b1424"))
    c.hline(x0, x1, 1, hexc("2e5f8f"))


def panel_part(left, right):
    """Segment of one continuous curved panel: only the outer ends get a bezel."""
    return lambda c: panel_face(c, left, right)


def display_item(name, kind):
    """Inventory/hand model: the stand plus the panels the world renderer draws at runtime."""
    m = Model(name + "_item")
    metal = brushed(hexc("30343c"))
    shell = flat(hexc("2a2e35"))
    if kind == "curved":
        m.box([5, 0, 6], [11, 0.6, 10], all=metal)
        m.box([7.4, 0.6, 7.8], [8.6, 5, 8.8], all=metal)
        # one continuous panel: the side segments hinge on the front edge, so the faces meet without seams
        # north faces are painted as seen from the front: the viewer's left (+x) is the texture's left
        m.box([5, 4.2, 7], [11, 10.2, 7.6], north=panel_part(False, False), south=shell, up=shell, down=shell)
        m.box([11, 4.2, 7], [15.4, 10.2, 7.6], north=panel_part(True, False), south=shell, up=shell, down=shell, east=shell,
              rotation={"origin": [11, 7, 7], "axis": "y", "angle": 22.5})
        m.box([0.6, 4.2, 7], [5, 10.2, 7.6], north=panel_part(False, True), south=shell, up=shell, down=shell, west=shell,
              rotation={"origin": [5, 7, 7], "axis": "y", "angle": -22.5})
    else:
        panels = 2 if kind == "dual" else 3
        m.box([6, 0, 9], [10, 0.8, 13], all=metal)
        m.box([7.4, 0.8, 10.4], [8.6, 7.5, 11.6], all=metal)
        if panels == 2:
            m.box([1, 7, 10.3], [15, 7.8, 11], all=metal)
            for x0 in (0.3, 8.2):
                m.box([x0, 5.5, 9.6], [x0 + 7.5, 10.5, 10.2], north=panel_face, south=shell, up=shell, down=shell, east=shell, west=shell)
        else:
            # the bar stays behind the centre panel so it never pierces the angled outer panels
            m.box([4, 7, 10.3], [12, 7.8, 11], all=metal)
            m.box([5.4, 5.5, 9.6], [10.6, 10, 10.2], north=panel_face, south=shell, up=shell, down=shell)
            m.box([10.6, 5.5, 9.6], [15.6, 10, 10.2], north=panel_face, south=shell, up=shell, down=shell, east=shell,
                  rotation={"origin": [10.6, 7.75, 9.6], "axis": "y", "angle": 22.5})
            m.box([0.4, 5.5, 9.6], [5.4, 10, 10.2], north=panel_face, south=shell, up=shell, down=shell, west=shell,
                  rotation={"origin": [5.4, 7.75, 9.6], "axis": "y", "angle": -22.5})
    return m


def video_wall():
    m = Model("video_wall_panel")

    def front(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("050607"))
        c.frame(0, 0, c.w - 1, c.h - 1, hexc("15171b"))
    side = flat(hexc("1b1d22"))
    m.box([0, 0, 13], [16, 16, 14], north=front, south=side, east=side, west=side, up=side, down=side)
    m.box([1, 1, 14], [15, 15, 15.5], all=monitor_back(hexc("26292f")), north=None)
    m.box([3, 0.5, 15.5], [4, 15.5, 16], all=flat(hexc("4a505a")))
    m.box([12, 0.5, 15.5], [13, 15.5, 16], all=flat(hexc("4a505a")))
    return m


# ---------------------------------------------------------------------------------------- gaming tower
def tower_side_panel(c):
    paint(CASE_BLACK, 0.03)(c)
    for y in range(3, c.h - 3, 3):
        c.hline(c.w - 7, c.w - 3, y, hexc("0f1114"))


def tower_top(c):
    """Hex perforation: 1-px holes on a 3-px pitch, alternate rows offset."""
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("1c1f24"))
    c.frame(0, 0, c.w - 1, c.h - 1, hexc("2b2f36"))
    for j, y in enumerate(range(2, c.h - 2, 2)):
        off = 1 if j % 2 else 0
        for x in range(2 + off, c.w - 2, 3):
            c.set(x, y, hexc("0a0b0d"))


def plinth(c):
    paint(hexc("1a1c21"), 0.02)(c)
    for y in range(1, c.h - 1):
        for x in range(1 + (y % 2), c.w - 1, 2):
            c.set(x, y, hexc("0c0d10"))


def tower_back(c):
    paint(CASE_BLACK, 0.02)(c)
    w, h = c.w, c.h
    # rear fan grille (upper), I/O shield, PCIe slot covers, PSU with its fan
    c.ring(w * 0.62, h * 0.28, 3.2, 5.6, hexc("2b2f36"))
    c.disc(w * 0.62, h * 0.28, 3.0, hexc("101216"))
    c.rect(2, 4, 5, 13, hexc("8b9099"))
    for y in range(5, 13, 2):
        c.hline(3, 4, y, hexc("3a3f48"))
    for i in range(7):
        c.vline(2 + i * 2, h - 12, h - 6, hexc("6b727d"))
    c.rect(2, h - 4, w - 3, h - 2, hexc("101216"))
    c.ring(w * 0.7, h - 3, 0.6, 1.6, hexc("2b2f36"))


def mobo(c):
    """Motherboard seen from the glass side (east face): PCB, VRM heatsinks, socket, DIMM slots, PCIe."""
    w, h = c.w, c.h
    c.rect(0, 0, w - 1, h - 1, PCB)
    for _ in range(30):
        x, y = RNG.randrange(w), RNG.randrange(h)
        c.hline(x, min(w - 1, x + RNG.randrange(2, 5)), y, hexc("303b47"))
    # VRM heatsinks along the top and the rear edge (east face: rear = left)
    c.rect(1, 1, w - 2, 3, HEAT_D)
    c.hline(1, w - 2, 1, HEAT)
    c.rect(1, 1, 3, h // 2, HEAT_D)
    c.vline(1, 1, h // 2, HEAT)
    # CPU socket
    sx, sy = w // 2 - 4, 5
    c.rect(sx, sy, sx + 6, sy + 6, hexc("2a2e35"))
    c.frame(sx, sy, sx + 6, sy + 6, HEAT)
    # DIMM slots toward the front (right)
    for i in range(4):
        c.vline(w - 9 + i * 2, 4, 13, hexc("12151a"))
    # chipset heatsink + M.2 cover
    c.rect(w - 9, h - 8, w - 3, h - 3, HEAT_D)
    c.hline(w - 9, w - 3, h - 8, HEAT)
    c.rect(4, h // 2 + 2, w - 12, h // 2 + 3, HEAT_D)
    c.hline(4, w - 12, h // 2 + 2, HEAT)
    # PCIe slots
    for k in range(2):
        y = h // 2 + 6 + k * 5
        c.hline(3, w // 2 + 3, y, hexc("0b0c0e"))
        c.set(w // 2 + 3, y, HEAT)


def shroud(c):
    base = hexc("30353d")
    paint(base, 0.02, hi=1.0, lo=0.85)(c)
    w, h = c.w, c.h
    c.hline(0, w - 1, 0, hexc("4a505a"))
    for y in range(2, h - 1, 2):
        for x in range(2 + (y // 2) % 2, w // 3, 2):
            c.set(x, y, hexc("15171b"))
    if w > 24 and h >= 5:
        c.text(w - 21, max(0, h - 5), "ORBIT", hexc("6b727d"))


def radiator(c):
    paint(hexc("23262c"), 0.02)(c)
    for x in range(1, c.w - 1, 2):
        c.vline(x, 1, c.h - 2, hexc("16181c"))


def fan_frame_face(c):
    c.rect(0, 0, c.w - 1, c.h - 1, hexc("15171b"))
    c.hline(0, c.w - 1, 0, hexc("23262c"))


def tower(on):
    m = Model("gaming_tower" + ("_on" if on else ""), render_type="minecraft:cutout", particle_paint=paint(CASE_BLACK, 0.03))
    fan = Ref("fan", (0, 0, 16, 16))
    m.texture("fan", fx("fan_rgb" if on else "fan_off"))
    m.texture("rgb", fx("rgb_strip" if on else "rgb_off"))
    case = paint(CASE_BLACK, 0.02)
    wall = paint(WALL, 0.02, hi=1.05, lo=0.9)
    frame = fan_frame_face
    # chassis: plinth, top, rear, solid right side (west). The glass front + side are drawn by the renderer.
    m.box([3.5, 0.5, 0.5], [12.5, 1.5, 15.5], north=plinth, east=plinth, west=case, south=case, up=flat(hexc("15171b")), down=case)
    for x in (3.7, 11.3):
        for z in (0.8, 14.2):
            m.box([x, 0, z], [x + 1, 0.5, z + 1], all=flat(hexc("0c0d0f")))
    m.box([3.5, 15, 0.5], [12.5, 16, 15.5], up=tower_top, north=case, east=case, west=case, south=case, down=flat(hexc("15171b")))
    m.box([3.5, 1.5, 14.5], [12.5, 15, 15.5], south=tower_back, east=case, west=case, north=wall)
    m.box([3.5, 1.5, 0.5], [4.1, 15, 14.5], west=tower_side_panel, north=case, east=wall)
    # RGB edge strip along the front-top
    m.box([4.1, 14.6, 0.55], [12.4, 15, 0.9], north=Ref("rgb", (0, 0, 8.3, 0.4)), down=Ref("rgb", (0, 0, 8.3, 0.35)), emissive=on, shade=False)
    # interior: motherboard, PSU shroud, vertical intake fans, top radiator with fans
    m.box([4.1, 4.2, 3.5], [4.4, 14.4, 13.8], east=mobo, north=flat(PCB), up=flat(PCB))
    m.box([4.1, 1.5, 0.7], [12.3, 4.0, 14.5], east=shroud, north=shroud, up=paint(hexc("2b3038"), 0.02), west=None)
    for i in range(3):
        y0 = 4.4 + i * 3.4
        m.box([4.1, y0, 0.8], [5.2, y0 + 3.3, 4.1], east=fan, north=frame, up=frame, down=frame, emissive=on, shade=False)
    m.box([5.4, 14.4, 4.3], [12.2, 15, 14.3], down=radiator, north=flat(hexc("1b1d22")), south=flat(hexc("1b1d22")),
          west=flat(hexc("1b1d22")), east=Ref("rgb", (0, 0, 10, 0.6)))
    for i in range(3):
        z0 = 4.5 + i * 3.25
        m.box([7.0, 13.6, z0], [10.2, 14.4, z0 + 3.2], down=fan, east=frame, west=frame, north=frame, south=frame, emissive=on, shade=False)
    return m


def tower_parts(models):
    """Standalone models the tower renderer places for installed parts (same block coordinates)."""
    for tier, accent, fans in (("gt", hexc("3b82f6"), 2), ("gx", hexc("22c55e"), 2), ("titan", hexc("f59e0b"), 3)):
        m = Model("part/gpu_" + tier)
        m.texture("fan", fx("fan_rgb" if tier == "titan" else "fan_off"))
        m.texture("rgb", fx("rgb_strip"))
        m.texture("gold", fx("rgb_gold"))

        def side(c, accent=accent):
            paint(hexc("24272d"), 0.02)(c)
            w, h = c.w, c.h
            c.hline(1, w - 2, h - 1, accent)
            for x in range(3, w - 2, 4):
                c.line(x, 0, x + 1, h - 2, hexc("33373f"))

        def backplate(c):
            paint(hexc("2a2e35"), 0.02)(c)
            c.hline(0, c.w - 1, 0, hexc("6b727d"))
            c.hline(0, c.w - 1, c.h - 1, hexc("6b727d"))
            c.rect(2, 2, c.w - 3, 3, hexc("3a3f48"))

        m.box([4.4, 5.0, 2.0], [9.6, 6.9, 13.6], east=side, north=paint(hexc("24272d")), up=backplate,
              down=flat(hexc("15171b")), south=flat(hexc("15171b")))
        f = 4.4 if fans == 2 else 3.6
        gap = (11.6 - fans * f) / (fans + 1)
        x0 = 7.0 - f / 2
        for i in range(fans):
            z0 = 2.0 + gap + i * (f + gap)
            m.box([x0, 4.8, z0], [x0 + f, 5.0, z0 + f], down=Ref("fan", (0, 0, 16, 16)), east=flat(hexc("15171b")), emissive=tier == "titan",
                  shade=False)
        if tier == "gx":
            m.box([9.6, 6.2, 2.4], [9.65, 6.6, 13.2], east=Ref("rgb", (0, 0, 10.8, 0.4)), emissive=True)
        if tier == "titan":
            # the TITAN wordmark as a lit bar, plus RGB along the shroud
            m.box([9.6, 6.35, 4.0], [9.65, 6.55, 11.6], east=Ref("gold", (0, 0, 7.6, 0.2)), emissive=True)
            m.box([9.6, 5.3, 2.4], [9.65, 5.5, 13.2], east=Ref("rgb", (0, 0, 10.8, 0.2)), emissive=True)
        # PCIe slot bracket screwed to the rear panel, and the gold fingers in the slot
        m.box([4.4, 4.4, 13.6], [10.2, 7.4, 13.9], all=brushed(hexc("b8bec7")))
        m.box([4.5, 4.6, 6.0], [5.0, 5.0, 11.0], down=flat(hexc("e3b95a")), east=flat(hexc("e3b95a")))
        models.append(m)
    # RAM stick: heat spreader on the large faces, RGB light bar along the outer edge facing the glass
    m = Model("part/ram")
    m.texture("rgb", fx("rgb_strip"))

    def spreader(c):
        paint(hexc("2b2f36"), 0.02)(c)
        c.hline(0, c.w - 1, 0, hexc("5c636e"))
        for y in range(2, c.h - 1, 2):
            c.set(c.w // 2, y, hexc("1b1d22"))
    m.box([4.4, 9.2, 0], [5.8, 12.4, 0.35], north=spreader, south=spreader, up=flat(hexc("2b2f36")), down=flat(hexc("e3b95a")))
    m.box([5.8, 9.2, 0], [6.05, 12.4, 0.35], east=Ref("rgb", (0, 0, 3.2, 0.35), rotation=90), north=Ref("rgb", (0, 0, 0.25, 3.2)),
          south=Ref("rgb", (0, 0, 0.25, 3.2)), up=Ref("rgb", (0, 0, 0.25, 0.35)), emissive=True)
    models.append(m)
    # AIO pump head with LCD + tubes
    m = Model("part/cooler")
    m.texture("lcd", fx("aio_lcd"))
    m.box([4.4, 9.6, 7.6], [5.6, 12.6, 10.6], north=flat(hexc("1b1d22")), south=flat(hexc("1b1d22")),
          up=flat(hexc("2a2e35")), down=flat(hexc("1b1d22")), east=flat(hexc("101216")))
    m.box([5.6, 9.75, 7.75], [5.65, 12.45, 10.45], east=Ref("lcd", (0, 0, 12, 12)), emissive=True)
    tube = paint(hexc("1e2126"), 0.04)
    m.box([5.0, 12.6, 8.2], [5.5, 14.0, 8.7], all=tube)
    m.box([5.0, 12.6, 9.5], [5.5, 14.0, 10.0], all=tube)
    models.append(m)
    # PCIe add-in card: PCB with chips and a heatsink on the visible side
    m = Model("part/card")

    def card_top(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("1d5a39"))
        c.hline(0, c.w - 1, 0, hexc("2a7a50"))
        c.rect(1, 2, c.w - 3, 5, HEAT_D)
        c.hline(1, c.w - 3, 2, HEAT)
        for (x, y) in ((1, 8), (4, 8), (1, 11)):
            c.rect(x, y, x + 1, y + 1, hexc("15171b"))
        c.set(c.w - 2, c.h - 2, hexc("e3b95a"))

    def card_edge(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("1d5a39"))
        for x in range(1, c.w - 1, 3):
            c.set(x, 0, hexc("15171b"))
    m.box([4.4, 7.5, 7.0], [8.5, 8.3, 13.6], up=card_top, east=card_edge, north=flat(hexc("1d5a39")), down=flat(hexc("164a2f")))
    models.append(m)
    # 2.5" drive on the shroud
    m = Model("part/drive")

    def drive_top(c):
        paint(hexc("2b2f36"), 0.02)(c)
        c.rect(1, 2, c.w - 2, c.h - 3, hexc("c9d1dc"))
        c.hline(1, c.w - 2, 2, hexc("7c5cff"))
        for (x, y) in ((0, 0), (c.w - 1, 0), (0, c.h - 1), (c.w - 1, c.h - 1)):
            c.set(x, y, hexc("8e96a3"))
    m.box([6.5, 4.0, 10.0], [10.5, 4.4, 13.8], up=drive_top, east=flat(hexc("1b1d22")), north=flat(hexc("1b1d22")),
          south=flat(hexc("e3b95a")))
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


# gear stacked bottom to top: (texture row key, y0, y1)
RACK_STACK = (("storage", 0.4, 4.9), ("blank", 4.9, 6.6), ("server_b", 6.6, 9.6), ("server_a", 9.6, 12.6),
              ("switch", 12.6, 14.1), ("patch", 14.1, 15.6))


def server_rack(on, variant=0):
    m = Model("server_rack" + (f"_on_{variant}" if on else ""), particle_paint=rack_side)
    m.texture("front", fx(f"rack_front_{variant}" if on else "rack_front_off"))
    for x in (0, 15):
        for z in (0, 15):
            m.box([x, 0, z], [x + 1, 15.6, z + 1], all=rack_post, up=None)
    m.box([0, 0, 1], [0.5, 15.6, 15], west=rack_side, east=flat(hexc("101215")))
    m.box([15.5, 0, 1], [16, 15.6, 15], east=rack_side, west=flat(hexc("101215")))
    m.box([1, 0, 15.5], [15, 15.6, 16], south=mesh(hexc("1a1c21"), hexc("08090b")), north=flat(hexc("0d0e10")))
    # perforated roof
    m.box([0, 15.6, 0], [16, 16, 16], up=mesh(hexc("1a1c21"), hexc("08090b"), step=3), north=flat(hexc("15171b")), south=flat(hexc("15171b")),
          east=flat(hexc("15171b")), west=flat(hexc("15171b")), down=flat(hexc("0d0e10")))
    for x in (1, 14.4):
        m.box([x, 0, 1], [x + 0.6, 15.6, 1.6], north=rack_post, east=flat(hexc("15171b")), west=flat(hexc("15171b")))
    lid = brushed(hexc("3a3f48"))
    for key, y0, y1 in RACK_STACK:
        r0, r1 = RACK_ROWS[key]
        v0, v1 = r0 / 2.0, r0 / 2.0 + (y1 - y0)
        m.box([1.2, y0, 1.2], [14.8, y1, 15.2], north=Ref("front", (0, v0, 13.6, v1)), up=lid, down=flat(hexc("2a2e35")),
              east=paint(hexc("3a3f48")), west=paint(hexc("3a3f48")), emissive=on and key != "blank")
    return m


# ---------------------------------------------------------------------------------------- laptops
def laptop_keys(rugged):
    def p(c):
        base = hexc("3a3d42") if rugged else hexc("c9ced6")
        well = hexc("2a2d33") if rugged else hexc("aeb4bd")
        cap = hexc("0e0f11") if rugged else hexc("1b1d22")
        paint(base, 0.015, hi=1.06, lo=0.9)(c)
        w, h = c.w, c.h
        kx0, kx1, ky0 = 2, w - 3, 2
        c.rect(kx0 - 1, ky0 - 1, kx1 + 1, ky0 + 9, well)
        c.hline(kx0 - 1, kx1 + 1, ky0 - 1, hexc("4a4f57") if rugged else shade(well, 0.85))
        stagger = (0, 1, 1, 2)
        for r in range(4):
            y = ky0 + r * 2
            for x in range(kx0, kx1 + 1):
                if (x - kx0 + stagger[r]) % 3 != 2:
                    c.set(x, y, cap)
        # bottom row: modifiers, space bar, arrow cluster
        y = ky0 + 8
        for x in list(range(kx0, kx0 + 2)) + list(range(kx0 + 3, kx0 + 5)) + list(range(kx0 + 6, kx1 - 5)) + [kx1 - 3, kx1 - 1]:
            c.set(x, y, cap)
        # trackpad
        tw = w // 3
        ty0 = ky0 + 11
        c.rect(w // 2 - tw // 2, ty0, w // 2 + tw // 2, h - 2, shade(base, 0.8 if rugged else 0.93))
        c.frame(w // 2 - tw // 2, ty0, w // 2 + tw // 2, h - 2, shade(base, 0.7 if rugged else 0.82))
        c.img = c.img.rotate(180)
        c.px = c.img.load()
    return p


def lid_back(rugged):
    def p(c):
        cx, cy = c.w / 2, c.h / 2
        if rugged:
            paint(hexc("3a3d42"), 0.03)(c)
            c.rect(int(cx) - 7, int(cy) - 3, int(cx) + 6, int(cy) + 3, hexc("2a2d33"))
            c.text(int(cx) - 5, int(cy) - 2, "ORB", hexc("c9d1dc"))
        else:
            brushed(ALU)(c)
            c.ring(cx, cy, 2.6, 4.0, hexc("e6f6ff"))
            c.disc(cx, cy, 1.2, hexc("e6f6ff"))
    return p


def lid_front(rugged):
    def p(c):
        c.rect(0, 0, c.w - 1, c.h - 1, hexc("0b0c0f"))
        c.frame(0, 0, c.w - 1, c.h - 1, hexc("2a2e35"))
        if rugged:
            c.frame(1, 1, c.w - 2, c.h - 2, hexc("1c1e22"))
        c.set(c.w // 2, 1, hexc("1f3a4d"))
    return p


def laptop(name, rugged):
    m = Model(name)
    lid_rot = {"origin": [8, 1.0, 12.4], "axis": "x", "angle": 22.5}
    if rugged:
        body = paint(hexc("3a3d42"), 0.03)
        bump = paint(hexc("1c1e22"), 0.04)
        m.box([1.5, 0.1, 2.5], [14.5, 1.0, 13], up=laptop_keys(True), north=paint(hexc("2f3237")), east=body, west=body, south=body,
              down=flat(hexc("1a1c1f")))
        for x in (1.2, 13.6):
            for z in (2.2, 11.9):
                m.box([x, 0, z], [x + 1.2, 1.4, z + 1.2], all=bump)
        # carry handle: two posts and a bar, no overlapping faces
        m.box([5, 0.3, 1.6], [5.6, 1.0, 2.5], all=bump)
        m.box([10.4, 0.3, 1.6], [11, 1.0, 2.5], all=bump)
        m.box([5, 0.3, 1.0], [11, 1.0, 1.6], all=bump)
        m.box([2, 1.0, 11.9], [14, 10.0, 12.9], north=lid_front(True), south=lid_back(True), east=bump, west=bump, up=bump, rotation=lid_rot)
        m.box([1.6, 9.2, 11.7], [3, 10.4, 13.1], all=bump, rotation=lid_rot)
        m.box([13, 9.2, 11.7], [14.4, 10.4, 13.1], all=bump, rotation=lid_rot)
        m.box([7, 9.6, 11.6], [9, 10.3, 13.0], all=paint(hexc("8e96a3")), rotation=lid_rot)
    else:
        body = brushed(ALU)
        m.box([2, 0.2, 3], [14, 1.0, 12.6], up=laptop_keys(False), north=body, east=body, west=body, south=body, down=flat(hexc("9aa1ab")))
        for x in (2.5, 12.8):
            for z in (3.4, 11.6):
                m.box([x, 0, z], [x + 0.7, 0.2, z + 0.7], all=flat(hexc("2a2e35")))
        m.box([2, 1.0, 11.9], [14, 9.8, 12.35], north=lid_front(False), south=lid_back(False), east=body, west=body, up=body, rotation=lid_rot)
        m.box([3, 0.7, 11.8], [13, 1.4, 12.5], all=flat(hexc("2a2e35")))
    return m


# ---------------------------------------------------------------------------------------- peripherals
SMALL_GUI = {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [1.0, 1.0, 1.0]}


def keyboard():
    m = Model("mechanical_keyboard", display=display(
        gui={"rotation": [45, 200, 0], "translation": [0, 1.5, 0], "scale": [0.9, 0.9, 0.9]},
        ground={"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
        fixed={"rotation": [-90, 0, 0], "translation": [0, 0, -3], "scale": [0.9, 0.9, 0.9]}))
    m.texture("keys", fx("keyboard_keys"))
    m.texture("rgb", fx("rgb_strip"))
    case = brushed(hexc("23262c"))
    # underglow plate sticking out around the case, lit on every outward face
    m.box([0.5, 0, 4.1], [15.5, 0.25, 10.6], north=Ref("rgb", (0, 0, 15, 0.25)), south=Ref("rgb", (0, 0, 15, 0.25)),
          east=Ref("rgb", (0, 0, 6.5, 0.25)), west=Ref("rgb", (0, 0, 6.5, 0.25)), up=Ref("rgb", (0, 0, 15, 6.5)), emissive=True)
    m.box([0.6, 0.25, 4.2], [15.4, 0.8, 10.2], north=case, south=case, east=case, west=case, up=paint(hexc("1a1c21"), 0.02))
    m.box([1, 0.8, 4.6], [15, 1.3, 9.6], up=Ref("keys", (14, 5, 0, 0)), north=flat(hexc("202329")),
          east=flat(hexc("202329")), west=flat(hexc("202329")), south=flat(hexc("202329")), emissive=True)
    m.box([1.2, 0.8, 9.65], [2.4, 1.5, 10.05], all=brushed(hexc("6b727d")))
    return m


def mouse():
    m = Model("gaming_mouse", display=display(
        gui={"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [1.25, 1.25, 1.25]},
        ground={"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        fixed={"rotation": [-90, 0, 0], "translation": [0, 0, -3], "scale": [1.25, 1.25, 1.25]}))
    m.texture("rgb", fx("rgb_strip"))
    shell = paint(hexc("1c1e23"), 0.03, hi=1.25)
    top = paint(hexc("24272d"), 0.03, hi=1.3)
    m.box([6.7, 0, 5.4], [9.3, 0.4, 10.6], all=paint(hexc("15171b"), 0.02))
    m.box([6.6, 0.4, 5.6], [9.4, 1.0, 10.4], all=shell)
    m.box([6.8, 1.0, 5.8], [9.2, 1.55, 8.4], all=top)
    m.box([8.05, 1.0, 8.4], [9.2, 1.25, 10.4], all=top)
    m.box([6.8, 1.0, 8.4], [7.95, 1.25, 10.4], all=top)
    m.box([7.85, 1.0, 8.9], [8.15, 1.45, 9.8], all=flat(hexc("3a3f48")))
    # RGB: side strips and the logo on the palm rest
    m.box([6.55, 0.5, 5.9], [6.6, 0.8, 6.9], west=Ref("rgb", (0, 0, 1, 0.3)), emissive=True)
    m.box([9.4, 0.5, 5.9], [9.45, 0.8, 6.9], east=Ref("rgb", (0, 0, 1, 0.3)), emissive=True)
    m.box([7.7, 1.55, 6.4], [8.3, 1.58, 7.0], up=Ref("rgb", (0, 0, 0.6, 0.6)), emissive=True)
    # thumb buttons
    m.box([9.4, 0.6, 7.2], [9.55, 0.9, 8.4], all=flat(hexc("2a2e35")))
    return m


def speaker_front(c):
    w, h = c.w, c.h
    c.rect(0, 0, w - 1, h - 1, hexc("141518"))
    c.frame(0, 0, w - 1, h - 1, hexc("22252a"))
    tx, ty = w / 2, h * 0.2
    c.disc(tx, ty, w * 0.22, hexc("0b0c0e"))
    c.ring(tx, ty, w * 0.14, w * 0.22, hexc("9aa1ab"))
    wx, wy = w / 2, h * 0.58
    R = w * 0.42
    c.disc(wx, wy, R, hexc("0b0c0e"))
    c.ring(wx, wy, R * 0.75, R, hexc("2e3238"))
    c.disc(wx, wy, R * 0.3, hexc("34383f"))
    c.hline(int(w * 0.3), int(w * 0.7), h - 2, hexc("050506"))
    c.set(w - 2, h - 2, LED_B)


def wood(c):
    for y in range(c.h):
        for x in range(c.w):
            g = math.sin(y * 0.9 + math.sin(x * 0.15) * 2.0) * 0.06
            c.set(x, y, shade(WOOD, 1 + g + RNG.uniform(-0.03, 0.03)))


def speakers():
    """One bookshelf speaker per block, so a pair can flank a monitor."""
    m = Model("desktop_speakers", display=display(
        gui={"rotation": [30, 225, 0], "translation": [0, 1, 0], "scale": [0.9, 0.9, 0.9]}))
    m.box([6, 0, 5.5], [10, 8, 11], north=speaker_front, east=wood, west=wood, up=wood, south=paint(hexc("141518")), down=flat(hexc("0e0f11")))
    return m


def crate():
    m = Model("delivery_crate")
    panel = paint(hexc("aeb5be"), 0.04)

    def side(c):
        panel(c)
        w, h = c.w, c.h
        for x in range(0, w, 4):
            c.rect(x, 1, x + 1, 2, hexc("ff8a1f"))
            c.rect(x + 2, 1, x + 3, 2, hexc("1b1d22"))
        for y in (h // 3, 2 * h // 3):
            c.hline(1, w - 2, y, shade(hexc("aeb5be"), 0.8))
        c.rect(w // 2 - 10, h // 2 - 3, w // 2 + 9, h // 2 + 3, hexc("d9dee4"))
        c.text(w // 2 - 9, h // 2 - 2, "ORBIT", hexc("2b2f36"))
        c.frame(0, 0, w - 1, h - 1, hexc("5b636e"))
    m.box([1.5, 0.5, 1.5], [14.5, 13.5, 14.5], north=side, south=side, east=side, west=side, up=panel, down=panel)
    edge = paint(hexc("ff7a1a"), 0.03)
    # corner posts between the top and bottom frames (no overlapping faces)
    for (x, z) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        m.box([x, 0.5, z], [x + 1, 13.5, z + 1], all=edge, up=None, down=None)
    for y in (0, 13.5):
        m.box([1, y, 1], [15, y + 0.5, 2], all=paint(hexc("4a505a")))
        m.box([1, y, 14], [15, y + 0.5, 15], all=paint(hexc("4a505a")))
        m.box([1, y, 2], [2, y + 0.5, 14], all=paint(hexc("4a505a")))
        m.box([14, y, 2], [15, y + 0.5, 14], all=paint(hexc("4a505a")))
    m.box([6, 14, 6], [10, 14.6, 10], all=paint(hexc("38c6ff")))
    return m


def pod():
    """Drop pod: three-tier taper, charred heat shield, splayed fins, hatch and beacon."""
    m = Model("supply_pod", particle_paint=paint(hexc("e4e7eb"), 0.03))
    hull = paint(hexc("e4e7eb"), 0.03)
    hull2 = paint(hexc("d3d8de"), 0.03)
    orange = paint(hexc("ff7a1a"), 0.03)

    def shield(c):
        for y in range(c.h):
            for x in range(c.w):
                c.set(x, y, shade(hexc("2a221c"), 1 + RNG.uniform(-0.15, 0.08)))
        for _ in range(max(2, c.w * c.h // 12)):
            c.set(RNG.randrange(c.w), RNG.randrange(c.h), RNG.choice([hexc("ff7a1a"), hexc("ffb02e"), hexc("c2410c")]))

    def hatch(c):
        hull(c)
        w, h = c.w, c.h
        c.frame(4, 1, w - 5, h - 2, hexc("8a929d"))
        c.hline(w // 2 - 2, w // 2 + 1, h // 2, hexc("2b2f36"))
        for x in range(0, w, 2):
            c.set(x, h - 1, hexc("ff7a1a") if (x // 2) % 2 == 0 else hexc("1b1d22"))

    def porthole(c):
        hull2(c)
        c.disc(c.w / 2, c.h / 2, min(c.w, c.h) * 0.32, hexc("0b2033"))
        c.set(c.w // 2 - 1, c.h // 2 - 1, hexc("38c6ff"))

    m.box([3, 0, 3], [13, 2, 13], all=shield)
    m.box([3, 2, 3], [13, 7, 13], north=hatch, south=hull, east=hull, west=hull, up=hull2)
    m.box([2.9, 5.2, 2.9], [13.1, 5.8, 13.1], all=orange, up=None, down=None)
    m.box([4, 7, 4], [12, 11, 12], north=porthole, south=hull2, east=hull2, west=hull2, up=hull)
    m.box([5, 11, 5], [11, 14, 11], all=hull, down=None)
    m.box([6, 14, 6], [10, 15.2, 10], all=orange, down=None)
    m.box([7.5, 15.2, 7.5], [8.5, 15.8, 8.5], all=flat(hexc("ff3b2f")), down=None, emissive=True)
    fin = paint(hexc("3a3f48"), 0.03)
    m.box([7.5, 0.5, 1.6], [8.5, 6, 3], all=fin, rotation={"origin": [8, 6, 3], "axis": "x", "angle": 22.5})
    m.box([7.5, 0.5, 13], [8.5, 6, 14.4], all=fin, rotation={"origin": [8, 6, 13], "axis": "x", "angle": -22.5})
    m.box([13, 0.5, 7.5], [14.4, 6, 8.5], all=fin, rotation={"origin": [13, 6, 8], "axis": "z", "angle": 22.5})
    m.box([1.6, 0.5, 7.5], [3, 6, 8.5], all=fin, rotation={"origin": [3, 6, 8], "axis": "z", "angle": -22.5})
    return m


# ============================================================================================ entry
def build(assets):
    tex_dir = os.path.join(assets, "textures", "block")
    model_tex = os.path.join(tex_dir, "model")
    model_dir = os.path.join(assets, "models", "block")
    shared_textures(tex_dir)
    # leftovers from the old layout
    for old in ("server_rack_on.json",):
        p = os.path.join(model_dir, old)
        if os.path.exists(p):
            os.remove(p)
    for old in ("server_rack_on.png",):
        p = os.path.join(model_tex, old)
        if os.path.exists(p):
            os.remove(p)
    models = [
        retro_pc(False), retro_pc(True), crt_monitor(), flat_monitor(), ultrawide_stand(),
        arm_stand("dual_monitor_arm", 2), arm_stand("triple_monitor_arm", 3), video_wall(),
        tower(False), tower(True), server_rack(False),
        display_item("curved_monitor", "curved"), display_item("dual_monitor_arm", "dual"), display_item("triple_monitor_arm", "triple"),
        laptop("pro_laptop", False), laptop("rugged_laptop", True), keyboard(), mouse(), speakers(), crate(), pod(),
    ]
    models += [server_rack(True, v) for v in range(len(RACK_FRAMES))]
    tower_parts(models)
    for m in models:
        if "/" in m.name:
            sub, base = m.name.split("/")
            m.name = base
            m.bake(MOD, os.path.join(model_tex, sub), os.path.join(model_dir, sub), atlas_name=base, tex_ns=f"block/model/{sub}")
        else:
            m.bake(MOD, model_tex, model_dir)
    return models
