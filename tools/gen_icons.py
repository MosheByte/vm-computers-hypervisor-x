"""Item icons, slot ghost icons, the GUI icon atlas and the HyperVisor X logo, all hand-built pixel art.

Item icons are 16x16 (vanilla res), lit from the top-left. Edges follow vanilla's "selective
outline" style: light materials get a bright top/left rim and a deep bottom/right rim, mid-tone
materials (PCB, plastics, tier colours) get a rim in the darkest shade of their own colour. That way
every silhouette reads on the vanilla #8B8B8B slot and on the dark #13161B panels. No text baked into
items: you tell tiers apart by colour, size, silhouette and how much detail there is.
"""

from PIL import Image

from pixelkit import Canvas, hexc, mix


def mat(*hexes):
    """A material: (highlight, base, shadow, deep) shades."""
    return tuple(hexc(h) for h in hexes)


SILVER = mat("f2f4f7", "c4cad2", "939ba7", "545c68")
GUN = mat("737b87", "4b525d", "333840", "1c1f25")
PLASTIC = mat("4d535e", "2c3139", "1f2228", "0f1115")
PCB = mat("4fae70", "2e7d4d", "1f5c38", "0f3a22")
SUB_DARK = mat("646b77", "343941", "262a31", "121418")
GOLD = mat("fde9a8", "e9bd5a", "b0812b", "6b4a12")
LABEL = mat("ffffff", "eef0ec", "c9cdd3", "8e949c")

TIER = {
    "blue": mat("9cc4ff", "3b82f6", "2559b8", "173a7a"),
    "green": mat("8af0b0", "22c55e", "168a42", "0c5428"),
    "purple": mat("dcbcff", "a855f7", "7a33c4", "4a1c82"),
    "amber": mat("fdd68f", "f59e0b", "b86f06", "704203"),
    "red": mat("ffb0b0", "ef4444", "b42828", "6e1515"),
    "cyan": mat("b8f1ff", "3bd1ff", "1f97c4", "0f5c7c"),
    "orange": mat("ffc68c", "ff8a1f", "c4620c", "7a3a05"),
    "violet": mat("c4b5ff", "7c5cff", "5a3fd6", "33228a"),
}

LED_GREEN = hexc("5dff9a")
LED_CYAN = hexc("7fe6ff")


def pal(accent="blue", **extra):
    """Shared palette for hand-authored grids (see Canvas.paint)."""
    a = TIER[accent]
    p = {
        "W": SILVER[0], "S": SILVER[1], "s": SILVER[2], "Z": SILVER[3],
        "g": GUN[0], "G": GUN[1], "K": GUN[2], "k": PLASTIC[2], "O": PLASTIC[3],
        "Q": PCB[0], "P": PCB[1], "p": PCB[2], "q": PCB[3],
        "Y": GOLD[0], "y": GOLD[1], "u": GOLD[2], "U": GOLD[3],
        "w": LABEL[0], "L": LABEL[1], "l": LABEL[2],
        "A": a[0], "a": a[1], "b": a[2], "B": a[3],
        "e": LED_GREEN, "c": LED_CYAN,
    }
    p.update(extra)
    return p


def sprite(rows, palette):
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), rows
    c = Canvas(16)
    c.paint(rows, palette)
    return c


# ------------------------------------------------------------------------------------------- shared parts
def panel(c, x0, y0, x1, y1, m):
    """Mid-tone slab: rim in the material's deep shade, highlight inside top/left, shadow inside bottom/right."""
    hi, base, sh, deep = m
    c.rect(x0, y0, x1, y1, deep)
    c.rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, base)
    c.hline(x0 + 1, x1 - 1, y0 + 1, hi)
    c.vline(x0 + 1, y0 + 1, y1 - 1, hi)
    c.hline(x0 + 2, x1 - 1, y1 - 1, sh)
    c.vline(x1 - 1, y0 + 2, y1 - 1, sh)


def fingers(c, x0, x1, y, rows=2, notch=None):
    """Gold edge-connector fingers (vertical stripes) with an optional key-notch column."""
    for x in range(x0, x1 + 1):
        if notch is not None and x == notch:
            continue
        lit = (x - x0) % 2 == 0
        for r in range(rows):
            if r == 0:
                col = GOLD[0] if lit else GOLD[2]
            elif r == rows - 1:
                col = GOLD[2] if lit else GOLD[3]
            else:
                col = GOLD[1] if lit else GOLD[2]
            c.set(x, y + r, col)


def chip(c, x, y, w, h):
    """Black IC package: lit top edge, dark base."""
    c.rect(x, y, x + w - 1, y + h - 1, PLASTIC[1])
    c.hline(x, x + w - 1, y, GUN[1])
    c.set(x, y, GUN[0])
    if h > 2:
        c.hline(x, x + w - 1, y + h - 1, PLASTIC[2])


def bracket(c, x, y0, y1, ports=()):
    """1px PCIe slot bracket seen edge-on, with vent slots."""
    c.vline(x, y0, y1, SILVER[1])
    c.set(x, y0, SILVER[0])
    for y in range(y0 + 2, y1 - 1, 2):
        c.set(x, y, SILVER[2])
    for y, col in ports:
        c.set(x, y, col)
    c.set(x, y1, SILVER[3])


