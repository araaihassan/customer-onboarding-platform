# Handoff: Enterprise Customer Journey & Onboarding Platform

## Overview
Design for an internal onboarding operations workspace plus an external customer portal, covering the full surface described in the PRD: dashboard, customer/case list, per-case journey workspace (journey, tasks, documents, agreements, timeline), workflow builder with conditional branching, reports, and the customer portal in web and mobile form factors.

The product model: a **customer** may hold several concurrent **cases**; a case runs through a nine-stage **journey** driven by a versioned **workflow**; work is tracked as milestones, tasks, documents and agreements; the customer sees a filtered read-only view of the same journey in the portal.

## About the Design Files
The files in this bundle are **design references created in HTML** — prototypes showing intended look and behavior, not production code to copy. The task is to **recreate these designs in the target codebase's existing environment** (React, Vue, SwiftUI, native, whatever is already in use), using its established component library, routing, and data layer. If no environment exists yet, pick the framework that best fits the project and implement there.

Notably, the prototype holds all data inline and has no backend, no auth, and no persistence. Every list, count, and status in it is representative sample data.

## Fidelity
**High fidelity.** Colors, typography, spacing, density, and interaction states are final and should be matched closely. Exact values are in Design Tokens below. Where the prototype fakes something (drag-and-drop upload zone, search field, "New onboarding" button), that is noted under Screens.

## Screens / Views

Global shell: fixed 244px left rail + fluid main column. Main column has a 60px sticky header and 24px/28px content padding (top/sides), 56px bottom.

### 1. Left rail (persistent)
- **Purpose**: primary navigation and role switching.
- **Layout**: `width: 244px; flex: 0 0 244px`, sticky, full viewport height, flex column. Background `#17181c` (dark, default) or `#efece6` (light variant), text `#f2f0ec` / `#191a1e`.
- **Components**:
  - Logo block: 26px indigo rounded square (radius 7px, `oklch(0.52 0.16 274)`, white "O"), product name 14px/600, org slug 10px mono at 50% opacity. Padding 22px 20px 18px.
  - Nav items: full-width buttons, padding 8px 11px, radius 9px, 13px text. Icon column 16px wide. Active = `rgba(255,255,255,0.11)` background, opacity 1, weight 500. Inactive = transparent, opacity 0.72. Hover = `rgba(255,255,255,0.07)`. Optional right-aligned count pill (10px mono, radius 20px, `rgba(255,255,255,0.12)`).
  - Items: Dashboard, Customers (48), Journey workspace, Workflow builder, Reports, Customer portal, Design notes.
  - Footer: "VIEWING AS" label (9.5px mono, uppercase, 0.09em tracking, 45% opacity) above a 2-up segmented control (Internal / Customer) — 27px tall, radius 7px within a `rgba(255,255,255,0.07)` track. Below it, 28px avatar + user name 12.5px/500 + role line 10.5px at 50% opacity. Separated by a 1px `rgba(255,255,255,0.09)` top border.
  - Behavior: "Customer" jumps to the portal screen and relabels the user line to "Impersonating customer".

### 2. Header (persistent)
- 60px tall, sticky, `rgba(247,246,243,0.88)` + `backdrop-filter: blur(10px)`, 1px `#e6e3dd` bottom border, 28px horizontal padding, 18px gap.
- Left: screen title 17px/600, letter-spacing -0.02em; meta line 11px mono `#75726c`.
- Right cluster: fake search field (230px, 34px tall, radius 9px, 1px `#e6e3dd`, white, "⌘K" chip), notification button (34px square, radius 9px, red count badge `oklch(0.57 0.17 25)` at top-right), primary button "New onboarding" (34px, radius 9px, `oklch(0.52 0.16 274)`, white 12.5px/500; hover `oklch(0.45 0.16 274)`).
- Notification panel: fixed, top 66px / right 24px, 348px wide, white, radius 14px, 1px `#e6e3dd`, shadow `0 18px 44px -18px rgba(25,26,30,0.32)`, `fadeUp` 0.16s entry. Rows: status dot + 12.5px text + 10px mono meta, 1px `#f4f2ee` dividers. Toggled by the bell; closes on navigation.

