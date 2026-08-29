# Workflow Engine & Case Lifecycle — Design Spec

**Date:** 2026-08-21
**Sub-project:** 2 of 10
**Status:** Approved
**Depends on:** Sub-project 1 (Foundation & Tenancy), merged to `main` at `9c26555`

> **Design system superseded (2026-08-25).** Every `docs/uispecs` reference below describes the
> design system this sub-project's frontend was actually built against, now at
> `docs/uispecs_legacy/`. It is accurate history and is left as written. For any new frontend
> work — including the sub-project 1–2 refactor — the current design system is
> `docs/uispecs_latest/design_handoff_onboarding_platform/`; see CLAUDE.md's "UI/UX" section.

---

## 1. Context

Sub-project 1 delivered the substrate: tenancy with database-enforced isolation, identity, RBAC with
record-level scope, authentication, the audit substrate, and customer management. This sub-project
builds the first domain that sits on it, and the one the remaining seven depend on: configurable
workflow definitions, and the running cases that execute them.

Six sub-projects list this one as a dependency (3, 6, 7, 8, 9 directly; 5 transitively), so its
seams matter more than its features. Tasks, documents and agreements will each need a way to
contribute to a milestone's completion; the portal will need a customer-safe view of the same
roadmap; dashboards and reports will need progress and stage occupancy without recomputing them.

### 1.1 Decisions taken before design

| Question | Decision |
|---|---|
| Who authors workflows | The **tenant** administrator, through a UI — not the platform administrator, and not by API alone |
| Builder scope | The full editable builder screen from `docs/uispecs`, not a read-only view |
| Stage/milestone rendering | Milestone rows, with the stage as a group header suppressed when a stage holds one milestone of the same name |
| Documents | Attachable to **any** milestone, not only a Document Collection stage |
| Milestone completion | Requirement rows now, satisfied manually; sub-projects 3–5 satisfy their own kinds |
| Branch conditions read | Customer fields **and** template-declared case attributes |
| Version migration | Pinning, eligibility computation, and the review screen — all three |
| Sub-project 1 leftovers | Both included: team-membership endpoints and UI, and the `user.manage` escalation guard |
| Light theme tokens | Fixed and measured in this sub-project |

### 1.2 Resolved product questions this sub-project implements

`Q1` stage contains many milestones · `Q2` freeze-by-default with explicit migration · `Q5`
force-complete requires an approval flow and a recorded reason · `Q6` progress weighted by
`estimatedDurationDays` · `Q7` conditional branching · `Q8` business days, SLA pauses on hold ·
`Q15` the `OWNER` participant is the default milestone owner.

---

## 2. Scope

**In scope.** Workflow templates with immutable published versions; stages, milestone definitions,
requirement definitions, dependencies, branch rules and template-declared attributes; the builder
screen; case creation, participants, milestones, requirements, hold and completion; branch
evaluation, entry conditions, auto-advance; approvals and force-complete; weighted progress and
business-day due dates; version migration with eligibility and a review screen; the journey
workspace (Journey and Timeline tabs, header, case switcher); the first audit read path; team
membership endpoints and UI; the role-delegation escalation guard; the light-theme token fix.

**Out of scope.** Tasks (3), documents (4), agreements (5), notification delivery and SLA
escalation (6), the customer portal (7), dashboards and WebSockets (8), reporting (9), packaging
(10). Also out: the no-code *condition builder* beyond a closed operand/operator vocabulary,
rework loops as branch targets, and tenant-configurable business calendars — see §5.6 and §11.

---

## 3. Architecture

### 3.1 Two new modules

`ModuleBoundaryTest` slices on the first package segment under `co.ara.onboarding` and requires
freedom of cycles, so module placement is a build-failing decision rather than a stylistic one.

**`workflow`** — authoring and versioning. `WorkflowTemplate`, `WorkflowVersion`, `Stage`,
`MilestoneDefinition`, `RequirementDefinition`, `AttributeDefinition`, `BranchRule`, and the
publish validator. Depends on `authz`, `audit`, `platform`, `tenancy`. It knows nothing about a
running case.

**`journey`** — the runtime. `Case`, `CaseParticipant`, `Milestone`, `Requirement`,
`CaseAttributeValue`, `Approval`, `CaseEngine`, migration eligibility, and the timeline read.
Depends on `workflow`, `authz`, `audit`, `platform`, `tenancy`.

Descriptors for the new resource types live in `scoping/`, as the existing four do — a descriptor
inside the module owning its entity would close a cycle.

`case` is a Java keyword, so the runtime package is `journey`, matching what the UI calls the
screen. The entities inside it are still `Case`, `Milestone`, `Requirement`.

### 3.2 The customer dependency inverts

`journey` does **not** import `customer`. It declares a port and `customer` implements it, which is
the idiom sub-project 1 established twice: `authz.ActorDirectory` is declared in `authz` and
implemented by `identity.IdentityActorDirectory`; `identity.UserSessionRevoker` is declared in
`identity` and implemented by `auth.RefreshTokenService`.

```java
// journey/CustomerDirectory.java
public interface CustomerDirectory {
    /** Empty when no such customer is visible to the actor. journey maps empty to 404. */
    Optional<CustomerFacts> findVisible(UUID customerId);
}

// journey/CustomerFacts.java
public record CustomerFacts(UUID id, String status, String industry, String country,
                            UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId) {}
```

Three properties of that shape are load-bearing:

- `status` is a `String`. The `CustomerStatus` enum stays in `customer`; branch conditions compare
  strings anyway.
- The implementation resolves through `AuthorizedQuery.getById(..., CUSTOMER_VIEW, id)` **inside**
  `customer`. So opening a case against another tenant's customer id is a 404 — not the 200 that a
  bypassed-RLS foreign key check produces, which is the existence oracle sub-project 1 left open on
  `customer.owner_user_id` (§11).
- The ownership triple is present because a case **copies** it at creation. That keeps the case and
  milestone descriptors reading their own columns instead of joining `customer` to resolve scope.

No display data crosses the port. The journey workspace header composes the case response with the
`useCustomer(customerId)` call the customers screen already ships.

`CustomerDirectory` is a `*Directory`, and `CLAUDE.md` records that
`AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` is name-shaped and binds
only to `*Service`, leaving `*Directory` invisible to it — "correct today, but a future `*Directory`
taking a foreign id would be unguarded." This is that `*Directory`, and it takes a customer id from
a request body. The rule widens to `*Directory` in the same commit, with the two existing
implementations excluded per-class and commented, since both run before there is an actor to
authorize.

