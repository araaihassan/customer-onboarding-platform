# Frontend Visual Refactor (Sub-projects 1–2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the already-delivered sub-project 1–2 frontend to the new design system at
`docs/uispecs_latest/design_handoff_onboarding_platform/`, dropping dark-theme support, without
changing routes, page structure, or component boundaries.

**Architecture:** Three phases. Phase A rewrites the token layer and the app shell (rail, sidebar,
top bar) everything else renders inside. Phase B brings every `ui/` primitive up to the new
component spec. Phase C sweeps the domain screens (journey, workflow, admin, customers, auth)
against it. Each task is independently testable; phases are ordered so a screen task never runs
against a primitive that isn't ready.

**Tech Stack:** Next.js 15 (App Router), TypeScript strict, Tailwind v4, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-26-frontend-visual-refactor-design.md`

---

## Global Constraints

- **No dark theme anywhere** — not a dormant CSS block, not a flag. `[data-theme="dark"]` is
  deleted, not redefined.
- **Every colour comes from a `--ob-*` custom property** whose name mirrors
  `DESIGN_TOKENS.md`'s own names (spec §3). No component reads a literal hex value or a
  pre-refactor `--ob-*` name after its task lands.
- **Restyle, don't rebuild.** Keep every component's existing props API, structure, and behaviour
  unless a task explicitly says otherwise. A component that deliberately does *not* use a shared
  primitive today (e.g. `MilestoneEditor`'s `MandatoryToggle`, `ContactForm`'s `PrimaryCheckbox`,
  `RoleEditor`'s own `Switch`) stays that way — its own comment already explains why, and no task
  below redirects it to "just use the primitive."
- **Icons stay put.** `frontend/src/components/icons/index.tsx` (the generated `currentColor`
  24px/1.5-stroke set) is not replaced with Lucide — see design spec §4. Its generator-comment
  source path gets a one-line update in Task 1, nothing else about it changes.
- **`Checkbox.tsx`'s server-round-trip-before-flip behaviour, `MigrationTable`'s
  `candidates.length === 0` empty-state guard, and every focus-trap/keyboard-navigation
  implementation already built (`Dialog`, `Tabs`, `TopBar`'s `AccountMenu`) are preserved exactly.
  A restyle task touches styling; it does not touch behavioural logic, unless the task says so.**
- **Run `cd frontend && npx vitest run` and `cd frontend && npm run build` after every task.**
  Both must be clean before a task's commit.

### Token rename map

Every Phase A–C task applies this map to the files it touches. It is not repeated per task; a
task's steps call out only what *isn't* a mechanical rename (new tokens, resolved ambiguities,
structural changes).

**Neutrals / surfaces:**

| Old (`docs/uispecs_legacy`) | New | Note |
|---|---|---|
| `--ob-bg-page` | `--ob-canvas` | |
| `--ob-bg-surface` | `--ob-surface` | |
| `--ob-bg-surface-subtle` | `--ob-surface-sunken` | Old had two tiers here (subtle/sunken); new has one — both collapse to it. |
| `--ob-bg-surface-sunken` | `--ob-surface-sunken` | |
| `--ob-bg-inset` | *(resolve per site)* | Progress-bar tracks → `--ob-line-faint`. Neutral chip/pill backgrounds → `--ob-neutral-bg`. Old used one token for both; new splits them. |
| `--ob-bg-inset-strong` | *(resolve per site)* | Avatar neutral fill → `--ob-avatar-neutral` (new has a dedicated token). Secondary-button/row hover → `--ob-surface-active`. |
| `--ob-bg-rail` | `--ob-ink` | The rail is now literally flat `ink` — no dedicated rail token. |
| `--ob-bg-rail-raised` | `--ob-ink-rail-active` | |
| `--ob-bg-overlay` | `--ob-surface` | No distinct overlay tier in the new system. |
| `--ob-border-default` | `--ob-line` | |
| `--ob-border-subtle` | `--ob-line-faint` | |
| `--ob-border-panel` | `--ob-line-soft` | |
| `--ob-border-strong` | `--ob-line-strong` | |
| `--ob-border-dashed` | `--ob-line-strong` | |
| `--ob-graphic-muted` | `--ob-line-strong` | The derived graphics-only tier `globals.css` added; new system has no separate graphics tier, `line-strong` is the closest 3:1-at-20px+ role. |

**Text:**

| Old | New | Note |
|---|---|---|
| `--ob-text-primary` | `--ob-ink` | |
| `--ob-text-secondary` | `--ob-text-muted` | New's `text-2` is narrower ("secondary copy inside callouts" only) — general secondary body/label text maps to `text-muted` instead. Use `--ob-text-2` only for text genuinely inside a callout. |
| `--ob-text-muted` | `--ob-text-subtle` | |
| `--ob-text-faint` | `--ob-text-faint` | Name match, same role. |
| `--ob-text-disabled` | `--ob-text-faint` | New has no separate disabled tier; old already collapsed faint===disabled. |
| `--ob-text-on-accent` | `--ob-canvas` | See "Primary button is ink, not accent" below — this token's old use case (white text on an accent-filled primary button) no longer exists; canvas-on-ink replaces it. |
| `--ob-text-on-solid` | `--ob-canvas` | |
| `--ob-text-on-rail` | *(Rail-only, see Task 4)* | Only relevant inside the new 56px icon rail now, not the Sidebar — Sidebar's `NavItem` text is `--ob-ink`, not a rail-text token. |

**Accent, status, motion:**

| Old | New | Note |
|---|---|---|
| `--ob-accent` | `--ob-accent-fg` | |
| `--ob-accent-hover` | `--ob-accent-fg-hover` | |
| `--ob-accent-tint` | `--ob-accent-bg` | |
| `--ob-accent-tint-border` | *(removed)* | New accent role has no border value — accent callouts are unbordered. |
| `--ob-accent-ink` | `--ob-accent-fg` | Same value now; new system doesn't distinguish tint-ink from fg. |
| `--ob-accent-weak` | *(removed — unused, no chart in scope)* | |
| `--ob-status-on-track-{bg,fg}` | `--ob-ok-{bg,fg}` | |
| `--ob-status-progress-{bg,fg}` | `--ob-accent-{bg,fg}` | "In progress / active" maps to the new `accent` role ("active/live"), not a status colour — see Button/StatusPill note below. |
| `--ob-status-at-risk-{bg,fg}` | `--ob-warn-{bg,fg}` | |
| `--ob-status-blocked-{bg,fg}` | `--ob-risk-{bg,fg}` | |
| `--ob-status-neutral-{bg,fg}` | `--ob-neutral-{bg,fg}` | |
| `--ob-solid-on-track` | `--ob-ok-fg` | New has no separate "solid dot" tier; the fg value already is the solid colour. |
| `--ob-solid-at-risk` | `--ob-warn-fg` | |
| `--ob-solid-blocked` | `--ob-risk-fg` | |
| `--ob-duration-instant`, `-fast`, `-progress` | `--ob-duration-pop` (.16s) | Collapse to the new system's one interaction duration; hover states are instant (no transition) per `DESIGN_TOKENS.md`'s Motion table — do not add a transition where the new spec says none. |
| `--ob-duration-pulse` | `--ob-duration-pulse` (2.4s, was 2s) | |

**New roles with no old equivalent** (available from Task 1 onward, used where a task calls for
them): `--ob-info-{fg,bg,border}`, `--ob-automation-{fg,bg,border}`, `--ob-surface-muted`,
`--ob-text-ghost`, `--ob-line-hover`.

**Primary button is `ink`, not `accent`.** `DESIGN_TOKENS.md`: "Primary actions are near-black,
not branded colour — colour is reserved for meaning." `COMPONENTS.md` §4 confirms: Primary button
background is `ink`, text `canvas`, hover `ink-hover`. The old system's primary button was
`--ob-accent` (indigo). This is a real visual change, not a rename — Task 10 (Button) implements
it explicitly; every other task that composes a primary button inherits it for free once `Button`
is restyled, since none of the surveyed domain components hardcode primary-button colour
themselves.

---

## Phase A — Tokens, fonts, shell, dark-theme removal

### Task 1: Rewrite the token layer

**Files:**
- Modify: `frontend/src/app/tokens.css` (full rewrite)
- Modify: `frontend/src/app/tailwind-theme.css` (full rewrite)
- Modify: `frontend/src/components/icons/index.tsx:1-2` (generator comment path only)

**Interfaces:**
- Produces: every `--ob-*` custom property named in the rename map above, plus layout tokens
  `--ob-rail-width` (56px), `--ob-sidebar-width` (250px), `--ob-topbar-height` (52px), consumed by
  Tasks 4–8. Plus type-role tokens `--ob-type-{role}-size/-line/-tracking` consumed by every later
  task that sets typography.

- [ ] **Step 1: Replace `tokens.css` in full**

```css
/* Onboard OS design tokens — flat semantic layer, mirroring
 * docs/uispecs_latest/design_handoff_onboarding_platform/DESIGN_TOKENS.md by name.
 * Light theme only. See CLAUDE.md's UI/UX section for why there is no dark theme.
 */

