# Design Tokens

Every value used in the design. Copy these into your theme config before building anything.

## Colour

The palette is warm-neutral. Whites and greys carry a slight yellow cast (never blue-grey), and the
semantic colours are muted jewel tones that sit at similar lightness so no single state screams
louder than another. Primary actions are near-black, not branded colour — colour is reserved for
meaning.

### Neutrals

| Token | Hex | Use |
| --- | --- | --- |
| `canvas` | `#fbfaf8` | App background, sidebar, top bar |
| `surface` | `#ffffff` | Cards, tables, drawers, modals |
| `surface-sunken` | `#faf9f6` | Table headers, card footers, inset panels, row hover |
| `surface-muted` | `#f6f5f2` | Board column backgrounds, palette row hover |
| `surface-active` | `#f2f0ec` | Active nav item, segmented-control track, neutral chip bg |
| `line` | `#e7e4de` | Default 1px border |
| `line-soft` | `#efece7` | Internal card dividers |
| `line-faint` | `#f4f2ee` | Table row dividers, progress-track fill |
| `line-strong` | `#dedad3` | Dashed dividers, inactive toggle track, scrollbar thumb |
| `line-hover` | `#cfcbc3` | Border on hover, dashed drop zones |
| `ink` | `#1c1b18` | Primary text, primary buttons, icon rail |
| `ink-hover` | `#302e2a` | Primary button hover, rail button bg |
| `ink-rail-active` | `#3d3a35` | Active icon-rail button |
| `text-2` | `#4a4741` | Secondary body copy inside callouts |
| `text-muted` | `#6b6862` | Supporting paragraphs |
| `text-subtle` | `#8b8780` | Metadata, row subtitles, placeholder |
| `text-faint` | `#a5a099` | Mono eyebrow labels, timestamps |
| `text-ghost` | `#c5c0b8` | Drag handles, disabled matrix cells |
| `avatar-neutral` | `#4b4842` | Neutral avatar fill |

### Semantic pairs

Each state is a foreground/background pair. Foregrounds are used for text, icons, bar fills and
numerals; backgrounds only for chips, badges and callouts.

| State | Foreground | Background | Border (callouts only) | Meaning |
| --- | --- | --- | --- | --- |
| `ok` | `#2f7d4f` | `#e8f3ec` | `#cfe4d7` | Complete, on track, signed, approved |
| `warn` | `#9a6410` | `#fbf1de` | `#f0e0bc` | Due today, aged, waiting on someone |
| `risk` | `#b4392f` | `#fbeae7` | `#f2d3cd` | Breached, escalated, restricted, over capacity |
| `info` | `#2b5fb0` | `#e9f0fb` | `#d5e2f5` | Clock paused, contact-only, informational |
| `accent` | `#10736b` | `#e6f2f0` | — | Links, progress fills, active/live, brand mark |
| `automation` | `#6a4fb0` | `#f0ebfa` | `#d8cdf0` | System-generated, workflow versions, branches, multi-journey |
| `neutral` | `#6b6862` | `#f2f0ec` | — | Not started, archived, no state |

`accent` hover for links and text buttons: `#0b544e`. `automation` chip hover: `#e6ddf7`.

### Avatar palette

Cycle in this order; index by a stable hash of the person's initials.

`#10736b` `#b4392f` `#6a4fb0` `#2b5fb0` `#9a6410` `#2f7d4f` `#4b4842`

Avatar text is always `#ffffff`, weight 600. Overlapping presence avatars get a 2px `#fbfaf8`
border and `-6px` left margin.

### Chart colours

| Series | Colour |
| --- | --- |
| Completed / activated | `#10736b` |
| Remaining estimate | `#efece7` |
| At risk / over target | `#b4392f` |
| Clock running (in a split bar) | `#9a6410` |
| Clock paused (in a split bar) | `#f0e0bc` |
| Compliance/turnaround series | `#6a4fb0` |
| Sparkline fill | the state's **background** colour, or `#dedad3` for neutral |

## Typography

Two families. Nothing else.

