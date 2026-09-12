# Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build sub-project 4 — a blob-storage substrate with local-filesystem and S3 adapters, a document container with immutable versions scoped per journey, QA Q9's two-axis visibility enforced server-side for internal *and* portal actors, and the `RequirementKind.DOCUMENT` seam that has sat in the enum since sub-project 2 with nothing behind it.

**Architecture:** One new backend module, `co.ara.onboarding.document`, depending one-way on `journey`, `workflow` and `customer` — the `programme` precedent. Nothing imports it, and there is **no port back into `journey`**. The sub-project also makes the first-ever change to the authorization core: a new opt-in `AudienceFilter` interface, collected by Spring like `ResourceAuthorizationDescriptor`, whose predicate `AuthorizationPredicateBuilder` ANDs **after** the scope union **including in the `ALL` branch**. That is what lets a record-level audience bind an ALL-scoped holder, which record scope structurally cannot. Portal actors resolve a code-constant permission set derived from their contact record, at `ALL` scope, with the audience filter doing every bit of the narrowing.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, AWS SDK for Java v2 (S3), JUnit 5, Testcontainers (PostgreSQL + MinIO), ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Playwright, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-12-documents-design.md`

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — the current bundle. Do **not** read `docs/uispecs_legacy/` for tokens, copy, layout or component behaviour. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 6), per CLAUDE.md. Unlike sub-project 3A, the bundle **does** cover this sub-project's operator screen — `SCREENS.md` §7 `docs` — so implement it rather than inventing one.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is loaded into every session and is the authority for everything sub-projects 1–3A established; this section carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. One new module: `co.ara.onboarding.document`. All descriptors and the new audience filter implementation go in `co.ara.onboarding.scoping`. The `AudienceFilter` interface itself goes in `co.ara.onboarding.authz`. Nothing else moves.
- **`journey`, `workflow` and `task` must never import `document`.** Enforced by three **named** `ModuleBoundaryTest` rules — `noJourneyDependencyOnDocument`, `noWorkflowDependencyOnDocument`, `noTaskDependencyOnDocument` — not by the cycle rule, which a one-way import would still pass. Each must be **seen red** before the module exists (Task 9).
- **There is no `DocumentDirectory` port back into `journey`.** If a task appears to need one, stop and escalate — it means something in `journey` grew a document dependency, which the rules above forbid.
- **Migration filenames are decided at dispatch time, never fixed here.** `V22` is the highest committed number as this plan is written. **Before writing any migration, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>`.** Forward-only — never edit a committed migration, not even temporarily.
- **Six new tables**, all tenant-owned: `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `SELECT enable_tenant_rls('<table>')` in the same migration, and `GRANT SELECT, INSERT, UPDATE ON <table> TO onboarding_app` — never `DELETE`. **`document_version` gets `GRANT SELECT, INSERT, UPDATE` but its immutability is enforced by a trigger**, not by withholding UPDATE, because the review columns are written after insert (Task 8). `RlsCoverageTest` is deny-by-default over the live schema; **its allowlist stays at four entries.**
- **UUIDv7 keys** via `co.ara.onboarding.platform.Uuid7.generate()`. All timestamps `timestamptz` in UTC; `due_at` and `expires_at` are `timestamptz`, not bare dates.
- **Every public `*Service` and `*Engine` method carries `@RequirePermission`.**
- **Every read of tenant business data goes through `AuthorizedQuery`**, and so does **every id a write path takes from a URL or a request body**, before it writes. **No new `AuthorizedQuery` exclusion is created in this sub-project.** The codebase has exactly one carve-out (`AuditQuery.findForResource`). If you find yourself wanting a second, the design is wrong, not the rule.
- **`document..` is added to `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`'s covered packages in the same commit that adds the services**, never afterwards. The rule was **already rebound to repository injection by sub-project 3A Task 2** — `injectsARepository()`, a `FINDER_RULE_EXCLUSIONS` list of fully-qualified names, and `finderRuleBindsToRepositoryInjectionNotClassName` asserting its contents. **Do not re-do that rebind, and add no new exclusion**; the precedent (`TaskDirectoryAdapter`) is that the offending class gets fixed to read through `AuthorizedQuery` instead.
- **`document.manage` is the one permission `DocumentAudienceFilter` deliberately does not narrow.** It grants metadata and retarget only, never bytes. This is load-bearing: without it an administrator could never load a document to fix its targeting (spec §6.2, §6.4).
- **Out-of-scope records return 404, never 403.** `AuthorizedQuery.getById` throws `NoSuchElementException`, which maps to 404.
- **`PATCH`, not `PUT`, for document metadata.** A `PUT` is a full replace and its view type must then carry every field its request type accepts. `PATCH` is used deliberately to avoid that trap; **do not add a `PUT` to this module.**
- **Permission keys** are declared in `PermissionKeys`, catalogued in `PermissionCatalog`, and referenced as constants — never as string literals.
- **Audit: record the cause before the calls that record its effects.** `AuditRecorder` stamps `occurred_at` from the clock, so call order is timeline order. `journey.CauseBeforeEffectTest` guards this; Task 29 adds `document.uploaded` → `requirement.satisfied` → `milestone.completed`.
- **No new caller of `CaseEngine.reconcile`.** Requirement satisfaction goes through the already-gated `journey.RequirementService.satisfy(requirementId, ref, refType)`. **`CaseEngine` is not modified anywhere in this plan.** If a task appears to need an engine change, stop and escalate.
- **The `BlobStore` port has no `delete`.** Not an omission — see spec §7.1. Do not add one, even unused.
- **Never a presigned URL.** Every byte transits the application (spec §7.3). If a task seems to need presigning, the local adapter cannot do it and the port would be leaky; stop and escalate.
- **TDD.** Failing test first; security tests before the mechanism they verify. A new structural guard must be **seen red** before the code it protects exists.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper instead, or `UnexpectedRollbackException` masks the exception under test.
- **Fixture create-helpers must run inside `runAs`** — the tables they write are RLS-protected.
- **Backend tests need Docker running.** Use `./gradlew cleanTest test`, never a bare `test` — Gradle marks an unchanged test task UP-TO-DATE and prints `BUILD SUCCESSFUL` having executed nothing. On PowerShell use `.\gradlew.bat`.
- **Every frontend task runs `npx tsc --noEmit` and `npm run lint` as part of its own verification**, not just `npx vitest run`. 3A's close records a hard `tsc` error and an ESLint error sitting undetected across several tasks because vitest is JSDOM-only and catches neither.
- **Java has been observed both blocked and working on this machine within single days** (Application Control policy). Run `java -version` at the start of any backend task; do not carry forward either state as durable.
- **Conventional Commits.** Explain *why* in the body, especially when deviating from this plan. When you find a plan defect, fix the code **and** amend the plan, and say so in the commit body.

---

## File structure

**Phase 1 modifies the authorization core.** Three files in `authz`, one in `auth`/`identity`'s read path. This is the only sub-project that touches these; treat every change as load-bearing.

```
backend/src/main/java/co/ara/onboarding/authz/
  AudienceFilter.java              NEW — the opt-in interface
  AudienceRegistry.java            NEW — Spring collects implementations; Optional on miss
  AuthorizationPredicateBuilder.java   MODIFIED — AND the audience after the scope union
  AuthorizationService.java        MODIFIED — resolve the PORTAL constant set

backend/src/main/java/co/ara/onboarding/scoping/
  DocumentAudienceFilter.java      NEW — the internal + portal predicate
  DocumentDescriptor.java          NEW
  DocumentVersionDescriptor.java   NEW
  DocumentShareDescriptor.java     NEW
  DocumentCaseLinkDescriptor.java  NEW
  DocumentRequestDescriptor.java   NEW
```

**Phase 2 creates the storage substrate** — no domain knowledge, no database:

```
backend/src/main/java/co/ara/onboarding/platform/storage/
  BlobStore.java                   put / open / exists. No delete.
  LocalFsBlobStore.java
  S3BlobStore.java
  StorageProperties.java           app.storage.kind = local | s3
  StorageConfig.java               selects the adapter; fails startup on a bad combination
```

`platform` is correct here and `document` would be wrong: the port names no domain type, and sub-project 5's agreements will want the same substrate. CLAUDE.md's rule is that `platform` must never name a domain type — `BlobStore` names none.

**Phase 3–5 create the module:**

```
backend/src/main/resources/db/migration/
  V<n>__document.sql               document, document_version, document_share,
                                   document_case_link, document_request,
                                   customer_contact.label,
                                   requirement_definition.requires_review

backend/src/main/java/co/ara/onboarding/document/
  Document.java  DocumentRepository.java  DocumentStatus.java  DocumentCategory.java
  VisibilityTier.java
  DocumentVersion.java  DocumentVersionRepository.java  ReviewStatus.java
  DocumentShare.java  DocumentShareRepository.java  SharePrincipalType.java
  DocumentCaseLink.java  DocumentCaseLinkRepository.java
  DocumentRequest.java  DocumentRequestRepository.java  DocumentRequestStatus.java
  DocumentService.java             upload, read, version append, PATCH, retire
  DocumentContentService.java      the streaming download; gated document.view
  DocumentSharingService.java      shares and cross-journey links
  DocumentRequestService.java      ad-hoc create, withdraw, fulfil
  DocumentReviewService.java       approve / reject, and satisfaction
  DocumentInstantiation.java       the RequirementKind.DOCUMENT seam
  DocumentController.java  DocumentRequestController.java  PortalDocumentController.java
  Document*Request/View records
  DocumentExceptionHandler.java    @RestControllerAdvice, in THIS module
```

**Phase 6 creates the frontend:**

```
frontend/src/lib/api/documents.ts          hooks + query keys
frontend/src/app/(app)/[slug]/documents/page.tsx      the `docs` screen
frontend/src/components/documents/
  DocumentTable.tsx  VisibilityCell.tsx  ScopeFilterRow.tsx  HiddenCountLine.tsx
  VisibilityAside.tsx  UploadDialog.tsx  RequestDocumentDialog.tsx  ReviewDialog.tsx
  DocumentsTab.tsx                        mounted in the case workspace
```

---

## Phase 0 — Baseline

### Task 1: Establish a green baseline across all three suites

**Files:** none created or modified. This task's deliverable is a recorded, trustworthy starting state.

**Interfaces:**
- Consumes: nothing.
- Produces: a written baseline every later task compares against. A failure appearing in Task 20 is only attributable to Task 20 if this task proved the suite was green first.

- [ ] **Step 1: Confirm Java runs at all**

```powershell
java -version
```

Expected: a version banner. If this reports an Application Control policy block, **stop and report** — CLAUDE.md records Java being blocked and unblocked on this machine within single days, and no backend task can proceed. Do not work around it.

- [ ] **Step 2: Confirm Docker is running**

```powershell
docker ps
```

Expected: a table header, even with no containers. Testcontainers needs this.

- [ ] **Step 3: Run the backend suite from clean**

```powershell
cd backend
.\gradlew.bat cleanTest test
```

Expected: `BUILD SUCCESSFUL`. Read the summary line, not a remembered count — CLAUDE.md is explicit that counts drift and a drifting number gets trusted. **`cleanTest` is not optional**: a bare `test` is marked UP-TO-DATE and prints `BUILD SUCCESSFUL` having executed nothing.

- [ ] **Step 4: Run the frontend unit suite**

```powershell
cd frontend
npx vitest run
```

Expected: all files passing.

- [ ] **Step 5: Run the type-check and lint that vitest does not**

```powershell
npx tsc --noEmit
npm run lint
```

Expected: both clean. 3A's close records these catching errors vitest is structurally blind to. If either is already red on `main`, **record it in the task report and stop** — inheriting a red baseline makes every later task's verification meaningless.

- [ ] **Step 6: Run the e2e suite against a scratch database**

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/onboarding_e2e_sp4"
npx playwright test
```

Expected: twelve specs passing. The harness starts both applications itself. Point it at a scratch database — it provisions a tenant per spec file and never truncates.

- [ ] **Step 7: Write the baseline report**

Record, in the task report: each suite's own summary line verbatim, the migration number currently highest in `backend/src/main/resources/db/migration/`, and the current `git rev-parse HEAD`. No commit — this task changes no files.

---

## Phase 1 — The authorization core

This phase changes a class every module depends on. It lands before anything else so that the rest of the sub-project builds on a mechanism already proven, rather than discovering at Task 20 that the mechanism does not work.

