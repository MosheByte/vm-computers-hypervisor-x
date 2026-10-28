"""Builds detailed Minecraft block models out of boxes and auto-packs their texture atlas.

A model is just a list of cuboids in block units (0..16). Each visible face gets its own spot in a
per-model atlas, all at the same texel density (2 texels per model unit, 32 per block), so a 16-wide
face is a 32 px strip. You paint a face "as seen from outside, top up" and the builder works out the
UVs, so painters never have to fight Minecraft's UV conventions.

A face can also point at a shared/animated texture (an RGB fan, say) with an explicit UV. Shared
strip textures are 32 px wide, so a UV of N units covers exactly 2N texels: put the face size in
model units as the UV and the density lines up with everything else.

Atlas regions get 2 px extruded gutters so mipmaps don't bleed into the neighbours, and every model
gets its own 16x16 particle texture instead of the atlas (which is mostly transparent).
"""
import json
import math
import os

from PIL import Image

from pixelkit import Canvas

DIRS = ("north", "south", "east", "west", "up", "down")
GUTTER = 2
EMISSIVE = {"block_light": 15, "sky_light": 15}


def face_size(d, frm, to):
    dx, dy, dz = to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]
    if d in ("north", "south"):
        return dx, dy
    if d in ("east", "west"):
        return dz, dy
    return dx, dz


class Ref:
    """A face that uses its own texture variable with an explicit 0..16 UV rect."""

    def __init__(self, var, uv=(0, 0, 16, 16), rotation=0):
        self.var = var
        self.uv = uv
        self.rotation = rotation


class Box:
    def __init__(self, frm, to, faces, rotation=None, shade=True, emissive=False):
        self.frm = [float(v) for v in frm]
        self.to = [float(v) for v in to]
        self.faces = faces  # dir -> painter(Canvas) | Ref | None
        self.rotation = rotation
        self.shade = shade
        self.emissive = emissive


# display transforms shared by the small desk items (vanilla block defaults otherwise)
def display(gui=None, ground=None, fixed=None, hand=None):
    d = {}
    if gui:
        d["gui"] = gui
    if ground:
        d["ground"] = ground
    if fixed:
        d["fixed"] = fixed
    if hand:
        d["thirdperson_righthand"] = hand
        d["firstperson_righthand"] = hand
    return d


