# Onboard OS — Component Specifications

Every component in the prototype, expressed in tokens rather than literals, with the states and
accessibility requirements the prototype left implicit.

The handoff `README.md` describes *what each screen looks like*. This describes *what each
component is*, so it can be built once and reused across the nine screens. Where the two differ,
the prototype is the truth — it has been updated and re-verified.

Token names are the semantic layer from [`../02-tokens/`](../02-tokens/). `space-16` means
`var(--ob-space-16)`.

---

## Foundations

### Shell

| | |
|---|---|
| Layout | fixed rail + fluid main column, `min-height: 100vh` |
| Rail | `rail-width` (244px), `flex: 0 0 244px`, sticky, `100vh`, flex column |
| Main | `flex: 1; min-width: 0` — the `min-width` matters, without it long mono strings blow out the grid |
| Header | `header-height` (60px), sticky, `z-index: 30` |
| Content | padding `content-padding-top` / `content-padding-x` / `content-padding-bottom` (24 / 28 / 56) |

The 56px bottom padding is deliberate: it lets the last table row clear the fold so it does not
look truncated.

### Density

`density: comfortable | compact` changes **only** table row vertical padding —
`table-row-padding-y` 13px → `table-row-padding-y-compact` 8px. It must not change type size,
radii or gaps; those are what make the product feel like itself at either density.

---

## 1. Rail

### Logo block
Padding `22px 20px 18px`, gap `space-10`. 26px `logo-tile.svg`, then product name `type-14`/600
at `-0.01em`, tenant slug `type-10` mono at 50% opacity.

### Nav item

| Property | Value |
|---|---|
| Box | full width, padding `8px 11px`, radius `rail-item-radius` (9px), gap `space-10` |
| Text | `type-13`, `text-on-rail` |
| Icon | 16px, stroke 1.5, `currentColor`, `opacity: 0.85`, in a fixed 16px column |
| Count pill | `type-10` mono, radius `radius-pill`, `rgba(255,255,255,0.12)`, right-aligned |

| State | Background | Opacity | Weight |
|---|---|---|---|
| Inactive | transparent | 0.72 | 400 |
| Hover | `rgba(255,255,255,0.07)` | 1 | 400 |
| Active | `bg-rail-raised` | 1 | 500 |
| Focus | + 2px `accent` outline, 2px offset | | |

Seven items: Dashboard, Customers (48), Journey workspace, Workflow builder, Reports, Customer
portal, Design notes.

**Accessibility.** `<nav>` with a list; active item carries `aria-current="page"`. Icons are
`aria-hidden` — the label is already there.

### Role switcher
"VIEWING AS" label `type-9-5` mono uppercase at 55% opacity — *not* 45%; at 45% it measured
4.09:1 and failed AA. Below it a 2-up segmented control, 27px tall, radius `radius-segment`,
inside a `rgba(255,255,255,0.07)` track. Then a 28px circular avatar, name `type-12-5`/500, role
line `type-10-5` at 50%.

Selecting **Customer** jumps to the portal and relabels the user line to "Impersonating
customer". Implement as a real permission boundary, not a view flag — see §12.

**Accessibility.** `role="radiogroup"` with two radios, not two buttons.

---

## 2. Header

60px, sticky, `rgba(247,246,243,0.88)` + `backdrop-filter: blur(10px)`, 1px `border-default`
bottom, `space-28` horizontal padding, `space-18` gap.

- **Left**: screen title `type-17`/600 `-0.02em`; meta line `type-11` mono `text-muted`,
  `overflow: hidden; text-overflow: ellipsis`.
- **Right**: search field (230px × `control-height`, radius `radius-control`, 1px
  `border-default`, `bg-surface`, 15px `search` icon, "⌘K" chip in `radius-bar`); notification
  button (34px square, `radius-control`, 16px `bell` icon, count badge `solid-blocked` at
  top-right); primary button.

Search and the "New onboarding" button are **visual only** in the prototype.

**Accessibility.** The bell needs `aria-label="Notifications, 7 unread"` and
`aria-expanded`. The count badge alone is not a name.

### Notification panel
Fixed, `top: 66px; right: 24px`, 348px, `bg-overlay`, radius `radius-overlay`, 1px
`border-default`, `elevation-popover`, `fadeUp` `duration-instant`. Rows: status dot + `type-12-5`
text + `type-10` mono meta, 1px `border-subtle` dividers. Closes on navigation.

**Accessibility.** Focus moves in on open, `Escape` closes and returns focus to the bell, focus is
trapped while open.