### Task 2: `AudienceFilter`, proven red under `ALL`

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/AudienceFilter.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/AudienceRegistry.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationPredicateBuilder.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/AudienceFilterTest.java`

**Interfaces:**
- Consumes: `AuthContext`, `AuthorizationService.effectivePermissions()`, `DescriptorRegistry` — all existing.
- Produces: `AudienceFilter<T>` with `Class<T> entityType()` and `Specification<T> audience(AuthContext ctx, String permissionKey)`; `AudienceRegistry.forEntity(Class<T>) → Optional<AudienceFilter<T>>`. Tasks 12 and 13 implement the interface; nothing else calls the registry directly.

- [ ] **Step 1: Write the failing test**

The test that matters is the one proving an `ALL`-scoped holder is narrowed. Everything else in this task is scaffolding for it.

Create `backend/src/test/java/co/ara/onboarding/authz/AudienceFilterTest.java`:

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of AudienceFilter is that it binds an actor holding the
 * permission at ALL -- which AuthorizationPredicateBuilder short-circuits past
 * before consulting any descriptor. A test that only exercises a narrow scope
 * would pass against the UNCHANGED builder and prove nothing.
 *
 * Customer is used as the subject rather than Document because this task lands
 * before the document module exists, and the mechanism must be proven before
 * anything depends on it.
 */
@Import(AudienceFilterTest.DenyEverythingAudience.class)
class AudienceFilterTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired CustomerRepository customers;
    @Autowired AuthorizedQuery authorizedQuery;

    @TestConfiguration
    static class DenyEverythingAudience {
        @Bean
        AudienceFilter<Customer> denyAllCustomers() {
            return new AudienceFilter<>() {
                @Override public Class<Customer> entityType() { return Customer.class; }
                @Override public Specification<Customer> audience(AuthContext ctx, String key) {
                    // Narrows to nothing for customer.view, and to everything for
                    // any other key -- the same permission-keyed shape
                    // DocumentAudienceFilter uses for document.manage.
                    return PermissionKeys.CUSTOMER_VIEW.equals(key)
                            ? (root, query, cb) -> cb.disjunction()
                            : (root, query, cb) -> cb.conjunction();
                }
            };
        }
    }

    @Test
    void anAllScopedHolderIsNarrowedByTheAudienceFilter() {
        UUID tenantId = fixture.createTenant("audience-all");
        var userId = new AtomicReference<UUID>();
        fixture.runAs(tenantId, () -> userId.set(fixture.createUser(tenantId, "admin@audience.test")));
        fixture.grantAtAllScope(tenantId, userId.get(), PermissionKeys.CUSTOMER_VIEW);
        fixture.runAs(tenantId, () -> fixture.createCustomer(tenantId, "Visible Co", null, null, null));

        fixture.runAsUser(tenantId, userId.get(), () -> {
            var page = authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_VIEW, null, org.springframework.data.domain.Pageable.unpaged());
            assertThat(page.getContent())
                    .as("an ALL-scoped holder must still be bound by the audience filter")
                    .isEmpty();
        });
    }

    @Test
    void aDifferentPermissionKeyIsNotNarrowed() {
        UUID tenantId = fixture.createTenant("audience-key");
        var userId = new AtomicReference<UUID>();
        fixture.runAs(tenantId, () -> userId.set(fixture.createUser(tenantId, "admin2@audience.test")));
        fixture.grantAtAllScope(tenantId, userId.get(), PermissionKeys.CUSTOMER_EDIT);
        fixture.runAs(tenantId, () -> fixture.createCustomer(tenantId, "Editable Co", null, null, null));

        fixture.runAsUser(tenantId, userId.get(), () -> {
            var page = authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_EDIT, null, org.springframework.data.domain.Pageable.unpaged());
            assertThat(page.getContent())
                    .as("the filter is keyed on permission; customer.edit is not narrowed")
                    .hasSize(1);
        });
    }
}
```

If `TenantFixture` has no `grantAtAllScope` helper, add one in this task following the shape of its existing role-granting code, self-binding via `runUnauthenticated` the same way `createAdminUser`/`createPlatformAdmin` already do (so the caller does not need to wrap the call in `runAs` itself).

**Amended after Task 2's implementation (found on a real run, not caught at plan-writing time):**
this snippet's `createUser(tenantId, "admin@audience.test")` call must run inside `fixture.runAs`
— `createUser`'s own javadoc requires it (an unbound insert fails RLS's `WITH CHECK`), and every
other call site in the suite already wraps it; the version above reflects that fix. Likewise,
`TenantFixture.createCustomer` takes **five** arguments
(`tenantId, displayName, ownerUserId, departmentId, teamId`), not four — the version above passes
the fifth (`teamId`) as `null` too. Both are scaffolding bugs in this task's own seed code, not in
`AudienceFilter`/`AudienceRegistry`/`AuthorizationPredicateBuilder`; see
`.superpowers/sdd/2026-09-12-documents/task-2-report.md` for the full detail.

- [ ] **Step 2: Run the test to verify it fails**

```powershell
cd backend
.\gradlew.bat cleanTest test --tests "*AudienceFilterTest*"
```

Expected: **compilation failure** — `AudienceFilter` does not exist. That is the correct first red.

- [ ] **Step 3: Create the interface**

`backend/src/main/java/co/ara/onboarding/authz/AudienceFilter.java`:

```java
package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;

/**
 * Declares, for one entity type, a MANDATORY predicate that narrows reads
 * regardless of the scope the actor holds the permission at -- including ALL.
 *
 * This exists because record scope and record audience are different questions.
 * Scope asks "which records of this type may this actor touch"; audience asks
 * "for this particular record, is this actor among its intended readers".
 * AuthorizationPredicateBuilder short-circuits scope to cb.conjunction() as soon
 * as the actor holds ALL, so an audience expressed as a descriptor predicate is
 * skipped by exactly the actor it most needs to bind (design spec 6.1).
 *
 * DELIBERATELY NOT A DEFAULT METHOD ON ResourceAuthorizationDescriptor.
 * DescriptorRegistry.forEntity throws on a miss, and the builder's ALL branch
 * returns before ever calling it -- so a default method would make that lookup
 * unconditional and turn every currently-working ALL-scoped read of a
 * descriptor-less entity into an IllegalStateException, across four modules, for
 * no gain. An opt-in registry returning Optional.empty() contributes nothing
 * where nothing is declared, leaves the nineteen existing descriptors untouched,
 * and makes the complete list of audience-governed types one grep for
 * "implements AudienceFilter".
 *
 * KEYED ON THE PERMISSION AS WELL AS THE ENTITY, and that is load-bearing rather
 * than incidental: document.manage must be able to load a document its holder
 * may not read, so that a mis-targeted document can be retargeted rather than
 * becoming permanently unreachable (spec 6.4). An entity-keyed filter would
 * apply to the read PATCH performs and make that impossible.
 *
 * Implementations live in `scoping`, like descriptors, and for the same reason:
 * placing one in the module owning the entity would close a module cycle.
 */
public interface AudienceFilter<T> {

    Class<T> entityType();

    /**
     * Must fail CLOSED on missing context, exactly as descriptors must: when the
     * AuthContext carries nothing to match on, return cb.disjunction(), never
     * cb.conjunction(). An audience that widens when its input is missing is
     * indistinguishable from no audience at all.
     */
    Specification<T> audience(AuthContext ctx, String permissionKey);
}
```

- [ ] **Step 4: Create the registry**

`backend/src/main/java/co/ara/onboarding/authz/AudienceRegistry.java`:

```java
package co.ara.onboarding.authz;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects AudienceFilter beans by entity type. Unlike DescriptorRegistry this
 * has no validate() and no startup failure: an audience is opt-in, so a type
 * with no filter is the normal case, not a misconfiguration.
 *
 * forEntity returns Optional rather than throwing. That difference from
 * DescriptorRegistry.forEntity is the entire reason this is a separate registry
 * -- see AudienceFilter's own javadoc.
 */
@Component
public class AudienceRegistry {

    private final Map<Class<?>, AudienceFilter<?>> byEntity = new HashMap<>();

    public AudienceRegistry(List<AudienceFilter<?>> filters) {
        for (var f : filters) {
            AudienceFilter<?> previous = byEntity.put(f.entityType(), f);
            if (previous != null) {
                // Two filters for one entity would silently mean "last bean wins",
                // and which one wins would depend on classpath order.
                throw new IllegalStateException(
                        "Two AudienceFilters registered for " + f.entityType().getName()
                                + ": " + previous.getClass().getName()
                                + " and " + f.getClass().getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<AudienceFilter<T>> forEntity(Class<T> entityType) {
        return Optional.ofNullable((AudienceFilter<T>) byEntity.get(entityType));
    }
}
```

- [ ] **Step 5: Wire it into the predicate builder**

Modify `AuthorizationPredicateBuilder`. The empty-scopes branch still returns first and untouched; the `ALL` branch no longer returns directly.

```java
@Component
public class AuthorizationPredicateBuilder {

    private final AuthorizationService authorization;
    private final AuthContextProvider contextProvider;
    private final DescriptorRegistry registry;
    private final AudienceRegistry audiences;

    public AuthorizationPredicateBuilder(AuthorizationService authorization,
                                         AuthContextProvider contextProvider,
                                         DescriptorRegistry registry,
                                         AudienceRegistry audiences) {
        this.authorization = authorization;
        this.contextProvider = contextProvider;
        this.registry = registry;
        this.audiences = audiences;
    }

    public <T> Specification<T> forPermission(String permissionKey, Class<T> entityType) {
        Set<Scope> scopes = authorization.effectivePermissions().scopesFor(permissionKey);

        // Fail closed: no grant means no rows, never all rows. Returns BEFORE the
        // audience lookup -- there is nothing to narrow, and a filter must never
        // be able to widen a denial.
        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();

        AuthContext ctx = contextProvider.current();
        Specification<T> scopePredicate = scopePredicate(scopes, entityType, ctx);

        // The audience is ANDed AFTER the scope union, and deliberately also in
        // the ALL case -- that is the whole mechanism (spec 6.2). Absent for every
        // entity that declares no filter, which is all of them but Document.
        return audiences.forEntity(entityType)
                .<Specification<T>>map(f -> scopePredicate.and(f.audience(ctx, permissionKey)))
                .orElse(scopePredicate);
    }

    private <T> Specification<T> scopePredicate(Set<Scope> scopes, Class<T> entityType, AuthContext ctx) {
        // ALL subsumes the others; short-circuit to an unconditional match rather
        // than OR-ing a match-all with narrower predicates. Note this no longer
        // returns from forPermission -- the audience still applies on top.
        if (scopes.contains(Scope.ALL)) return (root, query, cb) -> cb.conjunction();

        ResourceAuthorizationDescriptor<T> descriptor = registry.forEntity(entityType);

        Specification<T> combined = null;
        for (Scope scope : scopes) {
            Specification<T> part = switch (scope) {
                case DEPARTMENT -> descriptor.departmentScope(ctx);
                case TEAM       -> descriptor.teamScope(ctx);
                case ASSIGNED   -> descriptor.assignedScope(ctx);
                case ALL        -> null;   // unreachable, handled above
            };
            if (part == null) continue;
            // Scopes are SETS, not a hierarchy: union them (spec 6.3). A record
            // personally owned by the actor but belonging to someone else's team
            // qualifies under ASSIGNED even though TEAM excludes it.
            combined = (combined == null) ? part : combined.or(part);
        }
        return combined == null ? (root, query, cb) -> cb.disjunction() : combined;
    }
}
```

**Note the `contextProvider.current()` call moved earlier**, above the `ALL` short-circuit. Previously the `ALL` path never resolved an `AuthContext`. Confirm in Step 6 that `AuthContextProvider.current()` is safe to call for an ALL-scoped actor — if it throws for any actor that reaches here, that is a real finding: fix it, and record it as a plan defect.

- [ ] **Step 6: Run the new test and the whole suite**

```powershell
.\gradlew.bat cleanTest test --tests "*AudienceFilterTest*"
```

Expected: both tests PASS.

Then the full suite, because this modified a class every module depends on:

```powershell
.\gradlew.bat cleanTest test
```

