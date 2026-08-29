# Foundation & Tenancy — Design Spec

**Date:** 2026-08-12
**Sub-project:** 1 of 10
**Status:** Approved

> **Design system superseded (2026-08-25).** Every `docs/uispecs/` reference below (build order,
> token system, component families, shell dimensions) describes the design system this
> sub-project's frontend was actually built against, now at `docs/uispecs_legacy/`. It is accurate
> history and is left as written. For any new frontend work — including the sub-project 1–2
> refactor — the current design system is
> `docs/uispecs_latest/design_handoff_onboarding_platform/`; see CLAUDE.md's "UI/UX" section.

---

## 1. Context

The PRD describes a multi-tenant onboarding platform spanning roughly ten independent subsystems. It is too large for a single specification, so the work is decomposed into sub-projects, each with its own spec, plan, and implementation cycle.

**Build constraints agreed before design:**

| Decision | Choice |
|---|---|
| Product type | Multi-tenant SaaS product, reusable across clients |
| Team | Solo (one developer, working with Claude) |
| Stack | Spring Boot backend + Next.js frontend; no Redis, no Kubernetes in v1 |
| Packaging | Dockerfiles for both apps plus Compose, delivered in sub-project 10 |
| Workflow builder | Execution engine in v1; no-code builder UI deferred to v2 |

### 1.1 Sub-project decomposition

| # | Sub-project | Depends on |
|---|---|---|
| 1 | **Foundation & Tenancy** *(this spec)* | — |
| 2 | Workflow Engine & Case Lifecycle | 1 |
| 3 | Tasks & Collaboration | 2 |
| 4 | Documents | 2 |
| 5 | Agreements | 4 |
| 6 | Notifications, SLA & Escalation | 2, 3 |
| 7 | Customer Portal | 2, 4, 5 |
| 8 | Dashboards & Real-time | 2–6 |
| 9 | Reporting & Analytics | 2–6 |
| 10 | Packaging & Deploy | all |

Sub-project 9 is the most droppable; 1–8 constitute a complete sellable product.

### 1.2 Why these concerns are in sub-project 1

Multi-tenancy, the audit substrate, and the central authorization layer are all brutal to retrofit. Tenant isolation defects discovered after launch are the class of defect that kills a SaaS product. All three land here, before any domain module depends on them.

---

## 2. Scope of this sub-project

**In scope:** tenancy model and isolation, identity and organizational structure, RBAC with record-level scope, authentication and sessions, invitation and activation, audit substrate, customer and contact management, application shell.

**Out of scope:** cases, workflows, milestones, tasks, documents, agreements, notification system, SLA and escalation, dashboard data, WebSockets, reporting, Dockerfiles, CI, MFA implementation, OIDC/SSO, Redis, subdomain tenancy, tenant self-service signup.

Tenants are created by a platform administrator. This is correct for a product sold and onboarded by hand.

---

## 3. Architecture

### 3.1 Repository layout

Single monorepo. Solo work across two applications means constant coordinated change; separate repositories would require two pull requests for every API contract change.

```
/backend    Spring Boot 3.x, Java 21, Gradle
/frontend   Next.js (App Router), TypeScript
/docs       PRD, QA, specs
/docker     Dockerfiles and compose (populated in sub-project 10)
```

### 3.2 Backend structure

A modular monolith organized by domain, not by layer:

```
platform/   cross-cutting infrastructure
tenancy/    tenant records, tenant context resolution
identity/   users, departments, teams, platform admins
authz/      permission catalog, roles, grants, enforcement
audit/      audit events, action registry
customer/   customer companies, contacts, invitations
```

Each module owns its entities, repositories, services, and controllers, and exposes a narrow public interface to other modules. Sub-projects 2–9 each add a module. If boundaries are sloppy now, the codebase becomes unmaintainable by sub-project 6.

Microservices are explicitly rejected: pure cost for a solo team. Module boundaries should nonetheless be clean enough that extraction is mechanical rather than archaeological.

