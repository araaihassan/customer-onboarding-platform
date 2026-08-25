# Frontend Visual Refactor (Sub-projects 1–2) — Design Spec

**Date:** 2026-08-26
**Sub-projects covered:** 1 (Foundation & Tenancy), 2 (Workflow Engine & Case Lifecycle) — frontend only
**Status:** Approved
**Depends on:** Sub-projects 1–2 backend and frontend, both delivered and green as of 2026-08-23.
Docs pointer update (rename `docs/uispecs/` → `docs/uispecs_legacy/`, adopt
`docs/uispecs_latest/design_handoff_onboarding_platform/`), commit `2670948`.

---

## 1. Context

`docs/uispecs_latest/design_handoff_onboarding_platform/` replaces the design system sub-projects
1–2's frontend was originally built against. It is a different design, not an update to the old
one: a flat (not three-layer) token architecture, a new warm-neutral palette, Instrument Sans /
Spline Sans Mono in place of Archivo / IBM Plex Mono, and no dark theme at all. This spec covers
restyling the two already-delivered frontends to match it, without changing what they do.

Decisions locked with the user before this spec (see conversation record, not repeated here):
drop dark-theme support entirely; restyle in place — keep current routes, page structure and
component boundaries rather than rebuilding screens to the handoff's exact markup; write this
spec and its implementation plan now, execute later in checkpointed sessions.

**Why a restyle and not a rebuild:** the current `ui/` primitives (`Button`, `Card`, `Chip`,
`StatusPill`, `ProgressBar`, `Switch`, `Tabs`, `Dialog`, `TimelineRow`, `Checkbox`, `Avatar`,
`Pagination`, `Field`, `States`) already source every visual value from CSS custom properties via
inline styles rather than hardcoded Tailwind palette classes or literal hex — e.g. `Button.tsx`
reads `background: var(--ob-accent)`, never `bg-indigo-600`. The token layer can be swapped
without rewriting component logic; only the values, and the components' own size/variant
coverage, need to change.

**Skills used while writing this spec:** `frontend-design` and `ui-ux-pro-max` (per the standing
instruction in `CLAUDE.md`). Their scope here is narrower than usual — the handoff's palette,
type, spacing and component specs are final and specified to the value (its own `README.md`:
"Fidelity: High"), so there is no aesthetic direction left to invent. What they inform is
exactly the two things the handoff explicitly leaves open — §6 (empty/loading/error states) and
§8 (sub-1440px responsive behaviour) — plus a UX/accessibility cross-check of the handoff's own
component specs, both folded in below.

---

## 2. Scope

**In scope:** `frontend/src/components/{shell,journey,workflow,admin,customers,auth,ui}/`, the
token layer (`frontend/src/app/tokens.css`, `tailwind-theme.css`, `globals.css`), and every route
currently rendering real content:

- Public: `/t/[slug]/login`, `/activate`, `/reset-password`.
- App: `/t/[slug]/customers` (list, detail, case workspace), `/t/[slug]/admin/{org,roles,users}`,
  `/t/[slug]/admin/workflows` (list, builder, versions, migration), `/t/[slug]/dashboard`.

**`dashboard/page.tsx` stays the placeholder `EmptyState` it already is** — restyled to the new
tokens, not built out. The real dashboard is sub-project 8's screen; nothing here anticipates it.

**Explicitly out of scope:** any new-bundle component or screen with no counterpart in the current
app today — `CommandPalette`, the inbox `Drawer`, the dashboard-block components (`StatCard`,
`SectionCard`, `FunnelRow`, `BarChart`, `PersonBar`), and the tenant/role/journey `Switcher`
dropdown. These get built when the sub-project that owns them is reached, against this same token
layer — building them now would be new functionality riding on a restyle task, not a restyle.

---

## 3. Token layer

The new bundle's semantic structure is not a rename of the old one — it is shaped differently.
Concretely:

