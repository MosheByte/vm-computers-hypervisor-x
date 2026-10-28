#!/usr/bin/env python3
"""
Makes the assembly sound effects for the motherboard screen (OGG Vorbis, 44.1 kHz mono) and adds
them to sounds.json. It's all synthesised from damped partials, filtered noise and short transients,
so no third-party samples to license.
"""
import json
import os

import numpy as np
import soundfile as sf

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "hypervisorx")
OUT = os.path.join(ASSETS, "sounds", "assembly")
RATE = 44100
rng = np.random.default_rng(1790)


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(n, attack=0.001, decay=0.05):
    x = np.arange(n) / RATE
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return a * np.exp(-np.maximum(0, x - attack) / decay)


def partials(freqs, seconds, decay, amps=None, detune=0.0):
    x = t(seconds)
    out = np.zeros_like(x)
    amps = amps or [1.0] * len(freqs)
    for f, a in zip(freqs, amps):
        f = f * (1 + rng.uniform(-detune, detune))
        out += a * np.sin(2 * np.pi * f * x + rng.uniform(0, 6.28))
    return out * env(len(x), 0.0008, decay)


def noise(seconds, lo=None, hi=None, decay=0.03, attack=0.0005):
    n = int(RATE * seconds)
    w = rng.standard_normal(n)
    spec = np.fft.rfft(w)
    freqs = np.fft.rfftfreq(n, 1 / RATE)
    mask = np.ones_like(freqs)
    if lo:
        mask *= 1 / (1 + (lo / np.maximum(freqs, 1)) ** 4)
    if hi:
        mask *= 1 / (1 + (freqs / hi) ** 4)
    w = np.fft.irfft(spec * mask, n)
    w /= np.max(np.abs(w)) + 1e-9
    return w * env(n, attack, decay)


def click(sharp=1.0, body=2400, seconds=0.06):
    """Plastic snap: broadband transient + a short resonant body."""
    return 0.9 * noise(seconds, lo=1800 * sharp, hi=12000, decay=0.006) + 0.5 * partials([body, body * 1.63, body * 2.7], seconds, 0.012, [1, 0.5, 0.25])


def thump(freq=110, seconds=0.25, decay=0.07):
    x = t(seconds)
    sweep = freq * (1 + 0.6 * np.exp(-x / 0.02))
    phase = 2 * np.pi * np.cumsum(sweep) / RATE
    return np.sin(phase) * env(len(x), 0.002, decay)


def place(dst, src, at):
    i = int(at * RATE)
    end = min(len(dst), i + len(src))
    dst[i:end] += src[: end - i]


def finish(x, gain=0.8):
    x = x - np.mean(x)
    fade = min(len(x), int(0.004 * RATE))
    x[-fade:] *= np.linspace(1, 0, fade)
    peak = np.max(np.abs(x)) + 1e-9
    return (x / peak * gain).astype(np.float32)


def cpu_drop():
    x = np.zeros(int(RATE * 0.25))
    place(x, 0.5 * noise(0.03, lo=3000, hi=14000, decay=0.004), 0.0)
    place(x, 0.7 * partials([2900, 4650, 7100], 0.12, 0.02, [1, 0.6, 0.3]), 0.002)
    place(x, 0.35 * thump(180, 0.15, 0.03), 0.0)
    return finish(x, 0.6)


def lever():
    x = np.zeros(int(RATE * 0.5))
    place(x, 0.25 * noise(0.12, lo=1500, hi=6000, decay=0.05, attack=0.03), 0.0)
    place(x, 0.9 * click(1.4, 3200), 0.11)
    place(x, 0.8 * partials([1840, 3120, 4710, 6020], 0.35, 0.09, [1, 0.7, 0.45, 0.25], 0.004), 0.112)
    place(x, 0.4 * thump(140, 0.2, 0.04), 0.11)
    return finish(x, 0.75)


def ram():
    x = np.zeros(int(RATE * 0.3))
    place(x, 0.2 * noise(0.08, lo=800, hi=5000, decay=0.04, attack=0.02), 0.0)
    place(x, click(1.2, 2600), 0.07)
    place(x, 0.85 * click(1.1, 2450), 0.135)
    place(x, 0.3 * thump(160, 0.12, 0.025), 0.07)
    return finish(x, 0.8)