---

## 3. Card

The base surface for almost everything.

| | |
|---|---|
| Background | `bg-surface` |
| Border | 1px `border-default` |
| Radius | `card-radius` (13px) |
| Padding | `card-padding-y` / `card-padding-x` (16–22 / 17–20 by content density) |
| Elevation | `elevation-flat` — cards do not float |

**Card header**: title `type-13-5`/600, optional right-aligned `type-10-5` mono `text-faint`
count, `space-16` bottom margin.

---

## 4. KPI card

Card + label `type-10` mono uppercase `text-faint`; value `type-29`/600 `-0.03em`,
`line-height: 1`; delta `type-11` mono coloured by direction; sub-line `type-11-5` `text-muted`.

Deltas carry sign and unit: `+6`, `−4d`, `+3`, `−1%`. Colour is by *desirability*, not by sign —
"Overdue tasks +3" is red, "Active projects +6" is green. Pair each with `trending-up` /
`trending-down` so direction is not colour-only.

---

## 5. Progress bar

| Context | Track height | Radius | Fill |
|---|---|---|---|
| Inline (row, milestone, portal) | `progress-track-height` (6px) | `radius-bar` | `accent` |
| Pipeline by stage | `progress-track-height-lg` (22px) | `radius-check` | `accent`, `solid-at-risk` for the bottleneck |
| Team workload | 6px | `radius-bar` | by health |
| Roadmap segment | 5px | `radius-bar` | `solid-on-track` / `accent` / `bg-inset` |
| Agreement tracker | four equal 4px bars | `radius-bar` | `solid-on-track` / `accent` / `bg-inset` |

Track `bg-inset`. Width transitions over `duration-progress` (0.4s) `ease`.

**Accessibility — currently missing.** Add `role="progressbar"` with `aria-valuenow`,
`aria-valuemin="0"`, `aria-valuemax="100"` and `aria-label`. The visible percentage and
`aria-valuenow` must come from one value; today they are separate DOM text and can disagree.

---

## 6. Status pill

`type-9-5` mono, `letter-spacing: 0.05em`, uppercase, padding `3px 8px`, radius `radius-pill`,
`white-space: nowrap`. Background `status-*-bg`, colour `status-*-fg`.

| Pill | Role |
|---|---|
| On track, Designed, Signed, Completed, Approved | `status-on-track` |
| In Progress | `status-progress` |
| At risk, Awaiting Signature, Requested | `status-at-risk` |
| Blocked, Overdue, Cancelled, Expired | `status-blocked` |
| Pending, Waiting, Draft | `status-neutral` |

The pill always contains the **word**. Never a bare coloured dot where the pill would fit — that
is the pattern review §10 flags in three other places.

---

## 7. Table

| | |
|---|---|
| Container | card, `overflow: hidden` |
| Header row | `bg-surface-subtle`, `type-9-5` mono uppercase `text-faint` |
| Body row | `table-row-padding-y` vertical / `table-row-padding-x` horizontal |
| Divider | 1px `border-subtle` |
| Hover | `bg-surface-subtle` |
| Row click | navigates |

Column grids are per-table and specified in the handoff README (customers
`2.1fr 1.2fr 1.5fr 1fr 1fr 0.7fr`; documents `2.2fr 1.1fr 0.7fr 0.9fr 1.3fr 0.9fr`; tasks
`24px 2.4fr 1fr 1fr 1fr 1fr`).

**Accessibility.** Use a real `<table>` with `<th scope="col">`, or if it must stay CSS grid, add
`role="table"`/`row`/`columnheader`/`cell`. A clickable row must be a `<tr>` containing a link on
the primary cell — a `<div>` with an onClick is not keyboard reachable and this pattern appears in
both the dashboard and the customer list.

### Cells
- **Entity**: 30px rounded-square initials avatar (`radius-chip`, `bg-inset-strong`) + name
  `type-13`/500 + `type-10` mono sub-line.
- **Progress**: 6px bar + percentage.
- **Date**: `type-11` mono; `status-blocked-fg` when overdue, `status-at-risk-fg` when expiring
  within 30 days.
- **Visible to**: dot + scope text. Green dot = all contacts, grey = narrower.

---

## 8. Chips

### Filter chip
`control-height-sm` (30px), radius `radius-chip`, padding `0 13px`, `type-12`.
Inactive: `bg-surface` + 1px `border-default` + `text-secondary`.
Active: `text-primary` fill + `bg-surface` text (i.e. inverted).

