# Tasks & Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build tasks inside a running journey — instantiated from a workflow requirement of kind `TASK` or created ad-hoc — with checklists, internal comments on tasks and journeys, a cross-case "My work" board, and a Tasks tab in the case workspace.

**Architecture:** One new backend module, `co.ara.onboarding.task`, on sub-project 2's runtime. It depends on `journey`; the two directions that would push back the other way (reopening a milestone must reopen its tasks; the roadmap wants task counts) go through two ports `journey` declares and `task` implements — the `CustomerDirectory` / `UserSessionRevoker` idiom. Task completion adds **no** new caller of `CaseEngine.reconcile`: it routes through the existing gated `RequirementService.satisfy`. Descriptors for the two new resource types live in `scoping/`, as the existing six do.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Playwright, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-29-tasks-and-collaboration-design.md`

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — the current bundle. Do **not** read `docs/uispecs_legacy/` for tokens, copy, layout or component behaviour. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 3), per CLAUDE.md.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is loaded into every session and is the authority for everything sub-projects 1 and 2 established; this section carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. One new module: `co.ara.onboarding.task`. Descriptors go in `co.ara.onboarding.scoping`. Nothing else moves.
- **`journey` must never import `task`.** It declares `journey.TaskDirectory` and `journey.TaskLifecycle`; `task` implements both. Enforced by a **named** `ModuleBoundaryTest` rule, `noJourneyDependencyOnTask` — not by the cycle rule, which a one-way import would still pass.
- **Three new tables**, all tenant-owned: `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `SELECT enable_tenant_rls('<table>')` in the same migration, and `GRANT SELECT, INSERT, UPDATE ON <table> TO onboarding_app` — never `DELETE`. `RlsCoverageTest` is deny-by-default over the live schema; **its allowlist stays at four entries.**
- **The feature migration's actual filename is decided at dispatch time, not fixed here as `V14`.** Phase 1 lands first and its own migrations (Tasks 5 and 10) take whichever numbers are next when they run, so by the time Phase 2's Task 11 runs, `V14` may already be taken. **Before writing any migration in this plan, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>` — never assume a number from this document's text.** Forward-only — never edit a committed migration, not even temporarily.
- **UUIDv7 keys** via `co.ara.onboarding.platform.Uuid7.generate()`. All timestamps `timestamptz` in UTC; `due_date` is a bare `date`.
- **Every public `*Service` and `*Engine` method carries `@RequirePermission`.**
- **Every read of tenant business data goes through `AuthorizedQuery`**, and so does **every id a write path takes from a URL or a request body**, before it writes. **No new `AuthorizedQuery` exclusion is created in this sub-project.** The codebase has exactly one carve-out (`AuditQuery.findForResource`) and CLAUDE.md requires a second to carry its own explicit argument. This design was chosen so none is needed — if you find yourself wanting one, the design is wrong, not the rule.
- **`task..` is added to `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` in the same commit that adds the services**, never afterwards. There is no separate `co.ara.onboarding.comment` package — `Comment`, `CommentService` and everything comment-related live inside `co.ara.onboarding.task` per §3.1 of the spec, so `task..` alone covers both.
- **Out-of-scope records return 404, never 403.** `AuthorizedQuery` throws `NoSuchElementException`, which maps to 404.
- **A `PUT` is a full replace,** so its view type must carry every field its request type accepts. Adding a field to an `Update*Request` without adding it to the matching `*View` makes every client silently erase it.
- **Permission keys** are declared in `PermissionKeys`, catalogued in `PermissionCatalog`, and referenced as constants — never as string literals.
- **Audit: record the cause before the calls that record its effects.** `AuditRecorder` stamps `occurred_at` from the clock, so call order is timeline order. `journey.CauseBeforeEffectTest` guards this; `task` adds its own cases.
- **Progress is derived from requirements alone.** No checklist item, and no ad-hoc task, ever enters a progress numerator or denominator.
- **`ASSIGNED` means a personal relationship** — `assignee_id = actor`, never team-mediated.
- **TDD.** Failing test first; security tests before the mechanism they verify. A new structural guard must be **seen red** before the code it protects exists.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper instead, or `UnexpectedRollbackException` masks the exception under test.
- **Fixture create-helpers must run inside `runAs`** — the tables they write are RLS-protected.
- **Backend tests need Docker running.** Use `./gradlew cleanTest test`, never a bare `test` — Gradle marks an unchanged test task UP-TO-DATE and prints `BUILD SUCCESSFUL` having executed nothing. On PowerShell use `.\gradlew.bat`.
- **The database on this machine is on port 5434**, not 5432; 5432 is a different Postgres with different credentials. Run the backend with `DB_URL=jdbc:postgresql://localhost:5434/onboarding`.
- **Conventional Commits.** Explain *why* in the body, especially when deviating from this plan. When you find a plan defect, fix the code **and** amend the plan, and say so in the commit body.

---

## File structure

**Phase 1 (backlog) modifies existing files only** — no new modules. New migrations as noted per task.

**Phase 2 (backend feature) creates:**

```
backend/src/main/resources/db/migration/V14__task.sql

backend/src/main/java/co/ara/onboarding/journey/
  TaskDirectory.java          port: summaryFor(Collection<UUID>) -> Map<UUID, TaskSummary>
  TaskSummary.java            record(int open, int total)
  TaskLifecycle.java          port: instantiateForCase(UUID), reopenForMilestone(UUID)

backend/src/main/java/co/ara/onboarding/task/
  Task.java  TaskRepository.java  TaskStatus.java  TaskPriority.java
  TaskChecklistItem.java  TaskChecklistItemRepository.java
  Comment.java  CommentRepository.java  CommentResourceType.java
  TaskService.java            create, read, update, status transitions
  TaskInstantiation.java      requirement kind=TASK -> Task, called at case creation
  TaskDirectoryAdapter.java   implements journey.TaskDirectory
  TaskLifecycleAdapter.java   implements journey.TaskLifecycle
  ChecklistService.java
  CommentService.java
  TaskController.java  CommentController.java
  Task*Request/View records, Comment*Request/View records
  TaskExceptionHandler.java   @RestControllerAdvice, in THIS module (platform must never name a domain type)

backend/src/main/java/co/ara/onboarding/scoping/
  TaskDescriptor.java  CommentDescriptor.java
```

**Phase 3 (frontend) creates:**

```
frontend/src/lib/api/tasks.ts               hooks: useCaseTasks, useMyWork, useTaskMutations
frontend/src/lib/api/comments.ts
frontend/src/components/journey/TasksTab.tsx
frontend/src/components/task/TaskCard.tsx  TaskDetail.tsx  ChecklistEditor.tsx
frontend/src/components/task/WorkBoard.tsx  WorkColumn.tsx
frontend/src/components/comment/CommentThread.tsx  CommentComposer.tsx
frontend/src/app/(app)/t/[slug]/work/page.tsx
```

---

# Phase 0 — Verify the ground before building on it

Nothing in this phase is feature work. It exists because **the nine Playwright specs have never been run against the frontend visual refactor**, and every other guard the project has is structural or textual — none renders CSS in a browser. A sidebar that compiled to nothing passed per-task review, a fix loop and a whole-branch review before a human saw it on screen. Adding a module on top of that compounds unverified on unverified.

### Task 1: Run the nine Playwright specs and triage every failure

**Files:**
- Modify: only what the triage requires — record each fix in its own commit
- Modify: `CLAUDE.md` (record the outcome)

**Interfaces:**
- Consumes: nothing
- Produces: a known-good or known-bad baseline that every later task depends on

- [ ] **Step 1: Point the harness at a scratch database, never the working one**

The harness provisions a tenant per spec file and **never truncates**, so it must not run against the database holding real work. `onboarding-db-verify` is already running on port **5433** for exactly this.

```bash
docker ps --format '{{.Names}} {{.Ports}}'
# expect onboarding-db-verify ... 0.0.0.0:5433->5432/tcp
```

If it does not exist:

```bash
docker run -d --name onboarding-db-verify -p 5433:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=onboarding postgres:16-alpine
```

- [ ] **Step 2: Free ports 3000 and 8080 first**

Playwright starts both applications itself and **reuses whatever is already bound**, which silently tests the wrong build. Kill strays first — unless the port is held by another person's session on a shared machine, in which case that is their work, not a stray.

