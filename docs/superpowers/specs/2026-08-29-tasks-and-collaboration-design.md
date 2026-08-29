# Tasks & Collaboration — Design Spec

**Date:** 2026-08-29
**Sub-project:** 3 of 10
**Status:** Draft — awaiting review
**Depends on:** Sub-project 2 (Workflow Engine & Case Lifecycle), merged to `main` at `f67c5f1`
**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` (current bundle, not the
superseded `docs/uispecs_legacy/`)

---

## 1. Context

Sub-projects 1 and 2 delivered the substrate and the runtime: tenancy with database-enforced
isolation, RBAC with record-level scope, the audit trail, customers, workflow authoring and
versioning, and the case-lifecycle engine. This sub-project builds the first thing that does *work*
inside a running journey rather than describing it.

It is also the first sub-project to fill a seam the previous one deliberately cut. `RequirementKind`
already contains `TASK`, and `SatisfyRequest`'s own javadoc reads "ref/refType are the seam
sub-projects 3-5 fill". Nothing here invents a way to connect tasks to the journey — the connection
was designed in advance and left unpopulated.

The "Collaboration" half is comments. PRD §11 lists "Comments Added" among the timeline's events,
and §8 lists comments and attachments on every task.

### What this sub-project deliberately does not build

- **Attachments.** Task and comment attachments need a document store, which is sub-project 4. Both
  entities carry a nullable `attachment_ref` / `attachment_ref_type` seam and nothing writes it.
- **Customer-facing commenting and Q9's three visibility tiers.** Q9 grants customers the right to
  post comments with attachments under company-shared / contact-only / sensitive visibility. Both
  halves depend on documents (4) and the portal (7). Comments here are internal-only, and the
  visibility column is not added speculatively.
- **Time tracking.** PRD §8 marks it optional. No screen in the design bundle renders logged time
  and the nearest consumer is Reporting (sub-project 9). Considered and rejected for this
  sub-project; revisit only if 9's requirements justify it.
- **Notifications on assignment or overdue.** Sub-project 6. The actions this sub-project records
  (`task.assigned`, `task.overdue` is not recorded at all) are what 6 will subscribe to.

---

## 2. Scope

| In | Out |
|---|---|
| `task` entity, ad-hoc and requirement-instantiated | Attachment storage or upload |
| Checklist items on a task | Time tracking |
| `comment` on tasks and journeys, internal authors | Customer-authored comments, visibility tiers |
| Task completion satisfying a requirement | Notifications, escalation, SLA on tasks |
| Cross-case "My work" board | Portal task screens |
| Case workspace Tasks tab | Reporting or analytics over tasks |

---

## 3. Architecture

### 3.1 Module

One new module, `co.ara.onboarding.task`, owning its entities, repositories, services and
controllers. `comment` lives inside it rather than in its own module: a comment has no lifecycle
independent of what it is attached to, and a separate module would need the same ports for the same
reasons.

### 3.2 Dependency direction, and the two ports that keep it one-way

`task` depends on `journey`. A task hangs off a milestone, may satisfy a requirement, and is scoped
through its case. That direction is correct and unremarkable.

Two requirements push the other way, and either one alone would close a cycle:

1. **Reopening a milestone must reopen its tasks.** `MilestoneService.reopen` already resets
   requirement state; without the equivalent for tasks, a reopened milestone shows requirements
   satisfied by tasks that are still marked complete, and no way to clear them.
2. **The roadmap wants task counts.** The design bundle types `Milestone.tasks[]`, and the Tasks tab
   needs a count before it is opened.

Both are solved the way this codebase has already solved it twice — `journey.CustomerDirectory`
implemented by `customer`, `identity.UserSessionRevoker` implemented by `auth`:

```
journey declares:
    TaskDirectory   — summaryFor(Collection<UUID> milestoneIds) → Map<UUID, TaskSummary>
    TaskLifecycle   — reopenForMilestone(UUID milestoneId)

task implements both. Spring wires by interface; journey never names a task type.

