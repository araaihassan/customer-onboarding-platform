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
| **`docs/uispecs/`** | **Every visual and interaction decision — see below** |

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

**Sequence** (each gains a `*-design.md` in `docs/superpowers/specs/` and a plan in
`docs/superpowers/plans/`): 1 Foundation & Tenancy → 2 Workflow Engine & Case Lifecycle → 3 Tasks &
Collaboration → 4 Documents → 5 Agreements (needs 4) → 6 Notifications, SLA & Escalation (2, 3) →
7 Customer Portal (2, 4, 5) → 8 Dashboards & Real-time (2–6) → 9 Reporting & Analytics (2–6) →
10 Packaging & Deploy. Sub-projects 2–9 each add one module in the shape of sub-project 1's Tasks
20–21: entity with `tenant_id`, migration calling `enable_tenant_rls`, a
`ResourceAuthorizationDescriptor`, a service where every public method is gated and every read goes
through `AuthorizedQuery`, and a thin controller.

---

## Running it locally

PostgreSQL 16, database `onboarding`:

```bash
docker run -d --name onboarding-db -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=onboarding postgres:16-alpine
```

Flyway connects as the owner (`postgres`/`postgres`) and `V2` creates the `onboarding_app` login
role the application then connects as. Override with `DB_URL`, `DB_OWNER_USER` / `DB_OWNER_PASSWORD`,
`DB_APP_USER` / `DB_APP_PASSWORD` (defaults in `backend/src/main/resources/application.yml`). If 5432
is already taken, map another port and set `DB_URL` to match.

**Backend** on :8080 —

```bash
cd backend && SPRING_PROFILES_ACTIVE=dev \
  JWT_SECRET="$(openssl rand -base64 48)" \
  APP_PLATFORM_ADMIN_EMAIL=ops@example.com APP_PLATFORM_ADMIN_PASSWORD=<pick-one> ./gradlew bootRun
```

PowerShell — this repository is developed on Windows, and the bash form above runs on neither
`cmd` nor PowerShell (`VAR=value cmd` prefixing and `$(…)` are bash). Omitting `JWT_SECRET` is not
an option: the application refuses to start without it.

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:JWT_SECRET = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
$env:APP_PLATFORM_ADMIN_EMAIL = "ops@example.com"
$env:APP_PLATFORM_ADMIN_PASSWORD = "<pick-one>"
.\gradlew.bat bootRun
```

`JWT_SECRET` is **required, on every profile** — the application refuses to start without at least 32
bytes of it, and says so naming the variable. There is no dev default: a committed one is a signing
key every reader of this repository holds, and the deployment that forgets the variable is exactly
the one that would use it. Pick a value once and keep it in your shell; changing it between restarts
invalidates every access token already issued, which reads as a spate of 401s.

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
away once a tenant's administrator is activated:

```bash
curl -u admin@acme.test:<password> -X POST http://localhost:8080/api/t/acme/workflows \
  -H 'Content-Type: application/json' -d '{"name":"Onboarding"}'
# → {"id":"<templateId>", ...}
curl -u admin@acme.test:<password> -X POST http://localhost:8080/api/t/acme/workflows/<templateId>/versions
# → {"versionId":"<versionId>", ...} — an empty DRAFT, or a copy of the current published version

curl -u admin@acme.test:<password> -X PUT \
  http://localhost:8080/api/t/acme/workflows/<templateId>/versions/<versionId> \
  -H 'Content-Type: application/json' -d '{
    "stages": [{"key":"s1","name":"Onboarding","milestones":[
      {"key":"m1","name":"Kickoff","estimatedDurationDays":2,"dependsOnMilestoneKeys":[],
       "requirements":[{"kind":"MANUAL","label":"Sign up","mandatory":true}]}
    ]}]
  }'
# publish rule 5: every stage needs at least one milestone, or this 422s at publish, not here

curl -u admin@acme.test:<password> -X POST \
  http://localhost:8080/api/t/acme/workflows/<templateId>/versions/<versionId>/publish
# a case can only be created against a template with a PUBLISHED version

