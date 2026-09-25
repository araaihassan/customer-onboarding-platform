# Customer Onboarding Platform

Multi-tenant enterprise customer journey and onboarding platform, delivered as ten sub-projects.
Sub-project 1 (Foundation & Tenancy) builds the substrate everything else sits on: tenancy with
database-enforced isolation, identity, RBAC with record-level scope, authentication, audit, and
customer management.

**Stack:** Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway, Hibernate/JPA, Gradle (Kotlin DSL) ·
Next.js 15 (App Router), TypeScript strict, Tailwind, shadcn/ui, TanStack Query · JUnit 5,
Testcontainers, ArchUnit, Playwright.

---

## Authoritative documents

Read the relevant one before starting work. Where they disagree, the more specific wins.

| Document | Authority for |
|---|---|
| `docs/PRD.md` | Product requirements |
| `docs/QA.md` | Resolved product questions (referenced as Q1…Qn) |
| `docs/superpowers/specs/*-design.md` | Architecture and security design per sub-project |
| `docs/superpowers/plans/*.md` | Task-by-task implementation plan per sub-project |
| **`docs/uispecs_latest/design_handoff_onboarding_platform/`** | **Every visual and interaction decision — see below** |

`docs/uispecs_legacy/` (formerly `docs/uispecs/`) is the design system sub-projects 1–2 were
originally built against. It is superseded as of 2026-08-25 and kept only for its build scripts
(`contrast.py`, `build_tokens.py`, etc.) and as the historical record behind the plan/spec files'
already-completed frontend tasks — do not read it for current token values, copy or layout, and
do not use it as a reference for any new frontend work.

**Before starting any frontend task, invoke the `frontend-design` and `ui-ux-pro-max` skills.**
This holds regardless of which sub-project the task is in — building a new screen, restyling an
existing one, or adding a component all count.

---

## Project shape

Single monorepo: `backend/` (Spring Boot, Gradle) and `frontend/` (Next.js), with `docs/` holding
the PRD, QA, specs, plans and the design system. `docker/` arrives in sub-project 10.

The backend is a modular monolith organised by domain, one package per module under
`co.ara.onboarding`: `platform/` (cross-cutting infrastructure), `tenancy/` (tenant records and
context), `identity/` (users, departments, teams, platform admins), `authz/` (permission catalog,
roles, grants, enforcement), `audit/`, `customer/` (customers, contacts), `workflow/` (template
authoring and versioning — templates, versions, stages, milestone/requirement/attribute
definitions, branch rules, publish validation; knows nothing about a running case), `journey/`
(the runtime — `Case`, `CaseParticipant`, `Milestone`, `Requirement`, `CaseAttributeValue`,
`Approval`, `CaseEngine`, migration eligibility, the timeline read; `case` is a Java keyword, so
the package is `journey`, matching what the UI calls the screen). `provisioning/` and `scoping/`
exist only because they orchestrate two or more of the others.

`journey` depends on `workflow` but never the reverse, and never on `customer` directly — it
declares `CustomerDirectory` (`Optional<CustomerFacts> findVisible(UUID)`, empty maps to 404) and
`customer` implements it, the same inversion `authz.ActorDirectory`/`identity.UserSessionRevoker`
already established. Both boundaries are their own `ModuleBoundaryTest` rule, not just the cycle
check, because a one-way `workflow → journey` or `journey → customer` import would still pass a
plain no-cycles test.

**Sub-project 1 delivered:** tenancy with RLS, identity, RBAC with record-level scope and twelve
seeded role templates, JWT auth with refresh rotation and reuse detection, invitation / activation /
password reset, login throttling, the audit substrate, customer and contact management, and on the
frontend the token layer, i18n, API client and public auth pages.

**Sub-project 2 delivered:** workflow authoring and versioning (draft → publish → frozen, publish
validating the whole graph in one pass), the case-lifecycle engine (branching, entry conditions
that skip a stage, auto-advance, weighted progress, force-complete with a second person's
approval, migration between versions), and on the frontend the journey workspace (roadmap,
requirement checkboxes, approval/hold/force-complete dialogs, timeline) and the workflow builder
screen (stages, milestones, requirements, branch rules, publish, migration review).

**Sub-project 3 delivered:** the `task` module (ad-hoc and requirement-instantiated tasks,
checklist items, status transitions with cancellation, and their wiring into `journey` via the
`TaskDirectory`/`TaskLifecycle` ports), internal comment threads polymorphic over tasks and
journeys with author-only editing, and on the frontend the case workspace Tasks tab, the
cross-case "My work" board, and comment threads mounted on both a task and the journey itself.

**Sub-project 3A delivered:** the `programme` module (a customer-scoped grouping of a customer's
parallel journeys, no lifecycle of its own, a derived duration-weighted rollup computed on read and
never stored, participation that grants read of the container only — a journey inside it stays
visible solely through its own real `CaseParticipant` row), customer-tailored workflow templates
(clone-and-tailor a catalogue template per customer, one clone per customer, `cloned_from_template_id`
as provenance only — Q21), the two-gate plan approval (`plan_shape_approval` at publish time,
`plan_revision`/`plan_revision_item` per journey thereafter, a customer-template case held from
creation and released only by its first schedule approval, reusing `Case.held_at`/
`CaseOnHoldException` rather than a second pause mechanism — Q22/Q23), and milestone portal
visibility (`portal_visible` filters rendering only; progress stays one number for every audience —
Q24). On the frontend: the programme index and detail screens, clone/refresh from the workflows
screen, both plan approval gates and the held-journey banner, and the milestone visibility toggle
in the builder.

