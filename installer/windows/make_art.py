"""Draws the pictures for Convx's setup window (installer/windows/theme).

Run from the repository root: python3 installer/windows/make_art.py

The setup window is 560 x 360 at 100% scaling. Everything the setup draws over sits on pure black,
so every picture here is opaque on black and nothing depends on how Windows blends transparency.
Pictures that must stay sharp come in 1x, 2x and 3x; Burn picks the closest for the screen's
scaling. The window background is 1x only (Burn stretches it), so it holds only soft shapes.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "desktop" / "packaging"))
import make_icon  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "installer" / "windows" / "theme"
WORDMARK = ROOT / "desktop" / "packaging" / "convx-wordmark.png"
WORDMARK_GREY = (0xB9, 0xB9, 0xB9)

W, H = 560, 360
RED = (0xFA, 0x2D, 0x48)
BLACK = (0, 0, 0)
SCALES = (1, 2, 3)


def save(img, name, scale):
    suffix = "" if scale == 1 else f"@{scale}x"
    img.convert("RGB").save(OUT / f"{name}{suffix}.png", optimize=True)


def mark(size, scale):
    """Convx's crimson monogram on black, drawn from its vector (desktop/packaging/make_icon.py)."""
    px = size * scale
    m = make_icon.monogram(px)
    img = Image.new("RGB", (px, px), BLACK)
    img.paste(m, ((px - m.width) // 2, (px - m.height) // 2), m)
    return img


def wordmark(scale):
    """The "convx" wordmark (desktop/packaging/convx-wordmark.png), in its own grey, as the app shows it."""
    w, h = 132, 34
    src = Image.open(WORDMARK).getchannel("A")
    src = src.crop(src.getbbox())
    th = 24 * scale
    tw = min(w * scale, round(src.width * th / src.height))
    alpha = src.resize((tw, th), Image.LANCZOS)
    img = Image.new("RGB", (w * scale, h * scale), BLACK)
    img.paste(Image.new("RGB", alpha.size, WORDMARK_GREY), (0, (h * scale - th) // 2), alpha)
    return img


def pill(w, h, color, scale, ss=4, rim=None):
    """A rounded button. [rim] adds a lit edge, as the app's glass has, for the keyboard-focused button."""
    S = scale * ss
    img = Image.new("RGB", (w * S, h * S), BLACK)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w * S - 1, h * S - 1), radius=h * S // 2, fill=color)
    if rim:
        inset = round(2 * S / scale)
        d.rounded_rectangle((inset, inset, w * S - 1 - inset, h * S - 1 - inset), radius=h * S // 2 - inset,
                            outline=rim, width=max(1, round(1.5 * S / scale)))
    return img.resize((w * scale, h * scale), Image.LANCZOS)


def close_glyph(size, scale, color, ring=None, ss=4):
    S = size * scale * ss
    img = Image.new("RGB", (S, S), BLACK)
    d = ImageDraw.Draw(img)
    if ring:
        d.ellipse((0, 0, S - 1, S - 1), fill=ring)
    c, r = S / 2, S * 0.17
    width = max(1, round(1.6 * scale * ss))
    d.line((c - r, c - r, c + r, c + r), fill=color, width=width)
    d.line((c - r, c + r, c + r, c - r), fill=color, width=width)
    return img.resize((size * scale, size * scale), Image.LANCZOS)


def background():
    """Black, with a soft red glow and faint vinyl grooves at the right, away from every control."""
    # One pixel wider and taller than the window: Burn wants the picture larger than the window.
    bw, bh = W + 2, H + 2
    yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
    cx, cy = 492.0, 252.0
    r = np.hypot(xx - cx, yy - cy)
    glow = np.clip(1 - r / 175.0, 0, 1) ** 2.2 * 0.55
    grooves = np.zeros_like(r)
    for radius in range(44, 170, 13):
        grooves += np.exp(-((r - radius) ** 2) / (2 * 1.1 ** 2)) * 0.07 * np.clip(1 - radius / 175.0, 0.25, 1)
    rgb = np.zeros((bh, bw, 3), np.float32)
    for i, c in enumerate(RED):
        rgb[:, :, i] = glow * c
    rgb += (grooves * 255)[:, :, None]
    # A hairline edge, as the window has no frame of its own.
    edge = np.zeros((bh, bw), bool)
    edge[0, :W] = edge[H - 1, :W] = True
    edge[:H, 0] = edge[:H, W - 1] = True
    rgb[edge] = np.maximum(rgb[edge], 46)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")


def progress(scale):
    """Burn's progress picture: 4 columns (left end, done, to do, right end), stretched to fit."""
    h = 6 * scale
    img = Image.new("RGB", (4, h), BLACK)
    for x, color in enumerate((RED, RED, (0x2C, 0x2C, 0x2E), (0x2C, 0x2C, 0x2E))):
        for y in range(h):
            img.putpixel((x, y), color)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    background().save(OUT / "background.png", optimize=True)
    for s in SCALES:
        save(mark(52, s), "mark", s)
        save(wordmark(s), "wordmark", s)
        save(pill(156, 44, RED, s), "primary", s)
        save(pill(156, 44, (0xFF, 0x4D, 0x66), s), "primary-hover", s)
        save(pill(156, 44, (0xD6, 0x1F, 0x3A), s), "primary-pressed", s)
        save(pill(156, 44, RED, s, rim=(0xFF, 0xB3, 0xBF)), "primary-focus", s)
        save(pill(120, 44, (0x1C, 0x1C, 0x1E), s), "secondary", s)
        save(pill(120, 44, (0x2C, 0x2C, 0x2E), s), "secondary-hover", s)
        save(pill(120, 44, (0x3A, 0x3A, 0x3C), s), "secondary-pressed", s)
        save(pill(120, 44, (0x1C, 0x1C, 0x1E), s, rim=(0x6E, 0x6E, 0x73)), "secondary-focus", s)
        save(close_glyph(28, s, (0x8E, 0x8E, 0x93)), "close", s)
        save(close_glyph(28, s, (0xFF, 0xFF, 0xFF), ring=(0x2C, 0x2C, 0x2E)), "close-hover", s)
        save(close_glyph(28, s, (0xFF, 0xFF, 0xFF), ring=(0x3A, 0x3A, 0x3C)), "close-pressed", s)
        save(progress(s), "progress", s)


if __name__ == "__main__":
    main()
