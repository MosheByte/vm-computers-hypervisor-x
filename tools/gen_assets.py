#!/usr/bin/env python3
"""Spits out every texture, model, blockstate, loot table and recipe in HyperVisor X.

All art is procedural pixel art (32x32 for blocks/items) so the repository contains no
third-party assets. Re-run after changing anything here:

    python tools/gen_assets.py
"""
import colorsys
import json
import math
import os
import random
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "hypervisorx")
DATA = os.path.join(ROOT, "data", "hypervisorx")
MOD = "hypervisorx"
random.seed(1337)


def path(*p):
    full = os.path.join(*p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full


def save_png(img, *rel):
    img.save(path(ASSETS, "textures", *rel))


def write_json(obj, *rel, base=ASSETS):
    with open(path(base, *rel), "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


# --------------------------------------------------------------------------------------------- art helpers

def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def noise_fill(img, base, amount=0.06, box=None):
    w, h = img.size
    x0, y0, x1, y1 = box or (0, 0, w, h)
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            f = 1.0 + random.uniform(-amount, amount)
            px[x, y] = shade(base, f)


def new(size=32, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (size, size), color)


def bevel(d, box, light, dark):
    x0, y0, x1, y1 = box
    d.line([(x0, y0), (x1, y0)], fill=light)
    d.line([(x0, y0), (x0, y1)], fill=light)
    d.line([(x0, y1), (x1, y1)], fill=dark)
    d.line([(x1, y0), (x1, y1)], fill=dark)


def rgb_at(t, s=1.0, v=1.0):
    r, g, b = colorsys.hsv_to_rgb(t % 1.0, s, v)
    return (int(r * 255), int(g * 255), int(b * 255), 255)


def animated(frames, *rel, frametime=3, interpolate=True):
    w, h = frames[0].size
    strip = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    save_png(strip, *rel)
    with open(path(ASSETS, "textures", *rel) + ".mcmeta", "w", encoding="utf-8") as fp:
        json.dump({"animation": {"frametime": frametime, "interpolate": interpolate}}, fp)


def font(size):
    try:
        return ImageFont.load_default(size)
    except TypeError:
        return ImageFont.load_default()


# --------------------------------------------------------------------------------------------- block textures

BEIGE = hexc("d9d0b8")
BLACK = hexc("16181d")
GRAPHITE = hexc("2b2f37")
STEEL = hexc("aab1bc")


def retro_front(on):
    img = new()
    noise_fill(img, BEIGE, 0.03)
    d = ImageDraw.Draw(img)
    for y in (5, 11):  # 5.25" bays
        d.rectangle((4, y, 27, y + 4), fill=shade(BEIGE, 0.82))
        d.line((5, y + 2, 26, y + 2), fill=shade(BEIGE, 0.65))
    d.rectangle((4, 18, 15, 21), fill=shade(BEIGE, 0.8))  # floppy
    d.line((6, 19, 13, 19), fill=hexc("2a2a2a"))
    d.rectangle((22, 22, 27, 27), fill=shade(BEIGE, 0.75))  # power button
    d.rectangle((23, 23, 26, 26), fill=shade(BEIGE, 1.05))
    d.point((19, 25), fill=hexc("39ff7a") if on else hexc("3b4a3f"))
    d.point((19, 27), fill=hexc("ffb020") if on else hexc("4a4535"))
    for x in range(3, 29, 2):
        d.point((x, 29), fill=shade(BEIGE, 0.7))
    bevel(d, (0, 0, 31, 31), shade(BEIGE, 1.1), shade(BEIGE, 0.7))
    return img


def retro_side():
    img = new()
    noise_fill(img, BEIGE, 0.03)
    d = ImageDraw.Draw(img)
    for y in range(6, 26, 3):
        d.line((22, y, 28, y), fill=shade(BEIGE, 0.75))
    bevel(d, (0, 0, 31, 31), shade(BEIGE, 1.08), shade(BEIGE, 0.72))
    return img


def tower_front(frame, frames):
    img = new()
    noise_fill(img, BLACK, 0.05)
    d = ImageDraw.Draw(img)
    for y in range(2, 30, 2):  # mesh
        for x in range(4 + (y // 2) % 2, 30, 2):
            d.point((x, y), fill=hexc("0c0d10"))
    for y in range(1, 31):  # RGB strip
        c = rgb_at(frame / frames + y / 40.0)
        d.point((1, y), fill=c)
        d.point((2, y), fill=shade(c, 0.6))
    d.ellipse((12, 5, 26, 19), outline=rgb_at(frame / frames + 0.3))  # front fan ring
    d.ellipse((12, 17, 26, 31), outline=rgb_at(frame / frames + 0.6))
    return img


def tower_side():
    img = new()
    noise_fill(img, BLACK, 0.05)
    d = ImageDraw.Draw(img)
    bevel(d, (0, 0, 31, 31), hexc("30333a"), hexc("08090b"))
    for y in range(4, 28, 4):
        d.line((4, y, 27, y), fill=hexc("202329"))
    return img


def tower_glass():
    img = new(32, (130, 160, 190, 70))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), outline=(20, 22, 26, 255))
    for i in range(5):
        d.line((4 + i * 2, 2, 2, 4 + i * 2), fill=(255, 255, 255, 60))
    return img


def tower_top():
    img = new()
    noise_fill(img, BLACK, 0.05)
    d = ImageDraw.Draw(img)
    for x in range(6, 26, 2):
        d.line((x, 6, x, 25), fill=hexc("0b0c0f"))
    return img


def pcb():
    img = new()
    noise_fill(img, hexc("12301f"), 0.06)
    d = ImageDraw.Draw(img)
    for _ in range(26):
        x, y = random.randrange(2, 30), random.randrange(2, 30)
        d.line((x, y, x + random.choice((-6, 6)), y), fill=hexc("2f7a4f"))
    d.rectangle((10, 8, 20, 16), fill=hexc("1d1f24"))  # socket
    d.rectangle((22, 4, 24, 26), fill=hexc("26282e"))  # dimm slots
    d.rectangle((26, 4, 28, 26), fill=hexc("26282e"))
    d.rectangle((4, 20, 20, 22), fill=hexc("26282e"))  # pcie
    return img


def fan(frame, frames):
    img = new()
    noise_fill(img, hexc("15171c"), 0.04)
    d = ImageDraw.Draw(img)
    c = rgb_at(frame / frames)
    d.ellipse((3, 3, 28, 28), outline=c, width=2)
    a0 = frame / frames * math.pi * 2
    for k in range(5):
        a = a0 + k * math.pi * 2 / 5
        d.line((16, 16, 16 + int(math.cos(a) * 10), 16 + int(math.sin(a) * 10)), fill=hexc("5a5f69"), width=2)
    d.ellipse((13, 13, 19, 19), fill=shade(c, 0.8))
    return img


def rack_front(frame, frames, on):
    img = new()
    noise_fill(img, GRAPHITE, 0.05)
    d = ImageDraw.Draw(img)
    for u in range(4):
        y = 2 + u * 7
        d.rectangle((2, y, 29, y + 5), fill=shade(GRAPHITE, 0.75))
        d.line((3, y + 1, 28, y + 1), fill=shade(GRAPHITE, 1.25))
        for x in range(14, 27, 2):
            d.point((x, y + 3), fill=shade(GRAPHITE, 0.5))
        for i in range(3):
            blink = on and (random.Random(u * 7 + i * 13 + frame // (1 + i)).random() > 0.35)
            color = (hexc("3dff86") if i < 2 else hexc("ffb545")) if blink else hexc("2b3a30")
            d.point((4 + i * 2, y + 3), fill=color)
    d.rectangle((0, 0, 31, 31), outline=hexc("1a1d22"))
    return img


def rack_side():
    img = new()
    noise_fill(img, GRAPHITE, 0.05)
    d = ImageDraw.Draw(img)
    for y in range(3, 30, 3):
        for x in range(3, 30, 3):
            d.point((x, y), fill=hexc("15171b"))
    d.rectangle((0, 0, 31, 31), outline=hexc("1a1d22"))
    return img


def bezel():
    img = new()
    noise_fill(img, hexc("0e0f12"), 0.03)
    d = ImageDraw.Draw(img)
    d.rectangle((1, 1, 30, 26), fill=hexc("050608"))
    d.point((16, 29), fill=hexc("38c6ff"))
    return img


def monitor_back():
    img = new()
    noise_fill(img, hexc("23262c"), 0.05)
    d = ImageDraw.Draw(img)
    d.rectangle((10, 8, 21, 20), outline=hexc("1a1c21"))
    return img


def metal(base=STEEL):
    img = new()
    noise_fill(img, base, 0.04)
    d = ImageDraw.Draw(img)
    for y in range(0, 32, 4):
        d.line((0, y, 31, y), fill=shade(base, 1.06))
    return img


def crt_front():
    img = new()
    noise_fill(img, BEIGE, 0.03)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((3, 3, 28, 23), radius=3, fill=hexc("101310"))
    d.line((6, 5, 12, 5), fill=(255, 255, 255, 90))
    d.rectangle((22, 26, 26, 28), fill=shade(BEIGE, 0.75))
    d.point((18, 27), fill=hexc("39ff7a"))
    return img


def wall_front():
    img = new()
    noise_fill(img, hexc("060708"), 0.02)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), outline=hexc("101216"))
    return img


def laptop_lid(rugged):
    base = hexc("4b4f2e") if rugged else hexc("b9bec7")
    img = new()
    noise_fill(img, base, 0.03)
    d = ImageDraw.Draw(img)
    if rugged:
        for (x, y) in ((0, 0), (26, 0), (0, 26), (26, 26)):
            d.rectangle((x, y, x + 5, y + 5), fill=hexc("e0b31b"))
        d.text((9, 12), "RUGGED", fill=hexc("d6d0a8"), font=font(6))
    else:
        d.ellipse((12, 12, 20, 20), outline=hexc("38c6ff"))
        d.point((16, 16), fill=hexc("38c6ff"))
    return img


def laptop_keyboard(rugged):
    base = hexc("2f321d") if rugged else hexc("9da3ad")
    img = new()
    noise_fill(img, base, 0.03)
    d = ImageDraw.Draw(img)
    for row in range(5):
        for col in range(12):
            x = 2 + col * 2 + (row % 2)
            y = 4 + row * 3
            d.rectangle((x, y, x + 1, y + 1), fill=hexc("17181c"))
            if not rugged:
                d.point((x, y + 1), fill=(56, 198, 255, 160))
    d.rectangle((11, 21, 20, 27), fill=shade(base, 0.85))
    return img


def keyboard_top(frame, frames):
    img = new()
    noise_fill(img, hexc("111216"), 0.04)
    d = ImageDraw.Draw(img)
    for row in range(5):
        for col in range(14):
            x = 1 + col * 2 + (1 if row % 2 else 0)
            y = 4 + row * 4
            c = rgb_at(frame / frames + col / 14.0, 0.8, 0.9)
            d.rectangle((x, y, x + 1, y + 2), fill=hexc("2a2d34"))
            d.point((x, y + 2), fill=c)
    return img


def mouse_top(frame, frames):
    img = new()
    noise_fill(img, hexc("111216"), 0.04)
    d = ImageDraw.Draw(img)
    d.line((16, 2, 16, 10), fill=hexc("2a2d34"))
    c = rgb_at(frame / frames)
    d.line((12, 20, 20, 20), fill=c)
    return img


def speaker():
    img = new()
    noise_fill(img, hexc("15161a"), 0.04)
    d = ImageDraw.Draw(img)
    d.ellipse((8, 4, 24, 20), fill=hexc("0b0c0f"), outline=hexc("3a3d44"))
    d.ellipse((13, 9, 19, 15), fill=hexc("2a2c32"))
    d.ellipse((12, 23, 20, 30), fill=hexc("0b0c0f"), outline=hexc("3a3d44"))
    return img


def crate(top):
    img = new()
    noise_fill(img, hexc("c3c8cf"), 0.04)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 31, 31), outline=hexc("6c737d"), width=2)
    if top:
        d.line((2, 2, 29, 29), fill=hexc("8b929c"), width=2)
        d.line((29, 2, 2, 29), fill=hexc("8b929c"), width=2)
    else:
        for x in range(0, 32, 6):
            d.line((x, 4, x + 4, 4), fill=hexc("ff8a1f"), width=3)
        d.text((6, 13), "ORBIT", fill=hexc("2b2f36"), font=font(8))
    return img


