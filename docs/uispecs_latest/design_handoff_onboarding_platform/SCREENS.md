# Screens

19 screens plus 6 overlays. Route names below match the prototype's screen keys.

Shared chrome (rail, sidebar, top bar) is described in `COMPONENTS.md` §1 and is present on every
screen. Page headers follow one pattern unless noted:

```
mono 10px ls .1em uppercase eyebrow, text-faint
h1 26px/600 ls -.03em
p 13.5px text-muted, max-width ~70–80ch
(optional right-aligned button group or segmented control, align-items:flex-end)
margin-bottom 16–20px
```

---

# OPERATOR APP

## 1. `dashboard` — Role dashboard

**Purpose.** The operational hub. Per QA Q16 each role gets a *genuinely different layout*, not the
same layout with different numbers.

**Architecture.** Do not build eight screens. Build one dashboard that renders:
`kpis: StatCard[4]` then `blocks: SectionCard[]` into a 4-column grid where each block declares its
own `span`. A role is then a small config object: eyebrow, headline, sub, four KPIs, and an ordered
list of block ids. Adding a ninth role must be a data change.

**Header.** Eyebrow is the role and its scope (`PROJECT MANAGER · 14 ACTIVE CASES`). The headline is
a *sentence about today*, not a label — "Two cases need your decision before 5pm." Right side:
`Triage exceptions` and `All journeys →` secondary buttons.

**Block types** (renderers in `COMPONENTS.md` §7): `rows`, `funnel`, `chart`, `bars`, `spotlight`.

**Role compositions:**

| Role | KPIs | Blocks (span) |
| --- | --- | --- |
| Project Manager | My active cases 14 · Milestones due this week 9 · Blocked on customer 5 · On-time 91% | Decision-needed spotlight (2) · My milestones this week (2) · Where my cases sit funnel (2) · Sales handoffs (2) |
| Operations lead | Active journeys 128 · SLA at risk 6 · Pending approvals 21 · Team utilisation 84% | Live journey map funnel (4) · Attention queue (2) · Team workload bars (2) · Activations per week chart (2) |
| Compliance / Legal | Awaiting my review 12 · KYC expiring ≤30d 7 · Restricted documents 43 · Audit events today 318 | Review queue (2) · Expiring documents (2) · Access exceptions (2) · Review turnaround chart (2) |
| Finance | Approvals pending 8 · Activation value held £2.4M · Median approval 1.4d · Renewals ≤90d 11 | Approvals waiting on Finance (2) · Value by stage chart (2) · Renewals and expirations (4) |
| Executive / VP | Avg time to activate 24.6d · SLA breach rate 7.1% · Activated this quarter 62 · Revenue activated £18.6M | Activations chart (2) · Live journey map funnel (2) · Stage bottlenecks bars (2) · Portfolio risks (2) |
| Sales rep | My handoffs 18 · Awaiting my approval 4 · Invitations unsent 6 · Days to first document 2.1d | Sales→onboarding handoffs (2) · Portal invitations (2) · Where my cases sit funnel (2) |
| Service provider | Setups queued 9 · In progress 4 · Blocked on access 3 · In testing 3 | Setup queue (2) · My capacity bars (2) · Blocked on access (4) |
| Administrator | Users 214 · Cases on old version 12 · Workflow versions 4 · Failed logins 24h 3 | Migration spotlight (2) · Workflow versions (2) · Role coverage bars (2) · Audit stream (4) |

Every row in every block is clickable and lands on the underlying record. That is the point of the
dashboard: signal → work in one step.

**Copy rule.** Block footnotes state a conclusion, not a restatement — "Marco Lee is 30% over
capacity. Reassigning two setups to Nia clears the overload." Preserve this voice.

---

## 2. `cases` — Journeys (portfolio grid)

Two views behind a segmented control.

**Table view.** DataTable, dense columns:
`Customer | Case | Current stage | Owner | Weighted progress | SLA clock | ACV | WF`
(grid `1.35fr .8fr 1.15fr .95fr 1.05fr .95fr .6fr .5fr`; comfortable mode drops ACV and WF and uses
`1.5fr .9fr 1.3fr 1fr 1.15fr 1fr`).

- Customer cell: name 13px/600, plus an `automation` chip reading `3 JOURNEYS` when the account has
  more than one journey; subtitle is the human status ("Waiting on customer").
