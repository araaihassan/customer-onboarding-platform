# -*- coding: utf-8 -*-
"""Onboard OS logo suite generator.

Concept — "the progress arc".
The mark is a 300 degree arc with a symmetric 60 degree gap at twelve o'clock and a
filled node centred in that gap. It reads three ways at once: the letter O, a
progress ring, and a journey whose last stage is not yet closed. It is the same
figure the product draws everywhere else (progress bars, completion rings, stage
nodes), reduced to one glyph.

All geometry is derived, never hand-typed, so every variant stays in register.
"""
import math
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "01-brand" / "logo"

INDIGO = "#4f5cc3"          # exact sRGB of oklch(0.52 0.16 274)
INK = "#191a1e"
PAPER = "#f7f6f3"
GAP_DEG = 60                 # opening at twelve o'clock


def arc_path(cx, cy, r, gap_deg=GAP_DEG):
    """300 degree arc, symmetric gap centred on twelve o'clock, drawn clockwise."""
    half = gap_deg / 2
    a0 = -90 + half            # right side of the gap
    a1 = -90 - half + 360      # left side of the gap, the long way round
    x0 = cx + r * math.cos(math.radians(a0))
    y0 = cy + r * math.sin(math.radians(a0))
    x1 = cx + r * math.cos(math.radians(a1))
    y1 = cy + r * math.sin(math.radians(a1))
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    return f"M{f(x0)} {f(y0)}A{f(r)} {f(r)} 0 1 1 {f(x1)} {f(y1)}"


def node_xy(cx, cy, r):
    """Centre of the node that sits in the gap."""
    return cx, cy - r


def mark(cx, cy, r, stroke, colour, node_scale=0.5):
    """Arc + node, as markup fragment."""
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    nx, ny = node_xy(cx, cy, r)
    return (
        f'<path d="{arc_path(cx, cy, r)}" fill="none" stroke="{colour}" '
        f'stroke-width="{f(stroke)}" stroke-linecap="round"/>\n'
        f'  <circle cx="{f(nx)}" cy="{f(ny)}" r="{f(stroke * node_scale)}" fill="{colour}"/>'
    )


def tile(size, radius, fill, mark_colour):
    """Rounded-square app tile with the mark centred."""
    c = size / 2
    r = size * 0.234          # ring radius
    sw = size * 0.094         # stroke
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    return (
        f'<rect width="{f(size)}" height="{f(size)}" rx="{f(radius)}" fill="{fill}"/>\n'
        f'  {mark(c, c, r, sw, mark_colour)}'
    )


WORDMARK_FONT = (
    "font-family=\"Archivo, 'Helvetica Neue', Helvetica, Arial, sans-serif\" "
    "font-weight=\"600\" letter-spacing=\"-0.02em\""
)


def svg(w, h, body, title):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" role="img" aria-label="{title}">\n'
        f'  <title>{title}</title>\n'
        f'  {body}\n'
        f'</svg>\n'
    )


def write(name, content):
    (OUT / name).write_text(content, encoding="utf-8")