:root {
  /* ------------------------------------------------------------- neutrals */
  --ob-canvas: #fbfaf8;
  --ob-surface: #ffffff;
  --ob-surface-sunken: #faf9f6;
  --ob-surface-muted: #f6f5f2;
  --ob-surface-active: #f2f0ec;
  --ob-line: #e7e4de;
  --ob-line-soft: #efece7;
  --ob-line-faint: #f4f2ee;
  --ob-line-strong: #dedad3;
  --ob-line-hover: #cfcbc3;
  --ob-ink: #1c1b18;
  --ob-ink-hover: #302e2a;
  --ob-ink-rail-active: #3d3a35;
  --ob-text-2: #4a4741;
  --ob-text-muted: #6b6862;
  --ob-text-subtle: #8b8780;
  --ob-text-faint: #a5a099;
  --ob-text-ghost: #c5c0b8;
  --ob-avatar-neutral: #4b4842;

  /* ----------------------------------------------------------- semantics */
  --ob-ok-fg: #2f7d4f;       --ob-ok-bg: #e8f3ec;       --ob-ok-border: #cfe4d7;
  --ob-warn-fg: #9a6410;     --ob-warn-bg: #fbf1de;     --ob-warn-border: #f0e0bc;
  --ob-risk-fg: #b4392f;     --ob-risk-bg: #fbeae7;     --ob-risk-border: #f2d3cd;
  --ob-info-fg: #2b5fb0;     --ob-info-bg: #e9f0fb;     --ob-info-border: #d5e2f5;
  --ob-accent-fg: #10736b;   --ob-accent-bg: #e6f2f0;   --ob-accent-fg-hover: #0b544e;
  --ob-automation-fg: #6a4fb0; --ob-automation-bg: #f0ebfa; --ob-automation-border: #d8cdf0;
  --ob-automation-bg-hover: #e6ddf7;
  --ob-neutral-fg: #6b6862;  --ob-neutral-bg: #f2f0ec;

  /* -------------------------------------------------------------- spacing */
  --ob-space-2: 2px;   --ob-space-3: 3px;   --ob-space-4: 4px;   --ob-space-5: 5px;
  --ob-space-6: 6px;   --ob-space-7: 7px;   --ob-space-8: 8px;   --ob-space-9: 9px;
  --ob-space-10: 10px; --ob-space-11: 11px; --ob-space-12: 12px; --ob-space-13: 13px;
  --ob-space-14: 14px; --ob-space-15: 15px; --ob-space-16: 16px; --ob-space-18: 18px;
  --ob-space-20: 20px; --ob-space-22: 22px; --ob-space-26: 26px; --ob-space-40: 40px;

  /* --------------------------------------------------------------- radius */
  --ob-radius-4: 4px; --ob-radius-5: 5px; --ob-radius-6: 6px; --ob-radius-7: 7px;
  --ob-radius-8: 8px; --ob-radius-9: 9px; --ob-radius-10: 10px; --ob-radius-11: 11px;
  --ob-radius-13: 13px; --ob-radius-full: 50%;

  /* -------------------------------------------------------------- shadows */
  --ob-shadow-card: 0 1px 2px rgba(28, 27, 24, .03);
  --ob-shadow-dropdown: 0 8px 24px rgba(28, 27, 24, .08);
  --ob-shadow-drawer: -20px 0 50px rgba(28, 27, 24, .1);
  --ob-shadow-modal: 0 30px 70px rgba(28, 27, 24, .22);
  --ob-shadow-toast: 0 12px 30px rgba(28, 27, 24, .25);
  --ob-shadow-ring-selected: 0 0 0 3px rgba(28, 27, 24, .07);
  --ob-scrim-drawer: rgba(28, 27, 24, .18);
  --ob-scrim-modal: rgba(28, 27, 24, .24);

  /* --------------------------------------------------------------- motion */
  --ob-duration-pop: .16s;
  --ob-duration-slide: .2s;
  --ob-duration-pulse: 2.4s;
  --ob-ease-default: ease;

  /* ---------------------------------------------------------------- type */
  --ob-font-family-ui: var(--font-instrument-sans), 'Instrument Sans', ui-sans-serif, system-ui, sans-serif;
  --ob-font-family-data: var(--font-spline-mono), 'Spline Sans Mono', ui-monospace, monospace;

  --ob-type-page-title-size: 26px;      --ob-type-page-title-line: 1.2;    --ob-type-page-title-tracking: -0.03em;
  --ob-type-hero-metric-size: 34px;     --ob-type-hero-metric-line: 1.1;   --ob-type-hero-metric-tracking: -0.04em;
  --ob-type-section-heading-size: 19px; --ob-type-section-heading-line: 1.25; --ob-type-section-heading-tracking: -0.025em;
  --ob-type-card-title-size: 13.5px;    --ob-type-card-title-line: 1.3;    --ob-type-card-title-tracking: -0.01em;
  --ob-type-body-size: 13.5px;          --ob-type-body-line: 1.45;         --ob-type-body-tracking: 0;
  --ob-type-table-cell-size: 12.5px;    --ob-type-table-cell-line: 1.4;    --ob-type-table-cell-tracking: 0;
  --ob-type-row-subtitle-size: 11.5px;  --ob-type-row-subtitle-line: 1.4;  --ob-type-row-subtitle-tracking: 0;
  --ob-type-small-print-size: 11px;     --ob-type-small-print-line: 1.4;   --ob-type-small-print-tracking: 0;
  --ob-type-breadcrumb-size: 10.5px;    --ob-type-breadcrumb-line: 1.3;    --ob-type-breadcrumb-tracking: 0.08em;
  --ob-type-mono-label-size: 10px;      --ob-type-mono-label-line: 1.3;    --ob-type-mono-label-tracking: 0.08em;
  --ob-type-mono-label-sm-size: 9.5px;  --ob-type-mono-label-sm-line: 1.3; --ob-type-mono-label-sm-tracking: 0.1em;
  --ob-type-mono-chip-size: 9.5px;      --ob-type-mono-chip-line: 1.2;     --ob-type-mono-chip-tracking: 0.05em;
  --ob-type-mono-chip-sm-size: 9px;     --ob-type-mono-chip-sm-line: 1.2;  --ob-type-mono-chip-sm-tracking: 0.05em;
  --ob-type-mono-data-size: 11px;       --ob-type-mono-data-line: 1.3;     --ob-type-mono-data-tracking: 0;
  --ob-type-nav-item-size: 13px;        --ob-type-nav-item-line: 1.3;      --ob-type-nav-item-tracking: 0;

  /* -------------------------------------------------------------- layout */
  --ob-rail-width: 56px;
  --ob-sidebar-width: 250px;
  --ob-topbar-height: 52px;
  --ob-content-padding-x: var(--ob-space-22);
  --ob-content-padding-top: var(--ob-space-22);
  --ob-content-padding-bottom: var(--ob-space-40);
  --ob-card-radius: var(--ob-radius-11);
  --ob-control-height: 31px;      /* standard button, 30-32 */
  --ob-control-height-sm: 27px;   /* small button, 26-29 */
  --ob-focus-ring-width: 2px;
}

