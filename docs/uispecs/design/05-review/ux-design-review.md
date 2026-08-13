# UX & Accessibility Review — Onboard OS

Review of the prototype as handed off (`Onboarding Platform.dc.html`, 1,428 lines) against
`PRD.md` and the handoff `README.md`. Findings are ordered by severity. Every measurement here
was computed from the prototype's actual values, not estimated — the scripts are named per
finding so you can re-run them.

The original files are preserved as `Onboarding Platform.dc.html.orig` and
`Onboarding Platform.html.orig`. Everything marked **Fixed** is applied in the live files.

---

## Summary

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | 7 of 24 colour pairs fail WCAG AA; the worst is the most-used colour in the UI | High | **Fixed** |
| 2 | No focus indicator anywhere — the whole UI is keyboard-invisible | High | **Fixed** |
| 3 | Icon-only controls have no accessible name | High | **Fixed** |
| 4 | 50 colour literals with unintended near-duplicates, no token layer | Medium | **Fixed** |
| 5 | Unicode glyphs standing in for an icon set | Medium | **Fixed** |
| 6 | No logo — a letter in a rounded square | Medium | **Fixed** |
| 7 | Animation ignores `prefers-reduced-motion` | Medium | **Fixed** |
| 8 | Handoff doc omits 8 colours the prototype actually uses | Low | **Fixed** |
| 9 | Progress percentages carry no accessible role or value | Medium | Recommended |
| 10 | Status is encoded by colour + a coloured dot, with no text fallback in 3 places | Medium | Recommended |
| 11 | Designed for ≥1440px only; no breakpoint below the rail + content minimum | Medium | Recommended |
| 12 | Live-region announcements missing on the auto-rotating feed | Low | Recommended |

---

## 1. Contrast — 7 of 24 pairs fail WCAG AA

`PRD.md` §16 requires the platform be "Accessible (WCAG compliant)". It is not, and the
single most-used colour in the interface is the worst offender.

Measured with `contrast.py` (WCAG 2.1 relative luminance, thresholds by rendered size):

| Pair | Colour | On | Size | Ratio | Needs | Result |
|------|--------|----|------|-------|-------|--------|
| `text-faint`, table header labels | `#9a968f` | `#faf9f7` | 9.5px | **2.80** | 4.5 | fail |
| `text-faint`, mono uppercase labels | `#9a968f` | `#fff` | 10px | **2.94** | 4.5 | fail |
| dropzone glyph | `#b5b0a7` | `#fff` | 20px | **2.16** | 4.5 | fail |
| completed task title (struck) | `#b0aba3` | `#fff` | 12.5px | **2.28** | 4.5 | fail |
| `text-muted`, header meta | `#75726c` | `#f7f6f3` | 11px | **4.44** | 4.5 | fail |
| neutral pill (Pending / Waiting) | `#75726c` | `#f2f0ec` | 10px | **4.21** | 4.5 | fail |
| rail "VIEWING AS" label | 45% ink | `#17181c` | 9.5px | **4.09** | 4.5 | fail |

`#9a968f` appears **49 times** — it carries every mono uppercase label, every timestamp, and
every table column header. At 2.80:1 it is the least readable text in the product and also the
most frequent.

**The real constraint, and it is structural.** Quiet text in this design sits on four different
grounds: `#fff` cards, `#faf9f7` hover and table-header rows, the `#f7f6f3` page, and `#f2f0ec`
neutral pills. A value has to clear 4.5:1 on the *darkest* of those — `#f2f0ec` — which is the
binding case, not white.

Solving for that floor (holding hue and chroma in OKLCh, walking lightness down only as far as
needed) puts `text-faint` at `#716d67` and `text-muted` at `#706d67` — **the same colour to
within one unit.** That is the finding: this palette has no room for three distinct quiet-grey
text tiers that all pass AA. Any fix claiming otherwise is either failing the darkest ground or
inventing a distinction the eye cannot use.

So the fix keeps two quiet tiers, spaced as far apart as the constraint allows, and gives up the
third:

| Role | Was | Now | `#fff` | `#faf9f7` | `#f7f6f3` | `#f2f0ec` |
|------|-----|-----|--------|-----------|-----------|-----------|
| `text-primary` | `#191a1e` | unchanged | 17.39 | 16.52 | 16.09 | 15.28 |
| `text-secondary` | `#4d4a46` | unchanged | 8.81 | 8.37 | 8.15 | 7.74 |
| `text-muted` | `#75726c` | `#625f5a` | 6.36 | 6.04 | 5.88 | 5.58 |
| `text-faint` | `#9a968f` | `#716d67` | 5.14 | 4.89 | 4.76 | **4.52** |
| `text-disabled` | `#b0aba3` | `#716d67` | 5.14 | 4.89 | 4.76 | 4.52 |
| graphics-only tier | `#b5b0a7` | `#8f8a82` | 3.43 | 3.26 | 3.17 | **3.01** |

