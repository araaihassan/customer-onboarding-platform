# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast audit of the design's actual colour pairs.

Two tables. PAIRS is the light theme, measured from the prototype's own values and
the source of ux-design-review.md finding 1. DARK_PAIRS is the dark theme, added in
Task R1 -- the dark values had never been measured at all, which is how four of them
shipped below AA.
"""
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


# ============================================================== dark theme
# The four grounds quiet text and borders land on under [data-theme="dark"].
# The BINDING one is the lightest, slate-700 -- the same discipline the light
# audit applies at its darkest ground, #f2f0ec.
D_PAGE = "#0b0c10"    # slate-975  bg-page
D_RAIL = "#17181c"    # slate-950  bg-rail (identical in both themes, by design)
D_SURF = "#222328"    # slate-900  bg-surface
D_SUB = "#2b2c31"     # slate-800  bg-surface-subtle / sunken / overlay / rail-raised
D_INSET = "#34363c"   # slate-700  bg-inset / status-neutral-bg

# Non-text pairs carry size 0 and weight 0; see `large` and `threshold` below.
# 1.4.11 asks 3:1 of anything required to identify a control -- an input's border
# is, a card's is arguably decoration, but the token is shared so it is held to
# the stricter reading.
DARK_PAIRS = [
    ("text-primary on surface",    "#f7f6f3", D_SURF,  13, 400, "table cells, titles"),
    ("text-primary on page",       "#f7f6f3", D_PAGE,  13, 400, "page-level headings"),
    ("text-secondary on surface",  "#e6e3dd", D_SURF,  12, 400, "stage labels"),
    ("text-muted on surface",      "#b4afa7", D_SURF,  11.5, 400, "card sub-lines"),
    ("text-muted on page",         "#b4afa7", D_PAGE,  11, 400, "header meta line"),
    ("text-muted on subtle",       "#b4afa7", D_SUB,   11, 400, "table header row"),
    ("text-muted on inset",        "#b4afa7", D_INSET, 10, 500, "neutral pill ink"),
    ("text-faint on surface",      "#a49f97", D_SURF,  10, 500, "MONO UPPERCASE LABELS"),
    ("text-faint on subtle",       "#a49f97", D_SUB,   9.5, 500, "table header labels"),
    ("text-faint on inset",        "#a49f97", D_INSET, 10, 500, "worst case for quiet text"),
    ("text-disabled on surface",   "#a49f97", D_SURF,  12.5, 400, "struck-through titles"),
    ("text-on-rail on rail",       "#f2f0ec", D_RAIL,  13, 400, "active nav item"),

    ("border-default on surface",  "#8f8a82", D_SURF,   0, 0, "card and control borders"),
    ("border-default on page",     "#8f8a82", D_PAGE,   0, 0, "card edge against the canvas"),
    ("border-default on subtle",   "#8f8a82", D_SUB,    0, 0, "control on a hovered row"),
    ("border-default on inset",    "#8f8a82", D_INSET,  0, 0, "control inside an inset"),
    # Informational (-1), not a 1.4.11 requirement: two adjacent background
    # surfaces identify no component, and the rail is identified by its links.
    # It is measured because it was 1.00:1 -- the rail literally dissolved into
    # the page. The ceiling is 1.17:1 (a pure black page), because bg-rail is
    # pinned to slate-950 in both themes; a harder edge needs a border on the
    # component, which is not a token decision.
    ("rail against page",          D_RAIL,    D_PAGE,  -1, 0, "surface separation, ceiling 1.17"),
]


def large(size, weight):
    """WCAG large text: >=18.66px bold, or >=24px."""
    return size >= 24 or (size >= 18.66 and weight >= 700)


def threshold(size, weight):
    """(AA, AAA) for a pair.

    size  0 marks a non-text pair: 1.4.11, 3:1, no AAA.
    size -1 marks an informational pair: measured, held to nothing.
    """
    if size < 0:
        return None, None
    if size == 0:
        return 3.0, None
    return (3.0, 4.5) if large(size, weight) else (4.5, 7.0)


def report(title, pairs):
    print(title)
    print(f"{'pair':32s} {'fg':9s} {'bg':9s} {'px':>5s} {'ratio':>6s}  AA   AAA  where")
    print("-" * 118)
    fails = []
    for label, fg, bg, size, weight, where in pairs:
        r = ratio(fg, bg)
        need_aa, need_aaa = threshold(size, weight)
        aa = "note" if need_aa is None else ("pass" if r >= need_aa else "FAIL")
        aaa = "n/a" if need_aaa is None else ("pass" if r >= need_aaa else "fail")
        if aa == "FAIL":
            fails.append((label, r, need_aa, where))
        px = "  n/t" if size == 0 else ("  inf" if size < 0 else f"{size:5.1f}")
        print(f"{label:32s} {fg:9s} {bg:9s} {px} {r:6.2f}  {aa:4s} {aaa:4s} {where}")

    print()
    print(f"{len(fails)} of {len(pairs)} pairs fail WCAG AA for their size:")
    for label, r, need, where in fails:
        print(f"  - {label:30s} {r:.2f}:1  (needs {need}:1)  — {where}")
    print()
    return fails


report("LIGHT THEME — the PROTOTYPE AS HANDED OFF, not the shipped tokens.\n"
       "This table is the evidence behind ux-design-review.md finding 1, so its\n"
       "failures are the historical record of the problem. The fixed values are in\n"
       "the 'Now' column of that finding's table and in tokens.css.", PAIRS)
report("DARK THEME  [data-theme=\"dark\"] — the SHIPPED token values.\n"
       "Unlike the light table this measures what tokens.css actually emits, so a\n"
       "failure here is a live defect. Keep it that way.", DARK_PAIRS)