@media (prefers-reduced-motion: reduce) {
  :root {
    --ob-duration-pop: .01ms;
    --ob-duration-slide: .01ms;
    --ob-duration-pulse: 0s;
  }
}
```

- [ ] **Step 2: Replace `tailwind-theme.css` in full**

```css
/* Onboard OS — Tailwind v4 theme. Import after tokens.css. */
@theme {
  --color-canvas: var(--ob-canvas);
  --color-surface: var(--ob-surface);
  --color-surface-sunken: var(--ob-surface-sunken);
  --color-surface-muted: var(--ob-surface-muted);
  --color-surface-active: var(--ob-surface-active);
  --color-line: var(--ob-line);
  --color-line-soft: var(--ob-line-soft);
  --color-line-faint: var(--ob-line-faint);
  --color-line-strong: var(--ob-line-strong);
  --color-line-hover: var(--ob-line-hover);
  --color-ink: var(--ob-ink);
  --color-ink-hover: var(--ob-ink-hover);
  --color-text-2: var(--ob-text-2);
  --color-text-muted: var(--ob-text-muted);
  --color-text-subtle: var(--ob-text-subtle);
  --color-text-faint: var(--ob-text-faint);
  --color-text-ghost: var(--ob-text-ghost);
  --color-ok-fg: var(--ob-ok-fg); --color-ok-bg: var(--ob-ok-bg);
  --color-warn-fg: var(--ob-warn-fg); --color-warn-bg: var(--ob-warn-bg);
  --color-risk-fg: var(--ob-risk-fg); --color-risk-bg: var(--ob-risk-bg);
  --color-info-fg: var(--ob-info-fg); --color-info-bg: var(--ob-info-bg);
  --color-accent-fg: var(--ob-accent-fg); --color-accent-bg: var(--ob-accent-bg);
  --color-automation-fg: var(--ob-automation-fg); --color-automation-bg: var(--ob-automation-bg);
  --color-neutral-fg: var(--ob-neutral-fg); --color-neutral-bg: var(--ob-neutral-bg);

  --radius-4: var(--ob-radius-4); --radius-5: var(--ob-radius-5); --radius-6: var(--ob-radius-6);
  --radius-7: var(--ob-radius-7); --radius-8: var(--ob-radius-8); --radius-9: var(--ob-radius-9);
  --radius-10: var(--ob-radius-10); --radius-11: var(--ob-radius-11); --radius-13: var(--ob-radius-13);
  --radius-full: var(--ob-radius-full);

  --spacing-2: var(--ob-space-2); --spacing-3: var(--ob-space-3); --spacing-4: var(--ob-space-4);
  --spacing-5: var(--ob-space-5); --spacing-6: var(--ob-space-6); --spacing-7: var(--ob-space-7);
  --spacing-8: var(--ob-space-8); --spacing-9: var(--ob-space-9); --spacing-10: var(--ob-space-10);
  --spacing-11: var(--ob-space-11); --spacing-12: var(--ob-space-12); --spacing-13: var(--ob-space-13);
  --spacing-14: var(--ob-space-14); --spacing-15: var(--ob-space-15); --spacing-16: var(--ob-space-16);
  --spacing-18: var(--ob-space-18); --spacing-20: var(--ob-space-20); --spacing-22: var(--ob-space-22);
  --spacing-26: var(--ob-space-26); --spacing-40: var(--ob-space-40);

  --font-ui: var(--ob-font-family-ui);
  --font-data: var(--ob-font-family-data);

  --shadow-card: var(--ob-shadow-card);
  --shadow-dropdown: var(--ob-shadow-dropdown);
  --shadow-drawer: var(--ob-shadow-drawer);
  --shadow-modal: var(--ob-shadow-modal);
  --shadow-toast: var(--ob-shadow-toast);
}
```

Note: no `@custom-variant dark (...)` — there is no dark theme to key it to.

- [ ] **Step 3: Update the icon generator's source-path comment**

In `frontend/src/components/icons/index.tsx:1-2`, change:
```ts
// GENERATED by scripts/generate-icons.mjs from
// docs/uispecs/design/03-icons/icons.json — 56 icons. Do not edit.
```
to:
```ts
// GENERATED by scripts/generate-icons.mjs from
// docs/uispecs_legacy/design/03-icons/icons.json — 56 icons. Do not edit.
// (docs/uispecs_latest/ recommends Lucide instead; this set is kept — see
// docs/superpowers/specs/2026-08-26-frontend-visual-refactor-design.md §4.)
```

- [ ] **Step 4: Run the build to confirm no broken references yet**

Run: `cd frontend && npm run build`
Expected: FAILS — `globals.css` (Task 3) and every component still reference old `--ob-*` names not
yet defined. This is expected at this point; Task 1 only replaces the token *source*, not its
consumers. Confirm the failure is specifically undefined-variable/missing-token in nature (CSS
custom properties don't error at build time, but `frontend/src/app/globals.css`'s `:root` block
directly reads several old names for the shadcn mapping — Task 3 fixes that). If `npm run build`
succeeds silently, stop and check `tokens.css` was actually replaced, since undefined CSS custom
properties fail silently rather than erroring.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/tokens.css frontend/src/app/tailwind-theme.css frontend/src/components/icons/index.tsx
git commit -m "feat(frontend): rewrite token layer for the new design system

Flat token names mirroring DESIGN_TOKENS.md directly, light-only. Nothing
consumes these new names yet -- Task 2 onward repoint components."
```

