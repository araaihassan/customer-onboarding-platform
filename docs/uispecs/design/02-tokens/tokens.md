# Onboard OS — Design Tokens

Formalises the palette, type scale, spacing and geometry that the handoff `README.md` described in
prose, and that the prototype carried as 869 inline literals.

## Files

| File | What it is |
|------|-----------|
| `tokens.css` | CSS custom properties — light theme, dark theme, reduced motion. The implementation source. |
| `tokens.json` | DTCG-format token document, for Figma/Tokens Studio/Style Dictionary. Includes the migration record. |
| `tailwind.css` | Tailwind v4 `@theme` mapping, so `bg-surface` / `text-muted` / `rounded-card` resolve to tokens. |

```css
/* order matters: tokens.css defines the variables tailwind.css points at */
@import "./tokens.css";
@import "./tailwind.css";
```

---

## The three layers

Each layer may only reference the layer above it. That single rule is what makes the system
worth having.

```
primitive    raw values, named for what they ARE        --ob-paper-400: #e6e3dd
    ↓
semantic     named for the ROLE they play               --ob-border-default: var(--ob-paper-400)
    ↓
component    named for WHERE they are used              --ob-card-radius: var(--ob-radius-card)
```

**Primitive** — 40 colours, 23 spacing steps, 12 radii, 17 type steps. A palette, no opinions.
Never referenced directly by a component. Deliberately *not* exposed as Tailwind utilities, so
nobody can write `text-paper-700` and bypass the middle layer.

**Semantic** — 40 roles. This is the layer you use, and the only layer that changes between
themes. `border-default` is a promise about meaning; `#e6e3dd` is not.

**Component** — 25 tokens for the values that are genuinely component-specific and would
otherwise be magic numbers: the 244px rail, the 60px header, the two table row densities.

**The payoff:** the dark theme in `tokens.css` redefines only semantic tokens. Not one primitive
moves, and not one component token is touched. If a theme needs to change a component token, the
semantic layer is missing a role.

---

## Colour

### Primitive ramps

`paper` 0→950 (18 steps, warm neutral), `slate` 700→950 (4 steps, the rail),
`indigo` 50→800 (7), `green` (3), `amber` (5), `red` (3).

Colour is authored in `oklch()` for the chromatic ramps, because that is how the original design
specified it and because lightness steps in OKLCh are perceptually even. Each carries its exact
sRGB hex as a comment for tools that cannot parse `oklch()` — computed in `oklch.py`, not
eyeballed. The accent is `oklch(0.52 0.16 274)` = **`#4f5cc3`**.

Neutrals stay hex: they are near-achromatic, so `oklch()` would buy nothing and cost legibility.

### Two constrained tiers

Two primitives carry accessibility constraints in their definition, and both are commented as
such in `tokens.css`:

- **`paper-700` (`#716d67`)** is the accessible floor for quiet text **on the light surfaces**:
  ≥4.5:1 on all four it can land on (`#fff`, `#faf9f7`, `#f7f6f3`, `#f2f0ec`). Nothing lighter
  passes. It is *not* an accessible value on a dark ground — 2.35:1 on `slate-700`.
- **`paper-600` (`#8f8a82`)** is a **graphics-only** tier — clears 3:1 on every surface in
  *both* themes, valid for a 20px+ glyph, a decorative mark or a 1px border, **not valid for
  text.**
- **`paper-560` (`#b4afa7`) and `paper-580` (`#a49f97`)** are the dark theme's two quiet-text
  tiers, added in Task R1. Dark quiet text is bound by its *lightest* ground, `slate-700`, and
  nothing already on the ramp sat between `paper-550` (7.18:1 there, indistinguishable from
  secondary) and `paper-600` (3.52:1, and graphics-only anyway). `paper-580` is the floor at
  4.59:1.
- **The `300` tier is the dark theme's ink tier.** `indigo-300` always existed and was the only
  status ink that passed in dark; `green-300`, `amber-300` and `red-300` joined it in Task R1 fix
  round 1 at the same `L=0.86`, with the most chroma the sRGB gamut allows per hue. A `500` is a
  mid-tone, and over a low-alpha wash of its own hue on a dark surface it measured 3.33 / 4.38 /
  2.65:1 — so the dark status pills used to fail on three of five roles.
- **`indigo-400` (`#6777d3`)** is the dark theme's `accent-weak`. A chart series is a graphic
  required to understand the content, so it owes 3:1 against `bg-surface`; `indigo-800` gave
  1.77:1.

