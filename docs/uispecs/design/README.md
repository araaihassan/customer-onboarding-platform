# Onboard OS — Design System

Complete UI/UX design output for the Enterprise Customer Journey & Onboarding Platform: brand and
logo, design tokens, a 56-icon set, component specifications, and an accessibility review — plus
the two prototype files updated to use all of it.

Start here: **[`preview.html`](preview.html)** — open it in a browser for a visual index of
everything below.

---

## What was delivered

| # | Folder | Contents |
|---|--------|----------|
| 01 | [`01-brand/`](01-brand/) | Logo suite (8 SVG variants), construction spec, brand voice, usage rules |
| 02 | [`02-tokens/`](02-tokens/) | Three-layer tokens — `tokens.css`, `tokens.json`, `tailwind.css`, light + dark |
| 03 | [`03-icons/`](03-icons/) | 56 single-path SVG icons, sprite, JSON registry, gallery |
| 04 | [`04-components/`](04-components/) | Specifications for all 17 component families |
| 05 | [`05-review/`](05-review/) | UX & accessibility review — 12 findings, 8 fixed |
| — | [`scripts/`](scripts/) | The generators. Every asset here is generated, not hand-authored |

### Reference sheets

| Sheet | Shows |
|-------|-------|
| [`preview.html`](preview.html) | Index of the whole system |
| [`01-brand/logo/logo-sheet.html`](01-brand/logo/logo-sheet.html) | Logo construction, variants, minimum sizes, rules |
| [`03-icons/icon-sheet.html`](03-icons/icon-sheet.html) | All 56 icons, optical sizing, status pairing, glyph mapping |

---

## The prototype files were updated

Both files in the project root now use the new logo, the icon set, the token variables and the
accessibility fixes:

- `Onboarding Platform.dc.html` — the editable source
- `Onboarding Platform.html` — the standalone bundle

**46 edits** were applied to each, and the two are verified byte-identical in both the markup
region and the logic script after normalising for the bundler's own serialisation. Open either in
a browser; the bundle needs no server.

Originals are preserved untouched as `Onboarding Platform.dc.html.orig` and
`Onboarding Platform.html.orig`. To revert, delete the `.orig` suffix. To re-apply,
`python design/scripts/patch_prototype.py` — it restores from `.orig` first, so it is safe to run
repeatedly.

### What changed in them

| Change | Detail |
|--------|--------|
| Logo | The `O`-in-a-square placeholder became the real 26px mark |
| Icons | All 18 Unicode placeholder glyphs became inline SVG |
| Icon registry | An `ICON` object in the logic class holds path data; nav, feed, milestone, checkbox and mobile-tab icons are data-driven via `{{ item.iconPath }}` |
| Tokens | A `:root` block declares the core semantic tokens |
| Contrast | 6 colour values darkened to clear WCAG AA (86 occurrences) |
| Consistency | 9 near-duplicate literals folded onto their token |
| Focus | A global `:focus-visible` outline — there was none before |
| Reduced motion | `prefers-reduced-motion` collapses all four animations |
| Labels | `aria-label` on every icon-only control, `aria-hidden` on decorative icons |

The two remaining `→` characters are typographic arrows inside sentences, not icons, and are
correctly left as text.

### How the icons work in the prototype

Worth knowing before you edit the files. The `x-dc` runtime (`support.js`) turns out to support
real inline SVG:

- `React.createElement` receives arbitrary tags, so `<svg>`/`<path>`/`<circle>` render in the
  correct namespace;
- `encodeCamelAttrs` (support.js ~line 365) detects camelCase attributes in the raw source
  *before* HTML parsing lowercases them, so `viewBox` survives — verified in the DOM, 25/25 SVGs
  with correct `viewBox`, zero console warnings;
- `sc-if` uses plain truthiness, so an empty path string renders nothing — which is how the
  milestone status circle and the task checkbox switch between icon and no icon.

Because every icon is a single path, path data can live in the data layer. That is why the nav
array holds `ICON.dashboard` rather than a component reference.

**One caveat if you inline icons into a `style` attribute instead:** `cssToObj` (support.js ~line
391) splits declarations naively on `;`, so a `data:` URI containing a semicolon would break
parsing. Percent-encode it, or keep using inline SVG.

---

## Regenerating

Everything is generated from source-of-truth scripts. Do not hand-edit generated files.

