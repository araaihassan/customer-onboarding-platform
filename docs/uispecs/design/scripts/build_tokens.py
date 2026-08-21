# -*- coding: utf-8 -*-
"""Onboard OS token system generator.

Three layers, each only allowed to reference the layer above it:

  primitive  raw values, named after what they ARE       paper-350, indigo-600
  semantic   named after the ROLE they play              border-default, text-muted
  component  named after WHERE they are used             rail-item-radius

Emits tokens.json (DTCG), tokens.css (light + dark), tailwind.css (@theme),
and a collapse map recording which stray literals in the prototype fold into
which token.
"""
import json
import pathlib

from oklch import oklch_to_hex

OUT = pathlib.Path(__file__).resolve().parent.parent / "02-tokens"


def ok(L, C, H):
    """oklch() string plus its exact sRGB fallback."""
    hx, _ = oklch_to_hex(L, C, H)
    return f"oklch({L} {C} {H})", hx


# ============================================================ layer 1: primitive
# Warm paper scale. Every neutral in the prototype folds into one of these.
PAPER = {
    "0":   "#ffffff",
    "25":  "#fdfcfb",
    "50":  "#faf9f7",
    "100": "#f7f6f3",
    "150": "#f4f2ee",
    "200": "#f2f0ec",
    "250": "#f0eee9",
    "300": "#efece7",
    "350": "#eae7e1",
    "400": "#e6e3dd",
    "450": "#dbd7cf",
    "500": "#d6d1c9",
    "550": "#ccc7bf",
    # 560 and 580 are the DARK theme's two quiet-text tiers, added in Task R1.
    # Light quiet text is bound by its darkest ground (#f2f0ec); dark quiet text is
    # bound by its LIGHTEST one, slate-700 (#34363c), and nothing already on this
    # ramp sits between paper-550 (7.18:1 there -- indistinguishable from
    # secondary) and paper-600 (3.52:1 -- below AA and graphics-only anyway).
    # 580 is the floor: 4.59:1 on slate-700, the tightest cell in the dark table.
    "560": "#b4afa7",
    "580": "#a49f97",
    # 600 is a graphics-only tier: it clears 3:1 on every surface above AND on
    # every dark ground (4.57 / 4.06 / 3.52 on slate-900 / 800 / 700), which is
    # enough for a 20px+ glyph, a decorative mark or a 1px border, and is NOT
    # valid for text in either theme.
    "600": "#8f8a82",
    # 700 is the accessible floor for quiet text ON THE LIGHT SURFACES:
    # >=4.5:1 on all six of them. It is nowhere near that on a dark ground --
    # 2.35:1 on slate-700 -- which is why the dark theme uses 560/580 instead.
    "700": "#716d67",
    "800": "#625f5a",
    "900": "#4d4a46",
    "950": "#191a1e",
}

# Rail / dark-surface scale, anchored on the prototype's #17181c.
SLATE = {
    "700": "#34363c",
    "800": "#2b2c31",
    "900": "#222328",
    "950": "#17181c",
    # 975 exists only so the dark theme's page has a ground the rail is not
    # already sitting on. bg-page and bg-rail both resolved to 950, so the rail
    # dissolved into the canvas at 1.00:1. The rail is pinned to 950 in BOTH
    # themes by design -- that is what keeps navigation stable when the theme
    # flips -- so the page is what had to move, and it can only move down.
    # Honest ceiling: even pure black is 1.17:1 against 950, so this edge is
    # 1.10:1 and can never be strong. A rail that needs a hard edge needs a
    # border on the component, which is not a token decision.
    "975": "#0b0c10",
}

