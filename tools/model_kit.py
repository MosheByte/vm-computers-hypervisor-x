"""Builds detailed Minecraft block models out of boxes and auto-packs their texture atlas.

A model is a list of cuboids in block units (0..16). Every visible face gets its own region in a
per-model atlas painted at a fixed texel density (texels per model unit), so a 16-wide face at
density 2 is a 32-pixel strip. Faces are painted "as seen from outside, top up"; the builder emits
the matching UVs, so painters never have to think about Minecraft's UV conventions.

Faces may instead reference a shared/animated texture (e.g. an RGB fan) with an explicit UV.
"""
import json
import math
import os

from PIL import Image

from pixelkit import Canvas

DIRS = ("north", "south", "east", "west", "up", "down")


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
    def __init__(self, frm, to, faces, rotation=None, shade=True, tint=None, name=None):
        self.frm = [float(v) for v in frm]
        self.to = [float(v) for v in to]
        self.faces = faces  # dir -> painter(Canvas) | Ref | None
        self.rotation = rotation
        self.shade = shade
        self.name = name


class Model:
    def __init__(self, name, density=2, particle=None, render_type=None, ao=True):
        self.name = name
        self.density = density
        self.boxes = []
        self.textures = {}
        self.particle = particle
        self.render_type = render_type
        self.ao = ao

    def box(self, frm, to, faces=None, all=None, rotation=None, shade=True, skip=(), **per_face):
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
        b = Box(frm, to, f, rotation, shade)
        self.boxes.append(b)
        return b

    def texture(self, var, path):
        self.textures[var] = path

    # ---------------------------------------------------------------------------------- baking
    def bake(self, mod, tex_dir, model_dir, atlas_name=None):
        atlas_name = atlas_name or self.name
        regions = []  # (box, dir, w_px, h_px)
        for b in self.boxes:
            for d, p in b.faces.items():
                if isinstance(p, Ref):
                    continue
                fw, fh = face_size(d, b.frm, b.to)
                wpx = max(1, int(math.ceil(fw * self.density - 1e-6)))
                hpx = max(1, int(math.ceil(fh * self.density - 1e-6)))
                regions.append((b, d, wpx, hpx, p))
        placements, size = pack([(r[2], r[3]) for r in regions])
        atlas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        uvs = {}
        cache = {}
        for (b, d, wpx, hpx, p), (x, y) in zip(regions, placements):
            key = (id(p), wpx, hpx)
            if key in cache:
                img = cache[key]
            else:
                c = Canvas(wpx, hpx)
                c.face = d
                p(c)
                img = c.img
                cache[key] = img
            atlas.alpha_composite(img, (x, y))
            s = 16.0 / size
            uvs[(id(b), d)] = [round(x * s, 4), round(y * s, 4), round((x + wpx) * s, 4), round((y + hpx) * s, 4)]
        if regions:
            os.makedirs(tex_dir, exist_ok=True)
            atlas.save(os.path.join(tex_dir, atlas_name + ".png"))
        textures = {"atlas": f"{mod}:block/model/{atlas_name}"} if regions else {}
        textures.update(self.textures)
        textures["particle"] = self.particle or textures.get("atlas")
        elements = []
        for b in self.boxes:
            faces = {}
            for d, p in b.faces.items():
                if isinstance(p, Ref):
                    face = {"texture": "#" + p.var, "uv": list(p.uv)}
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
            elements.append(e)
        m = {"parent": "block/block", "textures": textures, "elements": elements}
        if self.render_type:
            m["render_type"] = self.render_type
        if not self.ao:
            m["ambientocclusion"] = False
        os.makedirs(model_dir, exist_ok=True)
        with open(os.path.join(model_dir, self.name + ".json"), "w", encoding="utf-8") as f:
            json.dump(m, f, indent=1)
            f.write("\n")
        return size


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