---

### Task 2: Swap fonts, remove dark-theme provider, update globals.css

**Files:**
- Modify: `frontend/src/app/layout.tsx`
- Modify: `frontend/src/app/globals.css`
- Delete: `frontend/src/components/ThemeProvider.tsx`
- Delete: `frontend/src/components/shell/ThemeToggle.tsx`
- Delete: `frontend/src/components/shell/ThemeToggle.test.tsx`
- Modify: `frontend/src/components/shell/TopBar.tsx:1-5,64` (minimal unblock only — remove the
  `ThemeToggle` import and its JSX usage; the rest of `TopBar` is restyled in Task 8)
- Modify: `frontend/package.json` (remove `next-themes`)

**Interfaces:**
- Produces: `--font-instrument-sans` / `--font-spline-mono` CSS variables on `<html>`, consumed by
  `tokens.css`'s `--ob-font-family-*` (Task 1).

- [ ] **Step 1: Replace the font loading in `layout.tsx`**

```tsx
import type { Metadata } from "next";
import { Instrument_Sans, Spline_Sans_Mono } from "next/font/google";
import "./globals.css";

const instrumentSans = Instrument_Sans({
  variable: "--font-instrument-sans",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  style: ["normal", "italic"],
  display: "swap",
});

const splineMono = Spline_Sans_Mono({
  variable: "--font-spline-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "Onboard OS",
  description: "Enterprise customer journey and onboarding platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${instrumentSans.variable} ${splineMono.variable}`}>
      <body className="antialiased">{children}</body>
    </html>
  );
}
```

Note what's gone: `suppressHydrationWarning` (only needed for `next-themes` writing `data-theme`
client-side — no longer applicable) and the `<ThemeProvider>` wrap.

- [ ] **Step 2: Update `globals.css`**

Replace the shadcn mapping block (the `:root { --background: ...; }` block) with:

```css
:root {
  --background: var(--ob-canvas);
  --foreground: var(--ob-ink);
  --card: var(--ob-surface);
  --card-foreground: var(--ob-ink);
  --popover: var(--ob-surface);
  --popover-foreground: var(--ob-ink);
  --primary: var(--ob-ink);
  --primary-foreground: var(--ob-canvas);
  --secondary: var(--ob-surface-active);
  --secondary-foreground: var(--ob-text-muted);
  --muted: var(--ob-surface-sunken);
  --muted-foreground: var(--ob-text-subtle);
  --accent: var(--ob-accent-bg);
  --accent-foreground: var(--ob-accent-fg);
  --destructive: var(--ob-risk-fg);
  --destructive-foreground: var(--ob-canvas);
  --border: var(--ob-line);
  --input: var(--ob-line);
  --ring: var(--ob-ink);
  --radius: var(--ob-radius-9);
}
```

(`--primary`/`--primary-foreground` now point at `ink`/`canvas`, matching the Global Constraints
note that the primary button is ink, not accent.)

Delete the three derived-role blocks entirely (`--ob-graphic-muted`, the rail-width-collapsed /
rail-item-hover / bg-header block) — none has a use in the new shell (Task 4 confirms `Rail` and
`Sidebar` need none of them; `--ob-graphic-muted`'s few remaining consumers are repointed to
`--ob-line-strong` directly per the rename map, not re-derived here).

Replace the font-family pointer block:

```css
:root {
  --ob-font-family-ui: var(--font-instrument-sans), 'Instrument Sans', ui-sans-serif, system-ui, sans-serif;
  --ob-font-family-data: var(--font-spline-mono), 'Spline Sans Mono', ui-monospace, monospace;
}
```

(This duplicates `tokens.css`'s Task 1 definition — keep only one. Since `tokens.css` already
defines these referencing the `--font-*` variables directly, delete this block from `globals.css`
entirely rather than duplicating it.)

Replace the focus-visible rule's colour (was `--ob-accent`, now `--ob-ink` per
`docs/uispecs_latest/.../README.md`'s non-negotiable: "2px `#1c1b18` outline, 2px offset"):

```css
:where(button, a, input, select, textarea, [tabindex]):focus-visible {
  outline: 2px solid var(--ob-ink);
  outline-offset: 2px;
  border-radius: 5px;
}
```

Replace the `fadeUp` keyframe block with the three named in `DESIGN_TOKENS.md`'s Motion section:

```css
@keyframes om-pop {
  from { opacity: 0; transform: translateY(6px) scale(.99); }
  to { opacity: 1; transform: none; }
}
@keyframes om-slide {
  from { transform: translateX(24px); opacity: 0; }
  to { transform: none; opacity: 1; }
}
@keyframes om-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .35; }
}
```

(`MilestoneRow`'s expanded panel, the one real consumer of the old `fadeUp`/`--ob-keyframe-enter`
reference, is repointed to `om-pop` in Task 20 — do not fix it here, only the keyframe definition
changes in this task.)

Keep the `body { background; color; font-family }` rule, repointing to `--ob-canvas` /
`--ob-ink` / `--ob-font-family-ui`.

- [ ] **Step 3: Delete the dark-theme files**

```bash
git rm frontend/src/components/ThemeProvider.tsx
git rm frontend/src/components/shell/ThemeToggle.tsx
git rm frontend/src/components/shell/ThemeToggle.test.tsx
```

- [ ] **Step 4: Strip `ThemeToggle` out of `TopBar.tsx` (minimal — full restyle is Task 8)**

Remove the `import { ThemeToggle } from "./ThemeToggle";` line and the `<ThemeToggle />` JSX usage.
Leave everything else in `TopBar.tsx` untouched for now — Task 8 does its full restyle.

- [ ] **Step 5: Remove the `next-themes` dependency**

```bash
cd frontend && npm uninstall next-themes
```

- [ ] **Step 6: Run the full check**

Run: `cd frontend && npx vitest run && npm run build`
Expected: PASS. `TopBar.test.tsx` may still reference `ThemeToggle` — if so this step fails; fix by
removing the theme-toggle assertions from `TopBar.test.tsx` now (a minimal removal, not a rewrite —
Task 8 rewrites the rest of that test file for the full restyle).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/layout.tsx frontend/src/app/globals.css frontend/src/components/shell/TopBar.tsx frontend/src/components/shell/TopBar.test.tsx frontend/package.json frontend/package-lock.json
git commit -m "feat(frontend): swap fonts, remove dark-theme support