Every cell clears its threshold, with `#f2f0ec` the tight one at 4.52 (text) and 3.01 (graphics).

Three consequences worth accepting knowingly:

- **`text-disabled` is now the same value as `text-faint`.** No lighter grey clears AA on this
  palette, so the completed-task state is carried by the strike-through rather than by
  lightness. A deliberate collapse, recorded in the token file. The struck title is content, not
  a disabled control, so the WCAG 1.4.3 exemption for disabled UI does not cover it.
- **`paper-600` (`#8f8a82`) is a graphics-only tier** — valid for the 20px+ dropzone glyph and
  decorative marks at 3:1, explicitly not valid for text. It is labelled that way in
  `tokens.json` so nobody reaches for it as a fifth text grey.
- **Mono uppercase labels are more prominent than before.** The right trade: they were the least
  readable text in the product. Hierarchy still comes from size, weight, case and
  letter-spacing, which were always doing most of the work.

The rail label is fixed by opacity, not colour — darkening a value on a dark ground moves the
wrong way. Raising it from `0.45` to `0.55` gives 5.48:1.

## 2. No focus indicator

Not one of the prototype's interactive elements defines a focus style, and several set
`border: none`, which suppresses the UA default on some engines. Every screen is operable by
keyboard — rail navigation, filter chips, tabs, milestone expansion, task checkboxes,
stage reorder, the device toggle — and none of it shows where you are. This fails WCAG 2.4.7
outright and makes the product unusable without a mouse.

**Fixed** with a single rule, applied in both prototype files:

```css
:where(button, a, input, [tabindex]):focus-visible {
  outline: 2px solid oklch(0.52 0.16 274);
  outline-offset: 2px;
  border-radius: 5px;
}
```

`:where()` keeps specificity at zero so any component can override it, and `:focus-visible`
means mouse users never see it.

## 3. Icon-only controls had no accessible name

The workflow stage controls (`▲ ▼ ✕`), the notification bell, and the task checkboxes were
glyph-only buttons. A screen reader announced them as "button" with a single symbol, or as
nothing at all once the glyph became an SVG.

