# -*- coding: utf-8 -*-
"""For each failing pair, find the minimal darkening that reaches WCAG AA.

Keeps the original hue and chroma in OKLCh and walks lightness down until the
pair passes, so the palette stays warm rather than turning grey.
"""
import colorsys

from contrast import ratio, hex_to_rgb
from oklch import oklch_to_hex

import math


def hex_to_oklch(h):
    """sRGB hex -> OKLCh (inverse of oklch.py)."""
    r, g, b = hex_to_rgb(h)

    def dec(u):
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = dec(r), dec(g), dec(b)

    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)

    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    bb = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    C = math.hypot(a, bb)
    H = math.degrees(math.atan2(bb, a)) % 360
    return L, C, H


def solve(fg_hex, bg_hex, target):
    """Walk L down in 0.002 steps, holding C and H, until the ratio clears target."""
    L, C, H = hex_to_oklch(fg_hex)
    best = None
    for i in range(0, 400):
        cand_L = L - i * 0.002
        if cand_L <= 0:
            break
        hx, _ = oklch_to_hex(cand_L, C, H)
        if ratio(hx, bg_hex) >= target:
            best = (round(cand_L, 3), round(C, 3), round(H, 1), hx, ratio(hx, bg_hex))
            break
    return best


FAILING = [
    ("text-muted on page",       "#75726c", "#f7f6f3", 4.5, "header meta line"),
    ("text-faint on surface",    "#9a968f", "#ffffff", 4.5, "mono uppercase labels"),
    ("text-faint on subtle",     "#9a968f", "#faf9f7", 4.5, "table header labels"),
    ("text-disabled on surface", "#b0aba3", "#ffffff", 4.5, "struck-through titles"),
    ("dropzone glyph",           "#b5b0a7", "#ffffff", 3.0, "20px, qualifies as large-ish UI glyph"),
    ("neutral pill",             "#75726c", "#f2f0ec", 4.5, "Pending / Waiting pill"),
    ("rail label 45%",           "#787672", "#17181c", 4.5, "'VIEWING AS' label"),
]

print("Minimal fix per failing pair — hue and chroma held, lightness walked down\n")
print(f"{'pair':28s} {'was':9s} {'ratio':>6s}  ->  {'fix':9s} {'ratio':>6s}  oklch")
print("-" * 96)
for label, fg, bg, target, note in FAILING:
    before = ratio(fg, bg)
    got = solve(fg, bg, target)
    if not got:
        print(f"{label:28s} {fg:9s} {before:6.2f}  ->  no solution")
        continue
    L, C, H, hx, r = got
    print(f"{label:28s} {fg:9s} {before:6.2f}  ->  {hx:9s} {r:6.2f}  oklch({L} {C} {H})")

print("\nAlternative for the rail label: raise the opacity instead of the colour.")
for opacity in (0.45, 0.55, 0.6, 0.65, 0.7):
    # #f2f0ec ink over #17181c rail at the given alpha
    ink = hex_to_rgb("#f2f0ec")
    rail = hex_to_rgb("#17181c")
    mixed = tuple(ink[i] * opacity + rail[i] * (1 - opacity) for i in range(3))
    hx = "#%02x%02x%02x" % tuple(round(c * 255) for c in mixed)
    print(f"  opacity {opacity:.2f} -> {hx}  ratio {ratio(hx, '#17181c'):.2f}:1")