| | Old (`docs/uispecs_legacy/`) | New (`docs/uispecs_latest/.../DESIGN_TOKENS.md`) |
|---|---|---|
| Layers | Primitive → semantic → component (3) | Flat — one semantic layer, no primitive ramp exposed |
| Text tiers | 5 (`primary`, `secondary`, `muted`, `faint`, `disabled`) | 6 (`ink`, `text-2`, `text-muted`, `text-subtle`, `text-faint`, `text-ghost`) |
| Status/semantic pairs | 5 (`on-track`, `progress`, `at-risk`, `blocked`, `neutral`) | 7 (`ok`, `warn`, `risk`, `info`, `accent`, `automation`, `neutral`) |
| Rail | Dedicated `--ob-bg-rail` / `--ob-bg-rail-raised` tokens | No dedicated token — the rail is literally `ink` (`#1c1b18`) |
| Themes | Light + dark, same variable names, different values | Light only |

Given that, forcing the new roles into the old `--ob-*` names would misrepresent what changed.
**Rewrite `tokens.css` with variable names that directly mirror `DESIGN_TOKENS.md`'s own names**,
keeping only the `--ob-` prefix for CSS namespace hygiene: `--ob-canvas`, `--ob-surface`,
`--ob-surface-sunken`, `--ob-surface-muted`, `--ob-surface-active`, `--ob-line`, `--ob-line-soft`,
`--ob-line-faint`, `--ob-line-strong`, `--ob-line-hover`, `--ob-ink`, `--ob-ink-hover`,
`--ob-ink-rail-active`, `--ob-text-2`, `--ob-text-muted`, `--ob-text-subtle`, `--ob-text-faint`,
`--ob-text-ghost`, `--ob-avatar-neutral`, and `--ob-{ok,warn,risk,info,accent,automation,neutral}
-{fg,bg,border}` for the semantic pairs (border only where the handoff defines one — callouts).
Anyone implementing a component can cross-reference a CSS variable against the spec table with no
translation layer in between.

Delete the dark-theme block entirely rather than leaving it dormant — an unused `[data-theme=
"dark"]` block with no toggle pointing at it is a trap for the next person who finds it and
assumes it works. `tailwind-theme.css` regenerates from the same set (`bg-canvas`, `text-ink`,
etc.). Component-tier tokens that don't exist in the new flat system (`--ob-rail-width`,
`--ob-control-height`, `--ob-radius-control`) are re-added only where a component genuinely needs
a named constant per `COMPONENTS.md` — e.g. the 56px rail width, the 52px top bar height — not
speculatively ahead of a real use.

---

## 4. Primitives (`ui/`)

Each existing primitive is restyled in place against its `COMPONENTS.md` entry and gains the
variants/sizes it's currently missing. Net-new primitives are built where sub-project 1–2 screens
need something the current `ui/` folder has no counterpart for.

| Current | `COMPONENTS.md` § | Gap to close |
|---|---|---|
| `Button.tsx` | §4 Button | 2 variants (primary/secondary) → 9 (+ small primary/secondary, danger-outline, filter active/idle, portal primary, text link), each with its own height/padding/radius per the table |
| `Card.tsx` | (implicit — `surface` + `line` + `card` shadow, used by `SectionCard`/`StatCard` bodies) | Confirm it matches `background:surface; border:1px solid line; box-shadow:card` exactly; this app has no dashboard blocks, so it stays a plain container |
| `Chip.tsx` | §3 Chip | Confirm standard/compact sizing and semantic-pair-only colouring (never bordered); audit variant strings used in this app's screens against §3's list |
| `StatusPill.tsx` | folds into §3 Chip | Likely mergeable into `Chip` once `Chip` covers every status this app shows — decide during implementation, not speculatively now |
| `ProgressBar.tsx` | §13 ProgressBar | 5 context-specific heights/tracks/fill-by-state rules, not just this app's own subset |
| `Switch.tsx` | §17 Toggle | Rename semantics to match (`on`/`off` track colours: `accent.fg` / `line-strong`), add the smaller inspector/auth variant |
| `Tabs.tsx` | §5 SegmentedControl | Confirm it already matches the track/segment spec; this is the case-workspace's five tabs |
| `Dialog.tsx` | §18 Modal | Force-complete modal's exact header/body/footer spec, including the disabled-until-valid submit rule |
| `TimelineRow.tsx` | §8 ListRow (closest analogue) | Confirm `<button>` semantics, status dot, hover `surface-sunken` |
| `Checkbox.tsx` | (requirement checkboxes) | Restyle only — logic (server round-trip before flipping, per the existing sub-project 2 defect note in `CLAUDE.md`) does not change |
| `Avatar.tsx` | (presence cluster, rail avatar) | Restyle; confirm the avatar-palette hash-cycling rule (§ Colour → Avatar palette) |
| `Pagination.tsx` | (no direct spec entry) | Restyle to token values; no structural change |
| `Field.tsx` | §18 Modal → Field styling | Label/control spec (`border:1px solid line; radius:9; padding:9px 11px`) |
| `States.tsx` | §22 Empty/loading/error | See §6 below — this is where the handoff explicitly hands off the decision |

