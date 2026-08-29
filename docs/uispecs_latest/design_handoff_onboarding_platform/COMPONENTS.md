# Components

The recurring parts. Build these first; the screens are then mostly composition.

Colour tokens referenced here are defined in `DESIGN_TOKENS.md`.

---

## 1. AppShell

Three fixed regions plus a scrolling body.

```
┌──────┬────────────┬─────────────────────────────────┐
│ rail │  sidebar   │  top bar (52px)                 │
│ 56px │   250px    ├─────────────────────────────────┤
│      │            │  scroll body                    │
└──────┴────────────┴─────────────────────────────────┘
```

- Root: `display:flex; height:100vh; overflow:hidden; background:canvas; font-size:13.5px`.
- Rail: `#1c1b18`, column, `padding:14px 0 12px`, `gap:6px`, centred children.
- Sidebar: `canvas`, 1px right border `line`, `padding:12px 10px 10px`, column.
- Main: `flex:1; min-width:0`, column, `overflow:hidden`; body scrolls.

### Rail contents

- **Brand mark** 30×30, radius 9, `accent` fill, containing an 11×11 `canvas` square rotated 45°.
  Margin-bottom 10.
- **Section buttons** 34×34, radius 9, no border, `#f4f2ee` glyph, 14px.
  Background `transparent` → `#3d3a35` when that section is active. Three: Workspace `◎`,
  Configure `⌘`, Customer portal `◐`.
- **Bottom group** (margin-top auto): `⌘K` button 34×34 radius 9 on `#302e2a`, mono 11px; then the
  current user's avatar 30×30 on `avatar-neutral`.

### Top bar

`height:52px; padding:0 18px; display:flex; align-items:center; gap:12px; border-bottom:1px solid line`.

- Left: breadcrumb, mono 10.5px, `letter-spacing:.08em`, uppercase, `text-subtle`.
- Right (margin-left auto, gap 10): presence cluster · Inbox button · primary action button.
- Presence cluster: mono 9.5px label `ON THIS CASE` in `text-faint`, then overlapping avatars;
  the group has `padding-right:10px; border-right:1px solid line`.

---

## 2. NavItem

```
display:flex; align-items:center; gap:9px;
border:0; border-radius:8px; padding:7px 9px;   (portal: 8px 9px)
font-size:13px; color:ink; text-align:left; cursor:pointer;
background: active ? surface-active : transparent;
hover: background surface-active;
```

Leading glyph: 14px-wide centred span, 11px, `text-subtle`. Trailing badge optional
(`margin-left:auto`): mono 10px, radius 4, `padding:1px 5px` — plain `text-subtle` for counts,
or a semantic pair for attention (`risk` for overdue, `warn` for pending migrations).

Group label above a set of items: mono 9.5px, `letter-spacing:.1em`, weight 500, `text-faint`,
`padding:12px 8px 5px`.

---

## 3. Chip

The workhorse. Two sizes, always uppercase mono.

| Size | Font | Padding | Radius |
| --- | --- | --- | --- |
| Standard | mono 9.5px, `ls .05em` | `2px 6px` | 5 |
| Compact | mono 9px, `ls .05–.06em` | `2px 5px` | 4 |

Colour is a semantic pair: `background: <state>.bg; color: <state>.fg`. Never bordered.

Variants seen in the design: `COMPLETE` `IN PROGRESS` `NOT STARTED` `BLOCKED` `WAITING ON CUSTOMER`
`AUTOMATED` `BREACHED 2.0d` `DUE TODAY` `WATCH` `PAUSED 3.1d` `SLA PAUSED` `PINNED TO v2.3` `SAFE`
`NEEDS MAPPING` `RESTRICTED` `SENSITIVE` `PERSONAL` `SIGNED` `DRAFT` `3 JOURNEYS`.

---

## 4. Button

| Variant | Height | Padding | Background | Text | Border | Radius | Hover |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | 30–32 | 0 13px | `ink` | `canvas`, 12.5px/600 | none | 8 | `ink-hover` |
| Secondary | 30–32 | 0 12px | `surface` | `ink`, 12.5px/500 | 1px `line` | 8 | `surface-active` |
| Small primary | 27–29 | 0 10–12px | `ink` | 11.5–12px/600 | none | 7 | `ink-hover` |
| Small secondary | 26–29 | 0 9–11px | `surface` | 11.5–12px | 1px `line` | 7 | `surface-active` |
| Danger-outline | 27 | 0 10px | `surface` | `risk.fg`, 600 | 1px `risk.border` | 7 | `risk.bg` |
| Filter (active) | 30–31 | 0 11px | `ink` | `canvas` | 1px `line` | 8 | — |
| Filter (idle) | 30–31 | 0 11px | `surface` | `ink` | 1px `line` | 8 | `surface-active` |
| Portal primary | 34–38 | 0 15–18px | `ink` | 13–13.5px/600 | none | 9 | `ink-hover` |
| Text link | — | 0 | transparent | `accent.fg`, 600 | none | — | underline |