### 3. Dashboard
- **Purpose**: answer "who is stuck" at a glance.
- **Layout**: vertical stack, 16px gap. Row 1: 4-up KPI grid (14px gap). Row 2: 1.35fr / 1fr. Row 3: three equal columns.
- **KPI card**: white, 1px `#e6e3dd`, radius 13px, padding 16px 17px. Label 10px mono uppercase 0.08em `#9a968f`; value 29px/600 letter-spacing -0.03em; delta 11px mono colored green/red/amber; sub 11.5px `#75726c`.
  - Active projects 48 (+6) · Avg. time to live 31d (−4d) · Overdue tasks 17 (+3) · SLA compliance 92% (−1%).
- **Pipeline by stage**: 9 rows, grid `132px 1fr 42px`, 9px gap. Track 22px tall, radius 5px, `#f4f2ee`; fill indigo, amber for the bottleneck stage (Verification). Counts descend 48→8.
- **Needs attention**: white card, header row with "5 blocked" pill; each row is dot + name (12.5px/500) + reason (11px `#75726c`) + age (10.5px mono, red/amber). Rows hover `#faf9f7` and navigate to the workspace.
- **Live activity**: 22px circular initials avatar + 12px text + 10px mono timestamp. Feed rotates one entry every 3.2s (a pulsing green dot marks it live). Rotation is decorative — replace with real subscriptions.
- **Team workload**: label + count row, then 6px bar. Compliance 96% red, Legal 72% amber, rest indigo.
- **This week**: four dated rows, 30px date column (9.5px mono day-of-week over 15px/600 date) + title/sub, 1px `#f4f2ee` dividers.

### 4. Customers
- **Purpose**: triage across all active cases. Note each row is a **case**, not a company — one company can appear more than once.
- Filter chips: All / At risk / My projects / Near go-live. 30px tall, radius 8px; active = `#191a1e` fill, white text; inactive = white with `#e6e3dd` border, `#4d4a46` text. Right side: "N shown · sorted by risk" in 11px mono.
- Table card: white, radius 13px, overflow hidden. Grid `2.1fr 1.2fr 1.5fr 1fr 1fr 0.7fr`, 14px gap, 20px horizontal padding. Header row `#faf9f7`, 9.5px mono uppercase 0.08em `#9a968f`. Body rows 13px vertical padding (8px in compact density), 1px `#f4f2ee` divider, hover `#faf9f7`, click → workspace.
- Row cells: 30px rounded-square initials avatar (radius 8px, `#f2f0ec`); name 13px/500 with 10px mono "ID · sector · case count" beneath; stage pill; progress = 6px bar + percentage; owner; due date (11px mono); health pill (green Designed/On track, amber At risk, red Blocked).

### 5. Journey workspace (per case)
- **Header card**: 46px rounded-square avatar; company name 19px/600 + health pill; 11px mono meta line including the case ID and `workflow v4 (frozen)`; five fact columns (Stage, Account manager, Primary contact, Teams, Est. completion) — 9.5px mono uppercase labels over 12.5px/500 values; right-aligned 34px/600 completion percentage with a 150px 7px bar.
- **Case switcher**: below the meta line, a "CASES" label plus one chip per case (dot + name + 9.5px mono ID) and a dashed "＋ New case" chip. Active chip = `#191a1e` fill. Switching sets the case scope for the whole screen.
- **Tabs**: Journey / Tasks (8) / Documents (8) / Agreements (4) / Timeline. 13px, active 600 with a 2px indigo inset bottom border, inactive `#75726c`. Sits on a 1px `#e6e3dd` rule.

#### 5a. Journey tab
- Above the list: workflow name + "Configure" link into the builder, and a hint "Click a milestone to expand".
- **Milestone row**: white card, radius 13px, border `#e6e3dd` (`#dbd7cf` + shadow `0 8px 24px -16px rgba(25,26,30,0.22)` when open). Grid `26px 1fr auto`, padding 14px 18px.
  - 26px status circle: green ✓ done, indigo active, red ! blocked, `#d6d1c9` pending.
  - Title 13.5px/600 + status pill + optional "blocked by X" in 10px mono red. Sub-line 11.5px `#75726c`.
  - Right: due date (11px mono) over owner (10.5px), 74px 6px progress bar, chevron rotating 180° on open (0.18s).
  - Nine stages: Registration, Sales Approval, Agreement, Document Collection, Verification, Technical Setup (blocked), Testing, Training, Go Live.
