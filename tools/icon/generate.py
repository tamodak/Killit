#!/usr/bin/env python3
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

SS = 4

INK = (177, 177, 177)
TILE = (44, 44, 44)

SQUIRCLE_N = 4.0
ART_FRAC = 0.72
SAFE_SCALE = 72.0 / 108.0

# The padlock, in units of its own height: the shackle's apex sits at y=0, the body's base
# at y=1, and x runs from 0 to MOTIF_W. Drawn rather than traced from a bitmap so that every
# density gets clean edges, and built from solid shapes rather than strokes because the
# launcher draws this 35px tall at mdpi, where an outline would disappear.
MOTIF_W = 0.72
BODY_TOP = 0.47
BODY_R = 0.11
SHACKLE_A = 0.270     # outer semi-axis across the arch
SHACKLE_B = 0.430     # outer semi-axis up it, and the height the arch springs from
SHACKLE_W = 0.100
SHACKLE_FOOT = 0.56   # where the legs stop, far enough under the body to stay hidden
CELL = 0.15
CELL_GAP = 0.05
CELL_R = 0.035

LEGACY_SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
ADAPTIVE_SIZES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}


def ink_mask(size):
    n = size * SS
    h = n * ART_FRAC
    ox, oy = (n - h * MOTIF_W) / 2.0, (n - h) / 2.0

    def at(x, y):
        return ox + x * h, oy + y * h

    img = Image.new('L', (n, n), 0)
    d = ImageDraw.Draw(img)
    cx = MOTIF_W / 2.0

    # The shackle is the band between two concentric ellipses, closed off below the body's
    # top edge. Filled as one polygon rather than stroked along its centre line, which PIL
    # renders with ragged joins at this width.
    ai, bi = SHACKLE_A - SHACKLE_W, SHACKLE_B - SHACKLE_W
    sweep = np.linspace(math.pi, 0.0, 400)
    arch = [at(cx - SHACKLE_A, SHACKLE_FOOT)]
    arch += [at(cx + SHACKLE_A * math.cos(a), SHACKLE_B - SHACKLE_B * math.sin(a)) for a in sweep]
    arch += [at(cx + SHACKLE_A, SHACKLE_FOOT), at(cx + ai, SHACKLE_FOOT)]
    arch += [at(cx + ai * math.cos(a), SHACKLE_B - bi * math.sin(a)) for a in sweep[::-1]]
    arch += [at(cx - ai, SHACKLE_FOOT)]
    d.polygon(arch, fill=255)

    d.rounded_rectangle([*at(0, BODY_TOP), *at(MOTIF_W, 1.0)], radius=BODY_R * h, fill=255)

    # The 2x2 grid is knocked back out of the body, so the apps being locked read as part of
    # the lock rather than as four separate marks floating on it.
    cy = (BODY_TOP + 1.0) / 2.0
    off = (CELL + CELL_GAP) / 2.0
    for sx in (-1, 1):
        for sy in (-1, 1):
            x, y = cx + sx * off - CELL / 2, cy + sy * off - CELL / 2
            d.rounded_rectangle([*at(x, y), *at(x + CELL, y + CELL)], radius=CELL_R * h, fill=0)

    return np.asarray(img.resize((size, size), Image.LANCZOS), np.float32) / 255.0


def superellipse(exponent, size, n=4000):
    a = np.linspace(0, 2 * math.pi, n)
    c, sn = np.cos(a), np.sin(a)
    e = 2.0 / exponent
    r = (size - 1) / 2.0
    return list(zip(r + r * np.sign(c) * np.abs(c) ** e,
                    r + r * np.sign(sn) * np.abs(sn) ** e))


def tile_mask(kind, size):
    if kind == 'square':
        return np.ones((size, size), np.float32)
    n = size * SS
    img = Image.new('L', (n, n), 0)
    d = ImageDraw.Draw(img)
    if kind == 'circle':
        d.ellipse([0, 0, n - 1, n - 1], fill=255)
    else:
        d.polygon(superellipse(SQUIRCLE_N, n), fill=255)
    return np.asarray(img.resize((size, size), Image.LANCZOS), np.float32) / 255.0


def render(size, tile='squircle', scale=1.0, color=INK, opaque=False):
    inner = round(size * scale)
    ink = ink_mask(inner)
    rgba = np.zeros((inner, inner, 4), np.float32)
    if tile is None:
        rgba[..., 0:3] = np.array(color, np.float32) / 255.0
        rgba[..., 3] = ink
    else:
        for c in range(3):
            rgba[..., c] = TILE[c] / 255 + (color[c] - TILE[c]) / 255 * ink
        rgba[..., 3] = tile_mask(tile, inner)
    img = Image.fromarray((np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8), 'RGBA')
    if inner != size:
        canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(img, ((size - inner) // 2, (size - inner) // 2))
        img = canvas
    return img.convert('RGB') if opaque else img


def save(img, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == '.webp':
        img.save(path, 'WEBP', lossless=True, quality=100, method=6)
    else:
        img.save(path)
    print('%-64s %dx%d' % (path, *img.size))


def main(root):
    root = Path(root)
    res = root / 'app/src/main/res'

    for density, size in LEGACY_SIZES.items():
        save(render(size), res / f'mipmap-{density}/ic_launcher.webp')
        save(render(size, tile='circle'), res / f'mipmap-{density}/ic_launcher_round.webp')

    for density, size in ADAPTIVE_SIZES.items():
        save(render(size, tile=None, scale=SAFE_SCALE),
             res / f'mipmap-{density}/ic_launcher_foreground.webp')
        save(render(size, tile=None, scale=SAFE_SCALE, color=(255, 255, 255)),
             res / f'mipmap-{density}/ic_launcher_monochrome.webp')

    save(render(1024), root / 'killit_icon.png')
    save(render(512, tile='square', opaque=True), root / 'killit_icon_play_512.png')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