### 3.3 The workflow/runtime boundary, enforced twice

**Structurally**, by two named ArchUnit rules — not merely by the cycle rule, which passes a
one-way violation:

```java
noClasses().that().resideInAPackage("..workflow..")
    .should().dependOnClassesThat().resideInAPackage("..journey..")
    .because("a version describes an executable definition; where a case sits is journey's alone");

noClasses().that().resideInAPackage("..journey..")
    .should().dependOnClassesThat().resideInAPackage("..customer..")
    .because("journey consumes CustomerDirectory, never customer's entities or repositories");
```

**In the schema.** No `workflow` table carries a column referencing a case, and none stores an
aggregate of them: the builder's "31 cases on v4 / 18 eligible to migrate" is computed in `journey`
at read time. A published version is immutable by database permission, not by service discipline —
§4.3.

---

## 4. Data model

Fourteen tenant-owned tables across two migrations, `V12__workflow.sql` and `V13__journey.sql`,
split on the module line. Every one has `tenant_id uuid NOT NULL REFERENCES tenant(id)`, a
`SELECT enable_tenant_rls(...)` call in the same migration, and `GRANT SELECT, INSERT, UPDATE` —
`V5_1` revoked the schema-wide default, so a new table starts with nothing.
UUIDv7 keys via `Uuid7.generate()`, `timestamptz` throughout, enums as `varchar` with
`@Enumerated(STRING)`, and every index leading with `tenant_id` because RLS adds a `tenant_id`
predicate to every query.

**Amended 2026-08-21, while writing the plan.** This section originally said no `DELETE` on any of
the fourteen. That holds for the six `journey` tables and for `workflow_template`, and fails for the
seven definition tables: editing a draft must be able to *remove* a stage, and discarding a draft
must delete it. An unpublished definition row is configuration bookkeeping — the category
sub-project 1 granted `DELETE` to with a comment (`role`, `role_grant`, `user_role`, `team_member`,
`login_attempt`) — not a business record. So `V12` grants `DELETE` on `workflow_version`, `stage`,
`milestone_definition`, `milestone_dependency`, `requirement_definition`, `attribute_definition` and
`branch_rule`, and §4.3's trigger is what makes that safe: any `UPDATE` or `DELETE` whose version is
not explicitly `DRAFT` is refused, so deletion is reachable for drafts only. `workflow_template` is
deactivated rather than deleted, and the six `journey` tables carry no `DELETE` at all.

### 4.1 `workflow`

| Table | Columns of interest |
|---|---|
| `workflow_template` | `name`, `description`, `status ACTIVE\|INACTIVE`, `current_version_id` |
| `workflow_version` | `template_id`, `version_no`, `status DRAFT\|PUBLISHED`, `published_at`, `published_by`, `@Version` optimistic lock |
| `stage` | `version_id`, `ordinal`, `name`, `responsible_department_id`, `requires_approval`, `auto_advance`, `portal_visible`, `sla_days`, `write_scope`, `notification_template_key`, entry-condition columns, `fallback_next_stage_id` |
| `milestone_definition` | `stage_id`, `ordinal`, `name`, `description`, `estimated_duration_days` |
| `milestone_dependency` | `milestone_definition_id` → `depends_on_milestone_definition_id` |
| `requirement_definition` | `milestone_definition_id`, `ordinal`, `kind TASK\|DOCUMENT\|APPROVAL\|MANUAL`, `label`, `weight`, `mandatory`, `document_category`, `approver_relationship` |
| `attribute_definition` | `version_id`, `key`, `label`, `data_type STRING\|NUMBER\|BOOLEAN\|ENUM\|DATE`, `required`, `allowed_values text[]` |
| `branch_rule` | `stage_id`, `ordinal`, condition columns, `target_stage_id` |

Template names are unique on `(tenant_id, lower(name))`. Sub-project 1 shipped
`customer_contact` unique case-**sensitively** while `app_user` was unique on `lower(email)`, and
the disagreement produced a real defect; the uniqueness rule for a human-typed name is `lower()`
here and everywhere after.

A partial unique index allows **one `DRAFT` per template**, so two administrators editing the same
workflow collide immediately rather than silently forking two drafts.

`stage.sla_days` and `milestone_definition.estimated_duration_days` are not the same number and must
not be conflated: the duration is expected effort and drives the schedule and Q6's weighting (§5.6),
while the SLA is the promise the tenant makes about that stage and drives breach and escalation in
sub-project 6. A stage can be planned at three days and promised in five.

`requirement_definition` uses typed nullable columns per kind rather than a `params jsonb`. A JSON
bag invites sub-projects 3–5 to write whatever they like into a column nobody validates; a typed
column forces each of them to add its own forward-only migration deliberately.

### 4.2 The condition shape

One `@Embeddable` used in two places — a stage's entry condition, and a branch rule:

`source CUSTOMER|ATTRIBUTE` · `key` · `operator EQ|NEQ|GT|GTE|LT|LTE|IN|IS_SET` · `value` ·
`values text[]`

`CUSTOMER` keys resolve through `CustomerDirectory`; `ATTRIBUTE` keys resolve against the version's
own `attribute_definition` rows, which is what lets publish reject a condition naming a key the
template never declares. One condition per rule: several rules express OR (first match wins), and
AND is deliberately absent until something asks for it.

### 4.3 Published versions are immutable at the storage layer

Editing a published workflow **inserts a new `DRAFT`** by deep copy; publish flips that draft to
`PUBLISHED`; a `BEFORE UPDATE OR DELETE` trigger on `workflow_version` and each child table rejects
any write whose version is already published. Publish is therefore the last legal write to a
version row.

Nothing needs an exception for archiving, because "no longer offered to new cases" is
`workflow_template.current_version_id` pointing elsewhere — not a status change on a frozen row.
This is the same reasoning that makes `audit_event` append-only by `GRANT` rather than by
convention: a definition the application can rewrite is not a definition anyone can trust a running
case to.

### 4.4 `journey`