# ------------------------------------------------------------------------------------------- CPU
def cpu(n, accent, caps, dark=False):
    """Top view: substrate + silver IHS with a tier badge. Package size, badge colour and SMD cap count go up
    each tier, and the flagship sits on a black substrate."""
    c = Canvas(16)
    x0 = (16 - n) // 2
    x1 = x0 + n - 1
    sub = SUB_DARK if dark else PCB
    panel(c, x0, x0, x1, x1, sub)
    i0, i1 = x0 + 2, x1 - 2
    c.rect(i0, i0, i1, i1, SILVER[1])
    c.hline(i0, i1 - 1, i0, SILVER[0])
    c.vline(i0, i0, i1 - 1, SILVER[0])
    c.hline(i0 + 1, i1, i1, SILVER[2])
    c.vline(i1, i0 + 1, i1, SILVER[2])
    for (x, y) in ((i0, i0), (i1, i0), (i0, i1), (i1, i1)):
        c.set(x, y, sub[1])
    a = TIER[accent]
    bx0, bx1 = i0 + 2, i1 - 2
    rows = 3 if n >= 15 else 2
    for r in range(rows):
        c.hline(bx0, bx1, i0 + 2 + r, a[2] if r == rows - 1 else a[1])
    c.set(bx0, i0 + 2, a[0])
    # laser-etched model line under the badge
    c.hline(bx0, bx1 - 2, i0 + 3 + rows, SILVER[2])
    # pin-1 marker and SMD caps on the substrate band
    c.set(x0 + 1, x1 - 1, GOLD[1])
    for k in range(caps):
        c.set(i0 + 1 + k * 2, x1 - 1, GOLD[2])
        if dark:
            c.set(x1 - 1, i0 + 1 + k * 2, GOLD[2])
    return c


# ------------------------------------------------------------------------------------------- RAM
CHIP_X = (2, 5, 8, 11)


def ram_bare(two_rows):
    """Stick without heat spreader: green PCB, black DRAM packages, gold fingers (1GB: 4 chips; 2GB: 8 + label)."""
    c = Canvas(16)
    top = 3 if two_rows else 4
    bottom = 12 if two_rows else 11
    panel(c, 0, top, 15, bottom, PCB)
    for x in CHIP_X:
        chip(c, x, top + 2, 2, 2 if two_rows else 3)
        if two_rows:
            chip(c, x, top + 5, 2, 2)
    if two_rows:
        b = TIER["blue"]
        c.rect(13, top + 2, 14, top + 6, b[1])
        c.vline(13, top + 2, top + 5, b[0])
        c.hline(13, 14, top + 6, b[2])
    fingers(c, 1, 14, bottom - 1, 2, notch=7)
    return c


RGB = [hexc(h) for h in ("ff4d6d", "ff8a3d", "ffd23f", "5dff9a", "3bd1ff", "7c5cff", "e05cff")]


def ram_spreader(style):
    """Heat-spreader stick. 4: bare aluminium + green stripe; 8: purple light bar; 16: taller, finned, amber bar;
    32: tallest, RGB bar and red accent."""
    c = Canvas(16)
    if style == 4:
        a = TIER["green"]
        c.rect(0, 3, 15, 10, SILVER[1])
        c.hline(0, 14, 3, SILVER[0])
        c.vline(0, 3, 10, SILVER[0])
        c.hline(1, 15, 10, SILVER[3])
        c.vline(15, 4, 10, SILVER[3])
        c.hline(1, 14, 9, SILVER[2])
        c.hline(1, 14, 6, a[1])
        c.hline(1, 14, 7, a[2])
        c.set(1, 6, a[0])
        c.set(15, 3, (0, 0, 0, 0))
        c.set(14, 3, SILVER[2])
        c.set(15, 4, SILVER[3])
        for x in (10, 12):
            c.vline(x, 4, 5, SILVER[2])
        c.hline(0, 15, 11, PCB[3])
        fingers(c, 1, 14, 11, 2, notch=7)
        return c
    a = TIER[{8: "purple", 16: "amber", 32: "red"}[style]]
    bar_top = 2 if style == 8 else 1
    bar_rows = 3 if style == 32 else 2
    sp_top = bar_top + bar_rows
    for x in range(16):
        if style == 32:
            t = x / 15 * (len(RGB) - 1)
            k = min(int(t), len(RGB) - 2)
            col = mix(RGB[k], RGB[k + 1], t - k)
            c.set(x, bar_top, mix(col, (255, 255, 255, 255), 0.55))
            c.set(x, bar_top + 1, col)
            c.set(x, bar_top + 2, mix(col, (40, 30, 60, 255), 0.4))
        else:
            c.set(x, bar_top, a[0])
            c.set(x, bar_top + 1, a[1])
    if style != 32:
        c.set(15, bar_top, a[1])
        c.set(15, bar_top + 1, a[2])
    y1 = 11
    c.rect(0, sp_top, 15, y1, GUN[1])
    c.hline(0, 14, sp_top, GUN[0])
    c.vline(0, sp_top, y1, GUN[0])
    c.hline(1, 15, y1, GUN[3])
    c.vline(15, sp_top, y1, GUN[3])
    c.hline(1, 14, y1 - 1, GUN[2])
    if style == 8:
        for y in range(sp_top + 1, y1):
            for x in range(1, 15):
                if x - (y - sp_top) * 2 > 8:
                    c.set(x, y, GUN[2])
        c.hline(2, 5, sp_top + 2, a[1])
    elif style == 16:
        for x in range(2, 14, 2):
            c.vline(x, sp_top + 1, sp_top + 3, GUN[2])
        c.hline(1, 14, sp_top + 5, a[2])
        c.set(1, sp_top + 5, a[1])
    else:
        for x in range(2, 14, 2):
            c.vline(x, sp_top + 1, sp_top + 2, GUN[2])
        c.hline(1, 14, sp_top + 4, a[1])
        c.hline(1, 14, sp_top + 5, a[2])
        c.set(1, sp_top + 4, a[0])
    c.hline(0, 15, y1 + 1, PCB[3])
    fingers(c, 1, 14, y1 + 1, 2, notch=7)
    return c