### 3.3 Persistence

PostgreSQL with Flyway migrations, forward-only, checked into source control. JPA/Hibernate for ORM.

Row-Level Security policies are created in the same migration as the table they protect. A tenant-scoped table cannot exist without its policy.

### 3.4 Frontend

Next.js App Router, TypeScript in strict mode, Tailwind CSS, shadcn/ui, TanStack Query for server state.

API types are generated from the backend OpenAPI specification rather than hand-written, so a backend contract change surfaces as a frontend compile error rather than a runtime failure.

### 3.5 Testing infrastructure

JUnit 5 with Testcontainers against real PostgreSQL. H2 and other in-memory databases are unsuitable: RLS, scope predicates, and tenant isolation are database behaviours those engines do not implement.

---

## 4. Tenancy

### 4.1 Isolation strategy

Shared schema with a `tenant_id` discriminator on every tenant-owned table (QA Q3: multi-tenant SaaS).

Alternatives rejected: schema-per-tenant multiplies every migration by tenant count, an unsustainable operational load for a solo team; database-per-tenant additionally multiplies provisioning, backup, and connection pooling.

### 4.2 Two-layer enforcement

1. **Application layer** — a Hibernate filter applied automatically through a shared base entity and repository, so application code cannot omit the tenant predicate.
2. **Database layer** — a PostgreSQL RLS policy on every tenant-owned table, reading `current_setting('app.tenant_id')`. The setting is applied per transaction on the connection by a Spring interceptor.

A cross-tenant leak therefore requires two independent failures.

RLS is enabled **and forced**, so the policy applies even to the table owner.

### 4.3 Tenant resolution

Path prefix: `/t/{tenantSlug}`.

Subdomain resolution is the eventual target but requires wildcard DNS and a wildcard TLS certificate — real operational cost that buys nothing before there are paying tenants. Resolution sits behind a single `TenantResolver` interface, making the later switch an implementation swap rather than a refactor.

### 4.4 Tenant table

`tenant` — `id`, `slug` (unique), `name`, `status`, `settings` (JSONB), `created_at`, `updated_at`.

Tenant creation seeds the standard role templates described in §6.4.

### 4.5 Migration path

If an enterprise client later requires physical separation, extracting a single tenant to its own schema or database is workable *provided* `tenant_id` discipline holds from day one. This is a further reason the meta-test in §11.2 matters.

---

## 5. Identity

### 5.1 Model

A single tenant-scoped `app_user` table with a type discriminator (QA Q9 governs what portal users may do).

Rejected alternatives: separate tables for internal staff and customer contacts would duplicate authentication, sessions, MFA, password reset, and notification preferences — two copies of the most security-sensitive code in the system. A global identity with tenant membership solves cross-tenant identity, which is rare in this product, at the cost of a tenant-selection step at login and a more complex RLS story. It can be layered on later without reshaping the user record.

### 5.2 Tables

**`app_user`** — `id`, `tenant_id`, `email`, `password_hash`, `user_type` (`INTERNAL` | `PORTAL`), `status` (`INVITED` | `ACTIVE` | `SUSPENDED` | `DEACTIVATED`), `full_name`, `department_id` (nullable), `mfa_enabled`, `mfa_secret` (nullable, unused in v1), `last_login_at`, `created_at`, `updated_at`.

Email is unique **within a tenant**, not globally.

**`platform_admin`** — deliberately not tenant-scoped. Supports vendor-side administration and support. It is the only path permitted to cross tenant boundaries. Every action it performs writes an audit event with actor type `PLATFORM_ADMIN`.

**`department`** — `id`, `tenant_id`, `name`, `description`, `created_at`.

**`team`** — `id`, `tenant_id`, `department_id`, `name`, `description`, `created_at`.

**`team_member`** — `user_id`, `team_id`.

A user belongs to at most one department and any number of teams. Departments and teams exist in this sub-project because the `DEPARTMENT` and `TEAM` scopes of §6 are unresolvable without them.

