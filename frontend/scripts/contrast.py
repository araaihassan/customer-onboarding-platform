# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast audit for the current (light-only) token set.

Hex pairs mirror tokens.css, which mirrors
docs/uispecs_latest/design_handoff_onboarding_platform/DESIGN_TOKENS.md by name -- with four
deliberate exceptions: text-subtle, text-faint, ok-fg and warn-fg are darkened from the design
doc's literal values (2026-08-28) because those literal values measured below WCAG AA at the
sizes they're actually used at. This is a values-only, hue-preserving deviation from the design
doc, not an invented one -- see tokens.css's own comments and the SDD ledger for the full record.
A FAIL here is live -- these are the values tokens.css actually ships.
"""


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


CANVAS = "#fbfaf8"
SURFACE = "#ffffff"
SUNKEN = "#faf9f6"
INK = "#1c1b18"

# (label, fg, bg, size_px, weight, where) -- size 0 = non-text (1.4.11, 3:1)
PAIRS = [
    ("ink on surface",           INK,       SURFACE, 13,   400, "table cells, titles"),
    ("ink on canvas",            INK,       CANVAS,  13,   400, "page headings"),
    ("text-2 on surface",        "#4a4741", SURFACE, 12,   400, "callout secondary copy"),
    ("text-muted on surface",    "#6b6862", SURFACE, 11.5, 400, "supporting copy"),
    ("text-muted on canvas",     "#6b6862", CANVAS,  11.5, 400, "header meta"),
    ("text-subtle on surface",   "#75726b", SURFACE, 11.5, 400, "row subtitles"),
    ("text-subtle on sunken",    "#75726b", SUNKEN,  11.5, 400, "table header labels"),
    ("text-faint on surface",    "#75726b", SURFACE, 10,   500, "mono uppercase labels"),
    ("text-faint on sunken",     "#75726b", SUNKEN,  9.5,  500, "table header labels"),
    ("canvas on ink",            CANVAS,    INK,     12.5, 600, "primary button label"),
    ("canvas on ink-hover",      CANVAS,    "#302e2a", 12.5, 600, "primary button hover"),
    ("accent-fg on surface",     "#10736b", SURFACE, 11.5, 400, "links, active state"),
    ("accent-fg on accent-bg",   "#10736b", "#e6f2f0", 9,   500, "accent chip"),

    ("ok-fg on ok-bg",           "#2e7b4d", "#e8f3ec", 9.5, 500, "status chip"),
    ("warn-fg on warn-bg",       "#986210", "#fbf1de", 9.5, 500, "status chip"),
    ("risk-fg on risk-bg",       "#b4392f", "#fbeae7", 9.5, 500, "status chip"),
    ("info-fg on info-bg",       "#2b5fb0", "#e9f0fb", 9.5, 500, "status chip"),
    ("automation-fg on automation-bg", "#6a4fb0", "#f0ebfa", 9.5, 500, "status chip"),
    ("neutral-fg on neutral-bg", "#6b6862", "#f2f0ec", 9.5, 500, "status chip"),

    ("canvas on ink (rail)",     CANVAS,    INK,     13,   400, "rail brand mark glyph"),
    ("avatar initials",         CANVAS,    "#4b4842", 13,  600, "neutral avatar fill"),

    ("line on surface",          "#e7e4de", SURFACE, 0,    0,   "card and control borders"),
    ("line-strong on surface",   "#dedad3", SURFACE, 0,    0,   "emphasis edge, 20px+ marks"),
]


def large(size, weight):
    return size >= 24 or (size >= 18.66 and weight >= 700)


def threshold(size, weight):
    if size == 0:
        return 3.0, None
    return (3.0, 4.5) if large(size, weight) else (4.5, 7.0)


def report(pairs):
    print(f"{'pair':32s} {'fg':9s} {'bg':9s} {'px':>5s} {'ratio':>6s}  AA   where")
    print("-" * 100)
    fails = []
    for label, fg, bg, size, weight, where in pairs:
        r = ratio(fg, bg)
        need_aa, _ = threshold(size, weight)
        aa = "pass" if r >= need_aa else "FAIL"
        if aa == "FAIL":
            fails.append((label, r, need_aa, where))
        px = "  n/t" if size == 0 else f"{size:5.1f}"
        print(f"{label:32s} {fg:9s} {bg:9s} {px} {r:6.2f}  {aa:4s} {where}")
    print()
    print(f"{len(fails)} of {len(pairs)} pairs fail WCAG AA for their size:")
    for label, r, need, where in fails:
        print(f"  - {label:30s} {r:.2f}:1  (needs {need}:1)  -- {where}")
    return fails


if __name__ == "__main__":
    import sys
    fails = report(PAIRS)
    sys.exit(1 if fails else 0)