- Owner cell: 20px avatar + name.
- Progress cell: 5px bar + mono percentage, right-aligned.
- SLA cell: a Chip — `PAUSED 3.1d` (info), `BREACHED 2.0d` (risk), `1.2d LEFT` (ok), `AGED 4.0d`
  (risk), `0.4d LEFT` (warn).

Filter bar above: a ⌘K search trigger, `All stages ▾`, and three saved-filter buttons —
`SLA at risk · 6` (permanently `risk`-tinted), `Waiting on customer · 23`, `Pinned to v2.3 · 12`.
Right: mono `SHOWING 09 OF 128`. Footer bar: mono ACV total, `Export CSV`, `Load 119 more`.

**Stage board view.** 5 columns (`Registration`, `Agreement`, `Document collection`, `Verification`,
`Technical setup`), each a `surface-muted` container, radius 11, `padding:9px`, `min-height:180px`,
holding compact cards: customer name + owner avatar, mono `id · ACV`, 4px progress bar, SLA chip.

---

## 3. `case` — Customer journey workspace ★ flagship

**Layout.** A wrapping two-column flex: content `flex:1 1 520px`, aside `flex:1 1 296px`
(`min 264px`, `max 340px`, 1px left border). Below ~900px the aside wraps under the content — this
was a real bug in an earlier revision; keep the wrap.

**Header.** Chip row: mono case id · `PINNED TO v2.3` (warn) · `SLA PAUSED 3.1d` (info) ·
`1 OF 3 JOURNEYS ON THIS ACCOUNT` (automation, clickable). Then
`h1 27px` = customer name with the journey name appended as a 15px/500 `text-subtle` span. Then
`Acme Foods Ltd. · owner Jordan Diaz · committed activation 18 September`.
Right: `Message customer` (secondary) and `Request document` (primary).
**The header row must wrap** and the title column needs `flex:1 1 260px; min-width:260px`.

**Tabs.** SegmentedControl: Journey · Tasks · Documents · Agreements · Activity.

### Journey tab

**Progress card.** `surface`, radius 11, `padding:15px`.
"Weighted journey progress", then the value at 34px/600 beside a **`Why 44%?` button**
(`accent.fg`, 600, 1px `line`, radius 7, h 25). 7px progress bar. Below it, mono 10px
`4 OF 9 STAGES · WEIGHTED BY ESTIMATED DURATION` and `27d TOTAL ESTIMATE`.

**Explain panel** (toggled, `om-pop`). This is the differentiator — build it faithfully.
`border:1px solid line; radius 10; background:surface-sunken`.
- Intro strip: title "How this number is calculated" + a sentence stating the rule and that no
  manual overrides are applied.
- Table, grid `1.5fr .5fr .6fr .5fr 1.4fr .7fr`, header mono 9px:
  `STAGE | EST. | WEIGHT | OWN % | CONTRIBUTION | ADDS`.
  One row per stage; stages with 0% are dimmed to `text-faint`. The CONTRIBUTION column is a 6px
  bar whose width is `weight × 3` and whose colour is `ok.fg` complete / `warn.fg` partial /
  `line` untouched. ADDS is `+7.4` style, mono, right-aligned.
- Footer on `surface`: "Sum of contributions" and the total in mono 14px/600.
- **The contributions must sum to the displayed percentage.** Compute both from one function.

**Stage list.** StageAccordion (`COMPONENTS.md` §14), nine stages. Reference data:

| # | Stage | Team | Est. | Own % | Milestones |
| --- | --- | --- | --- | --- | --- |
| 1 | Registration | Sales | 2d | 100 | Company profile captured (1d) · Primary contact verified (1d) |
| 2 | Sales approval | Sales | 1d | 100 | Handoff approved (1d) |
| 3 | Agreement | Legal | 8d | 100 | MSA drafted (2d) · Legal review (4d) · Signature collected (2d) |
| 4 | Document collection | Operations | 5d | 20 | Company registration record (1d, done) · Tax certificate (2d, **waiting**) · Document quality review (2d, **blocked**) |
| 5 | Verification | Compliance | 3d | 0 | KYC verification (2d) · Sanctions screening (1d, **automated**) |
| 6 | Technical setup | Technical | 4d | 0 | Environment provisioning (3d) · Integration configured (1d) |
| 7 | Testing | Technical | 2d | 0 | Customer acceptance test (2d) |
| 8 | Training | Support | 1d | 0 | Administrator training (1d) |
| 9 | Go live | Operations | 1d | 0 | Service activated (1d) |