**Accessibility.** A filter set is a `role="group"`; each chip is `aria-pressed`.

### Case chip
Dot + name + `type-9-5` mono ID. Active = inverted like the filter chip. Plus a dashed
`＋ New case` chip: 27px, radius `radius-chip`, 1px dashed `border-dashed`, `plus` icon at 13px,
`display: inline-flex; gap: 5px`.

### Range chip
Same as filter chip. 30 days / 90 days / 12 months.

---

## 9. Tabs

`padding: 9px 15px 11px`, `type-13`, on a 1px `border-default` rule.
Active: 600 + `box-shadow: inset 0 -2px 0 var(--ob-accent)`. Inactive: 400 `text-muted`.
Counts in the label: `Tasks (8)`, `Documents (8)`, `Agreements (4)`.

**Accessibility.** `role="tablist"` / `tab` / `tabpanel`, `aria-selected`, arrow-key navigation.

---

## 10. Milestone row

The spine of the product.

| | |
|---|---|
| Card | radius `card-radius`, grid `26px 1fr auto`, padding `14px 18px` |
| Closed | 1px `border-default`, `elevation-flat` |
| Open | 1px `border-strong`, `elevation-raised` |

### Status circle — 26px, `border-radius: full`, white ink

| State | Fill | Icon |
|---|---|---|
| Done | `solid-on-track` | `check`, 14px, stroke 2.4 |
| Active | `accent` | none |
| Blocked | `solid-blocked` | `exclamation`, 14px, stroke 2.4 |
| Pending | `paper-500` | none |

Stroke 2.4 rather than 1.5: reversed out of a saturated fill at 14px, 1.5 optically disappears.

### Body and controls
Title `type-13-5`/600 + status pill + optional "blocked by X" in `type-10` mono
`status-blocked-fg`. Sub-line `type-11-5` `text-muted`. Right: due date `type-11` mono over owner
`type-10-5`, 74px progress bar, then a 15px `chevron-down` rotating 180° over `duration-fast`.

### Expanded panel
1px `border-panel` top, padding `16px 18px 18px 58px` (the 58px left indent aligns content past
the status circle), grid `1.4fr 1fr`, gap `space-22`, `fadeUp` `duration-instant`.

- **Left** — `TASKS` (`type-9-5` mono uppercase) then task rows; `DOCUMENTS` then document chips.
- **Right** — `DEPENDENCIES` prose, `COMMENTS` (22px avatar + `type-12` + `type-9-5` mono
  timestamp), dashed comment affordance.

Several milestones may be open at once. State is per-milestone.

**Accessibility.** The header is a `<button>` with `aria-expanded` and `aria-controls`; the panel
gets `role="region"` and `aria-labelledby` pointing at the title.

---

## 11. Task row & checkbox

Checkbox: `checkbox-size` (17px), radius `radius-check`.
Unchecked `bg-surface` + 1px `paper-500`. Checked `solid-on-track` fill + white `check` at 11px,
**stroke 3** — again because it is reversed out small.

Checked also sets the title to `line-through` + `text-disabled`, and recomputes the status pill to
`Completed`.

Row: checkbox + title `type-12-5` + priority pill + right-aligned `assignee · due` in `type-10`
mono.

**Accessibility — partially fixed.** The control now has `aria-label`, but it is still a
`<button>` with no checked state. Use `<input type="checkbox">`, or add `role="checkbox"` +
`aria-checked`. The title change must not be the only signal.

---

## 12. Workflow stage row

| | |
|---|---|
| Box | `bg-surface`, radius `radius-row` (11px), padding `12px 16px`, `align-items: flex-start` |
| Selected | 1px `accent` border + `elevation-ring-accent` |

Contents: 2-digit `type-10` mono index, name `type-13`/500, `APPROVAL` / `AUTO` mono badges,
sub-line "department · SLA · write scope", and — when the stage branches — a rule strip
(`accent-tint` fill, `accent-tint-border` border): `IF <condition>` + 13px `arrow-right` +
`<target>`, with `else <fallback>` right-aligned in `type-9-5` mono.

Controls: three `control-height-xs` (26px) buttons, radius `radius-segment`, containing
`chevron-up` / `chevron-down` / `x` at 13px. Delete stops propagation. Delete hover goes
`status-blocked-bg` / `status-blocked-fg`.

**Accessibility — fixed.** All three carry `aria-label` ("Move stage up", "Move stage down",
"Delete stage"). Reordering should also announce the new position via a live region, and a
`grip-vertical` handle is available if drag is added.