# The 300 tier is the DARK theme's ink tier: bright enough to clear 4.5:1 on a
# low-alpha wash of its own hue over a dark surface, and deliberately low-chroma
# so it stays in this palette's register rather than going neon. indigo-300
# already existed and was the only status ink that passed in dark, which is what
# established the pattern; green/amber/red joined it in Task R1 fix round 1.
# Chroma per hue is the most the sRGB gamut allows at L=0.86 without clipping.
INDIGO = {
    "50":  ok(0.97, 0.02, 274),
    "100": ok(0.95, 0.03, 274),
    "200": ok(0.93, 0.03, 274),
    "300": ok(0.86, 0.04, 274),
    # 400 is the dark theme's accent-weak: dimmer than accent, but still >=3:1 on
    # bg-surface, because a chart series is a graphic required to understand the
    # content. indigo-800 was 1.77:1 there.
    "400": ok(0.60, 0.14, 274),
    "600": ok(0.52, 0.16, 274),
    "700": ok(0.45, 0.16, 274),
    "800": ok(0.42, 0.16, 274),
}
GREEN = {
    "100": ok(0.95, 0.04, 155),
    "300": ok(0.86, 0.09, 155),
    "500": ok(0.60, 0.12, 155),
    "700": ok(0.45, 0.12, 155),
}
AMBER = {
    "50":  ok(0.98, 0.02, 70),
    "100": ok(0.96, 0.05, 70),
    "200": ok(0.90, 0.05, 70),
    "300": ok(0.86, 0.09, 70),
    "500": ok(0.72, 0.14, 70),
    # 600 exists only for solid-at-risk, which shares one value across both
    # themes (SEMANTIC_SOLID has no light/dark split). amber-500 was 2.54:1 on
    # light bg-surface -- below 1.4.11's 3:1 -- so this is the least darkening
    # that clears it (3.21:1), and it still clears dark bg-surface comfortably
    # (4.89:1, down from 500's 6.17:1). amber-700, the at-risk pill ink, is far
    # past the floor (7.29:1 on light bg-surface) and would read as brown here.
    "600": ok(0.66, 0.14, 70),
    "700": ok(0.46, 0.14, 70),
}
RED = {
    "100": ok(0.96, 0.04, 25),
    "300": ok(0.86, 0.06, 25),
    "500": ok(0.57, 0.17, 25),
    "700": ok(0.50, 0.17, 25),
}

PRIMITIVE_SCALES = {
    "paper": PAPER, "slate": SLATE, "indigo": INDIGO,
    "green": GREEN, "amber": AMBER, "red": RED,
}

# Literals found in the prototype that are near-duplicates of a real token.
# Recorded so the migration is auditable rather than silent.
COLLAPSE = [
    ("#9a968f", "paper-700 (#716d67)", "faint text, darkened to clear WCAG AA"),
    ("#75726c", "paper-800 (#625f5a)", "muted text, darkened to clear WCAG AA"),
    ("#b0aba3", "paper-700 (#716d67)", "struck-through titles, darkened to clear AA"),
    ("#efece6", "paper-300 (#efece7)", "rail light background; one unit off the panel divider"),
    ("#ece9e3", "paper-350 (#eae7e1)", "one-off inset"),
    ("#ddd9d2", "paper-450 (#dbd7cf)", "one-off strong border"),
    ("#d9d5ce", "paper-500 (#d6d1c9)", "scrollbar thumb"),
    ("#c9c4bb", "paper-550 (#ccc7bf)", "one-off dashed border"),
    ("#b5b0a7", "paper-600 (#8f8a82)", "dropzone glyph; also darkened for contrast"),
    ("#8a867f", "paper-800 (#625f5a)", "one-off faint text; folds into muted"),
    ("oklch(0.94 0.03 274)", "indigo-200 (0.93 0.03 274)", "one-off tint edge"),
    ("oklch(0.93 0.04 70)", "amber-200 (0.90 0.05 70)", "one-off amber border"),
    ("oklch(0.93 0.03 70)", "amber-200 (0.90 0.05 70)", "one-off amber border"),
    ("oklch(0.48 0.14 70)", "amber-700 (0.46 0.14 70)", "one-off amber ink"),
    ("oklch(0.5 0.12 155)", "green-700 (0.45 0.12 155)", "one-off green ink"),
]