def pod():
    img = new()
    noise_fill(img, hexc("e7e9ec"), 0.03)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 12, 31, 17), fill=hexc("ff7a1a"))
    for x in range(0, 32, 8):
        d.rectangle((x, 26, x + 3, 31), fill=hexc("2b2f36"))
    return img


def solid(c):
    img = new(16)
    noise_fill(img, c, 0.02)
    return img


def screen_no_signal():
    w, h = 256, 144
    img = Image.new("RGBA", (w, h), hexc("05070b"))
    d = ImageDraw.Draw(img)
    for y in range(0, h, 2):
        d.line((0, y, w, y), fill=(14, 20, 30, 255))
    d.text((w // 2, 54), "NO SIGNAL", fill=hexc("38c6ff"), font=font(26), anchor="mm")
    d.text((w // 2, 86), "link with an HDMI cable", fill=hexc("8a94a6"), font=font(11), anchor="mm")
    d.text((w // 2, 128), "HyperVisor X", fill=hexc("b46cff"), font=font(10), anchor="mm")
    return img


def screen_boot():
    w, h = 256, 144
    img = Image.new("RGBA", (w, h), hexc("070a10"))
    d = ImageDraw.Draw(img)
    cx, cy = w // 2, 62
    for r in range(28, 6, -4):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=rgb_at(r / 40.0, 0.7, 0.9))
    d.text((cx, cy), "HVX", fill=hexc("e8eef8"), font=font(16), anchor="mm")
    d.text((cx, 112), "VM Computers: HyperVisor X", fill=hexc("8a94a6"), font=font(11), anchor="mm")
    return img


def logo():
    s = 256
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((8, 8, s - 8, s - 8), radius=44, fill=hexc("11151d"))
    for i in range(12):
        d.rounded_rectangle((28 + i * 2, 40 + i * 2, s - 28 - i * 2, s - 70 - i * 2), radius=18, outline=rgb_at(0.55 + i / 40.0, 0.7, 1.0))
    d.text((s // 2, 104), "HVX", fill=hexc("e8eef8"), font=font(64), anchor="mm")
    d.rectangle((s // 2 - 16, s - 70, s // 2 + 16, s - 48), fill=hexc("2b2f37"))
    d.rectangle((s // 2 - 50, s - 48, s // 2 + 50, s - 36), fill=hexc("2b2f37"))
    return img


# --------------------------------------------------------------------------------------------- item textures

def chip(color, label, pins=True):
    img = new()
    d = ImageDraw.Draw(img)
    if pins:
        for i in range(6, 27, 3):
            d.line((i, 3, i, 6), fill=hexc("d4b26a"))
            d.line((i, 25, i, 28), fill=hexc("d4b26a"))
            d.line((3, i, 6, i), fill=hexc("d4b26a"))
            d.line((25, i, 28, i), fill=hexc("d4b26a"))
    d.rectangle((6, 6, 25, 25), fill=hexc("9aa1ab"))
    d.rectangle((8, 8, 23, 23), fill=color)
    d.text((16, 16), label, fill=hexc("ffffff"), font=font(8), anchor="mm")
    return img


def ram_stick(color, gb):
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle((2, 10, 29, 21), fill=hexc("1d5c35"))
    for x in range(4, 28, 5):
        d.rectangle((x, 12, x + 3, 17), fill=hexc("17181c"))
    d.rectangle((2, 8, 29, 11), fill=color)
    for x in range(3, 29, 2):
        d.point((x, 21), fill=hexc("d4b26a"))
    d.text((16, 26), f"{gb}G", fill=hexc("e8eef8"), font=font(7), anchor="mm")
    return img


def gpu(tier):
    colors = [hexc("6b7280"), hexc("2f80ed"), hexc("9b51e0"), hexc("f2c94c")]
    c = colors[tier - 1]
    img = new()
    d = ImageDraw.Draw(img)
    if tier == 1:
        return chip(hexc("4b5563"), "iGPU")
    d.rectangle((2, 8, 29, 22), fill=hexc("1f2228"))
    d.ellipse((5, 9, 15, 19), outline=c, width=2)
    d.ellipse((17, 9, 27, 19), outline=c, width=2)
    if tier >= 3:
        d.line((2, 22, 29, 22), fill=rgb_at(0.8))
    d.rectangle((4, 23, 22, 25), fill=hexc("d4b26a"))
    d.line((2, 7, 29, 7), fill=c)
    return img


def drive(kind):
    img = new()
    d = ImageDraw.Draw(img)
    if kind == "nvme":
        d.rectangle((2, 12, 29, 19), fill=hexc("1d5c35"))
        d.rectangle((6, 13, 14, 18), fill=hexc("17181c"))
        d.rectangle((16, 13, 24, 18), fill=hexc("17181c"))
        d.rectangle((27, 13, 29, 18), fill=hexc("d4b26a"))
    elif kind == "ssd":
        d.rounded_rectangle((5, 6, 26, 26), radius=2, fill=hexc("2b2f37"))
        d.rectangle((8, 9, 23, 14), fill=hexc("38c6ff"))
        d.text((16, 20), "SSD", fill=hexc("e8eef8"), font=font(7), anchor="mm")
    else:
        big = kind == "ent"
        d.rounded_rectangle((4, 5, 27, 27), radius=2, fill=hexc("8d949e") if not big else hexc("5b6370"))
        d.ellipse((8, 8, 22, 22), fill=hexc("c9ced6"))
        d.line((15, 15, 22, 10), fill=hexc("2b2f37"), width=2)
        if big:
            d.rectangle((4, 24, 27, 27), fill=hexc("ff8a1f"))
    return img


def usb():
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((8, 4, 23, 22), radius=3, fill=hexc("2f80ed"))
    d.rectangle((11, 22, 20, 28), fill=hexc("c9ced6"))
    d.rectangle((13, 24, 14, 25), fill=hexc("2b2f37"))
    d.rectangle((17, 24, 18, 25), fill=hexc("2b2f37"))
    return img


def card(color, label):
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle((2, 8, 27, 21), fill=hexc("1d5c35"))
    d.rectangle((6, 10, 16, 18), fill=color)
    d.rectangle((4, 21, 22, 23), fill=hexc("d4b26a"))
    d.rectangle((27, 6, 29, 24), fill=hexc("9aa1ab"))
    d.text((21, 14), label, fill=hexc("e8eef8"), font=font(6), anchor="mm")
    return img


def blade(tier):
    img = new()
    d = ImageDraw.Draw(img)
    d.rectangle((2, 10, 29, 21), fill=hexc("2b2f37"))
    for x in range(4, 18, 3):
        d.line((x, 12, x, 19), fill=hexc("15171b"))
    d.point((24, 13), fill=hexc("3dff86"))
    d.point((26, 13), fill=hexc("ffb545") if tier > 3 else hexc("3dff86"))
    d.text((24, 18), "x16" if tier > 3 else "x8", fill=hexc("e8eef8"), font=font(5), anchor="mm")
    return img


def tablet():
    img = new()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((5, 2, 26, 29), radius=3, fill=hexc("1b1f27"))
    d.rectangle((7, 5, 24, 25), fill=hexc("0b2033"))
    d.ellipse((11, 9, 20, 18), outline=hexc("38c6ff"))
    d.point((22, 7), fill=hexc("ffb545"))
    d.line((9, 21, 22, 21), fill=hexc("b46cff"))
    return img


def cable():
    img = new()
    d = ImageDraw.Draw(img)
    pts = [(5, 26), (8, 18), (14, 16), (20, 14), (24, 7)]
    d.line(pts, fill=hexc("17181c"), width=3)
    d.rectangle((2, 25, 8, 29), fill=hexc("9aa1ab"))
    d.rectangle((22, 3, 28, 7), fill=hexc("9aa1ab"))
    return img


# --------------------------------------------------------------------------------------------- models

def tex(name):
    return f"{MOD}:block/{name}"


def element(frm, to, faces, rotation=None, shade_on=True):
    e = {"from": frm, "to": to, "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if not shade_on:
        e["shade"] = False
    return e


def faces_all(t, overrides=None):
    f = {d: {"texture": t} for d in ("north", "south", "east", "west", "up", "down")}
    for k, v in (overrides or {}).items():
        f[k] = {"texture": v}
    return f


def block_model(name, textures, elements, render_type=None, ambient=True):
    m = {"parent": "block/block", "textures": textures, "elements": elements}
    if render_type:
        m["render_type"] = render_type
    if not ambient:
        m["ambientocclusion"] = False
    write_json(m, "models", "block", name + ".json")


def facing_states(name, powered=False):
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in rot.items():
        if powered:
            for p in ("false", "true"):
                model = f"{MOD}:block/{name}" + ("_on" if p == "true" else "")
                v = {"model": model}
                if y:
                    v["y"] = y
                variants[f"facing={f},powered={p}"] = v
        else:
            v = {"model": f"{MOD}:block/{name}"}
            if y:
                v["y"] = y
            variants[f"facing={f}"] = v
    write_json({"variants": variants}, "blockstates", name + ".json")


def simple_state(name):
    write_json({"variants": {"": {"model": f"{MOD}:block/{name}"}}}, "blockstates", name + ".json")


def block_item(name, model=None):
    write_json({"parent": f"{MOD}:block/{model or name}"}, "models", "item", name + ".json")


def flat_item(name):
    write_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}}, "models", "item", name + ".json")


def models():
    # retro PC (desktop case)
    for suffix, front in (("", "retro_pc_front"), ("_on", "retro_pc_front_on")):
        block_model("retro_pc" + suffix, {"particle": tex("retro_pc_side"), "front": tex(front), "side": tex("retro_pc_side")},
                    [element([1, 0, 2], [15, 11, 15], faces_all("#side", {"north": "#front"}))])
    facing_states("retro_pc", powered=True)
    block_item("retro_pc")

    # RGB tempered glass gaming tower (glass on the east side = viewer's left of the front)
    for suffix in ("", "_on"):
        t = {"particle": tex("gaming_tower_side"), "side": tex("gaming_tower_side"), "top": tex("gaming_tower_top"),
             "front": tex("gaming_tower_front" + ("_on" if suffix else "")), "glass": tex("gaming_tower_glass"),
             "pcb": tex("pcb"), "fan": tex("tower_fan" + ("_on" if suffix else "")), "dark": tex("screen_shell")}
        block_model("gaming_tower" + suffix, t, [
            element([4, 0, 1], [5, 16, 15], faces_all("#side")),
            element([5, 15, 1], [12, 16, 15], faces_all("#side", {"up": "#top"})),
            element([5, 0, 1], [12, 1, 15], faces_all("#side")),
            element([5, 1, 1], [12, 15, 2], faces_all("#side", {"north": "#front"})),
            element([5, 1, 14], [12, 15, 15], faces_all("#side")),
            element([5.05, 1.5, 2.5], [5.5, 14.5, 13.5], faces_all("#pcb")),
            element([6, 12.5, 2.2], [11.5, 14.5, 6], faces_all("#dark", {"east": "#fan", "down": "#fan"})),
            element([5.5, 1, 2], [11.7, 3.5, 14], faces_all("#dark")),
            element([11.8, 1, 2], [12, 15, 14], {"east": {"texture": "#glass"}, "west": {"texture": "#glass"}}),
        ], render_type="minecraft:translucent")
    facing_states("gaming_tower", powered=True)
    block_item("gaming_tower")

    # server rack unit
    for suffix in ("", "_on"):
        block_model("server_rack" + suffix, {"particle": tex("server_rack_side"), "side": tex("server_rack_side"),
                                             "front": tex("server_rack_front" + suffix), "top": tex("server_rack_side")},
                    [element([0.5, 0, 0.5], [15.5, 16, 15.5], faces_all("#side", {"north": "#front"}))])
    facing_states("server_rack", powered=True)
    block_item("server_rack")

    # flat monitor
    block_model("monitor", {"particle": tex("monitor_back"), "bezel": tex("monitor_bezel"), "back": tex("monitor_back"), "stand": tex("stand_metal")}, [
        element([5, 0, 7], [11, 1, 11], faces_all("#stand")),
        element([7.5, 1, 8.5], [8.5, 4, 9.5], faces_all("#stand")),
        element([0, 3, 7], [16, 12.5, 8.5], faces_all("#back", {"north": "#bezel"})),
    ])
    facing_states("monitor")
    block_item("monitor")

    block_model("crt_monitor", {"particle": tex("retro_pc_side"), "front": tex("crt_front"), "side": tex("retro_pc_side")}, [
        element([1, 0, 2], [15, 13, 13], faces_all("#side", {"north": "#front"})),
        element([3, 2, 13], [13, 11, 16], faces_all("#side")),
    ])
    facing_states("crt_monitor")
    block_item("crt_monitor")

    block_model("curved_monitor", {"particle": tex("monitor_back"), "stand": tex("stand_metal")}, [
        element([4, 0, 6], [12, 1, 12], faces_all("#stand")),
        element([7, 1, 8.5], [9, 5, 10], faces_all("#stand")),
    ])
    facing_states("curved_monitor")
    block_item("curved_monitor")

    for name, width in (("dual_monitor_arm", (0, 16)), ("triple_monitor_arm", (-6, 22))):
        block_model(name, {"particle": tex("stand_metal"), "stand": tex("stand_metal")}, [
            element([6, 0, 10], [10, 1, 14], faces_all("#stand")),
            element([7.5, 1, 11.5], [8.5, 13, 12.5], faces_all("#stand")),
            element([width[0], 10, 10.5], [width[1], 11, 11.5], faces_all("#stand")),
        ])
        facing_states(name)
        block_item(name)

    block_model("video_wall_panel", {"particle": tex("video_wall_front"), "front": tex("video_wall_front"), "back": tex("monitor_back")}, [
        element([0, 0, 13], [16, 16, 16], faces_all("#back", {"north": "#front"})),
    ])
    facing_states("video_wall_panel")
    block_item("video_wall_panel")

    for name, rugged in (("pro_laptop", False), ("rugged_laptop", True)):
        prefix = "rugged_" if rugged else ""
        block_model(name, {"particle": tex(prefix + "laptop_lid"), "lid": tex(prefix + "laptop_lid"),
                           "keys": tex(prefix + "laptop_keyboard"), "bezel": tex("monitor_bezel"), "edge": tex(prefix + "laptop_lid")}, [
            element([2, 0, 3], [14, 1, 13], faces_all("#edge", {"up": "#keys"})),
            element([2, 1, 11.5], [14, 10, 12.5], faces_all("#edge", {"north": "#bezel", "south": "#lid"}),
                    rotation={"origin": [8, 1, 12.5], "axis": "x", "angle": 22.5}),
        ])
        facing_states(name)
        block_item(name)

    block_model("mechanical_keyboard", {"particle": tex("keyboard_top"), "top": tex("keyboard_top"), "side": tex("screen_shell")}, [
        element([1, 0, 4], [15, 1.5, 10], faces_all("#side", {"up": "#top"}))])
    facing_states("mechanical_keyboard")
    block_item("mechanical_keyboard")
    block_model("gaming_mouse", {"particle": tex("mouse_top"), "top": tex("mouse_top"), "side": tex("screen_shell")}, [
        element([6, 0, 5], [10, 1.5, 11], faces_all("#side", {"up": "#top"}))])
    facing_states("gaming_mouse")
    block_item("gaming_mouse")
    block_model("desktop_speakers", {"particle": tex("speaker"), "front": tex("speaker"), "side": tex("screen_shell")}, [
        element([0, 0, 6], [4, 8, 10], faces_all("#side", {"north": "#front"})),
        element([12, 0, 6], [16, 8, 10], faces_all("#side", {"north": "#front"}))])
    facing_states("desktop_speakers")
    block_item("desktop_speakers")

    block_model("delivery_crate", {"particle": tex("crate_side"), "side": tex("crate_side"), "top": tex("crate_top")}, [
        element([1, 0, 1], [15, 14, 15], faces_all("#side", {"up": "#top", "down": "#top"}))])
    simple_state("delivery_crate")
    block_item("delivery_crate")

    block_model("supply_pod", {"particle": tex("supply_pod"), "body": tex("supply_pod"), "dark": tex("screen_shell")}, [
        element([3, 2, 3], [13, 13, 13], faces_all("#body")),
        element([4, 13, 4], [12, 15, 12], faces_all("#body")),
        element([6, 15, 6], [10, 16, 10], faces_all("#dark")),
        element([4, 0, 4], [12, 2, 12], faces_all("#dark")),
        element([7.5, 0, 0], [8.5, 6, 3], faces_all("#dark")),
        element([7.5, 0, 13], [8.5, 6, 16], faces_all("#dark")),
        element([0, 0, 7.5], [3, 6, 8.5], faces_all("#dark")),
        element([13, 0, 7.5], [16, 6, 8.5], faces_all("#dark")),
    ])
    simple_state("supply_pod")


ITEMS = {
    "cpu_orbitcore_d2": lambda: chip(hexc("2f80ed"), "D2"),
    "cpu_orbitcore_q4": lambda: chip(hexc("27ae60"), "Q4"),
    "cpu_orbitcore_x8": lambda: chip(hexc("9b51e0"), "X8"),
    "cpu_orbitripper_16": lambda: chip(hexc("eb5757"), "R16"),
    "ram_1gb": lambda: ram_stick(hexc("6b7280"), 1),
    "ram_2gb": lambda: ram_stick(hexc("2f80ed"), 2),
    "ram_4gb": lambda: ram_stick(hexc("27ae60"), 4),
    "ram_8gb": lambda: ram_stick(hexc("9b51e0"), 8),
    "ram_16gb": lambda: ram_stick(hexc("f2994a"), 16),
    "ram_32gb": lambda: ram_stick(hexc("eb5757"), 32),
    "gpu_integrated": lambda: gpu(1),
    "gpu_orbitforce_gt": lambda: gpu(2),
    "gpu_orbitforce_gx": lambda: gpu(3),
    "gpu_orbitforce_titan": lambda: gpu(4),
    "hdd_500gb": lambda: drive("hdd"),
    "ssd_1tb": lambda: drive("ssd"),
    "nvme_2tb": lambda: drive("nvme"),
    "enterprise_hdd_8tb": lambda: drive("ent"),
    "usb_flash_drive": usb,
    "redstone_gpio_card": lambda: card(hexc("d32f2f"), "IO"),
    "wifi_card": lambda: card(hexc("2f80ed"), "Wi"),
    "server_blade_x8": lambda: blade(3),
    "server_blade_x16": lambda: blade(4),
    "orbittech_tablet": tablet,
    "hdmi_cable": cable,
}


def textures():
    save_png(retro_front(False), "block", "retro_pc_front.png")
    save_png(retro_front(True), "block", "retro_pc_front_on.png")
    save_png(retro_side(), "block", "retro_pc_side.png")
    frames = 12
    save_png(tower_front(0, frames), "block", "gaming_tower_front.png")
    animated([tower_front(i, frames) for i in range(frames)], "block", "gaming_tower_front_on.png", frametime=4)
    save_png(tower_side(), "block", "gaming_tower_side.png")
    save_png(tower_glass(), "block", "gaming_tower_glass.png")
    save_png(tower_top(), "block", "gaming_tower_top.png")
    save_png(pcb(), "block", "pcb.png")
    save_png(fan(0, 8), "block", "tower_fan.png")
    animated([fan(i, 8) for i in range(8)], "block", "tower_fan_on.png", frametime=1, interpolate=False)
    save_png(rack_front(0, 1, False), "block", "server_rack_front.png")
    animated([rack_front(i, 8, True) for i in range(8)], "block", "server_rack_front_on.png", frametime=5, interpolate=False)
    save_png(rack_side(), "block", "server_rack_side.png")
    save_png(bezel(), "block", "monitor_bezel.png")
    save_png(monitor_back(), "block", "monitor_back.png")
    save_png(metal(), "block", "stand_metal.png")
    save_png(crt_front(), "block", "crt_front.png")
    save_png(wall_front(), "block", "video_wall_front.png")
    save_png(laptop_lid(False), "block", "laptop_lid.png")
    save_png(laptop_lid(True), "block", "rugged_laptop_lid.png")
    save_png(laptop_keyboard(False), "block", "laptop_keyboard.png")
    save_png(laptop_keyboard(True), "block", "rugged_laptop_keyboard.png")
    animated([keyboard_top(i, 10) for i in range(10)], "block", "keyboard_top.png", frametime=4)
    animated([mouse_top(i, 10) for i in range(10)], "block", "mouse_top.png", frametime=4)
    save_png(speaker(), "block", "speaker.png")
    save_png(crate(False), "block", "crate_side.png")
    save_png(crate(True), "block", "crate_top.png")
    save_png(pod(), "block", "supply_pod.png")
    save_png(solid(hexc("1a1d24")), "block", "screen_shell.png")
    save_png(screen_no_signal(), "screen", "no_signal.png")
    save_png(screen_boot(), "screen", "boot.png")
    save_png(Image.new("RGBA", (16, 16), (255, 255, 255, 255)), "screen", "white.png")
    for name, fn in ITEMS.items():
        save_png(fn(), "item", name + ".png")
        flat_item(name)
    lg = logo()
    lg.save(path(ROOT, "hypervisorx_logo.png"))


# --------------------------------------------------------------------------------------------- data

def loot_tables():
    simple = ["server_rack", "monitor", "crt_monitor", "curved_monitor", "dual_monitor_arm", "triple_monitor_arm",
              "video_wall_panel", "mechanical_keyboard", "gaming_mouse", "desktop_speakers", "delivery_crate"]
    for b in simple:
        write_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{b}"}],
                                                         "conditions": [{"condition": "minecraft:survives_explosion"}]}],
                    "random_sequence": f"{MOD}:blocks/{b}"}, "loot_table", "blocks", b + ".json", base=DATA)
    for b, comps in (("retro_pc", ["installed_parts", "vm_link"]), ("gaming_tower", ["installed_parts", "vm_link"]),
                     ("pro_laptop", ["vm_link", "remote_target"]), ("rugged_laptop", ["vm_link", "remote_target"])):
        write_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{
            "type": "minecraft:item", "name": f"{MOD}:{b}",
            "functions": [{"function": "minecraft:copy_components", "source": "block_entity",
                           "include": [f"{MOD}:{c}" for c in comps]}]}]}],
                    "random_sequence": f"{MOD}:blocks/{b}"}, "loot_table", "blocks", b + ".json", base=DATA)