Instrument Sans / Spline Sans Mono replace Archivo / IBM Plex Mono.
ThemeProvider, ThemeToggle and next-themes are gone -- light theme only,
per the new design system having no dark palette at all."
```

---

### Task 3: Build the icon rail (`Rail.tsx`)

**Files:**
- Create: `frontend/src/components/shell/Rail.tsx`
- Create: `frontend/src/components/shell/Rail.test.tsx`

**Interfaces:**
- Consumes: `useAuth()` from `@/lib/auth/useAuth` (same hook `TopBar`'s `AccountMenu` already
  uses — `{ user, logout }`), `t()` from `@/lib/i18n`.
- Produces: `export function Rail({ onToggleSidebar }: { onToggleSidebar?: () => void })`,
  consumed by Task 6 (wiring into the tenant layout). `onToggleSidebar` is optional and only
  rendered as a button when provided — Task 5 (`Sidebar`) passes it once the `<1024px` drawer
  exists; until then `Rail` renders without a toggle button.

The account-menu popover (open/close state, Escape-to-close, outside-click-close, focus-in-on-open,
return-focus-on-close) is moved here verbatim from `TopBar.tsx`'s existing `AccountMenu` —
same logic, new position and new colours. This is a relocation, not a rewrite: reuse the pattern
already proven in `TopBar.tsx:81-226` today (the `useCallback`/`useRef`/keydown-and-pointerdown
`useEffect` block), don't re-derive it.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/shell/Rail.test.tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Rail } from "./Rail";

vi.mock("@/lib/auth/useAuth", () => ({
  useAuth: () => ({
    user: { fullName: "Jordan Diaz", email: "jordan@acme.test" },
    logout: vi.fn(),
  }),
}));

describe("Rail", () => {
  it("renders the brand mark and the account trigger, and opens the account popover on click", () => {
    render(<Rail />);
    const trigger = screen.getByRole("button", { name: /jordan diaz/i });
    expect(trigger).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.getByText("Jordan Diaz")).toBeInTheDocument();
    expect(screen.getByText("jordan@acme.test")).toBeInTheDocument();
  });

  it("does not render a sidebar-toggle button when onToggleSidebar is omitted", () => {
    render(<Rail />);
    expect(screen.queryByRole("button", { name: /menu/i })).not.toBeInTheDocument();
  });

  it("renders and calls onToggleSidebar when provided", () => {
    const onToggle = vi.fn();
    render(<Rail onToggleSidebar={onToggle} />);
    fireEvent.click(screen.getByRole("button", { name: /menu/i }));
    expect(onToggle).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd frontend && npx vitest run Rail.test.tsx`
Expected: FAIL — `Rail.tsx` does not exist.

- [ ] **Step 3: Implement `Rail.tsx`**

```tsx
"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { FocusEvent } from "react";
import { useAuth } from "@/lib/auth/useAuth";
import { t } from "@/lib/i18n";

/**
 * The 56px icon rail (COMPONENTS.md §1). Brand mark at top, account menu at the
 * bottom -- no section buttons (Workspace/Configure/Customer portal), since this
 * app has no customer portal and no navigational split those buttons would
 * switch between yet; no ⌘K button, since there is no command palette to open.
 * Both are real gaps in the *design*, not oversights here -- see the refactor's
 * design spec §2 for why they're out of scope.
 */
export function Rail({ onToggleSidebar }: { onToggleSidebar?: () => void }) {
  return (
    <div
      className="flex flex-col items-center bg-ink shrink-0"
      style={{ width: "var(--ob-rail-width)", padding: "14px 0 12px", gap: "var(--ob-space-6)" }}
    >
      <BrandMark />
      {onToggleSidebar && (
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={t("shell.sidebar.toggle")}
          className="text-[#f4f2ee]"
          style={{
            width: 34,
            height: 34,
            borderRadius: "var(--ob-radius-9)",
            fontSize: 14,
            display: "grid",
            placeItems: "center",
          }}
        >
          ☰
        </button>
      )}
      <div className="flex-1" />
      <AccountMenu />
    </div>
  );
}

function BrandMark() {
  return (
    <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="9" fill="var(--ob-accent-fg)" />
      <rect x="10.5" y="10.5" width="11" height="11" fill="var(--ob-canvas)" transform="rotate(45 16 16)" />
    </svg>
  );
}

/** Moved verbatim from TopBar.tsx's AccountMenu -- same open/close, focus and keyboard logic. */
function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const firstActionRef = useRef<HTMLButtonElement>(null);

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!open) return;
    firstActionRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") close(true);
    }
    function onPointerDown(event: MouseEvent) {
      if (containerRef.current?.contains(event.target as Node)) return;
      close(containerRef.current?.contains(document.activeElement) ?? false);
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onPointerDown);
    };
  }, [open, close]);

  function onBlurWithin(event: FocusEvent<HTMLDivElement>) {
    if (!open) return;
    const next = event.relatedTarget as Node | null;
    if (next && !containerRef.current?.contains(next)) close(false);
  }

  const name = user?.fullName ?? user?.email ?? "";

  return (
    <div className="relative" ref={containerRef} onBlur={onBlurWithin}>
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t("shell.account.open", { name })}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
        className="grid place-items-center"
        style={{
          width: 30,
          height: 30,
          borderRadius: "var(--ob-radius-full)",
          background: "var(--ob-avatar-neutral)",
          color: "var(--ob-canvas)",
          font: "600 13px/1 var(--ob-font-family-ui)",
        }}
      >
        {initials(name)}
      </button>

      {open && (
        <div
          id={panelId}
          role="group"
          aria-label={t("shell.account.open", { name })}
          className="absolute bottom-0 left-full"
          style={{
            marginLeft: "var(--ob-space-8)",
            minWidth: 200,
            background: "var(--ob-surface)",
            border: "1px solid var(--ob-line)",
            borderRadius: "var(--ob-radius-10)",
            boxShadow: "var(--ob-shadow-dropdown)",
            padding: "var(--ob-space-8)",
          }}
        >
          <div style={{ padding: "var(--ob-space-6) var(--ob-space-8) var(--ob-space-10)" }}>
            <p style={{ font: "500 12.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-ink)" }}>
              {user?.fullName}
            </p>
            <p style={{ font: "10.5px/1.3 var(--ob-font-family-data)", color: "var(--ob-text-muted)" }}>
              {user?.email}
            </p>
          </div>
          <button
            ref={firstActionRef}
            type="button"
            onClick={() => {
              setOpen(false);
              void logout();
            }}
            className="w-full text-left hover:bg-surface-sunken"
            style={{
              padding: "var(--ob-space-8)",
              borderRadius: "var(--ob-radius-8)",
              font: "13px/1.4 var(--ob-font-family-ui)",
              color: "var(--ob-ink)",
            }}
          >
            {t("auth.signOut")}
          </button>
        </div>
      )}
    </div>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0];
  const last = parts[parts.length - 1];
  if (!first || !last) return "";
  return (first.slice(0, 1) + (parts.length > 1 ? last.slice(0, 1) : "")).toUpperCase();
}
```