### 5.3 `user_type` enforcement

`user_type = PORTAL` must be checked at the API boundary, not only in the UI. A portal user holding a valid token must never reach internal endpoints. This is covered by the negative tests in §10.3.

---

## 6. Authorization

### 6.1 Model summary

A fixed, code-defined permission catalog; tenant-owned roles seeded from templates; scope attached to each role-permission grant rather than to the role as a whole (QA Q4: record-level permissions).

### 6.2 Permission catalog

The catalog lives in code as a registry. Each entry declares:

- `key` — e.g. `customer.view`, `role.manage`, `document.approve`
- `category` — for grouping in the administration UI
- `description` — human-readable, shown to tenant administrators
- `allowedScopes` — the set of scopes valid for this permission

The catalog is mirrored into a `permission` reference table by a startup synchronization job. Code remains the source of truth; the database gains referential integrity and a listable catalog for the admin UI.

If a permission is removed from the code catalog while `role_grant` rows still reference it, the synchronization job logs each orphaned grant at `WARN` and the authorization layer ignores it — an unknown permission grants nothing. Orphans are not deleted automatically, so a mistaken removal can be reverted without data loss.

Tenants configure roles and grants. Tenants **cannot** create permissions, because a permission the application does not understand cannot be enforced.

### 6.3 Scopes

Four scopes, no more. Additional scope types are not to be introduced without a demonstrated requirement in the PRD or QA.

| Scope | Meaning |
|---|---|
| `ALL` | Every record of this type within the tenant |
| `DEPARTMENT` | Records owned by the user's department |
| `TEAM` | Records owned by any team the user belongs to |
| `ASSIGNED` | Records where the user holds a qualifying personal relationship |

**Scopes are sets, not a hierarchy.** Treating them as nested levels would be a defect: a user with `TEAM` scope who is personally assigned a record belonging to another team would be denied access to their own work.

A user's effective visible set for a permission is the **union** of the sets contributed by every grant of that permission across all of the user's enabled roles.

**Not every permission supports every scope.** `role.manage` and `tenant.settings.edit` are `ALL`-only by nature. Invalid combinations are rejected at write time with a validation error. A startup check verifies that every seeded role template uses only valid combinations.

### 6.4 `ASSIGNED` semantics

`ASSIGNED` means a **personal** relationship. A record assigned to a team the user belongs to is `TEAM` scope, never `ASSIGNED`. This distinction is deliberate and must not be blurred.

Qualifying relationships are drawn from a fixed vocabulary:

| Relationship | Meaning |
|---|---|
| `OWNER` | The user is the designated owner of the record |
| `ASSIGNEE` | The record is directly assigned to the user for action |
| `PARTICIPANT` | The user is a listed participant on the record or its parent case |
| `APPROVER` | The user is a pending or past approver on the record |
| `CREATOR` | The user created the record |

Each resource type declares which of these count toward `ASSIGNED`. Declarations are explicit; there is no implicit default.

`CREATOR` is available but is **not** a default. Having once created a record should not confer permanent access to it.

In this sub-project, `customer` declares `ASSIGNED` as `OWNER` only. Sub-project 2 extends the vocabulary's use to cases and milestones (QA Q15: the `OWNER` participant is the default milestone owner).

### 6.5 No deny grants

Absence of a grant is the denial. There are no negative or deny grants.

This makes conflicting role grants structurally impossible — grants only union upward, so there is no precedence order to specify or to get wrong. It also makes a user's effective permission set computable and explainable, which matters for the multi-role tests in §10.3.

### 6.6 Role tables

**`role`** — `id`, `tenant_id`, `name`, `description`, `is_system_template`, `enabled`, `created_at`, `updated_at`.

Tenant-owned data, seeded at tenant creation from the twelve internal roles in PRD §4: Sales Representative, Account Manager, Project Manager, Service Provider, Business Partner, Operations, Legal, Finance, Technical, Compliance, Support, Administrator.