# ------------------------------------------------------------------------------------------- GPU
FAN6 = [".OOOO.", "OBBkkO", "OkhHBO", "OBHhkO", "OkkBBO", ".OOOO."]
FAN5 = [".OOO.", "OkBkO", "OBhBO", "OkBkO", ".OOO."]
FAN_PAL = {"O": hexc("15171b"), "k": hexc("23272e"), "B": hexc("6a727e"), "h": hexc("aab2bd"), "H": hexc("7d8591")}


def shroud(c, x0, y0, x1, y1, a):
    c.rect(x0, y0, x1, y1, GUN[1])
    c.vline(x0, y0, y1, GUN[0])
    c.hline(x0 + 1, x1, y1, PLASTIC[3])
    c.vline(x1, y0 + 1, y1, GUN[3])
    c.hline(x0, x1, y0, a[1])
    c.set(x0, y0, a[0])


def gpu(kind):
    """Side view, fan side up. Fan count 0/1/2/3 and card length grow per tier; accent colour on the shroud."""
    c = Canvas(16)
    if kind == "integrated":
        # low-profile passive card: no fan, finned aluminium heatsink
        bracket(c, 2, 6, 13)
        panel(c, 3, 8, 13, 11, PCB)
        c.rect(4, 5, 11, 9, SILVER[1])
        c.hline(4, 10, 5, SILVER[0])
        c.vline(4, 5, 8, SILVER[0])
        for x in (6, 8, 10):
            c.vline(x, 6, 8, SILVER[2])
        c.vline(11, 5, 9, SILVER[3])
        c.hline(4, 11, 9, SILVER[3])
        c.set(12, 10, LED_CYAN)
        fingers(c, 5, 12, 12, 2, notch=8)
        return c
    a = TIER[{"gt": "blue", "gx": "green", "titan": "amber"}[kind]]
    if kind == "gt":
        bracket(c, 1, 3, 13)
        shroud(c, 2, 4, 13, 11, a)
        c.paint(FAN6, FAN_PAL, 3, 5)
        c.rect(10, 5, 12, 10, PLASTIC[3])
        for x in (10, 12):
            c.vline(x, 5, 10, SILVER[2])
            c.set(x, 5, SILVER[1])
        fingers(c, 5, 12, 12, 2, notch=7)
    elif kind == "gx":
        bracket(c, 1, 2, 13)
        c.hline(2, 15, 3, SILVER[2])
        c.set(2, 3, SILVER[1])
        shroud(c, 2, 4, 15, 11, a)
        c.paint(FAN6, FAN_PAL, 3, 5)
        c.paint(FAN6, FAN_PAL, 9, 5)
        fingers(c, 5, 13, 12, 2, notch=7)
    else:
        bracket(c, 0, 1, 13)
        c.hline(1, 15, 2, SILVER[2])
        c.set(1, 2, SILVER[1])
        shroud(c, 1, 3, 15, 10, a)
        for x in (1, 6, 11):
            c.paint(FAN5, FAN_PAL, x, 4)
        c.hline(2, 14, 9, a[2])
        fingers(c, 4, 13, 11, 2, notch=6)
    return c


# ------------------------------------------------------------------------------------------- storage
HDD = [
    "................",
    "................",
    ".WWWWWWWWWWWWWS.",
    ".SZSAaaaaaaaSZs.",
    ".SSSLLLLLLLLSSs.",
    ".SSSLllllLLLSSs.",
    ".SSSLLLLLLLLSSs.",
    ".SSSllllllllSSs.",
    ".SSSSSSSSSSSSSs.",
    ".SZSSSSSSSSSSZs.",
    ".ZssssssOOOOOOZ.",
    ".ZsZssssOyyOyyZ.",
    ".ZZZZZZZZZZZZZZ.",
    "................",
    "................",
    "................",
]