```powershell
Get-NetTCPConnection -LocalPort 3000,8080 -State Listen -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

- [ ] **Step 3: Run the suite**

```bash
cd frontend
DB_URL=jdbc:postgresql://localhost:5433/onboarding npx playwright test
```

Expected: nine specs — login, activation, refresh rotation and reuse, customers with contact create/edit/retire, permission gating and the 1024px fallback, the administration screens, accessibility in both themes at four widths, workflow authoring through publish, case lifecycle, migration.

- [ ] **Step 4: Expect the accessibility spec to need updating, and confirm why before changing it**

`e2e/accessibility.spec.ts` was rewritten light-only during the refactor (dark theme was removed deliberately — the current design bundle defines no dark palette). If it still references `THEMES`, `useTheme()` or `assertTheme()`, that is a stale spec, not a product bug.

**Do not weaken any assertion to make a spec pass.** A failing guard is the signal. If a spec fails because the product changed deliberately, update the spec and say so in the commit body; if it fails because the product is wrong, fix the product.

- [ ] **Step 5: Triage each failure into one of three buckets, and commit each fix separately**

1. **Stale spec** — the product changed deliberately (e.g. dark theme removal). Update the spec.
2. **Real product bug** — fix the product, with a unit or component test that fails first.
3. **Flake** — do not paper over it. Read `frontend/e2e/.artifacts/backend.log`; it is the only place an activation token exists, since Playwright gives a test no way to read a `webServer`'s stdout.

- [ ] **Step 6: Record the outcome in CLAUDE.md**

Replace the line claiming the e2e command exists with what actually happened — the number of specs that ran, what failed, and what was fixed. Counts of individual tests are deliberately not pinned anywhere in that file; a number that drifts is a number that gets trusted.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test(e2e): run the nine Playwright specs against the visual refactor

First live run since the refactor. <N> specs passed, <M> needed changes:
<one line each, with which of the three buckets it fell into>.

No assertion was weakened to make a spec pass."
```

---

# Phase 1 — The CLAUDE.md open-item backlog

Ten items carried since sub-projects 1 and 2, none of them in sub-project 3's own path. They are here because they are real defects in shipped code, two of them cross-tenant, and because a backlog that is never scheduled is a backlog that grows. **This phase is not feature work and must not be folded into a task that is.**

Ordered by severity: the cross-tenant oracle first, the security-adjacent ones next, the ergonomic ones last.

### Task 2: Close the ownership-FK cross-tenant existence oracle

The highest-severity open item. `CustomerService` writes `ownerUserId`, `owningDepartmentId` and `owningTeamId` straight from the request with no existence or tenancy check. PostgreSQL evaluates referential integrity **with row security bypassed**, so a UUID belonging to *another tenant's* `app_user` satisfies the FK and answers 200, while an invented UUID raises an FK violation and answers 500. That difference is a cross-tenant existence oracle, and it leaves a customer owned by a stranger.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/customer/CustomerService.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/CrossTenantAccessTest.java`

**Interfaces:**
- Consumes: `AuthorizedQuery`, `AppUserRepository`, `DepartmentRepository`, `TeamRepository`
- Produces: nothing new; behaviour change only

- [ ] **Step 1: Write the failing test**

```java
@Test
void anotherTenantsUserIdCannotBecomeACustomerOwner() {
    UUID tenantA = fixture.createTenant("own-a");
    UUID tenantB = fixture.createTenant("own-b");
    var strangerId = new UUID[1];
    fixture.runAs(tenantB, () -> strangerId[0] = fixture.createUser(tenantB, "stranger@b.test"));

    // A real id, but in another tenant. The FK is satisfied because RLS is
    // bypassed for referential integrity, so before the fix this answered 200.
    // create() never takes an ownerUserId (the actor becomes the owner), but
    // DOES take owningDepartmentId straight from the request -- that is the
    // field under attack here.
    assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
            customers.create(new CreateCustomerRequest(
                    "Acme", null, null, null, null, strangerDepartmentId[0], null))))
            .isInstanceOf(NoSuchElementException.class);
}

@Test
void anInventedDepartmentIdIsA404NotA500() {
    UUID tenant = fixture.createTenant("own-invented");
    assertThatThrownBy(() -> fixture.runAs(tenant, () ->
            customers.create(new CreateCustomerRequest(
                    "Acme", null, null, null, null, Uuid7.generate(), null))))
            .isInstanceOf(NoSuchElementException.class);
}

/**
 * update() is the sharper case: unlike create(), it takes ownerUserId
 * straight from the request (CustomerService.java:131), so a real user id
 * belonging to another tenant satisfies the FK and hands ownership of this
 * tenant's customer to a stranger who cannot even see it.
 */
@Test
void updateCannotHandOwnershipToAnotherTenantsUser() {
    UUID customerId = createOwnCustomer(tenantA);
    assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
            customers.update(customerId, new UpdateCustomerRequest(
                    "Acme", null, null, null, null, strangerUserId[0], null, null))))
            .isInstanceOf(NoSuchElementException.class);
}
```

**`CreateCustomerRequest` has no `ownerUserId` field at all** — `create()` always sets the owner to the creating actor (`CustomerService.java:74`, "the creator becomes the owner by default"). The exploitable fields at creation are `owningDepartmentId` and `owningTeamId` only. `update()` is where `ownerUserId` is itself attacker-controlled (`CustomerService.java:131`), which is the more serious half of this bug — it lets an existing customer's ownership be reassigned to a stranger, not just a new one created under one. Read `CustomerService.java` in full before writing these tests; do not assume the request shapes above are exhaustive without checking the real records.

The `anInventedDepartmentIdIsA404NotA500` test is the half that closes the oracle: **both** the foreign-tenant case and the invented-id case must produce the *same* outcome. A 404 for one and a 500 for the other is the leak, even after the cross-tenant case alone appears fixed.

- [ ] **Step 2: Run to verify all three fail**

```bash
cd backend && ./gradlew cleanTest test --tests "co.ara.onboarding.security.CrossTenantAccessTest"
```

Expected: FAIL — the cross-tenant cases return a customer (200), the invented-id case throws `DataIntegrityViolationException` (500).

- [ ] **Step 3: Resolve every foreign id through its repository and let RLS do the tenancy work**

In `CustomerService`, replace the direct assignments in both `create` and `update` with resolution through `AuthorizedQuery`. RLS scopes each repository to the bound tenant, so a foreign id simply is not found:

```java
private UUID resolveDepartment(UUID departmentId) {
    if (departmentId == null) return null;
    return authorizedQuery.getById(departments, Department.class,
            PermissionKeys.DEPARTMENT_VIEW, departmentId).getId();
}

private UUID resolveTeam(UUID teamId) {
    if (teamId == null) return null;
    return authorizedQuery.getById(teams, Team.class, PermissionKeys.TEAM_VIEW, teamId).getId();
}

private UUID resolveOwner(UUID ownerUserId) {
    if (ownerUserId == null) return null;
    return authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, ownerUserId).getId();
}
```

`resolveDepartment`/`resolveTeam` are called from **both** `create` and `update`; `resolveOwner` only from `update`, since `create` never reads an owner id from the request. `AuthorizedQuery.getById` throws `NoSuchElementException` for both the foreign id and the invented one, which is exactly the collapse the oracle needs. Confirm the exact repository and permission-key names (`AppUserRepository`/`USER_VIEW` or equivalent) against the real code before using them verbatim — they are not re-verified here.

- [ ] **Step 4: Run to verify both pass, then the whole suite**

```bash
./gradlew cleanTest test
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(customer): resolve ownership ids through AuthorizedQuery, closing a cross-tenant oracle

PostgreSQL evaluates referential integrity with row security bypassed, so
another tenant's app_user id satisfied the FK and answered 200 while an
invented id raised an FK violation and answered 500. The difference was a
cross-tenant existence oracle, and it left customers owned by strangers.

Both now resolve through AuthorizedQuery and collapse to the same 404."
```

### Task 3: Validate the tenant slug at provisioning, and map duplicates to 409

A slug that does not match `PathPrefixTenantResolver`'s `^[a-z0-9][a-z0-9-]{0,62}$` — `Acme`, `acme_corp` — creates a tenant that is **permanently unreachable**: every request resolves no slug and answers 401, with no error at creation time. A duplicate slug is a raw 500 from the unique constraint.