def build():
    OUT.mkdir(parents=True, exist_ok=True)

    # 1. mark alone, inherits colour ------------------------------------------
    write("logo-mark.svg", svg(
        32, 32, mark(16, 16, 10, 3, "currentColor"),
        "Onboard OS mark"))

    # 2. app tile -------------------------------------------------------------
    write("logo-tile.svg", svg(
        32, 32, tile(32, 9, INDIGO, "#fff"),
        "Onboard OS app tile"))

    # 3. favicon — heavier stroke, tighter ring so it survives 16px -----------
    fav = (
        f'<rect width="32" height="32" rx="7" fill="{INDIGO}"/>\n'
        f'  {mark(16, 16, 8, 3.6, "#fff")}'
    )
    write("favicon.svg", svg(32, 32, fav, "Onboard OS favicon"))

    # 4/5. horizontal lockups -------------------------------------------------
    # tile 32, gap 11, cap-height-aligned wordmark at 20px
    def horizontal(ink, tile_fill, mark_colour, label):
        body = (
            f'<g>{tile(32, 9, tile_fill, mark_colour)}</g>\n'
            f'  <text x="43" y="22" {WORDMARK_FONT} font-size="20" fill="{ink}">Onboard OS</text>'
        )
        return svg(160, 32, body, label)

    write("logo-horizontal.svg",
          horizontal(INK, INDIGO, "#fff", "Onboard OS"))
    write("logo-horizontal-on-dark.svg",
          horizontal("#f2f0ec", INDIGO, "#fff", "Onboard OS (on dark)"))

    # 6/7. single-colour lockups for stamping, fax, engraving ----------------
    def mono(ink, label):
        body = (
            f'<g>{mark(16, 16, 10, 3, ink)}</g>\n'
            f'  <text x="43" y="22" {WORDMARK_FONT} font-size="20" fill="{ink}">Onboard OS</text>'
        )
        return svg(160, 32, body, label)

    write("logo-mono-black.svg", mono(INK, "Onboard OS (mono, black)"))
    write("logo-mono-white.svg", mono("#ffffff", "Onboard OS (mono, white)"))

    # 8. stacked --------------------------------------------------------------
    # Canvas must clear the wordmark, not just the tile: "Onboard OS" set in
    # Archivo 600 at 19px measures ~104px, so the box is 116 wide with the tile
    # and the text both centred on it.
    STACK_W, STACK_H, TILE = 116, 76, 40
    stacked = (
        f'<g transform="translate({(STACK_W - TILE) / 2:g} 0)">'
        f'{tile(TILE, 11, INDIGO, "#fff")}</g>\n'
        f'  <text x="{STACK_W / 2:g}" y="66" text-anchor="middle" {WORDMARK_FONT} '
        f'font-size="19" fill="{INK}">Onboard OS</text>'
    )
    write("logo-stacked.svg", svg(STACK_W, STACK_H, stacked, "Onboard OS (stacked)"))

    print("logo variants written to", OUT)

    # ---- spec sheet ---------------------------------------------------------
    ring = arc_path(16, 16, 10)
    sheet = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Onboard OS — Logo</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  *,*::before,*::after {{ box-sizing:border-box; }}
  body {{ margin:0; background:{PAPER}; color:{INK}; font-family:Archivo,Helvetica,sans-serif;
         -webkit-font-smoothing:antialiased; }}
  .wrap {{ max-width:1180px; margin:0 auto; padding:48px 28px 88px; }}
  .eyebrow {{ font-family:'IBM Plex Mono',monospace; font-size:10px; letter-spacing:.09em;
              text-transform:uppercase; color:#9a968f; }}
  h1 {{ font-size:29px; font-weight:600; letter-spacing:-.03em; margin:10px 0 8px; }}
  h2 {{ font-size:13.5px; font-weight:600; margin:0 0 14px; padding-bottom:9px;
        border-bottom:1px solid #e6e3dd; }}
  .lede {{ font-size:13.5px; color:#75726c; max-width:64ch; line-height:1.6; margin:0; }}
  .panel {{ background:#fff; border:1px solid #e6e3dd; border-radius:13px; padding:18px 20px;
            margin-top:16px; }}
  .row {{ display:flex; gap:16px; flex-wrap:wrap; align-items:stretch; }}
  .row > .panel {{ flex:1 1 300px; margin-top:0; }}
  .stage {{ display:grid; place-items:center; min-height:132px; border-radius:10px;
            background:#faf9f7; border:1px solid #f0eee9; padding:20px; }}
  .stage.dark {{ background:#17181c; border-color:#26272c; }}
  .stage.accent {{ background:{INDIGO}; border-color:{INDIGO}; }}
  .cap {{ font-family:'IBM Plex Mono',monospace; font-size:9.5px; letter-spacing:.08em;
          text-transform:uppercase; color:#9a968f; margin-bottom:10px; }}
  .sizes {{ display:flex; gap:24px; align-items:flex-end; flex-wrap:wrap; }}
  .sz {{ display:flex; flex-direction:column; align-items:center; gap:9px; }}
  .sz span {{ font-family:'IBM Plex Mono',monospace; font-size:9.5px; color:#9a968f; }}
  table {{ width:100%; border-collapse:collapse; }}
  th,td {{ text-align:left; padding:9px 10px; border-bottom:1px solid #f4f2ee; font-size:12px; }}
  th {{ font-family:'IBM Plex Mono',monospace; font-size:9.5px; letter-spacing:.08em;
        text-transform:uppercase; color:#9a968f; background:#faf9f7; }}
  code {{ font-family:'IBM Plex Mono',monospace; font-size:11px; }}
  .dont {{ color:oklch(0.5 0.17 25); }}
  .do {{ color:oklch(0.45 0.12 155); }}
  .grid2 {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:12px; }}
  .constr {{ position:relative; width:200px; height:200px; }}
</style>
</head>
<body>
<div class="wrap">
  <div class="eyebrow">Onboard OS · brand</div>
  <h1>Logo</h1>
  <p class="lede">The mark is a 300&#176; arc with a symmetric 60&#176; opening at twelve o&#8217;clock
  and a filled node centred in the opening. It reads as the letter O, as a progress ring, and as a
  journey whose final stage is still open &mdash; the same figure the product draws everywhere else.</p>

  <div class="panel">
    <h2>Construction</h2>
    <div class="row">
      <div style="flex:0 0 auto">
        <svg width="200" height="200" viewBox="0 0 32 32">
          <rect width="32" height="32" fill="#fff"/>
          <g stroke="#e6e3dd" stroke-width="0.25">
            <path d="M16 0v32M0 16h32M6 0v32M26 0v32M0 6h32M0 26h32"/>
          </g>
          <circle cx="16" cy="16" r="10" fill="none" stroke="{INDIGO}" stroke-width="0.25"
                  stroke-dasharray="1 1" opacity="0.6"/>
          <path d="{ring}" fill="none" stroke="{INDIGO}" stroke-width="3" stroke-linecap="round"/>
          <circle cx="16" cy="6" r="1.5" fill="{INDIGO}"/>
        </svg>
      </div>
      <div style="flex:1 1 260px">
        <table>
          <tr><th>Grid</th><td>32 &#215; 32</td></tr>
          <tr><th>Ring radius</th><td>10 (62.5% of grid)</td></tr>
          <tr><th>Stroke</th><td>3 &#8212; matches Archivo 600 stem weight at 20px</td></tr>
          <tr><th>Opening</th><td>60&#176;, centred on 12 o&#8217;clock</td></tr>
          <tr><th>Node</th><td>r 1.5 at (16, 6) &#8212; diameter equals stroke</td></tr>
          <tr><th>Caps</th><td>round, both ends</td></tr>
          <tr><th>Clear space</th><td>6 units all sides (= node diameter &#215; 2)</td></tr>
        </table>
      </div>
    </div>
  </div>

  <h2 style="margin-top:34px">Variants</h2>
  <div class="grid2">
    <div class="panel"><div class="cap">Horizontal &#183; primary</div>
      <div class="stage"><img src="logo-horizontal.svg" width="200" alt=""></div></div>
    <div class="panel"><div class="cap">Horizontal &#183; on dark rail</div>
      <div class="stage dark"><img src="logo-horizontal-on-dark.svg" width="200" alt=""></div></div>
    <div class="panel"><div class="cap">Stacked</div>
      <div class="stage"><img src="logo-stacked.svg" width="132" alt=""></div></div>
    <div class="panel"><div class="cap">App tile</div>
      <div class="stage"><img src="logo-tile.svg" width="72" alt=""></div></div>
    <div class="panel"><div class="cap">Mark &#183; inherits colour</div>
      <div class="stage accent"><img src="logo-mono-white.svg" width="200" alt=""></div></div>
    <div class="panel"><div class="cap">Mono &#183; black</div>
      <div class="stage"><img src="logo-mono-black.svg" width="200" alt=""></div></div>
  </div>

  <div class="panel">
    <h2>Minimum sizes</h2>
    <div class="sizes">
      <div class="sz"><img src="favicon.svg" width="16" alt=""><span>16 favicon</span></div>
      <div class="sz"><img src="logo-tile.svg" width="20" alt=""><span>20</span></div>
      <div class="sz"><img src="logo-tile.svg" width="26" alt=""><span>26 rail</span></div>
      <div class="sz"><img src="logo-tile.svg" width="40" alt=""><span>40</span></div>
      <div class="sz"><img src="logo-tile.svg" width="64" alt=""><span>64</span></div>
      <div class="sz"><img src="logo-horizontal.svg" width="120" alt=""><span>120 min lockup</span></div>
    </div>
    <p class="lede" style="margin-top:16px">Below 20px use <code>favicon.svg</code> &mdash; it carries a
    tighter ring and a heavier stroke so the opening does not close up. The horizontal lockup is never
    set below 120px wide; below that, switch to the tile.</p>
  </div>

  <div class="panel">
    <h2>Rules</h2>
    <table>
      <tr><td class="do">Do</td><td>Place the tile on indigo, ink, or paper. Keep 6 grid units of clear space.</td></tr>
      <tr><td class="do">Do</td><td>Use <code>logo-mark.svg</code> inside UI &mdash; it inherits <code>currentColor</code>.</td></tr>
      <tr><td class="do">Do</td><td>Keep the opening at twelve o&#8217;clock. It is the recognisable feature.</td></tr>
      <tr><td class="dont">Don&#8217;t</td><td>Rotate the mark, close the opening, or move the node out of the gap.</td></tr>
      <tr><td class="dont">Don&#8217;t</td><td>Re-weight the stroke, add a gradient, outline the wordmark, or set it in another face.</td></tr>
      <tr><td class="dont">Don&#8217;t</td><td>Place the indigo tile on a saturated background, or the mono-white lockup on paper.</td></tr>
    </table>
  </div>

  <div class="panel" style="border-color:oklch(0.9 0.05 70); background:oklch(0.98 0.02 70)">
    <h2 style="border-color:oklch(0.9 0.05 70)">Before external distribution</h2>
    <p class="lede">The wordmark in these files is live <code>&lt;text&gt;</code> set in Archivo 600, not
    outlines &mdash; it renders correctly wherever Archivo is available (the product, this sheet, any
    browser with the webfont) but will fall back to Helvetica in tools without the font installed.
    Convert to outlines before sending the logo outside the team:</p>
    <p class="lede" style="margin-top:10px"><code>pip install fonttools brotli</code> &rarr; then trace
    the wordmark with <code>fontTools.pens.svgPathPen</code> against Archivo-SemiBold, or open each
    lockup in Illustrator/Inkscape and apply Type &rarr; Create Outlines. The mark itself is already pure
    geometry and needs nothing.</p>
  </div>
</div>
</body>
</html>
"""
    write("logo-sheet.html", sheet)
    print("logo-sheet.html written")


if __name__ == "__main__":
    build()