# ============================================================= layer 2: semantic
# (token, light value, dark value, note)
SEMANTIC_COLOR = [
    # surfaces
    ("bg-page",            "paper-100", "slate-975", "app canvas"),
    ("bg-surface",         "paper-0",   "slate-900", "cards, tables, panels"),
    ("bg-surface-subtle",  "paper-50",  "slate-800", "row hover, table header"),
    ("bg-surface-sunken",  "paper-25",  "slate-800", "read-only inspector fields"),
    ("bg-inset",           "paper-150", "slate-700", "progress tracks, neutral pills"),
    ("bg-inset-strong",    "paper-200", "slate-700", "avatars, secondary button hover"),
    ("bg-rail",            "slate-950", "slate-950", "navigation rail"),
    ("bg-rail-raised",     "slate-800", "slate-800", "active rail item"),
    ("bg-overlay",         "paper-0",   "slate-800", "popovers, notification panel"),
    # borders. The three that carry a control's edge resolve to paper-600 in BOTH
    # themes now, for the same reason in both: nothing lighter on this ramp clears
    # 3:1 (1.4.11) against the surfaces they sit on. Dark already established this
    # -- slate-700 gave 1.30:1 against bg-surface -- and light was simply never
    # measured until report_shipped('light') found paper-400/450/550 at
    # 1.15-1.28 / 1.44 / 1.68 against bg-surface. paper-600 is the documented
    # graphics-only floor that clears 3:1 on every light surface and every dark
    # ground, so it is the minimal fix for all three, in both themes -- exactly
    # the collapse dark already went through: lifting only border-default would
    # have made "strong" read weaker than "default". The dividers (subtle, panel)
    # stay put -- a row rule identifies nothing, so 1.4.11 does not reach it.
    ("border-default",     "paper-600", "paper-600", "card and control borders"),
    ("border-subtle",      "paper-150", "slate-800", "table row dividers"),
    ("border-panel",       "paper-300", "slate-800", "expanded panel dividers"),
    ("border-strong",      "paper-600", "paper-600", "open milestone, emphasis"),
    ("border-dashed",      "paper-600", "paper-600", "dropzones, add affordances"),
    # text
    ("text-primary",       "paper-950", "paper-100", "headings, table values"),
    ("text-secondary",     "paper-900", "paper-400", "body, stage labels"),
    ("text-muted",         "paper-800", "paper-560", "supporting copy, meta"),
    ("text-faint",         "paper-700", "paper-580", "mono labels, timestamps"),
    ("text-disabled",      "paper-700", "paper-580", "struck-through completed titles — same value as faint in BOTH themes; the state is carried by strike-through, not lightness, because neither palette has room for a third quiet grey clearing AA on the ground that binds it"),
    ("text-on-accent",     "paper-0",   "paper-950", "text on the accent fill — see the note below"),
    # A separate role from text-on-accent, and it has to be. Both are white in
    # light, so one token served both until the dark accent's ink had to invert;
    # the solid status fills do NOT invert, so riding on text-on-accent would put
    # near-black on solid-blocked in dark and drop it to 3.43:1.
    ("text-on-solid",      "paper-0",   "paper-0",   "text on a solid status fill, e.g. a badge count"),
    ("text-on-rail",       "paper-200", "paper-200", "rail navigation text"),
    # accent
    ("accent",             "indigo-600", "indigo-300", "primary action, active state"),
    ("accent-hover",       "indigo-700", "indigo-200", "primary action hover"),
    ("accent-tint",        "indigo-100", "slate-800",  "selection ring, rule strips"),
    # paper-600 in dark, not slate-700: slate-700 on slate-800 was 1.15:1, and
    # the same graphics tier already carries the other control-bearing edges.
    # Light can't borrow that trick: paper-600 on indigo-100 (the light tint) is
    # only 2.96:1, still under 3:1, because the tint itself is warm-neutral
    # enough that a warm-grey border nearly disappears into it. indigo-200 (the
    # light value this replaces) was 1.06:1. indigo-400 is the least darkening
    # inside the indigo ramp that clears 3:1 there (3.51:1) -- it is also
    # already the dark theme's accent-weak value, so it is not a new primitive.
    ("accent-tint-border", "indigo-400", "paper-600",  "edge of tinted panels"),
    ("accent-ink",         "indigo-800", "indigo-300", "text on accent tint, links"),
    # indigo-300 was 1.53:1 on bg-surface -- under 3:1. indigo-400 clears it
    # (4.07:1) and is the same value dark already uses for this exact role
    # ("dimmer than accent, but still >=3:1 ... a chart series is a graphic
    # required to understand the content"), so light now matches dark here.
    ("accent-weak",        "indigo-400", "indigo-400", "earlier periods in charts"),
]

# status role -> (fill primitive, ink primitive, meaning)
SEMANTIC_STATUS = [
    ("on-track", "green-100", "green-700", "Designed, On track, Signed, Completed"),
    ("progress", "indigo-100", "indigo-800", "In Progress, active stage"),
    ("at-risk",  "amber-100", "amber-700", "At risk, expiring, over SLA"),
    ("blocked",  "red-100",   "red-700",   "Blocked, overdue, cancelled"),
    ("neutral",  "paper-200", "paper-800", "Pending, Waiting, Draft, low priority"),
]