Expected: `BUILD SUCCESSFUL`, matching Task 1's baseline. **Any new failure here is this task's, not a pre-existing one** — Task 1 exists to make that attributable.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/co/ara/onboarding/authz/ backend/src/test/java/co/ara/onboarding/authz/AudienceFilterTest.java backend/src/test/java/co/ara/onboarding/support/TenantFixture.java
git commit -m "feat(authz): add an opt-in AudienceFilter that binds ALL-scoped holders"
```

Body must state: why a separate interface rather than a default method on the descriptor (DescriptorRegistry.forEntity throws on a miss and the ALL branch returned before calling it), and that the test was seen red first.

### Task 3: Portal actors resolve a code-constant permission set

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationService.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/PortalPermissions.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/PortalAuthorityTest.java`

**Interfaces:**
- Consumes: `ActorDirectory` (already in `authz`), `UserType.PORTAL`, `AuthorizationService.effectivePermissions()`.
- Produces: `PortalPermissions.forContact()` and `PortalPermissions.forSponsor()`, each returning `Map<String, Scope>`. Task 13's audience filter relies on portal actors resolving `document.view` at `ALL`.

**Why this shape.** A portal actor resolves at `ALL` scope and the audience filter (Task 2) does every bit of the narrowing. That means no fifth `Scope` value, no descriptor learns anything new, and `RoleService`'s existing `PORTAL` refusal — "Portal users cannot hold internal roles" — stays exactly as it is. The risk it creates is real and is mitigated in Step 1: `document.view @ ALL` on an external user is only safe while the audience filter is present and correct, so the test that pins the constant set is not optional decoration.

**Amended after this task's own execution, in a post-implementation security review.** As originally
written below, both `forContact()` and `forSponsor()` also granted `case.view` (and `forSponsor()`
additionally `programme.view` and `plan.approve_schedule`) at `ALL`, on the claim that "the audience
filter does every bit of the narrowing." That claim was wrong: the audience filter this task and
Task 2 build (`DocumentAudienceFilter`, Tasks 12/13) only ever covers `Document`. `Case`, `Programme`
and `PlanRevision` have no `AudienceFilter` anywhere in this plan, so at `ALL` scope
`AuthorizationPredicateBuilder` matched every row tenant-wide for those three keys — a real,
concrete escalation: `PlanRevisionController.decide` resolves its target only by `revisionId` under
`plan.approve_schedule` and never checks its own `{caseId}` path variable against it, so a portal
contact of customer A marked `primaryContact=true` could have decided customer B's schedule
revision — an immutable write releasing B's held journey. The fix, made with an explicit human
ruling: `forContact()` and `forSponsor()` now carry ONLY `document.view` and `document.upload`, both
at `ALL`. `forSponsor()` stays a separate method from `forContact()` — today it is identical, on
purpose — as the seam sub-project 7 (Customer Portal) is expected to widen once it builds a real
narrowing mechanism for a sponsor's programme read and plan-approval authority (Q20, Q22's
customer-facing half); it is not built here. The code sample and test below are updated to the
corrected, shipped version, not the originally-planned one.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/co/ara/onboarding/security/PortalAuthorityTest.java`:

```java
package co.ara.onboarding.security;

import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import co.ara.onboarding.authz.InvalidGrantException;
import co.ara.onboarding.authz.RoleService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalAuthorityTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AuthorizationService authorization;
    @Autowired RoleService roleService;

    /**
     * The constant set is pinned so that widening it is a deliberate edit with a
     * failing test attached. A portal actor resolves document.view at ALL and is
     * narrowed entirely by DocumentAudienceFilter -- so an accidental addition
     * here is an accidental grant to every external user in every tenant.
     */
    @Test
    void aPortalActorResolvesExactlyTheExpectedConstantSet() {
        UUID tenantId = fixture.createTenant("portal-authority");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "sponsor@acme.test");

        fixture.runAsUser(tenantId, contactUserId, () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_UPLOAD)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).containsExactly(Scope.ALL);
            // Not granted, and must never be:
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_REVIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.USER_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.ROLE_MANAGE)).isEmpty();
        });
    }

    /**
     * A deactivated contact resolves nothing, on the very next request -- the
     * same property AuthorizationService already guarantees for a deactivated
     * internal user by joining app_user on status = 'ACTIVE'.
     */
    @Test
    void aRetiredContactResolvesNothing() {
        UUID tenantId = fixture.createTenant("portal-retired");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "gone@acme.test");
        fixture.retireContactFor(tenantId, contactUserId);

        fixture.runAsUser(tenantId, contactUserId, () ->
                assertThat(authorization.effectivePermissions()
                        .scopesFor(PermissionKeys.DOCUMENT_VIEW)).isEmpty());
    }

    /** RoleService's refusal is untouched by this design and must stay refused. */
    @Test
    void aPortalUserStillCannotBeAssignedAnInternalRole() {
        UUID tenantId = fixture.createTenant("portal-role");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "role@acme.test");
        UUID adminId = fixture.createAdministrator(tenantId, "admin@portal.test");
        UUID roleId = fixture.administratorRoleId(tenantId);

        assertThatThrownBy(() -> fixture.runAsUser(tenantId, adminId,
                () -> roleService.assignRole(contactUserId, roleId)))
                .hasRootCauseInstanceOf(InvalidGrantException.class);
    }
}
```

`TenantFixture` needs three new helpers in this task — `createPortalUserForContact`, `retireContactFor`, `runAsReturning` — each following the existing helpers' shape and each writing inside `runAs`, because the tables are RLS-protected. (Two more, `createAdministrator` and `administratorRoleId`, turned out to be needed too — the third test above calls both and neither existed; not an error in this list, just incomplete.)

**The test sample above predates this task's post-implementation security-review amendment** (see
"Why this shape" and the corrected `PortalPermissions` in Step 3) and must not be copied verbatim:
the shipped `PortalAuthorityTest` asserts the exact key set (`{document.view, document.upload}`,
nothing else) rather than a positive `case.view` grant, explicitly asserts `case.view`/
`programme.view`/`plan.approve_schedule` are ABSENT as a regression guard, adds a fourth test
exercising `forSponsor()` via a `primaryContact=true` contact (the three tests above never do, since
the plain `createPortalUserForContact` overload defaults `primaryContact` to `false`), and uses
`.isInstanceOf(InvalidGrantException.class)` rather than `.hasRootCauseInstanceOf(...)` in the third
test — confirmed empirically that `RoleService.assignRole` throws it with no wrapping cause, so
"root cause" resolves to nothing and the sample above fails as written.

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat cleanTest test --tests "*PortalAuthorityTest*"
```

Expected: compilation failure — `PermissionKeys.DOCUMENT_VIEW` does not exist yet. **Add the four document permission keys and catalog entries in this task** (Task 11 does the role seeding; the keys must exist here for the constant set to reference them). Then expect a real assertion failure: a portal actor currently resolves nothing.

- [ ] **Step 3: Create the constant set**

`backend/src/main/java/co/ara/onboarding/authz/PortalPermissions.java` — **this is the corrected,
shipped version**, not the one originally planned; see the amendment note above "Why this shape"
for what changed and why:

```java
package co.ara.onboarding.authz;

import java.util.Map;

/**
 * What a customer contact may do, as a code constant -- never a user_role row.
 *
 * Portal authority is NOT role-shaped. The PRD's external-user list is uniform
 * (view progress, upload requested documents, download agreements), and the
 * twelve seeded role templates are all internal. Letting contacts into role
 * assignment would reopen the escalation shape sub-project 1 fought three times,
 * and a single mis-seeded template would become a cross-company leak.
 *
 * Because this is a constant and not a grant, no administrator can create it and
 * no hand-edited database row can widen it. PortalAuthorityTest pins the exact
 * set so that widening it is a deliberate edit with a failing test attached.
 *
 * EVERY KEY HERE IS HELD AT ALL SCOPE, and is safe ONLY because
 * scoping.DocumentAudienceFilter narrows it. Adding a key whose entity type
 * declares no AudienceFilter grants that entity tenant-wide to every external
 * user. Do not add one without adding its filter in the same commit.
 *
 * REMOVED after this task's own security review: case.view, programme.view and
 * plan.approve_schedule. Case, Programme and PlanRevision have no AudienceFilter
 * anywhere in this plan, so at ALL scope those three were a real cross-customer
 * escalation, not a narrowed grant -- see this task's plan amendment for the
 * concrete finding. Portal case/programme/plan-approval access is real product
 * scope (Q20, Q22's customer-facing half) but belongs to sub-project 7, with its
 * own narrowing mechanism.
 */
public final class PortalPermissions {

    private PortalPermissions() {}

    public static Map<String, Scope> forContact() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL);
    }

    /**
     * Identical to forContact() today, deliberately -- the seam sub-project 7 is
     * expected to widen once it builds a real narrowing mechanism for a
     * sponsor's programme read and plan-approval authority (Q20, Q22).
     */
    public static Map<String, Scope> forSponsor() {
        return Map.of(
                PermissionKeys.DOCUMENT_VIEW,   Scope.ALL,
                PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL);
    }
}
```

(The originally-planned version of this file also carried `case.view` on both methods, plus
`programme.view` and a `PLAN_DECIDE`-keyed grant on `forSponsor()` — the executor's own note here
already flagged `PLAN_DECIDE` as unverified against `PermissionKeys`; it resolves to
`PLAN_APPROVE_SCHEDULE`, one of 3A's two catalogued schedule-gate permissions. Both the wrong key
name and the three now-removed keys are corrected above.)

- [ ] **Step 4: Resolve it in `AuthorizationService`**

In `effectivePermissions()`, branch on the actor's `UserType` **before** the existing role-join query. The portal branch must join the contact on `status = 'ACTIVE'` and the `app_user` on `status = 'ACTIVE'`, mirroring what the internal path already does — a retired contact must resolve nothing on the very next request, not when a token expires.

```java
// A PORTAL actor holds no roles by construction (RoleService refuses to assign
// one), so the role join would return empty and the actor would read nothing.
// Their authority is derived from the contact record instead, in code.
// Both status checks matter: the app_user check mirrors the internal path, and
// the contact check is what makes retirement take effect on the next request.
if (actor.userType() == UserType.PORTAL) {
    return contacts.findActiveContactForUser(actor.userId())
            .map(c -> new EffectivePermissions(
                    c.isSponsor() ? PortalPermissions.forSponsor() : PortalPermissions.forContact()))
            .orElseGet(EffectivePermissions::none);
}
```

`authz` must not import `customer`. Extend the existing `ActorDirectory` port — which `identity` already implements — with the contact lookup, or add a sibling port in `authz` implemented in `scoping`. **Do not import `customer` into `authz`**; `ModuleBoundaryTest` will catch it, but the design should not need catching.

- [ ] **Step 5: Run the tests**

```powershell
.\gradlew.bat cleanTest test --tests "*PortalAuthorityTest*"
```

Expected: all PASS (four tests in the shipped version — see the note on the test sample above).

- [ ] **Step 6: Run the full suite**

```powershell
.\gradlew.bat cleanTest test
```

Expected: `BUILD SUCCESSFUL`. `DescriptorRegistryTest` and the nine security negatives all exercise `effectivePermissions()`; a regression there is this task's.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/co/ara/onboarding/authz/ backend/src/test/java/co/ara/onboarding/security/PortalAuthorityTest.java backend/src/test/java/co/ara/onboarding/support/TenantFixture.java
git commit -m "feat(authz): derive portal authority from the contact record, never a grant"
```

Body must state that the set is pinned by test, that it is safe only because the audience filter narrows it, and that `RoleService`'s PORTAL refusal is deliberately untouched.

---

## Phase 2 — The storage substrate

No domain knowledge, no database, no authorization. This phase produces a port and two adapters that sub-project 5's agreements will reuse unchanged.

### Task 4: The `BlobStore` port, the local adapter, and a contract suite

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/storage/BlobStore.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/storage/LocalFsBlobStore.java`
- Create: `backend/src/main/java/co/ara/onboarding/platform/storage/StorageProperties.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/storage/BlobStoreContract.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/storage/LocalFsBlobStoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BlobStore` with `String put(InputStream content, long sizeBytes, String contentType)`, `InputStream open(String storageKey)`, `boolean exists(String storageKey)`. Tasks 5, 15 and 16 consume exactly these three signatures. `BlobStoreContract` is an abstract JUnit 5 test class Task 5 extends.

**Why `platform` and not `document`.** The port names no domain type, and CLAUDE.md's rule is that `platform` must never name one — `BlobStore`, `InputStream` and `String` satisfy that. Sub-project 5 will want the same substrate without depending on `document`.

- [ ] **Step 1: Write the contract as an abstract test**

