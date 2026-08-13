# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast audit of the prototype's actual colour pairs."""
from oklch import oklch_to_hex


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def lum(c):
    def f(u):
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (f(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg, bg):
    a, b = lum(hex_to_rgb(fg)), lum(hex_to_rgb(bg))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


OK = lambda L, C, H: oklch_to_hex(L, C, H)[0]

PAPER = "#f7f6f3"
WHITE = "#ffffff"
SUBTLE = "#faf9f7"
RAIL = "#17181c"

# (label, fg, bg, size_px, weight, where)
PAIRS = [
    ("text-primary on surface",    "#191a1e", WHITE,  13, 400, "table cells, titles"),
    ("text-secondary on surface",  "#4d4a46", WHITE,  12, 400, "stage labels"),
    ("text-muted on surface",      "#75726c", WHITE,  11.5, 400, "card sub-lines, reasons"),
    ("text-muted on page",         "#75726c", PAPER,  11, 400, "header meta line"),
    ("text-faint on surface",      "#9a968f", WHITE,  10, 500, "MONO UPPERCASE LABELS"),
    ("text-faint on subtle",       "#9a968f", SUBTLE, 9.5, 500, "table header labels"),
    ("text-disabled on surface",   "#b0aba3", WHITE,  12.5, 400, "struck-through task titles"),
    ("dropzone glyph",             "#b5b0a7", WHITE,  20, 400, "upload affordance"),

    ("accent on surface",          OK(0.52, 0.16, 274), WHITE, 11.5, 400, "'Mark all read', links"),
    ("accent-ink on accent-tint",  OK(0.42, 0.16, 274), OK(0.95, 0.03, 274), 9, 500, "branch rule strip"),
    ("white on accent",            WHITE, OK(0.52, 0.16, 274), 12.5, 500, "primary button"),
    ("white on accent-hover",      WHITE, OK(0.45, 0.16, 274), 12.5, 500, "primary button hover"),

    ("on-track pill",              OK(0.45, 0.12, 155), OK(0.95, 0.04, 155), 10, 500, "health pill"),
    ("at-risk pill",               OK(0.46, 0.14, 70),  OK(0.96, 0.05, 70),  10, 500, "health pill"),
    ("blocked pill",               OK(0.50, 0.17, 25),  OK(0.96, 0.04, 25),  10, 500, "health pill"),
    ("neutral pill",               "#75726c", "#f2f0ec", 10, 500, "Pending / Waiting pill"),
    ("white on danger badge",      WHITE, OK(0.57, 0.17, 25), 9.5, 400, "notification count"),
    ("delta green on surface",     OK(0.45, 0.12, 155), WHITE, 11, 400, "KPI delta"),
    ("delta red on surface",       OK(0.50, 0.17, 25),  WHITE, 11, 400, "KPI delta"),

    ("rail ink on rail",           "#f2f0ec", RAIL, 13, 400, "active nav item"),
    ("rail ink 72% on rail",       "#b6b4af", RAIL, 13, 400, "inactive nav (0.72 opacity)"),
    ("rail slug 50% on rail",      "#83817d", RAIL, 10, 500, "org slug (0.5 opacity)"),
    ("rail label 45% on rail",     "#787672", RAIL, 9.5, 500, "'VIEWING AS' (0.45 opacity)"),
    ("amber avatar ink",           "#191a1e", OK(0.72, 0.14, 70), 11, 600, "user avatar initials"),
]


def large(size, weight):
    """WCAG large text: >=18.66px bold, or >=24px."""
    return size >= 24 or (size >= 18.66 and weight >= 700)


print(f"{'pair':32s} {'fg':9s} {'bg':9s} {'px':>5s} {'ratio':>6s}  AA   AAA  where")
print("-" * 118)
fails = []
for label, fg, bg, size, weight, where in PAIRS:
    r = ratio(fg, bg)
    need_aa = 3.0 if large(size, weight) else 4.5
    need_aaa = 4.5 if large(size, weight) else 7.0
    aa = "pass" if r >= need_aa else "FAIL"
    aaa = "pass" if r >= need_aaa else "fail"
    if aa == "FAIL":
        fails.append((label, r, need_aa, where))
    print(f"{label:32s} {fg:9s} {bg:9s} {size:5.1f} {r:6.2f}  {aa:4s} {aaa:4s} {where}")

print()
print(f"{len(fails)} of {len(PAIRS)} pairs fail WCAG AA for their size:")
for label, r, need, where in fails:
    print(f"  - {label:30s} {r:.2f}:1  (needs {need}:1)  — {where}")