SEMANTIC_SOLID = [
    ("solid-on-track", "green-500", "completed milestone circle, healthy bar"),
    # amber-500 was 2.54:1 on light bg-surface -- under 1.4.11's 3:1 -- while
    # green-500/red-500 above and below already cleared it (3.73 / 4.84:1).
    # amber-600 is the least darkening that clears light (3.21:1) without
    # touching amber-500 itself, which the dark "at-risk" wash still uses; it
    # still clears dark bg-surface with room (4.89:1, down from 500's 6.17:1).
    ("solid-at-risk",  "amber-600", "bottleneck bar, warning dot"),
    ("solid-blocked",  "red-500",   "notification badge, blocked circle"),
]

# Status fills invert in dark: the tint becomes a low-alpha wash over whatever
# surface the pill sits on, because a solid pastel on a dark ground reads as a
# bright blob. Module level rather than inline in build() so contrast.py can
# import it -- the audit must read the same values the generator emits, never a
# copy that can drift.
# The inks are the 300 tier, NOT the 500s. A 500 is a mid-tone: over a wash of
# its own hue on a dark surface it measured 3.33 / 4.38 / 2.65:1 for on-track,
# at-risk and blocked. progress was the only one that passed, because it was
# already using indigo-300 -- so the other three now follow it.
DARK_STATUS = {
    "on-track": ("color-mix(in oklab, var(--ob-green-500) 18%, transparent)", "green-300"),
    "progress": ("color-mix(in oklab, var(--ob-indigo-600) 26%, transparent)", "indigo-300"),
    "at-risk":  ("color-mix(in oklab, var(--ob-amber-500) 20%, transparent)", "amber-300"),
    "blocked":  ("color-mix(in oklab, var(--ob-red-500) 22%, transparent)", "red-300"),
    # The neutral pill is the dark theme's binding case, exactly as #f2f0ec is
    # the light theme's: quiet ink on the lightest ground it ever lands on.
    # paper-700 here was 2.35:1. paper-560 is text-muted's dark value, which
    # mirrors the light pill's use of text-muted's light value.
    "neutral":  ("var(--ob-slate-700)", "paper-560"),
}


# ===================================================== resolution, for the audit
# contrast.py needs to answer "what colour does token X actually resolve to in
# theme Y". That knowledge lives here, in the generator, so there is exactly one
# copy of it. An audit holding its own hardcoded table is an audit that drifts
# from the thing it audits -- which is how the dark theme shipped unmeasured.

def primitive_hex(ref):
    """'paper-560' / 'indigo-300' -> '#rrggbb'."""
    family, step = ref.split("-", 1)
    value = PRIMITIVE_SCALES[family][step]
    # oklch scales carry (css, hex); the neutral scales are bare hex.
    return value[1] if isinstance(value, tuple) else value


def _dark_status_value(raw):
    """A DARK_STATUS fill -> ('hex', h) or ('wash', h, alpha)."""
    if raw.startswith("color-mix"):
        inner = raw.split("var(--ob-")[1]
        ref = inner.split(")")[0]
        percent = int(inner.split()[1].rstrip("%,"))
        return ("wash", primitive_hex(ref), percent / 100)
    return ("hex", primitive_hex(raw.split("var(--ob-")[1].split(")")[0]))


def semantic_color(token, theme):
    """A semantic token name + theme -> ('hex', h) or ('wash', h, alpha).

    'wash' means a translucent fill that has no colour until it is composited
    over the surface beneath it, which is exactly what a browser -- and axe --
    does at render time.
    """
    for name, light, dark, _note in SEMANTIC_COLOR:
        if name == token:
            return ("hex", primitive_hex(light if theme == "light" else dark))

    for name, fill, ink, _meaning in SEMANTIC_STATUS:
        if token == f"status-{name}-bg":
            return (("hex", primitive_hex(fill)) if theme == "light"
                    else _dark_status_value(DARK_STATUS[name][0]))
        if token == f"status-{name}-fg":
            return ("hex", primitive_hex(ink if theme == "light"
                                         else DARK_STATUS[name][1]))

    for name, value, _desc in SEMANTIC_SOLID:
        if name == token:
            return ("hex", primitive_hex(value))   # identical in both themes

    raise KeyError(f"no semantic token named {token!r}")