| Table | Columns of interest |
|---|---|
| `onboarding_case` | `customer_id`, `template_id`, `version_id` (pinned, `NOT NULL`), `status ACTIVE\|ON_HOLD\|COMPLETED\|CANCELLED`, `current_stage_id`, `progress_percent`, `target_completion_date`, `held_at`, `total_hold_days`, `owner_user_id`, `owning_department_id`, `owning_team_id`, `started_at`, `completed_at` |
| `case_participant` | `case_id`, `user_id`, `relationship` (`authz.RelationshipType`), `status ACTIVE\|REMOVED`, unique on `(case_id, user_id, relationship)` |
| `milestone` | `case_id`, `milestone_definition_id`, `status PENDING\|ACTIVE\|BLOCKED\|DONE\|SKIPPED`, `owner_user_id`, `due_date`, `progress_percent`, `completed_at`, `completed_by`, `completion_reason` |
| `requirement` | `case_id`, `milestone_id`, `requirement_definition_id`, `status OPEN\|SATISFIED\|WAIVED`, `satisfied_at`, `satisfied_by`, `satisfied_ref`, `satisfied_ref_type`, `waiver_reason` |
| `case_attribute_value` | `case_id`, `attribute_definition_id`, typed value columns, unique on the pair |
| `approval` | `case_id`, `kind STAGE_EXIT\|FORCE_COMPLETE`, `stage_id` (set for `STAGE_EXIT`), `milestone_id` (set for `FORCE_COMPLETE`), `requested_by`, `requested_at`, `reason NOT NULL`, `status`, `decided_by`, `decided_at`, `decision_note` |

`onboarding_case`, not `case` — reserved word. There is no case reference sequence: the mono ID the
prototype shows is `shortId()` of the UUIDv7, exactly as the customers screen already renders one.

`case_id` is denormalized onto `milestone`, `requirement` and `approval` so each descriptor resolves
scope through **one** subquery hop to `onboarding_case`, and so every index can lead
`(tenant_id, case_id)`.

Four modelling decisions worth their reasons:

**Instances join their definitions; they never copy them.** A `milestone` stores no name and no
weight. Copying would drift, and it would defeat migration — bringing a case onto v5 is *meant* to
change what its milestones say.

**`requirement.satisfied_ref` carries no foreign key.** Its target is a task, document or agreement
in a module that does not exist yet, so it is a soft reference with a `_type` discriminator. This is
a deliberate exception to the schema's habits, and it has a security benefit: a real FK would be
checked with row security bypassed, which is exactly the mechanism behind the cross-tenant
existence oracle in §11.

**`progress_percent` is stored and never accepted.** Weighted per Q6 — a milestone's percent from
its satisfied requirement weights, a case's from its milestones weighted by
`estimated_duration_days` — recomputed by the engine in the same transaction as any change, and
absent from every request type. Sub-projects 8 and 9 need it without a join over every requirement.

**A removed participant transitions status.** `DELETE` is revoked at the database, and a
participation that vanished leaves an unexplained gap in a case's history.

---

## 5. The engine

### 5.1 One reconciliation function, under a row lock

`CaseEngine.reconcile(case)` recomputes milestone statuses, progress, the current stage and due
dates **from persisted state**. It is idempotent. Every mutation path — satisfying a requirement,
deciding an approval, resuming from hold, and later a task closing in sub-project 3 or a document
arriving in 4 — follows the same sequence inside one transaction:

1. `AuthorizedQuery.getById(...)` resolves and authorizes the case.
2. The engine takes `SELECT … FOR UPDATE` on that case row.
3. The requirement, milestone or approval row is written.
4. `reconcile` runs.

Lock-first, always, so every entry point acquires locks in one order and two concurrent engine
transactions serialize instead of deadlocking. Idempotency makes retries safe; the lock is what
makes the outcome deterministic. An event chain — each mutation firing the next step — double-
advances the moment two sub-projects satisfy the last two requirements of a milestone
concurrently, and cannot be tested except end-to-end.

`CaseEngine` is **package-private** in `journey`, and `AuthorizationCoverageTest` gains `*Engine`
beside `*Service` in the same commit. `@RequirePermission` binds to public service methods, so a
public engine class outside that naming pattern would be an ungated entry point — the same
name-shaped-guard hole `CLAUDE.md` records for `*Directory`. Package-private means no HTTP path
reaches it except through a gated service; the widened rule means the next engine cannot either.

The locking finder is a named `CaseRepository.lockById`, callable only from `CaseEngine` — a
per-method, commented exclusion in the finder rule, since it re-reads a row `AuthorizedQuery` has
already resolved and widens visibility by nothing.

### 5.2 Instantiation

Creating a case resolves the customer through `CustomerDirectory.findVisible` (empty → 404, never
403), requires `template.current_version_id` to be `PUBLISHED`, validates submitted attributes
against that version's `attribute_definition` rows — required present, values within
`allowed_values`, types parseable — and pins the version id.

Ownership copies from the customer's triple. The customer's owner becomes the `OWNER` participant,
which per Q15 makes them every milestone's default owner. Then **all** milestones and requirements
for every stage are instantiated eagerly: the roadmap shows the whole spine from day one, with
future stages pending, and a lazily-built roadmap has nothing to draw.

### 5.3 Transitions

A milestone is `BLOCKED` while any dependency is not `DONE`, `ACTIVE` once its stage is current and
dependencies clear, `DONE` when every **mandatory** requirement is `SATISFIED` or `WAIVED`. A stage
is exitable when all its milestones are `DONE` or `SKIPPED`. On exit:

1. `requires_approval` → a `STAGE_EXIT` approval is created and the case waits. Nothing advances
   while an approval is pending.
2. Otherwise the stage's branch rules are evaluated in `ordinal` order, **first match wins**. No
   match falls to `fallback_next_stage_id`, and absent that, the next stage by ordinal.
3. The chosen stage's entry condition is evaluated. False → its milestones become `SKIPPED` and
   step 2 repeats from there.
4. `auto_advance` false → the case sits exitable until a holder of `case.advance` moves it. The
   engine computes the *available* transition without taking it.

Because branch targets are forward-only by ordinal (§5.5), the skip loop is strictly increasing and
terminates without a visited set.

**Terminal rule.** Exiting the final stage sets `status = COMPLETED`, stamps `completed_at`, and
leaves `current_stage_id` on that final stage — never on a skipped stage, never null. Publish
requires the final stage to carry **no entry condition**, so at least one stage is always
unconditionally enterable and the skip loop cannot run off the end.