**Net-new primitives** needed because sub-project 1–2 screens use them but nothing in `ui/` covers
them yet:

- **`DataTable`** — generalises whatever ad hoc table markup `CustomerTable` and the admin list
  pages currently use, per §12 (grid header, mono numeric/ID columns, optional footer bar, dense
  vs. comfortable column sets as a user preference — though this app has no ACV/workflow-version
  columns to toggle yet, so the density switch itself can wait for a screen that needs it).
- **`StageAccordion`** — the case roadmap, per §14 (rail circle/connector, header button, expanded
  milestone panel). This is the flagship screen's core primitive.
- **`BuilderNode`** — the workflow canvas's draggable stage node, per §21 (branch styling,
  selection ring, connector, drop behaviour). May already exist under `workflow/` in an earlier
  form (`draftState.ts`'s data model already carries what §21 needs) — align rather than duplicate.
- **`Toast`** — per §19. Check whether any ad hoc toast/notification exists today; if so, replace
  it rather than adding a second one.

---

## 5. Screens

Sweep each domain folder against the matching part of `SCREENS.md`, restyling what's already
wired to a shared primitive for free once §4 lands, and moving anything with ad hoc inline styles
or literal values onto a primitive:

- **`auth/`** — login, activate, reset-password. Smallest surface, good first screen to prove the
  new token layer end-to-end.
- **`customers/`** — list (`DataTable`), detail, contact list/edit/retire.
- **`journey/`** (case workspace) — the flagship: roadmap (`StageAccordion`), requirement
  checkboxes, approval/hold/force-complete (`Modal`), timeline (`ListRow`/`TimelineRow`), case
  header (hero — company name, health pill, fact columns, progress bar per `SCREENS.md`'s case
  header description).
- **`admin/`** — org (departments/teams), roles (`RoleEditor`), users, all table-heavy
  (`DataTable`).
- **`workflow/`** — builder canvas (`BuilderNode`), stage inspector, publish flow, migration table
  (`MigrationTable` — note its existing `candidates.length === 0` empty-state guard fix from
  2026-08-23 is a behavioural fix, not a style one; preserve it).
- **`shell/`** — `AppShell`/`Sidebar`/`TopBar`/`NavItem`, done first as part of Phase A (§7) since
  every other screen renders inside it.

---

## 6. Gaps the handoff leaves open

`README.md`'s own list — empty states, loading skeletons, error states, sub-1440px layout — plus
one addition from the `ui-ux-pro-max` accessibility cross-check:

- **Empty states**: centred, 26–40px padding, 12.5px `text-subtle`, naming the next action
  ("No exceptions right now. Nothing is past its SLA.") — never a bare "No data." `States.tsx`'s
  existing `EmptyState` already follows this shape (see `dashboard/page.tsx`'s own comment
  justifying it); carry the pattern into every other empty state this refactor touches, phrased in
  the interface's voice per `frontend-design`'s writing guidance — plain, specific, active voice,
  not an apology.
- **Loading**: skeleton blocks at `line-faint`, matching the real element's radius and height. No
  spinners inside cards — a spinner is for full-page transitions only.
- **Error states**: a `risk` callout (§ Modal's field styling family: 1px `risk.border`,
  `risk.bg`, radius 9–10, `padding:11px 12px`) with a message and a retry button. **Addition from
  the UX cross-check**: the callout's error text needs `role="alert"` (or an `aria-live` region) —
  visual-only error indication (colour/border alone) doesn't announce to assistive tech, and nothing
  in `COMPONENTS.md` §22 specifies this because the handoff is a visual reference, not an
  accessibility spec.
- **Sub-1440px**: the handoff has no opinion below 1440px. For `DataTable` specifically — the
  component most likely to overflow — wrap it in its own horizontal-scroll container
  (`overflow-x: auto`) rather than letting the page itself scroll horizontally or collapsing to a
  card layout (a card-per-row fallback would be a structural change, out of scope for a restyle).
  Everything else (forms, the case workspace, the builder) uses relative units and existing
  responsive behaviour from the current implementation; this refactor changes their skin, not
  their breakpoints, except where a fixed pixel value came from the old token system's component
  tier and needs a replacement.

---

## 7. Dark theme removal

Delete outright, not conditionally:

- `frontend/src/components/ThemeProvider.tsx`
- `frontend/src/components/shell/ThemeToggle.tsx` and `ThemeToggle.test.tsx`
- The toggle wiring inside `frontend/src/components/shell/TopBar.tsx`, and the corresponding
  assertions in `TopBar.test.tsx`
- The `ThemeProvider` wrap in `frontend/src/app/layout.tsx`
- Every dark-theme block in `tokens.css`, `tailwind-theme.css`, `globals.css`
- The `next-themes` dependency from `package.json`, once nothing else references it

---

## 8. Verification

- **Contrast**: adapt `docs/uispecs_legacy/design/scripts/contrast.py`'s WCAG math (not its data)
  into a light-only script checking the new bundle's actual token pairs — replace `SHIPPED_PAIRS`
  and `PAIRS` with the new flat token set's foreground/background combinations. This keeps the
  "don't eyeball contrast, run the script" invariant alive instead of quietly dropping it because
  the old script pointed at a superseded palette. Runs once per phase, not once at the end.
- **Style-coupled tests**: `ContactList.test.tsx`, `CustomerTable.test.tsx`, `CaseHeader.test.tsx`,
  `Sidebar.test.tsx` assert on inline styles or literal hex values today — update each as part of
  the task that touches its component, not as a separate cleanup pass.
- **Playwright accessibility spec** drops its "both themes" dimension (light-only now); the four
  widths stay.
- **Screenshot self-review**: per `frontend-design`'s process, take a screenshot of each screen
  after its restyle task and check it against the handoff's own `Onboarding Platform.dc.html`
  before calling the task done — a diff worth catching before merge, not after.
- **Keyboard/focus**: `README.md`'s non-negotiables already mandate `<button>` for interactive
  rows and a visible 2px `#1c1b18` focus ring with 2px offset — verify both hold after the
  restyle, since a naive class swap can silently drop a `:focus-visible` rule.

---

## 9. Task ordering

1. **Phase A — tokens + shell + dark-theme removal.** New `tokens.css`/`tailwind-theme.css`,
   `AppShell`/`Sidebar`/`TopBar`/`NavItem`, dark-theme deletion (§7). Everything else renders
   inside this frame, so it goes first.
2. **Phase B — primitives.** One task per component or small cluster from §4's table, including
   the four net-new ones.
3. **Phase C — screens.** One task per screen/route from §5, each restyled against its now-ready
   primitives and its `SCREENS.md` section.

Each task remains individually reviewable inside the checkpointed execution session — sequencing
by phase does not collapse them into one giant task; it only orders them so a screen task never
runs against a primitive that isn't ready yet.

---

## 10. Non-negotiables for this refactor

A change breaking one of these is a change to this design, not an implementation detail:

- No dark-theme code path remains reachable — not behind a flag, not dormant in CSS.
- No component reads a literal hex value or an old `--ob-*` (pre-refactor) variable name after its
  task lands — every colour comes from the new token set.
- `dashboard/page.tsx` remains a placeholder; nothing in this refactor gives it real content.
- Every interactive row/control that was a `<button>` before stays a `<button>`; none of this
  refactor's restyling regresses to a `<div onClick>`.
- The requirement checkbox's server-round-trip-before-flip behaviour (Task 27's "real and local"
  departure, `CLAUDE.md`) is unchanged — this is a visual pass, not a behavioural one, anywhere in
  scope.
- `MigrationTable`'s `candidates.length === 0` empty-state guard (fixed 2026-08-23) is preserved
  through the restyle, not reintroduced as `eligible.length === 0`.