- **Expanded panel**: 1px `#efece7` top border, padding 16px 18px 18px 58px, grid `1.4fr 1fr`, 22px gap, `fadeUp` 0.16s.
  - Left: TASKS section — rows with a 17px checkbox (radius 5px; checked = green fill, white ✓, title struck through and `#9a968f`), title, priority pill, right-aligned "assignee · due" in 10px mono. Then DOCUMENTS — inline chips with a mono extension badge, name, status pill.
  - Right: DEPENDENCIES prose, COMMENTS (22px avatar + 12px text + 9.5px mono timestamp), and a dashed "Write a comment… @ to mention" affordance.
  - Checkbox state is real and local; it drives the row's status pill.

#### 5b. Tasks tab
Flat table across the case. Grid `24px 2.4fr 1fr 1fr 1fr 1fr`. Checkbox, title + description, milestone, assignee, due (red when overdue), status pill. Same checkbox behavior as the journey tab.

#### 5c. Documents tab
- Grid `1fr 300px`. Table columns: `2.2fr 1.1fr 0.7fr 0.9fr 1.3fr 0.9fr` = Document (mono extension badge + name), Category, Version, Expires (amber if within 30 days), **Visible to**, Status.
- "Visible to" reflects per-contact document scoping: a green dot for "All 3 contacts", grey for a narrower scope ("A. Brandt only", "R. Lindqvist").
- Sidebar: dashed upload dropzone (radius 13px, 26px 18px padding, "PDF, DOCX, XLSX · max 50 MB · encrypted at rest") — visual only in the prototype; "Requested from customer" list with state dots; **Portal contacts** card listing each contact with role and access pill.

#### 5d. Agreements tab
2-up grid of cards. Title 14px/600, mono meta line, status pill. A four-step tracker (Draft → Review → Sent → Signed) rendered as four equal 4px bars with labels; completed steps green (signed) or indigo (in flight), remaining `#eae7e1`. Footer row: expiry/mono status, "Preview" and a context action (Download / Resend / Nudge).

#### 5e. Timeline tab
Immutable audit list. Grid `92px 22px 1fr`: right-aligned 10.5px mono timestamp, a dot on a 1px `#eae7e1` vertical rule, then event text 12.5px + 10px mono meta. Header notes "Immutable · 214 events · export CSV".

### 6. Workflow builder
- **Layout**: `1fr 320px`, inspector sticky at top 76px.
- Toolbar card: workflow name 14px/600, mono version line, "Save as template" (secondary) and "Add stage" (indigo primary).
- **Stage row**: white, radius 11px, padding 12px 16px, flex, `align-items: flex-start`. Selected = indigo border + `0 0 0 3px oklch(0.95 0.03 274)` ring. Contains: 2-digit mono index, name 13px/500, APPROVAL and AUTO mono badges, sub-line "department · SLA · write scope", and — when the stage branches — an inline rule strip (`oklch(0.97 0.02 274)` fill, `oklch(0.93 0.03 274)` border): `IF <condition> → <target stage>`, with `else <fallback>` right-aligned in 9.5px mono.
- Row controls: three 26px buttons (▲ ▼ ✕). Up/down reorder and keep selection; ✕ deletes (stops propagation); "Add stage" appends "New stage" and selects it. All functional in the prototype.
- **Inspector**: "STAGE CONFIGURATION" label, stage name 15px/600, then read-only-styled fields (32px, radius 8px, `#fdfcfb`): Stage name, Responsible department, SLA target, Entry condition, **Write scope (record-level)**, Notification template. Then three toggles (34×20px track, radius 20px, indigo when on): Requires approval, Auto-advance when tasks done, Visible in customer portal.
- **Branch rules** section: one card per condition with the rule text and "evaluated on stage exit" meta, plus a dashed "＋ Add condition" button.
- **Publishing** section: explains freeze-by-default, then an amber panel — "31 cases on v4 / 18 eligible to migrate" + "Review migration" button.

### 7. Reports
- Range chips (30 days / 90 days / 12 months) + export buttons (PDF, Excel, CSV, 11px mono).
- **Average onboarding time**: 8-bar column chart, 168px tall, bars radius `6px 6px 3px 3px`; recent two periods indigo, earlier `oklch(0.86 0.04 274)`; value above and label below each bar in mono.
- **Stage bottlenecks**: per stage, "9.2d / SLA 4d" in mono (red over SLA, green under), then an 8px track with the value bar and a 2px black 50%-opacity SLA marker positioned absolutely.
- Three summary cards (Department performance, Completion rates, Pipeline value): key 12px left, value 11.5px mono right, 1px `#f4f2ee` dividers.

