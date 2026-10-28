#!/usr/bin/env python3
"""Spits out every texture, model, blockstate, loot table and recipe in HyperVisor X.

All the art is procedural pixel art from gen_icons.py (items, GUI icons) and gen_models.py (the
detailed block models, each with its own texture atlas), so there are zero third-party assets in
the repo. Re-run it after you change anything here:

    python tools/gen_assets.py
"""
import colorsys
import json
import math
import os
import random
from PIL import Image, ImageDraw, ImageFont

import gen_icons
import gen_models

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


# --------------------------------------------------------------------------------------------- screens & logo

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
    d.text((cx, 112), "HyperVisor X", fill=hexc("8a94a6"), font=font(11), anchor="mm")
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


# --------------------------------------------------------------------------------------------- models

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


OBSOLETE = ["crate_side", "crate_top", "crt_front", "gaming_tower_front", "gaming_tower_front_on", "gaming_tower_glass",
            "gaming_tower_side", "gaming_tower_top", "keyboard_top", "laptop_keyboard", "laptop_lid", "monitor_back", "monitor_bezel",
            "mouse_top", "pcb", "retro_pc_front", "retro_pc_front_on", "retro_pc_side", "rugged_laptop_keyboard", "rugged_laptop_lid",
            "server_rack_front", "server_rack_front_on", "server_rack_side", "speaker", "stand_metal", "supply_pod", "tower_fan",
            "tower_fan_on", "video_wall_front"]


def models():
    for old in OBSOLETE:
        for ext in (".png", ".png.mcmeta"):
            p = os.path.join(ASSETS, "textures", "block", old + ext)
            if os.path.exists(p):
                os.remove(p)
    gen_models.build(ASSETS)
    for name in ("retro_pc", "gaming_tower", "server_rack"):
        facing_states(name, powered=True)
        block_item(name)
    for name in ("monitor", "crt_monitor", "video_wall_panel",
                 "pro_laptop", "rugged_laptop", "mechanical_keyboard", "gaming_mouse", "desktop_speakers"):
        facing_states(name)
        block_item(name)
    # the world renderer draws the panels for these, but the items need them baked into the model
    for name in ("curved_monitor", "dual_monitor_arm", "triple_monitor_arm"):
        facing_states(name)
        block_item(name, name + "_item")
    simple_state("delivery_crate")
    block_item("delivery_crate")
    simple_state("supply_pod")


def textures():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    noise_fill(img, hexc("1a1d24"), 0.02)
    save_png(img, "block", "screen_shell.png")
    save_png(screen_no_signal(), "screen", "no_signal.png")
    save_png(screen_boot(), "screen", "boot.png")
    save_png(Image.new("RGBA", (16, 16), (255, 255, 255, 255)), "screen", "white.png")
    for name, fn in gen_icons.ITEMS.items():
        save_png(fn().img, "item", name + ".png")
        flat_item(name)
    for kind in gen_icons.GHOSTS:
        save_png(gen_icons.ghost(kind).img, "gui", "slot_" + kind + ".png")
    atlas, _ = gen_icons.icon_atlas()
    save_png(atlas.img, "gui", "icons.png")
    lg = logo()
    lg.save(path(ROOT, "hypervisorx_logo.png"))


# --------------------------------------------------------------------------------------------- data

def loot_tables():
    simple = ["monitor", "crt_monitor", "curved_monitor", "dual_monitor_arm", "triple_monitor_arm",
              "video_wall_panel", "mechanical_keyboard", "gaming_mouse", "desktop_speakers", "delivery_crate"]
    for b in simple:
        write_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{b}"}],
                                                         "conditions": [{"condition": "minecraft:survives_explosion"}]}],
                    "random_sequence": f"{MOD}:blocks/{b}"}, "loot_table", "blocks", b + ".json", base=DATA)
    for b, comps in (("retro_pc", ["installed_parts", "vm_link"]), ("gaming_tower", ["installed_parts", "vm_link"]),
                     ("server_rack", ["installed_parts"]),
                     ("pro_laptop", ["vm_link", "remote_target"]), ("rugged_laptop", ["vm_link", "remote_target"])):
        write_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{
            "type": "minecraft:item", "name": f"{MOD}:{b}",
            "functions": [{"function": "minecraft:copy_components", "source": "block_entity",
                           "include": [f"{MOD}:{c}" for c in comps]}]}]}],
                    "random_sequence": f"{MOD}:blocks/{b}"}, "loot_table", "blocks", b + ".json", base=DATA)


def shaped(name, pattern, key, result=None, count=1):
    k = {c: ({"item": v} if ":" in v and not v.startswith("#") else {"tag": v[1:]} if v.startswith("#") else {"item": f"{MOD}:{v}"})
         for c, v in key.items()}
    write_json({"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern, "key": k,
                "result": {"id": f"{MOD}:{result or name}", "count": count}}, "recipe", name + ".json", base=DATA)


def shapeless(name, items, result=None, count=1):
    ing = [({"item": v} if ":" in v else {"item": f"{MOD}:{v}"}) for v in items]
    write_json({"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ing,
                "result": {"id": f"{MOD}:{result or name}", "count": count}}, "recipe", name + ".json", base=DATA)