def hdd(enterprise):
    """3/4 view of a 3.5" drive: lid with corner screws and a striped label, SATA port with gold pins at the front.
    The enterprise helium drive is darker with an orange stripe."""
    p = pal("orange" if enterprise else "blue")
    if enterprise:
        p.update({"W": hexc("a3abb6"), "S": hexc("646c78"), "s": hexc("4c535d"), "Z": hexc("2b3037"),
                  "L": hexc("2c3139"), "l": hexc("4a515b")})
    return sprite(HDD, p)


SSD = [
    "................",
    "................",
    "..gggggggggggg..",
    "..gGGGGGGGGGGK..",
    "..gGAaaaaaaaGK..",
    "..gGbbbbbbbbGK..",
    "..gGkkkkkkkkGK..",
    "..gGkwwkkkkkGK..",
    "..gGkkkkkkkkGK..",
    "..gGGGGGGGGGGK..",
    "..KKKKKOyyOyyO..",
    "..OOOOOOOOOOOO..",
    "................",
    "................",
    "................",
    "................",
]


def ssd():
    """Slim 2.5" enclosure: graphite case, cyan label band, thin SATA edge."""
    return sprite(SSD, pal("cyan"))


NVME = [
    "................",
    "................",
    "................",
    "................",
    "................",
    ".gggggggggggg...",
    "uKgGGGAaaaaKKYyy",
    ".ukkkkLLLLLgGuuU",
    ".ukkkkLlllLkkYy.",
    ".ukkkkLLLLLkkuuU",
    "uKOOOOlllllKKYyy",
    ".OOOOOOOOOOOO...",
    "................",
    "................",
    "................",
    "................",
]


def nvme():
    """M.2 2280 stick: screw half-moon, NAND package, label, controller, gold fingers with the key notch."""
    return sprite(NVME, pal("violet"))


USB = [
    "................",
    "................",
    "............W...",
    "...........WKS..",
    "..........WSSZ..",
    ".........WKSZ...",
    ".......KKSSZ....",
    "......AAKKZ.....",
    ".....AAaaKK.....",
    "....AAacbB......",
    "...AAaabB.......",
    "..AAaabB........",
    ".AaaabB.........",
    "..abbB..........",
    "...bb...........",
    "................",
]


def usb():
    """45-degree USB stick: blue body with activity LED, dark collar, silver plug with its two holes."""
    return sprite(USB, pal("blue"))


# ------------------------------------------------------------------------------------------- add-in cards
GPIO = [
    "................",
    "................",
    "........wr......",
    "........rR......",
    "........uU......",
    "........uU..WS..",
    "........uU..Ss..",
    ".qqqqqqquUqqSs..",
    ".qQGGGQQQQQpSr..",
    ".qQkkkPPPPPpSs..",
    ".qQbybybybypSr..",
    ".qQBBBBBBBBpSs..",
    ".qqqqqqqqqqqSs..",
    "............Ss..",
    "............ZZ..",
    "................",
]


def gpio_card():
    """PCIe card with a red pin header, a microcontroller and a redstone-torch status LED."""
    p = pal("red", r=hexc("ff2a1a"), R=hexc("c41e10"), w=hexc("ffc2b8"))
    c = sprite(GPIO, p)
    fingers(c, 3, 10, 13, 2, notch=7)
    return c


WIFI = [
    "................",
    "........c...g...",
    ".......c..c.G.g.",
    ".......c.c..G.G.",
    ".......c.c..G.G.",
    ".......c..c.G.G.",
    "........c...y.y.",
    ".qqqqqqqqqqqWSu.",
    ".qQQQQQQQQQpSs..",
    ".qQWWWWsQQQpSs..",
    ".qQWSSSsQGGpSs..",
    ".qQWSSSsQkkpSs..",
    ".qqZZZZZqqqqSs..",
    "............Ss..",
    "............ZZ..",
    "................",
]


def wifi_card():
    """PCIe Wi-Fi card: RF shield can, two antennas on the bracket, signal waves."""
    c = sprite(WIFI, pal("cyan"))
    fingers(c, 3, 10, 13, 2, notch=7)
    return c


def blade(big):
    """Rack server front panel (1U for x8, 2U for x16): lid edge, rack ears, drive bays with LEDs."""
    c = Canvas(16)
    y0 = 3 if big else 5
    y1 = 13 if big else 10
    c.hline(1, 14, y0 - 2, SILVER[0])
    c.hline(1, 14, y0 - 1, SILVER[2])
    c.rect(1, y0, 14, y1, PLASTIC[1])
    c.hline(1, 14, y0, GUN[0])
    c.hline(1, 14, y1, PLASTIC[3])
    for x in (0, 15):
        c.vline(x, y0, y1, SILVER[1] if x == 0 else SILVER[2])
        c.set(x, y0, SILVER[0] if x == 0 else SILVER[1])
        c.set(x, y1, SILVER[3])
        c.set(x, y0 + 2, SILVER[3])
        if big:
            c.set(x, y1 - 2, SILVER[3])
    rows = 2 if big else 1
    for r in range(rows):
        top = y0 + 1 + r * 5
        for i in range(4):
            x = 2 + i * 3
            c.rect(x, top, x + 1, top + 3, GUN[1])
            c.vline(x, top, top + 3, GUN[0])
            c.set(x + 1, top, LED_GREEN if (i + r) % 3 else LED_CYAN)
            c.set(x + 1, top + 3, GUN[2])
        c.vline(14, top, top + 3, PLASTIC[3])
    c.set(14, y0 + 1, LED_CYAN)
    return c


