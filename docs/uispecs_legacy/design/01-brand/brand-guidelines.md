# Onboard OS — Brand Guidelines

The product name in the prototype is **Onboard OS**, shown in the rail above the tenant slug
(`acme-industrial`). These guidelines cover the mark, the voice, and the rules that keep the two
consistent. Visual reference sheet: [`logo/logo-sheet.html`](logo/logo-sheet.html).

---

## 1. Positioning

Onboard OS is an **onboarding workspace**, not a CRM. `PRD.md` §3 is explicit about this and the
distinction should survive into every piece of copy: a CRM tracks *relationships and revenue*;
this tracks *work owed, by whom, by when*. When a headline could equally describe Salesforce,
rewrite it.

| | |
|---|---|
| **Category** | Enterprise onboarding workflow orchestration |
| **Primary users** | Internal operators across 12 roles — account managers, legal, compliance, technical, finance |
| **Secondary users** | Customer contacts, individually authenticated, seeing only their own scoped view |
| **Job it does** | Makes every stalled onboarding visible, with an owner and an age, before the customer has to ask |
| **What it replaces** | Spreadsheets, email chains, and "who has the NDA?" |
| **Peer set** | Linear, Stripe Dashboard, GitHub — dense, fast, keyboard-first, unembellished |

---

## 2. The mark

A **300° arc with a 60° opening at twelve o'clock and a filled node centred in the opening.**

It reads three ways at once, and all three are true of the product:

- the letter **O**, for Onboard;
- a **progress ring** — the figure the product already draws in every KPI, bar and percentage;
- a **journey whose last stage is still open**, with the node as the milestone not yet closed.

That third reading is why the opening stays. A closed ring would be a finished onboarding, which
is not what a workspace for in-flight work should say about itself.

### Construction

| | |
|---|---|
| Grid | 32 × 32 |
| Ring radius | 10 (62.5% of the grid) |
| Stroke | 3 — matched to Archivo 600 stem weight at 20px, so the mark and wordmark share optical weight |
| Opening | 60°, centred on twelve o'clock |
| Node | r 1.5 at (16, 6) — diameter equals the stroke, so it reads as the arc's head, not an added dot |
| Caps | round, both ends |
| Clear space | 6 grid units on all sides (= node diameter × 2) |

All geometry is derived in `build_logo.py` rather than hand-typed, so every variant stays in
register. Regenerate rather than editing an SVG by hand.

### Files

| File | Use |
|------|-----|
| `logo/logo-horizontal.svg` | Primary lockup, light backgrounds |
| `logo/logo-horizontal-on-dark.svg` | The dark rail, dark headers |
| `logo/logo-stacked.svg` | Square-ish spaces — login, splash, share cards |
| `logo/logo-tile.svg` | App icon, rail mark, favicon at ≥20px |
| `logo/logo-mark.svg` | Inside the UI — no tile, inherits `currentColor` |
| `logo/logo-mono-black.svg` / `logo-mono-white.svg` | One-colour reproduction: stamping, engraving, fax |
| `logo/favicon.svg` | Below 20px — tighter ring, heavier stroke |

### Minimum sizes

| Context | Size | Asset |
|---------|------|-------|
| Favicon | 16px | `favicon.svg` — the standard tile closes up below 20px |
| Rail | 26px | `logo-tile.svg` |
| Horizontal lockup | 120px wide minimum | below that, switch to the tile |

### Rules

**Do**
- Place the tile on indigo, ink, or paper.
- Keep 6 grid units of clear space; nothing enters it, including the tenant slug.
- Use `logo-mark.svg` inside the interface so it inherits text colour.
- Keep the opening at twelve o'clock — it is the recognisable feature.

**Don't**
- Rotate the mark, close the opening, or move the node out of the gap.
- Re-weight the stroke, add a gradient or a shadow, or outline the wordmark.
- Set the wordmark in any face other than Archivo 600.
- Put the indigo tile on a saturated background, or the mono-white lockup on paper.
- Stretch either axis. Scale only.

### Before sending the logo outside the team

The wordmark in these files is live `<text>` set in Archivo 600, **not outlines.** It renders
correctly anywhere Archivo is available — the product, the reference sheets, any browser with the
webfont — and falls back to Helvetica in tools without the font installed. Convert to outlines
before external distribution:

```bash
pip install fonttools brotli
# then trace the wordmark against Archivo-SemiBold with fontTools.pens.svgPathPen
```

or open each lockup in Illustrator/Inkscape and apply Type → Create Outlines. The mark itself is
pure geometry and needs nothing.

---

## 3. Colour

The full system is in [`../02-tokens/`](../02-tokens/). Two brand-level rules govern it:

**Indigo is for action and for "now".** `oklch(0.52 0.16 274)` (`#4f5cc3`) marks the primary
action, the active nav item, the current stage, and the in-flight state. It is never decoration.
A screen with indigo in six places has diluted it.

**Every other colour is a status claim.** Green means done or healthy, amber means at risk or
expiring, red means blocked or overdue, warm grey means pending or waiting. Colour is never
chosen for variety — if a swatch does not assert something about state, it should be a neutral.

The warm-paper neutrals (`#f7f6f3` page, `#fff` surfaces, `#e6e3dd` borders) are the whole
palette outside of status. This warmth is a deliberate departure from the cool greys of the peer
set, and it is what makes the product recognisable at a glance.

---

## 4. Typography

Two faces, and the split carries meaning rather than decoration:

| Face | Carries | Why |
|------|---------|-----|
| **Archivo** 400/500/600 | Everything a person wrote or reads as prose — labels, titles, body, buttons | Grotesque, tight apertures, holds up at 11px |
| **IBM Plex Mono** 400/500 | Everything a machine produced — case IDs, dates, counts, percentages, metrics, uppercase micro-labels | Tabular figures align in columns; mono signals "this is data, not opinion" |

Apply the split consistently. `ONB-2026-0341` is mono. "Northwind Logistics GmbH" is Archivo.
`31d` is mono; "Avg. time to live" is mono because it is a metric label; "9 started this week" is
Archivo because it is a sentence.

Tracking: `-0.03em` on numerals ≥29px, `-0.02em` on titles, `+0.08–0.09em` on mono uppercase
labels. Never track Archivo positively at body size.

---

## 5. Voice

**Specific, quantified, and about the work — never about the software.**

The prototype's own copy is the reference, and it is good. "Legal returned MSA — no owner action
4 days" is the house style: what happened, what it blocks, how long it has been true. Compare a
generic alternative — "Action required on this account" — which says nothing and could be about
anything.

| Principle | Do | Don't |
|-----------|----|-------|
| Name the blocker | "UBO declaration overdue from customer" | "Pending items" |
| Attach an owner | "no owner action 4 days" | "Needs attention" |
| Quantify | "6 of 8 required documents received" | "Documents partially received" |
| State the consequence | "Cannot start until verification clears" | "Blocked" |
| Say who acts next | "Waiting only on the UBO form" | "In progress" |
| Use the customer's words in the portal | "You're in Verification" | "Case status: VERIFICATION_PENDING" |

**Internal vs portal register.** Internal copy is terse and assumes context — "SLA v2 sent for
signature". Portal copy is plain and assumes none — "Action needed from you". The portal never
exposes internal notes, role names, or SLA language; `PRD.md` §12 requires it, and it is also
simply kinder.

**Never** use exclamation marks, "Oops", "Awesome", or anthropomorphised software ("I've found
3 documents"). Nobody managing 48 enterprise onboardings wants their tooling to be cheerful at
them.

**Numbers are always concrete.** "31d" not "about a month". "17 overdue" not "several".
Deltas carry sign and unit: `+6`, `−4d`, `−1%`.

---

## 6. Applying the brand to new surfaces

When you add a screen the prototype doesn't cover, these are the tests:

1. **Does it sort by exception?** The dashboard leads with what is stuck, not with totals.
2. **Is every status word paired with its colour?** Colour alone is not a label (see review §10).
3. **Is machine data in mono and human text in Archivo?**
4. **Is there exactly one primary action?** One indigo button per view.
5. **Does it hold at 13px?** The design's density is a feature; do not inflate type to fill space.
6. **Would a customer contact be able to see it safely?** If not, it must never reach the portal.