# ============================================================ layer 3: component
COMPONENT = [
    ("rail-width",              "244px",  "fixed, never fluid"),
    ("rail-item-height",        "32px",   "8px 11px padding at 13px text"),
    ("rail-item-radius",        "radius-control", ""),
    ("header-height",           "60px",   "sticky, blurred"),
    ("content-padding-x",       "space-28", ""),
    ("content-padding-top",     "space-24", ""),
    ("content-padding-bottom",  "space-56", "room for the last row to clear the fold"),
    ("card-radius",             "radius-card", ""),
    ("card-padding-y",          "space-16", "16-22 depending on density of contents"),
    ("card-padding-x",          "space-20", ""),
    ("grid-gap",                "space-16", "12-16 across dashboard rows"),
    ("kpi-value-size",          "type-29", ""),
    ("table-row-padding-y",     "space-13", "comfortable density"),
    ("table-row-padding-y-compact", "space-8", "compact density"),
    ("table-row-padding-x",     "space-20", ""),
    ("control-height",          "34px",   "search, buttons, primary actions"),
    ("control-height-sm",       "30px",   "filter chips, range chips"),
    ("control-height-xs",       "26px",   "stage reorder buttons"),
    ("pill-radius",             "radius-pill", ""),
    ("progress-track-height",   "6px",    "4-8 depending on context"),
    ("progress-track-height-lg","22px",   "pipeline-by-stage rows"),
    ("checkbox-size",           "17px",   ""),
    ("avatar-size",             "30px",   "22 in feeds, 28 rail, 46 workspace header"),
    ("touch-target-min",        "44px",   "mobile portal minimum"),
    ("focus-ring-width",        "2px",    "see the review note on focus visibility"),
]

SPACE = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 26, 28, 34, 44, 56]

TYPE_SCALE = [
    ("34", "34px", "600", "1.15", "-0.03em", "hero metric, portal percentage"),
    ("30", "30px", "600", "1.2",  "-0.03em", "portal headline"),
    ("29", "29px", "600", "1",    "-0.03em", "KPI value"),
    ("23", "23px", "600", "1.2",  "-0.02em", "mobile headline"),
    ("19", "19px", "600", "1.25", "-0.02em", "page object name"),
    ("17", "17px", "600", "1.3",  "-0.02em", "screen title"),
    ("15", "15px", "600", "1.3",  "-0.01em", "section heading"),
    ("14", "14px", "600", "1.35", "-0.01em", "card heading"),
    ("13-5","13.5px","600","1.35","0",       "panel heading, lede"),
    ("13", "13px", "400", "1.5",  "0",       "body, table cell"),
    ("12-5","12.5px","400","1.45","0",       "secondary text"),
    ("12", "12px", "400", "1.45", "0",       "dense table text"),
    ("11-5","11.5px","400","1.5", "0",       "tertiary, card sub-line"),
    ("11", "11px", "400", "1.4",  "0",       "meta, mono dates"),
    ("10-5","10.5px","400","1.4", "0",       "mono owner line"),
    ("10", "10px", "500", "1.3",  "0.08em",  "mono uppercase label"),
    ("9-5","9.5px", "500","1.3",  "0.09em",  "mono uppercase micro-label"),
]

RADIUS = [
    ("pill", "20px", "status pills, count badges"),
    ("overlay", "14px", "popovers, notification panel"),
    ("card", "13px", "cards, tables, dropzones"),
    ("avatar-lg", "12px", "large rounded-square avatars"),
    ("row", "11px", "workflow stage rows"),
    ("inner", "10px", "cards nested inside cards"),
    ("control", "9px", "buttons, inputs, rail items"),
    ("chip", "8px", "filter chips, small buttons"),
    ("segment", "7px", "segmented control, tile mark"),
    ("check", "5px", "checkboxes, progress tracks"),
    ("bar", "4px", "thin bars, kbd chips"),
    ("full", "9999px", "circular avatars and dots"),
]

ELEVATION = [
    ("flat", "none", "cards at rest — the default"),
    ("raised", "0 8px 24px -16px rgba(25,26,30,0.22)", "open milestone"),
    ("popover", "0 18px 44px -18px rgba(25,26,30,0.32)", "notification panel"),
    ("device", "0 30px 70px -30px rgba(25,26,30,0.45)", "mobile device frame"),
    ("ring-accent", "0 0 0 3px var(--ob-accent-tint)", "selected workflow stage"),
]

