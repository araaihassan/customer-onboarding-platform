# Handoff: Enterprise Customer Journey & Onboarding Platform

## Overview

A multi-tenant onboarding workspace for enterprise service providers. It replaces spreadsheets and
email chains with a single workspace where internal teams orchestrate a customer's onboarding
journey, and the customer watches it happen through a portal of their own.

The design covers **19 screens** across two audiences:

- **Operator app** — 8 role-adaptive dashboards, portfolio grid, case workspace, SLA war room,
  personal work queue, portfolio timeline, document repository, agreement lifecycle, configurable
  journey builder, workflow-version migration tool, RBAC matrix, insights, invitation flow.
- **Customer portal** — multi-journey account overview, journey home, requirements, documents,
  agreements, messages.

It is built directly against the attached PRD and the sixteen Q&A decisions. Those decisions are not
decoration — they drive real UI (weighted progress maths, SLA pause semantics, freeze-by-default
workflow versioning, record-level document visibility, force-complete approval gate). See
`DOMAIN_RULES.md`; implementing the screens without those rules will produce a shell that looks
right and behaves wrong.

## About the design files

The files in this bundle are **design references authored in HTML**. They are prototypes that
demonstrate intended look, layout, copy and interaction — **not production code to lift**.

The task is to **recreate these designs inside the target codebase**, using its established
framework, component library, state management and conventions. The PRD proposes Next.js + React +
TypeScript + Tailwind + shadcn/ui + Framer Motion + TanStack Query; if that stack is already in
place, build there. If the repository is empty, that stack is a reasonable choice and the design
maps onto it cleanly (every colour and radius in `DESIGN_TOKENS.md` can be expressed as Tailwind
theme extensions; every screen maps to a route).

Do not port the prototype's inline styles verbatim. Do not port its single-class state container.
Extract the tokens, rebuild the components, wire real data.

## Fidelity

**High fidelity.** Colours, typography, spacing, radii, shadows, motion timings and all copy are
final and specified to the value. Recreate the UI faithfully using the codebase's existing
primitives. Where the codebase already has a Button/Card/Table primitive, use it and restyle to
these tokens rather than introducing parallel components.

Two deliberate exceptions:

- **No imagery.** The design uses no photography or illustration. Nothing is missing.
- **Charts are hand-built bars.** In production, use the codebase's charting library; match the
  colours and the "clock running vs. clock paused" split-bar semantics described in `SCREENS.md`.

## What's in this bundle

| File | Purpose |
| --- | --- |
| `README.md` | This file. Start here. |
| `DESIGN_TOKENS.md` | Every colour, type, spacing, radius, shadow and motion value. |
| `COMPONENTS.md` | The ~20 recurring components with exact specs and states. |
| `SCREENS.md` | Screen-by-screen layout, content and behaviour. |
| `DOMAIN_RULES.md` | The PRD/QA business rules the UI encodes, and where each surfaces. |
| `STATE_AND_DATA.md` | State model, TypeScript data shapes, API surface implied by the design. |
| `Onboarding Platform.dc.html` | The interactive design reference. Open in a browser. |
| `source/PRD.md`, `source/QA.md` | The original requirements this was designed from. |

## Running the reference

Open `Onboarding Platform.dc.html` in any modern browser. It is self-contained apart from two
Google Fonts. Things worth clicking:

- **⌘K / Ctrl-K** — command palette, filters live. **⌘J** — inbox drawer. **Esc** — close.
- **Sidebar role switcher** ("Viewing as") — swaps the entire dashboard composition, not just the
  numbers. All eight roles are implemented.
- **Case workspace → "Why 44%?"** — the explainable progress panel.
- **SLA war room → Force-complete** — the approval-gated privileged action.
- **Journey builder** — stages are drag-reorderable; the purple node is a conditional branch.
- **Sidebar bottom-right ⇌** — switches between operator app and customer portal.
- **Customer portal → journey switcher** — the same contact holds three journeys.

## Suggested implementation order

1. **Tokens + shell** — theme config, app shell (rail / sidebar / top bar / scroll body), routing.
2. **Primitives** — Chip, StatCard, DataTable row, ProgressBar, Avatar, SectionCard, Drawer, Modal,
   CommandPalette. `COMPONENTS.md` is the spec.
3. **Case workspace** — the flagship screen and the one that proves the domain model. Includes the
   weighted-progress calculation, which belongs in shared logic, not in the view.
4. **Portfolio grid + war room** — both read the same case list; the SLA clock model must be real.
5. **Dashboards** — build the block renderer once, then the eight role compositions are data.
6. **Builder + migration** — the configurable-workflow half of the product.
7. **Customer portal** — separate layout, separate tone, strictly filtered data.
8. **Documents / agreements / admin / insights / auth.**

## Non-negotiables

- **Weighted progress is computed, never stored as a display string.** One function, shared by
  operator and portal, so the two can never disagree. See `DOMAIN_RULES.md` §Q6.
- **The SLA clock is a real model** with running/paused states and business-day arithmetic. Every
  screen that shows a clock reads the same model. See `DOMAIN_RULES.md` §Q8.
- **Document visibility is enforced server-side.** The three visibility tiers in the UI are labels
  on a decision the API has already made; never filter sensitive records in the client.
- **Customers see one account, many journeys.** Journey scoping is part of every portal query.
- **Accessibility**: the design's smallest type is 9px mono, used only for uppercase metadata
  labels with high contrast. Keep it at 9–10px only for that purpose; never for body copy. Nearly
  every interactive row in the prototype is a `<button>` element — preserve that, and add visible
  focus rings (the design relies on `:focus` states you must supply: 2px `#1c1b18` outline, 2px
  offset). One exception: the journey builder's draggable stage nodes are plain `<div>`s with
  `onClick`/`draggable`, since native HTML drag-and-drop needs a non-button element — give those a
  keyboard-accessible reorder affordance (e.g. move-up/move-down buttons) in production, not just a
  focus ring.