```bash
cd design/scripts
python build_icons.py       # -> 03-icons/  (svg/, sprite, icons.json, icon-sheet.html)
python build_logo.py        # -> 01-brand/logo/  (8 svg + logo-sheet.html)
python build_tokens.py      # -> 02-tokens/  (tokens.json, tokens.css, tailwind.css)
python patch_prototype.py   # -> both prototype files, from .orig
```

No third-party dependencies; standard library only. Paths are relative to the script, so the
folder can be moved. Re-running all four is idempotent — verified byte-identical across 73 files.

Analysis tools used for the review, kept so the findings can be re-checked:

```bash
python contrast.py       # WCAG audit of the prototype's actual colour pairs
python fix_contrast.py   # solves for minimal darkening to reach a target ratio
python oklch.py          # oklch() -> exact sRGB hex
```

### Adding an icon

Add to `ICONS` in `build_icons.py`, re-run it, check the result at 14–16px in
`icon-sheet.html`. To use it in the prototype, add it to the `picks` list in
`patch_prototype.py`'s `icon_registry()` and re-run that.

---

## Headline findings

Full detail in [`05-review/ux-design-review.md`](05-review/ux-design-review.md).

**1. `PRD.md` §16 requires WCAG compliance; the prototype did not meet it.** 7 of 24 measured
colour pairs failed AA. The worst was `#9a968f` at **2.80:1** — which is also the most-used colour
in the interface, at 49 occurrences, carrying every table header, mono label and timestamp.

The palette turned out to have a structural limit: solving for the accessible floor puts
`text-faint` and `text-muted` within one unit of each other, so there is no room for three
distinct quiet-grey text tiers that all pass. The fix keeps two, spaced as far apart as the
constraint allows, and documents the collapse rather than hiding it.

**2. No focus indicator existed anywhere.** Every screen is keyboard-operable and none of it
showed where you were — a straight WCAG 2.4.7 failure. Fixed globally.

**3. 50 distinct colour literals, 869 usages, all inline** — including 12 unintended
near-duplicates. `#efece6` and `#efece7` are one unit apart and both in use. Six border greys sit
inside a 6% lightness range. Three different amber borders exist for one amber panel. All folded,
with the migration recorded in `tokens.json`.

**4. The handoff doc under-documented the prototype.** `#2b2c31` (the active rail item, 7 uses),
`#34363c`, and six other values appear in the code but not in the token list. An implementer
working from the doc alone could not have got the rail right.

Four findings remain **recommended rather than applied**, because they are design decisions rather
than defects: accessible progress values, three places that still encode status in colour alone,
no layout below 1440px, and no live-region announcement on the rotating feed.

---

## Using this in the target codebase

The handoff README is clear that these files are references, not production code, and that the
task is to rebuild the design in the target environment. In that order:

1. **Tokens first.** Import `tokens.css`, then `tailwind.css` if you use Tailwind. Everything else
   depends on this, and doing it last means retrofitting.
2. **Icons second.** Generate components from `icons.json`, or drop in the sprite. Do not
   hand-copy paths.
3. **Then components**, in the order of [`04-components/`](04-components/). Card, progress bar,
   status pill and table cover most of the surface area; build those four and the nine screens are
   largely assembled.
4. **Read the review before the screens.** Four of its findings are cheaper to build in than to
   retrofit, particularly `role="progressbar"` and real table semantics.

### Things to preserve

The original design made seven decisions that are easy to erode and worth keeping. They are listed
in the handoff README §"Product decisions worth preserving"; the two most fragile in practice:

- **Colour always means status, never decoration.** The moment a chart gets a rainbow palette or a
  card gets a coloured header for variety, the status system stops working.
- **Mono for machine-generated values, Archivo for human text.** This is a real semantic
  distinction applied consistently across all nine screens, and it degrades quietly if new screens
  ignore it.

### Known gaps in the design itself

Not defects in the prototype, but work that does not exist yet and will be needed:

- **Empty states** — no list has one.
- **Loading states** — no skeletons; the `hint-placeholder-count` attributes record the expected
  row counts to skeleton against.
- **Error states** — no failed-upload, failed-save or permission-denied treatment.
- **Breakpoints below 1440px** — see review §11 for a proposed two-breakpoint approach; the icon
  set supports a 56px icon-only rail.
- **The dark theme is unreviewed at screen level.** The token structure is the deliverable; treat
  the individual dark values as a starting point.
- **The wordmark is live `<text>`, not outlines.** Fine in-product, must be outlined before
  external distribution — see `01-brand/brand-guidelines.md`.