Writing the contract *first*, as a shared abstract class, is what stops the two adapters from drifting. A port with one implementation is just an interface.

`backend/src/test/java/co/ara/onboarding/platform/storage/BlobStoreContract.java`:

```java
package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One contract, run against every adapter. Task 5's S3BlobStoreTest extends this
 * class and supplies a MinIO-backed store; LocalFsBlobStoreTest supplies a
 * temp-directory-backed one. Any behaviour asserted here must hold for both, or
 * the port is leaking implementation differences into its callers.
 */
public abstract class BlobStoreContract {

    protected abstract BlobStore store();

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws Exception {
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void putThenOpenRoundTripsTheExactBytes() throws Exception {
        String body = "hello éàü 📄";  // non-ASCII on purpose
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String key = store().put(new ByteArrayInputStream(raw), raw.length, "text/plain");

        assertThat(read(store().open(key))).isEqualTo(body);
    }

    @Test
    void everyPutReturnsADistinctKeyEvenForIdenticalContent() {
        String a = store().put(bytes("same"), 4, "text/plain");
        String b = store().put(bytes("same"), 4, "text/plain");

        assertThat(a).isNotEqualTo(b);
    }

    /**
     * Keys are generated, never derived from caller input. Nothing the caller
     * supplies -- filename, document name, tenant string -- may reach a
     * filesystem path or an object name (spec 7.2).
     */
    @Test
    void keysContainNoPathTraversalAndNoCallerSuppliedText() {
        String key = store().put(bytes("x"), 1, "text/plain");

        assertThat(key).doesNotContain("..").doesNotContain("\\");
        assertThat(key).matches("[A-Za-z0-9/_-]+");
    }

    @Test
    void existsIsTrueForAStoredKeyAndFalseOtherwise() {
        String key = store().put(bytes("x"), 1, "text/plain");

        assertThat(store().exists(key)).isTrue();
        assertThat(store().exists("definitely-not-a-key")).isFalse();
    }

    @Test
    void openingAnUnknownKeyThrowsRatherThanReturningEmpty() {
        assertThatThrownBy(() -> store().open("definitely-not-a-key"))
                .isInstanceOf(BlobNotFoundException.class);
    }

    /**
     * The port has NO delete, deliberately (spec 7.1). This test is the guard:
     * it fails to compile if someone adds one, which is the point.
     */
    @Test
    void thePortExposesNoDeleteOperation() {
        assertThat(BlobStore.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("delete", "remove", "purge");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd backend
.\gradlew.bat cleanTest test --tests "*LocalFsBlobStoreTest*"
```

Expected: compilation failure — neither `BlobStore` nor `LocalFsBlobStoreTest` exists.

- [ ] **Step 3: Write the port**

`backend/src/main/java/co/ara/onboarding/platform/storage/BlobStore.java`:

```java
package co.ara.onboarding.platform.storage;

import java.io.InputStream;

/**
 * Where document bytes live. Two adapters, one contract (BlobStoreContract).
 *
 * THERE IS NO delete, AND THAT IS THE DESIGN (spec 7.1). Business records are
 * never deleted -- the database enforces it by withholding the DELETE grant, and
 * this interface states the same thing for bytes by simply not offering the
 * operation. Retiring a document keeps its blob. A delete path belongs with QA
 * Q11's retention rules, designed alongside them, not added here as an unused
 * method someone later finds convenient.
 *
 * Keys are OPAQUE and generated by the adapter. Callers never construct one and
 * never parse one; nothing a user supplied may reach a path or an object name.
 */
public interface BlobStore {

    /**
     * @param sizeBytes the caller's declared length. Adapters may use it to choose
     *                  a transfer strategy but must not trust it for correctness.
     * @return the generated storage key, to be persisted on document_version.
     */
    String put(InputStream content, long sizeBytes, String contentType);

    /** @throws BlobNotFoundException when the key is unknown. Never returns null. */
    InputStream open(String storageKey);

    boolean exists(String storageKey);
}
```

Add `BlobNotFoundException` (a `RuntimeException`) in the same package.

- [ ] **Step 4: Write the local adapter**

`LocalFsBlobStore` stores under `StorageProperties.localRoot`, sharding by the first four characters of the generated key so no single directory accumulates every blob. Key generation:

```java
/**
 * A UUIDv7 rendered lowercase hexadecimal (no dashes), sharded two levels. UUIDv7
 * rather than SecureRandom because a storage key need only be unique, not
 * unpredictable -- access is always mediated by the application (spec 7.3), never
 * by key secrecy. CLAUDE.md's rule is that values needing unpredictability use
 * SecureRandom; this is explicitly not one of them, and saying so here stops a
 * future reader "fixing" it.
 */
private String newKey() {
    String flat = Uuid7.generate().toString().replace("-", "");
    return flat.substring(0, 2) + "/" + flat.substring(2, 4) + "/" + flat;
}
```

<!-- Corrected during Task 4's own review (2026-09-12): this comment originally said
"base32-lowercase," which was wrong -- the code below it was never base32, only the
words describing it were; UUID.toString() is hex, and stripping its dashes stays
hex. The encoding, sharding scheme and SecureRandom-vs-UUIDv7 reasoning are all
unchanged; only the wrong word was replaced, here and in LocalFsBlobStore.java. -->

`put` must write to a temporary file in the same directory and then atomically move it into place, so a crashed upload never leaves a partially-written blob readable under its final key.

- [ ] **Step 5: Write the test that binds the contract to this adapter**

```java
package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalFsBlobStoreTest extends BlobStoreContract {

    @TempDir static Path root;

    private final BlobStore store = new LocalFsBlobStore(root);

    @Override protected BlobStore store() { return store; }
}
```

- [ ] **Step 6: Run the contract against the local adapter**

```powershell
.\gradlew.bat cleanTest test --tests "*LocalFsBlobStoreTest*"
```

Expected: all six contract tests PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/co/ara/onboarding/platform/storage/ backend/src/test/java/co/ara/onboarding/platform/storage/
git commit -m "feat(platform): add the BlobStore port and its local-filesystem adapter"
```

Body must state why the port has no `delete` and why keys use `Uuid7` rather than `SecureRandom`.

### Task 5: The S3 adapter, against a real MinIO container

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/co/ara/onboarding/platform/storage/S3BlobStore.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/storage/S3BlobStoreTest.java`

**Interfaces:**
- Consumes: `BlobStore`, `BlobStoreContract`, `BlobNotFoundException` from Task 4.
- Produces: `S3BlobStore(S3Client client, String bucket)`.

- [ ] **Step 1: Add the dependencies**

In `backend/build.gradle.kts`:

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.29.52"))
implementation("software.amazon.awssdk:s3")
testImplementation("org.testcontainers:minio")
```

`org.testcontainers` is pinned to `1.21.4` in `extra["testcontainers.version"]` because Boot 3.4.1's managed 1.20.4 cannot negotiate with current Docker Desktop API versions. **Do not revert that pin**; the `minio` module resolves through the same pin.

- [ ] **Step 2: Write the failing test**

```java
package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * The S3 adapter is exercised against a REAL object store, not a mock. A mocked
 * S3Client would assert that this class calls the SDK the way the author
 * imagined, which is the one thing that is never in doubt. MinIO is
 * S3-compatible, so this same adapter covers AWS S3, MinIO, R2 and friends.
 */
@Testcontainers
class S3BlobStoreTest extends BlobStoreContract {

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z");

    private static S3BlobStore store;

    @BeforeAll
    static void setUp() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)   // MinIO does not do virtual-host addressing
                .build();
        client.createBucket(CreateBucketRequest.builder().bucket("documents").build());
        store = new S3BlobStore(client, "documents");
    }

    @Override protected BlobStore store() { return store; }
}
```

- [ ] **Step 3: Run to verify it fails**

```powershell
.\gradlew.bat cleanTest test --tests "*S3BlobStoreTest*"
```

Expected: compilation failure — `S3BlobStore` does not exist.

- [ ] **Step 4: Write the adapter**

Key generation is identical to the local adapter's, so extract it to a package-private `StorageKeys.newKey()` used by both rather than duplicating it — two adapters producing differently-shaped keys would pass the contract individually and still be a latent migration problem.

`open` must translate `NoSuchKeyException` into `BlobNotFoundException`, so the contract's "unknown key throws" test passes for the same reason on both adapters rather than by coincidence.

- [ ] **Step 5: Run the contract against the S3 adapter**

```powershell
.\gradlew.bat cleanTest test --tests "*S3BlobStoreTest*"
```

Expected: the same six contract tests PASS, now against MinIO. If `keysContainNoPathTraversalAndNoCallerSuppliedText` passes here but the round-trip fails, suspect `forcePathStyle`.

- [ ] **Step 6: Commit**

```powershell
git add backend/build.gradle.kts backend/src/main/java/co/ara/onboarding/platform/storage/ backend/src/test/java/co/ara/onboarding/platform/storage/
git commit -m "feat(platform): add the S3 blob adapter, verified against MinIO"
```

### Task 6: Adapter selection, and a startup guard that fails closed

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/storage/StorageConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/co/ara/onboarding/platform/storage/StorageConfigTest.java`

**Interfaces:**
- Consumes: `LocalFsBlobStore`, `S3BlobStore`, `StorageProperties`.
- Produces: exactly one `BlobStore` bean, selected by `app.storage.kind`.

**Why this task exists separately.** `JWT_SECRET` and `DB_APP_PASSWORD` both taught the same lesson in sub-project 1: configuration that silently falls back to a default is configuration that the deployment which forgot it will use. Storage is the same shape — an `s3` deployment that silently falls back to the local filesystem would write documents to a container's ephemeral disk and lose them on restart, with no error anywhere.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anUnknownStorageKindRefusesToStart() { /* expect IllegalStateException naming app.storage.kind */ }

@Test
void s3KindWithNoBucketConfiguredRefusesToStart() { /* names app.storage.s3.bucket */ }

@Test
void localKindWithNoRootConfiguredRefusesToStart() { /* names app.storage.local.root */ }

@Test
void localKindSelectsTheLocalAdapter() { /* assertThat(bean).isInstanceOf(LocalFsBlobStore.class) */ }
```

Each message must **name the property**, the way `JwtProperties` names its variable — a startup failure that does not say which key is wrong costs a deployment an hour.

- [ ] **Step 2: Run to verify they fail**

```powershell
.\gradlew.bat cleanTest test --tests "*StorageConfigTest*"
```

- [ ] **Step 3: Implement `StorageConfig`**

There is **no default for `app.storage.kind`**. Unset is a startup failure, not an implied `local`.

- [ ] **Step 4: Set `local` for dev and test profiles**

In `application.yml`, set `app.storage.kind: local` and a root under the build directory for the `dev` and `test` profiles only. The default profile gets no value, so a production deployment must choose.

- [ ] **Step 5: Run the tests, then the full suite**

```powershell
.\gradlew.bat cleanTest test --tests "*StorageConfigTest*"
.\gradlew.bat cleanTest test
```

Expected: both green. The full suite matters here because every `@SpringBootTest` now instantiates a `BlobStore` bean — a misconfigured test profile fails every one of them at once.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/co/ara/onboarding/platform/storage/ backend/src/main/resources/application.yml backend/src/test/java/co/ara/onboarding/platform/storage/StorageConfigTest.java
git commit -m "feat(platform): select the blob adapter by config, with no silent default"
```

---

## Phase 3 — The deferred decision, resolved

### Task 7: Resolve and record the upload-hardening decision

**Files:**
- Modify: `docs/superpowers/specs/2026-09-12-documents-design.md` (§2.3 and §7.6)
- Modify: `CLAUDE.md` (the deferred-decision bullet under *What sub-project 4 inherits*)
- Modify: this plan, if the ruling adds or removes work in Tasks 15 or 26

**Interfaces:**
- Consumes: the spec's §2.3 enumeration.
- Produces: a resolved ruling that Tasks 15 (upload) and 26 (portal upload) implement. **No task after this one may ship an upload endpoint while §2.3 still reads "open".**

**This task writes no code and must not be skipped.** It is a blocking checkpoint. The spec states it plainly: *an upload endpoint that ships without an explicit ruling has made the ruling by default.* The decision was deferred during brainstorming at the user's instruction, not decided against.

- [ ] **Step 1: Present the decision to the human partner**

Do **not** decide this autonomously. Present the enumeration with its cost and what each buys, and ask for a ruling on each:

| Defence | Buys | Costs |
|---|---|---|
| Size ceiling | Bounds memory and storage per request | One config property, one 413 path |
| Sniffed MIME allowlist | Closes "a `.pdf` that is really an HTML page" | A content-detection dependency; rejects some legitimate odd files |
| Filename sanitisation | Already closed by opaque keys (§7.2) — confirm, don't rebuild | — |
| `Content-Disposition: attachment` | Closes stored-XSS-on-download | One header; breaks nothing |
| Malware scanning | Real protection for external uploads | A ClamAV sidecar, an async `UPLOADED→SCANNING→CLEAN/QUARANTINED` state machine, a visible pending state across the UI, and a new container in the test stack |
| Per-version SHA-256 | Integrity, duplicate detection, provable version identity for sub-project 5 | One column, one digest stream |
| Per-tenant byte quota | Stops one tenant filling shared storage | One counter, one 507 path, and a backfill question for existing tenants |

State clearly which two are already settled and **not** open: opaque generated keys (Task 4) and streaming-through-the-application (Task 15), both forced by other decisions.

- [ ] **Step 2: Record the ruling in the spec**

Rewrite §2.3 and §7.6 so each row above reads as a decision with a reason, not an option. Anything ruled out must say *why* and what would change the answer — "out for now" with no reason is how a deferred decision becomes a forgotten one.

- [ ] **Step 3: Update CLAUDE.md**

Replace the deferred-decision bullet under *What sub-project 4 inherits* with the ruling. The bullet currently instructs a future reader to resolve this; leaving it in place after resolving it would send someone to re-litigate a closed decision.

- [ ] **Step 4: Amend this plan if the ruling changes the work**

If malware scanning is in, Tasks 15 and 26 gain a state machine and the plan gains a task for the scanner container. If a size ceiling is in, Task 15 gains a 413 test. Write those changes into this file now, while the ruling is fresh — a plan that lags its own decisions is the defect CLAUDE.md's *Plan deviations* section exists to catch.

- [ ] **Step 5: Commit**

```powershell
git add docs/superpowers/specs/2026-09-12-documents-design.md CLAUDE.md docs/superpowers/plans/2026-09-12-documents.md
git commit -m "docs: resolve the upload-hardening decision deferred at brainstorming"
```

Body must list each defence and its ruling, so the reasoning is in the history rather than only in the current text of the spec.

---

## Phase 4 — The `document` module

### Task 8: The migration, entities and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V<n>__document.sql`
- Create: the eleven entity/enum/repository files listed under *File structure*
- Test: `backend/src/test/java/co/ara/onboarding/document/DocumentSchemaTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `Uuid7`.
- Produces: `Document`, `DocumentVersion`, `DocumentShare`, `DocumentCaseLink`, `DocumentRequest` and their repositories, each extending `JpaRepository<T, UUID>` **and `JpaSpecificationExecutor<T>`** — `AuthorizedQuery` requires the latter and a repository missing it fails at the first scoped read, not at compile time.

- [ ] **Step 1: Find the next migration number**

```powershell
ls backend/src/main/resources/db/migration/ | Sort-Object Name
```

`V22` is highest as this plan is written. Use the next unused number. **Never edit a committed migration.**

- [ ] **Step 2: Write the failing schema test**

`RlsCoverageTest` is deny-by-default over the live schema and will fail on its own once the tables exist without policies — that is the guard, and it must be seen red. Write an additional test pinning the parts `RlsCoverageTest` does not cover:

```java
package co.ara.onboarding.document;

/**
 * RlsCoverageTest already proves tenant_id + RLS + FORCE on every new table, and
 * the revoked-DELETE migration proves deletion is denied. This test covers what
 * those do not: the CHECK constraints that encode domain rules at the database,
 * and document_version's immutability trigger.
 */
class DocumentSchemaTest extends PostgresTestBase {

    @Test
    void aSecondVersionWithTheSameNumberIsRejected() { /* unique (document_id, version_no) → DataIntegrityViolationException */ }

    @Test
    void anUnknownVisibilityTierIsRejected() { /* document_tier_ck */ }

    @Test
    void aContactOnlyDocumentWithNoOwnerContactIsRejected() { /* document_owner_ck */ }

    @Test
    void updatingAnImmutableVersionColumnIsRejected() {
        // The review columns MUST remain updatable; storage_key, size_bytes,
        // content_type, version_no and document_id must not. A blanket UPDATE
        // revoke would block review, so this is a trigger, not a grant.
    }

    @Test
    void deletingADocumentIsDeniedAtTheDatabase() { /* no GRANT DELETE */ }
}
```

- [ ] **Step 3: Run to verify it fails**

```powershell
.\gradlew.bat cleanTest test --tests "*DocumentSchemaTest*"
```

Expected: failure — the tables do not exist.

- [ ] **Step 4: Write the migration**

```sql
-- Sub-project 4, Task 8: the document substrate. Documents are scoped per journey
-- (PRD section 10), never per account; document_case_link is the explicit
-- cross-journey share that rule requires. Visibility is TWO axes -- tier (how
-- broadly) and targeting (which group) -- per QA Q9 and its 2026-08-29 amendment.

CREATE TABLE document (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    case_id              uuid NOT NULL REFERENCES onboarding_case(id),
    -- Denormalised from the case. Safe because a case never changes customer, and
    -- it is what lets the portal audience predicate avoid a join on every read.
    customer_id          uuid NOT NULL REFERENCES customer(id),
    name                 text NOT NULL,
    category             text NOT NULL,
    visibility_tier      text NOT NULL,
    target_department_id uuid     NULL REFERENCES department(id),
    target_contact_label text     NULL,
    owner_contact_id     uuid     NULL REFERENCES customer_contact(id),
    expires_at           timestamptz,
    status               text NOT NULL,
    current_version_id   uuid     NULL,
    uploaded_by          uuid NOT NULL REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT document_category_ck CHECK (category IN
        ('CONTRACT','AGREEMENT','NDA','COMPANY_REGISTRATION','TAX','KYC',
         'TECHNICAL','CERTIFICATE','INVOICE','OTHER')),
    CONSTRAINT document_tier_ck CHECK (visibility_tier IN
        ('COMPANY_SHARED','CONTACT_ONLY','SENSITIVE')),
    CONSTRAINT document_status_ck CHECK (status IN ('ACTIVE','RETIRED')),
    -- A CONTACT_ONLY document with no owner is visible to no contact at all,
    -- which is a SENSITIVE document wearing the wrong label. Refuse it here
    -- rather than let the audience predicate silently return nothing.
    CONSTRAINT document_owner_ck CHECK (
        visibility_tier <> 'CONTACT_ONLY' OR owner_contact_id IS NOT NULL)
);
CREATE INDEX document_tenant_case_idx     ON document (tenant_id, case_id);
CREATE INDEX document_tenant_customer_idx ON document (tenant_id, customer_id);
CREATE INDEX document_tenant_expiry_idx   ON document (tenant_id, expires_at)
    WHERE expires_at IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE document_version (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    document_id   uuid NOT NULL REFERENCES document(id),
    version_no    int  NOT NULL,
    storage_key   text NOT NULL,
    size_bytes    bigint NOT NULL,
    content_type  text NOT NULL,
    review_status text NOT NULL,
    reviewed_by   uuid     NULL REFERENCES app_user(id),
    reviewed_at   timestamptz,
    review_note   text,
    uploaded_by   uuid NOT NULL REFERENCES app_user(id),
    uploaded_at   timestamptz NOT NULL,
    CONSTRAINT document_version_review_ck CHECK (review_status IN
        ('PENDING','APPROVED','REJECTED')),
    -- Two clients racing a new version resolve as a 409 rather than needing a row
    -- lock: appending a version derives no state, unlike CaseEngine.reconcile.
    CONSTRAINT document_version_no_uq UNIQUE (document_id, version_no)
);

-- Versions are immutable in their CONTENT, mutable in their REVIEW OUTCOME.
-- A blanket UPDATE revoke would block review, so immutability is a trigger.
CREATE OR REPLACE FUNCTION document_version_immutable() RETURNS trigger AS $$
BEGIN
    IF NEW.document_id  IS DISTINCT FROM OLD.document_id
    OR NEW.version_no   IS DISTINCT FROM OLD.version_no
    OR NEW.storage_key  IS DISTINCT FROM OLD.storage_key
    OR NEW.size_bytes   IS DISTINCT FROM OLD.size_bytes
    OR NEW.content_type IS DISTINCT FROM OLD.content_type
    OR NEW.uploaded_by  IS DISTINCT FROM OLD.uploaded_by
    OR NEW.uploaded_at  IS DISTINCT FROM OLD.uploaded_at THEN
        RAISE EXCEPTION 'document_version content is immutable once written';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_version_immutable_trg
    BEFORE UPDATE ON document_version
    FOR EACH ROW EXECUTE FUNCTION document_version_immutable();

CREATE TABLE document_share (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    document_id    uuid NOT NULL REFERENCES document(id),
    principal_type text NOT NULL,
    principal_id   uuid NOT NULL,
    granted_by     uuid NOT NULL REFERENCES app_user(id),
    granted_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    CONSTRAINT document_share_principal_ck CHECK (principal_type IN
        ('CONTACT','USER','DEPARTMENT'))
);
-- Revocation is a column, not a DELETE: who could once see a document is part of
-- the record, and DELETE is denied at the database anyway.
CREATE UNIQUE INDEX document_share_live_uq
    ON document_share (document_id, principal_type, principal_id)
    WHERE revoked_at IS NULL;

CREATE TABLE document_case_link (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    document_id uuid NOT NULL REFERENCES document(id),
    case_id     uuid NOT NULL REFERENCES onboarding_case(id),
    linked_by   uuid NOT NULL REFERENCES app_user(id),
    linked_at   timestamptz NOT NULL,
    revoked_at  timestamptz
);
CREATE UNIQUE INDEX document_case_link_live_uq
    ON document_case_link (document_id, case_id) WHERE revoked_at IS NULL;

CREATE TABLE document_request (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    -- NULL is ad-hoc, non-null is requirement-instantiated. Exactly task's shape.
    requirement_id          uuid     NULL REFERENCES requirement(id),
    requested_of_contact_id uuid     NULL REFERENCES customer_contact(id),
    category                text NOT NULL,
    description             text,
    due_at                  timestamptz,
    requires_review         boolean NOT NULL DEFAULT false,
    status                  text NOT NULL,
    fulfilled_document_id   uuid     NULL REFERENCES document(id),
    requested_by            uuid NOT NULL REFERENCES app_user(id),
    requested_at            timestamptz NOT NULL,
    CONSTRAINT document_request_status_ck CHECK (status IN
        ('OPEN','FULFILLED','WITHDRAWN')),
    CONSTRAINT document_request_fulfilled_ck CHECK (
        status <> 'FULFILLED' OR fulfilled_document_id IS NOT NULL)
);
-- A requirement is satisfied by at most one document request, mirroring
-- task_requirement_uq.
CREATE UNIQUE INDEX document_request_requirement_uq
    ON document_request (requirement_id) WHERE requirement_id IS NOT NULL;
CREATE INDEX document_request_tenant_case_idx ON document_request (tenant_id, case_id, status);

-- QA Q9's amendment: customer-side targeting labels, set by internal staff.
ALTER TABLE customer_contact ADD COLUMN label text;

-- Design spec section 5.3: whether fulfilling this requirement needs review
-- before it satisfies. Nullable; NULL reads as false, so every existing frozen
-- row keeps its current meaning.
ALTER TABLE requirement_definition ADD COLUMN requires_review boolean;

SELECT enable_tenant_rls('document');
SELECT enable_tenant_rls('document_version');
SELECT enable_tenant_rls('document_share');
SELECT enable_tenant_rls('document_case_link');
SELECT enable_tenant_rls('document_request');

GRANT SELECT, INSERT, UPDATE ON document            TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_version    TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_share      TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_case_link  TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON document_request    TO onboarding_app;
```

Confirm the exact name of the requirement-definition table before writing that `ALTER` — read `V12`/`V13` rather than trusting this plan's spelling.

- [ ] **Step 5: Write the entities and repositories**

Every entity extends `TenantScopedEntity`. Every repository extends both `JpaRepository<T, UUID>` and `JpaSpecificationExecutor<T>`.

- [ ] **Step 6: Run the schema test and `RlsCoverageTest`**

```powershell
.\gradlew.bat cleanTest test --tests "*DocumentSchemaTest*" --tests "*RlsCoverageTest*"
```

Expected: both PASS. `RlsCoverageTest`'s allowlist must still have **four** entries — if you added a fifth to make it pass, revert it and fix the migration instead.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/document/ backend/src/test/java/co/ara/onboarding/document/
git commit -m "feat(document): add the document schema, entities and repositories"
```