**Skipped milestones leave the progress calculation entirely** — numerator and denominator — or a
case with any skipped stage could never reach 100%.

Conditions fail closed: an unset attribute makes a condition **false**, never true. A missing input
must not open a path, which is the rule every descriptor already follows.

### 5.4 Approvals and force-complete

Q5 asks for three things, and each is a separate mechanism rather than a policy note:

- **A reason.** `approval.reason` is `NOT NULL`, so the flow cannot be built without one.
- **Not a single click.** The decider must hold the relevant permission **and must not be the
  requester**. Self-approval is refused in the service.
- **A record.** The decision writes `milestone.force_completed` with the reason to `audit_event` at
  `timeline_visible = true`, so the customer-facing timeline shows a milestone was *forced*, not
  that it merely completed.

Among the twelve seeded templates only `Administrator` receives `milestone.force_approve`. Q5's
Director and VP do not exist as templates; a tenant wanting them can build them, since role
management already ships.

### 5.5 Publish validation

Publish returns **422 with every problem**, not the first:

1. The final stage carries no entry condition.
2. Every branch `target_stage_id` has a **higher** ordinal than its stage (forward-only).
3. Every dependency points at a milestone strictly earlier in plan order — a lower
   `(stage.ordinal, milestone.ordinal)` pair (backward-only).
4. Every condition key is a declared attribute or a known customer field.
5. Every stage holds at least one milestone.

Rules 2 and 3 exist together because the schedule in §5.6 plans intra-stage milestones as
sequential by ordinal: a dependency pointing forward would produce a plan the engine can never
follow.

A consequence an implementer will meet and should not mistake for a bug: because stages execute
strictly in order, and a stage exits only when all its milestones are `DONE` or `SKIPPED`, a
**cross-stage** dependency is always already satisfied by the time its stage becomes current. Only
intra-stage dependencies — or an earlier milestone that was reopened — can actually put a milestone
in `BLOCKED`. Cross-stage dependencies remain permitted because they document real sequencing for a
reader of the builder, and they matter after a reopen; they are not, however, a way to run stages in
parallel, which this model does not do. Rule 2 makes the graph a DAG by construction, so validation is an ordinal comparison rather
than cycle detection. The cost is explicit: "verification failed, go back to Document Collection"
is **not** a branch. It is reopening a milestone — an action with a permission
(`milestone.reopen`), a reason, and an audit event.

### 5.6 Dates and hold

A milestone's `due_date` is its stage's entry date plus the cumulative `estimated_duration_days` of
prior milestones **in that stage, by ordinal**, counted in business days per Q8.

**Dependencies gate `ACTIVE` and nothing else.** They never move a `due_date`. The only things that
shift one are a hold resuming and a migration recomputing open milestones. A blocked milestone
therefore goes overdue, which is exactly the signal the prototype draws — "blocked by X" in red
beside a red due date — rather than a schedule that quietly reflows to hide the blockage.

`ON_HOLD` stamps `held_at` and refuses requirement satisfaction until resumed. Resume adds the
elapsed business days to `total_hold_days` and shifts every open milestone's `due_date` and the
case's `target_completion_date` by that amount: a promise made while paused was not a promise
broken. Sub-project 6 reads `total_hold_days` rather than recomputing it, which is what Q8's "SLA
pauses while waiting for the customer" needs from this side.

**Consciously deferred:** Q8 says "the configured business calendar", and there is no tenant
settings surface at all — `tenant.settings.view/edit` are catalogued permissions with nothing
behind them and no `tenant_setting` table exists. This sub-project ships
`platform.BusinessCalendar` as an interface with a Monday–Friday implementation and no holidays.
Sub-project 6 replaces it with tenant-configured working days and a holiday table when it builds
the SLA machinery that needs them. Deferring is defensible; describing a hardcoded weekend rule as
configurable would not be.

### 5.7 Migration

A version publish leaves running cases pinned. `GET /cases/migration?versionId=` computes, per
case: eligible, or the reason it is not — a stage it has already passed no longer exists, or the
new version declares a required attribute the case has no value for. `POST /cases/migration`
repins the named eligible cases, re-instantiates milestones and requirements for stages not yet
passed, recomputes dates and progress, and audits one event per case.

---

## 6. Authorization and scoping

### 6.1 Catalog additions

`resourceType` is the table name; creation permissions are `ALL`-only because there is no record
yet to scope against.

| Key | Category | resourceType | Scopes |
|---|---|---|---|
| `workflow.view` | workflow | — | ALL |
| `workflow.manage` | workflow | — | ALL |
| `case.view` | journey | `onboarding_case` | RECORD |
| `case.create` | journey | — | ALL |
| `case.edit` | journey | `onboarding_case` | RECORD |
| `case.advance` | journey | `onboarding_case` | RECORD |
| `case.hold` | journey | `onboarding_case` | RECORD |
| `case.migrate` | journey | — | ALL |
| `milestone.edit` | journey | `milestone` | RECORD |
| `milestone.complete` | journey | `milestone` | RECORD |
| `milestone.reopen` | journey | `milestone` | ORG |
| `milestone.force_complete` | journey | `milestone` | ORG |
| `milestone.force_approve` | journey | — | ALL |
| `requirement.waive` | journey | `requirement` | ORG |
| `approval.decide` | journey | `approval` | ORG |

`case.create` being `ALL`-only is not a hole: creation resolves the target customer through
`AuthorizedQuery` under `customer.view`, so a Sales Representative holding `customer.view` at
`ASSIGNED` can only open a case on a customer they own. Authority to create is bounded by what you
can see — the same reasoning `customer.create` already carries.

Two keys are deliberately absent. Satisfying a requirement is `milestone.complete`, because it *is*
progressing the milestone, and sub-projects 3–5 will satisfy requirements through their own
objects' permissions. And `milestone.force_approve` is `ALL`-only because Q5 puts that authority
strictly above Project Manager; a scoped version would let a TEAM-scoped holder approve their own
team's forcings.

### 6.2 Four new descriptors

In `scoping/`, following `CustomerContactDescriptor`'s subquery pattern. `CaseDescriptor` reads the
case's own ownership triple for DEPARTMENT and TEAM. `MilestoneDescriptor`,
`RequirementDescriptor` and `ApprovalDescriptor` resolve through `case_id` — one hop each.