TABLET = [
    "................",
    "..gggggggggggg..",
    "..gGGGGkGGGGGO..",
    "..gGvvvvvvccGO..",
    "..gGnnnnnnnnGO..",
    "..gGnCCnVVnEGO..",
    "..gGncCnvVneGO..",
    "..gGnnnnnnnnGO..",
    "..gGnRRnAAnBGO..",
    "..gGnrRnaAnbGO..",
    "..gGnnnnnnnnGO..",
    "..gGnFFnTTnMGO..",
    "..gGnfFntTnmGO..",
    "..gGGGGGGGGGGO..",
    "..gGGGGssGGGGO..",
    "..OOOOOOOOOOOO..",
]


def tablet():
    """OrbitTech tablet: dark bezel, status bar and a grid of app tiles."""
    p = pal("violet", n=hexc("101826"), v=TIER["violet"][1], c=TIER["cyan"][1])
    tiles = {"C": "cyan", "V": "violet", "E": "green", "R": "red", "A": "amber", "B": "blue", "F": "purple",
             "T": "cyan", "M": "orange"}
    for ch, t in tiles.items():
        p[ch] = TIER[t][1]
        p[ch.lower()] = TIER[t][0]
    return sprite(TABLET, p)


HDMI = [
    ".WSs............",
    "WSKSZ...........",
    "gGGGK...........",
    "gGKKk...........",
    ".gKk.gggGGGG....",
    "..gGgKkkkKGGG...",
    "..gK.......GGK..",
    "..gGk......gGK..",
    "..gK........gK..",
    "..gGK......ggK..",
    "..GGG......gGKk.",
    "...GGGgggggGgKk.",
    "....KGGGGGk.gGGK",
    "...........gGKKk",
    "...........WSKSZ",
    "............SsZ.",
]


def hdmi_cable():
    """Coiled HDMI cable with a plug on each end."""
    return sprite(HDMI, pal())