**Two real corrections to check before writing anything:** `ProvisionRequest` is a **nested record inside `PlatformTenantController.java`** (`public record ProvisionRequest(String slug, String name, String adminEmail, String adminFullName) {}`), not its own file. And `TenantProvisioningService.provision` takes **four positional `String` arguments** (`provision(String slug, String name, String adminEmail, String adminFullName)`), not a request object — the controller unpacks the record before calling it. Bean validation (`@NotBlank`, `@Pattern`) only fires through `@Valid` at the web layer, so it cannot be exercised by calling the service directly with positional strings: **these tests must go through MockMvc**, the same pattern `security.DirectApiAccessTest` already uses against this exact endpoint (`mvc.perform(post("/api/platform/tenants")...)`). Read both files in full before writing the tests below.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/provisioning/PlatformTenantController.java` (the nested `ProvisionRequest` record, and `@Valid`)
- Create: `backend/src/main/java/co/ara/onboarding/provisioning/DuplicateSlugException.java`
- Test: `backend/src/test/java/co/ara/onboarding/provisioning/TenantProvisioningTest.java` (MockMvc-based, modelled on `security.DirectApiAccessTest`'s existing `/api/platform/tenants` calls)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aSlugTheResolverCannotParseIsRejectedAtCreation() throws Exception {
    mvc.perform(post("/api/platform/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"slug":"Acme","name":"Acme Corp","adminEmail":"a@acme.test","adminFullName":"Admin"}"""))
            .andExpect(status().isBadRequest());
}

@Test
void aDuplicateSlugIsAConflictNotAServerError() throws Exception {
    String body = """
            {"slug":"dup","name":"First","adminEmail":"a@x.test","adminFullName":"A"}""";
    mvc.perform(post("/api/platform/tenants")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

    mvc.perform(post("/api/platform/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"slug":"dup","name":"Second","adminEmail":"b@x.test","adminFullName":"B"}"""))
            .andExpect(status().isConflict());
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: the first creates an unreachable tenant and returns 200; the second's duplicate returns 500 (`DataIntegrityViolationException`), not 409.

- [ ] **Step 3: Constrain the request and add `@Valid`**

The pattern must be **the same literal** the resolver uses. Do not retype it — a drift between the two reintroduces the bug in the opposite direction.

```java
public record ProvisionRequest(
        @NotBlank @Pattern(regexp = PathPrefixTenantResolver.SLUG_PATTERN,
                message = "must be lowercase alphanumeric with hyphens, 1-63 characters")
        String slug,
        @NotBlank String name,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminFullName) {}
```

`PathPrefixTenantResolver`'s current pattern is `Pattern.compile("^/api/t/([a-z0-9][a-z0-9-]{0,62})(/.*)?$")` — the slug shape is a capture group inside a larger path pattern, not yet its own constant. Extract just the slug sub-pattern, `^[a-z0-9][a-z0-9-]{0,62}$`, as a `public static final String SLUG_PATTERN` on `PathPrefixTenantResolver`, and have the resolver build its existing path pattern by interpolating that constant into the capture group — so there is exactly one literal, not two that happen to agree.

Add `@Valid` to the controller method's `@RequestBody ProvisionRequest request` parameter, and catch the unique-constraint violation in `TenantProvisioningService`, rethrowing `DuplicateSlugException` mapped to 409 by the module's own `@RestControllerAdvice` (check whether `provisioning` already has one before creating a second).

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(provisioning): validate the tenant slug and map a duplicate to 409

An unparseable slug created a permanently unreachable tenant -- every
request resolved no slug and answered 401, with no error at creation.
SLUG_PATTERN now has exactly one definition, shared by the resolver and
the request constraint, so the two cannot drift."
```

### Task 4: Deactivation invalidates pending credentials

`deactivate` revokes every refresh family and `AuthorizationService` zeroes authority for a non-ACTIVE user — but `PasswordResetService` consults `status` nowhere, so a DEACTIVATED account can still request and complete a reset, and outstanding `invitation` rows stay redeemable. No authority is gained today, but "deactivation ends the account" is only half a mechanism while its pending credentials outlive it.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/auth/PasswordResetService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/identity/UserAdminService.java`
- Test: `backend/src/test/java/co/ara/onboarding/auth/DeactivationRevokesCredentialsTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aDeactivatedUserCannotCompleteAPasswordReset() {
    // token issued while ACTIVE, redeemed after deactivation
    String token = resets.request(email);
    admin.deactivate(userId);
    assertThatThrownBy(() -> resets.complete(token, "new-password-value"))
            .isInstanceOf(InvalidTokenException.class);
}