### 8. Customer portal
Device toggle (Web / Mobile) using the same chip style.

**Web**: browser-chrome frame (38px `#f2f0ec` bar, three 9px dots, centered mono URL) around a 34px/44px-padded page.
- Hero: "YOUR ONBOARDING" mono eyebrow, 30px/600 headline "You're in Verification", 13.5px supporting copy at `max-width: 46ch`, and a 210px progress block (34px percentage, 8px bar, mono go-live date).
- Roadmap: nine equal columns, each a 5px bar (green done / indigo current / `#eae7e1` upcoming) with an 11px label and 9.5px mono date.
- Below: amber "Action needed from you" card with per-document rows and a black "Upload" button; and an "Upcoming" list plus a one-line note naming the account manager.

**Mobile**: 372×764 device frame, radius 42px, 11px bezel, inner radius 32px, shadow `0 30px 70px -30px rgba(25,26,30,0.45)`.
- 44px status bar; scrollable body with 20px horizontal padding; 62px bottom tab bar (Progress, Tasks, Documents, Updates) with 44px minimum touch targets.
- Content: company eyebrow, 23px/600 headline, 27px percentage + go-live, 7px bar, amber "2 documents needed" card with a full-width 46px black upload button per document, then the journey as a vertical timeline (11px dots on a 1px rule).
- A "Mobile principles" column sits beside the frame in the prototype — documentation, not part of the product.

### 9. Design notes
Internal documentation screen: design decisions (2-up), a PRD coverage table (§, requirement, where it lives, state pill), the five resolved product questions with their design impact, and a design-language reference table. Not part of the shipped product — do not implement.

## Interactions & Behavior
- **Navigation**: rail switches screens; closes the notification panel. Dashboard "needs attention" rows and customer rows both route to the workspace. "Configure" on the journey tab routes to the builder.
- **Role switch**: Internal → dashboard; Customer → portal.
- **Filters / range chips / tabs / device toggle**: local selection state, immediate re-render.
- **Milestone expand**: independent per milestone (several can be open). Chevron rotates 180° over 0.18s; panel enters with `fadeUp` (0.16s ease, from `translateY(6px)` + opacity 0).
- **Task checkboxes**: toggle completion, strike the title, and recompute the status pill to "Completed".
- **Workflow editing**: reorder (swap with neighbor, selection follows), delete, append. Selecting a stage loads it into the inspector.
- **Live feed**: rotates every 3.2s; a `pulseDot` animation (2s, opacity 1 → 0.35) marks live indicators. Disableable via a prop.
- **Transitions**: progress bars animate width over 0.4s ease. Nothing else moves.
- **Hover**: table rows `#faf9f7`; secondary buttons `#f2f0ec`; nav items `rgba(255,255,255,0.07)`; primary buttons darken to `oklch(0.45 0.16 274)`.
- **Not implemented**: search, file upload, comment submission, "New onboarding", agreement actions, exports, migration review. All are visual affordances.
- **Responsive**: designed for ≥1440px desktop. The portal's mobile view is a fixed-size frame, not a fluid breakpoint — implement it as the real mobile layout.

## State Management
Local UI state in the prototype; in production most of it is server state.
- `screen` — active nav destination.
- `role` — internal | customer.
- `caseId` — selected case within the customer (drives the whole workspace).
- `tab` — journey | tasks | docs | agreements | timeline.
- `filter` — customer list filter.
- `range` — reports date range.
- `open` — map of expanded milestone keys.
- `doneTasks` — map of task id → completion override.
- `portalDevice` — web | mobile.
- `selStage` — index of the stage in the inspector.
- `stages` — the editable workflow array (name, dept, SLA, approval, auto, write scope, branch condition/target/fallback).
- `notifOpen`, `tick` (feed rotation).

Data the real implementation needs to fetch: customer + cases, case detail with milestones/tasks/documents/agreements/audit events, workflow definition and version, portal contacts and their document scopes, dashboard aggregates, report aggregates, notifications.

## Design Tokens

