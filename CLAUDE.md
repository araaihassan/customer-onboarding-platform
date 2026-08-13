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
- **Permissions are never embedded in tokens.** Authority is resolved server-side per request.
- **Out-of-scope records return 404, never 403.** The UI must not reintroduce the distinction the
  404 exists to hide.
- **Absence of a grant is the denial.** There are no deny grants anywhere in authorization.
- **UUIDv7 primary keys** via `co.ara.onboarding.platform.Uuid7.generate()`. Values that must be
  unpredictable rather than merely unique (refresh tokens, invitation tokens) use `SecureRandom`
  directly and never a UUID.
- **All timestamps** are `timestamptz`, stored in UTC.

---

## Working conventions

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
- Run `cd backend && ./gradlew test` before committing. On PowerShell use `.\gradlew.bat`.

## Plan deviations

The plans are detailed and mostly correct, but they were written ahead of the code and contain
defects that only surface on a real run. When you find one, fix the code **and** amend the plan so
the finding carries forward, then say so in the commit body. Several tasks in sub-project 1 already
carry such amendments.

---

*Sub-project 1 Task 29 extends this file with the operational detail (local setup, running both
applications, environment variables). Keep it dense — this file is loaded into every session.*