@Test
void deactivationRevokesOutstandingInvitations() {
    UUID invitationId = invitations.issue(userId, InvitationKind.ACTIVATION);
    admin.deactivate(userId);
    assertThat(invitations.findRedeemable(invitationId)).isEmpty();
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Add the status check and the revocation**

`PasswordResetService.complete` reads the user and refuses a non-ACTIVE one, mapping to the same `InvalidTokenException` the unknown-token path uses — a distinct error here would tell an attacker the address exists and is deactivated. `UserAdminService.deactivate` revokes outstanding invitations in the same transaction as the status change.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(auth): deactivation invalidates pending resets and invitations

Deactivation revoked sessions but left pending credentials redeemable.
The reset refusal reuses InvalidTokenException rather than a distinct
error, so it does not become an oracle for which addresses are deactivated."
```

### Task 5: Contact retirement revokes portal access, and the two uniqueness rules agree

Three related defects in one area, fixed together because they share a test fixture:

1. `update` sets `status = INACTIVE` on the contact only; the linked `app_user` stays ACTIVE and `LoginService` reads the user, so **a retired contact can still sign in**. It can also still be invited and activated — neither `InvitationService.issue` nor `ActivationService.activateContact` reads `ContactStatus`.
2. `CustomerContactService.update` rewrites `contact.email` without touching `app_user.email`, so a corrected address leaves the portal login on the old one.
3. `customer_contact` is unique on `(customer_id, email)` **case-sensitively** while `app_user` is unique on `(tenant_id, lower(email))` — so two contacts differing only in case are accepted and the second one's activation fails as an "invalid token".

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/auth/InvitationService.java`
- Create: `backend/src/main/resources/db/migration/V14__contact_email_ci.sql` *(if Phase 1 runs before Phase 2, this takes V14 and the feature migration becomes V15 — renumber, never edit a committed migration)*
- Test: `backend/src/test/java/co/ara/onboarding/customer/ContactRetirementTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aRetiredContactCannotSignIn() {
    contacts.update(contactId, retireRequest());
    assertThatThrownBy(() -> login.authenticate(tenantSlug, contactEmail, password))
            .isInstanceOf(BadCredentialsException.class);
}

@Test
void retiringAContactRevokesOutstandingInvitations() {
    invitations.issue(contactUserId, InvitationKind.ACTIVATION);
    contacts.update(contactId, retireRequest());
    assertThat(invitations.redeemableFor(contactUserId)).isEmpty();
}

@Test
void correctingAContactEmailMovesThePortalLoginWithIt() {
    contacts.update(contactId, emailChangedTo("new@acme.test"));
    assertThat(users.findById(contactUserId).orElseThrow().getEmail())
            .isEqualTo("new@acme.test");
}

@Test
void twoContactsDifferingOnlyInCaseAreRefused() {
    contacts.create(customerId, contact("Person@acme.test"));
    assertThatThrownBy(() -> contacts.create(customerId, contact("person@acme.test")))
            .isInstanceOf(DuplicateContactEmailException.class);
}
```

- [ ] **Step 2: Run to verify all four fail**

- [ ] **Step 3: Fix all three defects**

Retirement deactivates the linked `app_user` and revokes its invitations, in the same transaction. Email correction updates both rows. The migration replaces the case-sensitive unique index with `UNIQUE (customer_id, lower(email))` to match `app_user`.

The migration must handle existing rows that already violate the new index — a pre-existing case-collision pair cannot be silently dropped. Fail the migration loudly with a clear message rather than deleting data; business records are never deleted.

- [ ] **Step 4: Run to verify all four pass, then the whole suite**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(customer): retiring a contact ends portal access, and email rules agree

Three defects sharing one fixture: a retired contact could still sign in
and could still redeem an invitation; a corrected email left the portal
login on the old address; and case-only-different contacts were accepted
while app_user's lower(email) index refused the second activation."
```

### Task 6: Audit the three unaudited write paths

`RoleService.deleteRole` records nothing; re-enabling a disabled role records nothing because `setEnabled` records only on the disable branch; and `PasswordResetService` records neither request nor completion.

Deliberately still **not** audited, and this must not change: refresh-token rotation (every request would write a row, and reuse detection — the actual security event — *is* recorded) and login-throttle counters.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/RoleService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/auth/PasswordResetService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/RoleAuditTest.java` (new)

- [ ] **Step 1: Write the failing tests** — one per path, asserting the action key and that `timeline_visible` is `false` (these are identity/auth events, compliance-only, matching the existing split).

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Add the three recordings**, each **before** any call that records further events (the cause-before-effect rule). `setEnabled` records on both branches with distinct action keys — `role.enabled` and `role.disabled` — never one key with a boolean payload, which is the shape that made pre-2026-08-16 deactivations unqueryable.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 7: `DB_APP_PASSWORD` becomes required configuration

`V2__app_role_and_tenant.sql` creates the login role with the committed literal `onboarding_app` and `application.yml` defaults to it, with no guard — the same failure shape `JwtProperties` was built to prevent for `JWT_SECRET`.

Migrations are forward-only, so **the role's password must be rotated operationally**; the code half is to drop the default and refuse to start without the variable.

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/co/ara/onboarding/platform/DatabaseCredentialsGuard.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/DatabaseCredentialsGuardTest.java` (new)
- Modify: `CLAUDE.md` — the rotation is an operational step a reader must know about

- [ ] **Step 1: Write the failing test** — the guard refuses to start when the property is absent, blank, or equal to the published literal `onboarding_app`, modelled on `JwtSecretGuardTest`.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Add the guard and drop the default.** Deliberately **not** keyed on profile — a "unless dev" check misses the deployment that forgot the profile too. The test harness (`PostgresTestBase`) and the e2e harness (`e2e/support/backend.mjs`) must generate or set a value per run, exactly as they already do for `JWT_SECRET`; no literal is committed anywhere.

- [ ] **Step 4: Run the whole suite** — this one can break every test at once if the harness is missed, which is the point of running it here.

- [ ] **Step 5: Commit**

### Task 8: A department picker on the Users create form

A narrow-scoped `user.manage` holder cannot create a user through the Users screen: the form sends only `{email, fullName}`, so `departmentId` is null, a department-less user is outside a DEPARTMENT- or TEAM-scoped actor's own scope, and `UserAdminService.create` refuses with a 404 that says nothing useful. There is also **no user-edit screen** — `PUT /admin/users/{id}` exists but `lib/api/admin.ts` never calls it — so a department cannot be changed after creation at all.

**Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting this task.**

**Files:**
- Modify: `frontend/src/lib/api/admin.ts` (add the `PUT` call that already exists server-side)
- Modify: the Users create form component and its test

- [ ] **Step 1: Write the failing component test** — the form renders a department select, its options are the departments the actor can see, and submitting includes `departmentId`.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Add the picker**, options scoped to the actor. Where the actor holds `user.manage` at DEPARTMENT or TEAM, the picker must not offer a department they cannot manage into — offering an option that will 404 is worse than not offering it.

- [ ] **Step 4: Run `npx vitest run`**

- [ ] **Step 5: Commit**

### Task 9: Document the 409 on duplicate contact email

The behaviour is real and tested (`DuplicateContactEmailException`, the unique index), but springdoc advertises only 201 on create and 200 on update, so `generated.ts` has no 409 for a client to narrow on.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/customer/CustomerContactController.java` (`@ApiResponse`)
- Regenerate: `frontend/src/lib/api/generated.ts`

- [ ] **Step 1: Add the `@ApiResponse(responseCode = "409")` annotations to both endpoints**

- [ ] **Step 2: Regenerate and confirm the 409 appears**

```bash
cd backend && ./gradlew openApiSpec
cd ../frontend && npm run generate:api
git diff --stat frontend/src/lib/api/generated.ts
```

springdoc orders schema properties nondeterministically, so back-to-back regenerations produce reordering-only diffs. That is noise, not a contract change — but the 409 appearing is the signal you are looking for.

- [ ] **Step 3: Commit**

### Task 10: Q18 — a journey carries a human-readable name

`onboarding_case` carries no `name` column, so every multi-journey surface fakes a label: `CaseSwitcher` renders the current stage name plus a short id, which is not a name and stops being right the moment the stage advances. QA Q18 decides a journey carries a name set at creation.

This is a schema and `CreateCaseRequest` addition against an already-delivered module, not new sub-project work — and it is a prerequisite for anything in Phase 2 that lists tasks across journeys, because "My work" shows a task's journey and needs something to call it.

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__case_name.sql` *(renumber per what Phase 1 has already taken)*
- Modify: `Case.java`, `CreateCaseRequest.java`, `CaseView.java`, `UpdateCaseRequest.java`, `CaseService.java`
- Modify: `frontend/src/components/journey/CaseSwitcher.tsx` and its test

- [ ] **Step 1: Write the failing tests** — a case created with a name returns it; `CaseSwitcher` renders the name rather than a stage-plus-id label.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Add the column, backfilling existing rows**

Existing cases have no name. Backfill with the template name plus a short id — the same label the UI fakes today — so the column can be `NOT NULL` without inventing data that reads as user-authored.

**`UpdateCaseRequest` gaining `name` means `CaseView` must carry `name` too**, or every `PUT` from an existing client silently blanks it. This is the full-replace invariant, and this task is exactly the shape that trips it.

- [ ] **Step 4: Run both suites**

- [ ] **Step 5: Commit**

---

# Phase 2 — Tasks & Collaboration, backend

### Task 11: Migration and entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__task.sql` *(renumber if Phase 1 took V14/V15)*
- Create: `task/Task.java`, `TaskStatus.java`, `TaskPriority.java`, `TaskRepository.java`, `TaskChecklistItem.java`, `TaskChecklistItemRepository.java`, `Comment.java`, `CommentResourceType.java`, `CommentRepository.java`
- Test: `backend/src/test/java/co/ara/onboarding/architecture/RlsCoverageTest.java` (run, do not modify)

**Interfaces:**
- Produces: `Task`, `TaskStatus`, `TaskPriority`, `Comment`, `CommentResourceType` and the three repositories, used by every later task in this phase.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE task (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    case_id             uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id        uuid NOT NULL REFERENCES milestone(id),
    requirement_id      uuid     NULL REFERENCES requirement(id),
    title               text NOT NULL,
    description         text,
    priority            text NOT NULL,
    status              text NOT NULL,
    assignee_id         uuid     NULL REFERENCES app_user(id),
    due_date            date,
    completed_at        timestamptz,
    completed_by        uuid     NULL REFERENCES app_user(id),
    cancelled_at        timestamptz,
    cancellation_reason text,
    attachment_ref      uuid     NULL,
    attachment_ref_type text     NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT task_priority_ck CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT task_status_ck   CHECK (status IN
        ('PENDING','IN_PROGRESS','WAITING','COMPLETED','CANCELLED')),
    -- Cancelling without a reason is the silent-waiver path this design refuses.
    CONSTRAINT task_cancel_reason_ck CHECK (
        status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);
-- A requirement is satisfied by at most one task.
CREATE UNIQUE INDEX task_requirement_uq ON task (requirement_id)
    WHERE requirement_id IS NOT NULL;
-- "My work" is a cross-case query; this is why case_id is denormalised.
CREATE INDEX task_tenant_assignee_idx ON task (tenant_id, assignee_id, status);
CREATE INDEX task_tenant_case_idx     ON task (tenant_id, case_id);

CREATE TABLE task_checklist_item (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    task_id    uuid NOT NULL REFERENCES task(id),
    label      text NOT NULL,
    done       boolean NOT NULL DEFAULT false,
    ordinal    int NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX task_checklist_task_idx ON task_checklist_item (tenant_id, task_id, ordinal);

CREATE TABLE comment (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    case_id       uuid NOT NULL REFERENCES onboarding_case(id),
    resource_type text NOT NULL,
    resource_id   uuid NOT NULL,
    author_id     uuid NOT NULL REFERENCES app_user(id),
    body          text NOT NULL,
    edited_at     timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    -- Half the gate. The Java enum is the other half; adding a third value
    -- requires a migration AND a compile error, which is the point.
    CONSTRAINT comment_resource_type_ck CHECK (
        resource_type IN ('task','onboarding_case')),
    CONSTRAINT comment_body_ck CHECK (length(btrim(body)) > 0)
);
CREATE INDEX comment_tenant_resource_idx
    ON comment (tenant_id, resource_type, resource_id, created_at);

SELECT enable_tenant_rls('task');
SELECT enable_tenant_rls('task_checklist_item');
SELECT enable_tenant_rls('comment');

GRANT SELECT, INSERT, UPDATE ON task                TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON task_checklist_item TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON comment             TO onboarding_app;
```

No `GRANT DELETE` on any of the three. Business records are deactivated, never deleted.

- [ ] **Step 2: Run `RlsCoverageTest` and expect it to PASS without modification**

```bash
cd backend && ./gradlew cleanTest test --tests "co.ara.onboarding.architecture.RlsCoverageTest"
```

It is deny-by-default over the live schema, so it covers the three new tables the moment they exist. **Its allowlist stays at four entries.** If it fails, a table is missing `enable_tenant_rls` — fix the migration, never the allowlist.

- [ ] **Step 3: Write the entities**

All three extend `TenantScopedEntity`. Enums are `TaskStatus`, `TaskPriority`, and `CommentResourceType` with exactly two values — `TASK("task")` and `CASE("onboarding_case")` — each carrying its wire string, so the `CHECK` constraint and the enum cannot disagree by a typo.

- [ ] **Step 4: Run the whole suite**

- [ ] **Step 5: Commit**

### Task 12: Permission keys, catalog entries, and seeded role templates

**Files:**
- Modify: `authz/PermissionKeys.java`, `authz/PermissionCatalog.java`, `authz/RoleTemplates.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/PermissionCatalogTest.java`

**Interfaces:**
- Produces: `PermissionKeys.TASK_VIEW`, `TASK_MANAGE`, `TASK_COMPLETE`, `COMMENT_CREATE`

**Verify before writing:** `PermissionCatalog` has no `scopesFor` method. Scopes are read via `PermissionCatalog.byKey(key)` returning `Optional<Permission>`, and `Permission` is `record Permission(String key, String category, String resourceType, String description, Set<Scope> allowedScopes)` — so the accessor is `.allowedScopes()`, not `.scopesFor(...)`. And `RoleTemplates.RoleTemplate` is `record RoleTemplate(String name, String description, Map<String, Scope> grants)` — the map is called `grants`, not `permissions`. Read both files before writing the tests below; do not use the method names as first drafted here without checking.

- [ ] **Step 1: Write the failing test**

```java
@Test
void taskPermissionsAreCataloguedAtTheirIntendedScopes() {
    assertThat(PermissionCatalog.byKey(PermissionKeys.TASK_VIEW).orElseThrow().allowedScopes())
            .containsExactlyInAnyOrder(ALL, DEPARTMENT, TEAM, ASSIGNED);
    assertThat(PermissionCatalog.byKey(PermissionKeys.TASK_COMPLETE).orElseThrow().allowedScopes())
            .containsExactlyInAnyOrder(ALL, DEPARTMENT, TEAM, ASSIGNED);
    assertThat(PermissionCatalog.byKey(PermissionKeys.TASK_MANAGE).orElseThrow().allowedScopes())
            .containsExactlyInAnyOrder(ALL, DEPARTMENT, TEAM);
    assertThat(PermissionCatalog.byKey(PermissionKeys.COMMENT_CREATE).orElseThrow().allowedScopes())
            .containsExactlyInAnyOrder(ALL, DEPARTMENT, TEAM);
}

/**
 * Completing a requirement-linked task routes through the gated
 * RequirementService.satisfy, so a role holding task.complete without
 * milestone.complete is refused mid-flow. Every template that can complete a
 * milestone must be able to complete the tasks that clear its requirements.
 */
@Test
void everyTemplateHoldingMilestoneCompleteAlsoHoldsTaskComplete() {
    for (var template : RoleTemplates.all()) {
        Scope milestone = template.grants().get(PermissionKeys.MILESTONE_COMPLETE);
        if (milestone == null) continue;
        assertThat(template.grants())
                .as("template %s", template.name())
                .containsEntry(PermissionKeys.TASK_COMPLETE, milestone);
    }
}
```

The second test is the one that matters: it encodes the spec's §5.2 coupling as a rule rather than leaving it to whoever edits the templates next.

- [ ] **Step 2: Run to verify both fail**

- [ ] **Step 3: Add the four keys, catalogue them, extend the templates**

`TASK_VIEW` and `TASK_COMPLETE` use `RECORD` (all four scopes, matching `MILESTONE_COMPLETE`); `TASK_MANAGE` and `COMMENT_CREATE` use `ORG_SCOPES`. Every template holding `milestone.complete` gains `task.complete` at the same scope; every template holding `case.view` gains `task.view` and `comment.create` at that scope.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 13: Descriptors

**Files:**
- Create: `scoping/TaskDescriptor.java`, `scoping/CommentDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java`

**Interfaces:**
- Produces: descriptor coverage for `Task` and `Comment`, without which `DescriptorRegistry.validate()` refuses to start the application.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void taskAssignedScopeIsPersonalAndNotTeamMediated() {
    // The actor is in a team that owns the case, but is not the assignee.
    // ASSIGNED must NOT match: access mediated by a team is TEAM.
    var predicate = taskDescriptor.predicate(Scope.ASSIGNED, actorInOwningTeam);
    assertThat(matches(predicate, taskAssignedToSomeoneElse)).isFalse();
}

@Test
void bothDescriptorsFailClosedWithNoDepartmentAndNoTeams() {
    var bare = actorWith(null, Set.of());
    assertThat(matches(taskDescriptor.predicate(Scope.DEPARTMENT, bare), anyTask)).isFalse();
    assertThat(matches(commentDescriptor.predicate(Scope.TEAM, bare), anyComment)).isFalse();
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Write both descriptors**

Both live in `scoping/`, never in `task` — a descriptor inside the module owning the entity closes a module cycle. `TaskDescriptor` resolves DEPARTMENT and TEAM through the task's `case_id` to the case's `owning_department_id` / `owning_team_id`, and **ASSIGNED through `assignee_id` alone**. `CommentDescriptor` resolves all three through `case_id`, identically to `CaseDescriptor`. Both return `cb.disjunction()` when the actor has no department and no teams.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 14: The two ports `journey` declares

**Files:**
- Create: `journey/TaskDirectory.java`, `journey/TaskSummary.java`, `journey/TaskLifecycle.java`

**Interfaces:**
- Produces:
  - `TaskDirectory.summaryFor(Collection<UUID> milestoneIds) -> Map<UUID, TaskSummary>`
  - `TaskSummary(int open, int total)`
  - `TaskLifecycle.reopenForMilestone(UUID milestoneId) -> void`
  - `TaskLifecycle.instantiateForCase(UUID caseId) -> void` (used in Task 19)

- [ ] **Step 1: Declare the ports**

```java
package co.ara.onboarding.journey;

/**
 * What journey may know about tasks. task implements this; journey never names
 * a task type, and ModuleBoundaryTest.noJourneyDependencyOnTask enforces it.
 *
 * Takes a COLLECTION and returns a map deliberately. A roadmap renders every
 * milestone in a case, so a per-id port would make that N queries -- the shape
 * of the port is what prevents it.
 */
public interface TaskDirectory {
    Map<UUID, TaskSummary> summaryFor(Collection<UUID> milestoneIds);
}
```

```java
/**
 * Reopening a milestone must reopen the tasks completed inside it, or the
 * milestone shows requirements satisfied by tasks still marked complete, with
 * no way to clear them. Called from MilestoneService.reopen, same transaction.
 *
 * Cancelled tasks are NOT reopened -- reopening a milestone must not resurrect
 * work somebody deliberately abandoned.
 */
public interface TaskLifecycle {
    void instantiateForCase(UUID caseId);
    void reopenForMilestone(UUID milestoneId);
}
```

Interfaces only in this task; implementations land in Tasks 19-21. Nothing injects them yet, so nothing breaks.

- [ ] **Step 2: Compile** — `cd backend && ./gradlew compileJava`

- [ ] **Step 3: Commit**

### Task 15: `noJourneyDependencyOnTask`, proven red

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`

- [ ] **Step 1: Write the rule**

```java
/**
 * Its own named rule rather than folded into the cycle check: a one-way
 * journey -> task import would still pass a plain no-cycles test, and the whole
 * reason TaskDirectory and TaskLifecycle exist is to make that import
 * unnecessary. Same reasoning as noWorkflowDependencyOnJourney.
 */
@Test
void noJourneyDependencyOnTask() {
    noClasses().that().resideInAPackage("co.ara.onboarding.journey..")
            .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.task..")
            .check(classes);
}
```

- [ ] **Step 2: Prove it red before trusting it**

Temporarily add `import co.ara.onboarding.task.Task;` and a field reference to any `journey` class, run the rule, confirm it FAILS naming that class, then revert the import.

```bash
./gradlew cleanTest test --tests "co.ara.onboarding.architecture.ModuleBoundaryTest"
```

A structural guard nobody has seen fail is a guard nobody can trust — and every enumeration-shaped guard in sub-project 1 silently stopped covering things.

- [ ] **Step 3: Confirm it passes with the import removed**

- [ ] **Step 4: Commit**

### Task 16: `TaskService` — create ad-hoc, read, and the write-path guards

**Files:**
- Create: `task/TaskService.java`, `CreateTaskRequest.java`, `UpdateTaskRequest.java`, `TaskView.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskServiceTest.java` (new)

**Interfaces:**
- Consumes: `Task`, `TaskRepository` (Task 11), `TaskDescriptor` (13)
- Produces: `TaskService.create(UUID caseId, CreateTaskRequest) -> TaskView`, `.get(UUID) -> TaskView`, `.forCase(UUID) -> List<TaskView>`, `.update(UUID, UpdateTaskRequest) -> TaskView`

- [ ] **Step 1: Add `task..` to the finder rule FIRST**

Extend `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`'s package list with `co.ara.onboarding.task..` **before** writing the service, so the rule is live while the code is written rather than retrofitted after.

- [ ] **Step 2: Write the failing tests**

```java
@Test
void anAdHocTaskHasNoRequirementAndDoesNotTouchProgress() {
    var before = cases.get(caseId).progressPercent();
    UUID taskId = tasks.create(caseId, new CreateTaskRequest(
            milestoneId, null, "Chase legal re: NDA", null,
            TaskPriority.MEDIUM, assigneeId, LocalDate.now().plusDays(3))).id();

    assertThat(tasks.get(taskId).requirementId()).isNull();
    assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
}

@Test
void aTaskInAnotherTenantIsA404() {
    assertThatThrownBy(() -> fixture.runAs(tenantB, () -> tasks.get(tenantATaskId)))
            .isInstanceOf(NoSuchElementException.class);
}

/** The escalation shape that bit sub-project 1 three times. */
@Test
void aMilestoneIdFromTheRequestBodyIsResolvedBeforeItIsWritten() {
    assertThatThrownBy(() -> fixture.runAs(tenantA, () -> tasks.create(caseId,
            createRequestWithMilestone(anotherTenantsMilestoneId))))
            .isInstanceOf(NoSuchElementException.class);
}
```

- [ ] **Step 3: Run to verify they fail**

- [ ] **Step 4: Implement**

Every method carries `@RequirePermission`. Every id from the URL or body — `caseId`, `milestoneId`, `requirementId`, `assigneeId` — resolves through `AuthorizedQuery` **before** the write. `StageWriteScopeGuard.check(c, m, stageOf(m))` gates every mutation. `case_id` is copied from the resolved milestone's case, never taken from the request, so the two cannot disagree.

**`TaskView` must carry every field `UpdateTaskRequest` accepts** — title, description, priority, assigneeId, dueDate, milestoneId. The full-replace invariant.

- [ ] **Step 5: Run to verify they pass, then the whole suite**

- [ ] **Step 6: Commit**

### Task 17: Status transitions, and completion through the existing gated path

**Files:**
- Modify: `task/TaskService.java`
- Create: `task/TaskStatusRequest.java`, `task/IllegalTaskTransitionException.java`
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskCompletionTest.java` (new)

**Interfaces:**
- Consumes: `journey.RequirementService.satisfy(UUID requirementId, UUID ref, String refType)`
- Produces: `TaskService.changeStatus(UUID taskId, TaskStatusRequest) -> TaskView`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void completingARequirementLinkedTaskSatisfiesItsRequirementAndAdvancesTheMilestone() {
    tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));
    assertThat(cases.roadmap(caseId).stages().get(0).milestones().get(0).status())
            .isEqualTo(MilestoneStatus.DONE);
}

@Test
void completingAnAdHocTaskLeavesTheCaseUntouched() {
    var before = cases.get(caseId).progressPercent();
    tasks.changeStatus(adHocTaskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));
    assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
}

@Test
void taskCompleteWithoutMilestoneCompleteIsRefusedOnARequirementLinkedTask() {
    // Holds task.complete at ALL but NOT milestone.complete. The refusal comes
    // from RequirementService.satisfy's own gate, across the bean boundary.
    assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowUser, () ->
            tasks.changeStatus(requirementLinkedTaskId,
                    new TaskStatusRequest(TaskStatus.COMPLETED, null))))
            .isInstanceOf(AccessDeniedException.class);
}

@Test
void theSameActorMayCompleteAnAdHocTaskWithoutMilestoneComplete() {
    fixture.runAsUser(tenant, narrowUser, () ->
            tasks.changeStatus(adHocTaskId, new TaskStatusRequest(TaskStatus.COMPLETED, null)));
    assertThat(tasks.get(adHocTaskId).status()).isEqualTo(TaskStatus.COMPLETED);
}

@Test
void anIllegalTransitionIsRefused() {
    tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));
    assertThatThrownBy(() -> tasks.changeStatus(taskId,
            new TaskStatusRequest(TaskStatus.IN_PROGRESS, null)))
            .isInstanceOf(IllegalTaskTransitionException.class);
}
```

The third and fourth together document the spec's §5.2 coupling: refused where it clears a requirement, allowed where it does not.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

```java
@RequirePermission(PermissionKeys.TASK_COMPLETE)
@Transactional
public TaskView changeStatus(UUID taskId, TaskStatusRequest request) {
    Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_COMPLETE, taskId);
    guardTransition(t.getStatus(), request.status());
    // ... write scope, status, completedAt/By ...

    audit.record(AuditActions.TASK_STATUS_CHANGED, ...);   // cause before effects

    // The ONLY path to a case mutation. RequirementService.satisfy already takes
    // CaseRepository.lockById's row lock, reconciles, and audits -- so invariant 4
    // holds by construction, not by discipline. Do not call CaseEngine here.
    if (request.status() == TaskStatus.COMPLETED && t.getRequirementId() != null) {
        requirements.satisfy(t.getRequirementId(), t.getId(), "task");
    }
    return toView(t);
}
```

An ad-hoc task's completion calls nothing on the engine: progress is derived from requirements alone, so there is nothing to recompute. Write that argument into the code as a comment — if a later sub-project makes tasks count toward progress, this is the line that must change.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 18: Cancellation

**Files:**
- Modify: `task/TaskService.java`, `audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskCancellationTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void cancellingLeavesTheRequirementOpenAndTheMilestoneIncomplete() {
    tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "not needed"));
    assertThat(cases.roadmap(caseId).stages().get(0).milestones().get(0).status())
            .isNotEqualTo(MilestoneStatus.DONE);
}

@Test
void cancellingWithoutAReasonIsRefused() {
    assertThatThrownBy(() -> tasks.changeStatus(taskId,
            new TaskStatusRequest(TaskStatus.CANCELLED, "  ")))
            .isInstanceOf(IllegalArgumentException.class);
}

/**
 * Its own action, recorded on the TRANSITION into CANCELLED -- never inferred
 * from a status column. Because records are never deleted, this event is the
 * only evidence the cancellation happened, and it must stay distinguishable
 * from an unrelated edit. Same shape as contact.deactivated.
 */
@Test
void cancellingRecordsItsOwnAction() {
    tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "duplicate"));
    assertThat(timeline.forCase(caseId, Pageable.ofSize(50)).getContent())
            .extracting(AuditEventView::action).contains("task.cancelled");
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** A blank reason is refused in Java as well as by the `CHECK` constraint — the database is the backstop, not the error message. Cancelling never calls `satisfy` and never calls `waive`: `requirement.waive` demands its own reason under its own permission, and routing around it would be a silent waiver under a weaker gate.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 19: Instantiate tasks from requirements of kind `TASK`

**Ruling from the pre-flight scan:** `journey.TaskLifecycle` (Task 14) declares TWO methods, `instantiateForCase` and `reopenForMilestone`. This task is the first to inject `journey.TaskLifecycle` into `CaseService`, which means Spring needs a **complete** bean satisfying the whole interface before this task's tests can even start the application context — a bean implementing only `instantiateForCase` does not compile. So **this task creates `TaskLifecycleAdapter.java` implementing both methods**, not just the instantiation half; `reopenForMilestone` gets its real implementation here too (it is a small, self-contained query — moving a milestone's `COMPLETED` tasks to `PENDING`, leaving `CANCELLED` ones alone), even though nothing calls it yet. Task 20 then only wires `MilestoneService.reopen` to call it and writes the reopen tests — it does **not** create the adapter, because this task already did.

**Files:**
- Create: `task/TaskInstantiation.java`, `task/TaskLifecycleAdapter.java`
- Modify: `journey/CaseService.java` (call the port)
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskInstantiationTest.java` (new)

**Interfaces:**
- Consumes: `journey.TaskLifecycle` interface (declared in Task 14)
- Produces: `TaskLifecycleAdapter implements TaskLifecycle` — both methods implemented here; Task 20 wires the second one's call site.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aRequirementOfKindTaskProducesATaskWhenTheCaseOpens() {
    UUID caseId = openCaseWhoseFirstRequirementIsKindTask();
    assertThat(tasks.forCase(caseId)).singleElement()
            .satisfies(t -> {
                assertThat(t.requirementId()).isNotNull();
                assertThat(t.title()).isEqualTo("Collect KYC pack");   // the requirement's label
            });
}

@Test
void anInstantiatedTaskDefaultsToTheMilestoneOwner() {
    // Q15: the OWNER participant is the default owner. An instantiated task
    // arriving unassigned would put work in nobody's queue at the exact moment
    // the journey opens.
    assertThat(tasks.forCase(caseId).get(0).assigneeId()).isEqualTo(customerOwnerUserId);
}

@Test
void requirementsOfOtherKindsProduceNoTask() {
    UUID caseId = openCaseWithManualRequirementsOnly();
    assertThat(tasks.forCase(caseId)).isEmpty();
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

`CaseService.create` calls `taskLifecycle.instantiateForCase(c.getId())` **after** `audit.record(CASE_CREATED, …)` and before `engine.reconcile(c)`. Order matters: `case.created` must precede every `task.created` it causes, per the cause-before-effect rule.

`journey` calls the **port**, never a `task` type. `ModuleBoundaryTest.noJourneyDependencyOnTask` is what proves it.

- [ ] **Step 4: Run to verify they pass, plus `ModuleBoundaryTest` and `CauseBeforeEffectTest`**

- [ ] **Step 5: Commit**

### Task 20: Wire milestone reopen to `TaskLifecycleAdapter`

`TaskLifecycleAdapter` and its `reopenForMilestone` logic were created in Task 19 (a Spring bean has to implement a complete interface, and Task 19 was the first task to need one). This task's job is the call site and the tests, not the adapter itself.

**Files:**
- Modify: `journey/MilestoneService.java` (call `taskLifecycle.reopenForMilestone`), `task/TaskLifecycleAdapter.java` only if the review of Task 19's implementation finds a gap
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskReopenTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void reopeningAMilestoneReopensItsCompletedTasks() {
    tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));
    milestones.reopen(milestoneId, "customer sent the wrong document");

    assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.PENDING);
    assertThat(tasks.get(taskId).completedAt()).isNull();
}

