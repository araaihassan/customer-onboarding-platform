# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast audit.

Two tables, and the difference between them matters:

  PAIRS           literals copied from the PROTOTYPE AS HANDED OFF. This is the
                  evidence behind ux-design-review.md finding 1, so its failures
                  are a historical record and are meant to stay red. It says
                  nothing about the tokens shipping today.

  SHIPPED_PAIRS   the tokens that actually ship, named rather than copied and
                  resolved through build_tokens.py. A FAIL here is live.

SHIPPED_PAIRS resolves token NAMES instead of carrying hex values, so it cannot
drift from the generator it audits. It was rebuilt that way in Task R1 fix round
1, after a hardcoded 17-pair version reported "0 of 17 fail" while the dark
primary button sat at 1.53:1 -- every pair in it happened to be neutral, so the
accent and status roles were never looked at. Add a role here when you add one to
build_tokens.py.
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


# ================================================== the shipped tokens, resolved
# Colours are read from build_tokens.py, never repeated here. An audit holding
# its own copy of the palette drifts from the palette, and drift is the exact
# failure mode this table exists to prevent.
from build_tokens import semantic_color


def composite(fg_hex, alpha, bg_hex):
    """Flatten a translucent colour onto an opaque one.

    A linear blend of the encoded sRGB bytes, which is what axe-core does when it
    flattens a background stack -- and axe is the gate this has to satisfy.
    """
    f, b = hex_to_rgb(fg_hex), hex_to_rgb(bg_hex)
    return "#%02x%02x%02x" % tuple(
        round(255 * (alpha * f[i] + (1 - alpha) * b[i])) for i in range(3))


def resolve(token, theme, over=None):
    """A semantic token -> the opaque hex it renders as.

    `over` names the opaque surface a translucent token is painted on. The dark
    status fills are washes with no colour of their own, so a pill's real
    background depends on whether it sits on a card or a hovered table row --
    which is why the pills below are audited on both.
    """
    value = semantic_color(token, theme)
    if value[0] == "wash":
        if over is None:
            raise ValueError(f"{token} is translucent and needs a surface to sit on")
        return composite(value[1], value[2], resolve(over, theme))
    return value[1]


def P(label, fg, bg, size, weight, where, over=None, fg_alpha=1.0):
    """size 0 = non-text (1.4.11, 3:1). size -1 = informational, held to nothing."""
    return (label, fg, bg, size, weight, where, over, fg_alpha)


# The surfaces quiet text lands on. bg-inset (slate-700) is the BINDING ground in
# dark, mirroring #f2f0ec in light -- the lightest of them, not the darkest.
_TEXT_GROUNDS = [("page", "bg-page"), ("surface", "bg-surface"),
                 ("subtle", "bg-surface-subtle"), ("inset", "bg-inset")]

SHIPPED_PAIRS = [
    # ---------------------------------------------------------------- body text
    P("text-primary on surface",   "text-primary",   "bg-surface", 13, 400, "table cells, titles"),
    P("text-primary on page",      "text-primary",   "bg-page",    13, 400, "page headings"),
    P("text-primary on overlay",   "text-primary",   "bg-overlay", 13, 400, "popover body"),
    P("text-secondary on surface", "text-secondary", "bg-surface", 12, 400, "secondary Button label"),
    P("text-secondary on page",    "text-secondary", "bg-page",    12, 400, "stage labels"),
]
SHIPPED_PAIRS += [P(f"text-muted on {name}", "text-muted", token, 11, 400,
                    "supporting copy, meta") for name, token in _TEXT_GROUNDS]
SHIPPED_PAIRS += [P(f"text-faint on {name}", "text-faint", token, 10, 500,
                    "mono labels, timestamps") for name, token in _TEXT_GROUNDS]