Disabled state (used on Migrate and Send-for-approval): background `line`, text `text-faint`,
cursor default, no hover.

---

## 5. SegmentedControl

Track: `background:surface-active; border:1px solid line; border-radius:9px; padding:3px; display:flex; gap:3–4px`.
Segment: `height:26–27px; padding:0 11–12px; border:0; border-radius:6px; font-size:12–12.5px; weight:500`.
Selected segment gets `background:surface`; others `transparent`. No shadow.

Used for: Table/Stage board, By customer/By owner, and the five case tabs.

---

## 6. StatCard (dashboard KPI)

```
background:surface; border:1px solid line; border-radius:11px;
padding:14px 15px 13px; box-shadow:card;
```

Stack:
1. Mono 9.5px `ls .08em` uppercase label, `text-subtle`.
2. Row, `margin-top:9px`, `align-items:baseline; gap:8px` — value 29px/600 `ls -.04em` (colour:
   `ink`, or the state fg when the metric itself is good/bad), then delta 11.5px/600 in the
   state fg.
3. Sparkline: `height:22px; display:flex; align-items:flex-end; gap:2px; margin-top:11px`.
   10 bars, `flex:1`, `border-radius:1px`, height `4 + value*18` px, fill = state **background**
   colour (or `#dedad3` for neutral).
4. Note 11.5px `text-subtle`, `margin-top:8px`.

---

## 7. SectionCard (dashboard block)

```
background:surface; border:1px solid line; border-radius:11px;
box-shadow:card; overflow:hidden; grid-column: span <1–4>;
```

**Header** — `padding:13px 15px 11px; border-bottom:1px solid line-soft; display:flex; gap:10px`:
title 13.5px/600 `ls -.01em`; subtitle 11.5px `text-subtle` `margin-top:2px`; trailing Chip.

**Body** — one of five renderers, selected by block type:

| Type | Body |
| --- | --- |
| `rows` | Full-bleed list of ListRow (§8) |
| `funnel` | `padding:15px; gap:9px` stack of FunnelRow (§9) + footnote |
| `chart` | 120px bar chart + legend (§10) |
| `bars` | `padding:14px 15px; gap:12px` stack of PersonBar (§11) + footnote |
| `spotlight` | `padding:15px` — badge row, 19px/600 title, 12.5px body, fact list, two buttons |

Footnote style: 11.5px `text-subtle`, `border-top:1px solid line-soft; padding-top:10px`.

Spotlight fact list: rows of `display:flex; justify-content:space-between; font-size:12px;
border-bottom:1px dashed line-soft; padding-bottom:6px` — key in `text-subtle`, value 600.

---

## 8. ListRow

A full-width `<button>`. Never a `<div>` with a click handler.

```
display:flex; align-items:center; gap:11px; padding:11px 15px;
border:0; border-bottom:1px solid line-faint; background:transparent;
text-align:left; cursor:pointer;
hover: background surface-sunken;
```

Contents: 7px status dot (`flex:0 0 7px`, semantic fg) · title block (13px/600 + 11.5px
`text-subtle` subtitle, both `text-overflow:ellipsis`) · trailing Chip.

---

## 9. FunnelRow

```
display:flex; align-items:center; gap:11px;   (button, hover: opacity .75)
```

- Stage name: `width:112px; flex:0 0 112px; font-size:12px; weight:500`, ellipsis.
- Track: `flex:1; height:22px; background:line-faint; border-radius:5px; overflow:hidden`.
  Fill: `height:100%; border-radius:5px; background:<stage colour>; width:<%>`.
- Count: `width:34px; text-align:right;` mono 11.5px/500.
- Dwell time: `width:56px; text-align:right;` mono 10px, coloured by whether it exceeds target.

---

## 10. BarChart

`display:flex; align-items:flex-end; gap:8px; height:120px`. Each column is
`flex:1; display:flex; flex-direction:column; justify-content:flex-end; gap:5px; height:100%`
containing the bar (`border-radius:4px 4px 0 0`, height as %) and a mono 9.5px centred label in
`text-subtle`.

Legend below: `border-top:1px solid line-soft; padding-top:10px; display:flex; gap:14px`; each item
is an 8×8 radius-2 swatch + 11.5px `text-muted` label.

---

## 11. PersonBar