@Test
void reopeningDoesNotResurrectACancelledTask() {
    tasks.changeStatus(cancelledId, new TaskStatusRequest(TaskStatus.CANCELLED, "duplicate"));
    milestones.reopen(milestoneId, "reworking the stage");
    assertThat(tasks.get(cancelledId).status()).isEqualTo(TaskStatus.CANCELLED);
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** `COMPLETED → PENDING`, clearing `completed_at` / `completed_by`; cancelled tasks untouched. Same transaction as the reopen, so a rollback takes both.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 21: `TaskDirectoryAdapter` and roadmap counts

**Files:**
- Create: `task/TaskDirectoryAdapter.java`
- Modify: `journey/CaseService.java` (roadmap view), `journey/MilestoneRoadmapView.java`
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskDirectoryTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theRoadmapCarriesOpenAndTotalTaskCountsPerMilestone() {
    var milestone = cases.roadmap(caseId).stages().get(0).milestones().get(0);
    assertThat(milestone.taskSummary().total()).isEqualTo(3);
    assertThat(milestone.taskSummary().open()).isEqualTo(2);   // one completed
}

/** The port takes a collection precisely so a roadmap is not N queries. */
@Test
void aRoadmapOfManyMilestonesIssuesOneTaskQuery() {
    var counter = statementCounter();
    cases.roadmap(caseIdWithTwelveMilestones());
    assertThat(counter.countMatching("from task")).isEqualTo(1);
}

@Test
void aMilestoneWithNoTasksReportsZeroNotNull() {
    assertThat(emptyMilestone.taskSummary()).isEqualTo(new TaskSummary(0, 0));
}
```

The second test is the reason the port's signature is a collection. Without it the shape is a suggestion; with it, a regression to per-id lookup fails.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** One query grouped by `milestone_id`, returning a map. Milestones with no tasks get `TaskSummary(0, 0)` — never null, never absent from the map, so the view never branches on nullity.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 22: Checklist items

**Files:**
- Create: `task/ChecklistService.java` and its request/view records
- Test: `backend/src/test/java/co/ara/onboarding/task/ChecklistTest.java` (new)

- [ ] **Step 1: Write the failing tests** — add, rename, toggle, reorder, plus the two that matter:

```java
@Test
void checklistItemsNeverEnterProgress() {
    var before = cases.get(caseId).progressPercent();
    UUID itemId = checklists.add(taskId, new AddChecklistItemRequest("Verify passport"));
    checklists.toggle(itemId);
    assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
}

@Test
void everyItemDoneDoesNotCompleteTheTask() {
    checklists.toggle(onlyItemId);
    assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.PENDING);
}
```

A checklist is a private aid to whoever holds the task. Completing the task is a deliberate act, never an emergent one.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** Structural edits gated on `TASK_MANAGE`; toggling gated on `TASK_COMPLETE` — ticking an item is doing the work, not editing the task.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 23: Comments

**Files:**
- Create: `task/CommentService.java`, `CreateCommentRequest.java`, `UpdateCommentRequest.java`, `CommentView.java`
- Test: `backend/src/test/java/co/ara/onboarding/task/CommentTest.java` (new)

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aCommentOnATaskAndOnAJourneyBothResolveThroughTheCase() {
    UUID onTask = comments.create(caseId, new CreateCommentRequest(
            CommentResourceType.TASK, taskId, "Chased the customer today")).id();
    UUID onCase = comments.create(caseId, new CreateCommentRequest(
            CommentResourceType.CASE, caseId, "Kickoff moved to Tuesday")).id();

    assertThat(comments.forResource(caseId, CommentResourceType.TASK, taskId))
            .extracting(CommentView::id).containsExactly(onTask);
    assertThat(comments.forResource(caseId, CommentResourceType.CASE, caseId))
            .extracting(CommentView::id).containsExactly(onCase);
}

/** Author-only, and no breadth of scope widens it. */
@Test
void anotherUsersCommentCannotBeEditedEvenAtAllScope() {
    UUID theirs = fixture.runAsUser(tenant, otherUser, () ->
            comments.create(caseId, new CreateCommentRequest(
                    CommentResourceType.CASE, caseId, "Theirs")).id());

    assertThatThrownBy(() -> fixture.runAsUser(tenant, adminAtAllScope, () ->
            comments.update(theirs, new UpdateCommentRequest("Rewritten"))))
            .isInstanceOf(AccessDeniedException.class);
}

@Test
void editingMarksTheCommentEdited() {
    comments.update(mineId, new UpdateCommentRequest("Corrected"));
    assertThat(comments.get(mineId).editedAt()).isNotNull();
}

@Test
void aCommentIsReadableByAnyoneWhoCanSeeTheJourney() {
    fixture.runAsUser(tenant, caseViewerOnly, () ->
            assertThat(comments.forResource(caseId, CommentResourceType.CASE, caseId))
                    .isNotEmpty());
}

@Test
void aCrossTenantCaseIdYieldsNoComments() {
    assertThatThrownBy(() -> fixture.runAs(tenantB, () ->
            comments.forResource(tenantACaseId, CommentResourceType.CASE, tenantACaseId)))
            .isInstanceOf(NoSuchElementException.class);
}
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

`case_id` is written from the resolved case, never from the request. Reads go through `AuthorizedQuery` with `CommentDescriptor` — **no carve-out**, which is the whole reason `case_id` is denormalised. Editing checks `author_id == actor` and refuses otherwise, regardless of scope.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 24: Controllers, exception handler, and the generated client

**Files:**
- Create: `task/TaskController.java`, `task/CommentController.java`, `task/TaskExceptionHandler.java`
- Regenerate: `frontend/src/lib/api/generated.ts`
- Test: `backend/src/test/java/co/ara/onboarding/architecture/DirectApiAccessTest.java`

- [ ] **Step 1: Write the controllers** per §7 of the spec — thin, no logic beyond binding and delegation.

The `@RestControllerAdvice` lives in **this module**, not `platform`. `platform` is the foundation everything depends on and must never name a domain type.

- [ ] **Step 2: Run `DirectApiAccessTest`**

`everyTenantScopedEndpointRejectsAnonymousAccess` derives its list by sweeping `RequestMappingHandlerMapping`, so it picks up the new endpoints automatically. The older hand-typed `everyEndpointRefusesAnonymousAndResolvesForAnAdministrator` does **not** — it is a list someone has to widen by hand, and it is already eleven endpoints short. Add the new endpoints to it; do not leave that gap wider than you found it.

- [ ] **Step 3: Regenerate the API types**

```bash
cd backend && ./gradlew openApiSpec
cd ../frontend && npm run generate:api
```

springdoc orders schema properties nondeterministically, so back-to-back regenerations produce reordering-only diffs. That is noise, not a contract change.

- [ ] **Step 4: Run both suites**

- [ ] **Step 5: Commit**

### Task 25: Security negatives and cause-before-effect coverage

**Files:**
- Create: `backend/src/test/java/co/ara/onboarding/task/TaskIsolationTest.java`, `TaskWriteScopeTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/journey/CauseBeforeEffectTest.java`

- [ ] **Step 1: Write the negatives**

```java
/**
 * Spec §6.4. A portal contact may be a task's assignee, but holds no task.*
 * grant, so authority resolves to zero and every gate denies. The consequence
 * is accepted and explicit: the task is invisible to them until sub-project 7.
 */
