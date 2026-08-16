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
roles, grants, enforcement), `audit/`, `customer/` (customers, contacts). `provisioning/` and
`scoping/` exist only because they orchestrate two or more of the others.

**Sub-project 1 delivered:** tenancy with RLS, identity, RBAC with record-level scope and twelve
seeded role templates, JWT auth with refresh rotation and reuse detection, invitation / activation /
password reset, login throttling, the audit substrate, customer and contact management, and on the
frontend the token layer, i18n, API client and public auth pages.

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
(sub-project 2).

Also operational: `audit_event` is partitioned by month and `V5` creates only `2026_08`, `2026_09`
and a DEFAULT partition. The job that rolls partitions forward arrives in sub-project 6.

**Open at the close of sub-project 1**, verified against the running system — none of these is a
regression to hunt:

- **`user.manage` at DEPARTMENT or TEAM scope is still a privilege-escalation path.** Its holder
  can no longer reach users outside their scope — every target is resolved through
  `AuthorizedQuery` — but nothing checks the *role being granted*, so they can assign a role wider
  than their own to anyone they legitimately manage, themselves included, and `RoleEditor` shows
  them the ids. The guard (require `role.manage` to assign, or refuse a role whose grants exceed
  the caller's) is a policy decision about delegation and belongs to sub-project 2's tenant
  administration. **Until it exists, `user.manage` at any scope is equivalent to the widest role in
  the tenant. Grant it accordingly.**
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
- **Retiring a contact does not revoke portal access.** `update` sets `status = INACTIVE` on the
  contact only; the linked `app_user` stays `ACTIVE`, and `LoginService` reads the user. A retired
  contact can still sign in to the portal.
- **The 409 on a duplicate contact email is not in the OpenAPI document.** The behaviour is real and
  tested (`DuplicateContactEmailException`, unique `customer_contact_customer_id_email_key`), but
  springdoc advertises only 201 on create and 200 on update, so `generated.ts` has no 409 for a
  client to narrow on.

### Tests

```bash
cd backend && ./gradlew cleanTest test     # needs Docker running
cd frontend && npx vitest run
```

All three suites — backend, frontend unit, Playwright — were green at the close of sub-project 1
(2026-08-16), with nothing skipped and no retries. Counts are deliberately not pinned here: they
move every time sub-project 2 adds a test, and a number in this file that drifts is a number that
gets trusted. Read the suite's own summary line, and treat a *failure* as the signal, never a count.

**Use `cleanTest test`, never a bare `test`** — Gradle marks an unchanged test task UP-TO-DATE and
prints `BUILD SUCCESSFUL` having executed nothing, which reads exactly like a green run.
`org.testcontainers` is pinned to 1.21.4 in `build.gradle.kts` because Boot 3.4.1's managed 1.20.4
cannot negotiate with current Docker Desktop API versions; do not revert it blindly.

`cd frontend && npx playwright test` is the end-to-end command: six specs — login, activation,
refresh rotation and reuse, customers with contact create/edit/retire, permission gating and the
1024px fallback, the administration screens, and accessibility in both themes at four widths.
It starts **both** applications itself, so nothing needs to be running first; if 8080 or
3000 is already bound it reuses what is there, which is wrong often enough that killing strays
first is worth it. The backend goes through `e2e/support/backend.mjs`, which tees its output to
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
differently. `SHIPPED_PAIRS` resolves **token names** through `build_tokens.py` — 49 pairs in the
**dark** theme covering text, rail, accent, status pills, solids and borders, i.e. every pair a
component paints — so a FAIL there is live. `PAIRS` is 24 **literals copied from the prototype as
handed off**: it is the evidence behind review finding 1, its 7 failures are the historical record
and are meant to stay red, and it says nothing about the light tokens shipping today. **The shipped
light tokens are measured by nothing** — `report_shipped("light")` exists and is one line away, but
turning it on surfaces the known, deferred `border-default` at 1.28:1.

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
  sub-project 1 were this exact shape. `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`
  covers `customer..`, `identity..` and `auth..`; **add your package to it in the same commit that
  adds your service.**
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

---

## Where the guards live

- `backend/src/test/java/co/ara/onboarding/architecture/` — `RlsCoverageTest`,
  `AuthorizationCoverageTest`, `ModuleBoundaryTest`, `OpenApiDocumentTest`.
- `.../authz/DescriptorRegistryTest`, covering `DescriptorRegistry.validate()`.
- `.../security/` — the eight negative tests: `ChangedPermissionsTest`, `ConflictingGrantsTest`,
  `CrossTenantAccessTest`, `DirectApiAccessTest`, `InsufficientPermissionTest`,
  `InsufficientScopeTest`, `MultipleRolesTest`, `RoleLifecycleTest`.

**These are not to be weakened to make a change pass.** They exist precisely to fail when something
is missed. An allowlist entry or an exclusion added to green a build defeats the isolation design,
and its failure mode — silent cross-tenant exposure — is the one this product cannot survive.

---

## Working conventions

- **One module per domain**, owning its own entities, repositories, services and controllers and
  exposing a narrow interface. Sub-projects 2–9 each add one; nothing reaches into another module's
  internals.
- **Every user-facing string goes through `t()`** (`frontend/src/lib/i18n`). A missing key renders
  as the key itself, so gaps are visible rather than silent.
- **TDD.** Write the failing test first; security tests before the mechanism they verify. A
  structural guard you have never seen fail is a guard you cannot trust — prove new ones red.
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
- **Retirement gets its own action**, not a flag on an update — `contact.deactivated` is recorded
  when an update *transitions* status into INACTIVE, never when it merely arrives INACTIVE. Because
  business records are never deleted, that event is the only record the retirement happened, and it
  must stay distinguishable from a phone-number correction. Repeat the shape for anything a later
  sub-project retires.

## Plan deviations

The plans are detailed and mostly correct, but they were written ahead of the code and contain
defects that only surface on a real run. When you find one, fix the code **and** amend the plan so
the finding carries forward, then say so in the commit body. Several tasks in sub-project 1 already
carry such amendments.

---

*Keep it dense — this file is loaded into every session. Add a line only when its absence would cost
a future session real time, and delete one when it stops being true.*