`CaseDescriptor.assignedScope` is an `EXISTS` over `case_participant` where `status = 'ACTIVE'` and
the relationship is in `{OWNER, ASSIGNEE, PARTICIPANT, APPROVER}`. `CREATOR` is excluded on the
reasoning `CustomerDescriptor` already gives: having once created a case is not an ongoing
relationship to it. This is the first real use of `assignedRelationships()` — sub-project 1
declared it on every descriptor but resolved ASSIGNED from a single column each time.

Every descriptor fails closed: no department, no teams, no participation ⇒ `cb.disjunction()`.

**Engine invariant:** assigning a milestone owner also creates their `ASSIGNEE` participant row.
Without it, Q15's "reassign a milestone to any user" hands someone a milestone inside a case they
cannot open — a 404 on the only screen that would explain their work.

### 6.3 `write_scope` subtracts, never grants

After the `@RequirePermission` gate and after `AuthorizedQuery` has resolved the record, the engine
checks the stage's `write_scope` against the actor's relationship to the case:

- `OWNER_ONLY` — must be the milestone owner or the case `OWNER`
- `TEAM` — the case's owning team must be among the actor's
- `DEPARTMENT` — departments must match
- `ANY` — narrows nothing

There is no branch that grants. A second mechanism able to widen authority would be a parallel
authorization system, which is the one thing this codebase must not grow.

### 6.4 The twelve templates

`RoleTemplateValidityTest` already checks every template grant against the catalog, so these cannot
drift from §6.1.

| Template | New grants |
|---|---|
| Administrator | every new key at `ALL` — the only holder of `workflow.manage`, `case.migrate`, `milestone.force_approve` |
| Project Manager | `case.view/edit/advance/hold`, `milestone.edit/complete/reopen/force_complete`, `requirement.waive` at `TEAM`; `workflow.view` |
| Account Manager | `case.view/edit` at `TEAM`; `workflow.view` |
| Sales Representative | `case.view` at `ASSIGNED`, `case.create`; `workflow.view` |
| Operations | `case.view/edit`, `milestone.complete` at `DEPARTMENT`; `workflow.view` |
| Legal · Finance · Compliance | `case.view`, `milestone.complete` at `ALL`; `workflow.view` |
| Technical · Support | `case.view`, `milestone.complete` at `TEAM`; `workflow.view` |
| Service Provider · Business Partner | `case.view`, `milestone.complete` at `ASSIGNED`; `workflow.view` |

`Administrator` alone gets the three administrative keys, for the reason it is already the only
template with `role.manage`: a tenant must not be able to escalate its own authority through an
operational role.

### 6.5 The two inherited leftovers

**Team membership.** `V4` already granted `DELETE ON team_member` with a comment explaining that a
pure join table changes by removing rows, so nothing is needed at the schema layer — only
`OrgStructureService` methods to list, add and remove members under `team.manage`, and a members
panel on the existing `admin/org` screen. The consequence is disproportionate to the work:
`ctx.teamIds()` becomes non-empty in a running system, so TEAM scope starts resolving for every
descriptor including the four new ones, and Account Manager, Project Manager, Technical and Support
stop granting nothing at all. One test earns its place specifically here — TEAM scope proven
through the HTTP API rather than through `TenantFixture.addToTeam`, since the fixture-only write
path is exactly why nobody noticed.

**The escalation guard**, in `RoleService.assignRole`, as a comparison rather than a hierarchy: for
every grant in the role being assigned, the assigner must hold that same permission at a scope that
covers it, where `ALL` covers everything and anything else must match exactly. No ordering is
invented between `DEPARTMENT` and `TEAM`, which are not comparable. It belongs in this sub-project
rather than a later one because this sub-project adds fifteen permissions, three of which only
`Administrator` should ever hold, and an unguarded delegation path hands all of them to any
`user.manage` holder for free.

---

## 7. API surface

Paths follow `/api/t/{tenantSlug}/…`. Controllers stay thin. Every response record carries every
field its `PUT` counterpart accepts — `CLAUDE.md`'s full-replace invariant, whose failure mode is a
client silently erasing a field it never sent.

### 7.1 Workflow authoring

At `/workflows`, not `/admin/workflows`: `workflow.view` belongs to every operational role that
needs to read the definition its case is frozen on, so gating by path would either exclude them or
make `/admin` mean nothing. The *frontend route* still lives under `admin/`.

| Endpoint | Gate | Notes |
|---|---|---|
| `GET /workflows` | `workflow.view` | templates + current version number |
| `POST /workflows` | `workflow.manage` | creates template and its first `DRAFT` |
| `GET /workflows/{id}` | `workflow.view` | template + version history |
| `GET /workflows/{id}/versions/{vid}` | `workflow.view` | the whole definition graph in one call |
| `POST /workflows/{id}/versions` | `workflow.manage` | new `DRAFT` by deep copy; **409** if one exists |
| `PUT /workflows/{id}/versions/{vid}` | `workflow.manage` | full replace of a draft; **409** on stale write |
| `POST /workflows/{id}/versions/{vid}/publish` | `workflow.manage` | **422** listing every validation failure |
| `POST /workflows/{id}/deactivate` | `workflow.manage` | status `INACTIVE`; never deleted |

**The draft is edited as one document.** Reordering stages, deleting one a branch rule targets,
renaming a milestone another depends on — these are graph edits, and per-element endpoints leave
dangling references between calls that publish then has to reject. One `PUT` carrying stages with
nested milestones, requirements, dependencies, branch rules and attributes validates the graph once
and writes it atomically; `GET` returns exactly that shape, satisfying the full-replace invariant
by construction. Concurrent editing surfaces as a 409 from JPA optimistic locking, not
last-writer-wins.

### 7.2 Cases

