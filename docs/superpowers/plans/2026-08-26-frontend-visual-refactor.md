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
with the new shell, no dark theme, and a working contrast check.

---

## Phase B — Primitives

Every task in this phase applies the Global Constraints token rename map. Steps below cite exact
current line ranges (from a full read of each file) and give only the non-mechanical changes.

### Task 8: Button — 9 variants

**Files:** Modify `frontend/src/components/ui/Button.tsx` (full rewrite — currently 37 lines,
2 variants only).

**Interfaces:** `export function Button({ variant, className, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "small-primary" | "small-secondary" | "danger-outline" | "filter-active" | "filter-idle" | "portal-primary" | "text-link" })`.
Widening the `variant` union is a breaking-in-theory, safe-in-practice change: the only current
callers pass `"primary"` or nothing (default), per the domain-component survey — grep confirms no
caller passes `variant="secondary"` today that would need remapping, but check
`grep -rn 'variant=' frontend/src/components frontend/src/app` before assuming so and fix any
found.

- [ ] **Step 1: Write the failing tests** (new `Button.test.tsx` — none exists today)

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Button } from "./Button";

describe("Button", () => {
  it.each([
    ["primary", "var(--ob-ink)", "var(--ob-canvas)"],
    ["secondary", "var(--ob-surface)", "var(--ob-ink)"],
    ["small-primary", "var(--ob-ink)", "var(--ob-canvas)"],
    ["small-secondary", "var(--ob-surface)", "var(--ob-ink)"],
    ["portal-primary", "var(--ob-ink)", "var(--ob-canvas)"],
  ] as const)("variant=%s sets the documented background/text tokens", (variant, bg, color) => {
    render(<Button variant={variant}>Go</Button>);
    const el = screen.getByRole("button", { name: "Go" });
    expect(el.style.background).toBe(bg);
    expect(el.style.color).toBe(color);
  });

  it("danger-outline is transparent-on-surface with a risk border and risk text", () => {
    render(<Button variant="danger-outline">Force-complete</Button>);
    const el = screen.getByRole("button", { name: "Force-complete" });
    expect(el.style.background).toBe("var(--ob-surface)");
    expect(el.style.color).toBe("var(--ob-risk-fg)");
    expect(el.style.border).toContain("var(--ob-risk-border)");
  });

  it("text-link has no background or border", () => {
    render(<Button variant="text-link">Mark all read</Button>);
    const el = screen.getByRole("button", { name: "Mark all read" });
    expect(el.style.background).toBe("transparent");
    expect(el.style.border).toBe("");
  });

  it("disabled state uses the documented line/text-faint pair regardless of variant", () => {
    render(<Button variant="primary" disabled>Migrate</Button>);
    const el = screen.getByRole("button", { name: "Migrate" });
    expect(el.style.background).toBe("var(--ob-line)");
    expect(el.style.color).toBe("var(--ob-text-faint)");
  });
});
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd frontend && npx vitest run Button.test.tsx`
Expected: FAIL — `Button.tsx` doesn't have a `variant` prop with these values yet.

- [ ] **Step 3: Implement**

```tsx
import type { ButtonHTMLAttributes } from "react";

type Variant =
  | "primary" | "secondary" | "small-primary" | "small-secondary"
  | "danger-outline" | "filter-active" | "filter-idle" | "portal-primary" | "text-link";