### Task 9: The three module-boundary rules, proven red

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: the `document` package from Task 8.
- Produces: three named rules every later task is bound by.

**These must be seen red.** CLAUDE.md is explicit: a structural guard you have never seen fail is a guard you cannot trust.

- [ ] **Step 1: Add the three rules**

```java
@ArchTest
static final ArchRule noJourneyDependencyOnDocument =
        noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..document..")
                .because("document depends on journey, never the reverse. A one-way import "
                       + "would still pass the plain no-cycles rule, which is why this is its "
                       + "own named rule -- the same reasoning as noJourneyDependencyOnCustomer. "
                       + "There is deliberately NO DocumentDirectory port (spec 3.2).");

@ArchTest
static final ArchRule noWorkflowDependencyOnDocument = /* ..workflow.. */ ;

@ArchTest
static final ArchRule noTaskDependencyOnDocument = /* ..task.. */ ;
```

- [ ] **Step 2: Prove each red with a temporary violation**

Add a temporary import of `co.ara.onboarding.document.Document` into one class in `journey`, run `ModuleBoundaryTest`, confirm `noJourneyDependencyOnDocument` **fails**, then revert. Repeat for `workflow` and `task` — three separate red observations, not one generalised from the others.

```powershell
.\gradlew.bat cleanTest test --tests "*ModuleBoundaryTest*"
```

- [ ] **Step 3: Confirm green after reverting all three**

- [ ] **Step 4: Commit**

```powershell
git add backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java
git commit -m "test(arch): forbid journey, workflow and task from importing document"
```

Body must record that each rule was seen red individually, and name the class each temporary violation was added to.

### Task 10: The five descriptors

**Files:**
- Create: `DocumentDescriptor`, `DocumentVersionDescriptor`, `DocumentShareDescriptor`, `DocumentCaseLinkDescriptor`, `DocumentRequestDescriptor` in `backend/src/main/java/co/ara/onboarding/scoping/`
- Test: `backend/src/test/java/co/ara/onboarding/scoping/DocumentScopingTest.java`

**Interfaces:**
- Consumes: `ResourceAuthorizationDescriptor`, `AuthContext`, the entities from Task 8.
- Produces: registry coverage for five entity types.

**Why five when the catalog only validates one.** `DescriptorRegistry.validate()` requires a descriptor per *record-scoped permission's resource type*, which is `document` alone. But `AuthorizedQuery.findAll`/`getById` dispatch by **entity type**, and `DescriptorRegistry.forEntity` **throws** on a miss. Every one of the five entities is read through `AuthorizedQuery`, so all five need descriptors — and `validate()` will stay green while a missing one fails at the first request that reads that entity. This is exactly the trap `CaseParticipantDescriptor` and `CaseAttributeValueDescriptor` were added to close.

- [ ] **Step 1: Write the failing test**

Assert, for each of the five entity types, that `DescriptorRegistry.forEntity` returns a descriptor rather than throwing; and that each descriptor **fails closed** — `departmentScope` with a null department and `teamScope` with empty teams both match nothing.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Write the descriptors**

All five inherit scope from the case they belong to, the `viaCase` shape `TaskDescriptor` and `MilestoneDescriptor` already use. `DocumentDescriptor.assignedScope` resolves through the document's `uploaded_by` — a personal relationship, never team-mediated, per the `RelationshipType` invariant.

`DocumentVersionDescriptor`, `DocumentShareDescriptor` and `DocumentCaseLinkDescriptor` resolve through their parent `document`'s case, one subquery further out.

- [ ] **Step 4: Run the test, then the full suite**

The full suite matters: `DescriptorRegistryTest` asserts coverage and a duplicate registration now throws in `AudienceRegistry` too.

- [ ] **Step 5: Commit**

```powershell
git commit -m "feat(scoping): add the five document descriptors, all failing closed"
```

### Task 11: Role-template seeding for the six document permissions

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/RoleTemplates.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/RoleTemplateCoverageTest.java`

**Interfaces:**
- Consumes: the permission keys added in Task 3.
- Produces: seeded grants. Tasks 21 and 35 build test roles on these.

**Seeding is a deliberate act here.** CLAUDE.md records the same finding twice — `approval.decide` seeded to Administrator only, then `task.manage` seeded to Administrator only, both flagged as needing review before anything built on them. A third occurrence would be a pattern rather than an accident.

- [ ] **Step 1: Write the failing test**

Assert that each of the six keys is held by **at least one template other than Administrator**, and specifically that `document.review` is held by Legal, Finance and Compliance — the three role names Q9's own department list names.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Seed**

| Key | Templates |
|---|---|
| `document.view` | Account Manager · Project Manager (TEAM) · Operations · Legal · Finance · Technical · Compliance · Support · Sales Representative (ASSIGNED) · Service Provider (ASSIGNED) · Business Partner (ASSIGNED) |
| `document.upload` | Account Manager · Project Manager (TEAM) · Operations · Technical · Service Provider (ASSIGNED) |
| `document.manage` | Account Manager · Project Manager (TEAM) · Operations |
| `document.review` | **Legal · Finance · Compliance** |
| `document.share` | Account Manager · Project Manager (TEAM) |
| `document.request` | Account Manager · Project Manager (TEAM) · Operations |

`Administrator` holds all six at `ALL`, as it holds everything.

- [ ] **Step 4: Run the test, then the full suite**

- [ ] **Step 5: Commit**

```powershell
git commit -m "feat(authz): seed the six document permissions beyond Administrator"
```

Body must state that this deliberately avoids repeating the `approval.decide`/`task.manage` Administrator-only pattern, and give the reasoning for `document.review`'s three roles.

### Task 12: `DocumentAudienceFilter` — the internal half

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/scoping/DocumentAudienceFilter.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/DocumentAudienceTest.java`

**Interfaces:**
- Consumes: `AudienceFilter` (Task 2), `Document`, `DocumentShare`.
- Produces: the filter bean. Task 13 extends the same class with the portal branch.

- [ ] **Step 1: Write the failing tests — the ALL case first**

The test that matters is the one an ordinary scope test would never write:

```java
/**
 * The whole point of section 6.4: targeting binds EVERYONE. A Finance user and
 * the tenant Administrator are refused a Legal-targeted document alike. Without
 * this, department targeting is a convenience filter, not a confidentiality
 * boundary.
 */
@Test
void anAdministratorAtAllScopeCannotReadALegalTargetedDocument() { }

@Test
void anUntargetedDocumentIsVisibleToAnyoneWithTheScope() { }

@Test
void anExplicitShareToMyDepartmentWidensPastTargeting() { }

/**
 * document.manage is deliberately NOT narrowed -- otherwise a mis-targeted
 * document becomes permanently unreachable and unfixable (spec 6.4).
 */
@Test
void documentManageLoadsATargetedDocumentThatDocumentViewCannotRead() { }

/**
 * ...but metadata is not bytes. The manage holder still cannot download.
 */
@Test
void documentManageCannotReadTheContentOfATargetedDocument() { }
```

- [ ] **Step 2: Run to verify they fail**

Expected: the ALL test fails by returning the document — proving the filter is absent, which is what Task 2's mechanism exists to fix.

- [ ] **Step 3: Implement the internal branch**

```java
@Override
public Specification<Document> audience(AuthContext ctx, String permissionKey) {
    // document.manage grants the administrative handle, never the bytes. Without
    // this branch an ALL-scoped manage holder could not load a document to
    // RETARGET it, so a document targeted at a department that later empties out
    // would be unreachable and unfixable, permanently (spec 6.4). The content
    // endpoint is gated document.view, which IS narrowed -- so this widens the
    // handle without widening the payload.
    if (PermissionKeys.DOCUMENT_MANAGE.equals(permissionKey)) {
        return (root, query, cb) -> cb.conjunction();
    }
    return ctx.userType() == UserType.PORTAL ? portalAudience(ctx) : internalAudience(ctx);
}

private Specification<Document> internalAudience(AuthContext ctx) {
    return (root, query, cb) -> cb.or(
            // Untargeted, or targeted at my department.
            cb.or(cb.isNull(root.get("targetDepartmentId")),
                  ctx.departmentId() == null
                          ? cb.disjunction()      // fail closed: no department matches no target
                          : cb.equal(root.get("targetDepartmentId"), ctx.departmentId())),
            // An explicit share always widens past targeting.
            sharedWith(root, query, cb, SharePrincipalType.USER, ctx.userId()),
            ctx.departmentId() == null
                    ? cb.disjunction()
                    : sharedWith(root, query, cb, SharePrincipalType.DEPARTMENT, ctx.departmentId()));
}
```

`sharedWith` is an `EXISTS` subquery over `document_share` with `revoked_at IS NULL`.

- [ ] **Step 4: Run the tests, then the full suite**

- [ ] **Step 5: Commit**

```powershell
git commit -m "feat(scoping): bind document targeting to every internal reader, ALL included"
```

### Task 13: `DocumentAudienceFilter` — the portal half

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/scoping/DocumentAudienceFilter.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/PortalVisibilityTest.java`

**Interfaces:**
- Consumes: Task 12's class, Task 3's portal authority.
- Produces: the complete filter. Everything from Task 14 onward relies on it.

- [ ] **Step 1: Write the failing tests**

These are this sub-project's most important negatives. Every one is a cross-company or cross-contact leak if it regresses.

```java
@Test void contactACannotReadContactBsContactOnlyDocumentAtTheSameCustomer() { }
@Test void aContactCannotReadAnyDocumentOfAnotherCustomer() { }
@Test void aContactReadsACompanySharedDocumentAtTheirOwnCustomer() { }
@Test void aSensitiveDocumentReachesNoContactByTier() { }
@Test void anExplicitShareMakesASensitiveDocumentVisibleToThatContactOnly() { }
@Test void aFinanceLabelledContactCannotReadALegalLabelledDocument() { }
@Test void anUnlabelledTargetIsVisibleToEveryContactAtTheCustomer() { }
@Test void aRetiredContactReadsNothing() { }
```

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement the portal branch**

Note the parenthesisation carefully. The explicit-share disjunct sits **outside** the whole tier-and-targeting test, not inside it. Getting that wrong makes an explicit share subject to label targeting, which silently breaks the one mechanism that is supposed to override everything — and it would still pass every test above except `anExplicitShareMakesASensitiveDocumentVisibleToThatContactOnly`.

```java
/**
 * Q9's three tiers govern the CUSTOMER side -- "each contact sees only their
 * own", "company level attachments: visible to all in company", "sensitive
 * documents: restricted even within the company unless explicitly shared".
 * They are not internal-staff restrictions; internalAudience() handles those.
 *
 * FAILS CLOSED at every step: an actor with no live contact row matches nothing,
 * which is what makes contact retirement take effect on the very next request.
 */
