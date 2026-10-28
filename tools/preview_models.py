"""Quick offline ortho preview of block models, so I can iterate on art without launching the game.

    python tools/preview_models.py <out.png> [model names...]
"""
import json
import math
import os
import sys

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common", "src", "main", "resources", "assets", "hypervisorx")
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def load_tex(ref, cache={}):
    ns, path = ref.split(":")
    if ref in cache:
        return cache[ref]
    p = os.path.join(ROOT, "textures", path + ".png")
    im = Image.open(p).convert("RGBA")
    if im.height > im.width:
        im = im.crop((0, 0, im.width, im.width))
    cache[ref] = im
    return im


def resolve(model):
    tex = dict(model.get("textures", {}))
    def r(v):
        while v.startswith("#"):
            v = tex[v[1:]]
        return v
    return r


def corners(d, a, b):
    x0, y0, z0 = a
    x1, y1, z1 = b
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[d]


NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def rot(p, r):
    if not r:
        return p
    o = r["origin"]
    a = math.radians(r["angle"])
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    c, s = math.cos(a), math.sin(a)
    if r["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif r["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def render(model_path, size=320, cam=(1.0, 0.75, -1.25)):
    m = json.load(open(model_path, encoding="utf-8"))
    r = resolve(m)
    d = cam
    n = math.sqrt(sum(v * v for v in d))
    d = tuple(v / n for v in d)
    f = tuple(-v for v in d)
    right = (f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0)
    rn = math.sqrt(sum(v * v for v in right))
    right = tuple(v / rn for v in right)
    up = (right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0])
    scale = size / 26.0
    def proj(p):
        q = (p[0] - 8, p[1] - 8, p[2] - 8)
        return (size / 2 + (q[0] * right[0] + q[1] * right[1] + q[2] * right[2]) * scale,
                size / 2 - (q[0] * up[0] + q[1] * up[1] + q[2] * up[2]) * scale,
                q[0] * f[0] + q[1] * f[1] + q[2] * f[2])
    quads = []
    for e in m.get("elements", []):
        for dname, face in e["faces"].items():
            nrm = rot(NORMAL[dname], dict(e["rotation"], origin=[0, 0, 0])) if e.get("rotation") else NORMAL[dname]
            if sum(nrm[i] * d[i] for i in range(3)) <= 0:
                continue
            cs = [rot(p, e.get("rotation")) for p in corners(dname, e["from"], e["to"])]
            ps = [proj(p) for p in cs]
            depth = sum(p[2] for p in ps) / 4
            quads.append((depth, ps, face, dname, e.get("shade", True)))
    quads.sort(key=lambda q: -q[0])
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for depth, ps, face, dname, shade_on in quads:
        tex = load_tex(r(face["texture"]))
        tw, th = tex.size
        tp = tex.load()
        u0, v0, u1, v1 = face.get("uv", [0, 0, 16, 16])
        a, b, _, c3 = ps[0], ps[1], ps[2], ps[3]
        ex = (b[0] - a[0], b[1] - a[1])
        ey = (c3[0] - a[0], c3[1] - a[1])
        det = ex[0] * ey[1] - ex[1] * ey[0]
        if abs(det) < 1e-6:
            continue
        xs = [p[0] for p in ps]
        ys = [p[1] for p in ps]
        k = SHADE[dname] if shade_on else 1.0
        for y in range(max(0, int(min(ys))), min(size, int(max(ys)) + 1)):
            for x in range(max(0, int(min(xs))), min(size, int(max(xs)) + 1)):
                qx, qy = x + 0.5 - a[0], y + 0.5 - a[1]
                s = (qx * ey[1] - qy * ey[0]) / det
                t = (ex[0] * qy - ex[1] * qx) / det
                if 0 <= s <= 1 and 0 <= t <= 1:
                    u = (u0 + (u1 - u0) * s) / 16 * tw
                    v = (v0 + (v1 - v0) * t) / 16 * th
                    col = tp[min(tw - 1, max(0, int(u))), min(th - 1, max(0, int(v)))]
                    if col[3] == 0:
                        continue
                    cc = (int(col[0] * k), int(col[1] * k), int(col[2] * k))
                    if col[3] < 255:
                        bg = px[x, y]
                        al = col[3] / 255
                        cc = tuple(int(bg[i] * (1 - al) + cc[i] * al) for i in range(3))
                        px[x, y] = cc + (max(bg[3], col[3]),)
                    else:
                        px[x, y] = cc + (255,)
    return img


if __name__ == "__main__":
    out = sys.argv[1]
    names = sys.argv[2:]
    tiles = []
    for nm in names:
        p = os.path.join(ROOT, "models", "block", nm + ".json")
        a = render(p)
        b = render(p, cam=(-1.0, 0.6, -1.3))
        tiles.append((nm, a, b))
    W = 2 * 320
    sheet = Image.new("RGBA", (W * min(3, len(tiles)), 330 * ((len(tiles) + 2) // 3)), (120, 150, 190, 255))
    for i, (nm, a, b) in enumerate(tiles):
        x = (i % 3) * W
        y = (i // 3) * 330
        sheet.alpha_composite(a, (x, y))
        sheet.alpha_composite(b, (x + 320, y))
    sheet.save(out)