- [ ] **Step 4: Add the `shell.sidebar.toggle` i18n key**

Add to the en locale (wherever `shell.account.open` is currently defined, same file):
`"shell.sidebar.toggle": "Toggle navigation menu"`.

- [ ] **Step 5: Run the test to confirm it passes**

Run: `cd frontend && npx vitest run Rail.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/shell/Rail.tsx frontend/src/components/shell/Rail.test.tsx frontend/src/lib/i18n
git commit -m "feat(frontend): add the 56px icon rail (COMPONENTS.md §1)

Brand mark plus the account menu, relocated verbatim from TopBar's
AccountMenu -- same focus/keyboard handling, new position. No section
buttons or command-palette trigger: neither has a current counterpart."
```

---

### Task 4: Restyle the sidebar and add the sub-1024px drawer

**Files:**
- Modify: `frontend/src/components/shell/Sidebar.tsx` (full rewrite)
- Modify: `frontend/src/components/shell/Sidebar.test.tsx`

**Interfaces:**
- Produces: `export function Sidebar({ slug, isOpen, onClose }: { slug: string; isOpen?: boolean;
  onClose?: () => void })`. Below 1024px, `Sidebar` renders as a drawer whose visibility is
  controlled by `isOpen`/`onClose` (both optional — when omitted, `Sidebar` always renders inline,
  which is what happens ≥1024px regardless of the props, and is also what today's tests running at
  a default jsdom width already exercise). Consumed by Task 6.
- Consumes: `useHasPermission` (unchanged), `LayoutDashboardIcon`/`UsersIcon`/`SlidersIcon`
  (unchanged).

- [ ] **Step 1: Update the failing/changed assertions in `Sidebar.test.tsx` first**

Read the current file, then update its style-coupled assertions (per the design spec §8, this is
one of the four known style-coupled test files) to the new token names, and add two new cases:

```tsx
it("renders as a drawer below 1024px, hidden until isOpen", () => {
  window.innerWidth = 900;
  const { rerender } = render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
  expect(screen.getByRole("navigation")).toHaveAttribute("aria-hidden", "true");
  rerender(<Sidebar slug="acme" isOpen={true} onClose={vi.fn()} />);
  expect(screen.getByRole("navigation")).not.toHaveAttribute("aria-hidden");
});

it("calls onClose on Escape when open as a drawer", () => {
  const onClose = vi.fn();
  render(<Sidebar slug="acme" isOpen={true} onClose={onClose} />);
  fireEvent.keyDown(document, { key: "Escape" });
  expect(onClose).toHaveBeenCalledOnce();
});
```

Keep every existing permission-gating test (`canViewCustomers`, `canViewUsers`/`canViewRoles`)
unchanged — that logic doesn't move.

- [ ] **Step 2: Run to confirm the new cases fail**

Run: `cd frontend && npx vitest run Sidebar.test.tsx`
Expected: FAIL on the two new cases; existing cases may also fail on stale style assertions —
confirm the failures are the ones expected before moving on.

- [ ] **Step 3: Rewrite `Sidebar.tsx`**

Keep the existing `items` construction logic (the `canViewCustomers`/`canViewUsers`/`canViewRoles`
block building the `NavItem[]` array) untouched — only the render changes:

```tsx
"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ComponentType } from "react";
import { LayoutDashboardIcon, SlidersIcon, UsersIcon } from "@/components/icons";
import type { IconProps } from "@/components/icons";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

type NavItem = {
  label: string;
  href: string;
  section: string;
  Icon: ComponentType<IconProps>;
};

export function Sidebar({
  slug,
  isOpen,
  onClose,
}: {
  slug: string;
  isOpen?: boolean;
  onClose?: () => void;
}) {
  const pathname = usePathname();

  const canViewCustomers = useHasPermission("customer.view");
  const canViewUsers = useHasPermission("user.view");
  const canViewRoles = useHasPermission("role.view");

  const items: NavItem[] = [
    { label: t("nav.dashboard"), href: `/t/${slug}/dashboard`, section: `/t/${slug}/dashboard`, Icon: LayoutDashboardIcon },
  ];
  if (canViewCustomers) {
    items.push({ label: t("nav.customers"), href: `/t/${slug}/customers`, section: `/t/${slug}/customers`, Icon: UsersIcon });
  }
  if (canViewUsers || canViewRoles) {
    items.push({
      label: t("nav.admin"),
      href: canViewUsers ? `/t/${slug}/admin/users` : `/t/${slug}/admin/roles`,
      section: `/t/${slug}/admin`,
      Icon: SlidersIcon,
    });
  }

  const isDrawer = onClose !== undefined;

  // Escape closes the drawer. Only attached when it's actually a controlled drawer.
  useEffect(() => {
    if (!isDrawer || !isOpen) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose?.();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [isDrawer, isOpen, onClose]);

  return (
    <>
      {isDrawer && isOpen && (
        <div
          className="fixed inset-0 z-40"
          style={{ background: "var(--ob-scrim-drawer)" }}
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        className={isDrawer ? "fixed inset-y-0 z-50 max-[1023px]:flex hidden min-[1024px]:flex" : "flex"}
        style={{
          left: "var(--ob-rail-width)",
          width: "var(--ob-sidebar-width)",
          background: "var(--ob-canvas)",
          borderRight: "1px solid var(--ob-line)",
          flexDirection: "column",
          padding: "12px 10px 10px",
          transform: isDrawer && !isOpen ? "translateX(-100%)" : "none",
          transition: isDrawer ? `transform var(--ob-duration-slide) var(--ob-ease-default)` : undefined,
        }}
      >
        <nav
          aria-label={t("shell.nav.label")}
          aria-hidden={isDrawer && !isOpen ? true : undefined}
        >
          <ul className="flex flex-col" style={{ gap: "var(--ob-space-2)" }}>
            {items.map((item) => {
              const active = pathname === item.section || pathname.startsWith(`${item.section}/`);
              return (
                <li key={item.href}>
                  <NavLink item={item} active={active} onNavigate={isDrawer ? onClose : undefined} />
                </li>
              );
            })}
          </ul>
        </nav>
      </aside>
    </>
  );
}

function NavLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate?: () => void;
}) {
  const { Icon, href, label } = item;
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      onClick={onNavigate}
      className="flex items-center w-full hover:bg-surface-active"
      style={{
        gap: "var(--ob-space-9)",
        padding: "7px 9px",
        borderRadius: "var(--ob-radius-8)",
        font: `${active ? 500 : 400} var(--ob-type-nav-item-size)/var(--ob-type-nav-item-line) var(--ob-font-family-ui)`,
        color: "var(--ob-ink)",
        background: active ? "var(--ob-surface-active)" : "transparent",
      }}
    >
      <span aria-hidden="true" className="grid place-items-center" style={{ flex: "0 0 14px", width: 14, color: "var(--ob-text-subtle)" }}>
        <Icon size={14} />
      </span>
      <span className="text-left">{label}</span>
    </Link>
  );
}
```