curl -u admin@acme.test:<password> -X POST http://localhost:8080/api/t/acme/cases \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"<customerId>","templateId":"<templateId>"}'
# → CaseView, pinned to the version that was current when this call ran
```

Basic auth works here the same way it does against `/api/platform/**` — `SecurityConfig` admits it
tenant-wide, not just for the platform-admin routes. The builder UI can author everything above
**except** a workflow attribute or a stage's `entryCondition` — see "Open at the close of
sub-project 2". A workflow with a conditional skip (an `entryCondition` referencing an
`ATTRIBUTE`-sourced condition) can only be authored this way, through the `PUT`, until that gap is
closed.

**Open at the close of sub-project 1**, verified against the running system — none of these is a
regression to hunt:

- **A narrow-scoped `user.manage` holder cannot create a user through the Users screen.** The create
  form sends only `{email, fullName}`, so `departmentId` is null, and a department-less user is
  outside a DEPARTMENT- or TEAM-scoped actor's own scope — `UserAdminService.create` refuses with a
  404 rather than making them a user its author could not then manage. Correct, and fails closed,
  but the screen offers no department field to succeed with and the 404 says nothing useful. None
  of the twelve seeded templates is affected: only `Administrator` holds `user.manage`, at `ALL`.
  A department picker on that form is the fix, with the options scoped to the actor. There is no
  user-edit screen either — `PUT /admin/users/{id}` exists but `lib/api/admin.ts` never calls it —
  so a department cannot be changed after creation from the UI at all.
- **Ownership foreign keys carry no tenant component, and one is observable.** `CustomerService`
  writes `ownerUserId`, `owningDepartmentId` and `owningTeamId` straight from the request with no
  existence or tenancy check. PostgreSQL evaluates referential integrity with row security
  bypassed, so a UUID belonging to **another tenant's** `app_user` satisfies the FK and answers
  200, while an invented UUID raises an FK violation and answers 500. That difference is a
  cross-tenant existence oracle, and it leaves a customer owned by a stranger. Fix shape: resolve
  all three ids through their repositories and let RLS do the tenancy work. `app_user.department_id
  REFERENCES department(id)` has the same shape — undiscoverable through the API under RLS, but
  `UserAdminService.create` and `update` now lean on `departmentId` for authorization decisions.
- **Deactivation ends the session but not the pending credentials.** `deactivate` revokes every
  refresh family and `AuthorizationService` zeroes authority for a non-ACTIVE user, but
  `PasswordResetService` consults `status` nowhere — so a DEACTIVATED account can still request and
  complete a reset — and outstanding `invitation` rows stay redeemable. No authority is gained
  (`LoginService` admits only ACTIVE, rotation refuses), but if "deactivation ends the account" is
  an invariant, invalidating its pending tokens is the missing half.
- **The tenant slug is unvalidated at provisioning.** `PlatformTenantController` carries no
  `@Valid` and `ProvisionRequest` no constraints. A slug that does not match
  `PathPrefixTenantResolver`'s `^[a-z0-9][a-z0-9-]{0,62}$` — `Acme`, `acme_corp` — creates a tenant
  that is permanently unreachable, since every request resolves no slug and answers 401, with no
  error at creation time. A duplicate slug is a raw 500 from the unique constraint: nothing maps
  `DataIntegrityViolationException`.
- **`DB_APP_PASSWORD` defaults to the committed literal `onboarding_app`.**
  `V2__app_role_and_tenant.sql` creates the login role with that password and `application.yml`
  defaults to it, with no guard — the same failure shape `JwtProperties` was just built to prevent
  for `JWT_SECRET`. Migrations are forward-only, so the role's password must be rotated
  operationally; the code half is to drop the default and refuse to start without the variable,
  exactly as `JWT_SECRET` now does.
- **Contact email drifts from `app_user`, and the two uniqueness rules disagree.**
  `CustomerContactService.update` rewrites `contact.email` without touching the linked
  `app_user.email`, so a corrected address leaves the portal login on the old one. And
  `customer_contact` is unique on `(customer_id, email)` **case-sensitively** while `app_user` is
  unique on `(tenant_id, lower(email))` — so two contacts differing only in case are accepted, and
  the second one's activation fails as an "invalid token".
- **Three write paths are still unaudited**, all in `authz`/`auth` rather than the domain modules:
  `RoleService.deleteRole`; re-enabling a disabled role, because `setEnabled` records only on the
  disable branch; and `PasswordResetService`, which records neither request nor completion.
  `RoleService.unassignRole` was the fourth and is now audited — `user.role_unassigned`, written
  inside the `ifPresent` so an idempotent no-op records nothing. Deliberately not audited:
  refresh-token rotation (every request would write a row, and reuse detection — the security event —
  *is* recorded) and login-throttle counters.
- **Deactivations recorded before 2026-08-16 are mislabelled.** `UserAdminService.deactivate` wrote
  the `user.created` action key with only its prose summary dissenting. Fixed, but `audit_event` is
  append-only, so historical rows cannot be corrected — anything querying `user.created` over that
  period is counting deactivations too.
- **Retiring a contact does not revoke portal access, and does not stop a new one being granted.**
  `update` sets `status = INACTIVE` on the contact only; the linked `app_user` stays `ACTIVE`, and
  `LoginService` reads the user, so a retired contact can still sign in. It can also still be
  invited and activated: neither `InvitationService.issue` nor `ActivationService.activateContact`
  reads `ContactStatus`, and nothing revokes outstanding invitations on retirement — so a retired
  contact holding a live seven-day token still creates an ACTIVE `PORTAL` user.
- **The 409 on a duplicate contact email is not in the OpenAPI document.** The behaviour is real and
  tested (`DuplicateContactEmailException`, unique `customer_contact_customer_id_email_key`), but
  springdoc advertises only 201 on create and 200 on update, so `generated.ts` has no 409 for a
  client to narrow on.

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
- **`approval.decide` is seeded to `Administrator` only.** The catalog allows it at any of
  ALL/DEPARTMENT/TEAM, but none of the other eleven templates holds it — deciding a stage-exit
  approval currently requires the tenant's widest role, unlike `milestone.force_approve`, which is
  ALL-only in the catalog itself and so cannot be any narrower by construction. Not a bug (absence
  of a grant is the denial, same as everywhere else), but worth a role review before sub-project 6
  builds SLA escalation on top of an approval nobody but the administrator can clear.
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

### Tests

```bash
cd backend && ./gradlew cleanTest test     # needs Docker running
cd frontend && npx vitest run
```

Backend and frontend unit were green at the close of sub-project 1 (2026-08-16) and again at the
close of sub-project 2 (2026-08-23), nothing skipped, no retries — `./gradlew cleanTest test`
reported `BUILD SUCCESSFUL` and every one of 71 result files had zero failures/errors; `npx vitest
run` reported 46 files, 334 tests, all passing. **Playwright's three new sub-project-2 specs were
written and confirmed to parse and register (`npx playwright test --list`), but not run live** —
this machine had another session's backend and frontend already bound to :8080/:3000 when
sub-project 2 closed, and `reuseExistingServer: !CI` means a run would have silently exercised
that session's server rather than this branch. Running them for real is the first thing to do once
those ports are free; do not read "written" as "verified" for those three. Counts are deliberately
not pinned in general — they move every time a task adds a test, and a number in this file that
drifts is a number that gets trusted. Read each suite's own summary line, and treat a *failure* as
the signal, never a count.

**Use `cleanTest test`, never a bare `test`** — Gradle marks an unchanged test task UP-TO-DATE and
prints `BUILD SUCCESSFUL` having executed nothing, which reads exactly like a green run.
`org.testcontainers` is pinned to 1.21.4 in `build.gradle.kts` because Boot 3.4.1's managed 1.20.4
cannot negotiate with current Docker Desktop API versions; do not revert it blindly.

`cd frontend && npx playwright test` is the end-to-end command: nine specs — login, activation,
refresh rotation and reuse, customers with contact create/edit/retire, permission gating and the
1024px fallback, the administration screens, accessibility in both themes at four widths, workflow
authoring through publish, a case lifecycle (branch skip, force-complete, completion at 100%), and
migration between versions.
It starts **both** applications itself, so nothing needs to be running first; if 8080 or
3000 is already bound it reuses what is there, which is wrong often enough that killing strays
first is worth it — **unless that port is held by another session on a shared machine**, in which
case killing it is someone else's work, not a stray. The backend goes through
`e2e/support/backend.mjs`, which tees its output to
`frontend/e2e/.artifacts/backend.log` — **that log is the only place an activation token exists**,
and Playwright gives a test no way to read a `webServer`'s stdout. Override the database the same
way the backend does: `DB_URL=… npx playwright test`. It provisions a tenant per spec file and
never truncates, so point it at a scratch database.

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

`docs/uispecs/` is a complete design system for the whole platform. **Frontend work implements it;
it does not invent a visual language.** This applies to every sub-project, not just the one that
first builds the shell.

| Read | For |
|---|---|
| `docs/uispecs/design/README.md` | Build order, and what to preserve |
| `docs/uispecs/design/02-tokens/` | Three-layer tokens (`tokens.md`, `tokens.css`, `tailwind.css`) |
| `docs/uispecs/design/04-components/component-specs.md` | All 17 component families, with states and ARIA |
| `docs/uispecs/design/05-review/ux-design-review.md` | 14 accessibility findings; 4 still open |
| `docs/uispecs/design/03-icons/` | 56 icons + JSON registry |
| `docs/uispecs/README.md` | Screen-by-screen layout, and product decisions worth preserving |
| `docs/uispecs/Onboarding Platform.html` | The working prototype — open it before building a screen |

**Order matters:** tokens, then icons, then components, then screens. Each step is far cheaper
before the next than retrofitted after it.

Four decisions erode quietly and must be held:

1. **Colour always means status, never decoration.** If you cannot name the state a colour
   represents, use a neutral.
2. **IBM Plex Mono for machine-generated values, Archivo for human text.** IDs, dates, counts and
   metrics are mono; anything a person wrote is not.
3. **Cards are flat.** Elevation is only for what genuinely floats — popovers, device frames.
4. **Colour is never the only signal.** Every status colour is paired with a word or an icon.

Two token constraints are **not re-derivable by eye — do not "fix" them**: `text-faint` and
`text-disabled` resolve to the same value *in both themes* because neither palette has room for a
third quiet grey clearing WCAG AA on the ground that binds it — the *darkest* in light (`#f2f0ec`),
the *lightest* in dark (`slate-700`); and `paper-600` is a graphics-only tier valid at 3:1 for 20px+
marks and 1px borders, never for text. Derivation in `05-review/ux-design-review.md` §1 and §1b.

Run `docs/uispecs/design/scripts/contrast.py` if you add any colour, and read its two tables
differently. `SHIPPED_PAIRS` resolves **token names** through `build_tokens.py` — 49 pairs in each
theme covering text, rail, accent, status pills, solids and borders, i.e. every pair a component
paints — so a FAIL there is live. `PAIRS` is 24 **literals copied from the prototype as handed
off**: it is the evidence behind review finding 1, its 7 failures are the historical record and
are meant to stay red by design, and it says nothing about the shipped tokens.

**Closed since sub-project 1: both themes' shipped tokens now measure clean.**
`report_shipped("light")` runs unconditionally alongside `report_shipped("dark")` on every
invocation (previously light was skipped, and nine of its 49 pairs failed, `border-default` worst
at 1.15–1.28 against a 3:1 floor) — as of this sub-project's close, running the script reports
**0 of 49 pairs failing in either theme**, confirmed by actually running it rather than reading
the script. Light is still the **default** theme (`ThemeProvider` sets `defaultTheme="system"`),
so this was the rendering most readers see first. Remember why a clean axe run was never proof of
this on its own: axe's default rule set evaluates `color-contrast` for TEXT and has no non-text
rule, and every one of the nine (now-fixed) failures was a non-text graphic (WCAG 1.4.11) — a
border or a status circle's fill — which is exactly what a text-only checker cannot see. Keep
adding a pair to `SHIPPED_PAIRS` for every new role; the day this script goes green by having
nothing left to check is the day it stops proving anything.

Dark theme is keyed on `[data-theme="dark"]`, **not a class** — configure `next-themes` with
`attribute="data-theme"` or the dark tokens never apply. Generated assets come from the scripts in
`design/scripts/`; never hand-edit them. `frontend/src/app/tokens.css` and `tailwind-theme.css` are
verbatim copies of `02-tokens/tokens.css` and `tailwind.css` — regenerate, then copy both across.

**Gaps the design does not cover, which implementations must supply:** empty states, loading
skeletons, error states, and any layout below 1440px. Task R1 measured the dark theme's **contrast**
for the 49 pairs listed above and fixed every failure (§1b). That is a claim about those pairs and
nothing else: it is **not** a visual review, which has still never happened, so composition, weight
and hierarchy are unproven. Add a pair to `SHIPPED_PAIRS` whenever you add a role — the first
version of that table was all-neutral and reported "0 of 17 fail" while the dark primary button sat
at 1.53:1.

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

---

## Where the guards live

- `backend/src/test/java/co/ara/onboarding/architecture/` — `RlsCoverageTest`,
  `AuthorizationCoverageTest`, `ModuleBoundaryTest`, `OpenApiDocumentTest`.
- `.../authz/DescriptorRegistryTest`, covering `DescriptorRegistry.validate()`.
- `.../security/` — the nine negative tests: `ChangedPermissionsTest`, `ConflictingGrantsTest`,
  `CrossTenantAccessTest`, `DelegationGuardTest`, `DirectApiAccessTest`, `InsufficientPermissionTest`,
  `InsufficientScopeTest`, `MultipleRolesTest`, `RoleLifecycleTest`. Sub-project 2's own negatives
  live in-package instead: `journey.CaseIsolationTest` (cross-tenant), `journey.WriteScopeTest`
  (a wider-scoped holder still refused inside an `OWNER_ONLY` stage), `journey.ForceCompleteTest`
  (self-approval refused; deciding a `FORCE_COMPLETE` through the stage-approval endpoint refused,
  not weakly gated), `journey.JourneyScopingTest` and `identity.TeamMembershipTest` (TEAM resolves
  through real team membership, not a column).

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

## Plan deviations

The plans are detailed and mostly correct, but they were written ahead of the code and contain
defects that only surface on a real run. When you find one, fix the code **and** amend the plan so
the finding carries forward, then say so in the commit body. Several tasks in sub-project 1 already
carry such amendments.

---

*Keep it dense — this file is loaded into every session. Add a line only when its absence would cost
a future session real time, and delete one when it stops being true.*