`text-faint` and `text-disabled` resolve to the same value in **both** themes — `paper-700` in
light, `paper-580` in dark. That is not an oversight: neither palette has room for a third
distinct quiet grey that passes AA on the ground that binds it, so the completed-task state is
carried by the strike-through rather than by lightness. See
[`../05-review/ux-design-review.md`](../05-review/ux-design-review.md) §1 for the light
derivation; the dark one is measured by `scripts/contrast.py`, which now audits both themes.

### Status

Five roles, each a `bg` / `fg` pair:

| Role | Means |
|------|-------|
| `status-on-track` | Designed, On track, Signed, Completed |
| `status-progress` | In Progress, active stage |
| `status-at-risk` | At risk, expiring within 30 days, over SLA |
| `status-blocked` | Blocked, overdue, cancelled |
| `status-neutral` | Pending, Waiting, Draft, low priority |

Plus three solid fills for shapes rather than text — `solid-on-track`, `solid-at-risk`,
`solid-blocked` — used by the completed-milestone circle, the bottleneck bar, and the
notification badge.

**The governing rule, inherited from the original design: colour always means status, never
decoration.** A token exists for a *state*, so if you want a colour and cannot name the state it
represents, use a neutral.

### Dark theme

`[data-theme="dark"]` inverts the surface and text roles onto the `slate` ramp. Two details worth
knowing:

- Status fills become low-alpha washes via `color-mix(in oklab, …)` rather than fixed tints — a
  solid pastel tint on a dark surface reads as a bright blob. Inks move to the bright end of the
  ramp so they still clear 4.5:1 on the wash.
- The rail does not move. It is already dark in the light theme, so it is the one surface that is
  identical in both, which keeps navigation stable when the theme flips. That pin is what forced
  `bg-page` down onto the new `slate-975` rather than lightening the rail: both resolved to
  `slate-950`, so the rail dissolved into the canvas at 1.00:1. Honest ceiling — even a pure
  black page is only 1.17:1 against `slate-950`, so this edge is 1.10:1 and can never be strong.
  A rail that needs a hard edge needs a border on the component, which is not a token decision.
- `border-default`, `border-strong` and `border-dashed` resolve to `paper-600` in dark, not
  `slate-700`. `slate-700` gave 1.30:1 against `bg-surface`, below 1.4.11's 3:1 for anything
  required to identify a control, and all three already shared one value there — so lifting only
  `border-default` would have made "strong" weaker than "default". The row dividers
  (`border-subtle`, `border-panel`) stay on `slate-800`: a row rule identifies nothing.
  **Known asymmetry:** the *light* `border-default` (`paper-400` on `paper-0`) is 1.28:1, the
  same design decision, and was never flagged. The complete fix is a separate `border-control`
  token applied in both themes; that is a design change beyond this pass and is not done.

- `text-on-accent` **inverts**: `paper-0` in light, `paper-950` in dark. The dark accent is a pale
  `indigo-300` fill, so white on it measured 1.53:1 — the standard dark-theme pattern is a bright
  fill with a dark label. `text-on-solid` was split out from it at the same time: both are white
  in light, so one token served both, but the *solid* status fills do not invert, and riding on
  `text-on-accent` would have put near-black on `solid-blocked` at 3.43:1.
- `accent-tint-border` is `paper-600` in dark, not `slate-700`, which was 1.15:1 on the tint.

### Measuring it

`scripts/contrast.py` carries two tables and they mean different things.

| Table | What it measures | How to read a failure |
|-------|------------------|-----------------------|
| `PAIRS` | 24 literals from the **prototype as handed off** | Historical. Evidence for finding 1; meant to stay red. |
| `SHIPPED_PAIRS` | Token **names**, resolved through `build_tokens.py` | Live. Fix it. |

`SHIPPED_PAIRS` resolves names rather than repeating hex values, so the audit cannot drift from the
generator it audits. Run for **dark** by default: 49 pairs covering body text on all four grounds,
the rail (including its `.72` and `.5` opacities), the accent roles, all five status pills on both
the card and the hovered row, the solids, and the borders. Translucent dark status fills are
composited onto the surface beneath them first, the way a browser — and axe — does.

**The shipped *light* tokens are measured by nothing**, and the cost of that is larger than the two
failures this paragraph used to name. `report_shipped("light")` was run at the close of
sub-project 1 and reports **9 of 49 pairs failing**, not one:

```
accent-tint-border on tint  1.06    border-default on page     1.19
accent-weak on surface      1.53    border-default on surface  1.28
solid-at-risk on surface    2.54    border-default on subtle   1.22
border-strong on surface    1.44    border-default on inset    1.15
border-dashed on surface    1.68                        (all need 3.0:1)
```