**Fixed.** Every icon-only control now carries `aria-label` ("Move stage up", "Move stage
down", "Delete stage", "Toggle task") and every decorative icon carries `aria-hidden="true"`
so it is not announced twice.

Still open: the checkbox is a `<button>`, not an `<input type="checkbox">`, so it has no
checked state to announce. Production should use a real checkbox or add
`role="checkbox"` + `aria-checked`.

## 4. 50 colour literals, no token layer

Counted directly from the source: **27 distinct hex values and 23 distinct `oklch()` values**,
appearing 869 times, all inline. Beyond the maintenance cost, the count itself is the finding —
several values are unintentional near-duplicates that no one could have chosen deliberately:

| Literal | Uses | Almost certainly meant to be | Distance |
|---------|------|------------------------------|----------|
| `#efece6` | 1 | `#efece7` (panel divider) | 1/255 on one channel |
| `#ece9e3` | 1 | `#eae7e1` | ~2 |
| `#ddd9d2` | 2 | `#dbd7cf` | ~2 |
| `#d9d5ce` | 1 | `#d6d1c9` | ~3 |
| `#c9c4bb` | 1 | `#ccc7bf` | ~3 |
| `#8a867f` | 1 | `#9a968f` | ~16, and the only use |
| `oklch(0.94 0.03 274)` | 1 | `oklch(0.93 0.03 274)` | 0.01 L |
| `oklch(0.93 0.04 70)` | 1 | `oklch(0.9 0.05 70)` | one of three amber borders |
| `oklch(0.93 0.03 70)` | 1 | `oklch(0.9 0.05 70)` | ditto |
| `oklch(0.48 0.14 70)` | 1 | `oklch(0.46 0.14 70)` | 0.02 L |
| `oklch(0.5 0.12 155)` | 1 | `oklch(0.45 0.12 155)` | 0.05 L |

Six different border greys live inside a 6% lightness range. Three different amber borders
exist for one amber panel.

**Fixed.** All twelve fold onto their intended token; the collapse is recorded in
`design/02-tokens/tokens.json` under `$migration.collapse` so the change is auditable rather
than silent. The surviving values are formalised as a three-layer token system in
`design/02-tokens/`.

## 5. Unicode glyphs as an icon set — 6. no logo — 7. no reduced-motion

Grouped because all three were explicitly acknowledged gaps rather than mistakes. The handoff
README says the glyphs "were chosen to avoid shipping an icon dependency into a prototype, not
as a design decision," and the rail logo was a letter `O` in a rounded square.

- **Icons — fixed.** A 56-icon set replaces all 18 placeholder glyphs. See
  `design/03-icons/`. Unicode glyphs render differently per platform and per installed font;
  `⎙` and `◔` in particular fall back to tofu on several Windows font stacks.
- **Logo — fixed.** See `design/01-brand/`.
- **Reduced motion — fixed.** The prototype animates progress-bar widths (0.4s), panel entry
  (`fadeUp`), chevron rotation, and an infinite `pulseDot`. All are decorative, and the
  infinite pulse is exactly what WCAG 2.3.3 and vestibular-disorder guidance ask you to stop.
  A `prefers-reduced-motion: reduce` block now collapses all durations.

## 8. The handoff doc under-documents the prototype

`README.md` §Design Tokens lists the colour palette, but the prototype also uses `#2b2c31`
(7 times — the active rail item), `#34363c` (2), `#ddd9d2`, `#ece9e3`, `#8a867f`, `#c9c4bb`,
`#d9d5ce` and `#b5b0a7`, none of which appear in the doc. An implementer working from the doc
alone would have to invent the active-rail-item colour.

**Fixed** — the rail scale is now a documented primitive ramp (`slate-700/800/900/950`).

---

## Recommended, not applied

These are design changes rather than defects, so they are proposed rather than pushed into the
prototype unilaterally.

### 9. Progress has no accessible value

Progress appears as a bare `<div>` whose width is a percentage — dashboard pipeline bars,
milestone completion, the 34px portal percentage, team workload, report bars. A screen reader
gets nothing. The visible number next to it is separate text, so the two can disagree.

Recommend `role="progressbar"` with `aria-valuenow` / `aria-valuemin` / `aria-valuemax` and
`aria-label`, with the visible percentage as the single source for both.

### 10. Status relies on colour in three places

The design rule "colour always means status, never decoration" is good and consistently
followed. But three places encode status *only* in colour:

1. **Needs-attention rows** — a coloured dot plus an age in red or amber. Nothing says "blocked".
2. **Team workload bars** — Compliance red, Legal amber, rest indigo, with no legend.
3. **Pipeline bottleneck** — the Verification bar is amber; nothing states it is the bottleneck.

Each fails WCAG 1.4.1 (Use of Colour). The health pills elsewhere already do this correctly —
they pair the colour with the word. Recommend extending the same pattern, or adding
`aria-label` / visually-hidden text where the layout cannot take another word.

### 11. No layout below 1440px

The README states the design targets ≥1440px desktop. At 1024px the customer table's
six-column grid (`2.1fr 1.2fr 1.5fr 1fr 1fr 0.7fr` with 20px padding) puts owner, due date and
health into roughly 110px each, and the journey workspace's five fact columns collapse. The PRD
asks for "Mobile-friendly" (§16) and lists mobile apps only as future work (§17), so desktop
web is expected to degrade gracefully.

Recommend two breakpoints: at ≤1280px drop the rail to a 56px icon-only rail (the icon set now
supports this — every nav item has a distinct, legible 16px glyph); at ≤1024px collapse the
customer table to a two-line card list, keeping name, stage, progress and health.

### 12. The rotating feed is not announced

The live feed swaps an entry every 3.2s with a pulsing dot marking it live. Sighted users see
change; screen reader users get nothing, and when it becomes a real subscription the same code
will silently drop real events. Recommend `aria-live="polite"` on the feed container with
`aria-atomic="false"`, and honouring the existing `liveFeed` prop as a user-facing preference
rather than only a demo switch.

---

## What the design gets right

Worth recording, because these are the decisions an implementer is most likely to erode:

- **Exception-first dashboard.** "Needs attention" is a list of five specific blocked customers
  with reasons and ages, not a count. This is the correct instinct and rare.
- **One journey, two audiences.** The same nine stages render internally and in the portal, so
  a support call has one shared picture. Do not let the portal diverge into its own model.
- **Branch rules as readable sentences.** `IF Deal value > €500k → Executive Approval` with
  `else continue`. A node-graph editor would be worse for someone who edits an SLA twice a year.
- **Mono for machine-generated values.** IBM Plex Mono on IDs, dates, metrics and counts;
  Archivo on everything a human wrote. This is a real semantic distinction doing real work, and
  it is applied consistently across all nine screens.
- **The case, not the customer, is the unit of work.** The case switcher chips make concurrent
  onboardings switchable rather than merged — which the PRD never asked for and which is right.
- **Flat cards.** Elevation is reserved for things that actually float (open milestone, popover,
  device frame). Nothing is shadowed for decoration.