Note what's gone versus the pre-refactor version: the icon-only collapse at `<1281px` (the old
system's own responsive strategy) is replaced by the new spec's actual requirement — full hide,
toggled open as a drawer, below `1024px` (`SCREENS.md`'s `RESPONSIVE` table). The `min-[1024px]:flex`
/ `max-[1023px]:hidden` classes make the sidebar always inline ≥1024px regardless of `isOpen`.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run Sidebar.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/shell/Sidebar.tsx frontend/src/components/shell/Sidebar.test.tsx
git commit -m "feat(frontend): restyle Sidebar to the new NavItem spec, add <1024px drawer

Replaces the old icon-only collapse at 1281px with the actual requirement
from SCREENS.md's RESPONSIVE table: full hide + toggled drawer below
1024px, toggled from the rail (Task 3)."
```

---

### Task 5: Restyle the top bar

**Files:**
- Modify: `frontend/src/components/shell/TopBar.tsx` (the `AccountMenu` function and its usage are
  deleted here — it moved to `Rail.tsx` in Task 3 — leaving only the breadcrumb/title/meta header)
- Modify: `frontend/src/components/shell/TopBar.test.tsx`

**Interfaces:**
- Produces: `export function TopBar()` — same signature as before, no props change.
- Consumes: `usePageHeader()` (unchanged — `{ title, meta }`).

- [ ] **Step 1: Update `TopBar.test.tsx`**

Remove every `AccountMenu`-related test case (moved to `Rail.test.tsx` in Task 3). Keep the
title/meta rendering tests, updating any style assertions to the new tokens.

- [ ] **Step 2: Rewrite `TopBar.tsx`**

```tsx
"use client";

import { usePageHeader } from "./PageHeader";

/**
 * The shell top bar (COMPONENTS.md §1's "Top bar"). Presence cluster, inbox
 * button and a contextual primary action are specified there but have no
 * current counterpart -- no presence tracking, no inbox, no per-screen
 * "primary action" concept exists yet, so the right side is empty rather than
 * a dead control. Same principle this file already applied to search/
 * notifications before this restyle.
 */
export function TopBar() {
  const { title, meta } = usePageHeader();

  return (
    <header
      className="sticky top-0 z-30 flex items-center border-b"
      style={{
        height: "var(--ob-topbar-height)",
        padding: "0 18px",
        gap: "var(--ob-space-12)",
        borderColor: "var(--ob-line)",
        background: "var(--ob-canvas)",
      }}
    >
      {title && (
        <span
          className="truncate min-w-0 uppercase"
          style={{
            font: `var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)`,
            letterSpacing: "var(--ob-type-breadcrumb-tracking)",
            color: "var(--ob-text-subtle)",
          }}
        >
          {title}
        </span>
      )}
      {meta && (
        <span
          className="overflow-hidden text-ellipsis whitespace-nowrap"
          style={{
            font: `var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)`,
            color: "var(--ob-text-muted)",
          }}
        >
          {meta}
        </span>
      )}
      <div className="flex-1" />
    </header>
  );
}
```

Note the deliberate change from the old `<h1>` (17px/600 human-readable title) to a mono uppercase
breadcrumb-style label — `COMPONENTS.md` §1 specifies the top bar's left content as "breadcrumb,
mono 10.5px ... uppercase," not a page `<h1>`. `PageHeaderProvider`'s API (`useSetPageHeader`) is
unchanged; every page's own in-content heading (rendered separately, per `SCREENS.md`'s page-header
pattern: "mono eyebrow ... h1 26px") is what now carries the actual semantic `<h1>` — confirm each
route already renders its own `<h1>`/`<h2>` before removing this one (Phase C's screen tasks verify
this per-route; this task only changes the shell chrome).

- [ ] **Step 3: Run the tests**

Run: `cd frontend && npx vitest run TopBar.test.tsx`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/shell/TopBar.tsx frontend/src/components/shell/TopBar.test.tsx
git commit -m "feat(frontend): restyle TopBar to the new breadcrumb spec

AccountMenu is gone from here (moved to Rail in an earlier task); the
left side becomes a mono uppercase breadcrumb per COMPONENTS.md §1
instead of a 17px human-readable h1."
```

---

### Task 6: Wire the rail and sidebar drawer into the tenant layout

**Files:**
- Modify: `frontend/src/app/(app)/t/[slug]/layout.tsx`

**Interfaces:**
- Consumes: `Rail` (Task 3), `Sidebar` (Task 4) — both now take the shared `sidebarOpen`/`setSidebarOpen`
  state this task introduces.

- [ ] **Step 1: Rewrite the shell composition**

```tsx
"use client";

import { use, useState } from "react";
import { AuthProvider } from "@/lib/auth/AuthProvider";
import { AuthGuard } from "@/lib/auth/AuthGuard";
import { PageHeaderProvider } from "@/components/shell/PageHeader";
import { QueryProvider } from "@/lib/api/QueryProvider";
import { Rail } from "@/components/shell/Rail";
import { Sidebar } from "@/components/shell/Sidebar";
import { TopBar } from "@/components/shell/TopBar";

export default function TenantLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <AuthProvider slug={slug}>
      <AuthGuard slug={slug}>
        <QueryProvider>
          <PageHeaderProvider>
            <div className="flex min-h-screen">
              <Rail onToggleSidebar={() => setSidebarOpen((open) => !open)} />
              <Sidebar slug={slug} isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
              <div
                className="flex flex-1 min-w-0 flex-col"
                style={{ marginLeft: "var(--ob-rail-width)" }}
              >
                <div className="hidden min-[1024px]:block" style={{ marginLeft: "var(--ob-sidebar-width)", marginTop: `calc(-1 * var(--ob-topbar-height))` }} />
                <TopBar />
                <main
                  className="flex-1"
                  style={{
                    paddingLeft: "var(--ob-content-padding-x)",
                    paddingRight: "var(--ob-content-padding-x)",
                    paddingTop: "var(--ob-content-padding-top)",
                    paddingBottom: "var(--ob-content-padding-bottom)",
                  }}
                >
                  {children}
                </main>
              </div>
            </div>
          </PageHeaderProvider>
        </QueryProvider>
      </AuthGuard>
    </AuthProvider>
  );
}
```

This step's layout math (fixed rail, `Sidebar` positioned via its own `left` style set in Task 4,
main content offset by `margin-left`) is one reasonable way to lay out two fixed-position side
columns without a third column-tracking wrapper — if it renders with a visible gap or overlap
during Step 2's manual check, the fix is adjusting `Sidebar`'s own `position`/`left` (Task 4) to
`fixed` with `top: 0` and giving the flex row's second child a `margin-left` of
`calc(var(--ob-rail-width) + var(--ob-sidebar-width))` instead of the two-margin approach above —
prefer whichever renders correctly over preserving this exact snippet.

- [ ] **Step 2: Manual check**