@Test
void aPortalAssigneeCannotSeeTheirOwnTask() {
    UUID taskId = assignTaskTo(portalContactUserId);
    assertThatThrownBy(() -> fixture.runAsUser(tenant, portalContactUserId, () ->
            tasks.get(taskId)))
            .isInstanceOf(NoSuchElementException.class);
}

/** At least one write test at the NARROWEST scope -- the sub-project 1 lesson. */
@Test
void anAssignedScopeHolderCannotCompleteSomeoneElsesTask() {
    assertThatThrownBy(() -> fixture.runAsUser(tenant, assignedScopeUser, () ->
            tasks.changeStatus(taskAssignedToAnother,
                    new TaskStatusRequest(TaskStatus.COMPLETED, null))))
            .isInstanceOf(NoSuchElementException.class);
}

@Test
void aWiderScopedHolderIsStillRefusedInsideAnOwnerOnlyStage() {
    // StageWriteScopeGuard throws WriteScopeException (journey package) --
    // not "WriteScopeViolationException". Confirm the name against the real
    // class before using it.
    assertThatThrownBy(() -> fixture.runAsUser(tenant, allScopeNonOwner, () ->
            tasks.changeStatus(taskInOwnerOnlyStage,
                    new TaskStatusRequest(TaskStatus.IN_PROGRESS, null))))
            .isInstanceOf(WriteScopeException.class);
}