ITEMS = {
    "cpu_orbitcore_d2": lambda: cpu(12, "blue", 0),
    "cpu_orbitcore_q4": lambda: cpu(13, "green", 2),
    "cpu_orbitcore_x8": lambda: cpu(14, "purple", 3),
    "cpu_orbitripper_16": lambda: cpu(15, "red", 4, dark=True),
    "ram_1gb": lambda: ram_bare(False),
    "ram_2gb": lambda: ram_bare(True),
    "ram_4gb": lambda: ram_spreader(4),
    "ram_8gb": lambda: ram_spreader(8),
    "ram_16gb": lambda: ram_spreader(16),
    "ram_32gb": lambda: ram_spreader(32),
    "gpu_integrated": lambda: gpu("integrated"),
    "gpu_orbitforce_gt": lambda: gpu("gt"),
    "gpu_orbitforce_gx": lambda: gpu("gx"),
    "gpu_orbitforce_titan": lambda: gpu("titan"),
    "hdd_500gb": lambda: hdd(False),
    "enterprise_hdd_8tb": lambda: hdd(True),
    "ssd_1tb": ssd,
    "nvme_2tb": nvme,
    "usb_flash_drive": usb,
    "redstone_gpio_card": gpio_card,
    "wifi_card": wifi_card,
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
# 12x12 glyphs, hard white pixels on transparent (UiKit.icon tints them at draw time). Same 1px outline
# stroke everywhere, and glyphs that need a centred 1px stem use an odd 11px box anchored top-left.
ICON = hexc("ffffff")

GLYPHS = {
    "power": [
        ".....#......",
        "..#..#..#...",
        ".#...#...#..",
        ".#...#...#..",
        "#....#....#.",
        "#....#....#.",
        "#.........#.",
        ".#.......#..",
        ".#.......#..",
        "..##...##...",
        "....###.....",
        "............",
    ],
    "stop": [
        "............",
        "..########..",
        ".#........#.",
        ".#........#.",
        ".#..####..#.",
        ".#..####..#.",
        ".#..####..#.",
        ".#..####..#.",
        ".#........#.",
        ".#........#.",
        "..########..",
        "............",
    ],
    "play": [
        "............",
        "..##........",
        "..#.##......",
        "..#...##....",
        "..#.....##..",
        "..#.......#.",
        "..#.....##..",
        "..#...##....",
        "..#.##......",
        "..##........",
        "............",
        "............",
    ],
    "reset": [
        "......#.....",
        "......##....",
        "....#####...",
        "..##..##....",
        "..#...#.....",
        ".#........#.",
        ".#........#.",
        ".#........#.",
        ".#........#.",
        "..#......#..",
        "..##....##..",
        "....####....",
    ],
    "camera": [
        "....####....",
        "...#....#...",
        ".###....###.",
        "#..........#",
        "#...####...#",
        "#..#....#..#",
        "#..#....#..#",
        "#..#....#..#",
        "#...####...#",
        "#..........#",
        ".##########.",
        "............",
    ],
    "disc": [
        "....####....",
        "..##....##..",
        ".#........#.",
        ".#........#.",
        "#....##....#",
        "#...#..#...#",
        "#...#..#...#",
        "#....##....#",
        ".#........#.",
        ".#........#.",
        "..##....##..",
        "....####....",
    ],
    "usb": [
        "....####....",
        "....#..#....",
        "....#..#....",
        "..########..",
        "..#......#..",
        "..#......#..",
        "..#..##..#..",
        "..#..##..#..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "...######...",
    ],
    "library": [
        "..####......",
        "..#..#......",
        "###..#......",
        "#.####...###",
        "#.#..#..#.#.",
        "#.#..#..#.#.",
        "#.#..#..#.#.",
        "#.#..#..#.#.",
        "#.####.#.#..",
        "###..#.#.#..",
        "#.#..#.#.#..",
        "######.###..",
    ],
    "focus": [
        "####....####",
        "#..........#",
        "#..........#",
        "#..........#",
        "............",
        ".....##.....",
        ".....##.....",
        "............",
        "#..........#",
        "#..........#",
        "#..........#",
        "####....####",
    ],
    "gear": [
        "....###.....",
        ".##.###.##..",
        ".#########..",
        "..#######...",
        "####...####.",
        "####...####.",
        "####...####.",
        "..#######...",
        ".#########..",
        ".##.###.##..",
        "....###.....",
        "............",
    ],
    "eye": [
        "............",
        "............",
        "....####....",
        "..##....##..",
        ".#...##...#.",
        "#...####...#",
        "#...####...#",
        ".#...##...#.",
        "..##....##..",
        "....####....",
        "............",
        "............",
    ],
    "lock": [
        "....####....",
        "...#....#...",
        "...#....#...",
        "...#....#...",
        ".##########.",
        ".#........#.",
        ".#...##...#.",
        ".#...##...#.",
        ".#........#.",
        ".#........#.",
        ".##########.",
        "............",
    ],
    "user_plus": [
        "..###.......",
        ".#...#......",
        ".#...#......",
        ".#...#...#..",
        "..###....#..",
        ".......#####",
        "..####...#..",
        ".#....#..#..",
        "#......#....",
        "#......#....",
        "########....",
        "............",
    ],
    "user_minus": [
        "..###.......",
        ".#...#......",
        ".#...#......",
        ".#...#......",
        "..###.......",
        ".......#####",
        "..####......",
        ".#....#.....",
        "#......#....",
        "#......#....",
        "########....",
        "............",
    ],
    "cart": [
        "............",
        "##..........",
        ".#..........",
        ".###########",
        "..#.......#.",
        "..#......#..",
        "...#.....#..",
        "...#######..",
        "...#........",
        "...########.",
        "....##..##..",
        "....##..##..",
    ],
    "search": [
        "..####......",
        ".#....#.....",
        "#......#....",
        "#......#....",
        "#......#....",
        "#......#....",
        ".#....#.....",
        "..####.#....",
        ".......##...",
        "........##..",
        ".........##.",
        "..........#.",
    ],
    "download": [
        ".....#......",
        ".....#......",
        ".....#......",
        ".....#......",
        "..#..#..#...",
        "...#.#.#....",
        "....###.....",
        ".....#......",
        "#.........#.",
        "#.........#.",
        "###########.",
        "............",
    ],
    "folder": [
        "............",
        ".###........",
        "#...#######.",
        "#..........#",
        "############",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        ".##########.",
        "............",
    ],
    "check": [
        "............",
        "............",
        "............",
        "..........#.",
        ".........#..",
        "........#...",
        ".#.....#....",
        "..#...#.....",
        "...#.#......",
        "....#.......",
        "............",
        "............",
    ],
    "cross": [
        "............",
        ".#........#.",
        "..#......#..",
        "...#....#...",
        "....#..#....",
        ".....##.....",
        ".....##.....",
        "....#..#....",
        "...#....#...",
        "..#......#..",
        ".#........#.",
        "............",
    ],
    "warn": [
        ".....#......",
        "....#.#.....",
        "....#.#.....",
        "...#.#.#....",
        "...#.#.#....",
        "..#..#..#...",
        "..#.....#...",
        ".#...#...#..",
        ".#.......#..",
        "#.........#.",
        "###########.",
        "............",
    ],
    "monitor": [
        ".##########.",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        "#..........#",
        ".##########.",
        ".....##.....",
        ".....##.....",
        "...######...",
        "............",
    ],
    "back": [
        "............",
        ".....#......",
        "....#.......",
        "...#........",
        "..#.........",
        ".##########.",
        "..#.........",
        "...#........",
        "....#.......",
        ".....#......",
        "............",
        "............",
    ],
    "trash": [
        "....####....",
        "...#....#...",
        "############",
        ".#........#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#........#.",
        "..########..",
        "............",
    ],
    "chip": [
        "...#.#.#....",
        "...#.#.#....",
        "..#######...",
        "###.....###.",
        "..#.###.#...",
        "###.###.###.",
        "..#.###.#...",
        "###.....###.",
        "..#######...",
        "...#.#.#....",
        "...#.#.#....",
        "............",
    ],
    "memory": [
        "............",
        "............",
        "############",
        "#..........#",
        "#.##.##.##.#",
        "#.##.##.##.#",
        "#..........#",
        "#####..#####",
        "#.#.#..#.#.#",
        "#.#.#..#.#.#",
        "............",
        "............",
    ],
    "drive": [
        "............",
        "............",
        ".##########.",
        "#..........#",
        "#..........#",
        "#..........#",
        "############",
        "#......#.#.#",
        "#..........#",
        ".##########.",
        "............",
        "............",
    ],
    "wifi": [
        "............",
        "............",
        "...######...",
        ".##......##.",
        "#..........#",
        "............",
        "....####....",
        "...#....#...",
        "............",
        ".....##.....",
        ".....##.....",
        "............",
    ],
    "plus": [
        "............",
        ".....#......",
        ".....#......",
        ".....#......",
        ".....#......",
        ".#########..",
        ".....#......",
        ".....#......",
        ".....#......",
        ".....#......",
        "............",
        "............",
    ],
    "minus": [
        "............",
        "............",
        "............",
        "............",
        "............",
        ".#########..",
        "............",
        "............",
        "............",
        "............",
        "............",
        "............",
    ],
    "box": [
        "............",
        "############",
        "#..........#",
        "############",
        ".#........#.",
        ".#..####..#.",
        ".#........#.",
        ".#........#.",
        ".#........#.",
        ".#........#.",
        ".##########.",
        "............",
    ],
    "rocket": [
        ".....##.....",
        "....#..#....",
        "....#..#....",
        "....####....",
        "....#..#....",
        "....#..#....",
        "...##..##...",
        "..#.#..#.#..",
        "..#.####.#..",
        "..##....##..",
        ".....##.....",
        ".....##.....",
    ],
    "info": [
        "....###.....",
        "..##...##...",
        ".#.......#..",
        ".#...#...#..",
        "#.........#.",
        "#....#....#.",
        "#....#....#.",
        ".#...#...#..",
        ".#.......#..",
        "..##...##...",
        "....###.....",
        "............",
    ],
    "close": [
        "............",
        "............",
        "..#......#..",
        "...#....#...",
        "....#..#....",
        ".....##.....",
        ".....##.....",
        "....#..#....",
        "...#....#...",
        "..#......#..",
        "............",
        "............",
    ],
    "keyboard": [
        "............",
        "............",
        ".##########.",
        "#..........#",
        "#.#.#..#.#.#",
        "#..........#",
        "#.#.#..#.#.#",
        "#..........#",
        "#..######..#",
        ".##########.",
        "............",
        "............",
    ],
    "server": [
        ".##########.",
        "#..........#",
        "#.#.#......#",
        "#..........#",
        ".##########.",
        "............",
        "............",
        ".##########.",
        "#..........#",
        "#.#.#......#",
        "#..........#",
        ".##########.",
    ],
    "coin": [
        "....####....",
        "..##....##..",
        ".#........#.",
        ".#...##...#.",
        "#...####...#",
        "#...#.....#.",
        "#....##....#",
        "#......#...#",
        ".#..####..#.",
        ".#...##...#.",
        "..##....##..",
        "....####....",
    ],
    "gpu": [
        "#...........",
        "############",
        "#..........#",
        "#..####....#",
        "#.#....#.#.#",
        "#.#.##.#.#.#",
        "#.#.##.#.#.#",
        "#.#....#.#.#",
        "#..####....#",
        "#..........#",
        "############",
        "#..#.#.#.#..",
    ],
    "pcie": [
        "..........##",
        "##########.#",
        "#........#.#",
        "#.###....#.#",
        "#.#.#.##.#.#",
        "#.###....#.#",
        "#........#.#",
        "##########.#",
        ".#.#.#..#..#",
        ".#.#.#..#..#",
        "...........#",
        "............",
    ],
    "laptop": [
        "............",
        "..########..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..########..",
        ".#........#.",
        "#..........#",
        "############",
        "............",
    ],
    "sort": [
        "............",
        "..#......#..",
        ".###.....#..",
        "#.#.#....#..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..#....#.#.#",
        "..#.....###.",
        "..#......#..",
        "............",
    ],
    "chevron_down": [
        "............",
        "............",
        "............",
        "............",
        "..#......#..",
        "...#....#...",
        "....#..#....",
        ".....##.....",
        "............",
        "............",
        "............",
        "............",
    ],
    "chevron_right": [
        "............",
        "............",
        "....#.......",
        ".....#......",
        "......#.....",
        ".......#....",
        ".......#....",
        "......#.....",
        ".....#......",
        "....#.......",
        "............",
        "............",
    ],
    "grid": [
        "#####..#####",
        "#...#..#...#",
        "#...#..#...#",
        "#...#..#...#",
        "#####..#####",
        "............",
        "............",
        "#####..#####",
        "#...#..#...#",
        "#...#..#...#",
        "#...#..#...#",
        "#####..#####",
    ],
    "list": [
        "............",
        "##.#########",
        "............",
        "............",
        "##.#########",
        "............",
        "............",
        "##.#########",
        "............",
        "............",
        "##.#########",
        "............",
    ],
    "wrench": [
        ".......###..",
        "......#...#.",
        "......#..#..",
        "......#...##",
        ".....#.....#",
        "....#...###.",
        "...#...#....",
        "..#...#.....",
        ".#...#......",
        "#...#.......",
        "#..#........",
        ".##.........",
    ],
}

# order matters! the Java enum UiKit.Icon indexes the atlas by ordinal, so only ever append
ICON_ORDER = [
    "power", "stop", "play", "reset", "camera", "disc", "usb", "library", "focus", "gear", "eye", "lock",
    "user_plus", "user_minus", "cart", "search", "download", "folder", "check", "cross", "warn", "monitor", "back",
    "trash", "chip", "memory", "drive", "wifi", "plus", "minus", "box", "rocket", "info", "close", "keyboard",
    "server", "coin",
    # appended
    "gpu", "pcie", "laptop", "sort", "chevron_down", "chevron_right", "grid", "list", "wrench",
]


def icon_canvas():
    return Canvas(12)


def _glyph(name):
    rows = GLYPHS[name]
    assert len(rows) == 12 and all(len(r) == 12 for r in rows), name

    def draw(c):
        c.paint(rows, {"#": ICON})
    return draw


ICONS = [(n, _glyph(n)) for n in ICON_ORDER]


def icon_atlas():
    """12x12 white glyphs on a 16px grid, 8 per row; tinted at draw time."""
    cols = 8
    atlas = Canvas(128, 128)
    assert len(ICONS) <= cols * 8
    for i, (_, fn) in enumerate(ICONS):
        c = icon_canvas()
        fn(c)
        atlas.paste(c, (i % cols) * 16 + 2, (i // cols) * 16 + 2)
    return atlas, [n for n, _ in ICONS]


# ------------------------------------------------------------------------------------------- logo
LOGO_BG = hexc("11151d")
LOGO_VIOLET = TIER["violet"]
LOGO_CYAN = TIER["cyan"]

LETTERS = {
    "H": ["##..##", "##..##", "##..##", "######", "######", "##..##", "##..##", "##..##"],
    "V": ["##..##", "##..##", "##..##", "##..##", ".####.", ".####.", "..##..", "..##.."],
    "X": ["##..##", "##..##", ".####.", "..##..", "..##..", ".####.", "##..##", "##..##"],
}


def logo_32():
    """The 32x32 master of the logo: rounded near-black tile, violet monitor, bold cyan 'HVX' on the screen."""
    c = Canvas(32)
    # rounded tile
    corner = [4, 2, 1, 1]  # pixels cut on rows 0..3 from each side
    for y in range(32):
        cut = corner[y] if y < 4 else corner[31 - y] if y > 27 else 0
        c.hline(cut, 31 - cut, y, LOGO_BG)
    rim_hi, rim_lo = hexc("1f2636"), hexc("0a0d13")
    for y in range(32):
        cut = corner[y] if y < 4 else corner[31 - y] if y > 27 else 0
        if 0 < y < 31:
            c.set(cut, y, rim_hi)
            c.set(31 - cut, y, rim_lo)
    c.hline(4, 27, 0, rim_hi)
    c.hline(4, 27, 31, rim_lo)
    # monitor bezel
    v = LOGO_VIOLET
    x0, y0, x1, y1 = 3, 5, 28, 23
    c.rect(x0, y0, x1, y1, v[1])
    c.hline(x0 + 1, x1 - 1, y0, v[0])
    c.vline(x0, y0 + 1, y1 - 1, v[0])
    c.hline(x0 + 1, x1 - 1, y1, v[3])
    c.vline(x1, y0 + 1, y1 - 1, v[2])
    for (x, y) in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        c.set(x, y, LOGO_BG)
    # screen
    sx0, sy0, sx1, sy1 = x0 + 2, y0 + 2, x1 - 2, y1 - 3
    c.rect(sx0, sy0, sx1, sy1, hexc("070a10"))
    c.hline(sx0, sx1, sy0, hexc("1a1f2e"))
    # power LED on the chin
    c.hline(15, 16, y1 - 1, LOGO_CYAN[1])
    # HVX
    cy = LOGO_CYAN
    lx = sx0 + 1
    ly = sy0 + 3
    for ch in "HVX":
        rows = LETTERS[ch]
        for j, row in enumerate(rows):
            for i, bit in enumerate(row):
                if bit == "#":
                    col = cy[0] if j == 0 else cy[2] if j == len(rows) - 1 else cy[1]
                    c.set(lx + i, ly + j, col)
        lx += len(rows[0]) + 1
    # stand
    c.rect(13, y1 + 1, 18, y1 + 2, v[2])
    c.hline(13, 18, y1 + 1, v[3])
    c.rect(9, y1 + 3, 22, y1 + 4, v[1])
    c.hline(9, 22, y1 + 3, v[0])
    c.hline(9, 22, y1 + 4, v[2])
    return c


def logo():
    """256x256 RGBA logo: the 32x32 pixel master scaled up 8x with nearest-neighbour (crisp at 32px too)."""
    return logo_32().img.resize((256, 256), Image.NEAREST)
