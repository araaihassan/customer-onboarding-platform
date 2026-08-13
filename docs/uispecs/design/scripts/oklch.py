# -*- coding: utf-8 -*-
"""oklch() -> sRGB hex, so the token file can carry an accurate fallback."""
import math


def oklch_to_hex(L, C, H):
    h = math.radians(H)
    a, b = C * math.cos(h), C * math.sin(h)

    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3

    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s

    def enc(u):
        u = 1.055 * (u ** (1 / 2.4)) - 0.055 if u > 0.0031308 else 12.92 * u
        return max(0, min(255, round(u * 255)))

    clipped = any(v < -0.001 or v > 1.001 for v in (r, g, bl))
    return "#%02x%02x%02x" % (enc(max(r, 0)), enc(max(g, 0)), enc(max(bl, 0))), clipped


TOKENS = [
    ("accent",            0.52, 0.16, 274),
    ("accent-hover",      0.45, 0.16, 274),
    ("accent-tint",       0.95, 0.03, 274),
    ("accent-ink",        0.42, 0.16, 274),
    ("accent-chart-light",0.86, 0.04, 274),
    ("accent-ring",       0.93, 0.03, 274),
    ("accent-rule-fill",  0.97, 0.02, 274),
    ("success",           0.60, 0.12, 155),
    ("success-tint",      0.95, 0.04, 155),
    ("success-ink",       0.45, 0.12, 155),
    ("warning",           0.72, 0.14, 70),
    ("warning-tint",      0.96, 0.05, 70),
    ("warning-ink",       0.46, 0.14, 70),
    ("warning-panel",     0.98, 0.02, 70),
    ("warning-border",    0.90, 0.05, 70),
    ("danger",            0.57, 0.17, 25),
    ("danger-tint",       0.96, 0.04, 25),
    ("danger-ink",        0.50, 0.17, 25),
]

if __name__ == "__main__":
    for name, L, C, H in TOKENS:
        hx, clipped = oklch_to_hex(L, C, H)
        flag = "  <-- out of sRGB gamut, clipped" if clipped else ""
        print(f"{name:20s} oklch({L} {C} {H})  ->  {hx}{flag}")