MOTION = [
    ("duration-instant", "0.16s", "popovers, panel expansion"),
    ("duration-fast", "0.18s", "chevron rotation"),
    ("duration-progress", "0.4s", "progress bar width"),
    ("duration-pulse", "2s", "live indicator"),
    ("ease-default", "ease", "everything — no custom curves"),
    ("keyframe-enter", "fadeUp", "translateY(6px) + opacity 0 -> none"),
    ("keyframe-live", "pulseDot", "opacity 1 -> 0.35 -> 1"),
]

FONT = [
    ("family-ui", "Archivo, 'Helvetica Neue', Helvetica, Arial, sans-serif",
     "all interface text"),
    ("family-data", "'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
     "IDs, dates, metrics, uppercase labels — anything a machine produced"),
    ("weight-regular", "400", ""),
    ("weight-medium", "500", ""),
    ("weight-semibold", "600", ""),
    ("weight-bold", "700", "reserved; unused in the current UI"),
]


def resolve(ref):
    """primitive reference -> css var()."""
    return f"var(--ob-{ref})"


def build():
    OUT.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------ JSON
    def prim_color(scale, name):
        out = {}
        for k, v in scale.items():
            if isinstance(v, tuple):
                out[k] = {"$value": v[0], "$type": "color", "$extensions": {"srgb": v[1]}}
            else:
                out[k] = {"$value": v, "$type": "color"}
        return out

    doc = {
        "$description": "Onboard OS design tokens. Layer order is primitive -> "
                        "semantic -> component; a layer may only reference the one above it.",
        "primitive": {
            "color": {
                "paper": prim_color(PAPER, "paper"),
                "slate": prim_color(SLATE, "slate"),
                "indigo": prim_color(INDIGO, "indigo"),
                "green": prim_color(GREEN, "green"),
                "amber": prim_color(AMBER, "amber"),
                "red": prim_color(RED, "red"),
            },
            "space": {str(n): {"$value": f"{n}px", "$type": "dimension"} for n in SPACE},
            "radius": {n: {"$value": v, "$type": "dimension", "$description": d}
                       for n, v, d in RADIUS},
            "font": {n: {"$value": v, "$type": "fontFamily" if "family" in n else "fontWeight",
                         "$description": d} for n, v, d in FONT},
            "type": {n: {"$value": {"fontSize": sz, "fontWeight": w, "lineHeight": lh,
                                    "letterSpacing": ls},
                         "$type": "typography", "$description": d}
                     for n, sz, w, lh, ls, d in TYPE_SCALE},
            "elevation": {n: {"$value": v, "$type": "shadow", "$description": d}
                          for n, v, d in ELEVATION},
            "motion": {n: {"$value": v, "$type": "duration" if "duration" in n else "other",
                           "$description": d} for n, v, d in MOTION},
        },
        "semantic": {
            "color": {
                **{n: {"$value": f"{{primitive.color.{l.split('-')[0]}.{l.split('-')[1]}}}",
                       "$type": "color", "$description": note,
                       "$extensions": {"dark": f"{{primitive.color.{d.split('-')[0]}."
                                               f"{d.split('-')[1]}}}"}}
                   for n, l, d, note in SEMANTIC_COLOR},
                **{f"status-{n}-bg": {"$value": f"{{primitive.color.{f.split('-')[0]}."
                                                f"{f.split('-')[1]}}}",
                                      "$type": "color", "$description": m}
                   for n, f, i, m in SEMANTIC_STATUS},
                **{f"status-{n}-fg": {"$value": f"{{primitive.color.{i.split('-')[0]}."
                                                f"{i.split('-')[1]}}}",
                                      "$type": "color", "$description": m}
                   for n, f, i, m in SEMANTIC_STATUS},
                **{n: {"$value": f"{{primitive.color.{v.split('-')[0]}.{v.split('-')[1]}}}",
                       "$type": "color", "$description": d} for n, v, d in SEMANTIC_SOLID},
            }
        },
        "component": {
            n: {"$value": (f"{{primitive.{v.split('-', 1)[0]}.{v.split('-', 1)[1]}}}"
                           if v.startswith(("radius-", "space-", "type-")) else v),
                "$type": "dimension", "$description": d}
            for n, v, d in COMPONENT
        },
        "$migration": {
            "$description": "Stray literals in the prototype and the token each folds into.",
            "collapse": [{"literal": a, "token": b, "where": c} for a, b, c in COLLAPSE],
        },
    }
    (OUT / "tokens.json").write_text(
        json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # ------------------------------------------------------------------- CSS
    L = []
    L.append("/* Onboard OS design tokens")
    L.append(" * Generated. Layer order: primitive -> semantic -> component.")
    L.append(" * Colour is authored in oklch(); an sRGB hex sits beside each primitive as a")
    L.append(" * comment for tools that cannot parse oklch.")
    L.append(" */")
    L.append("")
    L.append(":root {")
    L.append("  /* ---------------------------------------------------- primitive: colour */")
    for label, scale in (("paper", PAPER), ("slate", SLATE)):
        for k, v in scale.items():
            L.append(f"  --ob-{label}-{k}: {v};")
        L.append("")
    for label, scale in (("indigo", INDIGO), ("green", GREEN),
                         ("amber", AMBER), ("red", RED)):
        for k, (v, hx) in scale.items():
            L.append(f"  --ob-{label}-{k}: {v};  /* {hx} */")
        L.append("")

    L.append("  /* ---------------------------------------------------- primitive: space */")
    for n in SPACE:
        L.append(f"  --ob-space-{n}: {n}px;")
    L.append("")
    L.append("  /* --------------------------------------------------- primitive: radius */")
    for n, v, d in RADIUS:
        L.append(f"  --ob-radius-{n}: {v};" + (f"  /* {d} */" if d else ""))
    L.append("")
    L.append("  /* ----------------------------------------------------- primitive: type */")
    for n, v, d in FONT:
        L.append(f"  --ob-font-{n}: {v};")
    for n, sz, w, lh, ls, d in TYPE_SCALE:
        L.append(f"  --ob-type-{n}-size: {sz};")
        L.append(f"  --ob-type-{n}-weight: {w};")
        L.append(f"  --ob-type-{n}-line: {lh};")
        L.append(f"  --ob-type-{n}-tracking: {ls};  /* {d} */")
    L.append("")
    L.append("  /* ------------------------------------------------ primitive: elevation */")
    for n, v, d in ELEVATION:
        L.append(f"  --ob-elevation-{n}: {v};  /* {d} */")
    L.append("")
    L.append("  /* --------------------------------------------------- primitive: motion */")
    for n, v, d in MOTION:
        L.append(f"  --ob-{n}: {v};  /* {d} */")
    L.append("")

    L.append("  /* ============================================== semantic: light theme */")
    for n, light, dark, note in SEMANTIC_COLOR:
        L.append(f"  --ob-{n}: {resolve(light)};" + (f"  /* {note} */" if note else ""))
    L.append("")
    for n, f, i, m in SEMANTIC_STATUS:
        L.append(f"  --ob-status-{n}-bg: {resolve(f)};")
        L.append(f"  --ob-status-{n}-fg: {resolve(i)};  /* {m} */")
    L.append("")
    for n, v, d in SEMANTIC_SOLID:
        L.append(f"  --ob-{n}: {resolve(v)};  /* {d} */")
    L.append("")

    L.append("  /* ==================================================== component layer */")
    for n, v, d in COMPONENT:
        val = resolve(v) if v.startswith(("radius-", "space-", "type-")) else v
        L.append(f"  --ob-{n}: {val};" + (f"  /* {d} */" if d else ""))
    L.append("}")
    L.append("")
    L.append("/* ======================================================== dark theme")
    L.append(" * Only the semantic layer is redefined. Primitives never change, and no")
    L.append(" * component token is touched — that is the point of the middle layer.")
    L.append(" * The rail is already dark in both themes, so it does not move.")
    L.append(" */")
    L.append('[data-theme="dark"] {')
    for n, light, dark, note in SEMANTIC_COLOR:
        L.append(f"  --ob-{n}: {resolve(dark)};")
    L.append("")
    L.append("  /* Status fills invert: tint becomes a low-alpha wash over the dark surface,")
    L.append("     ink becomes the bright end of the ramp so it clears 4.5:1 on that wash. */")
    for n, f, i, m in SEMANTIC_STATUS:
        bg, fg = DARK_STATUS[n]
        L.append(f"  --ob-status-{n}-bg: {bg};")
        L.append(f"  --ob-status-{n}-fg: {resolve(fg)};")
    L.append("")
    L.append("  --ob-elevation-raised: 0 8px 24px -16px rgba(0,0,0,0.6);")
    L.append("  --ob-elevation-popover: 0 18px 44px -18px rgba(0,0,0,0.7);")
    L.append("  --ob-elevation-device: 0 30px 70px -30px rgba(0,0,0,0.8);")
    L.append("}")
    L.append("")
    L.append("@media (prefers-color-scheme: dark) {")
    L.append('  :root:not([data-theme="light"]) {')
    L.append("    color-scheme: dark;")
    L.append("  }")
    L.append("}")
    L.append("")
    L.append("/* Honour the OS reduced-motion preference. The prototype animates progress")
    L.append(" * widths, panel entry, and a live-feed pulse; all three are decorative. */")
    L.append("@media (prefers-reduced-motion: reduce) {")
    L.append("  :root {")
    L.append("    --ob-duration-instant: 0.01ms;")
    L.append("    --ob-duration-fast: 0.01ms;")
    L.append("    --ob-duration-progress: 0.01ms;")
    L.append("    --ob-duration-pulse: 0s;")
    L.append("  }")
    L.append("}")
    (OUT / "tokens.css").write_text("\n".join(L) + "\n", encoding="utf-8")

    # -------------------------------------------------------------- tailwind
    T = ["/* Onboard OS — Tailwind v4 theme.",
         " * Import after tokens.css:  @import './tokens.css'; @import './tailwind.css';",
         " * Maps the semantic layer onto Tailwind's utility namespaces so classes like",
         " * bg-surface, text-muted, border-default, rounded-card resolve to tokens.",
         " */", "", "@theme {"]
    T.append("  /* colours — semantic only; primitives are deliberately not exposed as utilities */")
    for n, light, dark, note in SEMANTIC_COLOR:
        T.append(f"  --color-{n}: var(--ob-{n});")
    for n, f, i, m in SEMANTIC_STATUS:
        T.append(f"  --color-status-{n}-bg: var(--ob-status-{n}-bg);")
        T.append(f"  --color-status-{n}-fg: var(--ob-status-{n}-fg);")
    for n, v, d in SEMANTIC_SOLID:
        T.append(f"  --color-{n}: var(--ob-{n});")
    T.append("")
    T.append("  /* radius */")
    for n, v, d in RADIUS:
        T.append(f"  --radius-{n}: var(--ob-radius-{n});")
    T.append("")
    T.append("  /* spacing — the scale is irregular by design, so it is enumerated */")
    for n in SPACE:
        T.append(f"  --spacing-{n}: var(--ob-space-{n});")
    T.append("")
    T.append("  /* type */")
    for n, sz, w, lh, ls, d in TYPE_SCALE:
        T.append(f"  --text-{n}: var(--ob-type-{n}-size);")
        T.append(f"  --text-{n}--line-height: var(--ob-type-{n}-line);")
        T.append(f"  --text-{n}--font-weight: var(--ob-type-{n}-weight);")
        T.append(f"  --text-{n}--letter-spacing: var(--ob-type-{n}-tracking);")
    T.append("")
    T.append("  --font-ui: var(--ob-font-family-ui);")
    T.append("  --font-data: var(--ob-font-family-data);")
    T.append("")
    T.append("  /* elevation */")
    for n, v, d in ELEVATION:
        T.append(f"  --shadow-{n}: var(--ob-elevation-{n});")
    T.append("}")
    T.append("")
    T.append("/* Dark mode is driven by the data-theme attribute, matching tokens.css. */")
    T.append('@custom-variant dark (&:where([data-theme="dark"], [data-theme="dark"] *));')
    (OUT / "tailwind.css").write_text("\n".join(T) + "\n", encoding="utf-8")

    print("tokens.json, tokens.css, tailwind.css written to", OUT)
    print(f"  primitives: {sum(len(s) for s in (PAPER, SLATE, INDIGO, GREEN, AMBER, RED))} colours, "
          f"{len(SPACE)} spaces, {len(RADIUS)} radii, {len(TYPE_SCALE)} type steps")
    print(f"  semantic: {len(SEMANTIC_COLOR) + len(SEMANTIC_STATUS) * 2 + len(SEMANTIC_SOLID)} roles")
    print(f"  component: {len(COMPONENT)} tokens")
    print(f"  collapse map: {len(COLLAPSE)} stray literals folded")


if __name__ == "__main__":
    build()