def recipes():
    write_json({"type": "minecraft:crafting_shaped", "category": "misc",
                "pattern": ["IGI", "IRI", "ICI"],
                "key": {"I": {"item": "minecraft:iron_ingot"}, "G": {"item": "minecraft:glass_pane"},
                        "R": {"item": "minecraft:redstone"}, "C": {"item": "minecraft:copper_ingot"}},
                "result": {"id": f"{MOD}:orbittech_tablet", "count": 1}}, "recipe", "orbittech_tablet.json", base=DATA)
    write_json({"type": "minecraft:crafting_shapeless", "category": "misc",
                "ingredients": [{"item": "minecraft:copper_ingot"}, {"item": "minecraft:string"}, {"item": "minecraft:redstone"}],
                "result": {"id": f"{MOD}:hdmi_cable", "count": 2}}, "recipe", "hdmi_cable.json", base=DATA)
    write_json({"type": "minecraft:crafting_shaped", "category": "misc",
                "pattern": ["BBB", "SRS"],
                "key": {"B": {"item": "minecraft:stone_button"}, "S": {"item": "minecraft:smooth_stone_slab"},
                        "R": {"item": "minecraft:redstone"}},
                "result": {"id": f"{MOD}:mechanical_keyboard", "count": 1}}, "recipe", "mechanical_keyboard.json", base=DATA)
    write_json({"type": "minecraft:crafting_shaped", "category": "misc",
                "pattern": ["B", "S"],
                "key": {"B": {"item": "minecraft:stone_button"}, "S": {"item": "minecraft:smooth_stone_slab"}},
                "result": {"id": f"{MOD}:gaming_mouse", "count": 1}}, "recipe", "gaming_mouse.json", base=DATA)
    write_json({"type": "minecraft:crafting_shaped", "category": "misc",
                "pattern": ["N N", "IRI"],
                "key": {"N": {"item": "minecraft:note_block"}, "I": {"item": "minecraft:iron_ingot"}, "R": {"item": "minecraft:redstone"}},
                "result": {"id": f"{MOD}:desktop_speakers", "count": 1}}, "recipe", "desktop_speakers.json", base=DATA)


