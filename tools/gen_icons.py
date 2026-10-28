"""Item icons, slot ghost icons and the GUI icon atlas of HyperVisor X (all hand-built pixel art).

Item icons are 32x32 product shots lit from the top-left with a dark selective outline, modelled
after real parts: DDR4/DDR5 heat-spreader RAM with an RGB light bar, IHS-capped CPUs, triple-fan
graphics cards, 3.5"/2.5" drives, M.2 2280 sticks and PCIe add-in cards.
"""
import math

from pixelkit import Canvas, FONT_3x5, FONT_MICRO, alpha, hexc, hsv, mix, shade, text_width

OUTLINE = hexc("0b0d12")
GOLD = hexc("e3b95a")
GOLD_D = hexc("9c7426")
GOLD_L = hexc("f8e2a0")
PCB = hexc("1d5a39")
PCB_D = hexc("123724")
PCB_T = hexc("2f8255")
SILVER = hexc("c4cad2")
GUN = hexc("30353d")
BLACK = hexc("17191e")
WHITE = hexc("eef1f5")

TIER = {
    1: hexc("8b95a5"),
    2: hexc("3b82f6"),
    3: hexc("22c55e"),
    4: hexc("a855f7"),
    5: hexc("f59e0b"),
    6: hexc("ef4444"),
}


def finish(c):
    c.outline(OUTLINE)
    return c


def gold_fingers(c, x0, x1, y0, y1, notch=None):
    for x in range(x0, x1 + 1):
        if notch is not None and x in (notch, notch + 1):
            continue
        col = GOLD if x % 2 == 0 else GOLD_D
        c.vline(x, y0, y1, col)
        c.set(x, y0, GOLD_L if x % 2 == 0 else GOLD)