### Inspector
Sticky at `top: 76px`. `STAGE CONFIGURATION` label, stage name `type-15`/600, then read-only-styled
fields (32px, radius `radius-chip`, `bg-surface-sunken`), then three toggles (34×20px track, radius
`radius-pill`, `accent` when on).

**Accessibility.** Toggles are `role="switch"` with `aria-checked`. Fields that look like inputs
but are not must be `readonly`, or they lie about being editable.

---

## 13. Document chip & dropzone

**Chip**: mono extension badge + name + status pill, radius `radius-inner`.

**Dropzone**: `bg-surface`, 1px dashed `border-dashed`, radius `card-radius`, padding `26px 18px`,
centred. 24px `upload` icon in the graphics-only grey (`#8f8a82`), label `type-12-5`/500, hint
`type-11` `text-muted`. Visual only in the prototype.

**Accessibility.** A dropzone must also be a button that opens a file picker; drag-and-drop alone
is not operable by keyboard.

---

## 14. Agreement card

2-up grid. Title `type-14`/600, mono meta, status pill. Four-step tracker (Draft → Review → Sent →
Signed) as four equal 4px bars with labels — completed steps `solid-on-track` when signed or
`accent` when in flight, remaining `bg-inset`. Footer: expiry/mono status + "Preview" + one
context action (Download / Resend / Nudge).

**Accessibility.** The tracker is a `role="list"` of steps with the current one carrying
`aria-current="step"`, not four unlabelled bars.

---

## 15. Timeline row

Grid `92px 22px 1fr`: right-aligned `type-10-5` mono timestamp, dot on a 1px `border-subtle`
vertical rule, event text `type-12-5` + `type-10` mono meta. Header notes "Immutable · 214
events · export CSV".

Immutable by design — render as a list, never as editable rows.

---

## 16. Avatars

| Size | Radius | Where |
|---|---|---|
| 22px | full | activity feed, comments |
| 28px | full | rail user |
| 30px | `radius-chip` | table rows |
| 46px | `radius-avatar-lg` | workspace header |

Initials, `type-9-5`–`type-11`/600, `bg-inset-strong` on `text-secondary`. **Circular = a person,
rounded-square = a company.** Keep that distinction; it is doing quiet work in the customer table.

An automated actor uses a 13px icon (`refresh-cw`) instead of initials, on an `accent-tint`
background — a system action should not look like a person's.

---

## 17. Customer portal

Everything above applies, with three overrides:

1. **Nothing internal.** No internal notes, no role names, no SLA language, no other customers.
   `PRD.md` §12. Enforce server-side; a client-side filter is not a boundary.
2. **Touch targets ≥ `touch-target-min` (44px).** The mobile upload button is full-width and 46px,
   repeated per requested document.
3. **Plain register.** "You're in Verification", not "Case status: VERIFICATION".

**Web**: browser-chrome frame (38px `bg-inset-strong` bar, three 9px dots, centred mono URL) around
a `34px/44px`-padded page. Hero eyebrow `type-10` mono, headline `type-30`/600, copy `type-13-5` at
`max-width: 46ch`, 210px progress block.

**Mobile**: 372×764 frame, radius 42px, 11px bezel, inner radius 32px, `elevation-device`. 44px
status bar, 20px horizontal padding, 62px bottom tab bar with four 18px icons.

The mobile frame is a fixed-size mock, not a breakpoint. Build it as the real mobile layout.

---

## Cross-cutting requirements

**Focus.** Every interactive element shows a 2px `accent` outline at 2px offset on
`:focus-visible`. Applied globally in the prototype; keep it when componentising.

**Reduced motion.** All four animations collapse under `prefers-reduced-motion: reduce`.

**Colour is never the only signal.** Every status colour is paired with a word or an icon. Three
places in the prototype still break this (review §10).

**Keyboard.** Rail, chips, tabs, milestone expansion, checkboxes, stage reorder and the device
toggle are all operable, with visible focus and no traps outside the notification panel.

**Empty states.** Not designed in the prototype. Every list needs one: what belongs here, why it
is empty, and the action that fills it — a 24px icon in graphics grey, `type-13-5`/600 line, and
`type-12` `text-muted` explanation.

**Loading.** Not designed either. Tables and cards need skeletons at the real row height so the
layout does not jump. The prototype's `hint-placeholder-count` attributes record the expected
counts to skeleton (4 KPIs, 9 funnel rows, 4 notifications).