Total 27d · completed weight 12d → **44%**.

### Other tabs

- **Tasks** — DataTable `2fr 1fr .7fr .7fr` = Task / Status / Priority / Due. Statuses: BLOCKED,
  WAITING, IN PROGRESS, PENDING, COMPLETED.
- **Documents** — rows with a 30×34 radius-5 file-type tile (`PDF` / `XLS` / `REQ`), name, meta
  line stating visibility and version, a Chip, and an `Open` button.
- **Agreements** — same row shape with a `§` tile on `automation.bg`; meta states record mode
  ("v3 · file-backed · signed 14 Aug via OpenSign", "structured record only · no file required").
- **Activity** — vertical timeline: a 9px semantic dot with a 1px connector, mono 9.5px timestamp,
  `<strong>who</strong> did-what`, then the detail in 12px `text-muted`. Footer:
  `IMMUTABLE AUDIT LOG · 7-YEAR RETENTION · 41 EARLIER EVENTS`.

### Right rail

Sections separated by `border-top:1px solid line; padding-top:14px`, each headed by a mono 9.5px
`ls .08em` `text-faint` label:

1. **CUSTOMER** — key/value rows 12.5px: legal entity, segment, ACV, committed date, forecast date
   (forecast in `warn.fg` when it slips past committed).
2. **OTHER JOURNEYS** — with a right-aligned `SAME ACCOUNT` automation chip; each sibling journey is
   a bordered button showing name, mono percentage, a status chip and the current stage.
3. **SLA CLOCK** — an `info` callout: "Paused · 3.1 business days" plus the reason and the calendar
   in use.
4. **PARTICIPANTS** — avatar rows with role, and a 6px `ok` presence dot for anyone online.
5. **ACTIONS** — three full-width left-aligned buttons; the third (`Force-complete a milestone`) is
   the danger-outline variant and opens the modal.

---

## 4. `war` — SLA war room

**Header.** Eyebrow `SLA WAR ROOM · BUSINESS DAYS · NORTHWIND CALENDAR`. Headline names the count
and the user's share: "Six exceptions, two of them yours." Sub explains that paused clocks are
excluded and that every action here is audited.

**Summary strip.** Four cards (not StatCards — simpler: mono label, 28px value in the state colour,
11.5px note): `BREACHED 2` · `DUE TODAY 2` · `CLOCKS PAUSED 23` · `AUTO-ESCALATED 4`.

**Triage columns.** Three columns — Breached (`risk` dot), Due today (`warn`), Watch (`info`) —
each headed by a dot, a 13px/600 name and a count chip.

**Exception card.** `surface`, radius 11, 1px border (`risk.border` for breached, `line` otherwise).
Body `padding:13px 14px 11px`:
- Severity chip with the overrun appended (`BREACHED +2.0d`), mono case id right-aligned.
- Customer 15px/600, then `stage · ACV`.
- Fact rows 11.5px: `Elapsed / target` (mono), `Clock` (bold, `info.fg` when paused, else severity
  colour), `Owner` (18px avatar + name).
- A `surface-sunken` radius-8 note giving the escalation history (breached) or the pause reason
  (others).
- `Blocking: …` line in `text-subtle`.
Footer `padding:9px 14px; border-top:1px solid line-faint; background:surface-sunken` with the
actions: breached → `Open case` / `Reassign` / `Force-complete` (right-aligned, danger-outline);
due today → `Open case` / `Remind customer`; watch → `Open case`.

Below the Watch column, a dashed `line-strong` panel restating the SLA policy in force, including
that escalation cannot be opted out of.

---

## 5. `work` — My work

Four-column board: **Do now** (risk) · **In progress** (accent) · **Waiting** (info) ·
**Done this week** (ok). Same column and card treatment as the stage board. Cards: title 12.5px/600,
context 11.5px, then a chip + customer name.

The headline is the thesis: "Only what you can act on." Sub: items waiting on a customer are
separated out so they never inflate the queue.

---

## 6. `timeline` — Portfolio timeline

Gantt. Row grid `220px 1fr 74px`.
- Header: `CUSTOMER` · a week ruler (8 cells, each with a 1px `line` left border and a mono 9.5px
  label `W35…W42`) · `GO LIVE`.
- Row: customer 12.5px/600 with `stage · owner` beneath; then a bar track — a leading spacer of
  `offset%`, a completed segment in the case's state colour (radius `3px 0 0 3px`), a remaining
  segment in `line-soft` (radius `0 3px 3px 0`), a 9px `ink` diamond (square rotated 45°) marking
  the next milestone due, and a mono percentage; then the go-live date, mono, right-aligned.
