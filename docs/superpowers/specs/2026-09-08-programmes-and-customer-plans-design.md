# Programmes & Customer-Scoped Plans — Design Spec

**Date:** 2026-09-08
**Sub-project:** 3A of 10 (new; sequenced after 3, before 4)
**Status:** Draft — awaiting review
**Depends on:** Sub-project 3 (Tasks & Collaboration), `feat/tasks-and-collaboration` at `a30d98a`
**Answers:** QA **Q20–Q24**
**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` (current bundle, not the
superseded `docs/uispecs_legacy/`)

---

## 1. Context

Sub-projects 1–3 built a platform where a customer runs one or more journeys, each pinned to a
frozen version of a tenant-wide workflow template, worked internally through tasks and comments.
This sub-project reframes that for **project-based delivery**: a customer holds a *programme* of
parallel journeys, run against a plan **cloned and tailored for them**, and that plan is approved
twice — once as a shape, once as a schedule.

Five product decisions, taken together on 2026-09-08 and recorded as QA Q20–Q24, define it. None
weakens an existing invariant; each carries its own cross-check in §10.

Two of the five fill seams the previous sub-projects deliberately cut rather than inventing new
mechanism. `stage.portal_visible` has existed in the schema since `V12` and **is read by nothing** —
Q24 adds its milestone-level twin and this sub-project becomes the first thing that reads either.
`Case.held_at`, `CaseStatus.ON_HOLD` and `CaseOnHoldException` have existed since `V13` — Q23's
"a journey waits for its first schedule approval" is that hold, not a second pause mechanism.

### What this sub-project deliberately does not build

- **The customer's own approve button.** Q22 is explicit that the approver is internal until
  sub-project 7: 3A records the decision ("the sponsor approved by email, logged by the account
  manager"), and 7 wires the sponsor's own button to the same endpoint. The record carries
  `decided_by` (the user who pressed it) and a nullable `decided_on_behalf_of` contact, so 7 changes
  who presses it and nothing about the record's shape.
- **The portal itself.** `portal_visible` gains its first consumer here — the filtered rendering that
  gate 1 approves — but the customer-facing screens are sub-project 7.
- **Status reports (Q28).** Specified to reuse this sub-project's snapshot mechanism, but they need
  documents (4), agreements (5) and risk state (6) to be worth issuing. Sub-project 8.
- **Meetings (Q25, Q26).** Sub-project 4A, after documents.
- **A three-way merge between a catalogue template and its customer clone.** See §5.2 — refresh
  replaces, and that is a stated cost, not an oversight.
- **A second orchestration layer above `CaseEngine`.** Q20 is explicit that a programme has no
  lifecycle. Nothing in `programme` calls `reconcile`.

---

## 2. Scope

| In | Out |
|---|---|
| `programme`, its participants and journey membership (Q20) | Programme status, hold, approval or engine |
| Derived, duration-weighted programme rollup (Q20) | A stored programme progress column |
| `workflow_template.customer_id`, clone and refresh (Q21) | Merging upstream edits into a tailored clone |
| Gate 1 — shape approval at the customer template version (Q22) | The sponsor pressing either button (7) |
| Gate 2 — dated schedule revisions, snapshotted and approved (Q22) | Status reports over those snapshots (8) |
| First schedule approval releases a held journey (Q23) | A new blocking mechanism, or SLA changes |
| `milestone_definition.portal_visible` and the internal badge (Q24) | Portal screens that consume it (7) |
| Phase 1: the six items open from sub-projects 2 and 3 | Meetings, documents, agreements |

---

## 3. Architecture

### 3.1 One new module

`co.ara.onboarding.programme`, owning `Programme`, `ProgrammeParticipant` and `ProgrammeCase`, their
repositories, one service where every public method is gated and every read goes through
`AuthorizedQuery`, and a thin controller — the shape sub-project 1's Tasks 20–21 established and 2
and 3 each repeated.

It depends **one-way** on `journey` (it reads `Case` for the rollup) and reaches `customer` through a
facts port for the customer's name, the same inversion `journey.CustomerDirectory` already uses.

Two new `ModuleBoundaryTest` rules, each its own named method rather than folded into the cycle
check, for the reason sub-project 2's §3.3 gives: a one-way import in either direction would still
pass a plain no-cycles rule.

- `noJourneyDependencyOnProgramme`
- `noCustomerDependencyOnProgramme`

Both are proven red first with a temporary violation, the way `noJourneyDependencyOnTask` was.

### 3.2 The two approval gates live where their data lives

There is **no `plan` module**. The two gates sit at different tiers and each belongs to the module
that already owns the rows it touches:

| Gate | Tier | Module | Why |
|---|---|---|---|
| 1 — shape | `workflow_version` | `workflow` | The thing approved is a workflow version and its frozen graph |
| 2 — schedule | `Case` + its `Milestone` rows | `journey` | The snapshot copies runtime columns, and the release writes `Case.held_at` |

The alternative — a `plan` module depending on both — was considered and rejected. It is
conceptually tidier ("the plan and its approvals, in one place"), but it buys a second new module, a
second boundary rule, and it puts the code that must write `Case.held_at` outside the module that
owns every other write to that column. Q23's release would then either reach into `journey`'s table
or need a port back into `programme`-shaped territory — the two-way boundary this design rejects
everywhere else.

The gates need no orchestrator between them because gate 1 blocks nothing at runtime. Its teeth come
from a single ordering rule (§5.3) rather than a second hold.

### 3.3 Journey membership is `programme`'s data, not `journey`'s

Membership is a `programme_case` link table with a partial unique index, **not** a nullable
`programme_id` on `onboarding_case`. The column would be cheaper by one table and one join, but it
forces one of two things this design refuses: `programme` writing another module's table, or
`journey` gaining a `ProgrammeDirectory` port to validate the id — which puts an arrow back and makes
the boundary two-way. With the link table, `journey` never learns programmes exist, and
`noJourneyDependencyOnProgramme` proves it rather than asserting it.

---

## 4. Data model

Six new tables and three new columns. Every new table carries a non-null `tenant_id`, an RLS policy
and `FORCE ROW LEVEL SECURITY` created in the same migration as the table, and takes no `DELETE`
grant — `RlsCoverageTest` is deny-by-default over the live schema and will fail otherwise.

Every child table carries its owning id **denormalised** (`programme_id` or `case_id`) so its
descriptor is one subquery hop rather than a chain — the reason `Milestone` carries `case_id` and
`comment` carries it too.

### 4.1 `programme`

| Column | Notes |
|---|---|
| `name` | NOT NULL |
| `customer_id` | NOT NULL |
| `description` | nullable |
| `owner_user_id`, `owning_department_id`, `owning_team_id` | the descriptor needs all three, or DEPARTMENT and TEAM collapse to `cb.disjunction()` |
| `status` | `ACTIVE` / `INACTIVE` |
| `created_by` | |

`status` is **not** a lifecycle in Q20's sense — no hold, no approval, no engine. It exists because
business records are deactivated and never deleted, and `DELETE` is revoked at the database layer.

**What deactivation revokes**, asked before the setter was written, as sub-project 1 requires:
deactivating a programme revokes the sponsor's cross-programme read, because participation is the
only thing that grants it and an `INACTIVE` programme resolves no participants. The revocation is
structural rather than a cleanup step someone must remember. It does **not** revoke any journey
access, because — see §6.3 — programme participation never granted any.

### 4.2 `programme_participant`

`programme_id`, `user_id`, `relationship_type` (reusing `authz.RelationshipType`; no new value is
needed — the account manager is `OWNER`, the sponsor is `PARTICIPANT`), and its own `ACTIVE` /
`REMOVED` status. `UNIQUE(programme_id, user_id)`.

The status enum is programme's own rather than an import of `journey.ParticipantStatus`, so a later
change to journey's participant lifecycle cannot silently change programme's.

### 4.3 `programme_case`

`programme_id`, `case_id`, `added_at`, `added_by`, `removed_at` nullable.

`UNIQUE(case_id) WHERE removed_at IS NULL` — a journey belongs to at most one programme, but must be
able to leave one and join another. A plain `UNIQUE(case_id)` would make the second move impossible,
and dropping the row instead is not available: `DELETE` is revoked.

### 4.4 `workflow_template`, two new columns

| Column | Notes |
|---|---|
| `customer_id` | nullable. NULL = the tenant catalogue, which is every template today. Set = a lineage owned by one customer |
| `cloned_from_template_id` | nullable provenance pointer to the catalogue template it was cloned from |

`UNIQUE(cloned_from_template_id, customer_id) WHERE customer_id IS NOT NULL` enforces Q21's
"one clone per customer" — per source template, so a customer may hold clones of two different
catalogue templates.

The provenance pointer is a **correction to Q21 as written** — see §11.2.

### 4.5 `milestone_definition.portal_visible`

`boolean NOT NULL DEFAULT true`, the twin of `stage.portal_visible`.

`milestone_definition` is a frozen-child table: `refuse_published_child_write` already refuses every
`UPDATE` whose parent version is not `DRAFT`. So the flag is authorable only while the version is a
draft, and every row that predates this migration takes `true` permanently. That is the correct
answer and not a migration gap — a published version never mutates, and retro-hiding a milestone
inside a shape a customer already approved is precisely what the freeze exists to prevent.

### 4.6 `plan_shape_approval`

One row per **submission** of a published version for customer approval.

| Column | Notes |
|---|---|
| `version_id` | NOT NULL |
| `template_id`, `customer_id` | denormalised for lookup and listing, not for scoping — see §6.2 |
| `status` | `SUBMITTED` / `APPROVED` / `REJECTED` |
| `submitted_at`, `submitted_by` | |
| `decided_at`, `decided_by` | `decided_by` is the user who pressed it |
| `decided_on_behalf_of` | nullable `customer_contact` id — the sponsor, while the approver is internal |
| `decision_note` | |

Re-submitting after a rejection creates a **new row**; the latest row is the current state. Decisions
are one-shot, as `journey.Approval`'s already are.

**There is no shape snapshot, and none is needed.** Publish already froze the version, its stages,
its milestone definitions and their `portal_visible` flags, so "what shape did they approve?" is
answered by reading the version. The asymmetry with gate 2 is the freeze trigger's doing, not a
design inconsistency: gate 2 needs a snapshot because `Milestone.due_date` and
`Milestone.owner_user_id` are mutable runtime columns with nothing freezing them.

This table exists at all — rather than as columns on `workflow_version` — because
`workflow_version_frozen` refuses **every** `UPDATE` to a non-`DRAFT` row. Approval columns on the
version would be unwritable by construction. The schema settled this, not a preference.

### 4.7 `plan_revision`

One row per dated schedule revision of one journey's instantiated plan, carrying **its own decision**
— a revision has exactly one, so a separate `plan_schedule_approval` table would be a join for no
gain.

| Column | Notes |
|---|---|
| `case_id` | NOT NULL |
| `revision_number` | monotonic per case; `UNIQUE(case_id, revision_number)` |
| `status` | `ISSUED` / `APPROVED` / `REJECTED` / `SUPERSEDED` |
| `issued_at`, `issued_by`, `issue_note` | |
| `decided_at`, `decided_by`, `decided_on_behalf_of`, `decision_note` | same shape as §4.6 |

`plan_revision` keeps its `UPDATE` grant, because its status genuinely transitions.

### 4.8 `plan_revision_item`

One row per portal-visible milestone, as it stood at issue: `plan_revision_id`, `case_id`
(denormalised), `milestone_id`, `milestone_definition_id`, `stage_name`, `milestone_name`,
`due_date`, `owner_user_id`, `estimated_duration_days`, `portal_visible`, `sort_order`.

**Append-only at the database layer** — `GRANT SELECT, INSERT` with `UPDATE` and `DELETE` revoked, on
`audit_event`'s exact argument: a snapshot the application can rewrite is not a snapshot, and
"what did we send on 15 October?" has to have an answer for governance packs and for disputes.

Typed rows rather than a `jsonb` blob, for three reasons: diffing revision N against N−1 ("these
five dates moved") is a join instead of JSON extraction in application code; Q28's "milestones closed
in the interval" is a query; and `V12`'s own comment already rejects a JSON bag in favour of typed
columns for the requirement-definition table.

---

## 5. Lifecycle

### 5.1 Cloning a template for a customer (Q21)

`POST /workflows/{id}/clone` with `{customerId, name}` creates a customer-owned template whose first
`DRAFT` version is a deep copy of the source's **current published** version — the same deep copy
`WorkflowService.createDraftVersion` already performs, pointed at a different owner.

Three refusals:

| Condition | Status |
|---|---|
| Source has no published version | 422 — you cannot clone a shape that was never frozen |
| A clone of this source already exists for this customer | 409, from the partial unique index |
| The source is itself customer-owned | 422 — lineage stays exactly one level deep |

### 5.2 Refreshing a clone from its source — replace, not merge

`POST /workflows/{id}/refresh-from-source` deep-copies the source's current published version into a
**new `DRAFT` version of the customer template**. `DraftAlreadyExistsException` already covers the
one-draft-at-a-time case. The customer's journeys then move forward with ordinary migration, within
their own template, exactly as today.

**The customer's tailoring is not carried across.** The operator re-applies it in the new draft
before publishing. A three-way merge is out of scope and deliberately so: it would need per-node
identity across two lineages that Q2's freeze deliberately severs, and Q21 already rejected
"both tiers with promotion between them" for the adjacent reason. This is the honest cost of the
answer, recorded here so it is a known limitation rather than a discovery.

### 5.3 Gate 1 — the shape, after publish

A shape approval targets a **PUBLISHED** version. Approving a `DRAFT` would be approving something
that can still change, which is not an approval.

- `POST …/versions/{vid}/plan/submit` creates a `SUBMITTED` row.
- `POST …/versions/{vid}/plan/decide` records `APPROVED` or `REJECTED` with a note and an optional
  on-behalf-of contact.
- Submitting a **catalogue** version is a 422 — the whole two-gate story is defined at the customer
  tier (§5.5).

Gate 1 blocks nothing at runtime. It gets its teeth from one ordering rule instead of a second hold:

> **A schedule revision cannot be issued while its case's pinned version has no `APPROVED` shape
> approval.**

So a case can open on an unapproved shape, but it stays held (§5.5) until a revision is issued and
approved, and no revision can be issued until the shape is approved. One rule, no second mechanism.

### 5.4 Gate 2 — the schedule, per journey

- `POST /cases/{id}/plan/revisions` snapshots every portal-visible milestone into
  `plan_revision_item`, assigns the next `revision_number`, and marks any still-`ISSUED` revision
  `SUPERSEDED` — a case has at most one outstanding revision at a time.
- `POST /cases/{id}/plan/revisions/{rid}/decide` records the decision.
- `GET /cases/{id}/plan/revisions/{rid}/diff?against={rid}` is computed **server-side**: the typed
  snapshot rows are the reason we chose them, and recomputing the diff per client would let two
  clients disagree about what changed.

### 5.5 The hold, and what releases it (Q23)

**Which journeys are held:** a case pinned to a version of a **customer-owned** template is created
`ON_HOLD` with `held_at` set and a fixed reason recorded on the `case.held` audit event ("Awaiting
first plan approval"), so the hold is distinguishable in the trail from a manual one. A case on a
tenant catalogue template behaves exactly as it does today.
The gate is real precisely where a customer-tailored plan exists, and no existing flow or e2e spec
changes.

- `CaseStatus.ON_HOLD` and `CaseOnHoldException` already refuse every satisfy. **No new blocking
  mechanism is written.**
- Deciding the **first** revision `APPROVED` releases the hold through `resume`'s existing path, so
  `total_hold_days` accrues exactly as Q8 requires and the SLA pause is correct for free rather than
  invented twice.
- Later revisions decide without touching the hold. That is Q23's "revisions are advisory", and it
  is why an internal date correction cannot freeze a live project.

**`resume` refuses while the first approval is outstanding.** The condition is derived — *the case is
on a customer-owned template and no `APPROVED` revision exists* — not a new column. It matters twice:
it stops `case.hold` from being a way around the gate, and it composes correctly when a manual hold
is layered on top, because either reason alone is enough to refuse.

### 5.6 The programme rollup (Q20)

Programme progress is **derived on read, never stored**: `Σ(case.progressPercent × weight) /
Σ(weight)`, where a case's weight is the sum of `estimated_duration_days` over its non-`SKIPPED`
milestones — the same weighting `CaseEngine.progressOf` already applies within a case, so the rule is
consistent at both levels.

The weight is exposed by `journey` as a read; `programme` never reaches into `workflow` for
`MilestoneDefinition` itself.

Sub-project 2's invariant "progress is derived and stored by the engine; no request type accepts one"
is unaffected: the engine still owns per-case progress, and the rollup is a read over it.

### 5.7 Audit actions

Thirteen new actions, each with `timeline_visible` set deliberately rather than copied from a
neighbour:

| Action | `timeline_visible` |
|---|---|
| `programme.created`, `programme.updated`, `programme.deactivated` | true |
| `programme.journey_added`, `programme.journey_removed` | true |
| `programme.participant_added`, `programme.participant_removed` | true |
| `plan.shape_submitted`, `plan.shape_decided` | true |
| `plan.revision_issued`, `plan.revision_decided` | true |
| `workflow.cloned_for_customer`, `workflow.refreshed_from_source` | false |

`programme.*` and `plan.*` are business records the customer's own side of the story depends on;
`workflow.*` is authoring and stays compliance-only, where every existing `workflow.*` action
already sits.

**Cause before effect:** `plan.revision_decided` is recorded **before** the `case.resumed` its
approval triggers. `CauseBeforeEffectTest` gains that subsequence — this is exactly the
cause-after-effect shape that scrambled nine `journey` call sites and permanently misordered every
audit row written before 2026-08-29.

---

## 6. Authorization

### 6.1 Permissions

Six new keys:

| Key | Resource type | Scopes | Why |
|---|---|---|---|
| `programme.create` | — | ALL only | There is no record yet to scope against — the `case.create` / `customer.create` precedent |
| `programme.view` | `programme` | RECORD | |
| `programme.manage` | `programme` | ORG_SCOPES | Edit, deactivate, add/remove journeys and participants |
| `plan.issue` | `onboarding_case` | RECORD | Issue a schedule revision |
| `plan.approve_schedule` | `onboarding_case` | RECORD | Record the customer's decision on a revision |
| `plan.approve_shape` | — | ALL only | A workflow version is not a record-scoped resource; inventing a scope for it would be fiction |

Gate 1's *submit* needs no new key — it is authoring, so `workflow.manage` covers it.

Phase 1's role-template review seeds these across the twelve templates. Without it, 3A would leave a
third and fourth Administrator-only permission behind `approval.decide` and `task.manage`, which
CLAUDE.md already records as needing a review.

### 6.2 Descriptors — six, not one

`programme` needs a `ResourceAuthorizationDescriptor` because `programme.view` is RECORD and
`DescriptorRegistry.validate()` refuses to start the application otherwise. Its predicates: ASSIGNED
resolves through `programme_participant` on the actor, DEPARTMENT through `owning_department_id`,
TEAM through `owning_team_id`, and no department plus no teams ⇒ `cb.disjunction()`.

The other five — `ProgrammeParticipantDescriptor`, `ProgrammeCaseDescriptor`,
`PlanRevisionDescriptor`, `PlanRevisionItemDescriptor`, `PlanShapeApprovalDescriptor` — exist because
`AuthorizedQuery.findAll` / `getById` dispatch by **entity type**, and `validate()` will not remind
anyone: exactly the trap that produced `CaseParticipantDescriptor` and `CaseAttributeValueDescriptor`
in sub-project 2 for entities with no permission of their own.

Four of the five delegate to their parent's record predicate through the denormalised owning id
(`programme_id` for the two programme children, `case_id` for the two plan-revision tables).
`PlanShapeApprovalDescriptor` is the exception and must be written deliberately: its parent is a
workflow version, which is not a record-scoped resource, so it **fails closed** — DEPARTMENT, TEAM
and ASSIGNED all return `cb.disjunction()`, and only an ALL-scoped holder of `plan.approve_shape` or
`workflow.view` reads it. That matches how every other `workflow` permission is already catalogued;
inventing a record predicate for it would be fiction, and returning `conjunction()` instead would be
a silent total bypass.

All six live in `scoping/`, never in the module owning the entity, which would close a module cycle.

### 6.3 Programme participation grants read of the programme, and nothing else

Q20 as written contains a contradiction: a programme exists so the sponsor "sees the whole project",
*and* participation "never grants access a viewer could not otherwise obtain". Both cannot hold. A
sponsor who is only a programme participant reads the programme and none of its journeys, so the
container shows an empty list — the feature does nothing for the person it was built for.

**The resolution, which keeps the invariant:** programme participation stays read-only over the
programme itself. Adding a participant **offers to add them as a `CaseParticipant` on the
programme's journeys** as an explicit, audited, individually revocable write, authorized by
`programme.manage` rather than by participation.

Access therefore still comes only from a real per-journey grant. The programme stops that grant from
being twelve separate manual acts; it does not become a second source of authority. A container that
returned journeys its viewer could not otherwise open would be the scope-widening shape three
sub-project 1 escalations took, and §9.2's `ProgrammeScopeTest` is the test that proves it did not
happen here.

This requires a sentence amended into QA.md's Q20 — see §11.2.

### 6.4 The rollup is computed over what the reader can see

The journey list on a programme goes through `AuthorizedQuery` under `case.view`, and the progress
rollup is computed over **that same filtered set**, with the view stating the count it covered
("across 3 journeys").

Computing over every journey instead would be an aggregate over rows the viewer cannot open — the
identical shape to the `taskSummary` leak Phase 1 is fixing, and closing that one while opening this
one in the same branch would be indefensible.

This is not in tension with Q24's "one number for every audience": Q24 is about internal-versus-portal
*rendering*, not authorization scope. For the intended audiences the §6.3 grant makes every journey
visible anyway, so the number is the whole programme in practice. Stating the coverage in words means
a partial view reads as partial rather than reading as wrong.

### 6.5 Write-path obligations

Every id this sub-project takes from a URL or a request body is resolved through `AuthorizedQuery`
before it is written — the customer id on a clone, the case id added to a programme, the user id
added as a participant, the revision id being decided. `@RequirePermission` cannot see arguments, so
a passing gate proves only that the actor may touch *some* record of that type. Three sub-project 1
escalations were this exact shape.

---

## 7. API surface

All tenant-scoped, thin controllers over gated services. Every `PUT` request type and its matching
view stay field-for-field aligned — a field absent from the body deserialises to null and is written
as null, so "the form just doesn't send it" is not a mitigation.

**Programme**

| Method | Path |
|---|---|
| `POST` / `GET` | `/programmes` (list filterable by `customerId`) |
| `GET` / `PUT` | `/programmes/{id}` |
| `POST` | `/programmes/{id}/deactivate` |
| `POST` | `/programmes/{id}/journeys`, `/programmes/{id}/journeys/{caseId}/remove` |
| `POST` | `/programmes/{id}/participants`, `/programmes/{id}/participants/{userId}/remove` |

**Cloning**

| Method | Path |
|---|---|
| `POST` | `/workflows/{id}/clone` |
| `POST` | `/workflows/{id}/refresh-from-source` |
| `GET` | `/workflows` gains a catalogue/customer filter |

**Gate 1**

| Method | Path |
|---|---|
| `POST` | `/workflows/{id}/versions/{vid}/plan/submit` |
| `POST` | `/workflows/{id}/versions/{vid}/plan/decide` |
| `GET` | `/workflows/{id}/versions/{vid}/plan` — the approval state **and** the portal-visible rendering, because the rendering is the artifact |

**Gate 2**

| Method | Path |
|---|---|
| `GET` / `POST` | `/cases/{id}/plan/revisions` |
| `GET` | `/cases/{id}/plan/revisions/{rid}` |
| `POST` | `/cases/{id}/plan/revisions/{rid}/decide` |
| `GET` | `/cases/{id}/plan/revisions/{rid}/diff?against={rid}` |

API types are generated, never hand-written: `./gradlew openApiSpec` then `npm run generate:api`.

---

## 8. Screens

The design bundle's 19 screens cover **none** of this sub-project — there is no programme screen, no
customer-template screen and no plan-approval screen anywhere in it. All four areas below are
designed in-repo, extending the bundle's tokens and components rather than starting a second visual
language. `frontend-design` and `ui-ux-pro-max` are invoked before any of it, per CLAUDE.md.

### 8.1 Programme detail — `/t/{slug}/programmes/[id]`

Header with the customer and the weighted rollup, the journeys list with per-journey progress and
status, and a participants panel. The rollup states its coverage in words, per §6.4.

### 8.2 Customer templates

Catalogue/customer segmentation on the workflows list; a clone action with a customer picker; a
provenance line on a clone ("Cloned from Standard Onboarding, 12 Aug"); and a refresh action whose
confirmation says plainly that tailoring is not carried across (§5.2).

### 8.3 Plan approval

A **gate-1 panel** on the version page: the portal-visible rendering as the artifact, Submit, and
Record decision. A **Plan tab** on the case workspace: the revision list, Issue revision with a
preview of exactly what will be snapshotted, Record decision, and the revision-to-revision date diff.

Plus a banner on a held case naming *why* it is inert and offering the action. A case that silently
refuses every checkbox is the worst thing this sub-project could ship.

### 8.4 Milestone visibility

The milestone inspector gains the twin of `StageInspector`'s existing `portalVisible` toggle, and the
roadmap gains an **"Internal"** badge carrying a word, not only a colour.

### 8.5 Binding design decisions

1. Colour always means status, never decoration.
2. Instrument Sans for human text, Spline Sans Mono for machine values — revision numbers, dates,
   counts, progress figures and ids are mono.
3. Cards are flat.
4. Colour is never the only signal — every status colour is paired with a word or an icon.

### 8.6 Gaps the design system does not cover

The four screen areas above, plus the empty, loading and error states and any layout below 1440px,
which the bundle never covers for any screen.

---

## 9. Testing

TDD throughout: the failing test first, security tests before the mechanism they verify.

### 9.1 Guards extended in the same commit as the code

- `AuthorizationCoverageTest` gains `programme..` and the plan read/write paths — against the
  **rebound** rule Phase 1 produces (any class in the covered packages that injects a `*Repository`,
  with an explicit exclusion list), not the name-shaped one three `task` classes currently dodge.
- `RlsCoverageTest` is deny-by-default over the live schema: all six tables carry RLS and
  `FORCE ROW LEVEL SECURITY` in their own migration. No new allowlist entry.
- `ModuleBoundaryTest` gains its two rules (§3.1), each proven red first.
- `DescriptorRegistryTest` covers the new resource type; `OpenApiDocumentTest` regenerates.

### 9.2 Negative tests, in-package

- **`programme.ProgrammeScopeTest`** — the one that matters most. A programme participant who is
  *not* a case participant reads the programme, gets a journey list filtered to what they can
  actually open, and 404s on the journey directly. This is Q20's non-negotiable, and it is the test
  that would have caught all three sub-project 1 escalations.
- `programme.ProgrammeIsolationTest` — cross-tenant ids are 404, never 200 and never 500.
- `workflow.CloneTest` — clone of a clone, duplicate clone for the same customer, unpublished source.
- `journey.PlanHoldTest` — satisfy refused while awaiting the first approval; `resume` refused; a
  second revision does **not** re-hold.
- `journey.PlanSnapshotImmutabilityTest` — the database refuses an `UPDATE` to `plan_revision_item`.
  Proven red: a guard nobody has seen fail is a guard nobody can trust.

### 9.3 Scope discipline

Every multi-scope permission gets at least one write test at its **narrowest** scope —
`programme.manage` at TEAM, `plan.issue` and `plan.approve_schedule` at ASSIGNED. Every write case in
`UserAdminTest` converged on ALL, which is precisely why that escalation survived.

### 9.4 End-to-end

A new Playwright spec covering the whole arc: clone a catalogue template for a customer → tailor it →
publish → submit and approve the shape → open a journey (which starts held) → issue a schedule
revision → approve it → the hold releases and the first requirement can be satisfied. Plus a
programme spec covering the rollup and the scope filter.

Phase 0 runs the existing ten specs green **before** this branch changes anything, so the first
failure is attributable.

---

## 10. Invariant cross-check

A change breaking one of these is a change to the design, not an implementation detail.

1. A programme has no lifecycle — nothing in `programme` calls `CaseEngine.reconcile`.
2. `journey` never imports a `programme` type; the arrow is one-way, proven by its own rule.
3. Programme participation grants read of the programme only; every journey read goes through
   `AuthorizedQuery` under `case.view`.
4. Programme progress is derived on read, never stored, and no request type accepts one.
5. A published workflow version still never mutates — approvals live in their own table *because* the
   freeze trigger refuses everything else.
6. A case still has exactly one pinned version; cloning and refreshing create versions and never
   repin outside existing migration.
7. `plan_revision_item` is append-only at the database layer, by GRANT, on `audit_event`'s argument.
8. The plan hold reuses `Case.held_at` and `CaseOnHoldException` — no second pause mechanism.
9. Progress stays one number over every milestone; `portal_visible` filters rendering only, never the
   denominator. `CaseEngine` is not touched at all.
10. Out-of-scope and cross-tenant ids are 404, and `PUT` request/view types stay field-for-field
    aligned.

---

## 11. Inherited state

### 11.1 Phase 1 — the six items open from sub-projects 2 and 3

Sub-project 3 opened with a phase that closed all eight of sub-project 1's backlog items before
touching feature work. 3A repeats it, and two of the six are directly in its own path:

1. **No task-edit UI at all** — `PUT /tasks/{taskId}` has no frontend hook; title, description,
   priority, due date, milestone and assignee are all uneditable. Confirmed independently three
   times and deferred twice.
2. **`taskSummary` rendered nowhere and not scope-filtered** — either wire it into the roadmap UI
   with a `task.view` filter or drop the field. In 3A's path: it is the same aggregate-only leak
   shape §6.4 refuses to repeat.
3. **Design spec §8.2's "Do now, sorted by due date" was never implemented** — `TaskService.myWork`
   and `forCase` both pass `Pageable.unpaged()` with no `Sort`.
4. **`task.assigned` has no `AuditActions` constant** and nothing records a reassignment; sub-project
   6 is specified to subscribe to it. `TaskService.create`'s ad-hoc path also records no
   `task.created`.
5. **`AuthorizationCoverageTest`'s name-shaped rule** — rebind it to any class in the covered
   packages that injects a `*Repository`, with an explicit exclusion list, so the three `task`
   classes named to dodge it become visible exclusions and `customer.OrgUnitResolver`'s no-op
   exclusion stops being a no-op. In 3A's path: a new module is added under this rule.
6. **`task.manage` and `approval.decide` seeded to Administrator only** — the role-template review.
   In 3A's path: §6.1 adds six more permissions and would otherwise leave four Administrator-only.

### 11.2 QA.md amendments this design requires

Two sentences in QA.md are wrong as written, and both are corrected in the same commit as this spec:

- **Q21's migration sentence.** "The existing migration tool is the deliberate, per-journey way to
  pull an upstream improvement down later" cannot work: `MigrationService.casesOnAnOldVersionOf`
  filters by `templateId`, so migration only moves cases between versions of the **same** template,
  and with no link back there is no path from a customer clone to its catalogue source at all.
  Corrected to describe §5.2's refresh: `cloned_from_template_id` is provenance, refresh produces a
  new draft of the *customer's own* template, and ordinary migration then moves that customer's
  journeys forward within it. The pointer is not a propagation path, so Q21's "edits never flow
  automatically, in either direction" holds unchanged.
- **Q20's participation sentence**, per §6.3: participation grants read of the programme; journey
  access comes from an explicit, audited `CaseParticipant` grant that `programme.manage` authorizes
  and that adding a programme participant offers to perform.

### 11.3 What stays open, untouched by this sub-project

- **TEAM-scoped user creation** — `CreateUserRequest` still has no `teamIds` field, so a TEAM-scoped
  `user.manage` holder cannot create any user. Open since sub-project 1, outside 3A's path.
- **The workflow builder has no UI to declare an attribute or set a stage's `entryCondition`.**
  Sub-project 2's own open item. 3A touches the builder (§8.4) but for the milestone visibility
  toggle only; closing this would be scope creep into a different gap.
- **The audit timeline read's `AuthorizedQuery` carve-out** remains the codebase's only one, and a
  second needs its own explicit argument rather than a copy of the exclusion. 3A creates none.

---

## 12. Question mapping

| Question | Where it is built |
|---|---|
| Q20 · Programmes above journeys | §3.1, §3.3, §4.1–4.3, §5.6, §6.2–6.4, §8.1 |
| Q21 · Customer-scoped workflow templates | §4.4, §5.1, §5.2, §8.2, §11.2 |
| Q22 · The plan approved twice | §3.2, §4.6–4.8, §5.3, §5.4, §8.3 |
| Q23 · A pending first schedule approval holds the journey | §5.5, §9.2 |
| Q24 · Internal-only milestones, one progress number | §4.5, §8.4, invariant 9 |
| Q25, Q26 · Meetings and recurrence | Sub-project 4A — not here |
| Q27 · Outputs are derived | Grows across 4 and 5 — not here |
| Q28 · Status reports are issued snapshots | Sub-project 8, over §4.7–4.8's mechanism |
