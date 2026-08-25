# -*- coding: utf-8 -*-
"""Onboard OS icon system generator.

Single source of truth for the icon set. Every icon is ONE path on a 24x24 grid,
1.5px stroke, round caps/joins, no fill, live area inset to 3..21.
Single-path is deliberate: it lets the prototype bind path data from its data
layer (`<path d="{{ item.iconPath }}">`) and keeps the sprite trivial.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "03-icons"

# name -> (category, path, description)
ICONS = {
    # ---------------------------------------------------------------- navigation
    "layout-dashboard": ("navigation",
        "M4 5a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"
        "M13 5a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1z"
        "M13 12a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1z"
        "M4 14a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z",
        "Dashboard / operational hub"),
    "users": ("navigation",
        "M15 20v-1.5a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4V20"
        "M12 7.5a3.5 3.5 0 1 1-7 0a3.5 3.5 0 0 1 7 0"
        "M17 14.2a4 4 0 0 1 5 3.8V20M16 4.3a3.5 3.5 0 0 1 0 6.8",
        "Customers / accounts list"),
    "journey": ("navigation",
        "M5.5 7a2 2 0 1 0 0-4a2 2 0 0 0 0 4M5.5 14a2 2 0 1 0 0-4a2 2 0 0 0 0 4"
        "M5.5 21a2 2 0 1 0 0-4a2 2 0 0 0 0 4M5.5 7v3M5.5 14v3"
        "M10 5h10M10 12h7M10 19h8",
        "Journey workspace / staged roadmap"),
    "workflow": ("navigation",
        "M4 3.5h6v5H4zM14 15h6v5h-6zM7 8.5v7a2 2 0 0 0 2 2h5",
        "Workflow builder / stage graph"),
    "chart-column": ("navigation",
        "M4 4v15a1 1 0 0 0 1 1h15M8.5 16.5v-4M13 16.5v-7M17.5 16.5v-10",
        "Reports & analytics"),
    "portal-window": ("navigation",
        "M3 6.5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
        "M3 9.5h18M6 7h.01M8.5 7h.01",
        "Customer portal / external view"),
    "pencil-line": ("navigation",
        "M4 20.5h16M15.2 4.8a2 2 0 0 1 2.8 2.8L8.4 17.2 4.5 18.5l1.3-3.9z",
        "Design notes / edit"),

    # ------------------------------------------------------------------- actions
    "search": ("actions",
        "M20 20l-4.2-4.2M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 0 1 13 0",
        "Global search"),
    "plus": ("actions", "M12 5v14M5 12h14", "Create / add"),
    "x": ("actions", "M6 6l12 12M18 6L6 18", "Close / remove"),
    "chevron-down": ("actions", "M6 9.5l6 6l6-6", "Expand / collapse down"),
    "chevron-up": ("actions", "M6 14.5l6-6l6 6", "Collapse up / move up"),
    "chevron-right": ("actions", "M9.5 6l6 6l-6 6", "Drill in"),
    "chevron-left": ("actions", "M14.5 6l-6 6l6 6", "Back"),
    "arrow-right": ("actions", "M4 12h15M13 6l6 6l-6 6", "Transition / branch target"),
    "more-horizontal": ("actions", "M6 12h.01M12 12h.01M18 12h.01", "Row overflow menu"),
    "filter": ("actions",
        "M4 5.5h16l-6.2 7.4v5.9l-3.6-1.8v-4.1z", "Filter list"),
    "grip-vertical": ("actions",
        "M9 6h.01M9 12h.01M9 18h.01M15 6h.01M15 12h.01M15 18h.01",
        "Drag handle / reorder"),

    # -------------------------------------------------------------------- status
    "check": ("status", "M4.5 12.5l5 5L20 6", "Done / completed"),
    "check-circle": ("status",
        "M9 12.2l2.2 2.2 4.8-5.4M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0",
        "Milestone complete / approved"),
    "alert-triangle": ("status",
        "M12 4.5l8.5 14.5H3.5zM12 10v3.5M12 16.2h.01", "At risk / SLA breach"),
    "alert-circle": ("status",
        "M12 7.5v5M12 16h.01M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0", "Blocked"),
    "clock": ("status",
        "M12 7.5V12l3 2M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0", "Due / pending"),
    "pause-circle": ("status",
        "M10 9.5v5M14 9.5v5M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0", "Waiting on third party"),
    "x-circle": ("status",
        "M14.8 9.2l-5.6 5.6M9.2 9.2l5.6 5.6M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0",
        "Cancelled"),
    "exclamation": ("status",
        "M12 6v7M12 17.2h.01",
        "Bare alert stroke, for use inside a coloured status circle"),

    # ------------------------------------------------------------- files & data
    "file-text": ("files",
        "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
        "M14 3v5h5M8.5 13h7M8.5 16.5h4.5",
        "Document"),
    "file-signature": ("files",
        "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
        "M14 3v5h5M8.5 16.5c1.3-2 2.6-2 3.9 0s2.6 2 3.9 0",
        "Agreement / signature required"),
    "folder": ("files",
        "M4 7.5a2 2 0 0 1 2-2h3.2l2 2.5H18a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
        "Document category"),
    "download": ("files", "M12 4v11M7.5 11l4.5 4.5 4.5-4.5M4.5 20h15", "Download / export"),
    "upload": ("files", "M12 20V8.5M7.5 13l4.5-4.5 4.5 4.5M4.5 4.5h15", "Upload requested document"),
    "paperclip": ("files",
        "M18.5 11.5l-7.6 7.6a4.2 4.2 0 0 1-5.9-5.9l7.4-7.4a2.8 2.8 0 0 1 4 4"
        "l-7.4 7.4a1.4 1.4 0 0 1-2-2l6.8-6.8",
        "Attachment"),
    "eye": ("files",
        "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z"
        "M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0",
        "Visible to / portal scope"),
    "lock": ("files",
        "M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7"
        "a1.5 1.5 0 0 1 1.5-1.5zM8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5",
        "Internal only / encrypted"),
    "layers": ("files",
        "M12 3.5l8.5 4.5-8.5 4.5L3.5 8zM3.5 12.5l8.5 4.5 8.5-4.5M3.5 16.5l8.5 4.5 8.5-4.5",
        "Concurrent cases"),

    # -------------------------------------------------------------------- domain
    "shield-check": ("domain",
        "M12 3l7.5 2.8v6.4c0 4.4-3.1 7-7.5 8.8-4.4-1.8-7.5-4.4-7.5-8.8V5.8z"
        "M8.8 12.2l2.4 2.4 4-4.6",
        "Compliance / KYC cleared"),
    "scale": ("domain",
        "M12 4v16M7 20h10M4.5 8.5h15M7 8.5L4 14.5h6zM17 8.5l-3 6h6z",
        "Legal team"),
    "sliders": ("domain",
        "M5 7h9M18 7h1M5 12h3M12 12h7M5 17h7M16 17h3M16 5v4M10 10v4M14 15v4",
        "Technical setup / configuration"),
    "graduation-cap": ("domain",
        "M12 5.5L21.5 9.5 12 13.5 2.5 9.5z"
        "M6.5 11.2v4.3c0 1.8 2.5 3 5.5 3s5.5-1.2 5.5-3v-4.3",
        "Training handover"),
    "flag": ("domain", "M6 20V4.5h12l-2.5 4 2.5 4H6", "Go live / milestone marker"),
    "clipboard-check": ("domain",
        "M9 4.5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-12a2 2 0 0 0-2-2h-2"
        "M9 3h6a1 1 0 0 1 1 1v1.5a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"
        "M9 13.5l2 2 4-4.5",
        "Verification stage"),
    "user-check": ("domain",
        "M15 20v-1.5a4 4 0 0 0-4-4H6.5a4 4 0 0 0-4 4V20"
        "M13 7.5a4 4 0 1 1-8 0a4 4 0 0 1 8 0M16.5 12.2l1.8 1.8 3.4-4",
        "Sales approval / assigned owner"),
    "building": ("domain",
        "M4.5 20.5V6a1.5 1.5 0 0 1 1.5-1.5h7A1.5 1.5 0 0 1 14.5 6v14.5"
        "M14.5 10.5H18a1.5 1.5 0 0 1 1.5 1.5v8.5M3 20.5h18"
        "M7.5 8h4M7.5 11.5h4M7.5 15h4",
        "Customer company"),
    "credit-card": ("domain",
        "M4 8.5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"
        "M4 11h16M7.5 14.5h3",
        "Finance / invoicing"),
    "refresh-cw": ("domain",
        "M20 5.5v5h-5M4 18.5v-5h5"
        "M4.6 10.2a8 8 0 0 1 13.2-3l2.2 2.3M19.4 13.8a8 8 0 0 1-13.2 3L4 13.5",
        "Auto-advance / automated transition"),

    # ------------------------------------------------------------ comms  & meta
    "bell": ("comms",
        "M18.5 15.5V10a6.5 6.5 0 1 0-13 0v5.5L4 18h16zM9.5 18a2.5 2.5 0 0 0 5 0",
        "Notifications"),
    "calendar": ("comms",
        "M6.5 5h11a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"
        "M4.5 10h15M8.5 3v4M15.5 3v4",
        "Upcoming activity / due date"),
    "message-square": ("comms",
        "M20.5 15.5a2 2 0 0 1-2 2H8l-4 3.5v-15a2 2 0 0 1 2-2h12.5a2 2 0 0 1 2 2z",
        "Comments"),
    "at-sign": ("comms",
        "M12 16.5a4.5 4.5 0 1 0 0-9a4.5 4.5 0 0 0 0 9"
        "M16.5 7.5V13a3.5 3.5 0 0 0 3.5 3.5c1 0 1.5-2 1.5-4.5A9.5 9.5 0 1 0 15 20.7",
        "Mention a teammate"),
    "link": ("comms",
        "M10 13.5a4 4 0 0 0 6 .5l3-3a4 4 0 0 0-5.7-5.7L11.5 7"
        "M14 10.5a4 4 0 0 0-6-.5l-3 3a4 4 0 0 0 5.7 5.7L12.5 17",
        "Dependency"),
    "history": ("comms",
        "M4 5.5v5h5M4.3 13.2a8 8 0 1 0 1-6.2M12 8.5V12l3 1.8",
        "Activity timeline / audit"),
    "external-link": ("comms",
        "M13.5 4.5H19a.5.5 0 0 1 .5.5v5.5M19.5 4.5L12 12"
        "M17 14v4.5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V8.5a1 1 0 0 1 1-1h4.5",
        "Open portal / external link"),

    # ------------------------------------------------------------------ metrics
    "trending-up": ("metrics",
        "M4 16.5l5.5-5.5 3.5 3.5L20 7.5M14.5 7.5h5.5V13", "Positive delta"),
    "trending-down": ("metrics",
        "M4 7.5l5.5 5.5 3.5-3.5L20 16.5M14.5 16.5h5.5V11", "Negative delta"),
    "check-square": ("metrics",
        "M8.5 12.2l2.4 2.4 4.6-5.2"
        "M5.5 4.5h13a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z",
        "Task complete"),
    "square": ("metrics",
        "M5.5 4.5h13a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z",
        "Task open"),
}

# Unicode placeholder in the prototype -> icon that replaces it
GLYPH_MAP = [
    ("\u25eb", "layout-dashboard", "Dashboard nav"),
    ("\u2630", "users", "Customers nav"),
    ("\u25c8", "journey", "Journey workspace nav"),
    ("\u2699", "workflow", "Workflow builder nav / automation actor in feed"),
    ("\u25c9", "chart-column", "Reports nav"),
    ("\u25d0", "portal-window", "Customer portal nav"),
    ("\u270e", "pencil-line", "Design notes nav"),
    ("\u2315", "search", "Header search field"),
    ("\u25d4", "bell", "Header notification button / portal Updates tab"),
    ("\uff0b", "plus", "New case chip, Add condition"),
    ("\u2715", "x", "Delete stage"),
    ("\u25b2", "chevron-up", "Move stage up"),
    ("\u25bc", "chevron-down", "Move stage down / milestone chevron"),
    ("\u2713", "check", "Task checkbox, completed milestone"),
    ("!", "exclamation", "Blocked milestone status circle"),
    ("\u2192", "arrow-right", "Branch rule target"),
    ("\u21a5", "upload", "Document dropzone"),
    ("\u2399", "file-text", "Portal mobile Documents tab"),
]

CATEGORY_ORDER = ["navigation", "actions", "status", "files", "domain", "comms", "metrics"]
CATEGORY_LABEL = {
    "navigation": "Navigation & shell",
    "actions": "Actions & controls",
    "status": "Status & health",
    "files": "Files & data",
    "domain": "Onboarding domain",
    "comms": "Communication & meta",
    "metrics": "Metrics & tasks",
}

SVG_ATTRS = (
    'xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" '
    'fill="none" stroke="currentColor" stroke-width="1.5" '
    'stroke-linecap="round" stroke-linejoin="round"'
)


def build():
    svg_dir = OUT / "svg"
    svg_dir.mkdir(parents=True, exist_ok=True)

    # ---- individual files
    for name, (cat, path, desc) in ICONS.items():
        doc = (
            f'<svg {SVG_ATTRS} aria-hidden="true">\n'
            f'  <title>{name}</title>\n'
            f'  <path d="{path}"/>\n'
            f'</svg>\n'
        )
        (svg_dir / f"{name}.svg").write_text(doc, encoding="utf-8")

    # ---- sprite
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">',
        "  <!-- Onboard OS icon sprite. Use: "
        '<svg class="icon"><use href="icons-sprite.svg#i-search"/></svg> -->',
        "  <!-- Consumer sets: width/height, fill:none, stroke:currentColor, "
        "stroke-width:1.5, round caps/joins. -->",
    ]
    for cat in CATEGORY_ORDER:
        parts.append(f"  <!-- {CATEGORY_LABEL[cat]} -->")
        for name, (c, path, desc) in ICONS.items():
            if c != cat:
                continue
            parts.append(
                f'  <symbol id="i-{name}" viewBox="0 0 24 24"><path d="{path}"/></symbol>'
            )
    parts.append("</svg>\n")
    (OUT / "icons-sprite.svg").write_text("\n".join(parts), encoding="utf-8")

    # ---- json registry
    registry = {
        "$meta": {
            "name": "Onboard OS icon set",
            "count": len(ICONS),
            "grid": 24,
            "strokeWidth": 1.5,
            "liveArea": "3..21 of 24",
            "style": "outlined, single path, round caps and joins",
            "usage": "fill:none; stroke:currentColor; stroke-width:1.5; "
                     "stroke-linecap:round; stroke-linejoin:round",
        },
        "icons": {
            name: {"category": cat, "description": desc, "path": path}
            for name, (cat, path, desc) in ICONS.items()
        },
        "replaces": [
            {"glyph": g, "icon": i, "where": w} for g, i, w in GLYPH_MAP
        ],
    }
    (OUT / "icons.json").write_text(
        json.dumps(registry, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    # ---- html gallery
    cards = []
    for cat in CATEGORY_ORDER:
        rows = []
        for name, (c, path, desc) in ICONS.items():
            if c != cat:
                continue
            rows.append(
                f'      <figure class="cell">\n'
                f'        <svg class="ico" viewBox="0 0 24 24"><path d="{path}"/></svg>\n'
                f'        <figcaption><b>{name}</b><span>{desc}</span></figcaption>\n'
                f'      </figure>'
            )
        cards.append(
            f'    <section class="group">\n'
            f'      <h2>{CATEGORY_LABEL[cat]}</h2>\n'
            f'      <div class="grid">\n' + "\n".join(rows) + "\n      </div>\n    </section>"
        )

    sizes = "".join(
        f'<div class="sz"><svg class="ico" style="width:{s}px;height:{s}px" '
        f'viewBox="0 0 24 24"><path d="{ICONS["journey"][1]}"/></svg>'
        f'<span>{s}px</span></div>'
        for s in (14, 16, 18, 20, 24, 32, 48)
    )

    maprows = "".join(
        f'<tr><td class="g">{g}</td><td><svg class="ico sm" viewBox="0 0 24 24">'
        f'<path d="{ICONS[i][1]}"/></svg></td><td><code>{i}</code></td>'
        f'<td class="w">{w}</td></tr>'
        for g, i, w in GLYPH_MAP
    )

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Onboard OS — Icon set</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  *,*::before,*::after {{ box-sizing: border-box; }}
  body {{ margin:0; background:#f7f6f3; color:#191a1e; font-family:Archivo,Helvetica,sans-serif;
         -webkit-font-smoothing:antialiased; }}
  .wrap {{ max-width:1180px; margin:0 auto; padding:48px 28px 88px; }}
  .eyebrow {{ font-family:'IBM Plex Mono',monospace; font-size:10px; letter-spacing:.09em;
              text-transform:uppercase; color:#9a968f; }}
  h1 {{ font-size:29px; font-weight:600; letter-spacing:-.03em; margin:10px 0 8px; }}
  .lede {{ font-size:13.5px; color:#75726c; max-width:62ch; line-height:1.6; margin:0; }}
  .ico {{ width:22px; height:22px; fill:none; stroke:currentColor; stroke-width:1.5;
          stroke-linecap:round; stroke-linejoin:round; }}
  .ico.sm {{ width:17px; height:17px; }}
  .group {{ margin-top:34px; }}
  .group h2 {{ font-size:13.5px; font-weight:600; margin:0 0 14px;
               padding-bottom:9px; border-bottom:1px solid #e6e3dd; }}
  .grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(214px,1fr)); gap:10px; }}
  .cell {{ margin:0; display:flex; gap:12px; align-items:flex-start; background:#fff;
           border:1px solid #e6e3dd; border-radius:13px; padding:13px 14px; }}
  .cell .ico {{ flex:0 0 22px; color:#191a1e; margin-top:1px; }}
  figcaption {{ min-width:0; line-height:1.35; }}
  figcaption b {{ display:block; font-family:'IBM Plex Mono',monospace; font-size:11px;
                  font-weight:500; }}
  figcaption span {{ display:block; font-size:11.5px; color:#75726c; margin-top:3px; }}
  .panel {{ background:#fff; border:1px solid #e6e3dd; border-radius:13px; padding:18px 20px;
            margin-top:34px; }}
  .sizes {{ display:flex; gap:26px; align-items:flex-end; flex-wrap:wrap; }}
  .sz {{ display:flex; flex-direction:column; align-items:center; gap:8px; }}
  .sz span {{ font-family:'IBM Plex Mono',monospace; font-size:9.5px; color:#9a968f; }}
  table {{ width:100%; border-collapse:collapse; margin-top:6px; }}
  th,td {{ text-align:left; padding:9px 10px; border-bottom:1px solid #f4f2ee; font-size:12px;
           vertical-align:middle; }}
  th {{ font-family:'IBM Plex Mono',monospace; font-size:9.5px; letter-spacing:.08em;
        text-transform:uppercase; color:#9a968f; background:#faf9f7; }}
  td.g {{ font-size:17px; color:#75726c; width:52px; }}
  td.w {{ color:#75726c; }}
  code {{ font-family:'IBM Plex Mono',monospace; font-size:11px; }}
  .swatchrow {{ display:flex; gap:10px; flex-wrap:wrap; margin-top:14px; }}
  .chip {{ display:inline-flex; align-items:center; gap:7px; height:30px; padding:0 11px;
           border-radius:8px; font-size:12px; }}
</style>
</head>
<body>
<div class="wrap">
  <div class="eyebrow">Onboard OS · design system</div>
  <h1>Icon set — {len(ICONS)} icons</h1>
  <p class="lede">Outlined icons on a 24&#215;24 grid, 1.5px stroke, round caps and joins, live area
  inset to 3&#8211;21. Each icon is a single path so it can be stored as data, bound into a template,
  or emitted as a sprite symbol without restructuring. Colour comes from
  <code>currentColor</code> only &mdash; icons never carry their own colour.</p>

  <div class="panel">
    <h2 style="font-size:13.5px;font-weight:600;margin:0 0 14px;padding-bottom:9px;border-bottom:1px solid #e6e3dd">Optical sizing</h2>
    <div class="sizes">{sizes}</div>
    <p class="lede" style="margin-top:16px">Stroke stays 1.5 from 16&#8211;24px. Below 16px bump to
    1.75 to hold density; at 32px+ drop to 1.25. The rail uses 16px, table rows 15px, buttons 16px,
    empty states 20&#8211;24px.</p>
  </div>

  <div class="panel">
    <h2 style="font-size:13.5px;font-weight:600;margin:0 0 14px;padding-bottom:9px;border-bottom:1px solid #e6e3dd">Status colour pairing</h2>
    <div class="swatchrow">
      <span class="chip" style="background:oklch(0.95 0.04 155);color:oklch(0.45 0.12 155)"><svg class="ico sm" viewBox="0 0 24 24"><path d="{ICONS['check-circle'][1]}"/></svg>On track</span>
      <span class="chip" style="background:oklch(0.96 0.05 70);color:oklch(0.46 0.14 70)"><svg class="ico sm" viewBox="0 0 24 24"><path d="{ICONS['alert-triangle'][1]}"/></svg>At risk</span>
      <span class="chip" style="background:oklch(0.96 0.04 25);color:oklch(0.5 0.17 25)"><svg class="ico sm" viewBox="0 0 24 24"><path d="{ICONS['alert-circle'][1]}"/></svg>Blocked</span>
      <span class="chip" style="background:#f2f0ec;color:#75726c"><svg class="ico sm" viewBox="0 0 24 24"><path d="{ICONS['pause-circle'][1]}"/></svg>Waiting</span>
      <span class="chip" style="background:oklch(0.95 0.03 274);color:oklch(0.42 0.16 274)"><svg class="ico sm" viewBox="0 0 24 24"><path d="{ICONS['clock'][1]}"/></svg>In progress</span>
    </div>
    <p class="lede" style="margin-top:16px">An icon may only carry status colour when it
    <em>is</em> the status. Icons inside neutral UI inherit text colour.</p>
  </div>

{chr(10).join(cards)}

  <div class="panel">
    <h2 style="font-size:13.5px;font-weight:600;margin:0 0 14px;padding-bottom:9px;border-bottom:1px solid #e6e3dd">Replaces these prototype placeholders</h2>
    <table>
      <thead><tr><th>Was</th><th>Now</th><th>Icon</th><th>Where</th></tr></thead>
      <tbody>{maprows}</tbody>
    </table>
  </div>
</div>
</body>
</html>
"""
    (OUT / "icon-sheet.html").write_text(html, encoding="utf-8")
    print(f"wrote {len(ICONS)} icons to {svg_dir}")
    print("wrote icons-sprite.svg, icons.json, icon-sheet.html")

    # sanity: every mapped icon exists
    missing = [i for _, i, _ in GLYPH_MAP if i not in ICONS]
    assert not missing, f"GLYPH_MAP references unknown icons: {missing}"
    # sanity: no semicolons (would break the prototype's cssToObj if ever inlined in style)
    bad = [n for n, (_, p, _) in ICONS.items() if ";" in p]
    assert not bad, bad
    print("checks passed")


if __name__ == "__main__":
    build()