| Endpoint | Gate |
|---|---|
| `GET /customers/{customerId}/cases` | `case.view` |
| `POST /cases` | `case.create` (+ customer under `customer.view`) |
| `GET /cases/{id}` · `PUT /cases/{id}` | `case.view` · `case.edit` |
| `GET /cases/{id}/roadmap` | `case.view` |
| `POST /cases/{id}/advance` | `case.advance` |
| `POST /cases/{id}/hold` · `/resume` | `case.hold` |
| `GET /cases/{id}/participants` · `POST` · `POST /{userId}/remove` | `case.view` · `case.edit` |
| `PUT /cases/{id}/milestones/{mid}` | `milestone.edit` |
| `POST /cases/{id}/milestones/{mid}/complete` · `/reopen` | `milestone.complete` · `milestone.reopen` |
| `POST /cases/{id}/milestones/{mid}/force-complete` | `milestone.force_complete` |
| `POST /cases/{id}/requirements/{rid}/satisfy` · `/waive` | `milestone.complete` · `requirement.waive` |
| `GET /cases/{id}/approvals` | `case.view` |
| `POST /cases/{id}/stage-approvals/{aid}/decide` | `approval.decide` |
| `POST /cases/{id}/force-requests/{aid}/decide` | `milestone.force_approve` |
| `GET /cases/{id}/timeline` | `case.view` |
| `GET /cases/migration?versionId=` · `POST /cases/migration` | `case.migrate` |

**Approvals decide through two endpoints, not one,** because `@RequirePermission` is a static
annotation: a single `/approvals/{id}/decide` could not carry `approval.decide` for a stage exit
and `milestone.force_approve` for a forced completion, and choosing the gate inside the method body
would hide an authorization decision where no coverage test can see it.

`GET /cases/{id}` returns header facets and `customerId` — no customer name, no contacts. That is
§3.2 showing up in the API.

### 7.3 The timeline, and one documented carve-out

`AUDIT_VIEW` is catalogued and seeded to four templates but has **no endpoint**: the `audit` module
contains five classes, none of them a controller. So this sub-project builds the first audit read
path.

It must not reuse `AUDIT_VIEW`. `AuditEventDescriptor` scopes events **by actor** — DEPARTMENT and
TEAM resolve through `actorUserId` joined to `app_user`, and events with a null actor (`SYSTEM`)
match nothing but `ALL`. For a case timeline that is the wrong axis: a Project Manager holding
`AUDIT_VIEW` at `TEAM` would see only events their own teammates performed, so Legal's approval,
Finance's verification, a portal contact's upload and every system transition would vanish from the
history of a case they own.

`GET /cases/{id}/timeline` is therefore gated on **`case.view`**, in `journey`: resolve the case
through `AuthorizedQuery` — that resolution *is* the authorization — then read events by exact
`(resource_type, resource_id)`, which the existing
`audit_event_tenant_resource_idx (tenant_id, resource_type, resource_id, occurred_at DESC)` serves.
Journey calls a narrow `audit.AuditQuery.findForResource(...)` that takes strings and knows nothing
about cases; the dependency direction is the one that already exists for writing events, so no port
inversion and no cycle.

The cost is stated rather than discovered: those audit rows are read **without** passing through
`AuthorizedQuery`. It is a carve-out from the read invariant, justified by the parent resolution and
narrowed to one resource id, and it takes a commented exclusion in the finder rule — never a silent
one. The rejected alternative was a generic `GET /audit/timeline?resourceType=&resourceId=` in the
audit module, which keeps every read inside `AuthorizedQuery` but needs a `resourceType → permission
key` map whose failure mode is a missing entry defaulting to something.

The internal timeline shows every event for the case. `timeline_visible` remains what sub-project
7's portal filters on, and each new action sets it deliberately: case and milestone events visible,
workflow authoring and migration not.

### 7.4 OpenAPI

Every declared response gets an `@ApiResponse`, including the 409s and 422s above. Sub-project 1
shipped a real, tested 409 that springdoc never advertised, so `generated.ts` had no branch for it.
`OpenApiDocumentTest` writes `backend/build/openapi.json` during `:test`; `npm run generate:api`
regenerates the client types.

---

## 8. Screens

Implementing `docs/uispecs`, which is an input and not a deliverable.

### 8.1 Routes

`/t/{slug}/customers/{customerId}/cases/{caseId}?tab=journey` — the case is the unit of work, so it
belongs in the URL; the tab is a query param so reload, back and a shared link all land where the
reader was. The builder is at `/t/{slug}/admin/workflows` and
`/admin/workflows/{id}/versions/{vid}`, beside the existing `admin/users`, `admin/roles` and
`admin/org`.

### 8.2 Components

Reused as shipped: Card (3), Progress bar (5) — which already carries `role="progressbar"` with
`aria-valuenow`, closing review finding 9 for anything reusing it — Status pill (6), Table (7),
Avatars (16), rail and header. New: Chips (8) for the case switcher, Tabs (9), Milestone row (10)
with its 26px status circle and expanded panel, the checkbox from Task row (11) for requirement
rows, Workflow stage row and Inspector (12), Timeline row (15).

### 8.3 Where an editable builder departs from the prototype

`uispecs/README.md` §6 describes the inspector's fields as "read-only-styled" because the prototype
never saved anything. They become real inputs and selects keeping that geometry (32px, radius 8px,
`#fdfcfb`) plus the focus ring the design system defines from review finding 2.

Two things are kept rather than "improved": stage reordering stays the ▲▼ buttons the prototype
draws, because they are keyboard-operable and screen-reader-announceable for free and a drag
surface would be a new accessibility burden on a screen an admin touches twice a year; and the
builder saves through one explicit **Save** matching the atomic whole-draft `PUT`, with an
unsaved-changes guard on navigation rather than autosave, so a half-edited graph is never what
publish validates.

The inspector's **Notification template** field renders disabled with a hint that it arrives with
sub-project 6. The column exists and is authored; nothing acts on it yet, and a field that silently
does nothing is worse than one that says so.

### 8.4 The journey tab

Milestone rows are the expandable unit; the stage header appears only where a stage holds more than
one milestone. The expanded panel's left column lists **requirements** — the same checkbox row the
design draws for tasks — and its document chips are requirement rows of kind `DOCUMENT`, which is
what "every milestone can have documents" looks like before sub-project 4 exists.

One deliberate departure from prototype *behaviour*: §5a notes "checkbox state is real and local".
Here it cannot be. Satisfying a requirement recomputes the milestone's percent, possibly its
status, possibly the stage transition and the case's progress — server-side, inside one locked
transaction — so the checkbox awaits the round-trip and the roadmap query is invalidated on
success. Local optimism would show a milestone completing that `write_scope` then refuses.

Tasks, Documents and Agreements tabs render the design's empty state with a neutral message and no
affordance, rather than being hidden.

### 8.5 Screens the design does not cover