```
display:flex; align-items:center; gap:10px;
```
Avatar 24px · body containing a `justify-content:space-between` row (name 12px/500, meta mono
10.5px `text-subtle`) above a 6px track (`line-faint`, radius 3) with a coloured fill.

---

## 12. DataTable

Header: `display:grid; grid-template-columns:<cols>; gap:12px; padding:9px 15px;
background:surface-sunken; border-bottom:1px solid line;` cells mono 9.5px `ls .08em` `text-subtle`.

Row: same grid, `padding:10–11px 15px; border-bottom:1px solid line-faint; align-items:center`,
rendered as a `<button>` when the row navigates. Hover `surface-sunken`.

Footer bar (optional): `padding:10px 15px; background:surface-sunken` with a mono 10px summary and
small secondary buttons.

Numeric and ID columns: mono, right-aligned. The identifying column: 12.5–13px/600 with an 11px
`text-subtle` subtitle underneath.

The portfolio grid has a **dense** and a **comfortable** column set — dense adds ACV and workflow
version. Make that a user preference, not a hard-coded layout.

---

## 13. ProgressBar

| Context | Height | Track | Radius |
| --- | --- | --- | --- |
| Table cell | 5px | `line-faint` | 3 |
| Stage summary | 5px | `line-faint` | 3 |
| Case hero | 7px | `line-faint` | 4 |
| Portal sidebar | 5px | `line-soft` | 3 |
| Portal card | 6px | `line-soft` | 3 |

Fill colour by state: `>70%` → `accent.fg`; `41–70%` → `warn.fg`; `≤40%` → `info.fg`; complete →
`ok.fg`. Case-level and stage-level bars use `ok.fg` when complete and `warn.fg` while in progress.

---

## 14. StageAccordion (case journey)

Each stage is a two-column row: a 26px rail column and the content.

- Rail: 24px circle — `✓` on `ok.fg` when complete, the stage number on `warn.fg` when active,
  the number on `line-soft` with `text-subtle` when not started. Below it a 2px vertical connector,
  `flex:1`, `margin:4px 0`, coloured to match.
- Header button: `surface`, 1px `line`, radius 10, `padding:12px 14px`, `display:flex; gap:12px`.
  Contains title 14px/600 `ls -.015em`, meta 11.5px `text-subtle`
  (`"2/3 milestones · Operations · 5d estimated"`), an 80px progress bar, a Chip, and a `▾`.
- Expanded panel: `margin-top:7px; border:1px solid line; border-radius:10px;
  background:surface-sunken`, animated with `om-pop`. Header strip is a mono 9px `ls .08em` label
  `MILESTONES IN THIS STAGE`. Then milestone rows: 7px dot · name 12.5px/600 · `owner · note`
  11.5px `text-subtle` · mono 10px duration · Chip.

---

## 15. CommandPalette

Overlay `rgba(28,27,24,.24)`, content aligned to top with `padding-top:11vh`.
Panel: `width:min(620px, 92vw); background:surface; border:1px solid line; border-radius:13px;
box-shadow:modal; overflow:hidden; animation:om-pop`.

- **Input row** `padding:13px 15px; border-bottom:1px solid line-soft`: `⌕` glyph, borderless
  14.5px input, `ESC` key hint (mono 10px in a 1px `line` box, radius 4).
- **Results** `max-height:min(52vh,420px); overflow-y:auto; padding:6px`. Each result is a
  `<button>`: `padding:9px 10px; border-radius:8px; gap:11px; hover surface-muted` containing a
  24px radius-6 icon tile in the item's semantic pair, a label 13px/500 with an 11px `text-subtle`
  hint, and a right-aligned mono 9.5px kind (`CASE` / `GO` / `ACTION`).
- **Footer** `padding:9px 15px; border-top:1px solid line-soft; background:surface-sunken`, mono
  9.5px `ls .05em` `text-subtle`: `↑↓ NAVIGATE`, `↵ OPEN`, `⌘K TOGGLE`, right-aligned result count.

Empty state: centred 12.5px `text-subtle`, 26px padding.

**Keyboard**: ⌘/Ctrl-K toggles, Esc closes, ↑↓ moves selection, ↵ runs. The prototype implements
toggle/close only — implement the full set. Autofocus the input on open; trap focus in the panel.

---

## 16. Drawer (inbox)

`position:fixed; inset-block:0; right:0; width:390px; background:canvas;
border-left:1px solid line; box-shadow:drawer; animation:om-slide .2s ease`, over a
`rgba(28,27,24,.18)` scrim that closes on click.

Header 52px matching the top bar: title 14px/600, mono 9.5px unread count, a Preferences toggle
button, and `✕`.

