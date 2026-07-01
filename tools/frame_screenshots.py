#!/usr/bin/env python3
"""Compose marketing Play Store screenshots from raw device captures.

Wraps a raw screen capture in a branded green background with a marketing
headline and a clean device frame (own status bar redrawn). Brand palette is
sampled from store/feature-graphic.png: primary #1E6B3C, surface #F5FBF2,
mint #A6F2BF.

Usage:
    frame_screenshots.py <spec.json> <out_dir>
where spec.json is a list of {src, out, headline, sub?, device?} entries.
device: "phone" (1080x1920 canvas) or "tablet" (1920x1200 / 1200x1920).
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

PRIMARY = (30, 107, 60)
PRIMARY_DARK = (18, 74, 41)
MINT = (166, 242, 191)
WHITE = (255, 255, 255)
SUB = (200, 230, 210)

SF = "/System/Library/Fonts/SFNS.ttf"
SF_RND = "/System/Library/Fonts/SFNSRounded.ttf"


def font(path, size):
    return ImageFont.truetype(path, size)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient_bg(w, h):
    """Diagonal brand gradient with a soft off-center radial glow (feature-graphic vibe)."""
    bg = Image.new("RGB", (w, h))
    px = bg.load()
    for y in range(h):
        t = y / h
        row = lerp(PRIMARY, PRIMARY_DARK, t * 0.85)
        for x in range(w):
            px[x, y] = row
    # radial glow: lighter green blob upper-right, like the feature graphic
    glow = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(glow)
    cx, cy, r = int(w * 0.82), int(h * 0.20), int(w * 0.75)
    gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.18))
    light = Image.new("RGB", (w, h), lerp(PRIMARY, MINT, 0.22))
    bg = Image.composite(light, bg, glow)
    return bg


def rounded_shadow(size, radius, blur, alpha=120):
    w, h = size
    pad = blur * 3
    sh = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(sh)
    d.rounded_rectangle([pad, pad, pad + w, pad + h], radius=radius, fill=(0, 0, 0, alpha))
    return sh.filter(ImageFilter.GaussianBlur(blur)), pad


def round_corners(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0], im.size[1]], radius=radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def draw_status_bar(im, bg_color, fg=(20, 40, 28), bar_h=None):
    """Redraw a clean status bar over the device screen's top strip.

    Paints a band in the app surface color and draws 9:41, wifi, full battery
    so the emulator's '3G'/demo glitches never show in the marketing shot.
    The band must match the device's REAL status-bar height (a roughly fixed
    pixel count) — too tall and it clips app headers that sit flush under the
    bar (e.g. the order-detail back-arrow row). Caller passes the measured
    height; the fallback scales conservatively by the short edge.
    """
    w, h = im.width, im.height
    if bar_h is None:
        bar_h = int(min(w, h) * 0.045)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w, bar_h], fill=bg_color)
    f = font(SF, int(bar_h * 0.42))
    pad = int(w * 0.05)
    cy = bar_h // 2
    # clock
    d.text((pad, cy), "9:41", font=f, fill=fg, anchor="lm")
    # right cluster: wifi arc, signal bars, battery
    bx = w - pad
    # battery
    bw, bh = int(bar_h * 0.52), int(bar_h * 0.28)
    by = cy - bh // 2
    d.rounded_rectangle([bx - bw, by, bx, by + bh], radius=int(bh * 0.25), fill=fg)
    d.rectangle([bx, by + bh // 3, bx + int(bw * 0.06), by + bh - bh // 3], fill=fg)
    # signal bars
    sx = bx - bw - int(w * 0.03)
    bar_w = int(w * 0.008)
    for i in range(4):
        bh2 = int(bar_h * (0.12 + i * 0.07))
        d.rounded_rectangle(
            [sx + i * (bar_w + int(w * 0.004)), cy + int(bar_h * 0.18) - bh2,
             sx + i * (bar_w + int(w * 0.004)) + bar_w, cy + int(bar_h * 0.18)],
            radius=int(bar_w * 0.4), fill=fg)
    # wifi (three arcs)
    wx = sx - int(w * 0.055)
    for i, rr in enumerate((int(bar_h * 0.22), int(bar_h * 0.15), int(bar_h * 0.08))):
        d.arc([wx - rr, cy - rr + int(bar_h * 0.12), wx + rr, cy + rr + int(bar_h * 0.12)],
              start=215, end=325, fill=fg, width=max(2, int(bar_w * 0.9)))
    d.ellipse([wx - bar_w // 2, cy + int(bar_h * 0.14), wx + bar_w // 2, cy + int(bar_h * 0.14) + bar_w], fill=fg)
    return im


def wrap(draw, text, fnt, max_w):
    words = text.split()
    lines, cur = [], ""
    for wd in words:
        test = (cur + " " + wd).strip()
        if draw.textlength(test, font=fnt) <= max_w:
            cur = test
        else:
            if cur:
                lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


def compose(spec, out_dir):
    device = spec.get("device", "phone")
    if device == "tablet-land":
        W, H = 2400, 1500
    elif device == "tablet-port":
        W, H = 1500, 2400
    else:
        W, H = 1080, 1920

    bg = gradient_bg(W, H).convert("RGBA")
    draw = ImageDraw.Draw(bg)

    # ---- headline block ----
    is_tab = device.startswith("tablet")
    head_size = int(W * (0.052 if is_tab else 0.072))
    sub_size = int(W * (0.026 if is_tab else 0.036))
    hf = font(SF, head_size)
    sf = font(SF, sub_size)
    margin = int(W * 0.08)
    top = int(H * 0.07)
    head_lines = wrap(draw, spec["headline"], hf, W - margin * 2)
    y = top
    for ln in head_lines:
        draw.text((margin, y), ln, font=hf, fill=WHITE)
        y += int(head_size * 1.12)
    if spec.get("sub"):
        y += int(head_size * 0.18)
        for ln in wrap(draw, spec["sub"], sf, W - margin * 2):
            draw.text((margin, y), ln, font=sf, fill=SUB)
            y += int(sub_size * 1.25)
    head_bottom = y

    # ---- device shot ----
    shot = Image.open(spec["src"]).convert("RGB")
    sw, sh = shot.size
    surface = tuple(shot.getpixel((10, int(sh * 0.5)))[:3]) if shot.mode == "RGB" else (245, 251, 242)
    # clean the status bar (redraw). status_h is the device's real bar height in
    # capture px (measure with `adb shell dumpsys`); defaults per device family.
    default_status = {"phone": 70, "tablet-port": 56, "tablet-land": 44}.get(device, 64)
    shot = draw_status_bar(shot, surface, bar_h=spec.get("status_h", default_status))

    frame_radius = int(sw * 0.055)
    shot_r = round_corners(shot, frame_radius)

    # scale device to fit remaining space
    avail_top = head_bottom + int(H * 0.03)
    avail_h = H - avail_top - int(H * 0.04)
    avail_w = W - margin * 2
    scale = min(avail_w / sw, avail_h / sh)
    nw, nh = int(sw * scale), int(sh * scale)
    shot_r = shot_r.resize((nw, nh), Image.LANCZOS)

    dx = (W - nw) // 2
    dy = avail_top + (avail_h - nh) // 2

    # shadow
    sh_img, pad = rounded_shadow((nw, nh), int(frame_radius * scale), int(W * 0.02), alpha=110)
    bg.alpha_composite(sh_img, (dx - pad, dy - pad + int(H * 0.008)))
    bg.alpha_composite(shot_r, (dx, dy))

    bg.convert("RGB").save(Path(out_dir) / spec["out"], "PNG")
    print("wrote", spec["out"])


def main():
    spec_path, out_dir = sys.argv[1], sys.argv[2]
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    specs = json.loads(Path(spec_path).read_text())
    for s in specs:
        compose(s, out_dir)


if __name__ == "__main__":
    main()