SHIPPED_PAIRS += [
    P("text-disabled on surface", "text-disabled", "bg-surface", 12.5, 400, "struck-through titles"),

    # ---------------------------------------------------------------------- rail
    # The rail is identical in both themes by design, so these hold either way.
    P("text-on-rail on rail",        "text-on-rail", "bg-rail",        13, 400, "nav item"),
    P("text-on-rail on rail-raised", "text-on-rail", "bg-rail-raised", 13, 500, "active nav item"),
    P("nav inactive 72% on rail",    "text-on-rail", "bg-rail", 13, 400,
      "inactive nav, Sidebar opacity .72", None, 0.72),
    P("org slug 50% on rail",        "text-on-rail", "bg-rail", 10, 500,
      "Sidebar slug, opacity .5", None, 0.50),

    # -------------------------------------------------------------------- accent
    # Button.tsx paints a primary button as text-on-accent over accent with a 1px
    # accent border. That is the pair this widened table exists to catch: it read
    # 1.53:1 in dark and nothing measured it.
    P("text-on-accent on accent",       "text-on-accent", "accent",       13, 500, "PRIMARY BUTTON label"),
    P("text-on-accent on accent-hover", "text-on-accent", "accent-hover", 13, 500, "primary button hover"),
    P("accent-ink on accent-tint",      "accent-ink",     "accent-tint",   9, 500, "tinted panel ink"),
    P("accent-ink on surface",          "accent-ink",     "bg-surface",   12, 400, "links on a card"),
    # Non-text: the fill and the focus ring are what identify the control.
    P("accent on surface",              "accent", "bg-surface", 0, 0, "primary button edge, focus ring"),
    P("accent on page",                 "accent", "bg-page",    0, 0, "focus ring on the canvas"),
    P("accent-tint-border on tint",     "accent-tint-border", "accent-tint", 0, 0, "tinted panel edge"),
    P("accent-weak on surface",         "accent-weak", "bg-surface", 0, 0, "earlier chart periods"),

    # -------------------------------------------------------------------- status
]
# StatusPill.tsx paints status-{role}-fg over status-{role}-bg. In dark the fill
# is a translucent wash with no colour until composited, so each is audited on
# both surfaces a pill actually appears on.
for _role in ("on-track", "progress", "at-risk", "blocked", "neutral"):
    SHIPPED_PAIRS += [
        P(f"{_role} pill on surface", f"status-{_role}-fg", f"status-{_role}-bg",
          9.5, 500, "StatusPill on a card", "bg-surface"),
        P(f"{_role} pill on subtle", f"status-{_role}-fg", f"status-{_role}-bg",
          9.5, 500, "StatusPill on a hovered row", "bg-surface-subtle"),
    ]
SHIPPED_PAIRS += [
    P("solid-on-track on surface", "solid-on-track", "bg-surface", 0, 0, "milestone circle"),
    P("solid-at-risk on surface",  "solid-at-risk",  "bg-surface", 0, 0, "bottleneck bar, dot"),
    P("solid-blocked on surface",  "solid-blocked",  "bg-surface", 0, 0, "notification badge"),
    P("text-on-solid on blocked",  "text-on-solid",  "solid-blocked", 9.5, 400, "notification count"),

    # ------------------------------------------------------------------- borders
]
SHIPPED_PAIRS += [P(f"border-default on {name}", "border-default", token, 0, 0,
                    "card and control borders") for name, token in _TEXT_GROUNDS]
SHIPPED_PAIRS += [
    P("border-strong on surface", "border-strong", "bg-surface", 0, 0, "emphasis edge"),
    P("border-dashed on surface", "border-dashed", "bg-surface", 0, 0, "dropzone edge"),
    # Dividers identify no control, so 1.4.11 does not reach them: informational.
    P("border-subtle on surface", "border-subtle", "bg-surface", -1, 0, "table row divider"),
    P("border-panel on surface",  "border-panel",  "bg-surface", -1, 0, "panel divider"),

    # The rail's edge against the canvas. Informational, and deliberately so: two
    # adjacent background surfaces identify no component. It is capped at 1.17:1
    # while bg-rail stays pinned to slate-950 in both themes, so the visible edge
    # is a 1px border on the Sidebar component rather than a token move.
    P("rail against page",        "bg-rail",       "bg-page",   -1, 0, "see Sidebar's border"),
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
    """`pairs` are (label, fg hex, bg hex, size, weight, where)."""
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


def report_shipped(theme):
    """Resolve SHIPPED_PAIRS in one theme, then audit the resolved colours."""
    resolved = []
    for label, fg, bg, size, weight, where, over, fg_alpha in SHIPPED_PAIRS:
        ground = resolve(bg, theme, over)
        ink = resolve(fg, theme, over)
        if fg_alpha < 1.0:
            ink = composite(ink, fg_alpha, ground)
        resolved.append((label, ink, ground, size, weight, where))
    return report(
        theme.upper() + " THEME - the SHIPPED token values, resolved from "
        "build_tokens.py.\nEvery pair is one a component actually paints. "
        "A FAIL here is live.", resolved)


if __name__ == "__main__":
    import sys

    report("LIGHT THEME - the PROTOTYPE AS HANDED OFF, not the shipped tokens.\n"
           "This is the evidence behind ux-design-review.md finding 1, so its failures\n"
           "are the historical record of the problem and are meant to stay red. It says\n"
           "NOTHING about the light tokens shipping today: those are measured below by\n"
           "report_shipped('light'), which now runs on every invocation. See tokens.md.",
           PAIRS)
    light_fails = report_shipped("light")
    dark_fails = report_shipped("dark")
    if light_fails or dark_fails:
        sys.exit(1)