- Legend footer: Completed · At risk · Remaining estimate · Next milestone due.

Segmented control switches grouping by customer / by owner.

---

## 7. `docs` — Documents

**Scope filter row.** Five filter buttons: All in scope · Company-shared · Contact-only ·
Sensitive · Open requests. Active = `ink` fill. Right: mono `08 VISIBLE · 61 HIDDEN BY SCOPE` —
this line is important; it tells the user their view is scoped rather than empty.

**Table** `34px 1.6fr 1fr .9fr 1fr` = tile / Document / Customer / Category / Visibility.
The visibility cell stacks a Chip over a mono 8.5px scope label
(`COMPANY-SHARED` / `CONTACT ONLY` / `EXPLICIT SHARE` / `REQUEST OPEN`). Sensitive documents get a
lock glyph before the filename.

**Right aside** (288px): "How visibility works" with the three tiers explained in one sentence
each, colour-coded by their semantic fg; then a pending-access-requests block with a
`Review requests →` button; then a retention note.

---

## 8. `agreements` — Agreement lifecycle

**Lifecycle card.** Six columns: Draft 6 · Under review 9 · Sent 4 · Awaiting signature 7 ·
Signed 52 · Expiring ≤30d 3. Each is a 22px/600 count in the state colour, a 4px bar
(`width = min(100, n × 1.9)%`), and a mono uppercase label.

**Table** `1.5fr 1fr 1.2fr .8fr 1.5fr` = Agreement / Customer / Record mode / Owner / Status.
The status cell is a Chip plus an 11px `text-subtle` date phrase.
"Record mode" is the QA Q13 decision made visible: `File-backed · v3`,
`Structured + file · v2`, `Structured record only`.

---

## 9. `builder` — Journey builder

**Header.** `h1` "Journey builder" with a `DRAFT v2.5` automation chip inline. Sub: "Stages own
outcomes and SLAs. Milestones inside them are the actual work. Drag a stage to reorder it."
Buttons: `Compare versions`, `Publish v2.5`.

**Version-safety banner.** A `warn` callout with `⚠`, stating that running cases are frozen on their
pinned version, that publishing affects new cases only, and the counts on each version.

**Canvas** (`surface-sunken`, 1px `line`, radius 11, `padding:15px`): a vertical column of
BuilderNodes (`COMPONENTS.md` §21) separated by 14px connectors, ending in a dashed
`+ Add stage or conditional branch` button. Nodes are drag-reorderable and click-selectable.
Default order: Registration → Sales approval → Agreement → **branch: Segment is ENTERPRISE?** →
Enhanced due diligence *(conditional)* → Document collection → Verification → Technical setup →
Testing → Training → Go live.

**Inspector** (300px, sticky): the selected node's owning team (select), SLA days (number),
"Pause on customer" toggle, the transition rule as read-only prose, a drag-reorderable milestone
list with `+ Milestone`, and a footnote stating the force-complete policy.

---

## 10. `migrate` — Case migration (v2.3 → v2.4)

Two columns: case list (flex) + diff aside (330px).

**Case list.** Header: "Cases pinned to v2.3", a `12 TOTAL` warn chip, right-aligned mono
`02 SELECTED`. Rows: a 17px checkbox (radius 5; checked = `ink` fill, white `✓`), customer + mono
id, `stage · consequence note`, and a risk chip — `SAFE` (ok) / `NEEDS MAPPING` (warn) /
`BLOCKED` (risk). Blocked rows explain why ("Case on hold · migrate after hold is lifted").
Footer: `Run dry-run preview` (secondary), `Migrate N case(s)` (primary, **disabled at zero
selection**), and "Reversible for 14 days".

**Diff aside.** One row per change with a 19px radius-5 symbol tile:
`+` add (ok) · `⑃` split (warn) · `~` change (info) · `−` remove (risk); label 12.5px/600 and a
consequence sentence beneath. The five changes are listed in `DOMAIN_RULES.md` §Q2.

---

## 11. `admin` — Roles & access

**Matrix.** Grid `1.4fr repeat(6, minmax(0,1fr))`, `gap:1px`. Header row on `surface-sunken`;
columns Cases / Documents / Agreements / Workflows / Users / Reports. Eight role rows. Each cell is
a centred chip with the scope level:

| Level | Pair | Meaning |
| --- | --- | --- |
| `Full` | risk | Every record in the tenant |
| `Scope` | warn | Records inside an assigned scope |
| `Team` | info | Records assigned to the user's team |
| `Own` | accent | Only records the user owns |
| `None` | transparent bg, `text-ghost` | No access; hidden from navigation |

Below, two cards: **Scope levels** (the key, restated in prose) and **Governance in force** —
key/value rows for audit retention (7 years), right to erasure (pseudonymise, keep audit), MFA
enforcement (Admin, Finance), customer provisioning (invitation only), force-complete authority
(Director and above) — plus a `Preview customer invitation →` button.

---

## 12. `analytics` — Insights

Four plain metric cards: `AVG DAYS TO ACTIVATE 24.6` · `SLA BREACH RATE 7.1%` (risk) ·
`FIRST-PASS DOC RATE 96%` (ok) · `TIME LOST TO WAITING 41%` (warn).

**Median days per stage** (1.4fr): horizontal bars, `128px` label column, 20px track. Document
collection is a **split bar** — `#9a6410` for clock-running time, `#f0e0bc` for paused time — and
the legend explains the three fills. Over-target values are `risk.fg` and bold.

**Right column** (1fr): a `risk`-bordered **Current constraint** card naming Legal review with the
numbers and an `Open the 4 affected cases` primary button; below it **Department performance** —
four key/value rows with on-time percentages coloured by threshold.

Header buttons export PDF / Excel / CSV.

---

## 13. `auth` — Invitation activation

Centred, `max-width 520px`, three steps with a progress rail (24px numbered circles, `ink` once
reached, connected by 1px lines).

1. **Verify invitation** — brand mark, "You've been invited to Northwind onboarding", the inviter
   and invitee, a `surface-sunken` fact panel (organisation / your role / case), `Accept invitation`,
   and a footnote: "Self-registration is disabled. Access is granted by invitation only."
2. **Secure your account** — password + confirm fields and a 2FA toggle row (optional for customer
   contacts, recommended). Back / `Create account`.
3. **You're in, Alex.** — 44px `ok` check circle, the current progress restated, and
   `Open my onboarding`, which drops the user into the portal.

---

# CUSTOMER PORTAL

Same shell, different sidebar and a warmer register: larger headings (28–30px), more white space
(26px page padding), fewer numbers, no jargon. Never show internal notes, owners' workloads, ACV,
SLA mechanics or workflow versions.

The portal sidebar replaces the role switcher with a **journey switcher**: a bordered card showing
the current journey name, its progress bar, percentage and case id, opening a dropdown of all the
account's journeys (each with its percentage and a "N item(s) waiting on you" line). Below it,
`All my journeys` (badge 3), then My journey · What we need (badge 1) · My documents · Agreements ·
Messages.

## 14. `cjourneys` — All my journeys

Eyebrow `ACME FOODS LTD. · 3 SERVICES`. Headline "All my journeys". Sub explains that each service
has its own onboarding, its own progress and its own requirements.

One card per journey (`padding:18px 20px`, radius 13, hover border `line-hover`), laid out as
name + status chip / `sub · id` / a 13px/500 needs line coloured `warn.fg` when something is
outstanding, then a 200px right block with the percentage at 22px/600, a mono ETA
(`Go live 18 September` or `Activated 4 July`) and a 6px progress bar, then a `→`.

Footer note: documents are per journey and are never silently shared across them.

**Reference data** — Enterprise onboarding (44%, 1 item waiting, go live 18 Sep) · Manchester
distribution centre (12%, 2 items waiting, go live 6 Nov) · Payments module add-on (100%, live
since 4 July).

## 15. `chome` — My journey

Everything on this screen is scoped to the selected journey.

- **Header** — mono eyebrow (`STEP 3 OF 5 · DOCUMENTS`) beside a small bordered
  `1 OF 3 JOURNEYS` button that navigates to `cjourneys`; a 30px headline that states the situation
  in plain words ("One document left, Alex."); a lede that says what happens when they act.
- **Progress card** — journey name + sub on the left with the percentage at 38px/600; estimated
  go-live right-aligned. Below, a 5-step tracker: 26px circles (`ok.fg` + `✓` done, `ink` + `●`
  current, `line-soft` upcoming) joined by 2px connectors, with 12px labels beneath.
  **The five customer-facing steps group the nine internal stages** — Register, Agreement,
  Documents, Verification, Go live.