private Specification<Document> portalAudience(AuthContext ctx) {
    return (root, query, cb) -> {
        var contact = contacts.findActiveContactForUser(ctx.userId()).orElse(null);
        if (contact == null) {
            return cb.disjunction();   // no live contact => no documents, ever
        }

        Predicate atMyCustomer = cb.equal(root.get("customerId"), contact.customerId());

        Predicate byTier = cb.or(
                // COMPANY_SHARED: every ACTIVE contact at this customer. The
                // contact's own ACTIVE status is already proven by the lookup above.
                cb.equal(root.get("visibilityTier"), VisibilityTier.COMPANY_SHARED),
                // CONTACT_ONLY: the owning contact alone.
                cb.and(cb.equal(root.get("visibilityTier"), VisibilityTier.CONTACT_ONLY),
                       cb.equal(root.get("ownerContactId"), contact.id())));
        // SENSITIVE appears in neither disjunct: it reaches nobody BY TIER, and
        // only ever through the explicit share below. That absence is the rule.

        Predicate byLabel = cb.or(
                cb.isNull(root.get("targetContactLabel")),
                contact.label() == null
                        ? cb.disjunction()   // an unlabelled contact matches no label target
                        : cb.equal(root.get("targetContactLabel"), contact.label()));

        // The share disjunct is ORed against the WHOLE of the above, never folded
        // into byTier. That is precisely Q9's "restricted ... unless explicitly
        // shared": a share overrides tier AND label together.
        return cb.or(
                cb.and(atMyCustomer, byTier, byLabel),
                sharedWith(root, query, cb, SharePrincipalType.CONTACT, contact.id()));
    };
}
```

Resolve the acting contact through the same port Task 3 added, not by importing a `customer` repository here. `scoping` already imports `customer` for `CustomerContactDescriptor`, so the import itself is permitted — but reusing Task 3's port keeps one definition of "an active contact for this user" rather than two that can drift apart.

- [ ] **Step 4: Run the tests, then the full suite**

- [ ] **Step 5: Commit**

```powershell
git commit -m "feat(scoping): resolve Q9's three tiers and label targeting for portal actors"
```

### Task 14: `DocumentService` read paths

**Files:** `DocumentService.java` (create), `DocumentView.java`, `DocumentServiceTest.java`
**Interfaces:** Produces `list(Pageable)`, `forCase(UUID caseId, Pageable)`, `get(UUID id)`, all `@RequirePermission(DOCUMENT_VIEW)`.

- [ ] **Step 1: Write the failing tests** — including that `forCase` returns home-and-linked documents, and that a cross-tenant id is a 404 not a 500.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Every read goes through `AuthorizedQuery`. The home-or-linked filter is an `extra` Specification — **not** part of the audience filter, which answers *who*, not *where*.
- [ ] **Step 4: Add `document..` to `AuthorizationCoverageTest`'s covered packages in this same commit.** Never afterwards. The rule is already repository-injection-bound, so every class you add is covered automatically; **add no exclusion**.
- [ ] **Step 5: Run the tests and `AuthorizationCoverageTest`.**
- [ ] **Step 6: Commit.**

### Task 15: Upload — create a document, and append a version

**Files:** `DocumentService.upload`, `CreateDocumentRequest`, `DocumentServiceTest`
**Interfaces:** Produces `upload(UUID caseId, CreateDocumentRequest, InputStream, long, String) → DocumentView` and `addVersion(UUID documentId, InputStream, long, String) → DocumentVersionView`.

**This task implements Task 7's ruling.** If §2.3 still reads "open" when you reach this task, stop — the spec forbids shipping an upload path without it.

- [ ] **Step 1: Write the failing tests**

Include: blob-first ordering (a failed row commit leaves no readable document but does not corrupt anything); `version_no` starts at 1 and increments; a second concurrent version at the same number is a 409, not a silent overwrite; a new version resets `review_status` to `PENDING`; `customer_id` is taken from the **resolved case**, never from the request body; plus every test Task 7's ruling requires.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** `caseId` resolved through `AuthorizedQuery` under `DOCUMENT_UPLOAD` before anything is written. `StageWriteScopeGuard` applies, as it does for tasks — a stage's `write_scope` narrows who may write inside it, on top of the record scope.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 16: Streaming download

**Files:** `DocumentContentService.java`, `DocumentContentServiceTest.java`
**Interfaces:** Produces `open(UUID documentId, int versionNo) → BlobContent(InputStream, String contentType, long sizeBytes, String filename)`, gated `DOCUMENT_VIEW`.

- [ ] **Step 1: Write the failing tests** — a `document.manage`-only holder is refused (metadata is not bytes); a targeted document is refused to an ALL-scoped `document.view` holder outside the target; the stream is the exact bytes uploaded.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Gated `DOCUMENT_VIEW` so the audience filter narrows it. **Never a presigned URL** — the local adapter cannot presign, and a presigned URL outlives a revoked share.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 17: `PATCH` metadata and retarget

**Files:** `DocumentService.patch`, `PatchDocumentRequest`, tests
**Interfaces:** Produces `patch(UUID id, PatchDocumentRequest) → DocumentView`, gated `DOCUMENT_MANAGE`.

- [ ] **Step 1: Write the failing tests** — the recovery case is the important one: a document targeted at a department with no members is still loadable and retargetable by a `document.manage` holder. That is §6.4's entire justification.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** **`PATCH`, not `PUT`** — a `PUT` is a full replace and would require the view type to carry every request field. Retargeting is audited as its own action (`document.retargeted`), not folded into a generic update, for the same reason `contact.deactivated` is distinguishable from a phone-number correction.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 18: Retirement, and what it revokes

**Files:** `DocumentService.retire`, tests
**Interfaces:** Produces `retire(UUID id, String reason)`, gated `DOCUMENT_MANAGE`.

CLAUDE.md makes "what does this deactivation revoke?" a required design question. The answer is spec §5.5, and this task implements all four parts.

- [ ] **Step 1: Write the failing tests**

```java
@Test void retiringRevokesEveryLiveShare() { }
@Test void retiringRevokesEveryCrossJourneyLink() { }
@Test void retiringReopensARequirementItSatisfied() { }
@Test void retiringLeavesTheBlobReadableByKey() { }   // bytes are never deleted
@Test void aRetiredDocumentIsNotReturnedByAnyListing() { }
```

The third is the one that distinguishes this from the task rule and is easiest to omit.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Reopening goes through `journey`'s gated requirement path — **no new caller of `CaseEngine.reconcile`**, and no direct write to requirement state.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 19: Shares

**Files:** `DocumentSharingService.java` (share half), tests
**Interfaces:** Produces `share(UUID documentId, SharePrincipalType, UUID principalId)` and `revokeShare(UUID shareId)`, both gated `DOCUMENT_SHARE`.

- [ ] **Step 1: Write the failing tests** — sharing a `SENSITIVE` document to one contact makes it visible **to that contact only**; revoking makes it invisible **on the very next request**, not when a token expires; the principal id is resolved through `AuthorizedQuery` before being written (the write-half rule three sub-project 1 escalations broke).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 20: Cross-journey links

**Files:** `DocumentSharingService.java` (link half), tests
**Interfaces:** Produces `link(UUID documentId, UUID caseId)` and `unlink(UUID documentId, UUID caseId)`, gated `DOCUMENT_SHARE`.

- [ ] **Step 1: Write the failing tests** — a linked document appears in the second case's listing *and* the first's; linking to a case at a **different customer** is refused; the link does not change the document's `case_id` or `customer_id`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Both ids resolved through `AuthorizedQuery` first.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 21: Isolation and write-scope negatives

**Files:** `backend/src/test/java/co/ara/onboarding/document/DocumentIsolationTest.java`, `DocumentWriteScopeTest.java`

Following the `task` precedent, this sub-project's negatives live in-package rather than in `security`.

- [ ] **Step 1: Write the tests** — cross-tenant ids are 404 for every endpoint; a wider-scoped holder is still refused inside an `OWNER_ONLY` stage (the same shape as `security.WriteScopeTest` and `task.TaskWriteScopeTest`); the RLS policy refuses a document reached through another tenant's case.
- [ ] **Step 2: Run them — they should mostly pass already.** Any that fails is a real defect found by this task, which is the point. Fix the code, never the test.
- [ ] **Step 3: Commit.**

### Task 22: Controllers and the OpenAPI document

**Files:** `DocumentController.java`, `DocumentExceptionHandler.java`, regenerate `frontend/src/lib/api/generated.ts`
**Interfaces:** Produces the endpoints in spec §8 (minus the request and portal ones, which are Phase 5).

- [ ] **Step 1: Write the failing tests** — `DirectApiAccessTest.everyTenantScopedEndpointRejectsAnonymousAccess` sweeps `RequestMappingHandlerMapping` and will pick these up automatically; assert 404 (not 403) for out-of-scope ids and 409 for a duplicate version.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement thin controllers.** `DocumentExceptionHandler` is a `@RestControllerAdvice` in **this module** — `platform` must never name a domain type.
- [ ] **Step 4: Regenerate the API types.**

```powershell
cd backend; .\gradlew.bat openApiSpec
cd ../frontend; npm run generate:api
```

springdoc orders schema properties nondeterministically, so back-to-back regenerations produce reordering-only diffs. That is noise, not a contract change.

- [ ] **Step 5: Run the full backend suite and `npx tsc --noEmit`.**
- [ ] **Step 6: Commit.**

---

## Phase 5 — Requests, review, and the requirement seam

### Task 23: Ad-hoc document requests

**Files:** `DocumentRequestService.java`, `CreateDocumentRequestRequest.java`, `DocumentRequestView.java`, tests
**Interfaces:** Produces `create(UUID caseId, CreateDocumentRequestRequest) → DocumentRequestView` and `withdraw(UUID requestId, String reason)`, gated `DOCUMENT_REQUEST`.

- [ ] **Step 1: Write the failing tests** — an ad-hoc request carries a null `requirement_id`; `requested_of_contact_id` is resolved through `AuthorizedQuery` before it is written; withdrawing sets `WITHDRAWN` and **never** satisfies anything.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 24: `DocumentInstantiation` — the `RequirementKind.DOCUMENT` seam

**Files:** `DocumentInstantiation.java`, `DocumentInstantiationTest.java`
**Interfaces:** Consumes `RequirementKind.DOCUMENT` (already in the enum since sub-project 2). Produces one `document_request` per `DOCUMENT` requirement when its milestone activates.

**Model this on `TaskInstantiation` exactly** — read it before writing this class. But **do not copy its naming dodge**: `TaskInstantiation`'s own javadoc admits it was named to fall outside the old `*Service`/`*Directory` suffix rule. That rule is gone (3A Task 2 rebound it to repository injection), so this class is covered whatever it is called. Name it for what it does and let the rule cover it.

- [ ] **Step 1: Write the failing tests** — a milestone activating with a `DOCUMENT` requirement creates exactly one open request; activating twice creates only one (the unique index is the truth); a `MANUAL` requirement creates none; `requires_review` is read from the requirement definition, with null reading as `false`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** **No new caller of `CaseEngine.reconcile`** — instantiation hangs off the same hook `TaskInstantiation` uses.
- [ ] **Step 4: Run the tests and `ModuleBoundaryTest`.**
- [ ] **Step 5: Commit.**

### Task 25: Fulfilment, and satisfaction without review

**Files:** `DocumentRequestService.fulfil`, tests
**Interfaces:** Produces `fulfil(UUID requestId, UUID documentId)`. Calls `journey.RequirementService.satisfy(requirementId, documentId, "document")`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void fulfillingARequestWithRequiresReviewFalseSatisfiesTheRequirementImmediately() { }
@Test void fulfillingARequestWithRequiresReviewTrueDoesNotSatisfyYet() { }
@Test void fulfillingAWithdrawnRequestIsRefused() { }
@Test void theSatisfiedRefAndRefTypePointAtTheDocument() { }
```

The last one proves the seam `SatisfyRequest`'s own doc comment promised sub-projects 3–5 would use.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Satisfaction goes through the **existing gated** `RequirementService.satisfy`, which already reconciles under `CaseRepository.lockById`'s row lock. Do not lock, reconcile or recompute progress here.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 26: The portal upload endpoint

**Files:** `PortalDocumentController.java`, `PortalDocumentTest.java`
**Interfaces:** Produces `GET /portal/documents` and `POST /portal/cases/{caseId}/documents`.

**This task implements Task 7's ruling for externally-supplied files.** External upload is the exposure the whole hardening decision was about; if §2.3 still reads "open", stop.

- [ ] **Step 1: Write the failing tests** — a contact uploads with each of the three visibility choices SCREENS §17 names and gets the right tier; a contact cannot upload against **another customer's** case; a retired contact is refused; the uploaded document's `owner_contact_id` is the **acting contact**, never a value from the request body.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Reuse `DocumentService.upload` — do not fork a parallel upload path, or Task 7's ruling would have to be implemented twice and would drift.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 27: Review — approve, reject, and what each does to the requirement

**Files:** `DocumentReviewService.java`, tests
**Interfaces:** Produces `review(UUID documentId, int versionNo, ReviewDecision, String note)`, gated `DOCUMENT_REVIEW`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void approvingSatisfiesARequiresReviewRequirement() { }
@Test void rejectingAVersionThatAlreadySatisfiedReopensTheRequirement() { }
@Test void aNewVersionResetsReviewStatusToPending() { }
@Test void approvingV1SaysNothingAboutV2() { }
@Test void aHolderOfDocumentViewButNotDocumentReviewIsRefused() { }
@Test void theCrossCasePendingQueueIsScopeFilteredWithNoCarveOut() { }
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** The pending-review queue is an ordinary `AuthorizedQuery` listing filtered to `review_status = PENDING` — **no carve-out**, and if one seems necessary the design is wrong.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 28: Expiry