Built here, consistent with existing primitives: the migration review screen (a table of cases on
the old version, with an eligible column and, for the ineligible, the reason the engine computed);
and five Dialogs — create a case with its template-declared attributes, request an approval, decide
one, force-complete with a mandatory reason, place a case on hold. Reason fields are `required` in
the form because they are `NOT NULL` in the schema.

Plus the four gaps `CLAUDE.md` names for every sub-project: empty states (a customer with no cases,
a workflow with no stages), loading skeletons, error states, and layout below 1440px.

### 8.6 Below 1440px

Review finding 11 names the journey workspace as one of its two casualties. At ≤1280px the five
fact columns reflow to two rows and the builder's `1fr 320px` inspector unpins; at ≤1024px the
inspector stacks below the stage list and the case switcher chips scroll horizontally rather than
wrapping to three lines. Sub-project 1 already ships a 1024px fallback with a Playwright spec, so
this extends a pattern rather than inventing one.

### 8.7 Accessibility carried forward

Tabs get real `tablist`/`tab`/`tabpanel` semantics with arrow-key movement. The inspector's three
toggles are `button role="switch"` with `aria-checked`, not styled divs. Requirement checkboxes are
real inputs. The expanded panel's `fadeUp` and the chevron's 0.18s rotation honour
`prefers-reduced-motion`. And review finding 10 — colour is never the only signal — binds hardest
on the milestone status circle: four colours and three glyphs, so every row also carries its status
as a word in the pill, and "blocked by X" stays in text as drawn.

### 8.8 The light theme token fix

`CLAUDE.md` records that the shipped **light** tokens are measured by nothing, and that
`report_shipped("light")` at the close of sub-project 1 found **nine of 49 pairs failing** against
3:1 — `border-default` 1.15–1.28 on all four grounds, `border-strong` 1.44, `border-dashed` 1.68,
`accent-tint-border` 1.06, `accent-weak` 1.53, `solid-at-risk` 2.54. No *text* pair fails, which is
why axe stayed clean: its default rule set has no non-text contrast rule (WCAG 1.4.11).

Light is the default theme, and this sub-project's two largest screens are almost entirely borders.
The fix is the one already applied to dark: adjust the tokens in `build_tokens.py`, regenerate, copy
`tokens.css` and `tailwind.css` into `frontend/src/app/tokens.css` and `tailwind-theme.css`, and
turn `report_shipped("light")` on so a regression cannot be silent. Generated assets are never
hand-edited.

---

## 9. Testing

TDD throughout: the failing test first, security tests before the mechanism they verify, and every
new structural guard **seen red** before the code it protects. Backend runs as
`./gradlew cleanTest test` — never a bare `test`, which reports `BUILD SUCCESSFUL` having executed
nothing.

### 9.1 Structural guards

- `RlsCoverageTest` — all fourteen tables with `tenant_id`, a policy and `FORCE ROW LEVEL
  SECURITY`. **No allowlist entries added**; its four reviewed entries stay four.
- `AuthorizationCoverageTest` — gains `*Engine` for the permission-gate rule, and `workflow..` /
  `journey..` for the finder rule, which widens from `*Service` to `*Directory`. Three commented
  exclusions: `IdentityActorDirectory` and `UserRoleDirectory` (run before there is an actor to
  authorize), `CaseRepository.lockById` (callable only from `CaseEngine`), and the §7.3 timeline
  read.
- `ModuleBoundaryTest` — the two named rules of §3.3, each seen failing against a deliberate
  temporary import, because the cycle rule alone passes a one-way violation.
- `DescriptorRegistryTest` — the four new resource types; startup still refuses when a scoped
  permission has no descriptor.
- `DirectApiAccessTest` — **rewritten to derive** its list by sweeping every `@RestController`
  mapping instead of naming paths. It is eleven endpoints short today and this sub-project adds
  roughly thirty; a typed list would guarantee the same drift.
- `OpenApiDocumentTest` — every new endpoint and every error status documented.
- `contrast.py` — `report_shipped("light")` enabled, and a pair added for every new role. The first
  dark table was all-neutral and reported "0 of 17 fail" while the primary button sat at 1.53:1.

### 9.2 Security negatives

Extending the eight specs in `security/`:

- **Cross-tenant** — a case, milestone, requirement, approval or workflow id from tenant B answers
  404 for tenant A. And aimed at the oracle shape: creating a case with **another tenant's**
  `customerId` answers 404, not the 200 a bypassed-RLS FK check produces nor the 500 an invented id
  produces.
- **Scope** — TEAM-scoped `case.view` returns only the actor's teams' cases; ASSIGNED resolves
  through `case_participant`, not a column; and per the convention that tests constructing their own
  preconditions converge on the happy scope, **at least one write test runs at the narrowest
  catalogued scope** (`milestone.complete` at `ASSIGNED`). The absence of that single test is why
  the `user.manage` escalation survived sub-project 1.
- **`write_scope`** — a holder of `milestone.complete` at `ALL` is refused inside an `OWNER_ONLY`
  stage.
- **Force-complete** — the requester cannot decide their own request; `milestone.force_approve`
  cannot be held at anything but `ALL`; the reason is mandatory.
- **Kind confusion** — deciding a `FORCE_COMPLETE` through the stage-approval endpoint fails rather
  than falling through to the weaker gate.
- **Escalation guard** — a `user.manage` holder at `DEPARTMENT` cannot assign a role carrying
  `workflow.manage`: the guard proven against a permission written after it.
- **Database level**, in the `CustomerPersistenceTest` mould — `onboarding_app` cannot `DELETE` from
  the six `journey` tables or from `workflow_template`; it *can* delete a `DRAFT` definition row, and
  the trigger refuses every `UPDATE` or `DELETE` once its version is `PUBLISHED`. Both halves are
  asserted, because a grant proved only in the permitted direction is a grant nobody has bounded.
- **Timeline** — a case the actor cannot see returns 404 for its timeline, and the carve-out returns
  no other case's events.

### 9.3 Engine

- `reconcile` twice changes nothing.
- Two connections satisfying the last two requirements of a milestone **concurrently** produce
  exactly one advance. This is the test that justifies the row lock and that idempotency alone fails.
- A branch skipping through to the terminal stage completes the case with `current_stage_id` on a
  real stage; a case with skipped stages reaches **exactly 100%**.
- First-match-wins ordering; an unset attribute evaluating false; each of §5.5's five validations
  failing on its own.