def recipes():
    """Survival progression: every computer, display and part is craftable from vanilla stuff,
    and each tier upgrades the one before it, so the OrbitTech store is a shortcut, not a gate."""
    I, G, C, R, Q, D = "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:quartz", "minecraft:diamond"
    GP, GL, NS = "minecraft:glass_pane", "minecraft:glass", "minecraft:netherite_scrap"
    # tools & cables
    shaped("orbittech_tablet", ["IGI", "IRI", "ICI"], {"I": I, "G": GP, "R": R, "C": C})
    shapeless("hdmi_cable", [C, "minecraft:string", R], count=2)
    # CPUs
    shaped("cpu_orbitcore_d2", ["CRC", "RQR", "CRC"], {"C": C, "R": R, "Q": Q})
    shaped("cpu_orbitcore_q4", ["GRG", "RPR", "GRG"], {"G": G, "R": R, "P": "cpu_orbitcore_d2"})
    shaped("cpu_orbitcore_x8", ["DRD", "QPQ", "DRD"], {"D": D, "R": R, "Q": Q, "P": "cpu_orbitcore_q4"})
    shaped("cpu_orbitripper_16", ["NDN", "DPD", "NDN"], {"N": NS, "D": D, "P": "cpu_orbitcore_x8"})
    # memory: two sticks + a metal make the next size
    shaped("ram_1gb", ["RRR", "CCC", "GGG"], {"R": R, "C": C, "G": "minecraft:gold_nugget"})
    shapeless("ram_2gb", ["ram_1gb", "ram_1gb", "minecraft:iron_ingot"])
    shapeless("ram_4gb", ["ram_2gb", "ram_2gb", "minecraft:gold_ingot"])
    shapeless("ram_8gb", ["ram_4gb", "ram_4gb", "minecraft:redstone_block"])
    shapeless("ram_16gb", ["ram_8gb", "ram_8gb", "minecraft:diamond"])
    shapeless("ram_32gb", ["ram_16gb", "ram_16gb", "minecraft:netherite_scrap"])
    # graphics
    shaped("gpu_integrated", ["CQC", "RRR"], {"C": C, "Q": Q, "R": R})
    shaped("gpu_orbitforce_gt", ["III", "GPG", "CRC"], {"I": I, "G": GP, "P": "gpu_integrated", "C": C, "R": R})
    shaped("gpu_orbitforce_gx", ["GDG", "RPR", "GDG"], {"G": G, "D": D, "R": R, "P": "gpu_orbitforce_gt"})
    shaped("gpu_orbitforce_titan", ["NAN", "DPD", "NAN"], {"N": NS, "A": "minecraft:amethyst_shard", "D": D, "P": "gpu_orbitforce_gx"})
    # storage
    shaped("hdd_500gb", ["III", "IJI", "IRI"], {"I": I, "J": "minecraft:music_disc_13", "R": R})
    shaped("ssd_1tb", ["III", "QRQ", "III"], {"I": I, "Q": Q, "R": R})
    shapeless("nvme_2tb", ["ssd_1tb", "minecraft:gold_ingot", "minecraft:redstone_block"])
    shapeless("enterprise_hdd_8tb", ["hdd_500gb", "hdd_500gb", "minecraft:iron_block", "minecraft:diamond"])
    shaped("usb_flash_drive", ["N", "R", "C"], {"N": "minecraft:iron_nugget", "R": R, "C": C})
    # add-in cards and blades
    shaped("redstone_gpio_card", ["RTR", "CPC", "GGG"], {"R": R, "T": "minecraft:repeater", "C": "minecraft:comparator", "P": C,
                                                        "G": "minecraft:gold_nugget"})
    shaped("wifi_card", ["L L", "CQC", "GGG"], {"L": "minecraft:lightning_rod", "C": C, "Q": Q, "G": "minecraft:gold_nugget"})
    shaped("server_blade_x8", ["III", "PRP", "III"], {"I": I, "P": "cpu_orbitcore_x8", "R": "ram_8gb"})
    shaped("server_blade_x16", ["DID", "PBP", "DID"], {"D": D, "I": "minecraft:iron_block", "P": "cpu_orbitripper_16", "B": "server_blade_x8"})
    # computers
    shaped("retro_pc", ["TTT", "TRT", "TCT"], {"T": "minecraft:white_terracotta", "R": R, "C": "minecraft:chest"})
    shaped("gaming_tower", ["GIG", "GPG", "IAI"], {"G": GL, "I": I, "P": "retro_pc", "A": "minecraft:amethyst_shard"})
    shaped("server_rack", ["IBI", "IHI", "IBI"], {"I": I, "B": "minecraft:iron_bars", "H": "minecraft:hopper"})
    # displays
    shaped("monitor", ["III", "IGI", " R "], {"I": I, "G": GP, "R": R})
    shaped("crt_monitor", ["TTT", "TGT", "TRT"], {"T": "minecraft:white_terracotta", "G": GL, "R": R})
    shaped("curved_monitor", ["MMM", " I "], {"M": "monitor", "I": "minecraft:iron_block"})
    shaped("dual_monitor_arm", ["M M", " I "], {"M": "monitor", "I": I})
    shaped("triple_monitor_arm", ["MMM", " I "], {"M": "monitor", "I": I})
    shaped("video_wall_panel", ["GGG", "GRG", "III"], {"G": GP, "R": R, "I": I}, count=4)
    # laptops
    shaped("pro_laptop", ["IMI", "IKI", "IPI"], {"I": I, "M": "monitor", "K": "mechanical_keyboard", "P": "cpu_orbitcore_q4"})
    shaped("rugged_laptop", ["LBL", "BPB", "LBL"], {"L": "minecraft:leather", "B": "minecraft:iron_block", "P": "pro_laptop"})
    # peripherals
    shaped("mechanical_keyboard", ["BBB", "SRS"], {"B": "minecraft:stone_button", "S": "minecraft:smooth_stone_slab", "R": R})
    shaped("gaming_mouse", ["B", "S"], {"B": "minecraft:stone_button", "S": "minecraft:smooth_stone_slab"})
    shaped("desktop_speakers", ["N N", "IRI"], {"N": "minecraft:note_block", "I": I, "R": R})


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