**No text pair fails.** All nine are non-text graphics under 1.4.11 — which is exactly why nothing
caught them: axe's default rule set evaluates `color-contrast` for *text* and carries no non-text
contrast rule, so the frontend's clean axe run in both themes measured a different property from
the one failing here. And light is the **default** theme (`ThemeProvider` uses
`defaultTheme="system"`), so this is the default rendering, not an alternate one.

The fix is the one applied to dark in §1b: move the tokens in `build_tokens.py`, regenerate, copy
`tokens.css` and `tailwind.css` across to `frontend/src/app/`, and enable `report_shipped("light")`
in the same change. `paper-600` clears 3:1 on every light ground, so the palette does not block it.
Note `contrast.py`'s `__main__` banner still says "the known, deferred `border-default` at 1.28:1";
that wording predates the run above.

`PRD.md` §16 asks for light/dark theming; the prototype only ever showed light plus a rail
variant. **Task R1 was the dark theme's first review, and it was measured rather than eyeballed.**
The first pass fixed 13 neutral failures but its table was *all* neutral, so it reported "0 of 17
fail" while the dark primary button sat at 1.53:1; fix round 1 widened the table to the accent and
status roles, found 10 more failures, and fixed them. All 49 hold now — which is a claim about
those 49 pairs and nothing else. **Add a pair whenever you add a role.** The dark theme has still
never been reviewed *visually* at screen level, so treat composition and weight as unproven even
though the contrast is not.

---

## Typography

Two families (`font-family-ui` Archivo, `font-family-data` IBM Plex Mono) and 17 type steps, each
bundling size, weight, line-height and tracking:

```css
font: var(--ob-type-13-weight) var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui);
letter-spacing: var(--ob-type-13-tracking);
```

The scale is irregular — 34, 30, 29, 23, 19, 17, 15, 14, 13.5, 13, 12.5, 12, 11.5, 11, 10.5, 10,
9.5 — and includes half-pixel steps. This is faithful to the original design, and it is a real
scale rather than a ratio: at these densities the difference between 12.5px and 13px is doing
visible work in a table row. Do not "clean it up" to a geometric ramp; you will flatten the
hierarchy the design depends on.

Names are the size (`type-13`), not a semantic label (`type-body`). A single size serves several
roles here, and pretending otherwise would need aliases that all point to the same value.

---

## Spacing, radius, elevation, motion

**Spacing** — 2 through 56, enumerated rather than generated, because the design genuinely uses
3, 7, 9, 11 and 13. Card padding 16–22, table cells 13 vertical (8 compact) / 20 horizontal, grid
gaps 12–16.

**Radius** — 12 named steps from `bar` (4px) to `pill` (20px) and `full`. Named by role, since the
mapping is stable in this design: cards are always 13, controls always 9, chips always 8.

**Elevation** — five steps, four of which are almost never used. Cards are `flat`. Elevation is
reserved for things that actually float: `raised` (open milestone), `popover` (notification
panel), `device` (mobile frame), `ring-accent` (selected workflow stage). Keeping cards flat is a
deliberate decision in the original design; do not add shadows to make things "pop".

**Motion** — four durations and one easing. `ease`, no custom curves. Only four things move:
progress bar width (0.4s), panel entry (`fadeUp` 0.16s), chevron rotation (0.18s), live-feed
pulse (2s, infinite). `tokens.css` collapses all of them under
`prefers-reduced-motion: reduce` — the infinite pulse in particular is what that media query
exists for.

---

## Migration record

`tokens.json` → `$migration.collapse` lists 15 literals from the prototype and the token each
folds into, with the reason. Twelve were unintended near-duplicates (`#efece6` vs `#efece7`; six
border greys inside a 6% lightness range; three different amber borders). Three were contrast
fixes. The record exists so the change is auditable rather than silent — anyone who wonders where
`#8a867f` went can find out.

---

## Working with the system

**Adding a colour.** Ask what state it represents. If you can't say, you want an existing
neutral. If you can, add a *semantic* role and point it at an existing primitive. Adding a
primitive is a palette change and should be rare.

**Adding a component token.** Only if the value is genuinely local to one component and is not
already a spacing or radius step. `--ob-rail-width: 244px` qualifies. `--ob-card-gap: 16px` does
not — that is `space-16`.

**Checking contrast.** `contrast.py` in the build scripts audits pairs; `fix_contrast.py` solves
for the minimal darkening that reaches a target while holding hue and chroma. Run them when you
add any text colour.

**Regenerating.** `python build_tokens.py`. All three output files are generated from one source
of truth; do not hand-edit them.