**Files:** `DocumentService.expiring`, tests
**Interfaces:** Produces `expiring(Duration within, Pageable)`, gated `DOCUMENT_VIEW`.

- [ ] **Step 1: Write the failing tests** — the read honours scope *and* audience; a retired document never appears; the boundary is inclusive at exactly 30 days.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement the read only.** **Nothing fires here** — notification is sub-project 6. This task stores and exposes; it does not notify.
- [ ] **Step 4: Run the tests.**
- [ ] **Step 5: Commit.**

### Task 29: Audit actions, and cause before effect

**Files:** `AuditActions.java` (modify), `journey/CauseBeforeEffectTest.java` (modify)
**Interfaces:** Produces `document.uploaded`, `document.version_added`, `document.retargeted`, `document.retired`, `document.shared`, `document.share_revoked`, `document.linked`, `document.requested`, `document.request_withdrawn`, `document.reviewed`.

`AuditActions` has a **declaration-order hazard**: static initialisers run in declaration order, and `AuditActions` itself once shipped with exactly that bug. Add the new constants alongside the existing ones, never above the map they populate.

- [ ] **Step 1: Decide `timeline_visible` per action, deliberately**

CLAUDE.md: set it deliberately for each new action rather than copying a neighbour. Documents are business records, so most are timeline-visible — but `document.retargeted` and `document.share_revoked` are access-control changes, closer to the compliance-only `role.*` family. State the reasoning for each in the code.

- [ ] **Step 2: Write the failing `CauseBeforeEffectTest` subsequence**

Assert `document.uploaded` → `requirement.satisfied` → `milestone.completed` appear in that order. `AuditRecorder` stamps `occurred_at` from the clock, so call order **is** timeline order; the pre-2026-08-29 scrambled-timeline bug is what this guards against recurring.

- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run the tests, then the full backend suite.**
- [ ] **Step 6: Commit.**

---

## Phase 6 — Frontend

**Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any task in this phase**, per CLAUDE.md — whether the task is a new screen, a restyle, or a single component.

Unlike sub-project 3A, the design bundle **does** cover the operator screen: `SCREENS.md` §7 `docs`. Implement it; do not invent one. Read `COMPONENTS.md` for the Chip and table primitives and restyle in place rather than adding parallel components.

**Every task in this phase runs `npx tsc --noEmit` and `npm run lint` in its own verification step**, not just `npx vitest run`. 3A's close records a hard `tsc` error and an ESLint error sitting undetected across several tasks because vitest is JSDOM-only.

### Task 30: API hooks

**Files:** `frontend/src/lib/api/documents.ts`, `documents.test.tsx`
**Interfaces:** Produces `documentKeys`, `useDocuments`, `useCaseDocuments`, `useDocument`, `useUploadDocument`, `useAddVersion`, `usePatchDocument`, `useRetireDocument`, `useShareDocument`, `useRevokeShare`, `useLinkDocument`, `useDocumentRequests`, `useCreateDocumentRequest`, `useWithdrawRequest`, `useFulfilRequest`, `useReviewVersion`.

- [ ] **Step 1: Write the failing tests** — query-key invalidation is the part that actually breaks: uploading must invalidate both the case listing and the index; reviewing must invalidate the document *and* the pending queue.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Re-export generated OpenAPI types under short names, the discipline `lib/api/tasks.ts` and `cases.ts` already follow. **Never hand-write an API type.** File upload needs `FormData`, so `apiFetch` may need a multipart path — extend it rather than bypassing it, or the refresh-cookie handling is lost.
- [ ] **Step 4: Run `npx vitest run`, `npx tsc --noEmit`, `npm run lint`.**
- [ ] **Step 5: Commit.**

### Task 31: The `docs` table and its visibility cell

**Files:** `DocumentTable.tsx`, `VisibilityCell.tsx`, tests
**Interfaces:** Consumes `useDocuments`. Produces the table at `SCREENS.md` §7's `34px 1.6fr 1fr .9fr 1fr` grid.

- [ ] **Step 1: Write the failing component tests** — the visibility cell stacks a Chip over a mono scope label; a sensitive document renders a lock glyph before the filename; **every status colour is paired with a word or icon**, never colour alone (CLAUDE.md's fourth held decision).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Mono (`Spline Sans Mono`) for machine values — ids, dates, counts, the scope label. Instrument Sans for human text. Cards are flat. Every user-facing string goes through `t()`.
- [ ] **Step 4: Run vitest, tsc, lint.**
- [ ] **Step 5: Commit.**

### Task 32: The scope filter row and the hidden-count line

**Files:** `ScopeFilterRow.tsx`, `HiddenCountLine.tsx`, tests, plus a backend count endpoint if one is needed
**Interfaces:** Produces the five filters and the `08 VISIBLE · 61 HIDDEN BY SCOPE` line.

**This task ships the codebase's second deliberate aggregate-disclosure exception.** Spec §9 is the argument. The count must be **bounded to the current filter context**, and the code must carry its own written justification — CLAUDE.md is explicit that a second exception needs its own argument, not a copy of the audit-timeline carve-out's.

- [ ] **Step 1: Write the failing tests** — the line renders when rows are hidden; it renders "0 hidden" rather than disappearing when nothing is hidden (the line exists so a scoped view does not read as an empty one — hiding the line in the one case a user most needs it defeats the purpose); the count never exceeds the tenant total.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Deriving the hidden count needs a total the actor cannot see. Compute it server-side under the *same* permission and audience, differencing against an RLS-only count — and **document why that difference is safe to disclose** in the service, not only in the spec.
- [ ] **Step 4: Run vitest, tsc, lint.**
- [ ] **Step 5: Commit.**

### Task 33: Upload dialog, and the case workspace Documents tab

**Files:** `UploadDialog.tsx`, `DocumentsTab.tsx`, `VisibilityAside.tsx`, tests
**Interfaces:** Consumes `useUploadDocument`, `useCaseDocuments`. Mounts into the existing case workspace SegmentedControl (`Journey · Tasks · Documents · Agreements · Activity`).

- [ ] **Step 1: Write the failing tests** — the visibility select offers exactly the three tiers; a `SENSITIVE` selection surfaces the explanation rather than silently restricting; **the upload control's enabled state waits for the mutation** rather than optimistically flipping.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** The right aside is `SCREENS.md` §7's "How visibility works", three tiers explained in one sentence each, colour-coded by semantic fg.
- [ ] **Step 4: Run vitest, tsc, lint.**
- [ ] **Step 5: Commit.**

### Task 34: Request and review dialogs

**Files:** `RequestDocumentDialog.tsx`, `ReviewDialog.tsx`, tests
**Interfaces:** Consumes `useCreateDocumentRequest`, `useReviewVersion`. Wires the case header's `Request document` primary action from `SCREENS.md` §6.

- [ ] **Step 1: Write the failing tests** — the request dialog exposes `requires_review`; the review dialog requires a note on rejection; a user without `document.review` never sees the review affordance (permission gating, matching the existing screens' pattern).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run vitest, tsc, lint.**
- [ ] **Step 5: Commit.**

---

## Phase 7 — Verification and close-out

### Task 35: An e2e spec for the visibility arc

**Files:** `frontend/e2e/documents.spec.ts`

- [ ] **Step 1: Write the spec.** Seed through the API (`e2e/support/tenant.ts`), then drive the browser: upload a company-shared document and a Legal-targeted one; confirm a Finance user sees one and the hidden-count line says so; share the targeted one explicitly and confirm it appears.

**Two traps this suite has hit before, both recorded in CLAUDE.md:**
- **Never `.check()`/`.fill()`-and-assume against a control whose state depends on an async round trip.** Use `.click()` followed by an auto-retrying `expect(...)`.
- **Omitted `List`/`Map` request fields NPE the server rather than defaulting.** Seed every field explicitly, including `dependsOnMilestoneKeys`, `branchRules`, `attributes` and `autoAdvance: true`.

- [ ] **Step 2: Run it live against a scratch database.**

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/onboarding_e2e_sp4"
npx playwright test documents.spec.ts
```

Activation tokens exist only in `frontend/e2e/.artifacts/backend.log`; Playwright gives a test no way to read a `webServer`'s stdout.

- [ ] **Step 3: Fix what it finds.** Every previous first live run found real defects. **Never weaken an assertion to make a spec pass** — rule each finding as product bug or spec bug, and say which in the commit.
- [ ] **Step 4: Commit.**

### Task 36: An e2e spec for request → fulfil → review → satisfy

**Files:** `frontend/e2e/document-requests.spec.ts`

- [ ] **Step 1: Write the spec.** Author a workflow with a `DOCUMENT` requirement (through the API `PUT` — the builder cannot author `requires_review`, and this plan's §5.3 note says so), open a case, confirm the request instantiates, fulfil it, confirm the requirement stays open, approve the review, confirm the milestone completes.
- [ ] **Step 2: Run it live.**
- [ ] **Step 3: Fix what it finds.**
- [ ] **Step 4: Commit.**

### Task 37: Whole-branch verification and close-out

**Files:** `CLAUDE.md`

- [ ] **Step 1: Run all three suites green in the same pass, from clean.**

```powershell
cd backend; .\gradlew.bat cleanTest test
cd ../frontend; npx vitest run; npx tsc --noEmit; npm run lint
npx playwright test
```

Read each suite's **own summary line**. Do not pin counts in CLAUDE.md — a number that drifts is a number that gets trusted.

- [ ] **Step 2: Verify spec §10's ten invariants individually, against the code.**

Not by assertion, and not by trusting this plan's intentions. For each, name the test or the file:line that proves it. Invariant 2 ("no new caller of `CaseEngine.reconcile`") is checkable directly — `git log -p` the branch for `CaseEngine.java` and report honestly if anything touched it, including a comment-only change. 3A's close recorded exactly such a hit rather than rounding it to "holds"; do the same.

- [ ] **Step 3: Update CLAUDE.md.**

Add a "Sub-project 4 delivered" paragraph and "Sub-project 4's own ten". Record what stays open. **Prune what is no longer true** — in particular, CLAUDE.md's sub-project 3 section still lists four task gaps that 3A closed (spec §11.1 documents this). A file that accumulates without pruning stops being trusted, which is its own stated rule.

Record the `AudienceFilter` addition prominently: it is the first change to the authorization core in four sub-projects, and a future reader needs to know that `forPermission`'s `ALL` branch no longer returns unconditionally.

- [ ] **Step 4: Commit.**

---

## Plan self-review

Run against the spec after writing, before dispatching Task 1.

**Spec coverage.** §2.1's six scope items → Tasks 4–6 (storage), 8/15 (record and versions), 12–13 (visibility), 23–27 (requests and review), 3/26 (portal), 30–34 (frontend). §2.3's deferred hardening → Task 7, blocking Tasks 15 and 26. §4's seven schema items → Task 8. §5.5's four revocation consequences → Task 18. §6.2's mechanism → Task 2. §6.4's metadata/payload split → Tasks 12, 16, 17. §6.6's portal authority → Task 3. §6.7's permissions → Tasks 3 (keys) and 11 (seeding). §6.8 → Task 14, Step 4 (compliance only; the rebind is already done). §7's storage → Tasks 4–6. §8's API surface → Tasks 22 and 26. §9's screens → Tasks 31–34, with the hidden-count exception at 32. §10's ten invariants → Task 37, Step 2.

**Known gaps, deliberate.** §2.2's five out-of-scope items have no tasks by design. `requirement_definition.requires_review` has no builder UI (§5.3) — a third field added to a known authoring gap, recorded rather than hidden.

**Type consistency.** `BlobStore.put/open/exists` is used with identical signatures in Tasks 4, 5, 15 and 16. `AudienceFilter.audience(AuthContext, String)` is two-argument everywhere — Tasks 2, 12, 13. `RequirementService.satisfy(UUID, UUID, String)` matches the existing signature at `journey/RequirementService.java:78`, used in Tasks 25 and 27. `PortalPermissions.forContact/forSponsor` return `Map<String, Scope>` in Tasks 3 and 13.

**Ordering.** Task 3 adds the permission *keys and catalog entries* (needed for `PortalPermissions` to compile); Task 11 adds the *role seeding*. Task 7 blocks Tasks 15 and 26 and must not be skipped. Tasks 2 and 3 precede everything because they change a class every module depends on.