**Sub-project 4 delivered:** the `document` module — upload with content-hardening (a size ceiling,
a sniffed-content MIME allowlist checked against the bytes actually uploaded rather than the
caller's declared `Content-Type`, and a per-version SHA-256 digest), immutable append-only
`document_version` rows, PATCH-based metadata/retarget, and retirement that revokes every live
share and cross-journey link and reopens whatever requirement it satisfied. Q9's two-axis
visibility (three tiers × department/contact-label targeting, explicit principal shares, explicit
cross-journey links) is enforced server-side for internal and portal actors alike through a new
opt-in `AudienceFilter` mechanism (below) — the first change to the authorization core since
sub-project 1. Document requests close the `RequirementKind.DOCUMENT` seam sub-project 2 left
empty: ad-hoc and requirement-instantiated (`DocumentInstantiation`, following `TaskInstantiation`'s
precedent exactly), fulfilment, and review with approve/reject wired back to
`journey.RequirementService.satisfy` and a new `.reopen`. Sub-project 4 also built the first portal
**write** path in the codebase — document upload, narrowly self-defending inside the service layer
rather than trusting its controller, and deliberately skipping `StageWriteScopeGuard` (a precedent
for sub-project 5's signatures and sub-project 7's sponsor approvals — see "What sub-project 5
inherits" below). On the frontend: the tenant-wide `docs` index screen (five scope filters, the
visibility cell, and a documented, bounded aggregate-disclosure exception for its hidden-by-scope
count — the codebase's second such exception after the audit timeline read), the case workspace
Documents tab, and upload/request/review dialogs.

**Sequence** (each gains a `*-design.md` in `docs/superpowers/specs/` and a plan in
`docs/superpowers/plans/`): 1 Foundation & Tenancy → 2 Workflow Engine & Case Lifecycle → 3 Tasks &
Collaboration → **3A Programmes & Customer-Scoped Plans** → 4 Documents → **4A Meetings (needs 4)** →
5 Agreements (needs 4) → 6 Notifications, SLA & Escalation (2, 3) → 7 Customer Portal (2, 4, 5, 3A) →
8 Dashboards & Real-time (2–6, 3A) → 9 Reporting & Analytics (2–6) → 10 Packaging & Deploy.
Sub-projects 2–9 each add one module in the shape of sub-project 1's Tasks
20–21: entity with `tenant_id`, migration calling `enable_tenant_rls`, a
`ResourceAuthorizationDescriptor`, a service where every public method is gated and every read goes
through `AuthorizedQuery`, and a thin controller.

**3A and 4A were added to the sequence on 2026-09-08**, from nine product decisions recorded as
**QA Q20–Q28** — read those before touching `workflow`, `journey`, the portal or the builder. 3A
is delivered (above); 4A is still upcoming. In short: a `programme`
groups a customer's parallel journeys with a derived duration-weighted rollup and no lifecycle of
its own (Q20); `workflow_template` gains a nullable `customer_id` so a catalogue template can be
cloned and tailored per customer, one clone per customer (Q21); the plan is approved twice — shape
at the template version, schedule per journey — with a journey held until its first schedule
approval, reusing `Case.held_at` and Q8's SLA pause (Q22, Q23); milestones gain portal visibility
while progress stays **one number for every audience** (Q24); `RequirementKind` gains `MEETING`
backed by a `meeting` module following the `TASK`-seam precedent exactly, with recurrence on the
meeting series and never on the frozen graph (Q25, Q26); outputs are a derived rollup over
`satisfiedRef` with no new schema (Q27); status reports are issued, dated, immutable snapshots
(Q28). None of the nine weakens an existing invariant — each answer carries its own cross-check, and
Q20's read-only participants are the one to watch, since a container returning journeys its viewer
could not otherwise open would be exactly the scope-widening shape three sub-project 1 escalations
took. Sub-projects 4–8 gain amendments rather than new scope: 4 makes meeting agendas and recordings
real, 5 adds a `SIGNATURE` kind, 6 finally gives `stage.portal_visible` and
`stage.notification_template_key` consumers, 7 wires the sponsor's own approve button to 3A's
endpoints and adds the programme view, 8 builds the status-report generator and the
department-filtered portfolio.

---

## Running it locally

PostgreSQL 16, database `onboarding`:

```bash
docker run -d --name onboarding-db -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=onboarding postgres:16-alpine
```

Flyway connects as the owner (`postgres`/`postgres`) and `V2` creates the `onboarding_app` login
role the application then connects as. Override with `DB_URL`, `DB_OWNER_USER` / `DB_OWNER_PASSWORD`,
`DB_APP_USER`. `DB_APP_PASSWORD` has no default (see below) — set it, on every profile. If 5432
is already taken, map another port and set `DB_URL` to match.

**Backend** on :8080 —

```bash
cd backend && SPRING_PROFILES_ACTIVE=dev \
  JWT_SECRET="$(openssl rand -base64 48)" \
  DB_APP_PASSWORD="$(openssl rand -base64 48)" \
  APP_PLATFORM_ADMIN_EMAIL=ops@example.com APP_PLATFORM_ADMIN_PASSWORD=<pick-one> ./gradlew bootRun
```

PowerShell — this repository is developed on Windows, and the bash form above runs on neither
`cmd` nor PowerShell (`VAR=value cmd` prefixing and `$(…)` are bash). Omitting `JWT_SECRET` or
`DB_APP_PASSWORD` is not an option: the application refuses to start without either.

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:JWT_SECRET = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
$env:DB_APP_PASSWORD = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
$env:APP_PLATFORM_ADMIN_EMAIL = "ops@example.com"
$env:APP_PLATFORM_ADMIN_PASSWORD = "<pick-one>"
.\gradlew.bat bootRun
```

`JWT_SECRET` is **required, on every profile** — the application refuses to start without at least 32
bytes of it, and says so naming the variable. There is no dev default: a committed one is a signing
key every reader of this repository holds, and the deployment that forgets the variable is exactly
the one that would use it. Pick a value once and keep it in your shell; changing it between restarts
invalidates every access token already issued, which reads as a spate of 401s.

`DB_APP_PASSWORD` is likewise **required, on every profile** — `DatabaseCredentialsGuard` refuses to
start on a blank value or on the literal `onboarding_app` (the password `V2__app_role_and_tenant.sql`
creates the role with), naming the variable. Unlike `JWT_SECRET`, though, it does **not** need to
stay stable across restarts, and it can be **any** value, not specifically 32+ bytes: it is not a
signing key, and `AppRolePasswordReconciler` (a Flyway `AFTER_MIGRATE` callback) reconciles the
`onboarding_app` role's real database password to whatever `DB_APP_PASSWORD` currently says on every
single startup, automatically. Change it between restarts and the role is simply repointed to the
new value each time — there is no "spate of 401s" equivalent, and no separate `ALTER ROLE` step to
remember.

Both platform-admin variables are blank by default and `PlatformAdminBootstrap` does nothing without
them — but `/api/platform/**` is HTTP Basic behind `hasRole("PLATFORM_ADMIN")`, so without one no
tenant can ever be created. It is idempotent: an existing address is left untouched, never re-hashed —
so against a database that already has that administrator, changing `APP_PLATFORM_ADMIN_PASSWORD`
silently does nothing and the provisioning call below answers 401. Startup logs which it did
("already exists; leaving it unchanged"), and that line is the fastest way to read a puzzling 401.

**Frontend** on :3000 — `cd frontend && npm install && npm run dev`. `src/lib/api/client.ts` issues
same-origin `/api/t/{slug}/…` requests, which is what keeps the HttpOnly `SameSite=Strict` refresh
cookie attached; `next.config.ts` rewrites `/api/:path*` to `BACKEND_ORIGIN` (default
`http://localhost:8080`) to make that reach the backend. `rewrites` is a dev/runtime-server
mechanism — a production deployment configures the same mapping at its edge proxy instead
(sub-project 10). Never replace it with a route handler under `app/api/…`; one existed as a mock and
was deleted.

**Provision a tenant** (seeds the twelve role templates and one `INTERNAL` administrator):

```bash
curl -u ops@example.com:<password> -X POST http://localhost:8080/api/platform/tenants \
  -H 'Content-Type: application/json' \
  -d '{"slug":"acme","name":"Acme Corp","adminEmail":"admin@acme.test","adminFullName":"Acme Admin"}'
# → {"tenantId":"01a0001e-…"}
```

**Activation and reset tokens** are obtainable only from the log. Under the `dev` and `test` profiles
`LoggingEmailSender` logs each message body in full; `SmtpEmailSender` takes over elsewhere. Trigger
one (e.g. `POST /api/t/acme/auth/password-reset/request` with `{"email":"…"}`, which answers 204 for
known and unknown addresses alike) and grep the application's stdout for `[email]`:

```
… c.a.onboarding.auth.LoggingEmailSender : [email] to=admin@acme.test subject=Reset your password
Use this token to reset your password: <base64url token>
```

The body carries a bare token, not a URL; the activation page reads it from
`/t/{slug}/activate?token=…`.

Provisioning also issues the administrator an `ACTIVATION` invitation and emails it, in the same
transaction — that is the only way in, since the account is created `INVITED` and `LoginService`
admits only `ACTIVE`. So the full bootstrap is: provision → read the token from the log → `POST
/auth/activate` → `POST /auth/login`. A password reset is **not** a substitute: it sets the hash and
deliberately leaves `status` alone, so the login still returns 401. There is no re-issue path once
the seven-day TTL expires; a tenant provisioned and forgotten for a week needs manual intervention
still — sub-project 2 did not add one, and no later sub-project has this in scope either.

Also operational: `audit_event` is partitioned by month and `V5` creates only `2026_08`, `2026_09`
and a DEFAULT partition. The job that rolls partitions forward arrives in sub-project 6.

**Seed a workflow and open a case** — nothing in the product creates either for you; both are curl
away once a tenant's administrator is activated. **Tenant endpoints are JWT bearer-only, not HTTP
Basic** — `SecurityConfig` wires `httpBasic()` only on its separate `/api/platform/**` chain
(`platformFilterChain`, gated `hasRole("PLATFORM_ADMIN")`); the main tenant chain
(`filterChain`, everything under `/api/t/{slug}/**`) authenticates only via `jwtFilter`, so a
`curl -u admin@acme.test:<password>` against any tenant route 401s regardless of how correct the
credentials are. Log in first and carry the token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/t/acme/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"admin@acme.test","password":"<password>"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
# accessToken expires in 900s (expiresInSeconds in the same response) — re-login if a later
# call in the same session 401s.

curl -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8080/api/t/acme/workflows \
  -H 'Content-Type: application/json' -d '{"name":"Onboarding"}'
# → {"id":"<templateId>", ...}
curl -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8080/api/t/acme/workflows/<templateId>/versions
# → {"versionId":"<versionId>", ...} — an empty DRAFT, or a copy of the current published version

curl -H "Authorization: Bearer $TOKEN" -X PUT \
  http://localhost:8080/api/t/acme/workflows/<templateId>/versions/<versionId> \
  -H 'Content-Type: application/json' -d '{
    "attributes": [],
    "stages": [{"key":"s1","name":"Onboarding","autoAdvance":true,"branchRules":[],"milestones":[
      {"key":"m1","name":"Kickoff","estimatedDurationDays":2,"dependsOnMilestoneKeys":[],
       "requirements":[{"kind":"MANUAL","label":"Sign up","mandatory":true}]}
    ]}]
  }'
# publish rule 5: every stage needs at least one milestone, or this 422s at publish, not here.
# attributes/autoAdvance/branchRules/dependsOnMilestoneKeys are shown explicitly, not for
# padding: every one of them NPEs or silently misbehaves if the key is left out entirely —
# see "Live-running the three new specs" below.

curl -H "Authorization: Bearer $TOKEN" -X POST \
  http://localhost:8080/api/t/acme/workflows/<templateId>/versions/<versionId>/publish
# a case can only be created against a template with a PUBLISHED version

curl -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8080/api/t/acme/cases \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"<customerId>","templateId":"<templateId>","name":"Acme onboarding"}'
# → CaseView, pinned to the version that was current when this call ran. attributes may be
# omitted entirely (defaults to none, fixed below) — unlike the workflow PUT above, this one
# field no longer needs padding.
```

The builder UI can author everything above **except** a workflow attribute or a stage's
`entryCondition` — see "Open at the close of sub-project 2". A workflow with a conditional skip
(an `entryCondition` referencing an
`ATTRIBUTE`-sourced condition) can only be authored this way, through the `PUT`, until that gap is
closed.

**Open at the close of sub-project 1**, verified against the running system — none of these is a
regression to hunt:

- **A narrow-scoped `user.manage` holder cannot create a user through the Users screen — fixed for
  DEPARTMENT scope only, TEAM scope is a separate, still-open gap.** The create form now offers a
  department picker (sub-project 3, Task 8): an actor holding `department.manage` (ALL-only) picks
  from the tenant's full list; anyone else — the DEPARTMENT-scoped `user.manage` holder this gap was
  about — gets no visible field and the request silently carries their own `departmentId` from
  `useAuth().user`, the one department `scoping/AppUserDescriptor.departmentScope` guarantees they
  can succeed with. `lib/api/admin.ts` also gained `useUpdateUser()`, and the Users screen an Edit
  dialog, so a department can now be changed after creation too — closing the "no user-edit screen"
  half of this gap as well.
  **TEAM scope is untouched and cannot be fixed by a picker at all**: `AppUserDescriptor.teamScope`
  resolves TEAM by checking the *target* user's own `teamIds` (`root.join("teamIds").in(ctx.teamIds())`),
  but `CreateUserRequest` has no `teamIds` field and a freshly created `AppUser`'s `teamIds` starts
  empty — so that join can never match, and a TEAM-scoped `user.manage` holder cannot create ANY
  user today, department field or not. Confirmed empirically (a hand-built TEAM-scoped role's create
  attempt throws `NoSuchElementException` regardless of the department supplied). Needs
  `CreateUserRequest` to accept `teamIds`, or an equivalent mechanism, before a TEAM-scoped actor can
  create a user at all — not attempted here.
- **Deactivations recorded before 2026-08-16 are mislabelled.** `UserAdminService.deactivate` wrote
  the `user.created` action key with only its prose summary dissenting. Fixed, but `audit_event` is
  append-only, so historical rows cannot be corrected — anything querying `user.created` over that
  period is counting deactivations too.
- **Audit events written before 2026-08-29 have their causes stamped after their effects.** Nine
  `journey` call sites recorded an action only after `engine.reconcile` had already recorded what
  that action triggered, and `AuditRecorder` stamps `occurredAt` from the clock — so a case's
  `case.created` carries a LATER timestamp than the `case.stage_entered` and `milestone.completed`
  of its own creation, and a newest-first timeline shows every cause above its own effects. Fixed
  (`CauseBeforeEffectTest`, and the rule is on `AuditRecorder`), but `audit_event` is append-only,
  so existing rows read wrong permanently: cases opened before that date still show milestones
  completing before the case was created. New cases read correctly. Anything that derives a
  sequence — not just a set — from historical audit rows is reading a scrambled one.

**Closed since sub-project 1, verified against the running system:** TEAM scope (real
`POST /admin/teams/{teamId}/members` and its `/remove`, both gated `team.manage` and resolving
through `AuthorizedQuery`) and the light theme's shipped tokens (`contrast.py`'s
`report_shipped("light")` now runs unconditionally alongside dark and reports 0 of 49 pairs
failing, same as dark — confirmed by running it, not by reading the script).

**Open at the close of sub-project 2**, verified against the running system:

- **The workflow builder has no UI to declare an attribute or set a stage's entry condition.**
  `draftState.ts`'s `addAttribute`/`updateAttribute`/`removeAttribute` reducer actions exist and
  `BranchRuleCard` already reads `attributes` for its condition dropdown, but nothing in the
  builder page ever dispatches `addAttribute` — there is no "Add attribute" affordance anywhere.
  `StageInspector` has no field for a stage's own `entryCondition` at all (the prototype drew it as
  one of several "read-only-styled" fields that were never wired to anything real, the same
  treatment `notificationTemplateKey` still gets — but unlike that field, `entryCondition` is not
  inert: it is the mechanism §5.3 uses to skip a stage conditionally, and case-lifecycle.spec.ts's
  own workflow had to be seeded through the API rather than the builder for exactly this reason.
  Both are real product gaps, not test-writing conveniences.
- **The audit timeline read is a known, deliberate carve-out, not yet a pattern to repeat.** It
  bypasses `AuthorizedQuery` by design (spec §7.3) — narrowed to one resource id behind a
  `case.view` resolution and carries a commented exclusion in `AuthorizationCoverageTest` — but it
  is the first such exception in the codebase. A second one needs the same explicit argument this
  one carries, not a copy of the exclusion.
- **Every remaining sub-project 1 item above is still open** and none is in sub-project 2's own
  path (spec §11's own cross-check): the `user.manage` create-form gap, the ownership-FK oracle,
  deactivation's pending-credential gap, the unvalidated tenant slug, `DB_APP_PASSWORD`'s default,
  contact email drift, the three unaudited `authz`/`auth` write paths, the mislabelled pre-2026-08-16
  deactivations, and contact retirement not revoking portal access.

**Closed since sub-project 2, verified against the running system:** Q18's journey name.
`onboarding_case.name` is `NOT NULL`; `CreateCaseRequest`/`UpdateCaseRequest` both carry it
`@NotBlank`, so neither create nor update can leave a case unnamed or silently blank one on a
full-replace `PUT`; `CreateCaseDialog` collects a real name at creation (the only place one is
ever supplied — there is no synthesized fallback in application code, only in `V15`'s one-time
backfill of rows that predate the column); and `CaseSwitcher` renders it in place of the
stage-plus-id label this replaced. A fix round on this same task found and removed an earlier,
worse version of the bug: the first pass let `CaseService.create` synthesize a "template name plus
short id" label when a caller sent none, which read as a real name but was identical across every
case opened from the same template — the reviewer's point that a fake-but-plausible name is a
regression from a visibly-fake one, not progress toward Q18.

**Closed since sub-project 3, verified against the running system:** all eight of sub-project 1's
remaining backlog items, all closed by this sub-project's own Phase 1 (Tasks 2–10), none of them
feature work. `OrgUnitResolver` (Task 2) resolves `ownerUserId`/`owningDepartmentId`/
`owningTeamId` through their repositories before `CustomerService` writes them, closing the
cross-tenant existence oracle. `PendingInvitationRevoker` (Task 4) revokes outstanding invitations
alongside every refresh family on deactivation. `ProvisionRequest.slug` (Task 3) now carries
`@NotBlank @Pattern(regexp = PathPrefixTenantResolver.SLUG_PATTERN)`, and a duplicate slug maps to
a real 409 (`DuplicateSlugException`) instead of a raw 500. `AppRolePasswordReconciler` (Task 7)
reconciles `onboarding_app`'s real database password to `DB_APP_PASSWORD` on every startup, so the
committed literal default in `V2__app_role_and_tenant.sql` — which can never change, migrations
being forward-only — no longer matters. `LinkedPortalUserEmailSync` (Task 5) keeps
`app_user.email` in step with a corrected contact address, and the same task closed the
case-sensitivity mismatch between `customer_contact`'s and `app_user`'s uniqueness rules.
`RoleService.deleteRole`, role re-enablement and `PasswordResetService`'s request/completion are
now all audited (Task 6) — the fourth of the original four unaudited paths, `unassignRole`, was
already closed at sub-project 2's own close. `CustomerContactService.update`'s retirement branch
(Task 5) now deactivates the linked `app_user` and revokes its pending invitations, and its
reactivation branch restores access. And `CustomerContactController` (Task 9) documents 409 on
both contact create and update, so `generated.ts` carries it for a client to narrow on.

**Open at the close of sub-project 3, all four closed since — see below:** TEAM-scoped user
creation is untouched — `CreateUserRequest` still has no `teamIds` field, and it remains the one
`user.manage` gap this sub-project did not attempt. The workflow builder's missing
attribute/entry-condition UI and the audit-timeline-read carve-out precedent are both sub-project
2's own open items, outside this sub-project's path, and neither was touched by Phase 1's tasks.

**Closed by sub-project 3A's own Phase 1, verified against the code (design spec §11.1 of
`2026-09-12-documents-design.md` documents this explicitly — trust the code over any stale claim in
this file, which is exactly why this paragraph replaces the four bullets it used to carry):** the
task-edit UI gap (`TaskDetail.tsx`/`TaskEditDialog.tsx` now exist, wired to a real `useUpdateTask`),
the roadmap's unrendered `taskSummary` field (now scope-filtered and rendered — see
`MilestoneRow.tsx`), the unsorted "My work" board (`TaskService` now orders "do now" by due date),
and the two missing `task.*` audit actions (`task.assigned` and ad-hoc `task.created` are both now
recorded). None of these four was sub-project 4's own path either — confirmed here only because
this sub-project's own design spec had to re-verify them before it could safely rely on `task` as a
precedent module, and found this file itself had drifted behind the code.

**Closed by sub-project 3A Task 2, verified against the code:** `AuthorizationCoverageTest`'s
finder rule no longer binds on a `*Service`/`*Directory` name suffix alone. It is now a union of
that name shape with "injects a `*Repository` field," named exclusions living only in
`FINDER_RULE_EXCLUSIONS` — so the three `task` classes once named specifically to dodge the old
rule (`TaskInstantiation`, `TaskDirectoryAdapter`, `TaskLifecycleAdapter`) are now genuinely covered
rather than invisible to it, and `customer.OrgUnitResolver`'s old no-op exclusion is gone (replaced
by a real, named entry in the same list). The rebind itself surfaced four more classes the old rule
was equally blind to (`auth.PendingInvitationRevoker`, `customer.LinkedPortalUserEmailSync`,
`identity.PlatformAdminBootstrap`, `journey.CaseEngine`), each individually reasoned about rather
than exempted reflexively — see `AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS`'s own doc comment.

**Closed since sub-project 3A Phase 1 (Task 8), verified against the running system:**
`approval.decide` and `task.manage` are no longer Administrator-only. Operations (the
department-lead-shaped template) now holds `APPROVAL_DECIDE` at DEPARTMENT, so deciding a
stage-exit approval no longer requires the tenant's widest role; Project Manager now holds
`TASK_MANAGE` at TEAM, alongside the `TASK_VIEW`/`TASK_COMPLETE` it already had, so a role that can
complete a task can now also create, edit and reassign one. `RoleTemplateCoverageTest` guards this
derivably rather than by name — it fails whenever a permission catalogued at more than one scope is
held by no template but Administrator, not just for these two keys — and Phase 2 of this same plan
relies on it staying red until `programme.manage` and its siblings are seeded too.
Not `MILESTONE_FORCE_APPROVE`: it is ALL-only in the catalog itself (Q5), so it cannot be any
narrower by construction and the guard does not flag it.
The guard's first run also flagged two permissions outside this task's scope, both still
Administrator-only and both excluded from it by name pending a real role review, not fixed here:
`user.manage` (granting it at TEAM to any template today would seed a role that holds the
permission but 404s on every create — see the still-open TEAM-scoped-user-creation gap above) and
`customer.deactivate` (no scope decision for it exists anywhere in `docs/QA.md` or the PRD). The
exclusion list lives in `RoleTemplateCoverageTest` itself, the same shape as `RlsCoverageTest`'s
allowlist — a deliberate, commented entry, not a silent skip.

**Closed at Task 35 (whole-branch close-out, 2026-09-12):** the role review Phase 1 deliberately
left open. Project Manager now holds `PROGRAMME_VIEW`/`PROGRAMME_MANAGE` at TEAM — the same
day-to-day delivery-coordination role that already holds `CASE_EDIT`/`CASE_ADVANCE`/`TASK_MANAGE`
at TEAM, so coordinating the parallel journeys a programme groups for one customer is the same
responsibility one level up, not a new one. Account Manager (owns the ongoing relationship) was
considered for `PROGRAMME_VIEW` too, but `PROGRAMME_MANAGE`'s actual shape — edit a programme, its
journeys and its participants — is delivery orchestration, so both grants went to the one template
that already does that work rather than splitting view from manage across two templates with
nothing to tell them apart. Both scopes already had a narrowest-scope test before this grant existed
(`ProgrammeMembershipServiceTest.aTeamScopedProgrammeManageHolderCanAddAParticipantWithinTheirOwnScope`
for TEAM-scoped `programme.manage`, `ProgrammeScopeTest` for ASSIGNED-scoped `programme.view`), so
CLAUDE.md's "Working conventions" requirement needed no new test. `user.manage` and
`customer.deactivate` remain in `ADMINISTRATOR_ONLY_PENDING_REVIEW`, untouched by this decision.

**Open at the close of sub-project 3A** (spec §11.3's own cross-check, confirmed still open and
untouched by this sub-project's own path): TEAM-scoped user creation (`CreateUserRequest` still has
no `teamIds` field); the workflow builder's missing attribute/entry-condition UI (3A's own builder
change, the milestone visibility toggle, is a different field entirely); and the audit-timeline
read's `AuthorizedQuery` carve-out, still the codebase's only one. All three of sub-project 3's own
open items above (no task-edit UI, `taskSummary` unrendered, "Do now" unsorted, two missing
`task.*` audit actions) are likewise untouched — 3A added a `programme` module, not task features.
**Process note, worth carrying into sub-project 4's own task verification:** a green `npx vitest
run` proves no real `tsc` type-check or lint pass — it is JSDOM-based unit tests only. Task 33 found
a hard `tsc` compile error and an ESLint `prefer-const` error that had been silently blocking `next
build` since Task 32, invisible to every vitest run in between and caught only because Playwright's
`webServer` runs a real `next build`. This is not this sub-project's first time hitting this exact
blind spot (the ledger has earlier occurrences); running `tsc --noEmit`/`next lint` as part of each
frontend task's own verification, rather than waiting for the eventual live e2e run, would catch it
at the task that introduced it instead of several tasks later.

**Open at the close of sub-project 4** (design spec §11.1's own cross-check, confirmed against the
code at Task 37's close-out — real gaps, each individually ruled during this branch's own review
process and deliberately parked rather than fixed or forgotten):

- **A milestone's `status` can read `DONE` below 100% after a document retirement reopens a
  requirement it satisfied.** `RequirementService.reopen` (Task 18, the new method retirement calls)
  correctly reconciles `progressPercent` back down, but `CaseEngine.recomputeStatusesAndProgress`
  treats `DONE` as sticky — the same stickiness force-complete relies on to survive its own
  `reconcile` call — so the redundant `status` label goes stale while the authoritative number (Q24:
  "one number for every audience") stays correct. Deliberately not fixed inside sub-project 4: the
  plan's own global constraint forbids touching `CaseEngine` anywhere in this branch, and a correct
  fix needs to distinguish "force-completed, must stay sticky" from "naturally reopened, should
  revert" — real design work for its own task, not a fix-round patch risking sub-project 2's own
  guarded invariants.
- **`RequirementDefinition.documentCategory` has no validation at authoring or at publish.**
  `DocumentInstantiation` calls `DocumentCategory.valueOf(...)` on it, and — because a published
  workflow version never mutates — a template published with an invalid value permanently bricks
  case creation against that version, with no recovery path. Same class of defect as the
  already-documented `dependsOnMilestoneKeys`/`branchRules` NPE-on-omission traps (Tests section,
  below), but more severe: those 500/400 per malformed request, this one dead-ends a template
  forever. Reachable only through direct API authoring today — there is no builder UI for
  DOCUMENT-kind requirements at all.
- **The workflow builder's missing attribute/entry-condition gap (open since sub-project 2) gains a
  third unauthorable field.** Task 36 threaded `requiresReview` through the raw `PUT` payload (it
  had no field there at all before), but the builder UI still has no control for it, matching the
  existing attribute/entry-condition gap exactly.
- **Review is per-version; satisfaction is per-document.** Approving a stale, superseded version
  satisfies the requirement against bytes nobody will ever download again; rejecting a stale version
  after a newer one was already approved reopens a requirement actually satisfied by different,
  current bytes. A genuine, underspecified product question (does satisfaction track "whichever
  version was reviewed" or "only the current version"), not a bug with an obvious fix — no test
  exercises either direction today.
- **`DocumentReviewService.pending()` has no `AudienceFilter` for `DocumentVersion`, and no HTTP
  path reaches it at all.** Only `Document.class` has a registered filter; if something ever calls
  `pending()`, an ALL-scoped reviewer would see every pending version tenant-wide, SENSITIVE/targeted
  ones included. Zero live exploitability today — `DocumentController` maps no endpoint to it.
  Flagged for whichever future task builds the cross-case pending-review-queue screen the design
  handoff names; that task needs both a controller endpoint and the audience filter before going
  live, not just the endpoint.
- **`GET /documents`/`GET /documents/{id}` still return the full internal `DocumentView` to a
  portal actor who calls them directly**, unlike the two dedicated portal endpoints Task 26 built
  (which return a narrower `PortalDocumentView`). Leaks opaque internal UUIDs
  (`targetDepartmentId`, `targetContactLabel`, `uploadedBy`) — no names, no PII, no cross-tenant or
  cross-customer data. Predates Task 26 (it is Task 14's own original code); properly closing it
  means deciding whether every document read path should branch on actor type, a broader
  architectural question than one task's fix-round scope. The `Sidebar.tsx` "Documents" nav item had
  the identical actor-type blindness and was fixed at this same close-out (below) — this is its
  still-open, API-level sibling.
- **Account Manager — the plan's own first-named `document.request` holder — cannot actually fulfil
  a requirement-linked document request.** Holds `DOCUMENT_REQUEST` at TEAM but no
  `MILESTONE_COMPLETE` at any scope, and `fulfil`'s own `RequirementService.satisfy` call
  independently gates on it, so the whole call fails for that role outside the ad-hoc case. Tracked
  alongside `user.manage`/`customer.deactivate` in `RoleTemplateCoverageTest`'s
  `ADMINISTRATOR_ONLY_PENDING_REVIEW`-shaped role-review backlog — a platform-wide permission
  decision, not granted unilaterally inside this sub-project.
- **`DocumentInstantiation`'s auto-created document requests never fire `document.requested`** —
  only the ad-hoc `DocumentRequestService.create` path records it. Same severity class as
  sub-project 3's already-accepted `task.created` ad-hoc-vs-instantiated asymmetry.
- **The `docs` index screen's fifth scope-filter button, "Open requests," ships disabled.** There is
  still no tenant-wide document-request listing endpoint (only the per-case
  `GET /cases/{caseId}/document-requests` Task 36 added exists) — the button renders per the design
  (every specified button must be visible) but inert, with its own code comment naming the missing
  endpoint.
- **Malware scanning and a per-tenant byte quota remain deliberately out** (spec §2.3/§7.6, resolved
  2026-09-13 — see "What sub-project 4 inherits" below), not something this close-out re-opens, but
  worth restating here since both bear directly on anything that later makes portal upload
  production-facing.
- **Two role templates hold a permanently inert `document.view` grant.** Sales Representative and
  Business Partner (`authz/RoleTemplates.java`) both hold `document.view` at `Scope.ASSIGNED`, but
  `scoping.DocumentDescriptor.assignedScope` resolves ASSIGNED as `uploaded_by = ctx.userId()`, and
  neither template holds `document.upload` at all — so this grant can never match any row for either
  role. A hand-built test role asserting `document.view@ASSIGNED` works will see nothing today; that
  is the expected (if unfortunate) result of this specific grant combination, not a new bug to chase.
- **A customer's own SENSITIVE-tier upload is invisible to everyone, including themselves, until an
  internal staff member shares it.** Per the design's own portal visibility rules (SCREENS.md §17's
  "Selected contacts only" maps to the `SENSITIVE` visibility tier), but this sub-project grants
  portal actors no `document.share` permission at all — so a `SENSITIVE` document a customer uploads
  themselves has no visibility path back to them or anyone else at their company until an internal
  `document.share` holder explicitly shares it. This is Task 26's own design point 6, already
  documented in a code comment there — this entry surfaces it at the level a future session actually
  reads, since sub-project 7 (which owns the portal UI) will hit this first.

All three of sub-project 3A's own open items above (TEAM-scoped user creation, the
attribute/entry-condition builder gap, the audit-timeline-read carve-out) are likewise still open
and untouched by this sub-project's own path.

### Tests

```bash
cd backend && ./gradlew cleanTest test     # needs Docker running
cd frontend && npx vitest run
```

All three suites were green at the close of sub-project 2 (2026-08-23), nothing skipped, no
retries — `./gradlew cleanTest test` reported `BUILD SUCCESSFUL`; `npx vitest run` reported 46
files, 335 tests, all passing; and Playwright's three new specs (workflow authoring, case
lifecycle, migration) passed live against a scratch database, all four of their test cases green.
Counts are deliberately not pinned in general — they move every time a task adds a test, and a
number in this file that drifts is a number that gets trusted. Read each suite's own summary line,
and treat a *failure* as the signal, never a count.

**Live-running the three new specs for the first time found five real defects, none in the
product** — every one was in the specs' own seeded payloads or a test-writing habit that happened
to work elsewhere, not in `co.ara.onboarding` or the frontend:

- **Two `WorkflowDefinitionRequest` fields NPE the server when omitted, rather than defaulting.**
  `MilestoneRequest.dependsOnMilestoneKeys` and `StageRequest.branchRules` are plain
  `List<String>`/`List<BranchRuleRequest>` with no `@NotNull`, and downstream code iterates them
  with no null guard — a seeded stage that leaves either out 500s. `estimatedDurationDays` is the
  one field that DOES validate (`@Positive int`), so omitting it 400s instead — a real, useful
  contrast, but easy to miss if only the "it 500s" cases get exercised.
  `WorkflowDefinitionRequest.attributes` has the same shape at the top level.
  **`CreateCaseRequest`/`UpdateCaseRequest.attributes` had the identical shape one layer down and is
  now fixed, not just documented** (found seeding demo data days after 3A closed):
  `CaseService.validateAttributes`/`upsertAttributes` both normalise a null `supplied` map to `Map.of()`
  at their own top, since both are called from `create` and `update` alike and the fix belongs in the
  one shared place, not at each call site. The workflow-level fields above remain an unfixed,
  documented trap deliberately — this one differs only because it was hit again in practice; treat
  a `List`/`Map` request field with no explicit default the same way if you find another.
- **A boolean field omitted from JSON is not "the UI's own default."** `StageRequest.autoAdvance`
  is a primitive `boolean`; Jackson binds a missing key to `false`, not the `true` the builder's
  own `Switch` shows pre-checked. A workflow seeded through the API without `autoAdvance: true` on
  every stage never advances past the first one — the requirement still shows DONE, but the case
  sits exitable forever.
- **A freshly created draft's `lockVersion` is not reliably `0`.** `createDraftVersion` deep-copies
  the template's current published version when one exists (empty only for a template's first-ever
  draft), and that copy is itself a write — so a *second* version's starting `lockVersion` can
  already be past `0` by the time the caller's own `PUT` reads it. Capture the value the create
  response actually returns and round-trip it; don't assume the field starts at its type's default.
- **A checkbox whose `checked` state depends on a server round trip cannot use Playwright's
  `locator.check()`.** That action clicks and then verifies the box is checked in one synchronous
  step; `RequirementList`'s checkbox deliberately waits for the mutation before flipping (Task 27's
  "real and local" departure), so the verification runs before the state has actually changed. A
  plain `.click()` followed by an auto-retrying `expect(locator).toBeChecked()` is what actually
  waits for it — the general lesson: never `.check()`/`.fill()`-and-assume against a control whose
  state depends on an async round trip; separate the action from the (retrying) assertion.
- **Viewing a case's full representation is gated by more than `case.view`.** `CaseService`'s view
  resolves `currentStageName` by reading the `Stage` row, a `workflow`-module entity gated by
  `workflow.view` — so a role holding `case.view` but not `workflow.view` gets a 404 on the *whole*
  case read, not a blank stage-name field, because the nested lookup's own
  `NoSuchElementException` propagates up unchanged. Confirmed by direct SQL against the running
  database (the grant existed, scoped `ALL`, exactly as seeded) before the missing `workflow.view`
  permission was found by comparing which `forPermission` calls a temporary log line showed for the
  admin session against the ones for the failing one. A hand-built test role has to declare this
  dependency explicitly; the twelve seeded templates bundle it because a real Project-Manager-shaped
  role always holds both.
- **The identical `workflow.view`-for-a-nested-`Stage`-lookup trap recurs for every `document`
  write method that calls `applyWriteScope`.** `DocumentService.applyWriteScope` resolves the case's
  current `Stage` under `workflow.view`, exactly like `CaseService`'s own view above — so `share`,
  `link`, `request`, `fulfil`, `retire`, `upload`, `addVersion` and `patch` all 404 for a role holding
  only the `document.*` permission that otherwise gates the call, unless it also holds `workflow.view`.
  Independently rediscovered by four separate sub-project 4 tasks (19/20/22/23) and once more, live,
  by Task 35's e2e run — a hand-built test role exercising any of these methods needs `workflow.view`
  alongside whichever `document.*` permission actually gates the write.

Also found and fixed as a real product bug, not a test artifact: **`MigrationTable` blanked out
the entire table, ineligible rows included, whenever nothing remained eligible** — `eligible.length
=== 0` was the empty-state guard, so migrating the one eligible case in a mixed list made the
*ineligible* rows (and their reasons — the component's own stated reason for existing) disappear
too. Fixed to key the guard on `candidates.length === 0` instead; `MigrationTable.test.tsx` gained
a case proving the ineligible-only table still renders.

**Use `cleanTest test`, never a bare `test`** — Gradle marks an unchanged test task UP-TO-DATE and
prints `BUILD SUCCESSFUL` having executed nothing, which reads exactly like a green run.
`org.testcontainers` is pinned to 1.21.4 in `build.gradle.kts` because Boot 3.4.1's managed 1.20.4
cannot negotiate with current Docker Desktop API versions; do not revert it blindly.

`cd frontend && npx playwright test` is the end-to-end command: fourteen specs — login, activation,
refresh rotation and reuse, customers with contact create/edit/retire, permission gating and the
900px card-list fallback, the administration screens, accessibility in the light theme at four
widths, workflow authoring through publish, a case lifecycle (branch skip, force-complete,
completion at 100%), migration between versions, tasks (creation, checklist, comments, completion,
"My work" board — `frontend/e2e/tasks.spec.ts`, added in Task 31), added by sub-project 3A:
`customer-plan.spec.ts` (clone a template, tailor it, publish, both plan approval gates, the
held-journey release, satisfying the first requirement) and `programme.spec.ts` (the
duration-weighted rollup across two journeys, and a participant seeing only the one journey they
hold a real `CaseParticipant` row on — the scope-filter property, proven live), and, added by
sub-project 4: `documents.spec.ts` (the tenant-wide `docs` index screen's visibility arc — a
Legal-targeted document hidden from a Finance reader until an explicit share, the bounded
hidden-by-scope count staying correct throughout) and `document-requests.spec.ts` (a DOCUMENT
requirement authored with `requiresReview: true` auto-instantiates a request; fulfilling it does not
yet satisfy the requirement; approving the review does, and the case workspace shows the milestone
Done).

**First live run against the frontend visual refactor, 2026-08-29** (sub-project 3 Task 1) — every
spec had never actually been executed against this branch before; only read/reviewed. All nine
spec files pass now, after fixing what the first run surfaced (a stale scratch database left over
from an earlier session doesn't count as a suite finding — see below; individual test counts are
deliberately not pinned here, same as the backend/vitest suites above). Two were
real product bugs, fixed with their own test before the e2e fix: (1) `SecurityConfig` required
authentication on the servlet container's internal `/error` forward, so ANY framework-level
exception with no app `@ExceptionHandler` (a bean-validation failure, malformed JSON, an unmapped
route) had its real status silently overwritten to 401 by the entry point on that second pass —
invisible to every MockMvc-based backend test, since MockMvc never performs a real container
forward; only a live server does. (2) `Sidebar`'s drawer `aria-hidden`/tab-focus gating was derived
from `isOpen` alone, with no regard for the actual viewport, so the whole navigation landmark was
hidden from assistive technology (and its links stayed off-screen but still Tab-reachable) on every
authenticated screen at >=1024px by default — invisible to jsdom, which never evaluates the
`max-lg:` media query the CSS actually uses either way. Three were stale specs, not product bugs:
customers.spec.ts still asserted the pre-refactor 1024px table/card breakpoint (Task 27 moved it to
900px, matching SCREENS.md, and never touched this spec); accessibility.spec.ts's rail-collapse
assertion (`aside` width 244px below 1281px) asserted a "collapse to icons" mode the refactor
deliberately removed in favour of a fixed 250px sidebar that is either fully inline or a hidden
drawer, never anything in between (`Sidebar.tsx`'s own doc comment names this); and one heading
lookup used an unscoped name-only locator that matched both the shared page `<h1>` and the case
workspace's own `<h2>` repeating the same customer name by design. One was the spec's own malformed
seed data, not a stale assertion: accessibility.spec.ts's builder-sweep workflow omitted
`estimatedDurationDays` on its milestone, which the API correctly 400s on — the same
omit-a-field-and-it-500s/400s-instead-of-defaulting class of defect sub-project 2's live run
already found in other specs' seed payloads. No assertion was weakened to make a spec pass. Full
detail, including the exact failure output and the reasoning behind each ruling, is in
`.superpowers/sdd/2026-08-29-tasks-and-collaboration/task-1-report.md`.

It starts **both** applications itself, so nothing needs to be running first; if 8080 or
3000 is already bound it reuses what is there, which is wrong often enough that killing strays
first is worth it — **unless that port is held by another session on a shared machine**, in which
case killing it is someone else's work, not a stray. The backend goes through
`e2e/support/backend.mjs`, which tees its output to
`frontend/e2e/.artifacts/backend.log` — **that log is the only place an activation token exists**,
and Playwright gives a test no way to read a `webServer`'s stdout. Override the database the same
way the backend does: `DB_URL=… npx playwright test`. It provisions a tenant per spec file and
never truncates, so point it at a scratch database.

**Sub-project 3A baseline, 2026-09-09** (Task 1) — all three suites run green before any of 3A's
feature work touches the codebase: backend `cleanTest test` reported `BUILD SUCCESSFUL` (502
tests, none skipped), `npx vitest run` reported 61 files / 443 tests all passing, and `npx
playwright test` against the scratch database reported 41 passed in 2.2m, all specs including the
new-since-sub-project-3 `tasks.spec.ts`. The first run surfaced two backend failures, both the same
defect in different tests, neither a product bug: `CaseCreationTest.dueDatesAccumulateInBusinessDaysWithinAStage`
and `TransitionTest.enteringAStageSchedulesItsMilestones` each asserted a due date against the
bare, zero-arg `LocalDate.now()` (the JVM's default time zone) instead of `LocalDate.now(clock)`
(the injected `Clock` — `Clock.systemUTC()` in production, the UTC-backed `MutableClock` in
tests — which is what `CaseEngine` actually computes due dates from). On this host, whose default
zone is UTC+3, the two disagree for the few hours after local midnight but before UTC midnight,
which is exactly the window the suite happened to run in. `MigrationTest` already used the correct
`LocalDate.now(clock)` pattern; both failing call sites were brought in line with it. No assertion
was weakened. Full detail is in
`.superpowers/sdd/2026-09-08-programmes-and-customer-plans/task-1-report.md`.

**Sub-project 4 close-out, 2026-09-25** (Task 37) — all four suites (backend, vitest, `tsc`, and the
full `npx playwright test`, per this sub-project's own added `tsc --noEmit`/`next lint` step) ran
green in the same pass at the end of the branch: backend `cleanTest test` reported `BUILD
SUCCESSFUL` (829 tests, 0 failures/errors/skipped, no OS-memory kill — unlike several individual
tasks earlier in this branch's own ledger), `npx vitest run` reported 87 files / 644 tests all
passing, `npx tsc --noEmit` and `npm run lint` were both clean (2 pre-existing, unrelated warnings),
and the fourteen-spec `npx playwright test` passed in full (46 tests) against a scratch database —
after one real, live regression was found and fixed. **`Sidebar.tsx`'s "Documents" nav item leaked
into a PORTAL contact's own navigation.** `activation.spec.ts` (sub-project 1's own spec, untouched
by this branch) pins "a PORTAL user's rail carries Dashboard and nothing else" — true until this
sub-project gave every portal contact `document.view`/`document.upload` at `Scope.ALL`
(`authz.PortalPermissions`, Task 3's ruling), the first and only permission key a portal actor now
holds that any nav gate in `Sidebar.tsx` also checks. The component filtered strictly on the
permission (`useHasPermission("document.view")`), with no regard for actor type, so a portal
contact was sent a link to the tenant-wide `/documents` OPERATOR screen (no case picker, internal
visibility-tier vocabulary) despite sub-project 4 building no portal UI at all (design spec §2.2).
Fixed by also gating on `user?.userType !== "PORTAL"` (`Me.userType`, already generated from the
OpenAPI schema, previously unread anywhere in the frontend), proven genuinely red-then-green with a
new `Sidebar.test.tsx` case before the fix landed. No live disclosure risk today (the destination
page has no upload affordance, and any document it lists is already narrowed by
`DocumentAudienceFilter`'s own portal branch), but it is the frontend-navigation sibling of the
already-recorded `GET /documents`/`GET /documents/{id}` actor-type blindness gap above — caught only
because an unrelated, pre-existing spec happened to assert the portal rail's exact contents.

API types are generated, never hand-written. `OpenApiDocumentTest` writes `backend/build/openapi.json`
during `:test`; `./gradlew openApiSpec` is the wrapper that produces it and says where it is. `npm run
generate:api` then regenerates `frontend/src/lib/api/generated.ts` from it, and
`npm run generate:api:live` reads a running backend instead. The document's output is declared on
`test`, which writes it — declaring it on `openApiSpec`, which only checks for it, made Gradle delete
it as a stale output moments before the check and the task failed every run. Deleting only
`openapi.json` re-runs `:test`. Note springdoc orders schema properties nondeterministically, so
back-to-back regenerations produce reordering-only diffs; that is noise, not a contract change.

---

## UI/UX: the design system is an input, not a deliverable

`docs/uispecs_latest/design_handoff_onboarding_platform/` is the complete design system for the
whole platform — 19 screens across the operator app and the customer portal, covering every
sub-project, not just the one that first builds the shell. **Frontend work implements it; it does
not invent a visual language.** As of 2026-08-25 this bundle supersedes `docs/uispecs_legacy/`
(formerly `docs/uispecs/`), against which sub-projects 1–2's frontend was originally built — see
"Superseded design system" below for what that means for already-shipped screens.

**Before starting any frontend task, invoke the `frontend-design` and `ui-ux-pro-max` skills.**
Do this whether the task is a new screen, a restyle, or a single component — it is how this
bundle's visual language gets implemented consistently instead of reinvented per screen.

| Read | For |
|---|---|
| `.../README.md` | Overview, fidelity notes, suggested implementation order, non-negotiables |
| `.../DESIGN_TOKENS.md` | Every colour, type, spacing, radius, shadow and motion value |
| `.../COMPONENTS.md` | The ~20 recurring components, exact specs and states |
| `.../SCREENS.md` | Screen-by-screen layout, content and behaviour |
| `.../DOMAIN_RULES.md` | The PRD/QA business rules the UI encodes, and where each surfaces |
| `.../STATE_AND_DATA.md` | State model, TypeScript data shapes, API surface the design implies |
| `.../Onboarding Platform.dc.html` | The interactive design reference — open it before building a screen |

**Order matters:** tokens + shell, then primitives (`COMPONENTS.md`), then screens. Each step is
far cheaper before the next than retrofitted after it. **Do not port the prototype's inline
styles or its single-class state container** — extract the tokens, rebuild each screen against the
codebase's existing component primitives (restyle in place where one already exists, rather than
adding a parallel one), and wire real data. Charts in the reference are hand-built bars; use the
codebase's charting library in production and match colours and the "clock running vs. clock
paused" split-bar semantics instead of copying markup.

Four decisions erode quietly and must be held:

1. **Colour always means status, never decoration.** If you cannot name the state a colour
   represents, use a neutral.
2. **Instrument Sans for human text, Spline Sans Mono for machine-generated values.** IDs, dates,
   counts and metrics are mono; anything a person wrote is not.
3. **Cards are flat.** Elevation is only for what genuinely floats — popovers, device frames.
4. **Colour is never the only signal.** Every status colour is paired with a word or an icon.

**Light theme only — this design system has no dark theme.** Dropping dark-mode support is a
deliberate decision, not an oversight: the bundle defines no dark palette anywhere, and the prior
dark-theme invariant below (`[data-theme="dark"]`, the 49-pair `contrast.py` check run in both
themes) is retired along with `docs/uispecs_legacy/`. The frontend refactor for sub-projects 1–2
removes dark-theme support from the app itself (theming mechanism, `next-themes` config, the
`ThemeProvider`/theme-toggle UI) rather than leaving it half-wired against tokens that no longer
exist. Do not reintroduce a dark palette without a design decision to do so first.

**Gaps the design does not cover, which implementations must supply:** empty states, loading
skeletons, error states, and any layout below 1440px.

### Superseded design system

`docs/uispecs_legacy/` is retained for two reasons only: its build scripts (`contrast.py`,
`build_tokens.py`, `build_icons.py`, etc.) may still be useful during the refactor, and it is the
document sub-projects 1–2's original frontend implementation (and the plan/spec files describing
that work, task by task) was actually built against — rewriting those historical references would
misrepresent what was built and why. **Do not read it for current token values, copy, layout, or
component behaviour.** Its three-layer token system (primitive → semantic → component), its 56
icons, its `IBM Plex Mono`/`Archivo` type pairing and its light/dark theming are all superseded by
the bundle above. Frontend tasks touching sub-project 1 or 2 screens for the first time since
2026-08-25 restyle against the new bundle, not this one.

---

## Non-negotiable invariants

These are enforced by tests that will fail the build. They are not style preferences, and the
correct response to one failing is to fix the code, never to weaken the guard.

- **Every tenant-owned table** has a non-null `tenant_id`, an RLS policy, and
  `FORCE ROW LEVEL SECURITY`, created in the same migration as the table. `RlsCoverageTest` is
  deny-by-default over the live schema; its allowlist has four reviewed entries and adding a fifth
  is a deliberate act.
- **The application connects as `onboarding_app`** — non-superuser, non-BYPASSRLS. Connecting as
  the owner makes every isolation test pass vacuously.
- **DELETE is deny-by-default at the database layer.** Business records are deactivated, never
  deleted. A table needing deletion carries an explicit `GRANT DELETE` with a comment saying why.
- **Migrations are forward-only.** Never edit a committed migration, not even temporarily.
- **No dependency cycles between modules** (`ModuleBoundaryTest`). Two consequences bite
  repeatedly: `platform` is the foundation everything depends on, so it must never name a domain
  type — a domain exception's `@RestControllerAdvice` belongs in that domain's own module. And any
  class orchestrating two or more domain modules cannot live inside one of them; it needs its own
  slice, which is why `provisioning` and `scoping` exist.
- **Every public `*Service` method carries `@RequirePermission`** (`AuthorizationCoverageTest`).
  Exclusions are per-class, commented, and fall into exactly two categories: runs before there is
  an actor to authorize, or is infrastructure the gate itself depends on.
- **Reads of tenant business data go through `AuthorizedQuery`** — and so does **every id a write
  path takes from a URL or a request body**, before it writes. A repository finder called directly
  skips the scope predicate: a silent, total bypass rather than a visible error. The write half is
  the one that keeps escaping, because `@RequirePermission` cannot see arguments, so a passing gate
  proves only that the actor may touch *some* record of that type. Three separate escalations in
  sub-project 1 were this exact shape — contact creation, role assignment, invitation issuance —
  and each was fixed individually before the pattern was named. Sub-projects 2–10 nest resources
  far more deeply than customer→contact, so expect more of them, not fewer.
  `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` covers `customer..`,
  `identity..` and `auth..`; **add your package to it in the same commit that adds your service.**
  Note the rule is **name-shaped**: it binds to classes ending in `Service`, so `IdentityActorDirectory`
  and `UserRoleDirectory` call finders directly and are invisible to it. Both are correct today, but
  a future `*Directory` taking a foreign id would be unguarded in exactly the way `auth` was.
- **Permissions are never embedded in tokens and never cached across requests.** Authority is
  resolved server-side per request, so a revoked grant takes effect on the next call rather than
  when a token happens to expire. **The same applies to a revoked account**, which is why
  `AuthorizationService` joins `app_user` on `status = 'ACTIVE'`: a deactivated user resolves zero
  permissions, so every gate denies and every `AuthorizedQuery` predicate collapses to disjunction
  on the very next request, rather than after the access token's remaining ≤15 minutes.
- **Deactivating a user must end the session, not set a column.** `deactivate` revokes every
  refresh family (through the `identity/UserSessionRevoker` port) and `RefreshTokenService.rotate`
  independently refuses a non-ACTIVE user, because deactivation will not stay the only way a status
  changes. Before this, `UserStatus.ACTIVE` was read in exactly one place in the whole main source
  tree — `LoginService` — which a browser holding a refresh cookie never reaches again.
- **`JWT_SECRET` is required configuration, not a default with an override.** `JwtProperties`
  refuses to start the application when it is unset, under 32 bytes, or one of the three secrets
  this repository has published (`JwtSecretGuardTest`). The guard is deliberately not keyed on
  profile — a "unless dev" check misses the deployment that forgot the profile too. **No usable
  signing secret is written down anywhere in this repository**: `application.yml` ships no fallback,
  the backend suite generates one per run in `PostgresTestBase`, and the e2e harness does the same
  in `e2e/support/backend.mjs`. Do not reintroduce a literal — a committed secret is a secret
  nobody has, and the denylist is what a value becomes once it has been published.
- **Every resource type registers a `ResourceAuthorizationDescriptor`.** `DescriptorRegistry.validate()`
  refuses to start the application otherwise — an unregistered type would reach scope resolution with
  no predicate to apply. Descriptors must fail closed: no department, no teams ⇒ `cb.disjunction()`.
- **An entity type may register an opt-in `AudienceFilter`** (`authz.AudienceRegistry`, consumed by
  `AuthorizationPredicateBuilder`), narrowing visibility *underneath* record-level scope — including
  at `Scope.ALL`, which used to return an unconditional match. Added in sub-project 4
  (`scoping.DocumentAudienceFilter`, still the only implementation): `forPermission`'s `ALL` branch
  is no longer unconditional wherever a filter is registered for that entity type, and a future
  entity needing the same "governed even at the tenant's widest scope" property registers its own
  filter rather than special-casing `forPermission` again. A second, narrowly-scoped method exists
  beside it, `AuthorizationPredicateBuilder.forPermissionIgnoringScope`/
  `AuthorizedQuery.countIgnoringScope` — bypasses only the record-level scope union (never the
  audience filter, and never a caller holding no grant at all) for exactly one caller,
  `DocumentService.visibilitySummary`'s deliberately scope-unbounded tenant total, after a security
  review found the first version of that method bypassing the audience filter too and leaking a
  portal contact's own count across customers. Reach for `forPermissionIgnoringScope` only with the
  same written, per-call-site safety argument this one carries — it is a bypass of one specific,
  sanctioned shape, not a general escape hatch.
- **`ASSIGNED` means a personal relationship** (`RelationshipType`); access mediated by a team the
  user belongs to is `TEAM`. Conflating them silently widens `ASSIGNED` to everything the user's
  teams can reach.
- **Out-of-scope records return 404, never 403.** The UI must not reintroduce the distinction the
  404 exists to hide.
- **A `PUT` is a full replace, so its view type must carry every field its request type accepts.**
  A field absent from the JSON body deserialises to null and is written as null — omitting it is
  identical to blanking it, so "the form just doesn't send it" is not a mitigation. Adding a field
  to an `Update*Request` without adding it to the matching `*View` makes every client silently
  erase it.
- **Absence of a grant is the denial.** There are no deny grants anywhere in authorization.
- **`audit_event` is append-only at the database layer** — `GRANT SELECT, INSERT` with `UPDATE` and
  `DELETE` revoked. A permission, not a convention, because an audit trail the application can
  rewrite is not evidence.
- **UUIDv7 primary keys** via `co.ara.onboarding.platform.Uuid7.generate()`. Values that must be
  unpredictable rather than merely unique (refresh tokens, invitation tokens) use `SecureRandom`
  directly and never a UUID.
- **All timestamps** are `timestamptz`, stored in UTC.

**Sub-project 2's own ten** (design spec §10's cross-check; a change breaking one of these is a
change to the design, not an implementation detail):

- A published workflow version never mutates — publish is the last legal `UPDATE`; a trigger
  refuses every write to a `PUBLISHED` row.
- A case always has exactly one pinned version, `NOT NULL` from creation; migration repins, never
  unpins.
- `journey` never depends on `customer` entities or repositories — only `CustomerDirectory`.
- Every runtime mutation goes through `CaseEngine.reconcile`, under the row lock
  `CaseRepository.lockById` takes — nothing else calls it.
- Progress is derived and stored by the engine every reconcile; no request type accepts one.
- Authorization narrows at a stage's `write_scope`; there is no branch that widens it.
- Nobody delegates a permission they do not hold at an equal or broader scope
  (`RoleService.refuseEscalation`).
- A cross-tenant id is consistently a 404 — never the 200 a bypassed-RLS FK check would produce,
  nor the 500 an invented id does.
- Branch rules run forward and dependencies point backward, both enforced structurally at
  publish, never detected at runtime.
- Skipped milestones contribute to neither progress numerator nor denominator.

**Sub-project 3's own ten** (design spec §10's cross-check; a change breaking one of these is a
change to the design, not an implementation detail):

- `journey` never imports a `task` type; both directions of the relationship go through
  `TaskDirectory` / `TaskLifecycle`.
- Task completion adds no new caller of `CaseEngine.reconcile` — it goes through the existing
  gated `RequirementService.satisfy`.
- A cancelled task never satisfies or waives its requirement.
- Checklist items never enter a progress calculation.
- `comment.resource_type` is constrained at the database and in Java; adding a value is a
  migration.
- Every comment carries `case_id`, so comment reads need no `AuthorizedQuery` carve-out.
- `ASSIGNED` on a task means `assignee_id = actor`, never team-mediated.
- Every audit action is recorded before the calls that record its consequences.
- Out-of-scope and cross-tenant ids are 404.
- `PUT` request and view types stay field-for-field aligned.

Each was verified during this plan's own execution, not just asserted: #1 by
`ModuleBoundaryTest.noJourneyDependencyOnTask` (Task 15, proven red-then-green by a temporary
violation); #2 by Task 17's `changeStatus` review (confirmed no `CaseEngine`/lock call in that
method); #3 by Task 18's review (traced the fixture chain proving cancellation structurally cannot
reach `satisfy`/`waive`); #4 by Task 22's review; #5 by Task 11's migration + enum; #6 by Task 23's
review ("no carve-out, which is the whole reason `case_id` is denormalised" — confirmed in the
actual code); #7 by Task 13's `TaskDescriptor` and Task 25's dedicated negative test; #8 by Task
25's `CauseBeforeEffectTest` additions (`task.created`/`task.status_changed`→`requirement.satisfied`
→`milestone.completed` subsequences); #9 by Task 16's and Task 25's negative tests; #10 by Task
16's `TaskView` review (carries every field `UpdateTaskRequest` accepts). Re-verified at
sub-project 3's close (2026-09-08): the full backend suite, vitest and the ten-spec Playwright
suite (`tasks.spec.ts` included) all ran green in the same pass — each read from its own summary
line, not a pinned count (see the Tests section below for why) — so none of the ten had regressed
by the time the plan finished.

**Sub-project 3A's own ten** (design spec §10's cross-check; a change breaking one of these is a
change to the design, not an implementation detail):

- A programme has no lifecycle — nothing in `programme` calls `CaseEngine.reconcile`.
- `journey` never imports a `programme` type; the arrow is one-way.
- Programme participation grants read of the programme only; every journey read still goes through
  `AuthorizedQuery` under `case.view`.
- Programme progress is derived on read, never stored; no request type accepts one.
- A published workflow version still never mutates — plan approvals live in their own table
  precisely because the freeze trigger refuses everything else.
- A case still has exactly one pinned version; cloning and refreshing a customer template create
  workflow versions and never touch a case's pin.
- `plan_revision_item` is append-only at the database layer, the same `GRANT` shape as
  `audit_event`.
- The plan hold reuses `Case.held_at`/`CaseOnHoldException` — no second pause mechanism.
- Progress stays one number for every audience; `portal_visible` filters rendering only, never the
  denominator.
- Out-of-scope and cross-tenant ids are 404; `PUT` request/view types stay field-for-field aligned.

Each was verified against the actual code at Task 35's close-out, not just asserted: #1 by grepping
`programme`'s main and test sources for `CaseEngine` (none call it; `ProgrammeRollupTest` asserts it
structurally) and Task 14's own test; #2 by `ModuleBoundaryTest.noJourneyDependencyOnProgramme`,
proven red before Task 9's module was used elsewhere (Task 10); #3 by `ProgrammeScopeTest`, which
seeds a programme with one journey the participant can already open and one they cannot, and proves
only the first is visible; #4 by reading `V17__programme.sql` (no `progress_percent` column) and
`CreateProgrammeRequest`/`UpdateProgrammeRequest` (neither accepts one) — `ProgrammeJourneyView`'s
own `progressPercent` is a read-only projection of the journey's own engine-derived value, not a
stored programme field; #5 by `PlanShapeSchemaTest.approvalColumnsCouldNotHaveLivedOnTheVersionItself`,
which asserts the trigger's own refusal message against a live `UPDATE`; #6 by confirming
`Case.setVersionId` is called only from `CaseService.create` (pins) and `MigrationService` (repins),
and that `CustomerTemplateService` (clone/refresh) touches no `onboarding_case` row at all; #7 by
reading `V21__plan_revision.sql`'s `GRANT SELECT, INSERT` (no `UPDATE`/`DELETE`); #8 by grepping
`held_at`/`ON_HOLD`/`CaseOnHoldException` across `src/main` — every hit is the one existing
mechanism, nothing new; #9 by `git log --oneline -- .../journey/CaseEngine.java` against this
branch's own commits — one hit, a two-line javadoc correction (cb875d7) with no change to
`reconcile` or any mutation path; #10 by `ProgrammeIsolationTest`/`ProgrammeServiceTest`'s
cross-tenant and out-of-scope cases, and by reflecting over `UpdateProgrammeRequest`/`ProgrammeView`'s
record components (`ProgrammeServiceTest.updateIsAFullReplaceAndTheViewCarriesEveryFieldTheRequestAccepts`).
Re-verified at sub-project 3A's close (2026-09-12): the full backend suite (603 tests), vitest (77
files, 553 tests) and the twelve-spec Playwright suite (44 tests, `customer-plan.spec.ts` and
`programme.spec.ts` included) all ran green in the same pass, so none of the ten had regressed by
the time the plan finished.

**Sub-project 4's own ten** (design spec §10's cross-check; a change breaking one of these is a
change to the design, not an implementation detail):

- `journey` never imports a `document` type; the dependency is one-way and there is no port back.
- Documents add no new caller of `CaseEngine.reconcile` — satisfaction goes through the existing
  gated `RequirementService.satisfy`.
- A retired document satisfies nothing: its shares and links are revoked and any requirement it
  satisfied is reopened.
- A withdrawn request never satisfies its requirement.
- The audience filter binds `ALL` — an ALL-scoped `document.view` holder is refused a targeted
  document's content; `document.manage` is the one permission the filter deliberately does not
  narrow, and it grants metadata only, never bytes.
- A portal actor's permission set is a code constant, never a `user_role` row.
- Derived audience is never materialised — no table caches who may see a document.
- Every byte transits the gate: no presigned URL, no direct blob access, on either adapter.
- `document_version` rows are immutable and append-only; a new version never edits an old one.
- Out-of-scope and cross-tenant ids are 404; `PATCH`/view types stay field-for-field aligned.

Each was verified against the actual code at Task 37's close-out, not just asserted, several of
them re-derived from scratch rather than trusted from an earlier task's own report: #1 by
`ModuleBoundaryTest.noJourneyDependencyOnDocument`/`.noTaskDependencyOnDocument` (Task 9, proven red
before the real rule existed) and by the port itself — `journey.DocumentRequestLifecycle` (one
method, no `document` type anywhere in its signature) implemented by
`document.DocumentRequestLifecycleAdapter`, the same inversion `TaskLifecycle` already established;
#2 by `git log --oneline 342bdf2..HEAD -- backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java`,
which returns **zero commits** — not even a comment-only touch, a cleaner result than sub-project
3A's own two-line javadoc hit at its equivalent check — and by confirming `document`'s four services
(`DocumentService`, `DocumentSharingService`, `DocumentRequestService`, `DocumentReviewService`)
call neither `CaseEngine` nor `CaseRepository.lockById` anywhere, only
`journey.RequirementService.satisfy`/the new `.reopen`; #3 by `DocumentServiceTest`'s
`retiringRevokesEveryLiveShare`/`.EveryCrossJourneyLink`/`.ReopensARequirementItSatisfied` (Task 18)
plus the `DocumentStatus.RETIRED` guards later added to every other write path capable of
re-satisfying a retired document's requirement once review found each one missing it in turn
(`DocumentRequestService.fulfil`, Task 25; `DocumentReviewService.review`, Task 27;
`DocumentSharingService.share`, Task 19); #4 by `DocumentRequestServiceTest`'s Task 23 test proving
`withdraw` structurally imports neither `RequirementService` nor `CaseEngine`; #5 by
`security.DocumentAudienceTest`'s pivotal ALL-scope test (Task 12) and, for the load-bearing fix,
`DocumentControllerTest.aPortalContactHittingTheOperatorVisibilitySummaryRouteNeverSeesAnotherCustomersDocuments`
(Task 32), which pins the exact regression a security review found live — an ALL-scoped portal
actor's own aggregate count leaking another customer's documents — now closed by
`AuthorizationPredicateBuilder.forPermissionIgnoringScope`; `document.manage`'s carve-out is
`DocumentAudienceFilter`'s own explicit early return, confirmed never reached by the download path
(`DocumentController`'s content endpoint resolves under `DOCUMENT_VIEW` only); #6 by
`authz.PortalPermissions` (a static `Map`-returning method, not a database row) and
`security.PortalAuthorityTest`'s exact-set assertion; #7 by reading `V23__document.sql` in full — no
table stores a resolved viewer list, only the source facts (`tier`, targeting, shares, links)
`DocumentAudienceFilter` recomputes at query time; #8 by `LocalFsBlobStore`/`S3BlobStore` (Tasks 4-5)
and `DocumentController`'s download endpoint (`ResponseEntity<InputStreamResource>`, Task 22), and a
grep for "presigned"/`PutObjectRequest`-style pre-signed-URL construction anywhere in `document`
(none); #9 by `V23__document.sql`'s `document_version_immutable_trg` trigger and
`DocumentSchemaTest.updatingAnImmutableVersionColumnIsRejected`/`.theReviewOutcomeOfAVersionRemainsUpdatable`
(Task 8), proving both halves — content frozen, review outcome deliberately not; #10 by
`document.DocumentIsolationTest` (Task 21's three tests, plus the roughly dozen already-scattered
per-method cross-tenant tests across `DocumentServiceTest`/`DocumentSharingServiceTest`/
`DocumentRequestServiceTest`) and by reflecting over `PatchDocumentRequest`'s four fields (`name`,
`category`, `targetDepartmentId`, `targetContactLabel`), all four present on `DocumentView`.
Re-verified at sub-project 4's close (2026-09-25): the full backend suite (829 tests), vitest (87
files, 644 tests) and the fourteen-spec Playwright suite (`documents.spec.ts` and
`document-requests.spec.ts` included) all ran green in the same pass, so none of the ten had
regressed by the time the plan finished.

---

## Where the guards live

- `backend/src/test/java/co/ara/onboarding/architecture/` — `RlsCoverageTest`,
  `AuthorizationCoverageTest`, `ModuleBoundaryTest`, `OpenApiDocumentTest`.
- `.../authz/DescriptorRegistryTest`, covering `DescriptorRegistry.validate()`.
- `.../security/` — the nine negative tests: `ChangedPermissionsTest`, `ConflictingGrantsTest`,
  `CrossTenantAccessTest`, `DelegationGuardTest`, `DirectApiAccessTest`, `InsufficientPermissionTest`,
  `InsufficientScopeTest`, `MultipleRolesTest`, `RoleLifecycleTest`. Sub-project 2's own negatives
  live in-package instead, and — corrected here, verified against the actual code rather than
  copied forward — three of them are in `security` too, not `journey`: `security.CaseIsolationTest`
  (cross-tenant), `security.WriteScopeTest` (a wider-scoped holder still refused inside an
  `OWNER_ONLY` stage), `security.ForceCompleteTest` (self-approval refused; deciding a
  `FORCE_COMPLETE` through the stage-approval endpoint refused, not weakly gated),
  `scoping.JourneyScopingTest` and `identity.TeamMembershipTest` (TEAM resolves through real team
  membership, not a column). Sub-project 3's own negatives live in-package the same way:
  `task.TaskIsolationTest` (cross-tenant, the polymorphic comment discriminator refused at the
  database), `task.TaskWriteScopeTest` (a wider-scoped holder still refused inside an
  `OWNER_ONLY` stage, the same shape as `security.WriteScopeTest`), `task.TaskServiceTest`
  (the create/update case-milestone-mismatch escalation guards), and `task.CommentTest`
  (author-only edit enforced independently of scope). Sub-project 3A's own negatives:
  `programme.ProgrammeIsolationTest` (cross-tenant), `programme.ProgrammeScopeTest` (participation
  grants read of the programme only — a journey inside it is visible ONLY through its own real
  `CaseParticipant` row, never through programme membership), `programme.ProgrammeServiceTest` and
  `.ProgrammeMembershipServiceTest` (the customerId-escalation guards on create/list, and the
  narrowest-scope TEAM write), `workflow.PlanShapeSchemaTest`/`.PlanShapeGateTest` (gate 1: the
  freeze trigger forced approvals into their own table; only a DRAFT's owning template's version can
  be submitted), and `journey.PlanRevisionTest`/`.PlanHoldTest`/`.PlanSnapshotImmutabilityTest`
  (gate 2: a customer-template case created `ON_HOLD` and released only by its first schedule
  approval; the snapshot a revision decides against never changes underneath the decision).
  Sub-project 4's own negatives: `document.DocumentIsolationTest` (cross-tenant, a short
  representative file in `task.TaskIsolationTest`'s/`programme.ProgrammeIsolationTest`'s own shape,
  not a duplicate of the per-method tests already scattered across `DocumentServiceTest`/
  `DocumentSharingServiceTest`/`DocumentRequestServiceTest`), `security.DocumentAudienceTest` and
  `security.PortalAuthorityTest` (the two audience-filter halves — an ALL-scoped internal reader and
  a portal contact each refused a targeted document, both hand-traced clause-by-clause at opus-level
  review with no scope-widening bug found), and `DocumentControllerTest`'s
  `aPortalContactHittingTheOperatorVisibilitySummaryRouteNeverSeesAnotherCustomersDocuments` (the
  `forPermissionIgnoringScope` regression test, pinning the one live cross-customer disclosure this
  branch's own security review found and closed).

**These are not to be weakened to make a change pass.** They exist precisely to fail when something
is missed. An allowlist entry or an exclusion added to green a build defeats the isolation design,
and its failure mode — silent cross-tenant exposure — is the one this product cannot survive.

**A guard is only as wide as its enumeration, and every enumeration in sub-project 1 drifted
behind the code.** All three ArchUnit rules, `DirectApiAccessTest`'s endpoint list and
`contrast.py`'s pair table are hand-written lists, and each was correct when written: the
`AuthorizedQuery` rule named two of the packages that needed it, the endpoint list is eleven
endpoints short (detail in the plan's *Notes for the Executor*), the contrast table covered one of
the two themes that ship. **Prefer a derivable list to a typed one** — sweep every `@RestController`
mapping rather than naming paths, resolve both themes rather than one — so a guard grows with the
code instead of being re-widened by hand after each miss.

**What sub-project 2 actually did with that advice, verified against the code rather than the
plan's intentions for it:**

- **`ModuleBoundaryTest`** gained the two rules of spec §3.3, each its own named method rather
  than folded into the cycle check — `noWorkflowDependencyOnJourney`,
  `noJourneyDependencyOnCustomer` — because a one-way import in either direction would still pass
  a plain no-cycles rule.
- **`AuthorizationCoverageTest`**'s permission-gate rule gained `*Engine` alongside `*Service`, so
  `CaseEngine` itself must carry `@RequirePermission`. Its finder rule widened from `*Service` to
  `*Service`-or-`*Directory` over `workflow..`/`journey..` — `CustomerDirectory` is exactly the
  shape the rule exists to catch, since it takes a customer id straight from a request body. The
  exclusions are **by call-target owner name, not by naming individual methods**: a call whose
  target owner ends in `AuthorizedQuery` (the sanctioned wrapper) or `AuditQuery` (journey's
  timeline carve-out, below) is allowed, plus `IdentityActorDirectory` and `UserRoleDirectory`
  excluded per-class (both run before there is an actor to authorize). `CaseRepository.lockById`
  needs no exclusion at all here — it is never called from a `*Service` or `*Directory`, only from
  `CaseEngine` itself, so the finder rule never sees it. (The design spec describes `lockById` as
  "a per-method exclusion"; that names the intent, not a literal clause in this test.)
- **`DirectApiAccessTest`** gained one new test, `everyTenantScopedEndpointRejectsAnonymousAccess`,
  which derives its list by sweeping `RequestMappingHandlerMapping` rather than naming paths — but
  the original hand-typed `everyEndpointRefusesAnonymousAndResolvesForAnAdministrator` (the one
  eleven endpoints short) is still there, unconverted, alongside it. "Rewritten to derive"
  describes the new test, not the file; the old one is still a list someone has to remember to
  widen by hand.
- **Six descriptors exist in `scoping/`, not the catalog's four.** `DescriptorRegistry.validate()`
  only requires one per record-scoped permission's own resource type (`onboarding_case`,
  `milestone`, `requirement`, `approval`), but `AuthorizedQuery.findAll`/`getById` dispatch by
  **entity type** for any read through that path — and `CaseService` reads `CaseParticipant` and
  `CaseAttributeValue` rows under `case.view`/`case.edit` with no permission of their own catalogued
  against either. `CaseParticipantDescriptor` and `CaseAttributeValueDescriptor` exist to satisfy
  `AuthorizedQuery`, not `validate()` — a future entity read the same way needs the same second
  descriptor, and `validate()` alone will not remind anyone to add it.

---

## Working conventions

- **One module per domain**, owning its own entities, repositories, services and controllers and
  exposing a narrow interface. Sub-projects 2–9 each add one; nothing reaches into another module's
  internals.
- **Every user-facing string goes through `t()`** (`frontend/src/lib/i18n`). A missing key renders
  as the key itself, so gaps are visible rather than silent.
- **TDD.** Write the failing test first; security tests before the mechanism they verify. A
  structural guard you have never seen fail is a guard you cannot trust — prove new ones red.
- **Tests that construct their own preconditions converge on the happy scope.** Every write case in
  `UserAdminTest` granted `USER_MANAGE` at `ALL`, which is precisely why the escalation survived:
  not one test asked what a *narrow* write scope does. Wherever a permission is catalogued at
  several scopes, **at least one write test must run at the narrowest one.**
- **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`). Explain *why* in the
  body, especially when deviating from the plan.
- **Never assert an exception inside a `fixture.runAs(...)` lambda.** Those helpers run in a
  `TransactionTemplate`; catching inside leaves it rollback-only and surfaces
  `UnexpectedRollbackException`, masking the exception under test. Wrap the helper instead.
- **Fixture create-helpers must run inside `runAs`** — the tables they write are RLS-protected, and
  Spring Data repository proxies do not trigger the tenant binder.
- **Backend tests need Docker running** (Testcontainers, `postgres:16-alpine`).
- Run `cd backend && ./gradlew cleanTest test` before committing. On PowerShell use `.\gradlew.bat`.

---

## What sub-project 2 inherits

- **The descriptor seam.** A new resource type implements `ResourceAuthorizationDescriptor` in
  `scoping/` — never in the module owning the entity, which would close a module cycle — and returns
  the DEPARTMENT, TEAM and ASSIGNED predicates. Nothing imports the implementations; Spring collects
  them by interface and the registry validates coverage against the permission catalog at startup.
- **`RelationshipType`** (`OWNER, ASSIGNEE, PARTICIPANT, APPROVER, CREATOR`) is the vocabulary cases
  and milestones extend. Extend the enum; do not invent a parallel one.
- **`audit_event.timeline_visible`** is on every event and is what the Activity Timeline reads: the
  audit trail and the customer-visible timeline are one table, separated only by this flag. Set it
  deliberately for each new action rather than copying a neighbour. The split so far: business
  records (`customer.*`, `contact.*`, `invitation.*`) are visible, identity and auth
  (`user.*`, `role.*`, `auth.*`, `tenant.created`) are compliance-only.
- **"What does deactivation revoke?" is a required design question**, asked alongside "does it have
  a descriptor?" whenever a sub-project adds a deactivatable entity. "Never delete, deactivate
  instead" is only half a mechanism: the *storage* half is enforced rigorously (DELETE revoked at
  the database, proven red), but the *consequence* half is convention only. Nothing revoked
  sessions, invitations, tokens or grants on deactivation until the final fix wave of sub-project 1,
  and two gaps remain open above. Enumerate what a status change must invalidate before writing the
  setter, not after.
- **Retirement gets its own action**, not a flag on an update — `contact.deactivated` is recorded
  when an update *transitions* status into INACTIVE, never when it merely arrives INACTIVE. Because
  business records are never deleted, that event is the only record the retirement happened, and it
  must stay distinguishable from a phone-number correction. Repeat the shape for anything a later
  sub-project retires.

## What sub-project 3 inherits

- **The requirement seam.** `RequirementRoadmapView`'s `kind`, and `CaseRequirementView`'s
  `satisfiedRef`/`satisfiedRefType`, exist precisely so a task, a document or an agreement can
  satisfy a requirement by reference instead of the plain manual check-off this sub-project's UI
  ever sends. `SatisfyRequest`'s own doc comment names this; both fields are already nullable and
  already round-trip, so satisfying one from a real record is a caller populating them, not a
  schema change.
- **`CaseEngine.reconcile`, under `CaseRepository.lockById`'s row lock, is the only path to a
  runtime mutation** (invariant 4 above). Any new write against a case — a task completing, a
  document approving, an agreement signing — calls the same gated `reconcile`, inside the same
  lock, rather than recomputing status or progress independently. Two connections racing the last
  requirement of a milestone is exactly the shape `journey.ReconcileConcurrencyTest` proves the
  lock closes; a second write path that skips it reopens that race for its own resource type.
- **The `write_scope` guard.** A stage's `write_scope` (`ANY`/`DEPARTMENT`/`TEAM`/`OWNER_ONLY`)
  narrows who may write inside it, on top of — never instead of — the record-level scope a
  permission is held at. `StageWriteScopeGuard` is the one place that checks it; a new mutation
  against a case's stage calls through it rather than re-deriving the check.
- **"What does this new entity's deactivation revoke?" is still the required design question**
  sub-project 1 made it (above), now asked of whatever sub-project 3 can retire or cancel: a task,
  a document request, an agreement. Enumerate what a status change must invalidate before writing
  the setter.

## What sub-project 4 inherits

- **The `attachment_ref`/`attachment_ref_type` seam on `task`.** `V16__task.sql` (Task 11) already
  carries both columns, nullable, and `TaskView`/`Task` already round-trip them — so a document
  sub-project 4 builds only needs to populate the field on satisfaction or creation, not add a
  schema migration or a new column. **Note this seam is on `task` only, not on `comment`** —
  `comment`'s own columns in the same migration carry no equivalent pair, so a comment attachment
  (if sub-project 4 wants one) is a real schema addition, not a caller populating an existing field.
- **Upload hardening was resolved 2026-09-13** (deferred 2026-09-12 at brainstorming, at the
  user's instruction, not decided against). Sub-project 4 accepts binary uploads from **external
  portal users**, a class of exposure the platform has never had — every input it took before this
  was JSON through bean validation — so the enumerated defences got an explicit ruling rather than
  being designed around silently. **Four ship:** a size ceiling (one `app.storage.max-upload-bytes`
  property, one 413 path); a sniffed-content MIME allowlist, validated against the bytes actually
  uploaded rather than the caller's declared `Content-Type`; `Content-Disposition: attachment` on
  every download; and a per-version SHA-256 digest, computed alongside the upload stream and stored
  on `document_version` — integrity, duplicate detection, and the provable version identity
  sub-project 5's agreements need. **Two were never actually open**, forced by decisions made
  elsewhere in the design: opaque generated storage keys (no user-supplied string ever reaches a
  path) and streaming every byte through the application (no presigned URL exists to harden).
  **Two are deferred, not dropped:** malware scanning (a ClamAV sidecar, an async
  `UPLOADED→SCANNING→CLEAN/QUARANTINED` state machine, a visible pending state across the UI, and a
  new test-stack container touch nearly every task in Phases 4–6 rather than bolt on — its own
  focused piece of work once the core document flow is proven; revisit on a compliance requirement
  naming it, or portal upload reaching production) and a per-tenant byte quota (an
  operational/billing concern with its own backfill question for tenants that already exist;
  revisit if multi-tenant storage cost becomes a real problem or the product decides to bill by
  usage). Full ruling and reasoning: spec §2.3/§7.6.

## What sub-project 5 inherits

- **Every write path capable of touching `Requirement.satisfiedRef` needs a `RETIRED`-shaped guard
  as a matter of course, not rediscovered each time.** Three consecutive Phase 5 tasks in this
  sub-project each shipped without one and were caught by review before merge: `retire`/`link`
  themselves (Tasks 18/20), `DocumentRequestService.fulfil` (Task 25), and
  `DocumentReviewService.review`'s APPROVE branch (Task 27) — the identical "a requirement satisfied
  by reference to a document/record nobody can ever act on again" shape every time. A `SIGNATURE`
  kind's own satisfaction path should carry this check from its very first draft.
- **`document_version.sha256`** is exactly the "provable version identity" sub-project 5's own
  spec already names needing — a per-version content digest, computed alongside the upload stream,
  no new schema required to cite it from an agreement's signature record.
- **The portal-write precedent** (`document.PortalCaseAccess`, `DocumentService.uploadFromPortal`,
  Task 26): resolve a foreign case with a plain, deliberately-`AuthorizedQuery`-bypassing lookup
  (there is no meaningful scope to check for a portal actor against an entity with no
  `AudienceFilter`) followed by an explicit customer-id comparison; skip `StageWriteScopeGuard`
  entirely and document why (a stage's `write_scope` governs internal collaboration, not a
  customer's own action on their own case); and make the write path **self-defending** —
  re-verify the acting contact and the case/customer match *inside* the gated method itself, not
  only in its controller. That last point was a real, opus-level review finding on this branch (a
  portal upload that trusted its controller's own checks) — a future portal write path (a sponsor's
  approval, a customer's signature) should not repeat the gap it took a fix round to close here.
- **The `AudienceFilter`/`AuthorizationPredicateBuilder` seam** (above) is reusable by any future
  entity type needing governance beneath `Scope.ALL`. A `SIGNATURE`-kind record targeted the same
  way a document is registers its own filter, the way `DocumentAudienceFilter` does, rather than
  reopening `AuthorizationPredicateBuilder` itself.

## Plan deviations

The plans are detailed and mostly correct, but they were written ahead of the code and contain
defects that only surface on a real run. When you find one, fix the code **and** amend the plan so
the finding carries forward, then say so in the commit body. Several tasks in sub-project 1 already
carry such amendments.

---

*Keep it dense — this file is loaded into every session. Add a line only when its absence would cost
a future session real time, and delete one when it stops being true.*