def gpu():
    x = np.zeros(int(RATE * 0.55))
    place(x, 0.25 * noise(0.15, lo=500, hi=4000, decay=0.06, attack=0.05), 0.0)
    place(x, thump(92, 0.4, 0.09), 0.13)
    place(x, 0.5 * noise(0.05, lo=200, hi=2500, decay=0.015), 0.13)
    place(x, 0.8 * click(1.3, 2900), 0.2)
    return finish(x, 0.85)


def screw():
    x = np.zeros(int(RATE * 0.7))
    at = 0.0
    for k in range(7):
        place(x, (0.35 + 0.08 * k) * click(2.2, 5200 + 140 * k, 0.025), at)
        at += 0.085 - 0.006 * k
    place(x, 0.4 * partials([1200, 2650], 0.12, 0.03, [1, 0.5]), at + 0.01)
    return finish(x, 0.6)


def card():
    x = np.zeros(int(RATE * 0.3))
    place(x, 0.2 * noise(0.08, lo=900, hi=5000, decay=0.03, attack=0.03), 0.0)
    place(x, 0.6 * thump(150, 0.18, 0.03), 0.07)
    place(x, click(1.2, 3000), 0.08)
    return finish(x, 0.75)


def usb():
    x = np.zeros(int(RATE * 0.75))
    place(x, 0.3 * noise(0.06, lo=2000, hi=9000, decay=0.03, attack=0.02), 0.0)
    place(x, click(1.6, 3600, 0.04), 0.05)
    # "device connected": two soft bell tones
    for i, f in enumerate([880, 1318.5]):
        place(x, 0.35 * partials([f, f * 2, f * 3.01], 0.45, 0.12, [1, 0.25, 0.08]), 0.2 + i * 0.13)
    return finish(x, 0.6)


def remove():
    x = np.zeros(int(RATE * 0.35))
    place(x, 0.9 * click(1.3, 2300), 0.0)
    place(x, 0.35 * noise(0.22, lo=600, hi=3500, decay=0.08, attack=0.04), 0.03)
    return finish(x, 0.7)


def post():
    """Good old single short BIOS beep, aka everything passed."""
    x = t(0.24)
    sq = np.sign(np.sin(2 * np.pi * 1000 * x)) * 0.5 + np.sin(2 * np.pi * 1000 * x) * 0.5
    e = np.clip(x / 0.004, 0, 1) * np.clip((0.2 - x) / 0.01, 0, 1)
    return finish(sq * e, 0.4)


def pick():
    """Soft UI pop when the part list opens."""
    x = t(0.12)
    f = 600 + 900 * (1 - np.exp(-x / 0.02))
    s = np.sin(2 * np.pi * np.cumsum(f) / RATE) * env(len(x), 0.002, 0.03)
    return finish(s, 0.35)


SOUNDS = {
    "cpu_drop": (cpu_drop, "CPU seated", "המעבד הונח בשקע"),
    "cpu_lever": (lever, "CPU socket locked", "תפס המעבד ננעל"),
    "ram_click": (ram, "Memory clicks in", "הזיכרון ננעל בחריץ"),
    "gpu_seat": (gpu, "Graphics card seated", "כרטיס המסך הוכנס"),
    "screw": (screw, "Screw tightened", "בורג הודק"),
    "card_seat": (card, "Card seated", "כרטיס הוכנס"),
    "usb_plug": (usb, "USB device connected", "התקן USB חובר"),
    "part_remove": (remove, "Part removed", "רכיב הוסר"),
    "post_beep": (post, "Motherboard beeps", "צפצוף לוח אם"),
    "part_pick": (pick, "Part list opens", "רשימת רכיבים נפתחת"),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (fn, _, _) in SOUNDS.items():
        data = fn()
        sf.write(os.path.join(OUT, name + ".ogg"), data, RATE, format="OGG", subtype="VORBIS")
        print("wrote", name, f"{len(data) / RATE:.2f}s")
    path = os.path.join(ASSETS, "sounds.json")
    with open(path, encoding="utf-8") as f:
        sounds = json.load(f)
    for name in SOUNDS:
        sounds["assembly_" + name] = {
            "subtitle": "subtitles.hypervisorx.assembly_" + name,
            "sounds": [{"name": "hypervisorx:assembly/" + name, "volume": 1.0}],
        }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(sounds, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