def tags():
    blocks = ["retro_pc", "gaming_tower", "server_rack", "monitor", "crt_monitor", "curved_monitor", "dual_monitor_arm",
              "triple_monitor_arm", "video_wall_panel", "pro_laptop", "rugged_laptop", "delivery_crate"]
    p = os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"replace": False, "values": [f"{MOD}:{b}" for b in blocks]}, f, indent=2)


def sounds():
    s = {
        "keyboard_click": [{"name": "minecraft:random/click", "volume": 0.35, "pitch": 1.9}],
        "pc_power_on": [{"name": "minecraft:block/beacon/activate", "volume": 0.6, "pitch": 1.6}],
        "pc_power_off": [{"name": "minecraft:block/beacon/deactivate", "volume": 0.6, "pitch": 1.6}],
        "pod_incoming": [{"name": "minecraft:fireworks/launch1", "volume": 1.0, "pitch": 0.5, "attenuation_distance": 96}],
        "pod_land": [{"name": "minecraft:random/anvil_land", "volume": 0.8, "pitch": 0.6, "attenuation_distance": 48},
                     {"name": "minecraft:random/explode1", "volume": 0.4, "pitch": 1.4, "attenuation_distance": 48}],
        "tablet_beep": [{"name": "minecraft:note/bit", "volume": 0.4, "pitch": 1.8}],
        "order_confirmed": [{"name": "minecraft:random/levelup", "volume": 0.5, "pitch": 1.7}],
    }
    out = {k: {"subtitle": f"subtitles.{MOD}.{k}", "sounds": [dict(v, type="file") for v in vals]} for k, vals in s.items()}
    write_json(out, "sounds.json")