- Dependencies gating `ACTIVE` while `due_date` stays put.
- Hold shifting dates by business days and accumulating `total_hold_days`.
- Q6 weighting against hand-computed numbers — durations of 2, 3 and 5 giving 20%, 50%, 100% — not
  an assertion that recomputes the formula it tests.
- Migration eligibility, including each ineligibility reason.

### 9.4 Frontend

Vitest over the builder's graph editing (reorder preserves selection; deleting a stage a branch
rule targets surfaces the error before Save, not at publish), the roadmap's header-suppression rule
at 1:1, the requirement checkbox awaiting its round-trip, tab keyboard navigation, and i18n key
presence for every new string. Every user-facing string goes through `t()`.

### 9.5 Playwright

Extending the six existing specs, on the harness that starts both applications and tees the backend
log to `frontend/e2e/.artifacts/backend.log`:

1. Workflow authoring through publish, including the validation-failure path.
2. A case lifecycle: creation with attributes → requirement satisfaction → automatic stage advance →
   a branch skipping a stage → force-complete requiring a second person's approval → completion.
3. Migration: publish v2 → review screen → migrate an eligible case.
4. The two new screens added to the existing axe sweep, both themes, four widths.

---

## 10. Invariant cross-check

The ten promises this design makes, where each is expressed, and what proves it. A change that
breaks a row here is a change to the design, not to an implementation detail.

| # | Invariant | Expressed in | Proven by |
|---|---|---|---|
| 1 | A published workflow version never mutates | §4.3 trigger; publish is the last legal `UPDATE`; archiving moves `current_version_id` instead | §9.1 (`RlsCoverageTest` grants), §9.2 trigger test; 409 on stale draft write (§7.1) |
| 2 | A case always has exactly one pinned version | §4.4 `onboarding_case.version_id` `NOT NULL`; §5.2 requires `PUBLISHED` at creation; §5.7 migration repins, never unpins | §9.3 migration tests; instantiation rejects a template with no published version |
| 3 | `journey` never depends on `customer` entities or repositories | §3.2 `CustomerDirectory` port, consumer-declared; §7.2 responses carry `customerId` only | §9.1 named `ModuleBoundaryTest` rule, seen red |
| 4 | Every runtime mutation goes through the locked `reconcile` path | §5.1 four-step sequence; `CaseEngine` package-private; `lockById` restricted to it | §9.3 concurrency test; §9.1 `*Engine` added to the gate rule, `lockById` a per-method exclusion |
| 5 | Progress is derived and stored by the engine, never client-supplied | §4.4 `progress_percent` absent from every request type; §5.1 recomputed in-transaction | §9.3 Q6 weighting against hand-computed values; §9.4 checkbox awaits the round-trip |
| 6 | Authorization narrows at `write_scope`, never widens | §6.3 — no branch grants | §9.2 `milestone.complete` at `ALL` refused in `OWNER_ONLY` |
| 7 | Nobody delegates a permission they do not hold at an equal or broader scope | §6.5 comparison in `RoleService.assignRole`; `ALL` covers all, otherwise exact match | §9.2 `DEPARTMENT`-scoped `user.manage` cannot assign `workflow.manage` |
| 8 | A cross-tenant id is consistently a 404 | §3.2 port resolves through `AuthorizedQuery`; §4.4 `satisfied_ref` carries no FK; `NoSuchElementException` → 404 everywhere | §9.2 cross-tenant suite, including case creation with a foreign `customerId` |
| 9 | Branches run forward, dependencies point backward — enforced at publish | §5.5 rules 2 and 3; the DAG is structural, not detected | §9.3 each validation failing on its own |
| 10 | Skipped milestones contribute nothing to progress | §5.3 out of numerator and denominator | §9.3 a case with skipped stages reaching exactly 100% |

Invariants 1, 4 and 5 together are what let sub-projects 3–5 satisfy requirements without
re-implementing the engine: they mutate their own row, call the same gated service, and progress
follows.

---

## 11. Inherited state, and what stays open

**Closed by this sub-project** (from `CLAUDE.md`'s open list): TEAM scope dead in a running system,
and the `user.manage` escalation path. Partially addressed: the cross-tenant ownership-FK oracle
shape is avoided in every new table (§4.4), though the existing `customer` columns keep it until
someone resolves those three ids through their repositories.

**Deliberately deferred, with the reason:**

| Item | Why not now |
|---|---|
| Tenant-configurable business calendar and holidays | No tenant settings surface exists at all; sub-project 6 needs it for SLA and will build both (§5.6) |
| Rework loops as branch targets | Forward-only keeps the graph a DAG by construction; reopening a milestone covers the real case with a permission, a reason and an event (§5.5) |
| Compound (AND) conditions | Several first-match rules express OR; nothing has asked for AND (§4.2) |
| `notification_template_key` behaviour | Authored here, acted on in sub-project 6; the field says so (§8.3) |
| A generic audit read endpoint | Would need a `resourceType → permission` map whose failure mode is a silent default (§7.3) |
| Remaining sub-project 1 open items | Contact email drift, unvalidated tenant slug, `DB_APP_PASSWORD` default, unaudited `authz`/`auth` write paths — none is in this sub-project's path |

**Known limitation created here:** the audit timeline read bypasses `AuthorizedQuery` by design
(§7.3). It is narrowed to one resource id behind a `case.view` resolution and carries a commented
exclusion, but it is the first such carve-out in the codebase and should not become a pattern
without the same explicit argument.

---

## 12. Question mapping

| Question | Where it lands |
|---|---|
| Q1 stage → many milestones | §4.1, §8.4 (header suppressed at 1:1) |
| Q2 freeze by default, explicit migration | §4.3, §5.7, §7.1, §8.5 |
| Q4 record-level permissions | §6.2, §6.3 |
| Q5 force-complete authority | §5.4, §6.1, §9.2 |
| Q6 weighted progress | §4.4, §5.3, §9.3 |
| Q7 conditional branching | §4.2, §5.3, §5.5 |
| Q8 business days, SLA pauses | §5.6 (calendar deferred to 6) |
| Q11 retention | Not this sub-project; audit is append-only already |
| Q15 assignment resolution | §5.2, §6.2 (the `ASSIGNEE` participant invariant) |
| Q16 per-role dashboards | Sub-project 8; this sub-project stores `progress_percent` for it |