task    ──────►  journey        RequirementService.satisfy, AuthorizedQuery, StageWriteScopeGuard
journey ──X───   task           never a direct import, enforced structurally
```

`TaskDirectory.summaryFor` takes a **collection** of milestone ids and returns a map, not one id at a
time. A roadmap renders every milestone in a case; a per-id port would make that N queries, and the
shape of the port is what prevents it.

This gets its own named rule, `noJourneyDependencyOnTask`, rather than relying on the cycle check —
the same reasoning sub-project 2 applied when it added `noWorkflowDependencyOnJourney` and
`noJourneyDependencyOnCustomer` as separate rules. A one-way `journey → task` import would still
pass a plain no-cycles test.

### 3.3 Why `comment` is polymorphic, and why that is safe

A comment attaches to a task today and to a document, an agreement and a journey later. Three
parallel comment tables — `task_comment`, `document_comment`, `agreement_comment` — is the outcome
of avoiding a discriminator, and they drift.

The risk of a discriminator is that scope resolution becomes "resolve the parent, whatever it is",
which is the shape that produced the codebase's one `AuthorizedQuery` carve-out (the audit timeline,
§7.3 of sub-project 2's spec). CLAUDE.md is explicit that a second carve-out needs its own argument
rather than a copy of the first one's.

This design avoids needing one. **Every comment carries `case_id`**, denormalised, regardless of what
it is attached to. `CommentDescriptor` then resolves DEPARTMENT / TEAM / ASSIGNED through the case
exactly as `CaseDescriptor` does, so comment reads go through `AuthorizedQuery` normally. No
carve-out, no exclusion, no new argument required.

`resource_type` is constrained twice — a `CHECK` constraint in the migration and a Java enum — so
the discriminator cannot grow a third value without a migration and a compile error. A polymorphic
column with no allowlist is an unrestricted escape hatch; this one has a gate on both sides.

---

## 4. Data model

Three tables. Each carries `tenant_id`, an RLS policy and `FORCE ROW LEVEL SECURITY` created in the
same migration as the table, per the standing invariant. UUIDv7 primary keys via `Uuid7.generate()`.
All timestamps `timestamptz`, UTC.

### 4.1 `task`

| Column | Notes |
|---|---|
| `id`, `tenant_id` | |
| `case_id` | NOT NULL, denormalised — see below |
| `milestone_id` | NOT NULL; a task always belongs to a milestone |
| `requirement_id` | NULLABLE; null means ad-hoc |
| `title`, `description` | |
| `priority` | `LOW` / `MEDIUM` / `HIGH` |
| `status` | `PENDING` / `IN_PROGRESS` / `WAITING` / `COMPLETED` / `CANCELLED` |
| `assignee_id` | NULLABLE, references `app_user` |
| `due_date` | `date`, nullable |
| `completed_at`, `completed_by` | set on transition to COMPLETED |
| `cancelled_at`, `cancellation_reason` | reason NOT NULL when cancelled |
| `attachment_ref`, `attachment_ref_type` | seam for sub-project 4; nothing writes them |

`case_id` is denormalised rather than reached through `milestone_id` because **"My work" is a
cross-case query**. Without it, every task read joins task → milestone → case before the scope
predicate can be applied, and the descriptor would have to express scope through a join rather than
a column. `Milestone` already carries `case_id` for the same reason.

`requirement_id` is nullable and UNIQUE where not null: a requirement is satisfied by at most one
task.

**Assignee defaulting.** A task instantiated from a requirement defaults `assignee_id` to the
milestone's owner, following Q15's resolution that the `OWNER` participant is the default owner —
an instantiated task arriving unassigned would put work in nobody's queue at the exact moment the
journey opens. An **ad-hoc** task may be created unassigned: it is often filed before it is known who
will do it. Both are reassignable under `task.manage`.

### 4.2 `task_checklist_item`

`id`, `tenant_id`, `task_id`, `label`, `done`, `ordinal`.

Checklist items **do not affect progress**. Progress is derived from requirements (Q6) and stored by
the engine; nothing here counts checklist items into a numerator. A checklist is a private aid to
whoever holds the task.

### 4.3 `comment`

| Column | Notes |
|---|---|
| `id`, `tenant_id` | |
| `case_id` | NOT NULL — what `CommentDescriptor` scopes on |
| `resource_type` | `CHECK (resource_type IN ('task','onboarding_case'))` |
| `resource_id` | |
| `author_id` | references `app_user` |
| `body` | NOT NULL, non-blank |
| `edited_at` | nullable; an edited comment says so |

No `deleted` column. Business records are never deleted (DELETE is revoked at the database), and a
comment that can vanish is not a collaboration record. Correcting one is an edit, and the edit is
visible.

---

## 5. Lifecycle

### 5.1 Statuses

```
PENDING ──► IN_PROGRESS ──► COMPLETED
   │             │
   └──► WAITING ─┘
   