@Test
void anUnlistedCommentResourceTypeIsRefusedByTheDatabase() {
    // The Java enum makes this uncompilable through the service; this proves the
    // CHECK constraint is the second half of the gate and not decorative.
    assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO comment (id,tenant_id,case_id,resource_type,resource_id," +
            "author_id,body,created_at,updated_at) VALUES (?,?,?,'document',?,?,?,now(),now())",
            Uuid7.generate(), tenant, caseId, caseId, userId, "x"))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: Add task and comment cases to `CauseBeforeEffectTest`**

```java
@Test
void taskCreationIsRecordedBeforeTheEventsItCauses() {
    assertThat(chronological(caseId))
            .containsSubsequence("case.created", "task.created");
}

@Test
void completingATaskIsRecordedBeforeTheRequirementItSatisfies() {
    assertThat(chronological(caseId))
            .containsSubsequence("task.status_changed", "requirement.satisfied",
                    "milestone.completed");
}
```

The second is the full causal chain this sub-project introduces, asserted end to end.

- [ ] **Step 3: Run each, confirm it fails for the right reason, implement any gaps**

- [ ] **Step 4: Run the whole suite**

- [ ] **Step 5: Commit**

---

# Phase 3 — Frontend

**Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting each task in this phase**, per CLAUDE.md. They serve execution quality and the four documented gaps — **not** a new visual language. The design bundle pins every colour, type and layout decision; where a skill suggests an "opinionated palette" or an "aesthetic risk", the bundle wins. Its own rule agrees: where the brief pins a visual direction, follow it exactly.

