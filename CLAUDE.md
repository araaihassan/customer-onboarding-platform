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
  APP_PLATFORM_ADMIN_EMAIL=ops@example.com APP_PLATFORM_ADMIN_PASSWORD=<pick-one> ./gradlew bootRun
```

Both platform-admin variables are blank by default and `PlatformAdminBootstrap` does nothing without
them — but `/api/platform/**` is HTTP Basic behind `hasRole("PLATFORM_ADMIN")`, so without one no
tenant can ever be created. It is idempotent: an existing address is left untouched, never re-hashed.
Other variables worth knowing: `JWT_SECRET` (dev default is a placeholder, min 32 bytes).

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

### Tests

```bash
cd backend && ./gradlew cleanTest test     # needs Docker running
cd frontend && npx vitest run
```

After Task R1 these were 154 backend tests over 42 classes and 122 frontend tests over 16 files, all
green.

**Use `cleanTest test`, never a bare `test`** — Gradle marks an unchanged test task UP-TO-DATE and
prints `BUILD SUCCESSFUL` having executed nothing, which reads exactly like a green run.
`org.testcontainers` is pinned to 1.21.4 in `build.gradle.kts` because Boot 3.4.1's managed 1.20.4
cannot negotiate with current Docker Desktop API versions; do not revert it blindly.

`cd frontend && npx playwright test` is the end-to-end command — **Playwright is not installed and
no specs exist yet; they arrive with Task 28. Never cite it as passing.**

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
| `docs/uispecs/design/05-review/ux-design-review.md` | 12 accessibility findings; 4 still open |
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
`text-disabled` resolve to the same value because the palette has no room for a third quiet grey
clearing WCAG AA on the darkest ground it lands on; and `paper-600` is a graphics-only tier valid
at 3:1 for 20px+ marks, never for text. Derivation in `05-review/ux-design-review.md` §1. Run
`docs/uispecs/design/scripts/contrast.py` if you add any text colour.

Dark theme is keyed on `[data-theme="dark"]`, **not a class** — configure `next-themes` with
`attribute="data-theme"` or the dark tokens never apply. Generated assets come from the scripts in
`design/scripts/`; never hand-edit them.

**Gaps the design does not cover, which implementations must supply:** empty states, loading
skeletons, error states, and any layout below 1440px. The dark theme exists structurally but has
never been reviewed at screen level.

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
- **Reads of tenant business data go through `AuthorizedQuery`.** A repository finder called
  directly skips the scope predicate — a silent, total bypass rather than a visible error.
- **Permissions are never embedded in tokens and never cached across requests.** Authority is
  resolved server-side per request, so a revoked grant takes effect on the next call rather than
  when a token happens to expire.
- **Every resource type registers a `ResourceAuthorizationDescriptor`.** `DescriptorRegistry.validate()`
  refuses to start the application otherwise — an unregistered type would reach scope resolution with
  no predicate to apply. Descriptors must fail closed: no department, no teams ⇒ `cb.disjunction()`.
- **`ASSIGNED` means a personal relationship** (`RelationshipType`); access mediated by a team the
  user belongs to is `TEAM`. Conflating them silently widens `ASSIGNED` to everything the user's
  teams can reach.
- **Out-of-scope records return 404, never 403.** The UI must not reintroduce the distinction the
  404 exists to hide.
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
  deliberately for each new action rather than copying a neighbour.

## Plan deviations

The plans are detailed and mostly correct, but they were written ahead of the code and contain
defects that only surface on a real run. When you find one, fix the code **and** amend the plan so
the finding carries forward, then say so in the commit body. Several tasks in sub-project 1 already
carry such amendments.

---

*Keep it dense — this file is loaded into every session. Add a line only when its absence would cost
a future session real time, and delete one when it stops being true.*