Tenant administrators may rename, clone, disable, and modify grants. Deletion is blocked while users are assigned.

**`role_grant`** — `id`, `role_id`, `permission_key`, `scope`. Unique on `(role_id, permission_key)`: a role grants a given permission at exactly one scope.

**`user_role`** — `user_id`, `role_id`. Many-to-many.

### 6.7 Role lifecycle effects

A disabled role contributes nothing to the union, effective immediately.

Effective permissions are computed per request from the database and cached only for the duration of that request. No cross-request cache in v1. This costs one query per request and eliminates an entire class of stale-permission defects. A role change takes effect on the user's next request.

### 6.8 Central enforcement

Authorization is enforced centrally. Endpoints must not implement it independently. Three mechanisms, all fail-closed:

**1. Permission gate.** `@RequirePermission("customer.view")` on **service** methods, not controllers, so every call path is covered regardless of entry point. Answers: does this user hold this permission at any scope?

**2. Query constraint.** An `AuthorizationPredicate` builder produces a JPA Specification representing the user's effective visible set for a permission. The shared base repository's read methods **require** a predicate argument, so an endpoint cannot issue an unscoped list query.

**3. Single-record access.** Fetch-by-id runs through the same predicate. An out-of-scope record returns **404, not 403**, so direct API probing cannot confirm that a record exists.

### 6.9 Resource descriptors

Each resource type registers a `ResourceAuthorizationDescriptor` declaring how to resolve its `DEPARTMENT`, `TEAM`, and `ASSIGNED` predicates, and which relationships qualify for `ASSIGNED`.

A permission referencing a resource type with no registered descriptor causes **startup failure**. The application refuses to boot rather than silently permitting access.

When sub-project 2 adds cases and milestones, implementing a descriptor is a required step, enforced by the application refusing to start without it.

### 6.10 Static enforcement

An ArchUnit test asserts that every controller endpoint reaches a service method carrying an authorization annotation. A forgotten gate breaks the build.

---

## 7. Authentication & Sessions

### 7.1 Token model

Access token in memory, refresh token in an httpOnly cookie.

Rejected alternatives: a BFF pattern routing all traffic through the Next.js server adds a hop to the read-heavy, live-updating dashboards of sub-project 8 and complicates WebSocket authentication. Tokens in `localStorage` are rejected outright.

### 7.2 Access token

JWT, 15-minute lifetime, held only in a JavaScript variable on the frontend — never persisted to `localStorage`, `sessionStorage`, or a cookie.

Claims carry **identity only**: user id, tenant id, user type, token id, expiry. Permissions are deliberately **not** in the token; authority is computed server-side per request per §6.7. Embedding permissions would reintroduce the stale-permission problem that design avoids.

### 7.3 Refresh token

Opaque random value, stored only as a hash.

Cookie attributes: `httpOnly`, `Secure`, `SameSite=Strict`, path scoped to the refresh endpoint. Absolute lifetime 14 days. Rotated on every use.

**`refresh_token`** — `id`, `tenant_id`, `user_id`, `token_hash`, `family_id`, `issued_at`, `expires_at`, `used_at`, `revoked_at`, `ip`, `user_agent`.

`tenant_id` is denormalized from the owning user so that this table carries RLS like every other tenant-owned table, rather than relying on a join for isolation.

### 7.4 Reuse detection

Tokens are tracked in families. Presenting an already-used refresh token indicates theft: the entire family is revoked, the session terminated, and an audit event written.

This is the principal reason for choosing this token model — it provides breach *detection*, not merely breach resistance.

### 7.5 Passwords and throttling

Argon2id hashing.

Failed-login throttling with progressive delay and lockout after repeated failures, counted in PostgreSQL since Redis is out of scope.

### 7.6 Invitation and activation

Per QA Q12, customers are provisioned by invitation, never self-registration.