Binding, from CLAUDE.md and the bundle:

1. **Colour always means status, never decoration.** If you cannot name the state, use a neutral.
2. **Instrument Sans for human text, Spline Sans Mono for machine values.** A task title is sans; its due date, counts and ids are mono.
3. **Cards are flat.** Elevation only for what genuinely floats.
4. **Colour is never the only signal** — every status colour is paired with a word or an icon.
5. **Every user-facing string goes through `t()`.** A missing key renders as the key itself, so gaps are visible rather than silent.

### Task 26: API client hooks

**Files:**
- Create: `frontend/src/lib/api/tasks.ts`, `frontend/src/lib/api/comments.ts`
- Test: `frontend/src/lib/api/tasks.test.tsx` (new)

**Interfaces:**
- Produces: `useCaseTasks(caseId)`, `useMyWork(filters)`, `useCreateTask()`, `useChangeTaskStatus()`, `useComments(caseId, resourceType, resourceId)`, `useAddComment()`

- [ ] **Step 1: Write the failing tests** — each hook calls the right path and narrows the generated types; the status mutation invalidates both the task list **and** the roadmap query, since completing a requirement-linked task changes milestone state.

- [ ] **Step 2: Run to verify they fail** — `npx vitest run src/lib/api/tasks.test.tsx`

- [ ] **Step 3: Implement.** Types come from `generated.ts` — never hand-written. Requests are same-origin `/api/t/{slug}/…` through the existing client, which is what keeps the HttpOnly `SameSite=Strict` refresh cookie attached.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 27: The case workspace Tasks tab

**Files:**
- Create: `frontend/src/components/journey/TasksTab.tsx`, `frontend/src/components/task/TaskCard.tsx`, `frontend/src/components/task/TaskDetail.tsx`, `frontend/src/components/task/ChecklistEditor.tsx`
- Modify: the case workspace page to render it in the existing (currently empty) Tasks tab
- Test: `frontend/src/components/journey/TasksTab.test.tsx`, `frontend/src/components/task/TaskDetail.test.tsx` (both new)

**Interfaces:**
- Consumes: `useCaseTasks`, `useChangeTaskStatus` (Task 26)
- Produces: `TaskCard` (used by `WorkBoard` in Task 28) and `TaskDetail` (mounted by `CommentThread` in Task 29). Both are created here so no later task modifies a file that does not exist.

- [ ] **Step 1: Write the failing tests** — tasks group by milestone in roadmap order; an empty state renders with an action rather than blank space; the loading state renders `SkeletonRows`; an error renders a retry control. `TaskDetail` renders the checklist through `ChecklistEditor`, and ticking every item does **not** complete the task (the backend rule from Task 22, asserted again at the UI so the two cannot drift).

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** Status chips pair colour with a word. Due dates are mono; titles are sans. Overdue is `risk`-coloured **and** labelled — colour alone is not a signal.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 28: The "My work" board

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/work/page.tsx`, `components/task/WorkBoard.tsx`, `WorkColumn.tsx`
- Modify: the nav to include the route
- Test: `frontend/src/components/task/WorkBoard.test.tsx` (new)

- [ ] **Step 1: Write the failing tests**

The bucket mapping is the substance here, and it is **not** the five statuses:

```tsx
it("puts PENDING in Do now, IN_PROGRESS in In progress, WAITING in its own column", ...)
it("shows a COMPLETED task from 3 days ago in Done this week", ...)
it("omits a COMPLETED task from 10 days ago", ...)
it("omits CANCELLED tasks from every column", ...)
it("renders each column's own empty state, not one for the board", ...)
```

`CANCELLED` appearing nowhere is what makes the headline — "Only what you can act on" — true rather than decorative.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement**

Four columns per `SCREENS.md` §5, same column and card treatment as the stage board: title 12.5px/600, context 11.5px, then a chip and the customer name. Each column carries its own empty state — an empty "Do now" means something good ("Nothing needs you right now"); an empty "Waiting" is merely neutral.

**Filters live in the URL** so a filtered queue is shareable and the back button behaves.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 29: Comment threads

**Files:**
- Create: `frontend/src/components/comment/CommentThread.tsx`, `CommentComposer.tsx`
- Modify: `TaskDetail.tsx` and the journey workspace to mount them
- Test: `frontend/src/components/comment/CommentThread.test.tsx` (new)

- [ ] **Step 1: Write the failing tests** — comments render oldest-first with author and time; the edit control appears only on the actor's own comments; an edited comment is marked; the composer refuses an empty body **before** issuing a request; a failed post keeps the draft rather than discarding it.

The last one matters: losing someone's typed comment to a network error is the kind of small betrayal that stops people using a feature.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** Body text is sans; timestamps mono. Validation errors sit next to the field, never in a banner at the top.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

### Task 30: The four documented gaps, and responsive behaviour

**Files:**
- Modify: the Phase 3 components
- Test: `frontend/src/components/task/WorkBoard.responsive.test.tsx` (new)

- [ ] **Step 1: Write the failing tests**

jsdom evaluates neither media queries nor stacking contexts, so these assert the **structural invariant** — the class is present and correctly formed — exactly as `Sidebar`'s own breakpoint test does.

Assert specifically that no responsive class is written flush against a template interpolation. `min-[1024px]:flex${…}` is unextractable by Tailwind and compiles to nothing; that exact bug shipped a sidebar that never rendered and passed every review. `src/app/design-tokens.test.ts` already guards this globally — confirm it covers the new files.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement.** Four columns collapse to two, then to a single ordered list at 1024px. The board never scrolls horizontally; wide content scrolls inside its own container.

- [ ] **Step 4: Run `npx vitest run` and the token guard**

- [ ] **Step 5: Commit**

---

# Phase 4 — Close out

### Task 31: A Playwright spec for tasks and collaboration

**Files:**
- Create: `frontend/e2e/tasks.spec.ts`

- [ ] **Step 1: Write the spec** — create an ad-hoc task, assign it, complete a requirement-linked task and watch the milestone advance, cancel a task and confirm the milestone does **not** advance, post a comment.

**Never `.check()` or `.fill()`-and-assume against a control whose state depends on an async round trip.** `locator.check()` clicks and verifies in one synchronous step, which races a mutation that deliberately waits for the server. Use `.click()` followed by an auto-retrying `expect(locator).toBeChecked()`.

- [ ] **Step 2: Run it against the scratch database**

```bash
cd frontend && DB_URL=jdbc:postgresql://localhost:5433/onboarding npx playwright test e2e/tasks.spec.ts
```

- [ ] **Step 3: Commit**

### Task 32: Full verification and documentation

- [ ] **Step 1: Run everything**

```bash
cd backend && ./gradlew cleanTest test          # Docker must be running
cd ../frontend && npx vitest run
DB_URL=jdbc:postgresql://localhost:5433/onboarding npx playwright test
```

Report each suite's own summary line. **Treat a failure as the signal, never a count** — counts are deliberately not pinned in CLAUDE.md, because a number that drifts is a number that gets trusted.

- [ ] **Step 2: Update CLAUDE.md**

Add sub-project 3 to "delivered", add its own invariant cross-check, and **remove every backlog item Phase 1 actually closed** — a stale open-items list is worse than none, because it sends the next session hunting for bugs that no longer exist. Add anything Phase 1 found and did not fix.

Add one line to "What sub-project 4 inherits": the `attachment_ref` / `attachment_ref_type` seam on `task` and `comment` is already nullable and already round-trips, so attaching a document is a caller populating a field, not a schema change.

- [ ] **Step 3: Commit**