Two panes toggled by the header button:
- **List** — rows with a 26px radius-7 icon tile (semantic pair), title 12.5px/600, body 11.5px
  `text-muted`, mono 9.5px timestamp in `text-faint`. Unread rows are `surface`; read rows
  `transparent`.
- **Preferences** — an explanatory paragraph, then one row per notification type with a Toggle.

---

## 17. Toggle

`width:34px; height:19px; border-radius:11px; padding:2px; display:flex; border:0`.
Knob 15×15 circle `#fff` with `0 1px 2px rgba(0,0,0,.2)`.
On: `justify-content:flex-end; background:accent.fg`. Off: `justify-content:flex-start;
background:line-strong`. (A 32×18 / 28×16 variant with a 14px or 12px knob appears in inspector and
auth contexts.)

---

## 18. Modal (force-complete)

`width:min(520px,100%)`, `background:surface`, radius 13, `box-shadow:modal`, `om-pop`, over a
`rgba(28,27,24,.24)` scrim. Click-outside closes; the panel stops propagation.

- Header `padding:17px 20px; border-bottom:1px solid line-soft`: mono 9.5px `ls .08em` in `risk.fg`
  reading `PRIVILEGED ACTION · APPROVAL REQUIRED`, then 18px/600 title, then 12.5px context line.
- Body `padding:17px 20px; gap:14px`: a `risk` warning callout, a select, and a textarea
  (`min-height:74px`, `resize:vertical`) with a mono 9.5px `MANDATORY · MIN 10 CHARS` hint.
- Footer `padding:13px 20px; border-top:1px solid line-soft; background:surface-sunken`: Cancel
  left, submit right. **Submit is disabled until an approver is chosen and the reason exceeds
  9 characters.**

Field styling: label 11.5px `text-subtle` `margin-bottom:5px`; control `border:1px solid line;
border-radius:9px; padding:9px 11px; font-size:13px; background:surface`.

---

## 19. Toast

`position:fixed; bottom:20px; left:50%; translateX(-50%); background:ink; color:canvas;
border-radius:9px; padding:10px 15px; font-size:12.5px; box-shadow:toast; animation:om-pop`.
Leading 6px `#5fd0a8` dot. Auto-dismisses after **2600ms**.

---

## 20. Switcher dropdown (tenant / role / journey)

Trigger: full-width button, 1px `line`, radius 9–10, `padding:7–10px 9–11px`, with a trailing
`⇅` or `▾` in `text-subtle`. Hover: `surface-active` (tenant/role) or border `line-hover` (journey).

Menu: `border:1px solid line; border-radius:9–10px; background:surface; padding:4px;
box-shadow:dropdown; animation:om-pop`, pulled up with a negative top margin to sit against the
trigger. Rows: `padding:6–8px 8–9px; border-radius:6–7px; hover surface-muted`; the current
selection carries `background:surface-active`.

---

## 21. BuilderNode

```
draggable; cursor:grab; border-radius:10px; padding:11px 13px;
display:flex; align-items:center; gap:12px;
background: branch ? #faf7ff : surface;
border: 1px solid (selected ? ink : branch ? automation.border : line);
box-shadow: selected ? ring-selected : card;
opacity: dragging ? .4 : 1;
```

Left: `⋮⋮` handle in `text-ghost`. Then a 24px radius-7 number tile — `automation.fg` fill for a
branch (glyph `⑃`), `ink` fill when selected, `surface-active` otherwise. Then name 13.5px/600 with
an optional `CONDITIONAL` chip, and a meta line `team · SLA · transition rule`. Right: milestone
name pills wrapped in a `max-width:44%` flex — 10.5px, `surface-active`, 1px `line`, radius 5.

Connector between nodes: a 14px-tall centred 1px `line-strong` vertical rule.

Drop behaviour: `dragstart` records the node id, `dragover` prevents default, `drop` splices the
dragged node in at the target index.

---

## 22. Empty, loading and error states

Not drawn in the prototype — you must supply them, in this style:

- **Loading**: skeleton blocks at `line-faint`, matching the real element's radius and height. No
  spinners inside cards; a spinner only for full-page transitions.
- **Empty**: centred, 26–40px padding, 12.5px `text-subtle` sentence that names the next action
  ("No exceptions right now. Nothing is past its SLA.") — never a bare "No data".
- **Error**: a `risk` callout (1px `risk.border`, `risk.bg`, radius 9–10, `padding:11px 12px`)
  with a 12px `#5c2a24` message and a retry button.
- **Optimistic actions**: the prototype fires a Toast immediately. In production, show the toast on
  server confirmation and roll back visibly on failure — these are audited actions.