1. Internal staff create a `customer_contact` record.
2. Staff issue an invitation: a single-use, expiring, signed token delivered by email.
3. The recipient activates, sets a password, and a `PORTAL` `app_user` is created and linked via `customer_contact.user_id`.

Password reset reuses the same token machinery.

**`invitation`** — `id`, `tenant_id`, `customer_contact_id`, `token_hash`, `expires_at`, `accepted_at`, `revoked_at`, `created_by`, `created_at`.

### 7.7 Outbound email

A narrow `EmailSender` interface with an SMTP implementation and a development implementation that logs to the console.

Only invitation and password-reset messages are sent from this sub-project. The full notification system — templates, preferences, opt-out per QA Q10, multiple channels — is sub-project 6 and must not leak backwards into this one.

### 7.8 Deferred

MFA is scaffolded only: the fields exist and the login flow reserves a challenge step that currently always passes. OIDC and SSO are out entirely.

---

## 8. Audit

### 8.1 Semantic events

Audit events are semantic, not entity-level column diffs. Column-level history is technically complete and humanly useless, and this table also backs the user-facing Activity Timeline of sub-project 2 (PRD §11), which must read as "Sarah approved the NDA."

Services record events explicitly through an `AuditRecorder` API. Request context — IP address, user agent, request id, actor — is attached automatically from a request-scoped holder, keeping call sites short.

### 8.2 Action registry

The `action` value is drawn from a finite, code-defined registry, following the same pattern and rationale as the permission catalog. Each action declares whether it is `timeline_visible`.

### 8.3 Single store

The Activity Timeline of sub-project 2 reads from this same table, filtered by `timeline_visible`, rather than being a second event store. Two stores would require dual writes that inevitably drift, and the PRD explicitly describes the timeline as the official audit history.

### 8.4 Table

**`audit_event`** — `id`, `tenant_id`, `occurred_at`, `actor_type` (`USER` | `SYSTEM` | `PLATFORM_ADMIN`), `actor_user_id` (nullable), `action`, `resource_type`, `resource_id`, `summary`, `payload` (JSONB, before/after), `timeline_visible`, `ip`, `user_agent`, `request_id`.

Partitioned by month. QA Q11 sets audit retention at seven years, configurable per organization, which makes pruning a real recurring operation rather than a hypothetical one.

### 8.5 Append-only

Enforced at the database level: the application's PostgreSQL role holds no `UPDATE` or `DELETE` grant on `audit_event`. This is a permission, not a convention.

---

## 9. Customer Domain

### 9.1 Tables

**`customer`** — `id`, `tenant_id`, `legal_name`, `display_name`, `status`, `industry`, `country`, `external_ref`, `owner_user_id`, `owning_department_id`, `owning_team_id`, `created_by`, `created_at`, `updated_at`.

The three ownership columns are what make §6.3's scope predicates resolvable. Without them, `DEPARTMENT` and `TEAM` scope have nothing to match against.

**`customer_contact`** — `id`, `tenant_id`, `customer_id`, `user_id` (nullable), `full_name`, `email`, `title`, `phone`, `is_primary`, `status`, `created_at`, `updated_at`.

Per QA Q12, a contact record exists before any user account. `user_id` remains null until the invitation is accepted.

**Status values.** `customer.status` is `PROSPECT` | `ACTIVE` | `ON_HOLD` | `INACTIVE`. `customer_contact.status` is `ACTIVE` | `INACTIVE`; portal access state is not duplicated here — it is read from the linked `app_user.status`, so there is exactly one source of truth for whether a person can log in.

These sets are intentionally minimal. Onboarding *case* status is a separate concept owned by sub-project 2 and must not be conflated with customer status.

### 9.2 Resource descriptor

`customer` maps `DEPARTMENT` → `owning_department_id`, `TEAM` → `owning_team_id`, `OWNER` → `owner_user_id`. `ASSIGNED` resolves to `OWNER` only in this sub-project.