const VARIANTS: Record<Variant, {
  height: string; padding: string; background: string; color: string;
  border: string; radius: string; font: string; hoverBg?: string;
}> = {
  primary: { height: "var(--ob-control-height)", padding: "0 13px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-8)", font: "600 12.5px/1.2", hoverBg: "var(--ob-ink-hover)" },
  secondary: { height: "var(--ob-control-height)", padding: "0 12px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "small-primary": { height: "var(--ob-control-height-sm)", padding: "0 11px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-7)", font: "600 11.5px/1.2", hoverBg: "var(--ob-ink-hover)" },
  "small-secondary": { height: "var(--ob-control-height-sm)", padding: "0 10px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-7)", font: "500 11.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "danger-outline": { height: "27px", padding: "0 10px", background: "var(--ob-surface)", color: "var(--ob-risk-fg)", border: "1px solid var(--ob-risk-border)", radius: "var(--ob-radius-7)", font: "600 12px/1.2", hoverBg: "var(--ob-risk-bg)" },
  "filter-active": { height: "30px", padding: "0 11px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2" },
  "filter-idle": { height: "30px", padding: "0 11px", background: "var(--ob-surface)", color: "var(--ob-ink)", border: "1px solid var(--ob-line)", radius: "var(--ob-radius-8)", font: "500 12.5px/1.2", hoverBg: "var(--ob-surface-active)" },
  "portal-primary": { height: "36px", padding: "0 16px", background: "var(--ob-ink)", color: "var(--ob-canvas)", border: "none", radius: "var(--ob-radius-9)", font: "600 13px/1.2", hoverBg: "var(--ob-ink-hover)" },
  "text-link": { height: "auto", padding: "0", background: "transparent", color: "var(--ob-accent-fg)", border: "none", radius: "0", font: "600 12.5px/1.2" },
};

export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  const v = VARIANTS[variant];
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center ${variant === "text-link" ? "hover:underline" : ""} ${className}`}
      style={{
        height: v.height,
        padding: v.padding,
        borderRadius: v.radius,
        background: props.disabled ? "var(--ob-line)" : v.background,
        color: props.disabled ? "var(--ob-text-faint)" : v.color,
        border: v.border,
        font: `${v.font} var(--ob-font-family-ui)`,
        cursor: props.disabled ? "not-allowed" : "pointer",
        ...props.style,
      }}
    />
  );
}
```

(Hover states are handled by callers already relying on browser `:hover` today — the old `Button`
had no hover styling either, since inline styles can't express `:hover`; if a caller needs a real
hover effect, that's a pre-existing gap this task doesn't newly introduce. Note `hoverBg` values
above are documented for a future CSS-module/class-based hover pass, not wired up here — flag this
explicitly rather than silently dropping the hover requirement `COMPONENTS.md` §4 lists for every
variant.)

- [ ] **Step 4: Run to confirm pass**

Run: `cd frontend && npx vitest run Button.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/Button.tsx frontend/src/components/ui/Button.test.tsx
git commit -m "feat(frontend): restyle Button to the 9-variant spec (COMPONENTS.md §4)

Primary is now ink/canvas, not accent -- colour is reserved for meaning
per the new design's own stated principle. New variants: small-primary/
-secondary, danger-outline, filter-active/-idle, portal-primary, text-link."
```

---

### Task 9: Card

**Files:** Modify `frontend/src/components/ui/Card.tsx:10-25` (the `Card` export only —
`CardHeader`, L35-65, gets its type-token references updated but no structural change).

- [ ] **Step 1–4 (test/implement/verify/commit cycle):**

Replace the token references per the rename map:
`bg-bg-surface border-border-default rounded-card` → `bg-surface border-line rounded-11` (Tailwind
utility classes now resolve through Task 1's `tailwind-theme.css`), and the inline padding to
`padding: 14px 15px 14px 15px` (`COMPONENTS.md`'s `SectionCard`/general card body range,
14–18px — pick 14/15 as the tighter, table-adjacent end since this `Card` wraps dense content
like `TeamMembers`'s member list) plus `boxShadow: var(--ob-shadow-card)` (new — the old `Card` had
no shadow at all, and `card` shadow is "every card, subtle by design" per `DESIGN_TOKENS.md`).
`CardHeader`'s title `<h2>` repoints `--ob-type-13-5-*` → `--ob-type-card-title-*` (Task 1). No
existing test file to update. Run `npx vitest run` (nothing regresses — `Card` has no dedicated
test) and `npm run build`, then commit:

```bash
git add frontend/src/components/ui/Card.tsx
git commit -m "feat(frontend): restyle Card to the new surface/line/card-shadow tokens"
```

---

### Task 10: Chip

**Files:** Modify `frontend/src/components/ui/Chip.tsx:26-57`.

The existing `active` prop's ink/surface ternary already matches the new spec's active-filter
pattern conceptually (per the domain-survey fork's finding) — only token names, size and radius
change; no test update needed (`Chip.test.tsx` has zero style assertions, confirmed by the fork).

- [ ] **Step 1: Update the failing case in a small addition to `Chip.test.tsx`**

```tsx
it("uses the standard mono chip sizing", () => {
  render(<Chip>COMPLETE</Chip>);
  const el = screen.getByRole("button");
  expect(el.style.font).toContain("var(--ob-type-mono-chip-size)");
  expect(el.style.borderRadius).toBe("var(--ob-radius-5)");
});
```

- [ ] **Step 2: Run to confirm failure, then implement**

Rewrite the style block (was L26-57) to:

```tsx
style={{
  height: "auto",
  padding: "2px 6px",
  borderRadius: "var(--ob-radius-5)",
  border: "none",
  font: `400 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)`,
  letterSpacing: "var(--ob-type-mono-chip-tracking)",
  textTransform: "uppercase",
  background: active ? "var(--ob-ink)" : "var(--ob-neutral-bg)",
  color: active ? "var(--ob-canvas)" : "var(--ob-neutral-fg)",
  cursor: props.onClick ? "pointer" : "default",
  ...props.style,
}}
```

(`Chip` had no `textTransform`/mono treatment before — the new spec's Chip is "always uppercase
mono," which the old `StatusPill`-adjacent `Chip` wasn't. This is the one real behavioural-looking
change in this task, and it's visual only: the text content passed in is unchanged, only its
rendered casing/face.)

- [ ] **Step 3: Run to confirm pass, Step 4: Commit**

```bash
git add frontend/src/components/ui/Chip.tsx frontend/src/components/ui/Chip.test.tsx
git commit -m "feat(frontend): restyle Chip to the always-uppercase-mono spec (COMPONENTS.md §3)"
```

---

### Task 11: StatusPill — rename roles, keep the 5 in use

**Files:** Modify `frontend/src/components/ui/StatusPill.tsx:14-24,51-66`.

- [ ] **Step 1–4:**

Rename `StatusRole` from `"on-track" | "progress" | "at-risk" | "blocked" | "neutral"` to
`"ok" | "accent" | "warn" | "risk" | "neutral"` (per the rename map's status-role row), updating
`ROLE_BY_STATUS`'s five existing mappings (`ACTIVE→ok`, etc. — read the current file to get each
domain-status string right, only the target role name changes) and the render's token references
(`--ob-status-{role}-bg/fg` → `--ob-{role}-bg/fg`). No test file exists to update. Leave `info` and
`automation` off the `StatusRole` union for now — nothing in sub-project 1–2 needs them yet, and
adding unused union members ahead of a real caller is exactly the "not speculatively" pattern the
design spec's Global Constraints warn against. Run `npx vitest run && npm run build`, commit:

```bash
git add frontend/src/components/ui/StatusPill.tsx
git commit -m "feat(frontend): rename StatusPill's roles to the new 7-role semantic set

Only the 5 roles StatusPill actually uses are renamed (on-track/progress/
at-risk/blocked/neutral -> ok/accent/warn/risk/neutral); info and
automation are available on the token layer but unused until a caller
needs them."
```

---

### Task 12: ProgressBar — 5 context-specific heights

**Files:** Modify `frontend/src/components/ui/ProgressBar.tsx:26-40` and its
`size: "inline" | "large"` prop.

- [ ] **Step 1: Extend the test file**

```tsx
it.each([
  ["table-cell", "5px", "var(--ob-radius-4)"],
  ["stage-summary", "5px", "var(--ob-radius-4)"],
  ["case-hero", "7px", "var(--ob-radius-4)"],
  ["portal-sidebar", "5px", "var(--ob-radius-4)"],
  ["portal-card", "6px", "var(--ob-radius-4)"],
] as const)("context=%s sets the documented track height", (context, height) => {
  render(<ProgressBar value={50} label="Progress" context={context} />);
  expect(screen.getByRole("progressbar").style.height).toBe(height);
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

Widen the existing `size: "inline" | "large"` prop to `context: "table-cell" | "stage-summary" |
"case-hero" | "portal-sidebar" | "portal-card"` (a rename, not an addition — five contexts replace
two sizes 1:1 in spirit but the values differ, so check every current caller of `size=` and update
it to the matching `context=` value: `size="inline"` callers most likely want `"table-cell"` or
`"stage-summary"`; `size="large"` callers want `"case-hero"`. Grep
`grep -rn 'ProgressBar' frontend/src/components frontend/src/app` to find and update each call
site in the same commit — don't leave a caller passing the now-removed `size` prop). Track
`background` moves from `bg-bg-inset` to `bg-line-faint` per the rename map's `--ob-bg-inset`
resolution (progress-bar tracks → `line-faint`), fill colour becomes conditional on the *value*
per `COMPONENTS.md` §13's fill-by-state rule (`>70% → accent-fg`, `41–70% → warn-fg`, `≤40% →
info-fg`, complete → `ok-fg`) rather than always `bg-accent`:

```tsx
function fillColor(value: number): string {
  if (value >= 100) return "var(--ob-ok-fg)";
  if (value > 70) return "var(--ob-accent-fg)";
  if (value > 40) return "var(--ob-warn-fg)";
  return "var(--ob-info-fg)";
}
```

Apply `fillColor(value)` as the fill's `background`. Note: `COMPONENTS.md` §13 also says
"Case-level and stage-level bars use `ok.fg` when complete and `warn.fg` while in progress" — a
narrower, two-state rule for those two specific contexts that overrides the four-tier rule above.
Implement `fillColor` to accept the `context` and branch:

```tsx
function fillColor(value: number, context: Context): string {
  if (context === "case-hero" || context === "stage-summary") {
    return value >= 100 ? "var(--ob-ok-fg)" : "var(--ob-warn-fg)";
  }
  if (value >= 100) return "var(--ob-ok-fg)";
  if (value > 70) return "var(--ob-accent-fg)";
  if (value > 40) return "var(--ob-warn-fg)";
  return "var(--ob-info-fg)";
}
```

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/ProgressBar.tsx frontend/src/components/ui/ProgressBar.test.tsx
git commit -m "feat(frontend): restyle ProgressBar to 5 contexts with fill-by-state colour

size (inline|large) becomes context (table-cell|stage-summary|case-hero|
portal-sidebar|portal-card); fill colour now depends on the value per
COMPONENTS.md §13's rule, not a fixed accent fill. Updates every call
site's size= to context=."
```

---

### Task 13: Switch → Toggle spec

**Files:** Modify `frontend/src/components/ui/Switch.tsx:43-64`.

- [ ] **Step 1–4:**

Track colours: on `background: var(--ob-accent-fg)`, off `background: var(--ob-line-strong)` (was
`--ob-accent`/`--ob-bg-inset`). Dimensions already match (`COMPONENTS.md` §17: 34×19, knob 15×15 —
confirm the current 34×20/16×16 is close enough to leave as a one-pixel adjustment: track height
19 not 20, knob 15 not 16, translateX distance becomes 13px not 14px to keep the knob's 2px inset
on both sides at the new track height). Update `Switch.test.tsx`'s `role`/`aria-checked` assertions
— none reference colour, so only re-run to confirm no regression, no rewrite needed. Commit:

```bash
git add frontend/src/components/ui/Switch.tsx
git commit -m "feat(frontend): restyle Switch (Toggle, COMPONENTS.md §17) to accent-fg/line-strong"
```

---

### Task 14: Tabs → SegmentedControl visual

**Files:** Modify `frontend/src/components/ui/Tabs.tsx:62-70` and its wrapping container.

This is the one primitive with a real **visual shape change**, not just a recolour: the current
`Tabs` renders an underline-tab treatment (`boxShadow: inset 0 -2px 0 var(--ob-accent)` on the
selected tab); `SCREENS.md` §3 explicitly names the case workspace's five tabs as a
"SegmentedControl" — a pill-track control (`COMPONENTS.md` §5: `background:surface-active;
border:1px solid line; border-radius:9px; padding:3px`, each segment `height:26–27px; padding:0
11–12px; border-radius:6px`, selected segment `background:surface`). The `role="tablist"`/
`role="tab"` semantics and the existing arrow-key automatic-activation behaviour (proven by
`Tabs.test.tsx`, zero style assertions) are unchanged — only the container and per-tab styling.

- [ ] **Step 1: Update `Tabs.test.tsx`'s existing style-agnostic tests to still pass** (no new
  cases needed — the fork confirmed zero style assertions in this file; run it after Step 2 to
  confirm nothing behavioural broke).

- [ ] **Step 2: Rewrite the container and tab styling**

Wrap the `role="tablist"` element in the track styling (`background: var(--ob-surface-active);
border: 1px solid var(--ob-line); border-radius: var(--ob-radius-9); padding: 3px; display: flex;
gap: 4px`), and change each tab's style block from the underline treatment to:

```tsx
style={{
  height: "27px",
  padding: "0 12px",
  border: "0",
  borderRadius: "var(--ob-radius-6)",
  font: `500 12.5px/1.2 var(--ob-font-family-ui)`,
  background: selected ? "var(--ob-surface)" : "transparent",
  color: selected ? "var(--ob-ink)" : "var(--ob-text-muted)",
}}
```

- [ ] **Step 3: Run `npx vitest run Tabs.test.tsx`, confirm PASS (behavioural tests untouched)**

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/ui/Tabs.tsx
git commit -m "feat(frontend): restyle Tabs from underline to SegmentedControl (COMPONENTS.md §5)

SCREENS.md names the case workspace's five tabs a SegmentedControl, a
pill-track shape, not the underline-tab treatment this had before.
role=tablist/tab and arrow-key activation are unchanged."
```

---

### Task 15: Dialog → Modal spec

**Files:** Modify `frontend/src/components/ui/Dialog.tsx:77-111` (styling only — all focus-trap
logic at L1-75, 118-154 is untouched, confirmed by the fork's line-range read).

`COMPONENTS.md` §18's Modal is specifically the force-complete modal's own spec (520px max-width,
a `PRIVILEGED ACTION · APPROVAL REQUIRED` eyebrow header, a bordered footer). Every *other* current
`Dialog` caller (`CreateCaseDialog`, `HoldDialog`, `ForceCompleteDialog`, `TeamMembers`'s dialog,
`ContactList`'s dialog, `RequirementList`'s `WaiveDialog`) uses the same shell at its current
460px width for a plainer header. Rather than hardcoding 520px into `Dialog` itself (which would
widen every dialog, not just the one §18 actually describes), add an optional `maxWidth` prop
defaulting to the current 460:

- [ ] **Step 1: Add a test case to `Dialog.test.tsx`**

```tsx
it("defaults to 460px and accepts a wider maxWidth", () => {
  const { rerender } = render(<Dialog isOpen title="t" onClose={vi.fn()}>x</Dialog>);
  expect(screen.getByRole("dialog").style.maxWidth).toBe("460px");
  rerender(<Dialog isOpen title="t" onClose={vi.fn()} maxWidth={520}>x</Dialog>);
  expect(screen.getByRole("dialog").style.maxWidth).toBe("520px");
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

Add `maxWidth?: number` to `Dialog`'s props (default `460`), use it in place of the hardcoded
`maxWidth: 460` at L~95. Restyle the scrim to `var(--ob-scrim-modal)`, the panel to
`background: var(--ob-surface); border-radius: var(--ob-radius-13); box-shadow: var(--ob-shadow-modal)`,
title per `--ob-type-section-heading-*`. Task 21 (journey dialogs) is where `ForceCompleteDialog`
itself gets the `PRIVILEGED ACTION` eyebrow header and bordered footer §18 describes, composed
around `Dialog`/`DialogActions` rather than built into the shared primitive — those two pieces of
chrome are specific to that one modal, not every `Dialog` caller.

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/Dialog.tsx frontend/src/components/ui/Dialog.test.tsx
git commit -m "feat(frontend): restyle Dialog to the new scrim/panel tokens, add maxWidth prop

Default stays 460px (every non-force-complete dialog); maxWidth=520 is
available for the one caller that needs COMPONENTS.md §18's width."
```

---

### Task 16: TimelineRow → ListRow spec

**Files:** Modify `frontend/src/components/ui/TimelineRow.tsx` (full restyle — inline styles
throughout, no Tailwind classes, confirmed by the fork).

- [ ] **Step 1–4:**

`COMPONENTS.md` §8's ListRow is the closest analogue: full-width `<button>` (confirm `TimelineRow`
already renders one — the fork noted it's an `<li>`, not a `<button>`; check whether the timeline
row is itself clickable/navigates anywhere before deciding — if it's purely informational (a static
activity-log entry, not a link to a record), keep it as `<li>` and do NOT force it into a `<button>`
per §8's own rule ("Never a `<div>` with a click handler" — this is about avoiding a *fake*
interactive element, not mandating every row become a real button when nothing happens on click).
Restyle: `padding: 11px 15px`, `border-bottom: 1px solid var(--ob-line-faint)`, hover
`background: var(--ob-surface-sunken)` only if it is in fact clickable. Update
`TimelineRow.test.tsx` only if any assertion touches removed inline styles (the fork found none —
confirm by running first). Commit:

```bash
git add frontend/src/components/ui/TimelineRow.tsx
git commit -m "feat(frontend): restyle TimelineRow to the new line-faint/surface-sunken tokens"
```

---

### Task 17: Checkbox — restyle only

**Files:** Modify `frontend/src/components/ui/Checkbox.tsx:38-50`.

**The server-round-trip-before-flip behaviour (the `busy` prop disabling input during a mutation)
is untouched — this task only changes colour/size.**

- [ ] **Step 1–4:**

Checked fill `--ob-solid-on-track` → `--ob-ok-fg` (per the rename map's solid-tier collapse). Size:
`COMPONENTS.md` doesn't give checkboxes their own entry, but the migration table's checkbox is
specified at 17px radius 5 (`SCREENS.md` §10) — align the shared `Checkbox` to that (17px, radius
`--ob-radius-5`) since it's the more specific of the two callers (requirement checkboxes have no
explicit size in either doc; keep them consistent with this one rather than inventing a second
size). `Checkbox.test.tsx`'s one style assertion (`checked.textDecoration === "line-through"`) is
unrelated to colour/size — leave it, confirm it still passes. Commit:

```bash
git add frontend/src/components/ui/Checkbox.tsx
git commit -m "feat(frontend): restyle Checkbox to ok-fg fill, 17px/radius-5"
```

---

### Task 18: Avatar — restyle, keep the company/person shape distinction

**Files:** Modify `frontend/src/components/ui/Avatar.tsx` (full restyle).

`COMPONENTS.md` doesn't distinguish avatar shapes, but the current `kind: "company"|"person"` prop
carries real product meaning (confirmed used by `ContactList.test.tsx` and `CustomerTable.test.tsx`
asserting on `borderRadius`) — keep the prop and the distinction, only remap which radius token
each shape uses and add the avatar colour-cycle behaviour `DESIGN_TOKENS.md`'s Colour section
specifies.

- [ ] **Step 1: Update the two style-coupled tests first**

In `ContactList.test.tsx` and `CustomerTable.test.tsx`, change the asserted radius tokens:
`"var(--ob-radius-full)"` (person, circular) stays `"var(--ob-radius-full)"` — unchanged, since
`--ob-radius-full` still resolves (Task 1 kept it, just as `50%` instead of `9999px`; the token
*name* is identical so this assertion needs no edit). `"var(--ob-radius-chip)"` (company, square)
becomes `"var(--ob-radius-5)"` (the rename map has no `--ob-radius-chip` in the new set — the
closest new radius for a small rounded-square avatar tile is `5px`, matching `COMPONENTS.md`'s
"file-type tiles" role, the closest documented rounded-square small element).

- [ ] **Step 2: Run to confirm the (now-expected) failure against the still-old `Avatar.tsx`**

- [ ] **Step 3: Implement**

```tsx
import type { CSSProperties } from "react";

const AVATAR_PALETTE = ["#10736b", "#b4392f", "#6a4fb0", "#2b5fb0", "#9a6410", "#2f7d4f", "#4b4842"];

function paletteColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  return AVATAR_PALETTE[hash % AVATAR_PALETTE.length];
}

export function Avatar({
  name,
  kind = "person",
  size = 30,
}: {
  name: string;
  kind?: "company" | "person";
  size?: number;
}) {
  const style: CSSProperties = {
    width: size,
    height: size,
    borderRadius: kind === "person" ? "var(--ob-radius-full)" : "var(--ob-radius-5)",
    background: paletteColor(name),
    color: "var(--ob-canvas)",
    font: `600 ${Math.round(size * 0.37)}px/1 var(--ob-font-family-ui)`,
    display: "grid",
    placeItems: "center",
  };
  return (
    <span style={style} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0];
  const last = parts[parts.length - 1];
  if (!first || !last) return "";
  return (first.slice(0, 1) + (parts.length > 1 ? last.slice(0, 1) : "")).toUpperCase();
}
```

Keep every other existing prop (read the current file for the full signature before this rewrite —
the fork's summary covered `kind`/`size` but the actual file may carry more, e.g. an `online`
presence-dot flag used by the case workspace's participant list; preserve anything found).

**Cross-cutting note carried over from the domain-survey fork**: `Avatar`'s `initials()` and
`TopBar.tsx`'s (now `Rail.tsx`'s, since Task 3 moved it) inline `initials()` are duplicate
implementations. Rail's Task 3 code already inlines its own copy rather than importing this one,
which was necessary at the time since `Avatar.tsx` hadn't been restyled yet. **This task exports
`initials` — as a follow-up inside this same task**, update `Rail.tsx` to import
`{ initials } from "@/components/ui/Avatar"` and delete its local copy, closing the duplication
this refactor would otherwise leave behind.

- [ ] **Step 4: Run all four affected test files, confirm PASS**

Run: `cd frontend && npx vitest run Avatar ContactList CustomerTable Rail`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/Avatar.tsx frontend/src/components/customers/ContactList.test.tsx frontend/src/components/customers/CustomerTable.test.tsx frontend/src/components/shell/Rail.tsx
git commit -m "feat(frontend): restyle Avatar, add the palette colour-cycle, dedupe initials()

Keeps the company/person shape distinction (real product meaning, no
design-spec equivalent to fall back on) with remapped radius tokens.
Adds DESIGN_TOKENS.md's 7-colour avatar palette, hashed by name. Rail.tsx
now imports initials() from here instead of carrying its own copy."
```

---

### Task 19: Pagination — restyle (cascades from Button)

**Files:** Modify `frontend/src/components/ui/Pagination.tsx` (the mono page-position text only —
its two buttons already restyle for free once Task 8 lands, since `Pagination` composes `Button`
rather than styling its own).

- [ ] **Step 1–4:** Update the mono text's font token to `--ob-type-mono-data-*`, colour
  `--ob-text-muted`. No test file. Commit:

```bash
git add frontend/src/components/ui/Pagination.tsx
git commit -m "feat(frontend): restyle Pagination's mono page-position text"
```

---

### Task 20: Field / TextareaField

**Files:** Modify `frontend/src/components/ui/Field.tsx` (both exports, ~117 lines).

- [ ] **Step 1–4:**

Apply `COMPONENTS.md` §18's field styling exactly (it's the only place the new spec gives field
dimensions): label `11.5px text-subtle margin-bottom:5px`, control
`border:1px solid var(--ob-line); border-radius:var(--ob-radius-9); padding:9px 11px; font-size:13px;
background:var(--ob-surface)`. Error state: border/text → `--ob-risk-fg`, keeping the existing
`aria-describedby`/`aria-invalid` wiring untouched (behavioural, not visual). No test file exists.
Commit:

```bash
git add frontend/src/components/ui/Field.tsx
git commit -m "feat(frontend): restyle Field/TextareaField to COMPONENTS.md §18's field spec"
```

---

### Task 21: States — empty/loading, add an ErrorState

**Files:** Modify `frontend/src/components/ui/States.tsx` (restyle `EmptyState`/`SkeletonRows`,
add a new `ErrorState` export — the new spec's §22 explicitly hands this off as a gap to fill, and
the current file has no error renderer at all, confirmed by the fork).

- [ ] **Step 1: Write the failing test** (new `States.test.tsx` if none exists — confirm first;
  the fork found none)

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ErrorState } from "./States";