# ------------------------------------------------------------------------------------------- CPU
def cpu(tier, label):
    c = Canvas(32)
    c.box(3, 3, 28, 28, PCB, 1.35, 0.6)
    # SMD capacitors on the substrate
    for i in range(6, 26, 4):
        c.rect(i, 4, i + 1, 5, hexc("8a6d3b"))
        c.rect(i, 26, i + 1, 27, hexc("8a6d3b"))
    for i in range(8, 24, 4):
        c.rect(4, i, 5, i + 1, hexc("8a6d3b"))
        c.rect(26, i, 27, i + 1, hexc("8a6d3b"))
    # integrated heat spreader
    c.rect(7, 7, 24, 24, shade(SILVER, 0.55))
    c.vgrad(7, 7, 24, 24, hexc("e4e8ee"), hexc("9aa2ad"))
    c.bevel(7, 7, 24, 24, hexc("f7f9fb"), hexc("6e7682"))
    for (x, y) in ((7, 7), (24, 7), (7, 24), (24, 24)):
        c.set(x, y, (0, 0, 0, 0))
    # brushed highlight
    c.line(9, 9, 13, 9, alpha(WHITE, 140))
    # tier badge + laser markings
    col = TIER[tier]
    c.box(9, 11, 22, 17, col, 1.3, 0.6)
    w = text_width(label)
    c.text(16 - w // 2, 12, label, WHITE)
    c.hline(9, 19, 19, hexc("7d8591"))
    c.hline(9, 15, 21, hexc("7d8591"))
    # pin 1 marker
    c.set(4, 27, GOLD)
    c.set(4, 26, GOLD)
    c.set(5, 27, GOLD)
    return finish(c)


# ------------------------------------------------------------------------------------------- RAM
def ram(tier, gb):
    c = Canvas(32)
    label = f"{gb}GB"
    if tier <= 2:
        # bare DDR3-style module: green PCB, black chips, sticker
        c.box(1, 10, 30, 22, PCB, 1.3, 0.6)
        for x in range(3, 28, 7):
            c.box(x, 12, x + 4, 18, hexc("202227"), 1.6, 0.7)
            c.hline(x + 1, x + 3, 15, hexc("2b2e35"))
        c.rect(20, 19, 27, 20, WHITE)
        gold_fingers(c, 2, 29, 21, 22, notch=17)
        c.text(3, 19, str(gb), hexc("d6f5e2"), FONT_MICRO)
        return finish(c)
    # heat-spreader module (DDR5 style) with light bar
    col = TIER[min(6, tier)]
    rgb = gb >= 16
    for x in range(1, 31):  # light bar diffuser
        top = hsv(x / 34.0 + 0.55, 0.75, 1.0) if rgb else shade(col, 1.25)
        c.set(x, 6, mix(top, WHITE, 0.45))
        c.set(x, 7, top)
        c.set(x, 8, shade(top, 0.75))
    c.rect(1, 9, 30, 20, GUN)
    c.vgrad(1, 9, 30, 20, hexc("434a55"), hexc("23272e"))
    # angular facet like real heat spreaders
    for y in range(9, 21):
        for x in range(1, 31):
            if x + (y - 9) * 1.6 < 14 or x - (y - 9) * 1.6 > 20:
                c.set(x, y, shade(c.get(x, y), 0.8))
    c.hline(1, 30, 9, hexc("5d6672"))
    c.vline(1, 9, 20, hexc("4a525d"))
    c.vline(30, 9, 20, hexc("15181c"))
    w = text_width(label)
    c.text(16 - w // 2, 13, label, hexc("dfe4ea"))
    c.hline(16 - w // 2, 16 - w // 2 + w - 1, 18, col)
    c.rect(1, 21, 30, 22, PCB_D)
    gold_fingers(c, 2, 29, 23, 24, notch=17)
    return finish(c)


# ------------------------------------------------------------------------------------------- GPU
def fan(c, cx, cy, r, rim, blades=7, phase=0.0):
    c.disc(cx, cy, r, hexc("101216"))
    c.ring(cx, cy, r - 1.1, r, rim)
    for k in range(blades):
        a = phase + k * 2 * math.pi / blades
        for t in (0.45, 0.6, 0.75, 0.9):
            rr = (r - 1.2) * t
            x = cx + math.cos(a + t * 0.9) * rr
            y = cy + math.sin(a + t * 0.9) * rr
            c.set(int(x), int(y), hexc("3a3f48"))
    c.disc(cx, cy, max(1.2, r * 0.32), hexc("2a2e35"))
    c.set(int(cx - 0.5), int(cy - 0.5), hexc("5c636e"))


def gpu(tier):
    c = Canvas(32)
    if tier == 1:
        return integrated_gpu()
    col = {2: hexc("3b82f6"), 3: hexc("22c55e"), 4: hexc("f59e0b")}[tier]
    fans = 2 if tier < 4 else 3
    x0, x1 = 3, 30
    y0, y1 = 8 if tier == 4 else 10, 22
    # backplate edge visible on top
    c.rect(x0, y0 - 2, x1, y0 - 1, hexc("6d7480") if tier >= 3 else hexc("2a2e35"))
    c.hline(x0, x1, y0 - 2, hexc("9aa1ab") if tier >= 3 else hexc("3b4049"))
    # shroud
    c.box(x0, y0, x1, y1, hexc("23262c"), 1.5, 0.55)
    # accent cut lines
    c.line(x0 + 1, y1 - 1, x0 + 5, y0 + 1, col)
    c.line(x1 - 5, y1 - 1, x1 - 1, y0 + 4, col)
    if tier >= 3:
        for x in range(x0 + 1, x1):
            c.set(x, y0, hsv(x / 30.0 + 0.6, 0.7, 1.0) if tier == 4 else shade(col, 1.2))
    span = x1 - x0 - 2
    r = min((y1 - y0) / 2 - 0.6, span / (2 * fans) - 0.3)
    for i in range(fans):
        cx = x0 + 1.5 + span * (i + 0.5) / fans
        fan(c, cx, (y0 + y1) / 2 + 0.5, r, shade(col, 0.95) if tier == 4 else hexc("4a505a"), phase=i * 0.7)
    # bracket
    c.box(0, y0 - 2, 2, y1 + 3, SILVER, 1.2, 0.65)
    for y in range(y0, y1, 3):
        c.set(1, y, hexc("3a3f48"))
    # PCIe fingers
    gold_fingers(c, 8, 22, y1 + 1, y1 + 2, notch=11)
    return finish(c)


def integrated_gpu():
    c = Canvas(32)
    c.box(5, 5, 26, 26, hexc("1d3a5a"), 1.35, 0.6)
    c.box(9, 9, 22, 22, hexc("16181d"), 1.8, 0.7)
    c.ring(15.5, 15.5, 3.4, 4.6, hexc("38c6ff"))
    c.disc(15.5, 15.5, 1.5, hexc("38c6ff"))
    for i in range(7, 25, 3):
        c.set(i, 5, GOLD)
        c.set(i, 26, GOLD)
        c.set(5, i, GOLD)
        c.set(26, i, GOLD)
    return finish(c)


# ------------------------------------------------------------------------------------------- storage
def hdd(enterprise):
    c = Canvas(32)
    body = hexc("5a616c") if enterprise else hexc("a3aab4")
    c.box(5, 2, 26, 29, body, 1.25, 0.6, rounded=True)
    # label
    lab = hexc("1b1d22") if enterprise else hexc("f2f2ee")
    c.rect(7, 4, 24, 13, lab)
    c.hline(7, 24, 4, hexc("ff8a1f") if enterprise else hexc("3b82f6"))
    c.hline(7, 24, 5, hexc("ff8a1f") if enterprise else hexc("3b82f6"))
    t = "8TB" if enterprise else "500G"
    c.text(16 - text_width(t) // 2, 7, t, hexc("e6e9ee") if enterprise else hexc("2b2f36"))
    c.hline(9, 21, 13, hexc("9aa1ab") if not enterprise else hexc("4b525c"))
    # spindle / platter bump
    c.ring(16, 21, 4.6, 5.6, shade(body, 0.7))
    c.disc(16, 21, 4.4, shade(body, 1.12))
    c.disc(16, 21, 1.4, shade(body, 0.6))
    for (x, y) in ((7, 16), (24, 16), (7, 27), (24, 27)):
        c.set(x, y, shade(body, 0.45))
    return finish(c)


def ssd():
    c = Canvas(32)
    c.box(6, 4, 25, 27, hexc("202329"), 1.7, 0.6, rounded=True)
    for y in range(6, 26, 2):
        c.hline(8, 23, y, hexc("262a31"))
    c.rect(6, 16, 25, 19, hexc("38c6ff"))
    c.hline(6, 25, 16, hexc("8fe3ff"))
    c.text(16 - text_width("SSD") // 2, 9, "SSD", hexc("e8eef8"))
    c.text(16 - text_width("1TB") // 2, 21, "1TB", hexc("8a94a6"))
    return finish(c)


def nvme():
    c = Canvas(32)
    c.box(1, 11, 30, 20, hexc("15261c"), 1.6, 0.6)
    # screw notch
    c.disc(1.5, 15.5, 1.8, (0, 0, 0, 0))
    c.ring(1.5, 15.5, 1.8, 2.6, GOLD)
    # controller + NAND + label sticker
    c.box(5, 12, 9, 18, hexc("1c1e23"), 1.7, 0.7)
    c.box(11, 12, 24, 19, hexc("dfe3ea"), 1.1, 0.75)
    c.rect(11, 12, 24, 13, hexc("7c3aed"))
    c.text(13, 15, "2TB", hexc("2b2f36"))
    gold_fingers(c, 26, 30, 12, 19)
    for y in (15, 16):
        c.set(26, y, (0, 0, 0, 0))
        c.set(27, y, (0, 0, 0, 0))
    return finish(c)


def usb():
    c = Canvas(32)
    body = hexc("2563eb")
    c.box(10, 3, 21, 19, body, 1.35, 0.6, rounded=True)
    c.rect(12, 5, 13, 15, shade(body, 1.4))
    c.disc(15.5, 5.5, 1.3, (0, 0, 0, 0))
    c.ring(15.5, 5.5, 1.3, 2.2, shade(body, 0.6))
    c.set(18, 16, hexc("7dff9a"))
    c.box(11, 20, 20, 29, SILVER, 1.2, 0.6)
    c.rect(12, 26, 19, 29, hexc("e9edf2"))
    c.rect(13, 23, 14, 24, hexc("2a2e35"))
    c.rect(17, 23, 18, 24, hexc("2a2e35"))
    return finish(c)


# ------------------------------------------------------------------------------------------- add-in cards
def card(kind):
    c = Canvas(32)
    c.box(2, 9, 26, 21, PCB, 1.3, 0.6)
    for y in (11, 14, 17):
        c.hline(4, 12, y, PCB_T)
    if kind == "gpio":
        c.box(12, 11, 23, 16, hexc("b3261e"), 1.4, 0.6)
        for x in range(13, 23, 2):
            c.set(x, 12, GOLD_L)
            c.set(x, 14, GOLD_L)
        c.box(5, 15, 9, 19, hexc("1c1e23"), 1.7, 0.7)
        c.disc(18, 19, 1.3, hexc("ff3b2f"))
    else:
        c.box(12, 11, 21, 18, SILVER, 1.2, 0.65)
        c.text(14, 13, "WI", hexc("4b525c"))
        # antennas
        c.box(28, 1, 29, 8, hexc("1b1d22"), 1.8, 0.8)
        c.box(30, 3, 31, 8, hexc("1b1d22"), 1.8, 0.8)
    c.box(27, 7, 29, 25, SILVER, 1.2, 0.6)
    if kind == "gpio":
        c.rect(28, 11, 28, 13, hexc("b3261e"))
        c.rect(28, 16, 28, 18, hexc("b3261e"))
    gold_fingers(c, 7, 20, 22, 23, notch=10)
    return finish(c)


def blade(big):
    c = Canvas(32)
    y0 = 9 if big else 12
    y1 = 24 if big else 20
    c.rect(2, y0 - 3, 29, y0 - 1, hexc("6b727d"))
    c.hline(2, 29, y0 - 3, hexc("9aa1ab"))
    c.box(1, y0, 30, y1, hexc("2a2e35"), 1.6, 0.55)
    rows = 2 if big else 1
    rh = (y1 - y0 - 1) // rows
    for r in range(rows):
        yy = y0 + 1 + r * rh
        for i in range(4):
            x = 5 + i * 5
            c.box(x, yy, x + 3, yy + rh - 2, hexc("1b1d22"), 1.8, 0.8)
            c.set(x + 1, yy + 1, hexc("3dff86"))
            c.set(x + 2, yy + 1, hexc("38c6ff") if big else hexc("3dff86"))
        c.dots(25, yy + 1, 28, yy + rh - 2, 2, hexc("15171b"))
    c.box(0, y0, 1, y1, SILVER, 1.2, 0.6)
    c.box(30, y0, 31, y1, SILVER, 1.2, 0.6)
    return finish(c)


def tablet():
    c = Canvas(32)
    c.box(5, 2, 26, 29, hexc("1b1f27"), 1.6, 0.6, rounded=True)
    c.rect(7, 4, 24, 26, hexc("0c1220"))
    c.hgrad(7, 4, 24, 6, hexc("7c3aed"), hexc("38c6ff"))
    tiles = [hexc("38c6ff"), hexc("a855f7"), hexc("22c55e"), hexc("f59e0b"), hexc("ef4444"), hexc("3b82f6")]
    k = 0
    for row in range(3):
        for col in range(2):
            x = 9 + col * 8
            y = 9 + row * 6
            c.box(x, y, x + 5, y + 4, shade(tiles[k], 0.7), 1.4, 0.7)
            k += 1
    c.hline(13, 18, 28, hexc("4b5563"))
    return finish(c)


def hdmi_cable():
    c = Canvas(32)
    # coiled cable
    for i, (cx, cy) in enumerate(((15, 15), (16, 16), (17, 15))):
        c.ring(cx, cy, 7.2, 8.4, hexc("1d2026") if i != 1 else hexc("2a2e35"))
    c.ring(16, 16, 7.6, 8.0, hexc("3a3f48"))
    # connectors
    for (x, y) in ((2, 22), (23, 3)):
        c.box(x, y, x + 6, y + 5, hexc("2a2e35"), 1.6, 0.6)
        c.box(x + 1, y + 1, x + 5, y + 3, SILVER, 1.2, 0.7)
        c.hline(x + 2, x + 4, y + 2, GOLD)
    return finish(c)


ITEMS = {
    "cpu_orbitcore_d2": lambda: cpu(2, "D2"),
    "cpu_orbitcore_q4": lambda: cpu(3, "Q4"),
    "cpu_orbitcore_x8": lambda: cpu(4, "X8"),
    "cpu_orbitripper_16": lambda: cpu(6, "R16"),
    "ram_1gb": lambda: ram(1, 1),
    "ram_2gb": lambda: ram(2, 2),
    "ram_4gb": lambda: ram(3, 4),
    "ram_8gb": lambda: ram(4, 8),
    "ram_16gb": lambda: ram(5, 16),
    "ram_32gb": lambda: ram(6, 32),
    "gpu_integrated": lambda: gpu(1),
    "gpu_orbitforce_gt": lambda: gpu(2),
    "gpu_orbitforce_gx": lambda: gpu(3),
    "gpu_orbitforce_titan": lambda: gpu(4),
    "hdd_500gb": lambda: hdd(False),
    "enterprise_hdd_8tb": lambda: hdd(True),
    "ssd_1tb": ssd,
    "nvme_2tb": nvme,
    "usb_flash_drive": usb,
    "redstone_gpio_card": lambda: card("gpio"),
    "wifi_card": lambda: card("wifi"),
    "server_blade_x8": lambda: blade(False),
    "server_blade_x16": lambda: blade(True),
    "orbittech_tablet": tablet,
    "hdmi_cable": hdmi_cable,
}


# ------------------------------------------------------------------------------------------- slot ghosts
GHOST = hexc("4a5465")
GHOST_D = hexc("343c49")


def ghost(kind):
    """16x16 silhouette drawn inside empty typed slots."""
    c = Canvas(16)
    g, d = GHOST, GHOST_D
    if kind == "cpu":
        c.rect(3, 3, 12, 12, d)
        c.rect(5, 5, 10, 10, g)
        for i in range(4, 12, 2):
            c.set(i, 1, g); c.set(i, 2, g); c.set(i, 13, g); c.set(i, 14, g)
            c.set(1, i, g); c.set(2, i, g); c.set(13, i, g); c.set(14, i, g)
    elif kind == "ram":
        c.rect(0, 3, 15, 4, g)
        c.rect(0, 5, 15, 10, d)
        for x in range(2, 14, 4):
            c.rect(x, 6, x + 2, 9, g)
        for x in range(1, 15, 2):
            c.set(x, 11, g)
            c.set(x, 12, g)
        c.set(9, 11, (0, 0, 0, 0)); c.set(9, 12, (0, 0, 0, 0))
    elif kind == "gpu":
        c.rect(1, 4, 14, 11, d)
        c.ring(5, 7.5, 1.6, 3.2, g)
        c.ring(10.5, 7.5, 1.6, 3.2, g)
        c.rect(0, 3, 0, 13, g)
        for x in range(4, 11, 2):
            c.set(x, 12, g)
    elif kind == "storage":
        c.rect(3, 1, 12, 14, d)
        c.rect(4, 2, 11, 6, g)
        c.ring(7.5, 10.5, 1.0, 2.6, g)
    elif kind == "expansion":
        c.rect(1, 4, 12, 10, d)
        c.rect(5, 5, 9, 8, g)
        c.rect(13, 2, 14, 13, g)
        for x in range(3, 11, 2):
            c.set(x, 11, g)
            c.set(x, 12, g)
    elif kind == "usb":
        c.rect(5, 1, 10, 9, d)
        c.rect(6, 10, 9, 14, g)
        c.set(7, 12, d); c.set(8, 12, d)
    elif kind == "blade":
        c.rect(0, 4, 15, 11, d)
        for x in range(2, 11, 3):
            c.rect(x, 6, x + 1, 9, g)
        c.dots(12, 6, 14, 9, 2, g)
    return c


GHOSTS = ["cpu", "ram", "gpu", "storage", "expansion", "usb", "blade"]


# ------------------------------------------------------------------------------------------- GUI icons
ICON = hexc("ffffff")


def icon_canvas():
    return Canvas(12)


def ic_power(c):
    c.ring(5.5, 6.5, 3.4, 4.6, ICON)
    c.rect(5, 0, 6, 6, ICON)
    c.rect(4, 1, 7, 2, (0, 0, 0, 0))
    c.rect(5, 0, 6, 6, ICON)
    for y in range(1, 4):
        c.set(4, y, (0, 0, 0, 0)); c.set(7, y, (0, 0, 0, 0))


def ic_stop(c):
    c.rect(2, 2, 9, 9, ICON)


def ic_reset(c):
    c.ring(5.5, 6, 3.3, 4.5, ICON)
    c.rect(6, 0, 11, 4, (0, 0, 0, 0))
    c.rect(7, 1, 10, 1, ICON)
    c.rect(10, 1, 10, 4, ICON)


def ic_camera(c):
    c.rect(0, 3, 11, 10, ICON)
    c.rect(3, 1, 7, 2, ICON)
    c.ring(5.5, 6.5, 1.5, 2.6, (0, 0, 0, 0))
    c.disc(5.5, 6.5, 1.4, ICON)
    c.set(10, 4, (0, 0, 0, 0))


def ic_disc(c):
    c.disc(5.5, 5.5, 5.2, ICON)
    c.disc(5.5, 5.5, 1.4, (0, 0, 0, 0))
    c.ring(5.5, 5.5, 2.6, 3.2, alpha(ICON, 120))


def ic_usb(c):
    c.rect(3, 0, 8, 6, ICON)
    c.rect(4, 7, 7, 11, ICON)
    c.set(5, 9, (0, 0, 0, 0)); c.set(6, 9, (0, 0, 0, 0))


def ic_library(c):
    for i, x in enumerate((0, 4, 8)):
        c.rect(x, 2 + (i % 2), x + 2, 11, ICON)
    c.set(1, 5, (0, 0, 0, 0)); c.set(5, 6, (0, 0, 0, 0)); c.set(9, 5, (0, 0, 0, 0))


def ic_focus(c):
    for (x, y, dx, dy) in ((0, 0, 1, 1), (11, 0, -1, 1), (0, 11, 1, -1), (11, 11, -1, -1)):
        for k in range(4):
            c.set(x + dx * k, y, ICON)
            c.set(x, y + dy * k, ICON)


def ic_gear(c):
    c.disc(5.5, 5.5, 4.2, ICON)
    for (x, y) in ((5, 0), (6, 0), (5, 11), (6, 11), (0, 5), (0, 6), (11, 5), (11, 6), (1, 1), (10, 1), (1, 10), (10, 10)):
        c.set(x, y, ICON)
    c.disc(5.5, 5.5, 1.6, (0, 0, 0, 0))


def ic_eye(c):
    for x in range(12):
        h = int(round(math.sin(x / 11 * math.pi) * 3.2))
        c.vline(x, 6 - h, 5 + h, ICON)
    c.disc(5.5, 5.5, 2.2, (0, 0, 0, 0))
    c.disc(5.5, 5.5, 1.1, ICON)


def ic_lock(c):
    c.rect(1, 5, 10, 11, ICON)
    c.ring(5.5, 4.5, 2.4, 3.6, ICON)
    c.rect(1, 5, 10, 11, ICON)
    c.rect(5, 7, 6, 9, (0, 0, 0, 0))


def ic_user_plus(c):
    c.disc(4, 3, 2.4, ICON)
    c.rect(0, 7, 7, 11, ICON)
    c.rect(9, 3, 9, 9, ICON)
    c.rect(7, 6, 11, 6, ICON)


def ic_user_minus(c):
    c.disc(4, 3, 2.4, ICON)
    c.rect(0, 7, 7, 11, ICON)
    c.rect(8, 6, 11, 6, ICON)


def ic_cart(c):
    c.rect(0, 1, 1, 1, ICON)
    c.rect(2, 2, 11, 6, ICON)
    c.rect(3, 7, 10, 7, ICON)
    c.disc(4, 10, 1.3, ICON)
    c.disc(9, 10, 1.3, ICON)


def ic_search(c):
    c.ring(4.5, 4.5, 2.6, 3.8, ICON)
    for k in range(4):
        c.set(7 + k, 7 + k, ICON)
        c.set(8 + k, 7 + k, ICON)


def ic_download(c):
    c.rect(5, 0, 6, 6, ICON)
    for k in range(4):
        c.hline(2 + k, 9 - k, 4 + k, ICON)
    c.rect(0, 10, 11, 11, ICON)
    c.rect(0, 8, 1, 11, ICON)
    c.rect(10, 8, 11, 11, ICON)


def ic_folder(c):
    c.rect(0, 2, 4, 3, ICON)
    c.rect(0, 3, 11, 10, ICON)
    c.hline(1, 10, 4, alpha(ICON, 90))


def ic_check(c):
    for k in range(4):
        c.set(1 + k, 5 + k, ICON); c.set(1 + k, 6 + k, ICON)
    for k in range(7):
        c.set(4 + k, 8 - k, ICON); c.set(4 + k, 9 - k, ICON)


def ic_cross(c):
    for k in range(10):
        c.set(1 + k, 1 + k, ICON)
        c.set(1 + k, 10 - k, ICON)
        c.set(min(10, 2 + k), 1 + k, ICON)
        c.set(min(10, 2 + k), 10 - k, ICON)


def ic_warn(c):
    for y in range(11):
        half = y // 2
        c.hline(5 - half, 6 + half, y, ICON)
    c.rect(5, 3, 6, 7, (0, 0, 0, 0))
    c.rect(5, 9, 6, 9, (0, 0, 0, 0))


def ic_monitor(c):
    c.rect(0, 0, 11, 8, ICON)
    c.rect(1, 1, 10, 7, (0, 0, 0, 0))
    c.rect(5, 9, 6, 10, ICON)
    c.rect(3, 11, 8, 11, ICON)


def ic_back(c):
    c.rect(2, 5, 11, 6, ICON)
    for k in range(5):
        c.set(1 + k, 5 - k, ICON); c.set(1 + k, 6 + k, ICON)
        c.set(2 + k, 5 - k, ICON); c.set(2 + k, 6 + k, ICON)


def ic_trash(c):
    c.rect(0, 2, 11, 3, ICON)
    c.rect(4, 0, 7, 1, ICON)
    c.rect(1, 4, 10, 11, ICON)
    for x in (3, 5, 7):
        c.vline(x + (1 if x == 7 else 0), 5, 10, (0, 0, 0, 0))


def ic_chip(c):
    c.rect(2, 2, 9, 9, ICON)
    c.rect(4, 4, 7, 7, (0, 0, 0, 0))
    for i in range(3, 9, 2):
        c.set(i, 0, ICON); c.set(i, 11, ICON); c.set(0, i, ICON); c.set(11, i, ICON)


def ic_memory(c):
    c.rect(0, 2, 11, 8, ICON)
    for x in range(2, 10, 3):
        c.rect(x, 4, x + 1, 6, (0, 0, 0, 0))
    for x in range(0, 12, 2):
        c.set(x, 10, ICON)


def ic_drive(c):
    c.rect(0, 2, 11, 9, ICON)
    c.hline(1, 10, 6, (0, 0, 0, 0))
    c.set(9, 8, (0, 0, 0, 0))


def ic_wifi(c):
    c.ring(5.5, 11, 9.4, 10.6, ICON)
    c.ring(5.5, 11, 5.6, 6.8, ICON)
    c.disc(5.5, 10, 1.6, ICON)
    c.rect(0, 11, 11, 11, (0, 0, 0, 0))


def ic_plus(c):
    c.rect(5, 1, 6, 10, ICON)
    c.rect(1, 5, 10, 6, ICON)


def ic_minus(c):
    c.rect(1, 5, 10, 6, ICON)


def ic_box(c):
    c.rect(0, 3, 11, 11, ICON)
    c.rect(1, 0, 10, 2, ICON)
    c.hline(1, 10, 3, (0, 0, 0, 0))
    c.rect(4, 5, 7, 6, (0, 0, 0, 0))


def ic_rocket(c):
    for y in range(9):
        w = min(2, y // 2)
        c.hline(5 - w, 6 + w, y, ICON)
    c.rect(2, 7, 9, 8, ICON)
    c.rect(4, 9, 7, 9, ICON)
    c.set(5, 10, ICON); c.set(6, 11, ICON)
    c.disc(5.5, 4, 0.9, (0, 0, 0, 0))


def ic_info(c):
    c.disc(5.5, 5.5, 5.4, ICON)
    c.rect(5, 2, 6, 3, (0, 0, 0, 0))
    c.rect(5, 5, 6, 9, (0, 0, 0, 0))


def ic_close(c):
    for k in range(8):
        c.set(2 + k, 2 + k, ICON)
        c.set(2 + k, 9 - k, ICON)
        c.set(min(9, 3 + k), 2 + k, ICON)
        c.set(min(9, 3 + k), 9 - k, ICON)


def ic_keyboard(c):
    c.rect(0, 2, 11, 9, ICON)
    for y in (4, 6):
        for x in range(2, 10, 2):
            c.set(x, y, (0, 0, 0, 0))
    c.hline(3, 8, 8, (0, 0, 0, 0))


def ic_server(c):
    for y in (0, 4, 8):
        c.rect(0, y, 11, y + 2, ICON)
        c.set(2, y + 1, (0, 0, 0, 0))
        c.set(4, y + 1, (0, 0, 0, 0))


def ic_play_tri(c):
    for x in range(8):
        h = (8 - x) // 2
        c.vline(2 + x, 5 - h, 6 + h, ICON)


ICONS = [
    ("power", ic_power), ("stop", ic_stop), ("play", ic_play_tri), ("reset", ic_reset), ("camera", ic_camera),
    ("disc", ic_disc), ("usb", ic_usb), ("library", ic_library), ("focus", ic_focus), ("gear", ic_gear),
    ("eye", ic_eye), ("lock", ic_lock), ("user_plus", ic_user_plus), ("user_minus", ic_user_minus), ("cart", ic_cart),
    ("search", ic_search), ("download", ic_download), ("folder", ic_folder), ("check", ic_check), ("cross", ic_cross),
    ("warn", ic_warn), ("monitor", ic_monitor), ("back", ic_back), ("trash", ic_trash), ("chip", ic_chip),
    ("memory", ic_memory), ("drive", ic_drive), ("wifi", ic_wifi), ("plus", ic_plus), ("minus", ic_minus),
    ("box", ic_box), ("rocket", ic_rocket), ("info", ic_info), ("close", ic_close), ("keyboard", ic_keyboard),
    ("server", ic_server),
]


def icon_atlas():
    """12x12 white glyphs on a 16px grid, 8 per row; tinted at draw time."""
    cols = 8
    rows = (len(ICONS) + cols - 1) // cols
    atlas = Canvas(128, 128)
    for i, (_, fn) in enumerate(ICONS):
        c = icon_canvas()
        fn(c)
        atlas.paste(c, (i % cols) * 16 + 2, (i // cols) * 16 + 2)
    return atlas, [n for n, _ in ICONS]
