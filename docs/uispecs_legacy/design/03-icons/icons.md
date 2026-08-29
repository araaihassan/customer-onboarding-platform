# Onboard OS — Icon Set

56 outlined icons. Visual gallery: [`icon-sheet.html`](icon-sheet.html).

Replaces the 18 Unicode placeholder glyphs the prototype used. The handoff README was explicit
that those "were chosen to avoid shipping an icon dependency into a prototype, not as a design
decision" — this is that decision.

---

## Specification

| | |
|---|---|
| Grid | 24 × 24 |
| Live area | inset to 3–21; nothing touches the edge |
| Stroke | 1.5, `currentColor` |
| Caps / joins | round, both |
| Fill | none, always |
| Structure | **one `<path>` per icon** |

### Why one path

Every icon is a single `d` string, using subpaths (`M…M…`) rather than multiple elements. This is
not a stylistic choice, it is what makes the set portable:

- it can live in a **data layer** — the prototype binds `<path d="{{ item.iconPath }}">` straight
  from its nav array, which a multi-element icon cannot do;
- the **sprite** is a trivial transform of the same string;
- a **registry** is a flat `name → string` map with no parsing;
- diffing an icon change is one line.

The trade-off is that icons needing two colours or a fill are out of scope. Nothing in this
product needs them.

---

## Usage

### Inline SVG (preferred in-product)

```html
<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
     stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d="M4 5a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z…"/>
</svg>
```

### Sprite

```html
<svg class="icon" aria-hidden="true"><use href="icons-sprite.svg#i-search"/></svg>
```

```css
.icon { width: 16px; height: 16px; fill: none; stroke: currentColor;
        stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
```

### Registry

`icons.json` holds every icon as `{ category, description, path }`, plus a `replaces` array
mapping each old glyph to its icon and the place it appeared. Generate framework components from
it rather than copying paths by hand.

### Individual files

`svg/<name>.svg` — 56 standalone files, each with a `<title>` and `aria-hidden="true"`, for
design tools and any pipeline that wants files.

---

## Sizing

Stroke stays 1.5 across the normal range. Adjust only at the extremes, so weight looks constant:

| Size | Stroke | Where |
|------|--------|-------|
| 13–14px | 1.75 | dense table cells, inline meta |
| **16px** | **1.5** | rail nav, buttons, header — the default |
| 18–20px | 1.5 | mobile tab bar, section headers |
| 24px | 1.5 | dropzones, empty states |
| 32px+ | 1.25 | illustrations, marketing |

In the prototype: rail 16, header search 15, bell 16, milestone chevron 15, milestone status
glyph 14 (stroke 2.4, because it sits reversed inside a 26px coloured circle), task checkbox 11
(stroke 3, same reason), stage controls 13, mobile tabs 18.

Reversed-out icons on a coloured fill need a heavier stroke than the same icon on paper — the
optical thinning is real. 2.4 at 14px and 3 at 11px are the calibrated values.

---

## Colour

Icons carry **no colour of their own.** They inherit `currentColor`, which means the parent
decides and dark mode is free.

An icon may only take status colour when the icon **is** the status:

| State | Fill | Ink | Icon |
|-------|------|-----|------|
| On track / Completed | `status-on-track-bg` | `status-on-track-fg` | `check-circle` |
| In progress | `status-progress-bg` | `status-progress-fg` | `clock` |
| At risk | `status-at-risk-bg` | `status-at-risk-fg` | `alert-triangle` |
| Blocked | `status-blocked-bg` | `status-blocked-fg` | `alert-circle` |
| Waiting | `status-neutral-bg` | `status-neutral-fg` | `pause-circle` |
| Cancelled | `status-neutral-bg` | `status-neutral-fg` | `x-circle` |

An icon inside otherwise-neutral UI inherits text colour and stays neutral, even if the row it
sits in concerns a blocked case.

---

## Accessibility

- Decorative icon beside a text label → `aria-hidden="true"`. It would otherwise be announced
  twice.
- Icon-only control → `aria-label` on the **control**, `aria-hidden` on the svg. Applied in the
  prototype to the stage reorder/delete buttons and the task checkbox.
- Icon carrying meaning with no adjacent text → give it `role="img"` and a `<title>`, or better,
  add the text. See review finding §10: three places in the prototype currently encode status in
  colour alone.
- Never rely on an icon alone to distinguish two states. `check-circle` and `alert-circle` differ
  by their interior stroke, which is 2px at 16px — invisible to plenty of people.

---

## Replaces these placeholders

| Was | Now | Where it appeared |
|-----|-----|-------------------|
| `◫` | `layout-dashboard` | Dashboard nav, portal mobile Progress tab |
| `☰` | `users` | Customers nav |
| `◈` | `journey` | Journey workspace nav |
| `⚙` | `workflow` | Workflow builder nav |
| `⚙` | `refresh-cw` | Automation actor in the activity feed |
| `◉` | `chart-column` | Reports nav |
| `◐` | `portal-window` | Customer portal nav |
| `✎` | `pencil-line` | Design notes nav |
| `⌕` | `search` | Header search field |
| `◔` | `bell` | Header notification button, portal Updates tab |
| `＋` | `plus` | New case chip, Add condition |
| `✕` | `x` | Delete stage |
| `▲` | `chevron-up` | Move stage up |
| `▼` | `chevron-down` | Move stage down |
| `▾` | `chevron-down` | Milestone expand chevron (rotates 180°) |
| `✓` | `check` | Task checkbox, completed milestone circle |
| `!` | `exclamation` | Blocked milestone circle |
| `→` | `arrow-right` | Branch rule target |
| `↥` | `upload` | Document dropzone |
| `⎙` | `file-text` | Portal mobile Documents tab |

Two `→` remain in the source. Both are typographic arrows inside running sentences
("Agreement status → Awaiting signature"), not UI icons, and are correctly left as text.

---

## Categories

| Category | Count | Contents |
|----------|-------|----------|
| Navigation & shell | 7 | one per rail destination |
| Actions & controls | 11 | search, plus, x, four chevrons, arrow, overflow, filter, grip |
| Status & health | 8 | check, check-circle, alert-triangle, alert-circle, clock, pause-circle, x-circle, exclamation |
| Files & data | 9 | file-text, file-signature, folder, download, upload, paperclip, eye, lock, layers |
| Onboarding domain | 10 | shield-check, scale, sliders, graduation-cap, flag, clipboard-check, user-check, building, credit-card, refresh-cw |
| Communication & meta | 7 | bell, calendar, message-square, at-sign, link, history, external-link |
| Metrics & tasks | 4 | trending-up, trending-down, check-square, square |

The domain group maps to the nine journey stages and the departments that own them, so a stage
list can be iconised without inventing anything: Registration `user-check`, Sales Approval
`user-check`, Agreement `file-signature`, Document Collection `folder`, Verification
`clipboard-check`, Technical Setup `sliders`, Testing `check-square`, Training
`graduation-cap`, Go Live `flag`.

---

## Extending the set

1. Add the entry to `ICONS` in `build_icons.py` — `name: (category, path, description)`.
2. Draw on the 24 grid, live area 3–21, single path, no `;` in the `d` string.
3. Re-run `python build_icons.py`. Files, sprite, registry and gallery regenerate together.
4. Check it in `icon-sheet.html` at 14px and 16px, not at 48px. Most icon mistakes are only
   visible small.

Names are lowercase kebab-case and describe the **thing**, not the use — `file-signature`, not
`agreement-icon`. Two features may need the same icon; a name tied to one of them ages badly.