- **Action card** (1.35fr) — `warn`-bordered when something is needed: a `WAITING ON YOU` chip, a
  19px instruction, a reassuring sentence, then three facts (`NEEDED BY` / `FORMATS` /
  `WHO SEES IT`) and a footer with `Upload now` + `Ask a question`.
  When the journey is live, this becomes an `ok`-bordered "Nothing is waiting on you" card with
  `View agreement` / `Ask a question`.
- **Right column** (1fr) — "What happens next", three dotted items in plain language; then "Your
  onboarding team" with the owner's avatar and a `Send a message` button.

## 16. `creq` — What we need

Eyebrow is `journey name · case id`; the sub states that these requirements belong to this journey
only. Rows: a 32px radius-9 status tile (`✓` on `ok.bg` when done, `↑` on `warn.bg` when needed),
name 14px/600, hint 12.5px, a chip (`NEEDED BY 28 AUG` / `COMPLETE`), and a 74px `Upload` button
(replaced by an equal-width spacer on completed rows so the column stays aligned).

Below: a privacy note explaining that personal uploads are visible only to the uploader and the
review team, while company documents are shared with approved contacts.

## 17. `cdocs` — My documents

A dashed drop zone (radius 13, `padding:26px`, centred) with the headline "Drop a file here, or
choose one from your device", format/size line, a **visibility select** (Only me and the review
team / Everyone at Acme Foods on this onboarding / Selected contacts only) and a `Choose file`
button. Below, the document list with a chip per file (`ACCEPTED`, `SIGNED`, `ONLY YOU`) and a
`Download` button.

The visibility choice at upload time is the customer-facing half of QA Q9 — it must be present.

## 18. `cagree` — Agreements

A primary agreement card: 38×44 `§` tile on `automation.bg`, name 17px/600, `Version 3 · signed 14
August 2026`, a `SIGNED` chip, then key/value rows (signatories, renewal date, notice period) and
`Download signed copy`. Beneath, a muted in-progress agreement row that explicitly says nothing is
needed from the customer.

## 19. `cmsg` — Messages

One thread. Header: owner avatar, name, "Operations · usually replies within 2 hours", online dot.
Bubbles: `max-width:76%`, radius 13, `padding:10px 13px`, 13px/1.5 — inbound `surface` with a 1px
`line` border, outbound `ink` on `canvas` text; a mono 9px `who · when` line beneath each, aligned
to the bubble. Composer: bordered input + `Send`; Enter sends. Sent messages append to the thread
immediately.

---

# OVERLAYS

| Overlay | Trigger | Spec |
| --- | --- | --- |
| Command palette | ⌘K, rail button, sidebar search, filter search | `COMPONENTS.md` §15 |
| Inbox drawer | Top-bar Inbox, ⌘J | `COMPONENTS.md` §16 |
| Notification preferences | Drawer header toggle | 9 types, each with a Toggle; intro states all types are optional but manager escalation is enforced by policy |
| Force-complete modal | War-room card, case rail | `COMPONENTS.md` §18 |
| Switchers | Tenant, role, journey | `COMPONENTS.md` §20 |
| Toast | Any confirmed action | `COMPONENTS.md` §19 |

**Command palette contents.** Cases (Acme Foods, Orbit Finance, Brightline), navigation (war room,
timeline, builder, roles, documents, agreements, insights), and actions (migrate cases, request a
document, invite a customer contact, switch to customer portal, export SLA report). Each carries a
kind tag: `CASE` / `GO` / `ACTION`. Filtering matches label + hint + kind, case-insensitive.

**Notification types** (all opt-out-able): task assigned to me · task overdue · milestone completed ·
document requested · document approved or rejected · agreement status changed · customer commented ·
deadline in 48 hours · workflow version published.

---

# RESPONSIVE

The design targets ≥1280px. Required behaviour below that:

| Breakpoint | Behaviour |
| --- | --- |
| < 1280px | Dashboard grid 4 → 2 columns; `span 4` blocks stay full width |
| < 1100px | Case workspace aside wraps beneath the content; case header row wraps |
| < 1024px | Sidebar collapses to a drawer over the content, toggled from the rail |
| < 900px | Boards and triage columns become a single scrolling column; tables switch to stacked cards keyed by the identifying column |
| Portal | Should work down to 390px — it is the surface customers will open on a phone |

Mobile hit targets: 44px minimum. The 26–30px controls in the operator app are desktop-only sizes;
scale them up on touch.