any open state ──► CANCELLED
```

`COMPLETED` and `CANCELLED` are terminal. The only route back out of `COMPLETED` is a milestone
reopen (§5.3).

### 5.2 Completion, and why it introduces no new engine path

Sub-project 2's invariant 4 is that every runtime mutation goes through `CaseEngine.reconcile` under
`CaseRepository.lockById`'s row lock, and nothing else calls it. This design does not add a second
caller.

A requirement-linked task completing calls the **existing** `RequirementService.satisfy(requirementId,
taskId, "task")`, which already takes the lock, reconciles, and audits. The invariant holds by
construction rather than by discipline.

That has an authorization consequence which must be stated rather than discovered: `satisfy` is
gated on `milestone.complete`, and `@RequirePermission` applies across the bean boundary. **Completing
a requirement-linked task therefore requires both `task.complete` and `milestone.complete`.** This is
correct — finishing work that clears a mandatory requirement is milestone work — but a hand-built
role holding one without the other will be refused, and the refusal has to be legible. The twelve
seeded templates that hold `milestone.complete` gain `task.complete` at the same scope.

**Ad-hoc tasks do not reconcile.** A task with no `requirement_id` cannot move progress, status or
stage, because progress is derived from requirements and nothing else. The argument is written into
the code, not just here: if a later sub-project ever makes tasks count toward progress, this is the
line that must change.

### 5.3 Reopen

`MilestoneService.reopen` calls `TaskLifecycle.reopenForMilestone(milestoneId)` in the same
transaction. `COMPLETED` tasks return to `PENDING`, and `completed_at` / `completed_by` are cleared.

Cancelled tasks stay cancelled. Reopening a milestone must not resurrect work somebody deliberately
abandoned.

### 5.4 What cancellation revokes

CLAUDE.md makes "what does this new entity's deactivation revoke?" a required design question for
anything retirable, asked before the setter is written rather than after. For a task, cancelling:

- **does not** satisfy its requirement — the requirement stays open, so a milestone cannot advance
  because someone gave up. Cancelling is not a silent waiver, and `requirement.waive` deliberately
  demands a non-blank reason that this must not route around.
- **removes it from every "My work" bucket** — a cancelled task is not work.
- **must not fire assignee notifications** when sub-project 6 adds them.
- **requires a reason**, NOT NULL, for the same reason a waiver does.

It is recorded as its own action, `task.cancelled`, written when an update *transitions* status into
CANCELLED — never when a status merely arrives CANCELLED. This follows `contact.deactivated`, whose
own rationale is that the event is the only record the retirement happened and must stay
distinguishable from an unrelated edit.

### 5.5 Audit actions, and their cause-before-effect ordering

New actions: `task.created`, `task.assigned`, `task.status_changed`, `task.completed`,
`task.cancelled`, `comment.added`, `comment.edited`.

Each is recorded **before** any call that records further events — `AuditRecorder` stamps
`occurred_at` from the clock, so call order is timeline order. This is not a general nicety; nine
call sites across five journey services had it backwards, and the resulting timeline showed
`case.created` after the `milestone.completed` events of its own creation. The rule is on
`AuditRecorder` and guarded by `journey.CauseBeforeEffectTest`; `task` gains its own cases in that
test rather than assuming the rule travels.

`timeline_visible` is set deliberately per action, not copied from a neighbour. Tasks and comments
are business records: visible. This matters because sub-project 7's portal reads on that flag.

---

## 6. Authorization

### 6.1 Permissions

Four, deliberately not more:

| Permission | Scopes | Covers |
|---|---|---|
| `task.view` | ALL / DEPARTMENT / TEAM / ASSIGNED | reading tasks |
| `task.manage` | ALL / DEPARTMENT / TEAM | create, edit, assign, cancel |
| `task.complete` | ALL / DEPARTMENT / TEAM / ASSIGNED | transitioning status |
| `comment.create` | ALL / DEPARTMENT / TEAM | posting a comment |

Reading comments is gated on `case.view`, matching the timeline: if you can see the journey, you can
see its discussion. A separate `comment.view` would be a permission nobody would ever grant
differently.

**Editing is author-only, and no scope widens it.** A holder of `comment.create` at `ALL` may post
anywhere they can see, but may edit only their own comments — rewriting somebody else's words
falsifies the collaboration record, and no breadth of scope is a reason to allow it. There is
deliberately no permission that grants editing another author's comment; the absence is the denial.

`task.complete` exists separately from `task.manage` so an assignee can finish their own work
without being able to edit or reassign anyone else's.

### 6.2 Descriptors

Two new implementations of `ResourceAuthorizationDescriptor`, in `scoping/` — never in the module
owning the entity, which would close a module cycle:

- `TaskDescriptor` — DEPARTMENT and TEAM resolve through the task's `case_id` to the case's
  `owning_department_id` / `owning_team_id`. **ASSIGNED resolves through `assignee_id` only.**
- `CommentDescriptor` — all three predicates resolve through `case_id`, identically to
  `CaseDescriptor`.

Both fail closed: no department and no teams ⇒ `cb.disjunction()`.

`ASSIGNED` meaning `assignee_id = actor` and nothing else is the `RelationshipType` invariant:
ASSIGNED is a *personal* relationship, and access mediated by a team the user belongs to is TEAM.
Conflating them silently widens ASSIGNED to everything the user's teams can reach.

### 6.3 Write-path obligations

- **Every id taken from a URL or request body goes through `AuthorizedQuery` before the write.** Three
  separate escalations in sub-project 1 were this exact shape. `@RequirePermission` cannot see
  arguments, so a passing gate proves only that the actor may touch *some* task.
- **`StageWriteScopeGuard` gates every task mutation.** A stage's `write_scope` narrows who may write
  inside it, on top of — never instead of — the record scope. There is no branch that widens.
- **`task..` and `comment..` are added to
  `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` in the same commit that adds
  the services.** The rule is an enumeration, and every enumeration in sub-project 1 drifted behind
  the code.

### 6.4 Portal assignees

`assignee_id` may reference a PORTAL user (a customer contact). This is safe by construction rather
than by intent: portal users hold no `task.*` grant, absence of a grant is the denial, and
`AuthorizationService` resolves authority per request. A contact assigned a task resolves zero
permissions and receives a 404.

The consequence is real and accepted: **a task assigned to a contact is invisible to them until
sub-project 7 builds the portal.** This is an explicit, tested expectation (§9), not an accident to
be discovered later.

---

## 7. API surface

```
GET  /api/t/{slug}/cases/{caseId}/tasks              list within a journey
POST /api/t/{slug}/cases/{caseId}/tasks              create ad-hoc
GET  /api/t/{slug}/tasks/{taskId}
PUT  /api/t/{slug}/tasks/{taskId}                    full replace
POST /api/t/{slug}/tasks/{taskId}/status             transition; reason required for CANCELLED
GET  /api/t/{slug}/tasks?assignee=me&bucket=…        "My work", cross-case
POST /api/t/{slug}/tasks/{taskId}/checklist
PUT  /api/t/{slug}/checklist/{itemId}
GET  /api/t/{slug}/cases/{caseId}/comments?resourceType=&resourceId=
POST /api/t/{slug}/cases/{caseId}/comments
PUT  /api/t/{slug}/comments/{commentId}
```

**`PUT` is a full replace, so `TaskView` must carry every field `UpdateTaskRequest` accepts.** A field
absent from the JSON body deserialises to null and is written as null — omitting it is identical to
blanking it, so "the form just doesn't send it" is not a mitigation. Adding a field to the request
without adding it to the view makes every client silently erase it.

Out-of-scope records return **404, never 403**, and the UI must not reintroduce the distinction the
404 exists to hide.

API types are generated, never hand-written: `./gradlew openApiSpec` then `npm run generate:api`.

---

## 8. Screens

Three surfaces, all implementing `docs/uispecs_latest/design_handoff_onboarding_platform/`. **The
design system is an input, not a deliverable** — these screens implement the bundle's visual
language and do not invent one. Every frontend task invokes the `frontend-design` and
`ui-ux-pro-max` skills, per CLAUDE.md.

### 8.1 Case workspace — Tasks tab

The tab already exists in the shell and currently renders nothing. Tasks grouped by milestone,
matching the roadmap's own order so the two read as the same journey.

### 8.2 "My work" — cross-case board

`SCREENS.md` §5: four columns, same column and card treatment as the stage board; cards are title
12.5px/600, context 11.5px, then a chip and the customer name. Headline "Only what you can act on."

**The four columns are not the five statuses**, and the bundle leaves the mapping implicit. Making it
explicit:

| Column | Contents |
|---|---|
| Do now | `PENDING`, sorted by due date, risk-coloured when overdue |
| In progress | `IN_PROGRESS` |
| Waiting | `WAITING` — separated so customer-blocked work never inflates the queue |
| Done this week | `COMPLETED` with `completed_at` inside 7 days |

`CANCELLED` appears in no column, which is what makes the headline true rather than decorative.

### 8.3 Comment threads

On a task, and on the journey itself. Author, relative time, body. Editing marks the comment edited.

### 8.4 Binding design decisions

- Colour always means status, never decoration. If the state cannot be named, the colour is neutral.
- Colour is never the only signal — every status colour is paired with a word or an icon.
- Instrument Sans for human text; Spline Sans Mono for machine-generated values (due dates, counts,
  ids). A task title is sans; its due date is mono.
- Cards are flat. Elevation is only for what genuinely floats.

### 8.5 The four gaps the design system does not cover

CLAUDE.md names these as the implementation's responsibility:

- **Empty states, per column rather than per board.** An empty "Do now" means something good
  ("Nothing needs you right now"); an empty "Waiting" is merely neutral. One blank board says
  neither. Every empty state carries an action or a reason, never blank space.
- **Loading skeletons** reuse the existing `SkeletonRows`, sized to the card so columns do not
  reflow when data lands.
- **Error states carry a recovery path** — a retry control, not only a message. Validation errors
  sit next to the field, never in a banner at the top.
- **Below 1440px:** four columns collapse to two, then to a single ordered list at the existing
  1024px breakpoint. The board never scrolls horizontally.

Additionally, **state lives in the URL**: the Tasks tab and every board filter are deep-linkable, so
a filtered queue is shareable and the back button behaves.

---

## 9. Testing

TDD throughout: the failing test first, security tests before the mechanism they verify. A
structural guard nobody has seen fail is a guard nobody can trust — every new one is proven red.

### 9.1 Guards extended in the same commit as the code

- `ModuleBoundaryTest` gains `noJourneyDependencyOnTask`, its own named rule.
- `AuthorizationCoverageTest` gains `task..` and `comment..` in the finder rule; every public
  `*Service` method carries `@RequirePermission`.
- `RlsCoverageTest` needs no change — it is deny-by-default over the live schema, so the three new
  tables are covered the moment they exist. If it passes without modification, that is the proof.
- `journey.CauseBeforeEffectTest` gains task and comment cases.

### 9.2 Negative tests, in-package

- A PORTAL user assigned a task gets 404 on it (§6.4).
- `task.complete` without `milestone.complete` is refused on a requirement-linked task, and allowed
  on an ad-hoc one.
- A cancelled task leaves its requirement OPEN and its milestone unable to complete.
- A wider-scoped holder is still refused inside an `OWNER_ONLY` stage (`write_scope`).
- A cross-tenant task id is a 404 — never the 200 a bypassed-RLS FK check would produce.
- `comment.resource_type` outside the allowlist is refused by the database, not only by Java.

### 9.3 Scope discipline

**At least one write test runs at the narrowest scope.** Every write case in sub-project 1's
`UserAdminTest` granted `USER_MANAGE` at `ALL`, which is precisely why an escalation survived: not
one test asked what a narrow write scope does.

### 9.4 End-to-end

A Playwright spec covering: create an ad-hoc task, assign it, complete a requirement-linked task and
watch the milestone advance, cancel a task and confirm the milestone does *not* advance, and post a
comment.

---

## 10. Invariant cross-check

A change breaking one of these is a change to the design, not an implementation detail.

1. `journey` never imports a `task` type; both directions of the relationship go through
   `TaskDirectory` / `TaskLifecycle`.
2. Task completion adds no new caller of `CaseEngine.reconcile` — it goes through the existing gated
   `RequirementService.satisfy`.
3. A cancelled task never satisfies or waives its requirement.
4. Checklist items never enter a progress calculation.
5. `comment.resource_type` is constrained at the database and in Java; adding a value is a migration.
6. Every comment carries `case_id`, so comment reads need no `AuthorizedQuery` carve-out.
7. `ASSIGNED` on a task means `assignee_id = actor`, never team-mediated.
8. Every audit action is recorded before the calls that record its consequences.
9. Out-of-scope and cross-tenant ids are 404.
10. `PUT` request and view types stay field-for-field aligned.

---

## 11. Inherited state, and what stays open

This sub-project inherits the sub-project 1 and 2 open items listed in CLAUDE.md, none of which are
in its own path. The implementation plan carries two explicit workstreams beyond the feature itself:

- **Run the nine Playwright specs**, which have never been run against the frontend visual refactor.
  Every guard the project has is structural or textual; none renders CSS in a browser, which is how a
  sidebar that compiled to nothing passed per-task review, a fix loop and a whole-branch review
  before a human noticed it on screen. Must run against a scratch database — the harness provisions a
  tenant per spec file and never truncates.
- **Work the CLAUDE.md open-item backlog**: the `user.manage` create-form department gap, the
  ownership-FK cross-tenant existence oracle, deactivation not invalidating pending credentials, the
  unvalidated tenant slug, `DB_APP_PASSWORD`'s committed default, contact email drift and the
  case-sensitivity mismatch, the three unaudited `authz`/`auth` write paths, contact retirement not
  revoking portal access, the missing 409 in the OpenAPI document, and Q18's journey-name schema
  addition.

Neither is feature work, and neither should be folded silently into a task that is.

---

## 12. Question mapping

| Question | Where it lands |
|---|---|
| Q5 · Force-complete authority | Unchanged; tasks do not add a force path |
| Q6 · Progress calculation | §4.2, §5.2 — tasks and checklists never enter progress |
| Q9 · Customer write access | Deferred to 4 and 7; §1 records why |
| Q15 · Assignment resolution | §4.1 — instantiated tasks default to the milestone's owner; ad-hoc may be unassigned. §6.4 — a portal contact may be the assignee |
| PRD §8 · Task fields | §4.1; time tracking rejected with reasons in §1 |
| PRD §11 · Activity timeline | §5.5 — task and comment actions are `timeline_visible` |