class Model:
    def __init__(self, name, density=2, particle=None, render_type=None, ao=True, display=None, particle_paint=None):
        self.name = name
        self.density = density
        self.boxes = []
        self.textures = {}
        self.particle = particle
        self.particle_paint = particle_paint
        self.render_type = render_type
        self.ao = ao
        self.display = display

    def box(self, frm, to, faces=None, all=None, rotation=None, shade=True, skip=(), emissive=False, **per_face):
        f = {}
        for d in DIRS:
            if d in skip:
                continue
            if d in per_face:
                p = per_face[d]  # explicit None hides the face
            elif faces is not None and d in faces:
                p = faces[d]
            else:
                p = all
            if p is not None:
                f[d] = p
        b = Box(frm, to, f, rotation, shade and not emissive, emissive)
        self.boxes.append(b)
        return b

    def texture(self, var, path):
        self.textures[var] = path

    def _particle_painter(self):
        if self.particle_paint:
            return self.particle_paint
        for b in self.boxes:
            for p in b.faces.values():
                if not isinstance(p, Ref):
                    return p
        return None

    # ---------------------------------------------------------------------------------- baking
    def bake(self, mod, tex_dir, model_dir, atlas_name=None, tex_ns="block/model"):
        atlas_name = atlas_name or self.name
        regions = []  # (box, dir, w_px, h_px, painter)
        for b in self.boxes:
            for d, p in b.faces.items():
                if isinstance(p, Ref):
                    continue
                fw, fh = face_size(d, b.frm, b.to)
                wpx = max(1, int(math.ceil(fw * self.density - 1e-6)))
                hpx = max(1, int(math.ceil(fh * self.density - 1e-6)))
                regions.append((b, d, wpx, hpx, p))
        placements, size = pack([(r[2] + 2 * GUTTER, r[3] + 2 * GUTTER) for r in regions])
        atlas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        uvs = {}
        cache = {}
        for (b, d, wpx, hpx, p), (x, y) in zip(regions, placements):
            key = (id(p), wpx, hpx, d)
            if key in cache:
                img = cache[key]
            else:
                c = Canvas(wpx, hpx)
                c.face = d
                p(c)
                img = c.img
                cache[key] = img
            atlas.alpha_composite(extrude(img, GUTTER), (x, y))
            s = 16.0 / size
            x0, y0 = x + GUTTER, y + GUTTER
            uvs[(id(b), d)] = [round(x0 * s, 4), round(y0 * s, 4), round((x0 + wpx) * s, 4), round((y0 + hpx) * s, 4)]
        os.makedirs(tex_dir, exist_ok=True)
        textures = {}
        if regions:
            atlas.save(os.path.join(tex_dir, atlas_name + ".png"))
            textures["atlas"] = f"{mod}:{tex_ns}/{atlas_name}"
        textures.update(self.textures)
        if self.particle:
            textures["particle"] = self.particle
        else:
            pp = self._particle_painter()
            if pp is not None:
                c = Canvas(16, 16)
                c.face = "north"
                pp(c)
                # particles must be opaque, otherwise break chips are invisible
                flat_img = Image.new("RGBA", (16, 16), (40, 44, 52, 255))
                flat_img.alpha_composite(c.img)
                flat_img.save(os.path.join(tex_dir, atlas_name + "_p.png"))
                textures["particle"] = f"{mod}:{tex_ns}/{atlas_name}_p"
            else:
                textures["particle"] = textures.get("atlas", next(iter(self.textures.values()), "minecraft:block/black_concrete"))
        elements = []
        for b in self.boxes:
            faces = {}
            for d, p in b.faces.items():
                if isinstance(p, Ref):
                    face = {"texture": "#" + p.var, "uv": [round(v, 4) for v in p.uv]}
                    if p.rotation:
                        face["rotation"] = p.rotation
                else:
                    face = {"texture": "#atlas", "uv": uvs[(id(b), d)]}
                faces[d] = face
            e = {"from": [round(v, 4) for v in b.frm], "to": [round(v, 4) for v in b.to], "faces": faces}
            if b.rotation:
                e["rotation"] = b.rotation
            if not b.shade:
                e["shade"] = False
            if b.emissive:
                e["neoforge_data"] = dict(EMISSIVE)
            elements.append(e)
        m = {"parent": "block/block", "textures": textures, "elements": elements}
        if self.render_type:
            m["render_type"] = self.render_type
        if not self.ao:
            m["ambientocclusion"] = False
        if self.display:
            m["display"] = self.display
        os.makedirs(model_dir, exist_ok=True)
        with open(os.path.join(model_dir, self.name + ".json"), "w", encoding="utf-8") as f:
            json.dump(m, f, indent=1)
            f.write("\n")
        return size


def extrude(img, g):
    """Smears the edge texels of a region g pixels outward (gutter so mipmaps stay clean)."""
    w, h = img.size
    out = Image.new("RGBA", (w + 2 * g, h + 2 * g), (0, 0, 0, 0))
    out.paste(img, (g, g))
    src = img.load()
    dst = out.load()
    for y in range(h + 2 * g):
        sy = min(h - 1, max(0, y - g))
        for x in range(w + 2 * g):
            if g <= x < w + g and g <= y < h + g:
                continue
            sx = min(w - 1, max(0, x - g))
            dst[x, y] = src[sx, sy]
    return out


def pack(sizes):
    """Dumb shelf packer. Returns each rect's position and the power-of-two atlas edge."""
    order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0]))
    for edge in (16, 32, 64, 128, 256, 512, 1024):
        pos = [None] * len(sizes)
        x = y = shelf = 0
        ok = True
        for i in order:
            w, h = sizes[i]
            if w > edge:
                ok = False
                break
            if x + w > edge:
                x = 0
                y += shelf
                shelf = 0
            if y + h > edge:
                ok = False
                break
            pos[i] = (x, y)
            x += w
            shelf = max(shelf, h)
        if ok:
            return pos, edge
    raise ValueError("model atlas too large")