### 9.3 Operations

Create, edit, list with search, filter and pagination, detail view, contact management, invitation sending, and deactivation.

### 9.4 Deactivation, not deletion

QA Q11 requires right-to-erasure to be satisfied by pseudonymization rather than hard deletion. Hard deletes are therefore wrong throughout this product. Establishing the discipline here avoids retrofitting it across nine modules.

---

## 10. Frontend

### 10.0 The design system is an input, not a deliverable

`docs/uispecs/` contains a complete UI/UX design system for the platform — brand and logo suite, a three-layer token system with light and dark themes, 56 icons, specifications for 17 component families, a WCAG review, and a working HTML prototype of nine screens. **The frontend implements it; it does not invent a visual language.**

The token layer, icon set, component library and accessibility baseline are all in scope for this sub-project even though most of the nine prototype screens are not, because every later sub-project builds on them. Retrofitting a token system across nine modules is the same class of refactor that §10.5 avoids for i18n.

Build order, from `docs/uispecs/design/README.md`: tokens, then icons, then components, and read the accessibility review before any screen. Each step is markedly cheaper before the next than retrofitted after it.

Four design decisions are load-bearing and erode quietly:

1. **Colour always means status, never decoration.** A token exists for a *state*; if a colour cannot be named as a state, use a neutral.
2. **Mono for machine-generated values, Archivo for human text.** Applied consistently across all nine prototype screens; a real semantic distinction, not a stylistic one.
3. **Cards are flat.** Elevation is reserved for what genuinely floats.
4. **Colour is never the only signal.** Every status colour is paired with a word or an icon.

Two constraints in the token system are not re-derivable by eye and must not be "improved": `text-faint` and `text-disabled` deliberately resolve to the same value, because the palette has no room for a third quiet grey clearing AA on the darkest ground it lands on; and `paper-600` is a graphics-only tier valid at 3:1 for 20px+ marks, never for text.

Known gaps the frontend must fill, because the design does not cover them: **empty states**, **loading skeletons**, **error states**, and **any layout below 1440px**. The dark theme exists structurally but has never been reviewed at screen level.

### 10.1 Structure

App Router with two route groups: public (login, activation, password reset) and authenticated (everything else).

Shell per `docs/uispecs/design/04-components/component-specs.md`: a 244px `slate` rail (identical in both themes, which keeps navigation stable when the theme flips) and a 60px sticky header. Light and dark themes per PRD §16, keyed on `[data-theme]` as `tokens.css` defines — not on a class. WCAG AA contrast, verified rather than assumed. Responsive down from 1440px, which the design does not specify and the implementation must decide.

### 10.2 Auth handling

Access token in a React context, never persisted. An API client transparently refreshes on a 401 response and retries the original request once. Route protection applied at the layout level.

### 10.3 Permission-aware UI

A `/me` endpoint returns the user's effective permissions, feeding a `useHasPermission` hook that hides unavailable actions.

**This is convenience, not security.** The server is the sole authority. The negative tests in §11.3 cover direct API access that bypasses UI restrictions.

### 10.4 Pages

Login; invitation activation; password reset; an empty dashboard placeholder; customer list and detail; administration screens for users, roles, departments, and teams.

The prototype has **no authentication screens** — it opens already signed in — so login, activation and reset are the one part of the frontend with no visual reference. They must still be built from the token and component layers rather than acquiring a second visual language.

### 10.5 Internationalization

Strings routed through a translation layer from the outset, English only at launch. PRD §16 requires multi-language readiness, and retrofitting it across nine modules is a punishing refactor.

---

## 11. Testing

### 11.1 Approach

Implementation follows test-driven development. Security tests are written before the mechanisms they verify.

Integration tests run against real PostgreSQL via Testcontainers.

### 11.2 Automated structural guards

These matter more than any coverage percentage, because they keep working for every table and module added in sub-projects 2 through 9.