**Color**
- Page background `#f7f6f3`; surface `#fff`; subtle surface `#faf9f7` / `#fdfcfb`; inset `#f4f2ee` / `#f2f0ec` / `#f0eee9`.
- Border `#e6e3dd`; light divider `#f4f2ee`; panel divider `#efece7`; strong border `#dbd7cf`; dashed `#ccc7bf`.
- Text primary `#191a1e`; secondary `#4d4a46`; muted `#75726c`; faint `#9a968f`; disabled `#b0aba3`.
- Rail dark `#17181c` with `#f2f0ec` ink; rail light `#efece6` with `#191a1e` ink.
- Accent (indigo) `oklch(0.52 0.16 274)`, hover `oklch(0.45 0.16 274)`, tint `oklch(0.95 0.03 274)`, ink-on-tint `oklch(0.42 0.16 274)`, chart light `oklch(0.86 0.04 274)`.
- Success `oklch(0.6 0.12 155)`, tint `oklch(0.95 0.04 155)`, ink `oklch(0.45 0.12 155)`.
- Warning `oklch(0.72 0.14 70)`, tint `oklch(0.96 0.05 70)`, ink `oklch(0.46 0.14 70)`, panel `oklch(0.98 0.02 70)` / border `oklch(0.9 0.05 70)`.
- Danger `oklch(0.57 0.17 25)`, tint `oklch(0.96 0.04 25)`, ink `oklch(0.5 0.17 25)`.
- Neutral pill `#f2f0ec` on `#75726c`. Rule: color always means status, never decoration.

**Typography**
- UI: Archivo 400/500/600/700. Data (IDs, dates, metrics, labels): IBM Plex Mono 400/500.
- Scale: 34px/600 hero metric · 30px/600 portal headline · 29px/600 KPI · 23px/600 mobile headline · 19px/600 page object · 17px/600 screen title · 15px/600 section · 14px/600 card · 13.5px/600 panel · 13px body/table · 12.5px secondary · 12px dense · 11.5px tertiary · 11px meta · 10.5px/10px/9.5px mono labels.
- Tracking: -0.03em on ≥29px numerals, -0.02em on titles, +0.08–0.09em uppercase on mono labels.
- Line-height: 1.4–1.6 on prose; 1.15–1.35 on headings.

**Spacing** — 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 26, 28, 34, 44. Card padding 16–22px; table cells 13px vertical (8px compact) / 20px horizontal; grid gaps 12–16px.

**Radius** — 20px pills · 14px overlays · 13px cards · 12px avatars(lg) · 11px stage rows · 10px inner cards · 9px controls/nav · 8px chips/buttons · 7px segmented · 5px checkboxes · 4–5px bars · 50% avatars/dots.

**Elevation** — cards flat. Open milestone `0 8px 24px -16px rgba(25,26,30,0.22)`; popover `0 18px 44px -18px rgba(25,26,30,0.32)`; device frame `0 30px 70px -30px rgba(25,26,30,0.45)`; selection ring `0 0 0 3px oklch(0.95 0.03 274)`.

**Motion** — `fadeUp` 0.16–0.18s ease (expansion, popovers); `pulseDot` 2s ease-in-out infinite; progress width 0.4s ease; chevron rotate 0.18s.

**Component props** (surfaced as tweaks in the prototype): `density` comfortable | compact (table row padding), `railTheme` dark | light, `liveFeed` boolean.

## Assets
None. No images, logos, or icon libraries — all glyphs are Unicode characters (◫ ☰ ◈ ⚙ ◉ ◐ ✎ ⌕ ◔ ✓ ▲ ▼ ✕ ＋ ↥ ⎙ →). Replace them with the codebase's icon set; they were chosen to avoid shipping an icon dependency into a prototype, not as a design decision. Fonts load from Google Fonts (Archivo, IBM Plex Mono).

## Files
- `Onboarding Platform.dc.html` — the full prototype (all screens). Markup and logic are in one file; the logic class holds all sample data.
- `Onboarding Platform.html` — the same prototype bundled standalone; open it directly in a browser with no server or network.
- `PRD.md` — the source requirements document.

## Product decisions worth preserving
1. **One object, many facets.** Tasks, documents, agreements and audit are tabs on a case, not top-level sections — people work on a customer, not on "documents".
2. **The roadmap is the spine.** The same nine-stage journey renders internally and in the portal so both sides discuss one picture.
3. **The dashboard sorts by exception**, not by chart count.
4. **Branches are readable rules, not a node canvas** — an admin edits an SLA twice a year and should not have to learn a graph editor.
5. **The case, not the customer, is the unit of work** — concurrent cases are switched, never merged.
6. **The portal shows debt, not detail** — what the customer owes and when they go live; contacts authenticate individually and see only documents scoped to them.
7. **Workflow versions freeze on running cases**; migration is explicit and reviewed.