```
Instrument Sans  — 400, 500, 600, 700 (+ italics)   UI and body
Spline Sans Mono — 400, 500, 600                    labels, IDs, numerals, metadata
```

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Instrument+Sans:ital,wght@0,400..700;1,400..700&family=Spline+Sans+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```

Fallbacks: `'Instrument Sans', ui-sans-serif, system-ui, sans-serif` /
`'Spline Sans Mono', ui-monospace, monospace`.

### Scale

| Role | Size | Weight | Letter-spacing | Notes |
| --- | --- | --- | --- | --- |
| Page title (operator) | 26px | 600 | -0.03em | |
| Page title (customer) | 28–30px | 600 | -0.035em | Portal is one step larger and warmer |
| Big metric | 29px | 600 | -0.04em | Dashboard KPI value |
| Hero metric | 34–38px | 600 | -0.04em | Case progress, portal progress |
| Section heading | 19–20px | 600 | -0.025em | Spotlight titles, portal card heads |
| Card title | 13.5px | 600 | -0.01em | |
| Body / base | 13.5px | 400 | — | line-height 1.45 |
| Table cell | 12.5px | 400/600 | — | 600 for the identifying column |
| Row subtitle | 11.5px | 400 | — | `text-subtle` |
| Small print | 11px | 400 | — | |
| **Mono label** | 9.5–10px | 400/500 | 0.06–0.1em | UPPERCASE, `text-faint` or `text-subtle` |
| **Mono chip** | 9–9.5px | 400 | 0.05em | UPPERCASE inside a chip |
| **Mono data** | 10–11px | 400/500 | — | Case IDs, dates, durations, counts |

Apply `text-wrap: pretty` to every paragraph and any heading that can wrap.

Numerals that are compared down a column (progress %, days, currency, counts) are set in Spline
Sans Mono and right-aligned. Never set a comparable column in the sans face.

## Spacing

4px base. Values actually used:

`2 · 3 · 4 · 5 · 6 · 7 · 8 · 9 · 10 · 11 · 12 · 13 · 14 · 15 · 16 · 18 · 20 · 22 · 26 · 40`

| Context | Value |
| --- | --- |
| Page padding (operator) | `22px 22px 40px` |
| Page padding (customer) | `26px 26px 40px` |
| Card padding | `13–15px` header, `14–18px` body |
| Portal card padding | `17–22px` |
| Table row padding | `10–11px 15px` |
| Grid gap (cards) | `11–12px` |
| Stack gap (list items) | `6–10px` |
| Inline gap (chips, icons) | `6–9px` |

**Always use flex/grid `gap`** for sibling groups. No margin-based spacing between siblings.

## Layout

| Region | Value |
| --- | --- |
| Icon rail | `56px` fixed, `#1c1b18` |
| Sidebar | `250px` fixed, `#fbfaf8`, 1px right border |
| Top bar | `52px` fixed height, 1px bottom border |
| Content | fills remainder, `overflow-y: auto` |
| Dashboard grid | `repeat(4, minmax(0,1fr))`, blocks span 1–4 |
| Board columns | `repeat(4|5, minmax(0,1fr))` |
| Case workspace | content `flex: 1 1 520px` + aside `flex: 1 1 296px; min 264px; max 340px`, wrapping |
| Content max-width | `1500px` dashboard, `760–1000px` customer portal |

## Radius

| Value | Use |
| --- | --- |
| `4px` | Mono chips, sparkline bars, legend swatches |
| `5px` | Status chips, file-type tiles |
| `6px` | Segmented-control segments, dropdown rows |
| `7px` | Small buttons (h 26–29), icon buttons |
| `8px` | Standard buttons (h 30–32), nav items, inputs |
| `9px` | Portal buttons (h 34–38), rail buttons, inputs in modals |
| `10px` | Inset panels, expanded milestone panels, builder nodes |
| `11px` | Cards |
| `13px` | Portal cards, modals, command palette |
| `50%` | Avatars, step dots, status dots |

## Shadows

| Token | Value | Use |
| --- | --- | --- |
| `card` | `0 1px 2px rgba(28,27,24,.03)` | Every card. Subtle by design. |
| `dropdown` | `0 8px 24px rgba(28,27,24,.08)` | Tenant/role/journey switchers |
| `drawer` | `-20px 0 50px rgba(28,27,24,.1)` | Inbox drawer |
| `modal` | `0 30px 70px rgba(28,27,24,.22)` | Command palette, force-complete |
| `toast` | `0 12px 30px rgba(28,27,24,.25)` | Toast |
| `ring-selected` | `0 0 0 3px rgba(28,27,24,.07)` | Selected builder node |

Scrim behind overlays: `rgba(28,27,24,.18)` drawer, `rgba(28,27,24,.24)` modal and palette.

## Motion

Three keyframes, short and unfussy.

```css
@keyframes om-pop   { from { opacity:0; transform:translateY(6px) scale(.99); } to { opacity:1; transform:none; } }
@keyframes om-slide { from { transform:translateX(24px); opacity:0; }          to { transform:none; opacity:1; } }
@keyframes om-pulse { 0%,100% { opacity:1; } 50% { opacity:.35; } }
```

| Element | Animation |
| --- | --- |
| Dropdowns, expanded panels, modals, toast | `om-pop .16s ease` |
| Inbox drawer | `om-slide .2s ease` |
| War-room dot in sidebar | `om-pulse 2.4s infinite` |
| Hover state changes | instant (no transition) — deliberate; the UI feels crisper without them |

If the codebase uses Framer Motion, `om-pop` maps to
`initial={{opacity:0, y:6, scale:.99}} animate={{opacity:1, y:0, scale:1}} transition={{duration:.16}}`.

Respect `prefers-reduced-motion`: drop `om-pulse` and the transform components, keep opacity.

## Iconography

The design uses **typographic glyphs, not an icon set**: `◈ ✓ ◔ ▤ ▭ ◭ ▣ § ⎇ ⇄ ⊞ ◱ ◎ ⌘ ◐ ⌕ ⇅ ▾ ⋮⋮ ⑃ ✕ ↑ ↓ → ← ⚠ 🔒`.

This was a prototype convenience. **In production, substitute your codebase's icon library**
(Lucide is a good match for the weight). Keep icons at 11–14px, `text-subtle` in navigation and
`currentColor` inside chips. Do not commission or hand-draw custom SVG icons for this.

## Scrollbars

```css
::-webkit-scrollbar { width:10px; height:10px; }
::-webkit-scrollbar-thumb { background:#dedad3; border-radius:8px; border:3px solid #fbfaf8; }
::-webkit-scrollbar-track { background:transparent; }
```

## Links

```css
a         { color:#10736b; text-decoration:none; }
a:hover   { color:#0b544e; text-decoration:underline; }
::selection { background:#cfe7e4; }
```