- **RLS meta-test** — queries `pg_policies` and `pg_class` and asserts that every tenant-scoped table has RLS enabled and forced, with a policy present. Converts "remember to add the policy" from discipline into a failing build.

  The test operates on a deny-by-default basis: every table in the application schema must have RLS, *except* those on an explicit allowlist held in the test itself. The allowlist starts as `tenant`, `platform_admin`, `permission`, and the Flyway history table. Adding a table to it is a deliberate, reviewable act — which is the point, since a table silently omitted from tenant scoping is exactly the defect this guard exists to catch.
- **ArchUnit rules** — every controller path reaches an authorization-annotated service method; domain modules interact only through declared public interfaces.
- **Startup checks** — every permission's resource type has a registered descriptor; every seeded role template uses only valid permission/scope combinations.

### 11.3 Required negative security tests

All of the following are mandatory and gate completion:

1. **Cross-tenant access** — a user of tenant A cannot read, list, or modify any record of tenant B, by any endpoint or id.
2. **Insufficient permission** — a user lacking a permission is refused, at the service layer, not merely hidden in the UI.
3. **Correct permission, insufficient scope** — a user holding `customer.view` at `TEAM` scope cannot read a customer owned by another team, and receives 404 rather than 403.
4. **Multiple roles** — a user holding several roles receives the union of their visible sets, including a record reachable through only one of the roles.
5. **Conflicting role grants** — the same permission granted at different scopes across roles resolves to the union, deterministically.
6. **Disabled and deleted roles** — a disabled role contributes nothing immediately; deletion is blocked while users are assigned.
7. **Changed role permissions** — a grant revoked mid-session takes effect on the user's next request, with no stale cached authority.
8. **Direct API access bypassing UI** — a `PORTAL` user with a valid token cannot reach internal endpoints; hidden UI actions are refused when invoked directly against the API.

### 11.4 Frontend testing

Playwright end-to-end coverage for flows where failure is expensive: login, invitation activation, token refresh, and refresh-token reuse detection. Broad component coverage is not a goal.

---

## 12. Definition of Done

1. A platform administrator creates a tenant, and it is seeded with the twelve role templates from PRD §4.
2. A tenant administrator logs in, creates departments, teams, and users, assigns roles, and edits role grants — with invalid permission/scope combinations rejected.
3. A user creates a customer, adds contacts, and sends an invitation.
4. A contact activates that invitation and logs in as a `PORTAL` user.
5. Every action above appears in the audit log with correct actor and request context.
6. All eight negative security tests pass, together with the RLS meta-test, the ArchUnit rules, and the startup checks.
7. The interface is built on the `docs/uispecs/` token, icon and component layers rather than an ad-hoc equivalent, and an automated accessibility pass over login, the customer list and the role editor is clean of serious and critical violations **in both themes**.

Item 6 is the gate. If the security tests are not green, this sub-project is not done, regardless of how much of the interface works.

Item 7 is a second, softer gate, and it is here because it is the cheapest it will ever be. Every subsequent sub-project renders inside this shell and reuses these components; a divergent token layer or an inaccessible table pattern established now is inherited nine times over.

---

## 13. Traceability to QA Decisions

| QA | Decision | Where addressed |
|---|---|---|
| Q3 | Multi-tenant SaaS | §4 |
| Q4 | Record-level permissions | §6 |
| Q9 | Customer write access | §5.3, portal screens in sub-project 7 |
| Q10 | Notification opt-out | Deferred to sub-project 6; §7.7 |
| Q11 | 7-year audit retention, pseudonymization | §8.4, §9.4 |
| Q12 | Invitation-based provisioning | §7.6, §9.1 |
| Q15 | Assignment resolution | §6.4, extended in sub-project 2 |
| Q16 | Per-role dashboards | Deferred to sub-project 8 |

Q1, Q2, Q5, Q6, Q7, Q8, Q13, and Q14 concern the workflow engine, agreements, and SLA, and are addressed in sub-projects 2, 5, and 6.