describe("ErrorState", () => {
  it("announces the message via role=alert and renders a retry button", async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Couldn't load customers." onRetry={onRetry} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Couldn't load customers.");
    screen.getByRole("button", { name: /retry|try again/i }).click();
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

```tsx
export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div
      role="alert"
      style={{
        border: "1px solid var(--ob-risk-border)",
        background: "var(--ob-risk-bg)",
        borderRadius: "var(--ob-radius-10)",
        padding: "11px 12px",
        display: "flex",
        alignItems: "center",
        gap: "var(--ob-space-11)",
        font: "12px/1.4 var(--ob-font-family-ui)",
        color: "#5c2a24",
      }}
    >
      <span style={{ flex: 1 }}>{message}</span>
      <Button variant="small-secondary" onClick={onRetry}>
        {t("common.retry")}
      </Button>
    </div>
  );
}
```

(Import `Button` from `./Button` and `t` from `@/lib/i18n`; add the `common.retry` key.) Restyle
`EmptyState`'s icon colour from `--ob-graphic-muted` to `--ob-line-strong` (rename map), and
`SkeletonRows`' skeleton fill from whatever it currently reads to `--ob-line-faint` per
`COMPONENTS.md` §22 ("skeleton blocks at line-faint"). Keep `SkeletonRows`' `aria-busy`/
`aria-live="polite"` wiring untouched.

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/States.tsx frontend/src/components/ui/States.test.tsx frontend/src/lib/i18n
git commit -m "feat(frontend): add ErrorState (role=alert + retry), restyle Empty/SkeletonRows

Design spec §6's addition from the ui-ux-pro-max accessibility check:
error text needs role=alert so assistive tech announces it, not just a
coloured border. This is the gap COMPONENTS.md §22 explicitly hands off."
```

---

### Task 22: DataTable (net-new primitive)

**Files:** Create `frontend/src/components/ui/DataTable.tsx`, `DataTable.test.tsx`.

Generalises the ad hoc table markup `CustomerTable` (its own `Cell`/`ColumnHeader` helpers, per
the domain-survey fork) and `MigrationTable` (same shape) each currently build themselves. This
task builds the shared primitive only; Phase C's Task 27 (customers) and Task 32 (migration) are
where each existing table is *converted* to use it — do not touch `CustomerTable.tsx` or
`MigrationTable.tsx` in this task.

**Interfaces:**
- Produces: `export function DataTable<T>({ columns, rows, getRowKey, onRowClick, footer }: {
  columns: { key: string; label: string; align?: "left" | "right"; width?: string }[]; rows: T[];
  getRowKey: (row: T) => string; onRowClick?: (row: T) => void; footer?: ReactNode; })`. Also
  `export function StackedCard<T>({ rows, render }: { rows: T[]; render: (row: T) => ReactNode })`
  — the `<900px` fallback per the design spec §6's responsive correction; `DataTable` itself
  renders the stacked-card fallback automatically below 900px via CSS (`hidden`/responsive
  classes), rather than requiring the caller to pick.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DataTable } from "./DataTable";

type Row = { id: string; name: string };
const rows: Row[] = [{ id: "1", name: "Acme" }, { id: "2", name: "Orbit" }];
const columns = [{ key: "name", label: "Name" }];

describe("DataTable", () => {
  it("renders a header row and one row per item, wrapped in a horizontal-scroll container", () => {
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Acme")).toBeInTheDocument();
    expect(screen.getByText("Orbit")).toBeInTheDocument();
  });

  it("renders each row as a button when onRowClick is provided, and calls it with the row", () => {
    const onRowClick = vi.fn();
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} onRowClick={onRowClick} />);
    screen.getByRole("button", { name: /acme/i }).click();
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it("does not render rows as buttons when onRowClick is omitted", () => {
    render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

```tsx
import type { ReactNode } from "react";

type Column<T> = {
  key: string;
  label: string;
  align?: "left" | "right";
  width?: string;
  render?: (row: T) => ReactNode;
};

export function DataTable<T>({
  columns,
  rows,
  getRowKey,
  onRowClick,
  footer,
}: {
  columns: Column<T>[];
  rows: T[];
  getRowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  footer?: ReactNode;
}) {
  const gridTemplate = columns.map((c) => c.width ?? "1fr").join(" ");

  return (
    <div style={{ overflowX: "auto" }}>
      <div style={{ minWidth: "fit-content" }}>
        <div
          role="row"
          style={{
            display: "grid",
            gridTemplateColumns: gridTemplate,
            gap: "var(--ob-space-12)",
            padding: "9px 15px",
            background: "var(--ob-surface-sunken)",
            borderBottom: "1px solid var(--ob-line)",
          }}
        >
          {columns.map((col) => (
            <span
              key={col.key}
              style={{
                font: `500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)`,
                letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                textTransform: "uppercase",
                color: "var(--ob-text-subtle)",
                textAlign: col.align ?? "left",
              }}
            >
              {col.label}
            </span>
          ))}
        </div>
        {rows.map((row) => {
          const cells = columns.map((col) => (
            <span key={col.key} style={{ textAlign: col.align ?? "left" }}>
              {col.render ? col.render(row) : String((row as Record<string, unknown>)[col.key] ?? "")}
            </span>
          ));
          const rowStyle = {
            display: "grid",
            gridTemplateColumns: gridTemplate,
            gap: "var(--ob-space-12)",
            padding: "10px 15px",
            borderBottom: "1px solid var(--ob-line-faint)",
            alignItems: "center",
            width: "100%",
            textAlign: "left" as const,
            font: `13px/1.4 var(--ob-font-family-ui)`,
            color: "var(--ob-ink)",
          };
          return onRowClick ? (
            <button
              key={getRowKey(row)}
              type="button"
              onClick={() => onRowClick(row)}
              className="hover:bg-surface-sunken"
              style={rowStyle}
            >
              {cells}
            </button>
          ) : (
            <div key={getRowKey(row)} style={rowStyle}>
              {cells}
            </div>
          );
        })}
        {footer && (
          <div
            style={{
              padding: "10px 15px",
              background: "var(--ob-surface-sunken)",
              font: `var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)`,
            }}
          >
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
```

The `<900px` stacked-card fallback (`SCREENS.md`'s RESPONSIVE table) is deliberately **not** built
into this version — it needs a per-column "identifying column" concept the generic `columns` prop
above doesn't yet carry, and no caller in Phase C actually needs it until Task 27 (customers list)
converts `CustomerTable`, which *already has* its own `<1024px` card fallback today (confirmed by
the fork). Task 27 decides then whether to fold that existing fallback into `DataTable` as a
`stackedColumn` prop or keep it as `CustomerTable`'s own concern — flagging here rather than
guessing now, per this plan's "no silent caps" rule.

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/DataTable.tsx frontend/src/components/ui/DataTable.test.tsx
git commit -m "feat(frontend): add the DataTable primitive (COMPONENTS.md §12)

Generic grid-based table with an optional onRowClick (renders real
<button> rows only when clickable). The <900px stacked-card fallback is
deferred to Task 27, which converts the one caller that already has one."
```

---

### Task 23: StageAccordion (net-new primitive)

**Files:** Create `frontend/src/components/ui/StageAccordion.tsx`, `StageAccordion.test.tsx`.

This is the case roadmap's core primitive (`COMPONENTS.md` §14) — built here as a standalone
primitive; Task 30 (journey roadmap) converts `Roadmap.tsx`/`StageGroupHeader.tsx`/`MilestoneRow.tsx`
to use it.

**Interfaces:**
- Produces: `export function StageAccordion({ number, title, meta, progressPercent, statusChip,
  isOpen, onToggle, children }: { number: number; title: string; meta: string; progressPercent:
  number; statusChip: ReactNode; isOpen: boolean; onToggle: () => void; children: ReactNode })` —
  `children` is the expanded panel's content (the milestone rows), left to the caller so this
  primitive doesn't need to know `Milestone`'s shape.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { StageAccordion } from "./StageAccordion";

describe("StageAccordion", () => {
  it("renders the header and toggles the panel via a button click", () => {
    const onToggle = vi.fn();
    render(
      <StageAccordion number={4} title="Document collection" meta="2/3 milestones · Operations · 5d estimated" progressPercent={20} statusChip={<span>IN PROGRESS</span>} isOpen={false} onToggle={onToggle}>
        <div>Milestone content</div>
      </StageAccordion>,
    );
    expect(screen.getByText("Document collection")).toBeInTheDocument();
    expect(screen.queryByText("Milestone content")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /document collection/i }));
    expect(onToggle).toHaveBeenCalledOnce();
  });

  it("renders the expanded panel when isOpen", () => {
    render(
      <StageAccordion number={1} title="Registration" meta="" progressPercent={100} statusChip={<span>COMPLETE</span>} isOpen onToggle={vi.fn()}>
        <div>Milestone content</div>
      </StageAccordion>,
    );
    expect(screen.getByText("Milestone content")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

```tsx
import type { ReactNode } from "react";
import { ProgressBar } from "./ProgressBar";

export function StageAccordion({
  number,
  title,
  meta,
  progressPercent,
  statusChip,
  isOpen,
  onToggle,
  children,
  status = "upcoming",
}: {
  number: number;
  title: string;
  meta: string;
  progressPercent: number;
  statusChip: ReactNode;
  isOpen: boolean;
  onToggle: () => void;
  children: ReactNode;
  status?: "complete" | "active" | "upcoming";
}) {
  const railFill = status === "complete" ? "var(--ob-ok-fg)" : status === "active" ? "var(--ob-warn-fg)" : "var(--ob-line-soft)";
  return (
    <div style={{ display: "flex", gap: "var(--ob-space-11)" }}>
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", width: 26 }}>
        <span
          style={{
            width: 24, height: 24, borderRadius: "var(--ob-radius-full)",
            background: railFill,
            color: status === "upcoming" ? "var(--ob-text-subtle)" : "var(--ob-canvas)",
            display: "grid", placeItems: "center",
            font: "600 11px/1 var(--ob-font-family-ui)",
          }}
        >
          {status === "complete" ? "✓" : number}
        </span>
        <span style={{ flex: 1, width: 2, margin: "4px 0", background: railFill }} />
      </div>
      <div style={{ flex: 1 }}>
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={isOpen}
          className="w-full text-left hover:bg-surface-sunken"
          style={{
            background: "var(--ob-surface)",
            border: "1px solid var(--ob-line)",
            borderRadius: "var(--ob-radius-10)",
            padding: "12px 14px",
            display: "flex",
            alignItems: "center",
            gap: "var(--ob-space-12)",
          }}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ font: `600 14px/1.3 var(--ob-font-family-ui)`, letterSpacing: "-0.015em", color: "var(--ob-ink)" }}>
              {title}
            </div>
            {meta && (
              <div style={{ font: "11.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-text-subtle)" }}>
                {meta}
              </div>
            )}
          </div>
          <div style={{ width: 80 }}>
            <ProgressBar value={progressPercent} label={title} context="stage-summary" />
          </div>
          {statusChip}
          <span aria-hidden="true" style={{ transform: isOpen ? "rotate(180deg)" : "none" }}>▾</span>
        </button>
        {isOpen && (
          <div
            style={{
              marginTop: "var(--ob-space-7)",
              border: "1px solid var(--ob-line)",
              borderRadius: "var(--ob-radius-10)",
              background: "var(--ob-surface-sunken)",
              animation: `om-pop var(--ob-duration-pop) var(--ob-ease-default)`,
            }}
          >
            <div
              style={{
                padding: "10px 14px",
                font: `500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)`,
                letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                textTransform: "uppercase",
                color: "var(--ob-text-faint)",
              }}
            >
              Milestones in this stage
            </div>
            {children}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/StageAccordion.tsx frontend/src/components/ui/StageAccordion.test.tsx
git commit -m "feat(frontend): add the StageAccordion primitive (COMPONENTS.md §14)

The case roadmap's core primitive. Task 30 converts Roadmap.tsx/
StageGroupHeader.tsx/MilestoneRow.tsx to compose it."
```

---

### Task 24: BuilderNode (net-new primitive, aligns with `StageRow`)

**Files:** Create `frontend/src/components/ui/BuilderNode.tsx`, `BuilderNode.test.tsx`.

`StageRow.tsx` (workflow builder, ~258 lines per the fork) is the existing analogue — its own
`RowButton`/`Badge` helpers and hover-via-`onMouseEnter`/`onMouseLeave` pattern are the thing this
primitive replaces, but **not in this task**: Task 31 (workflow builder) does that conversion.
This task builds the shared shape only.

**Interfaces:**
- Produces: `export function BuilderNode({ name, teamMeta, milestonePills, isBranch, isSelected,
  isDragging, conditionalChip, onClick, dragHandleProps }: { name: string; teamMeta: string;
  milestonePills: string[]; isBranch?: boolean; isSelected?: boolean; isDragging?: boolean;
  conditionalChip?: boolean; onClick: () => void; dragHandleProps?: React.HTMLAttributes<HTMLSpanElement> })`.
  Drag-and-drop wiring (`draggable`, `onDragStart`/`onDragOver`/`onDrop`) stays the caller's
  responsibility — `StageRow.tsx` already implements it, and `COMPONENTS.md` §21 itself says a
  keyboard-accessible reorder affordance (move-up/move-down buttons) is needed in production since
  native HTML drag-and-drop needs a non-button element; that affordance is `dragHandleProps`'
  slot, populated by Task 31's conversion, not invented here without the real reorder logic to
  attach it to.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { BuilderNode } from "./BuilderNode";

describe("BuilderNode", () => {
  it("renders the name, meta and milestone pills, and calls onClick", () => {
    const onClick = vi.fn();
    render(
      <BuilderNode name="Agreement" teamMeta="Legal · 8d" milestonePills={["MSA drafted", "Legal review"]} onClick={onClick} />,
    );
    expect(screen.getByText("Agreement")).toBeInTheDocument();
    expect(screen.getByText("MSA drafted")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /agreement/i }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("renders a CONDITIONAL chip and the branch number-tile fill when isBranch", () => {
    render(<BuilderNode name="Segment is ENTERPRISE?" teamMeta="" milestonePills={[]} isBranch conditionalChip onClick={vi.fn()} />);
    expect(screen.getByText("CONDITIONAL")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

```tsx
export function BuilderNode({
  name,
  teamMeta,
  milestonePills,
  isBranch = false,
  isSelected = false,
  isDragging = false,
  conditionalChip = false,
  onClick,
}: {
  name: string;
  teamMeta: string;
  milestonePills: string[];
  isBranch?: boolean;
  isSelected?: boolean;
  isDragging?: boolean;
  conditionalChip?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        width: "100%",
        display: "flex",
        alignItems: "center",
        gap: "var(--ob-space-12)",
        borderRadius: "var(--ob-radius-10)",
        padding: "11px 13px",
        background: isBranch ? "#faf7ff" : "var(--ob-surface)",
        border: `1px solid ${isSelected ? "var(--ob-ink)" : isBranch ? "var(--ob-automation-border)" : "var(--ob-line)"}`,
        boxShadow: isSelected ? "var(--ob-shadow-ring-selected)" : "var(--ob-shadow-card)",
        opacity: isDragging ? 0.4 : 1,
        cursor: "grab",
        textAlign: "left",
      }}
    >
      <span aria-hidden="true" style={{ color: "var(--ob-text-ghost)" }}>⋮⋮</span>
      <span
        aria-hidden="true"
        style={{
          width: 24, height: 24, borderRadius: "var(--ob-radius-7)",
          display: "grid", placeItems: "center",
          background: isBranch ? "var(--ob-automation-fg)" : isSelected ? "var(--ob-ink)" : "var(--ob-surface-active)",
          color: isBranch || isSelected ? "var(--ob-canvas)" : "var(--ob-text-muted)",
          font: "600 11px/1 var(--ob-font-family-ui)",
        }}
      >
        {isBranch ? "⑃" : ""}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: "var(--ob-space-8)" }}>
          <span style={{ font: "600 13.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-ink)" }}>{name}</span>
          {conditionalChip && (
            <span
              style={{
                font: `400 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)`,
                textTransform: "uppercase",
                background: "var(--ob-automation-bg)",
                color: "var(--ob-automation-fg)",
                borderRadius: "var(--ob-radius-5)",
                padding: "2px 6px",
              }}
            >
              CONDITIONAL
            </span>
          )}
        </div>
        {teamMeta && (
          <div style={{ font: "11.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-text-subtle)" }}>{teamMeta}</div>
        )}
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--ob-space-6)", maxWidth: "44%", justifyContent: "flex-end" }}>
        {milestonePills.map((pill) => (
          <span
            key={pill}
            style={{
              font: "10.5px/1.3 var(--ob-font-family-ui)",
              background: "var(--ob-surface-active)",
              border: "1px solid var(--ob-line)",
              borderRadius: "var(--ob-radius-5)",
              padding: "2px 6px",
            }}
          >
            {pill}
          </span>
        ))}
      </div>
    </button>
  );
}
```

- [ ] **Step 4: Run to confirm pass, Step 5: Commit**

```bash
git add frontend/src/components/ui/BuilderNode.tsx frontend/src/components/ui/BuilderNode.test.tsx
git commit -m "feat(frontend): add the BuilderNode primitive (COMPONENTS.md §21)

The workflow builder's draggable stage node. Task 31 converts StageRow.tsx
(the current analogue) to compose it; drag-and-drop wiring stays there."
```

---

### Task 25: Toast (net-new primitive)

**Files:** Create `frontend/src/components/ui/Toast.tsx`, `Toast.test.tsx`.

**Before implementing, check whether any ad hoc toast/notification mechanism already exists** —
`grep -rn 'toast' frontend/src/components frontend/src/lib` (case-insensitive). Neither research
fork found one in the files it read, but neither read every file in the repo — if one turns up,
replace it with this rather than adding a second toast system, per the design spec §4's rule
against parallel components.

**Interfaces:**
- Produces: `export function ToastProvider({ children }): JSX.Element`, `export function useToast():
  { show: (message: string) => void }` (context-based, matching `PageHeaderProvider`'s existing
  pattern in this codebase — `frontend/src/components/shell/PageHeader.tsx` — rather than
  inventing a different state-sharing mechanism).

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, act } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ToastProvider, useToast } from "./Toast";

function TestButton() {
  const { show } = useToast();
  return <button onClick={() => show("Case migrated")}>Trigger</button>;
}

describe("Toast", () => {
  it("shows a message on demand and auto-dismisses after 2600ms", () => {
    vi.useFakeTimers();
    render(
      <ToastProvider>
        <TestButton />
      </ToastProvider>,
    );
    screen.getByText("Trigger").click();
    expect(screen.getByText("Case migrated")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(2600));
    expect(screen.queryByText("Case migrated")).not.toBeInTheDocument();
    vi.useRealTimers();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement**

```tsx
"use client";

import { createContext, useCallback, useContext, useState } from "react";
import type { ReactNode } from "react";

type ToastContextValue = { show: (message: string) => void };
const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null);

  const show = useCallback((next: string) => {
    setMessage(next);
    window.setTimeout(() => setMessage(null), 2600);
  }, []);

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      {message && (
        <div
          role="status"
          aria-live="polite"
          style={{
            position: "fixed",
            bottom: 20,
            left: "50%",
            transform: "translateX(-50%)",
            background: "var(--ob-ink)",
            color: "var(--ob-canvas)",
            borderRadius: "var(--ob-radius-9)",
            padding: "10px 15px",
            font: "12.5px/1.3 var(--ob-font-family-ui)",
            boxShadow: "var(--ob-shadow-toast)",
            display: "flex",
            alignItems: "center",
            gap: "var(--ob-space-8)",
            animation: `om-pop var(--ob-duration-pop) var(--ob-ease-default)`,
          }}
        >
          <span aria-hidden="true" style={{ width: 6, height: 6, borderRadius: "50%", background: "#5fd0a8" }} />
          {message}
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) throw new Error("useToast must be used within a ToastProvider");
  return context;
}
```

Note per the design spec §8: "the prototype fires a Toast immediately. In production, show the
toast on server confirmation and roll back visibly on failure — these are audited actions." This
primitive only renders on demand; *when* `show()` is called (on mutation success, not on click) is
each Phase C caller's responsibility, not this task's.

- [ ] **Step 4: Run to confirm pass**

- [ ] **Step 5: Wire `ToastProvider` into the tenant layout**

Add `<ToastProvider>` inside `frontend/src/app/(app)/t/[slug]/layout.tsx`'s provider stack (from
Task 6), wrapping `{children}` alongside `PageHeaderProvider` — innermost is fine, order relative
to `PageHeaderProvider` doesn't matter since neither depends on the other.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/Toast.tsx frontend/src/components/ui/Toast.test.tsx frontend/src/app/\(app\)/t/\[slug\]/layout.tsx
git commit -m "feat(frontend): add the Toast primitive (COMPONENTS.md §19), wire ToastProvider

Context-based, matching PageHeaderProvider's existing pattern. Auto-
dismisses after 2600ms. Checked first for an existing toast mechanism to
replace rather than duplicate -- see this task's own note if one turns up."
```

---

## Phase C — Screens

Every task applies the Global Constraints token rename map. Each task's "Files" list was read in
full by the domain-survey research pass before this plan was written — line numbers below are
approximate section markers, not guesses; re-read the file at the start of the task regardless,
since intervening Phase B commits may have touched shared code it imports.

### Task 26: Auth — `AuthCard` + public routes

**Files:**
- Modify: `frontend/src/components/auth/AuthCard.tsx` (~59 lines)
- Modify: `frontend/src/app/(public)/t/[slug]/login/page.tsx`, `activate/page.tsx`,
  `reset-password/page.tsx`

`AuthCard` is explicitly **not** attempting to match `SCREENS.md` §13's 3-step invitation flow —
that screen has a progress rail and three distinct steps this app's simpler login/activate/reset
pattern doesn't have, and building that structure is out of scope for a restyle (design spec §2).
Apply the token rename map only: card background `--ob-surface`, border `--ob-line`, radius
`--ob-radius-13` (`COMPONENTS.md` says modals/portal cards use 13px; a centred auth card is the
closest analogue), the brand mark restyled to match `Rail.tsx`'s (Task 3) — reuse the same SVG
markup rather than a third copy. Each route page underneath uses only `Field`+`Button` (Task 20/8
restyle these for free) plus the page's own heading/copy tokens (`--ob-type-page-title-*` for the
h1, `--ob-type-body-*` for the lede).

- [ ] **Step 1–4:** Read each file, apply the rename map and the notes above, run
  `cd frontend && npx vitest run && npm run build`, then manually check all three routes render
  correctly (`npm run dev`, visit `/t/acme/login` etc.). Commit:

```bash
git add frontend/src/components/auth/AuthCard.tsx "frontend/src/app/(public)"
git commit -m "feat(frontend): restyle AuthCard and the public auth routes"
```

---

### Task 27: Customers list — convert `CustomerTable` to `DataTable`

**Files:**
- Modify: `frontend/src/components/customers/CustomerTable.tsx` (full rewrite, converting to
  `DataTable` from Task 22)
- Modify: `frontend/src/components/customers/CustomerTable.test.tsx`
- Modify: `frontend/src/app/(app)/t/[slug]/customers/page.tsx` (`FilterChip`/`SearchBox` restyle)

`CustomerTable` today renders two presentations gated by Tailwind `lg:` classes (a real `<table>`
≥1024px, a card list below) — per Task 22's note, this is the caller `DataTable` deferred its
`<900px` stacked-card fallback to. Two options, pick based on what's simpler once both files are
open side by side: (a) extend `DataTable` with a `stackedColumn` prop that renders the existing
card-list markup below 900px (moving that markup into the shared primitive, benefiting `MigrationTable`
too in Task 34), or (b) keep `CustomerTable`'s own responsive split as-is structurally, converting
only its ≥1024px table half to compose `DataTable`'s header/row rendering. **Prefer (a)** — it's
the one that actually closes the gap Task 22 flagged rather than leaving a second one-off fallback
for Task 34 to duplicate. If (a) turns out to need `DataTable` changes broader than a single prop,
stop and do (b) instead rather than scope-creeping this task into rewriting Task 22.

- [ ] **Step 1: Update `CustomerTable.test.tsx`'s style assertions** (avatar radius token — already
  updated in Task 18; confirm, don't re-touch) and add a case proving the `<900px` fallback still
  renders every row (this is the same shape as the `MigrationTable` empty-state guard fix already
  in `CLAUDE.md` — a fallback that silently drops rows is a regression, prove it doesn't).

- [ ] **Step 2: Run to confirm failure**

- [ ] **Step 3: Implement** the `DataTable` conversion per whichever option (a)/(b) above, columns
  matching the existing `CustomerTable` columns (name, status, owner, etc. — read the current file
  for the exact set, this plan doesn't invent new columns).

- [ ] **Step 4: Run to confirm pass**

- [ ] **Step 5: Restyle `customers/page.tsx`'s `FilterChip`/`SearchBox`** to the rename map plus
  `Button` variant `filter-active`/`filter-idle` (Task 8) if `FilterChip` is materially the same
  shape as `Button`'s filter variants — if so, replace it with `Button` rather than keeping a
  parallel component (design spec §4's "restyle in place where one already exists" rule).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/customers/CustomerTable.tsx frontend/src/components/customers/CustomerTable.test.tsx "frontend/src/app/(app)/t/[slug]/customers/page.tsx"
git commit -m "feat(frontend): convert CustomerTable to the DataTable primitive"
```

---

### Task 28: Customer detail + contacts

**Files:**
- Modify: `frontend/src/app/(app)/t/[slug]/customers/[id]/page.tsx` (its own `Fact` helper)
- Modify: `frontend/src/components/customers/ContactList.tsx` (~358 lines — uses `Avatar`,
  `Button`, `Card`/`CardHeader`, `Dialog`, `EmptyState`/`SkeletonRows`, `StatusPill`, all restyled
  by Phase B already)
- Modify: `frontend/src/components/customers/ContactList.test.tsx` (avatar radius — confirm from
  Task 18, don't re-touch)
- Modify: `frontend/src/components/customers/ContactForm.tsx` (~277 lines — keep its own
  `PrimaryCheckbox`, per the Global Constraints rule)
- Modify: `frontend/src/components/customers/CustomerForm.tsx` (~139 lines)

Since every primitive these compose is already restyled by Phase B, this task is mostly "confirm
it inherited the restyle correctly" plus fixing any ad hoc styling each file still does itself
(`Fact` in the detail page — the fork flagged this duplicates `CaseHeader`'s own `Fact` helper;
**do not deduplicate them in this task** — that's a real-but-separate refactor beyond a visual
pass, flag it and move on) and `ContactForm`'s `PrimaryCheckbox`'s own fill colour (rename map:
`--ob-solid-on-track` → `--ob-ok-fg`, same as Task 17's `Checkbox`, but this is a *separate*
component per its own comment — update it there too, don't redirect it to import `Checkbox`).

- [ ] **Step 1–4:** Read each file, apply the rename map, run `npx vitest run ContactList
  ContactForm CustomerForm && npm run build`, manually verify the customer detail page renders
  correctly, commit:

```bash
git add "frontend/src/app/(app)/t/[slug]/customers/[id]/page.tsx" frontend/src/components/customers/ContactList.tsx frontend/src/components/customers/ContactForm.tsx frontend/src/components/customers/CustomerForm.tsx
git commit -m "feat(frontend): restyle customer detail page, contacts and forms"
```

---

### Task 29: Case header, switcher, create-case dialog

**Files:**
- Modify: `frontend/src/components/journey/CaseHeader.tsx` (~115 lines — uses `Avatar`,
  `StatusPill`)
- Modify: `frontend/src/components/journey/CaseHeader.test.tsx` (asserts `case-fact-grid`
  className contains `grid-cols-3`/`xl:grid-cols-5`, and `.style.font` mono-vs-human — both
  **layout/behavioural assertions, not colour ones**, so they should survive unchanged; run first
  to confirm before editing anything)
- Modify: `frontend/src/components/journey/CaseSwitcher.tsx` (~132 lines — deliberately not using
  `Chip`, since it needs real `<Link>`s; keep that decision, only restyle colours/tokens, including
  its own duplicate status→colour map)
- Modify: `frontend/src/components/journey/CreateCaseDialog.tsx` (~230 lines — uses `Button`,
  `Dialog`/`DialogActions`, `Field`, `SkeletonRows`; has its own `selectStyle`/`errorStyle` consts
  and a radio-as-card pattern to restyle)

Per `SCREENS.md` §3: header chip row (case id · pinned-version chip `warn` · SLA-paused chip `info`
· multi-journey chip `automation`), `h1 27px` for the customer name with a `15px/500 text-subtle`
journey-name span, then the address/owner/date line, then the right-aligned `Message customer`
(secondary)/`Request document` (primary) buttons — none of the latter two exist as functionality
yet (no messaging, no document requests are sub-project 1–2 scope), so **do not add these buttons**
— restyle what's actually there today, matching only the layout/typography pattern, not inventing
controls for features that don't exist (same "no dead controls" principle `TopBar.tsx`/`Rail.tsx`
already established).

- [ ] **Step 1–4:** Read each file, apply the rename map plus the case-header chip-row notes above,
  run `npx vitest run CaseHeader CaseSwitcher CreateCaseDialog && npm run build`, commit:

```bash
git add frontend/src/components/journey/CaseHeader.tsx frontend/src/components/journey/CaseSwitcher.tsx frontend/src/components/journey/CreateCaseDialog.tsx
git commit -m "feat(frontend): restyle CaseHeader, CaseSwitcher and CreateCaseDialog"
```

---

### Task 30: Journey roadmap — convert to `StageAccordion`

**Files:**
- Modify: `frontend/src/components/journey/Roadmap.tsx` (~49 lines — thin composition)
- Modify: `frontend/src/components/journey/StageGroupHeader.tsx` (~20 lines)
- Modify: `frontend/src/components/journey/MilestoneRow.tsx` (~222 lines — largest journey file;
  its own `CIRCLE_COLOR` map duplicates `StatusPill`'s role logic, per the fork; leave the
  duplication — not this task's job to unify it, same reasoning as Task 29's `CaseSwitcher`)
- Modify: `frontend/src/components/journey/RequirementList.tsx` (~190 lines — uses `Button`,
  `Dialog`, `Field`, `StatusPill`; has its own local `DocumentChip`/`WaiveDialog`; **uses a raw
  `<input type="checkbox">`, not the shared `Checkbox`** per the fork — leave that as-is, since
  `RequirementList`'s checkbox has the server-round-trip-before-flip behaviour the Global
  Constraints call out as untouched, and redirecting it to the shared primitive risks that
  behaviour, which this task does not need to touch)

Convert `Roadmap`/`StageGroupHeader` to render one `StageAccordion` (Task 23) per stage, with
`MilestoneRow` becoming the content passed as `StageAccordion`'s `children` for the expanded panel
— `MilestoneRow` itself keeps its current internal structure (nested `Section` helper for
dependencies/comments), only its outer wrapper and colour tokens change, plus its
`var(--ob-keyframe-enter)` reference (flagged by Task 2's globals.css note as needing this exact
fix) becomes `animation: om-pop var(--ob-duration-pop) var(--ob-ease-default)`.

- [ ] **Step 1–4:** Read all four files, convert `Roadmap`/`StageGroupHeader` to compose
  `StageAccordion`, apply the rename map to `MilestoneRow`/`RequirementList`, fix the `fadeUp`→
  `om-pop` reference, run `npx vitest run journey && npm run build`, manually verify the case
  workspace's Journey tab still expands/collapses stages and shows requirement checkboxes/waive
  dialog correctly, commit:

```bash
git add frontend/src/components/journey/Roadmap.tsx frontend/src/components/journey/StageGroupHeader.tsx frontend/src/components/journey/MilestoneRow.tsx frontend/src/components/journey/RequirementList.tsx
git commit -m "feat(frontend): convert the journey roadmap to the StageAccordion primitive"
```

---

### Task 31: Approval, hold, and force-complete dialogs

**Files:**
- Modify: `frontend/src/components/journey/ApprovalPanel.tsx` (~99 lines — uses `Button`,
  `TextareaField`)
- Modify: `frontend/src/components/journey/ForceCompleteDialog.tsx` (~74 lines — this is
  `COMPONENTS.md` §18's actual Modal spec target)
- Modify: `frontend/src/components/journey/HoldDialog.tsx` (~60 lines)

`ForceCompleteDialog` gets the full §18 treatment: `Dialog` with `maxWidth={520}` (Task 15), a
header eyebrow `PRIVILEGED ACTION · APPROVAL REQUIRED` in `risk-fg` mono above the title, a `risk`
warning callout in the body (reuse `ErrorState`'s visual pattern from Task 21 — same border/bg
tokens, different copy — or a lighter inline callout if `ErrorState`'s retry-button affordance
doesn't fit here; this is a warning, not a retry prompt), and a bordered footer via `DialogActions`
with the submit button disabled until an approver is chosen and the reason exceeds 9 characters
(**this validation logic already exists today per the Global Constraints — confirm it, do not
rewrite it**). `ApprovalPanel` and `HoldDialog` get the token rename map only; neither is the §18
target.

- [ ] **Step 1–4:** Read all three files first (confirm the disabled-until-valid logic's exact
  current shape before touching it), implement the `ForceCompleteDialog` header/callout/footer
  restyle plus the rename map on all three, run `npx vitest run ApprovalPanel ForceCompleteDialog
  HoldDialog && npm run build`, commit:

```bash
git add frontend/src/components/journey/ApprovalPanel.tsx frontend/src/components/journey/ForceCompleteDialog.tsx frontend/src/components/journey/HoldDialog.tsx
git commit -m "feat(frontend): restyle approval/hold dialogs, give ForceCompleteDialog its COMPONENTS.md §18 chrome

Privileged-action eyebrow header, risk warning callout, bordered footer.
The disabled-until-valid submit rule is unchanged, only restyled."
```

---

### Task 32: Timeline tab

**Files:** Modify `frontend/src/components/journey/TimelineTab.tsx` (~69 lines — already uses
`Pagination`, `EmptyState`/`SkeletonRows`, `TimelineRow`, all restyled by Phase B).

- [ ] **Step 1–4:** Rename-map pass only, plus the `IMMUTABLE AUDIT LOG · 7-YEAR RETENTION · N
  EARLIER EVENTS` footer copy per `SCREENS.md` §3 if not already present in some form (confirm
  first — this may already exist and just need restyling, not new copy). Run
  `npx vitest run TimelineTab && npm run build`, commit:

```bash
git add frontend/src/components/journey/TimelineTab.tsx
git commit -m "feat(frontend): restyle TimelineTab"
```

---

### Task 33: Workflow builder canvas — convert `StageRow` to `BuilderNode`

**Files:**
- Modify: `frontend/src/components/workflow/StageRow.tsx` (~258 lines, full rewrite converting to
  `BuilderNode` from Task 24, keeping its existing drag-and-drop wiring — `dragstart`/`dragover`/
  `drop` handlers — untouched, only the rendered markup changes)
- Modify: `frontend/src/components/workflow/BranchRuleCard.tsx` (~191 lines — its own `selectStyle`
  const, rename-map pass)
- Modify: `frontend/src/components/workflow/StageInspector.tsx` (~246 lines — uses `Field`,
  `Switch`; composes `MilestoneEditor`+`BranchRuleCard`; sticky `xl:top-[76px]` positioning
  preserved)
- Modify: `frontend/src/components/workflow/MilestoneEditor.tsx` (~229 lines — its own
  `MandatoryToggle`, kept per the Global Constraints rule)

Per `COMPONENTS.md` §21's own closing note, add the keyboard-accessible reorder affordance
(move-up/move-down buttons) `BuilderNode`'s Task 24 comment deferred to this task — since
`StageRow` already has the real reorder logic (the drag handlers), wire two small buttons calling
the same reorder function the drop handler calls, visually hidden until focus (`sr-only
focus:not-sr-only` or an always-visible small icon pair next to the `⋮⋮` handle — pick whichever
reads better once the canvas is actually rendered, this is a UI-polish judgment call the design
doesn't dictate the shape of, only that it must exist).

- [ ] **Step 1–4:** Read all four files, convert `StageRow` to compose `BuilderNode`, add the
  keyboard reorder affordance, apply the rename map to `BranchRuleCard`/`StageInspector`/
  `MilestoneEditor`, run `npx vitest run workflow && npm run build`, manually verify the builder
  canvas still drag-reorders and click-selects nodes, commit:

```bash
git add frontend/src/components/workflow/StageRow.tsx frontend/src/components/workflow/BranchRuleCard.tsx frontend/src/components/workflow/StageInspector.tsx frontend/src/components/workflow/MilestoneEditor.tsx
git commit -m "feat(frontend): convert the workflow builder canvas to the BuilderNode primitive

Adds the keyboard-accessible reorder affordance COMPONENTS.md §21 calls
for in production, wired to the same reorder function drag-and-drop
already calls."
```

---

### Task 34: Publish panel + migration table — convert to `DataTable`

**Files:**
- Modify: `frontend/src/components/workflow/PublishPanel.tsx` (~93 lines — one `<Link>` styled as
  a button; check whether it should become a real `Button` with `asChild`-style link composition,
  or stay a styled `<Link>` — Next's `<Link>` can't literally be a `<button>` when it needs to
  navigate, so keep it a styled anchor, just restyled to `Button`'s primary-variant tokens rather
  than a separate hardcoded style)
- Modify: `frontend/src/components/workflow/MigrationTable.tsx` (~145 lines, converting to
  `DataTable`)
- Modify: `frontend/src/components/workflow/MigrationTable.test.tsx`

**`MigrationTable`'s `candidates.length === 0` empty-state guard (the real product-bug fix from
2026-08-23, per `CLAUDE.md`) must survive this conversion exactly.** Write a test for it *before*
converting if `MigrationTable.test.tsx` doesn't already cover it explicitly by name (confirm first
— per `CLAUDE.md` it does: "`MigrationTable.test.tsx` gained a case proving the ineligible-only
table still renders" — so this is regression protection already in place, not a gap to fill; just
don't let the `DataTable` conversion break it).

- [ ] **Step 1: Run `MigrationTable.test.tsx` before touching anything, confirm it's green and
  note which case covers the empty-state guard.**

- [ ] **Step 2: Convert `MigrationTable` to compose `DataTable`**, keeping the guard's exact
  condition (`candidates.length === 0`, not `eligible.length === 0`) — the checkbox column (17px,
  radius 5, per Task 17's `Checkbox` restyle already covering this size), risk chip column
  (`SAFE`/`NEEDS MAPPING`/`BLOCKED`), and the diff aside are unchanged in behaviour, restyled in
  place. If `DataTable` gained the `stackedColumn` prop in Task 27's option (a), use it here too —
  this was the second caller Task 22 named as the reason to fold the fallback into the primitive
  rather than duplicate it a second time.

- [ ] **Step 3: Run `npx vitest run MigrationTable && npm run build`, confirm the empty-state guard
  test still passes.**

- [ ] **Step 4: Restyle `PublishPanel`** (rename map plus the `Button` primary-variant tokens for
  its link-styled-as-button).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/workflow/PublishPanel.tsx frontend/src/components/workflow/MigrationTable.tsx frontend/src/components/workflow/MigrationTable.test.tsx
git commit -m "feat(frontend): convert MigrationTable to DataTable, restyle PublishPanel

Preserves the candidates.length === 0 empty-state guard (2026-08-23 fix)
exactly -- confirmed via its existing regression test before and after
the conversion."
```

---

### Task 35: Admin — roles, team members, org, users, workflow list pages

**Files:**
- Modify: `frontend/src/components/admin/RoleEditor.tsx` (~513 lines, largest file in this refactor
  — uses `Button`, `StatusPill`; **has its own locally-defined `Switch`**, kept per the Global
  Constraints rule, restyled to the same track/knob tokens as `ui/Switch.tsx` gets in Task 13, just
  not consolidated into it; sticky `xl:top-[76px]` inspector pattern preserved)
- Modify: `frontend/src/components/admin/TeamMembers.tsx` (~235 lines — uses `Button`,
  `Card`/`CardHeader`, `Dialog`/`DialogActions`, `EmptyState`/`SkeletonRows`. **Fix the pre-existing
  bug the domain-survey fork found**: line ~213's `var(--ob-font-family-mono)` doesn't exist as a
  token in either the old or new system — correct it to `var(--ob-font-family-data)`, the name
  every other mono usage in the codebase actually uses.)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/layout.tsx` (the tab-strip sub-nav between
  Users/Roles/Org/Workflows — restyle its underline-active treatment's tokens; **leave it as
  underline-style navigation, not `SegmentedControl`** — unlike Task 14's case-workspace tabs,
  this is real route navigation between separate pages, not a `role="tablist"` switching panels in
  place, so `SegmentedControl`'s single-page-switching visual doesn't fit it the way it fit
  `Tabs.tsx`)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/org/page.tsx` (`Row`/`OrgForm` helpers; **fix the
  second pre-existing bug the fork found**: line ~156's `hover:bg-bg-hover` Tailwind class points
  at a token, `--ob-bg-hover`, that was never defined in the old system either — replace with
  `hover:bg-surface-active`, the closest real hover token)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/roles/page.tsx`
- Modify: `frontend/src/app/(app)/t/[slug]/admin/users/page.tsx` (~626 lines, largest route file —
  `UserRow`/`RoleChip`/`Quiet`/`RoleAssignment` helpers)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/workflows/page.tsx`,
  `workflows/[id]/versions/[vid]/page.tsx`, `workflows/[id]/migration/page.tsx` (this last one
  composes `MigrationTable`, already converted in Task 34 — confirm it renders correctly, no
  changes expected here beyond the rename map on the page's own chrome, e.g. `ProblemBanner`)

- [ ] **Step 1–4:** Read every file, apply the rename map plus the two named bug fixes, run
  `npx vitest run admin workflows/page && npm run build`, manually verify every admin screen and
  the two named Tailwind-class bugs are gone (search-confirm no remaining `bg-bg-hover` or
  `font-family-mono` references anywhere: `grep -rn 'bg-bg-hover\|font-family-mono' frontend/src`),
  commit:

```bash
git add frontend/src/components/admin "frontend/src/app/(app)/t/[slug]/admin"
git commit -m "feat(frontend): restyle admin screens (org, roles, users, workflow list)

Fixes two pre-existing token-reference bugs found during the restyle
survey: TeamMembers' var(--ob-font-family-mono) (undefined in either
system) and admin/org's hover:bg-bg-hover (never a defined token)."
```

---

### Task 36: Case workspace route page

**Files:** Modify `frontend/src/app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx` (the
route composing `CaseHeader`, `Tabs` (now `SegmentedControl`-styled per Task 14), `Roadmap`
(now `StageAccordion`-based per Task 30), `TimelineTab`, `ApprovalPanel`/dialogs).

Mostly wiring confirmation — every child it renders was already restyled in Tasks 29–32. This task
is the "does it all actually fit together" check plus any page-level layout (the wrapping two-column
flex from `SCREENS.md` §3: content `flex:1 1 520px` + aside `flex:1 1 296px; min 264px; max 340px`,
wrapping below `1100px` per the design spec's corrected responsive section) that lives at this
level rather than in any one child component.

- [ ] **Step 1: Read the current page file in full.**

- [ ] **Step 2: Apply the two-column flex layout** per the dimensions above, with the `<1100px`
  wrap behaviour (`flex-wrap: wrap` on the row, both children's `flex` values already encode the
  wrap point via their basis/min/max).

- [ ] **Step 3: Manual check** — resize the browser through 1100px, confirm the aside wraps beneath
  the content and the header row (from `CaseHeader`, Task 29) also wraps rather than overflowing.

- [ ] **Step 4: Run the full suite**

Run: `cd frontend && npx vitest run && npm run build`

- [ ] **Step 5: Commit**

```bash
git add "frontend/src/app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx"
git commit -m "feat(frontend): wire the case workspace's two-column layout with the <1100px wrap"
```

---

### Task 37: Dashboard placeholder

**Files:** Modify `frontend/src/app/(app)/t/[slug]/dashboard/page.tsx`.

Token rename map only — `EmptyState` (already restyled in Task 21) does the rest. No structural
change; this route stays a placeholder per the design spec §2.

- [ ] **Step 1–2:** Confirm the page renders correctly with no code changes needed beyond what
  `EmptyState`'s own Task 21 restyle already provides — if the page passes its own icon/copy
  through unchanged and `EmptyState` is the only thing rendering, this task may find nothing left
  to do. If so, skip the commit and note that in the plan's tracking rather than making a
  no-op commit.

---

## Phase C completion check

Once Task 37 lands, run the full verification suite from the design spec §8:

```bash
cd backend && ./gradlew cleanTest test
cd frontend && npx vitest run
cd frontend && npx playwright test
python frontend/scripts/contrast.py
```

All four must be clean. `npx playwright test`'s accessibility spec drops its "both themes"
dimension (per the design spec §8) — if that spec still asserts against `[data-theme="dark"]`
anywhere, update it as part of Task 2 (dark-theme removal) rather than leaving it for this final
check to discover, since a spec asserting against a deleted mechanism is a build-breaking miss, not
a polish item.

Grep for any remaining reference to the old token names or the old design system, as a final sweep
nothing above should have missed:

```bash
grep -rn -- '--ob-bg-\|--ob-text-primary\|--ob-text-secondary\|--ob-accent\b\|--ob-status-\|--ob-solid-\|docs/uispecs/\b' frontend/src
```

A clean result (no matches outside `docs/uispecs_legacy/`'s own copied `.css`/`.md` files, which
this refactor never touches) is the exit criterion for the whole plan.