Run: `cd frontend && npm run dev`, open a tenant route, confirm: the rail (56px, dark) sits flush
left, the sidebar (250px, canvas) sits beside it with nav items, the top bar sits above the content
at the correct offset, and resizing the viewport below 1024px hides the sidebar and shows Rail's
toggle button, which opens it as an overlay drawer.

- [ ] **Step 3: Run the full suite**

Run: `cd frontend && npx vitest run && npm run build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/\(app\)/t/\[slug\]/layout.tsx
git commit -m "feat(frontend): wire Rail + Sidebar drawer into the tenant shell"
```

---

### Task 7: Adapt the contrast script for the new token set

**Files:**
- Create: `frontend/scripts/contrast.py`

**Interfaces:**
- None consumed by other tasks — this is a standalone verification script, run manually (per the
  design spec §8) whenever a colour is added, same convention `docs/uispecs_legacy/design/scripts/contrast.py`
  established.

- [ ] **Step 1: Write the script**

The new token set is flat, single-theme, literal hex — no `build_tokens.py`-style resolution layer
is needed (that machinery existed specifically for the old three-layer/dark-theme indirection).
Reuse `docs/uispecs_legacy/design/scripts/contrast.py`'s `hex_to_rgb`/`lum`/`ratio`/`large`/
`threshold`/`report` functions verbatim (the WCAG math doesn't change), replacing its `PAIRS` and
`SHIPPED_PAIRS` machinery with one literal list built from `DESIGN_TOKENS.md`'s own colour table:

```python
# -*- coding: utf-8 -*-
"""WCAG 2.1 contrast audit for the current (light-only) token set.

Literal hex pairs from docs/uispecs_latest/design_handoff_onboarding_platform/DESIGN_TOKENS.md.
A FAIL here is live -- these are the values tokens.css actually ships.
"""

def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def lum(c):
    def f(u):
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (f(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg, bg):
    a, b = lum(hex_to_rgb(fg)), lum(hex_to_rgb(bg))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


CANVAS = "#fbfaf8"
SURFACE = "#ffffff"
SUNKEN = "#faf9f6"
INK = "#1c1b18"

# (label, fg, bg, size_px, weight, where) -- size 0 = non-text (1.4.11, 3:1)
PAIRS = [
    ("ink on surface",           INK,       SURFACE, 13,   400, "table cells, titles"),
    ("ink on canvas",            INK,       CANVAS,  13,   400, "page headings"),
    ("text-2 on surface",        "#4a4741", SURFACE, 12,   400, "callout secondary copy"),
    ("text-muted on surface",    "#6b6862", SURFACE, 11.5, 400, "supporting copy"),
    ("text-muted on canvas",     "#6b6862", CANVAS,  11.5, 400, "header meta"),
    ("text-subtle on surface",   "#8b8780", SURFACE, 11.5, 400, "row subtitles"),
    ("text-subtle on sunken",    "#8b8780", SUNKEN,  11.5, 400, "table header labels"),
    ("text-faint on surface",    "#a5a099", SURFACE, 10,   500, "mono uppercase labels"),
    ("text-faint on sunken",     "#a5a099", SUNKEN,  9.5,  500, "table header labels"),
    ("canvas on ink",            CANVAS,    INK,     12.5, 600, "primary button label"),
    ("canvas on ink-hover",      CANVAS,    "#302e2a", 12.5, 600, "primary button hover"),
    ("accent-fg on surface",     "#10736b", SURFACE, 11.5, 400, "links, active state"),
    ("accent-fg on accent-bg",   "#10736b", "#e6f2f0", 9,   500, "accent chip"),

    ("ok-fg on ok-bg",           "#2f7d4f", "#e8f3ec", 9.5, 500, "status chip"),
    ("warn-fg on warn-bg",       "#9a6410", "#fbf1de", 9.5, 500, "status chip"),
    ("risk-fg on risk-bg",       "#b4392f", "#fbeae7", 9.5, 500, "status chip"),
    ("info-fg on info-bg",       "#2b5fb0", "#e9f0fb", 9.5, 500, "status chip"),
    ("automation-fg on automation-bg", "#6a4fb0", "#f0ebfa", 9.5, 500, "status chip"),
    ("neutral-fg on neutral-bg", "#6b6862", "#f2f0ec", 9.5, 500, "status chip"),

    ("canvas on ink (rail)",     CANVAS,    INK,     13,   400, "rail brand mark glyph"),
    ("avatar initials",         CANVAS,    "#4b4842", 13,  600, "neutral avatar fill"),

    ("line on surface",          "#e7e4de", SURFACE, 0,    0,   "card and control borders"),
    ("line-strong on surface",   "#dedad3", SURFACE, 0,    0,   "emphasis edge, 20px+ marks"),
]


def large(size, weight):
    return size >= 24 or (size >= 18.66 and weight >= 700)


def threshold(size, weight):
    if size == 0:
        return 3.0, None
    return (3.0, 4.5) if large(size, weight) else (4.5, 7.0)


def report(pairs):
    print(f"{'pair':32s} {'fg':9s} {'bg':9s} {'px':>5s} {'ratio':>6s}  AA   where")
    print("-" * 100)
    fails = []
    for label, fg, bg, size, weight, where in pairs:
        r = ratio(fg, bg)
        need_aa, _ = threshold(size, weight)
        aa = "pass" if r >= need_aa else "FAIL"
        if aa == "FAIL":
            fails.append((label, r, need_aa, where))
        px = "  n/t" if size == 0 else f"{size:5.1f}"
        print(f"{label:32s} {fg:9s} {bg:9s} {px} {r:6.2f}  {aa:4s} {where}")
    print()
    print(f"{len(fails)} of {len(pairs)} pairs fail WCAG AA for their size:")
    for label, r, need, where in fails:
        print(f"  - {label:30s} {r:.2f}:1  (needs {need}:1)  -- {where}")
    return fails


if __name__ == "__main__":
    import sys
    fails = report(PAIRS)
    sys.exit(1 if fails else 0)
```

- [ ] **Step 2: Run it**

Run: `python frontend/scripts/contrast.py`
Expected: exits 0, "0 of N pairs fail." If any pair fails, that is a real finding — stop and either
pick a different pairing for that use (e.g. a size/weight combo the design actually uses at that
spot) or flag it; do not adjust the hex values to force a pass, since they come directly from
`DESIGN_TOKENS.md`.

- [ ] **Step 3: Commit**

```bash
git add frontend/scripts/contrast.py
git commit -m "test: add a light-only WCAG contrast script for the new tokens

Reuses the WCAG math from docs/uispecs_legacy/design/scripts/contrast.py;
replaces its token-resolution machinery (built for the old three-layer/
dark-theme system) with a flat literal pair list, since the new tokens
are already flat and single-theme."
```

---

**Phase A is now complete and independently shippable**: the app renders in the new palette/type
with the new shell, no dark theme, and a working contrast check. Phase B (primitives) and Phase C
(screens) continue in this same file — see the task list below once appended.