def shaders():
    write_json({
        "vertex": "rendertype_text",
        "fragment": f"{MOD}:rendertype_hvx_screen",
        "samplers": [{"name": "Sampler0"}, {"name": "Sampler2"}],
        "uniforms": [
            {"name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]},
            {"name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]},
            {"name": "ColorModulator", "type": "float", "count": 4, "values": [1.0, 1.0, 1.0, 1.0]},
            {"name": "FogStart", "type": "float", "count": 1, "values": [0.0]},
            {"name": "FogEnd", "type": "float", "count": 1, "values": [1.0]},
            {"name": "FogColor", "type": "float", "count": 4, "values": [0.0, 0.0, 0.0, 0.0]},
            {"name": "FogShape", "type": "int", "count": 1, "values": [0]},
        ]}, "shaders", "core", "rendertype_hvx_screen.json")
    with open(path(ASSETS, "shaders", "core", "rendertype_hvx_screen.fsh"), "w", encoding="utf-8") as f:
        f.write("""#version 150

#moj_import <fog.glsl>

// HyperVisor X screen shader: "sharp bilinear" magnification. When a VM pixel covers more than one
// screen pixel, the interpolation is squeezed into a one-pixel band at texel edges, so text stays
// crisp up close without the shimmer of nearest-neighbour. Minification uses the normal trilinear
// mip chain (and anisotropic filtering) set on the texture.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 px = texCoord0 * size;
    vec2 fw = fwidth(px);
    vec2 uv = texCoord0;
    if (max(fw.x, fw.y) < 1.0) {
        vec2 base = floor(px - 0.5) + 0.5;
        vec2 f = px - base;
        vec2 scale = 1.0 / max(fw, vec2(1e-4));
        f = clamp((f - 0.5) * scale + 0.5, 0.0, 1.0);
        uv = (base + f) / size;
    }
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
""")


if __name__ == "__main__":
    textures()
    models()
    loot_tables()
    recipes()
    tags()
    sounds()
    shaders()
    print("assets generated")
