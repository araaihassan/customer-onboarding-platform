# Workflow Engine & Case Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build configurable workflow definitions with immutable published versions, and the case runtime that executes them — stages, milestones, requirements, branching, approvals, weighted progress, hold, and version migration — plus the tenant-admin builder and the journey workspace that drive them.

**Architecture:** Two new backend modules on sub-project 1's substrate. `workflow` owns authoring and versioning and knows nothing about a running case; `journey` owns the runtime and depends on `workflow` but reaches `customer` only through a port it declares itself. All runtime state changes funnel through one package-private `CaseEngine.reconcile(...)` under a row lock on the case. Descriptors for the four new resource types live in `scoping/`, as the existing four do.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Playwright, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-21-workflow-engine-and-case-lifecycle-design.md`

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is loaded into every session and is the authority for everything sub-project 1 established; this section carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. Two new modules: `co.ara.onboarding.workflow` and `co.ara.onboarding.journey`. Descriptors go in `co.ara.onboarding.scoping`. Nothing else moves.
- **`journey` must never import `customer`.** It declares `journey.CustomerDirectory` and `customer` implements it — the `authz.ActorDirectory` / `identity.UserSessionRevoker` idiom. Enforced by a named `ModuleBoundaryTest` rule, not by the cycle rule.
- **`workflow` must never import `journey`.** A version describes an executable definition; where a case sits is `journey`'s alone. No `workflow` table references a case or stores a count of them.
- **Fourteen new tables**, all tenant-owned: `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `SELECT enable_tenant_rls('<table>')` in the same migration, and `GRANT SELECT, INSERT, UPDATE ON <table> TO onboarding_app` — never `DELETE`. `RlsCoverageTest` is deny-by-default over the live schema; **its allowlist stays at four entries.**
- **Two migrations only:** `V12__workflow.sql`, `V13__journey.sql`. Forward-only — never edit either once committed.
- **UUIDv7 keys** via `co.ara.onboarding.platform.Uuid7.generate()`. All timestamps `timestamptz` in UTC.
- **Every public `*Service` method carries `@RequirePermission`.** From Task 1 the same rule binds to `*Engine`, so `CaseEngine` is **package-private** in `journey` and reachable only through a gated service.
- **Every read of tenant business data goes through `AuthorizedQuery`**, and so does **every id a write path takes from a URL or a request body**, before it writes. Two exclusions are created in this sub-project, both commented, both narrow: `CaseRepository.lockById` (Task 14) and `AuditQuery.findForResource` (Task 21).
- **Out-of-scope records return 404, never 403.** `AuthorizedQuery` throws `NoSuchElementException`, which maps to 404.
- **`progress_percent` is computed by the engine and never read from a request body.** It appears in no `*Request` record anywhere in this plan.
- **A `PUT` is a full replace,** so its view type must carry every field its request type accepts. Adding a field to an `Update*Request` without adding it to the matching `*View` makes every client silently erase it.
- **Permission keys** are declared in `PermissionKeys`, catalogued in `PermissionCatalog`, and referenced as constants — never as string literals. The fifteen new keys land in Task 11.
- **Scope values are exactly four** (`ALL`, `DEPARTMENT`, `TEAM`, `ASSIGNED`) and no fifth is added. `write_scope` on a stage is a **separate, subtractive** concept and must never grant.
- **TDD.** Failing test first; security tests before the mechanism they verify. A new structural guard must be **seen red** before the code it protects exists.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper instead, or `UnexpectedRollbackException` masks the exception under test:

  ```java
  // RIGHT
  assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.create(...)))
      .isInstanceOf(NoSuchElementException.class);
  ```

- **Fixture create-helpers must run inside `runAs`** — the tables they write are RLS-protected and Spring Data proxies do not trigger the tenant binder.
- **Backend tests need Docker.** Run `cd backend && ./gradlew cleanTest test` — never a bare `test`, which reports `BUILD SUCCESSFUL` having executed nothing. On PowerShell use `.\gradlew.bat`.
- **The frontend implements `docs/uispecs`; it does not invent a visual language.** Read `design/02-tokens/tokens.md`, `design/04-components/component-specs.md` (families 8, 9, 10, 11, 12, 15 are the new ones here), `design/05-review/ux-design-review.md`, and open `docs/uispecs/Onboarding Platform.html` before building a screen. Colour always means status; mono for machine values, Archivo for human text; cards are flat; colour is never the only signal.
- **Every user-facing string goes through `t()`** (`frontend/src/lib/i18n`). A missing key renders as the key, so gaps are visible.
- **API types are generated, never hand-written.** `./gradlew openApiSpec` then `npm run generate:api`.
- **Commit at the end of every task.** Conventional Commits, with the *why* in the body.
- **Amend this plan when you find a defect in it**, and say so in the commit body. Sub-project 1's plan carries several such amendments; they are how a finding survives the session that found it.

---

## File Structure

**Backend — `workflow` module** (`backend/src/main/java/co/ara/onboarding/workflow/`)

| File | Responsibility |
|---|---|
| `WorkflowTemplate.java` | Named workflow; `status`, `currentVersionId` |
| `WorkflowVersion.java` | `DRAFT`/`PUBLISHED`, `versionNo`, `@Version` optimistic lock |
| `Stage.java` | Ordinal, name, department, flags, `slaDays`, `writeScope`, entry condition, fallback |
| `MilestoneDefinition.java` | Ordinal, name, `estimatedDurationDays` |
| `MilestoneDependency.java` | Definition → definition edge |
| `RequirementDefinition.java` | Kind, label, weight, mandatory, per-kind columns |
| `AttributeDefinition.java` | Key, label, data type, required, allowed values |
| `BranchRule.java` | Ordinal, condition, target stage |
| `Condition.java` | `@Embeddable` shared by `Stage` and `BranchRule` |
| `WriteScope.java`, `RequirementKind.java`, `AttributeType.java`, `ConditionSource.java`, `ConditionOperator.java`, `VersionStatus.java`, `TemplateStatus.java` | Enums |
| `*Repository.java` (8) | Spring Data repositories |
| `WorkflowService.java` | Template create/deactivate, draft create/copy/replace — gated, audited |
| `PublishService.java` | The five validations, publish, `currentVersionId` move |
| `PublishValidationException.java` | Carries the full problem list → 422 |
| `WorkflowDefinitionView.java` / `WorkflowDefinitionRequest.java` | The whole-graph read and full-replace write shapes |
| `WorkflowController.java` | Thin HTTP layer |
| `WorkflowExceptionHandler.java` | `@RestControllerAdvice` for this module's exceptions |

**Backend — `journey` module** (`backend/src/main/java/co/ara/onboarding/journey/`)

| File | Responsibility |
|---|---|
| `Case.java`, `CaseParticipant.java`, `Milestone.java`, `Requirement.java`, `CaseAttributeValue.java`, `Approval.java` | Entities |
| `CaseStatus.java`, `MilestoneStatus.java`, `RequirementStatus.java`, `ApprovalKind.java`, `ApprovalStatus.java`, `ParticipantStatus.java` | Enums |
| `*Repository.java` (6) | Repositories; `CaseRepository.lockById` is the only locking finder |
| `CustomerDirectory.java`, `CustomerFacts.java` | The port `journey` declares |
| `CaseEngine.java` | **package-private** — reconcile, transitions, branching, progress |
| `ConditionEvaluator.java` | Evaluates a `Condition` against customer facts and attribute values |
| `CaseService.java` | Create, read, edit, advance, hold, resume — gated |
| `MilestoneService.java` | Edit, complete, reopen, force-complete request |
| `RequirementService.java` | Satisfy, waive |
| `ApprovalService.java` | Stage-exit decide, force-complete decide |
| `MigrationService.java` | Eligibility, migrate |
| `TimelineService.java` | Case timeline read via `audit.AuditQuery` |
| `CaseController.java`, `MilestoneController.java`, `ApprovalController.java`, `MigrationController.java` | HTTP layers |
| `JourneyExceptionHandler.java` | This module's `@RestControllerAdvice` |

**Backend — modified**

| File | Change |
|---|---|
| `platform/BusinessCalendar.java`, `platform/WeekdayBusinessCalendar.java` | New: business-day arithmetic (Task 12) |
| `authz/PermissionKeys.java`, `authz/PermissionCatalog.java` | Fifteen new keys (Task 11) |
| `authz/RoleTemplates.java` | New grants on all twelve templates (Task 11) |
| `authz/RoleService.java` | The delegation escalation guard (Task 4) |
| `scoping/CaseDescriptor.java`, `MilestoneDescriptor.java`, `RequirementDescriptor.java`, `ApprovalDescriptor.java` | New descriptors (Task 11) |
| `customer/JourneyCustomerDirectory.java` | Implements `journey.CustomerDirectory` (Task 10) |
| `identity/OrgStructureService.java`, `OrgStructureController.java` | Team membership (Task 3) |
| `audit/AuditActions.java` | New action keys (Tasks 6, 13–19) |
| `audit/AuditQuery.java` | New: `findForResource` (Task 21) |
| `architecture/ModuleBoundaryTest.java`, `AuthorizationCoverageTest.java` | Two named rules; `*Engine` and `*Directory` widening (Task 1) |
| `security/DirectApiAccessTest.java` | Derived endpoint enumeration (Task 1) |

**Frontend**

| File | Responsibility |
|---|---|
| `components/ui/Tabs.tsx`, `Chip.tsx`, `Checkbox.tsx`, `Switch.tsx`, `TimelineRow.tsx` | New primitives (Task 23) |
| `components/workflow/StageRow.tsx`, `StageInspector.tsx`, `BranchRuleCard.tsx`, `PublishPanel.tsx` | Builder (Task 24) |
| `components/workflow/MigrationTable.tsx` | Migration review (Task 25) |
| `components/journey/CaseHeader.tsx`, `CaseSwitcher.tsx`, `CreateCaseDialog.tsx` | Workspace shell (Task 26) |
| `components/journey/MilestoneRow.tsx`, `RequirementList.tsx`, `ForceCompleteDialog.tsx`, `HoldDialog.tsx`, `ApprovalPanel.tsx` | Journey tab (Task 27) |
| `components/journey/TimelineTab.tsx` | Timeline (Task 28) |
| `lib/api/workflows.ts`, `lib/api/cases.ts` | TanStack Query hooks |
| `app/(app)/t/[slug]/admin/workflows/page.tsx`, `[id]/versions/[vid]/page.tsx`, `[id]/migration/page.tsx` | Builder routes |
| `app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx` | Journey workspace route |
| `e2e/workflow-authoring.spec.ts`, `case-lifecycle.spec.ts`, `migration.spec.ts` | Playwright (Task 29) |

**Design system**

| File | Change |
|---|---|
| `docs/uispecs/design/scripts/build_tokens.py` | Light-theme token fixes (Task 2) |
| `docs/uispecs/design/scripts/contrast.py` | `report_shipped("light")` enabled (Task 2) |
| `docs/uispecs/design/02-tokens/*`, `frontend/src/app/tokens.css`, `tailwind-theme.css` | Regenerated and copied (Task 2) |

---

## Task Sequence

| # | Task | Deliverable |
|---|---|---|
| 1 | Structural guards and enumeration debt | Guards that will catch this sub-project's mistakes, seen red first |
| 2 | Light theme tokens | Nine failing non-text pairs fixed; light measured on every run |
| 3 | Team membership | TEAM scope alive in a running system |
| 4 | Delegation escalation guard | Nobody grants authority they lack |
| 5 | `V12` workflow schema and entities | Fourteen-table half one, with the immutability trigger proved |
| 6 | Template and draft authoring | `WorkflowService`, whole-draft replace, audited |
| 7 | Publish validation and freeze | Five validations, 422 list, `currentVersionId` |
| 8 | Workflow HTTP layer | Endpoints, OpenAPI, generated types |
| 9 | `V13` journey schema and entities | The runtime half |
| 10 | The customer port | `CustomerDirectory`, and the boundary rule enforced |
| 11 | Permissions, descriptors, templates | Fifteen keys, four descriptors, twelve templates |
| 12 | Business calendar | Business-day arithmetic behind an interface |
| 13 | Case creation | Attributes, participants, eager instantiation, dates |
| 14 | `CaseEngine.reconcile` under lock | Progress, statuses, idempotency, concurrency |
| 15 | Transitions, branching, completion | Exit, first-match branch, skip loop, terminal rule |
| 16 | Requirements and `write_scope` | Satisfy, waive, subtractive narrowing |
| 17 | Approvals and force-complete | Two decide paths, no self-approval |
| 18 | Hold, resume, reopen, reassign | Date shifting, `total_hold_days`, Q15's manual override |
| 19 | Version migration | Eligibility with reasons, repin, recompute |
| 20 | Journey HTTP layer | Endpoints, OpenAPI, generated types |
| 21 | The audit read path | `AuditQuery`, timeline endpoint, documented carve-out |
| 22 | Security negatives | Cross-tenant, narrow-scope write, kind confusion |
| 23 | UI primitives | Tabs, Chip, Checkbox, Switch, TimelineRow |
| 24 | The builder screen | Stage rows, inspector, branch rules, save |
| 25 | Publish and migration screens | Validation errors, eligibility table |
| 26 | Journey workspace shell | Header, case switcher, tabs, create-case |
| 27 | The journey tab | Milestone rows, requirements, dialogs |
| 28 | Timeline, empty tabs, responsive, axe | The rest of the workspace |
| 29 | Playwright and `CLAUDE.md` | Three end-to-end specs; the file future sessions read |

---

## Task 1: Structural guards and enumeration debt

The guards come first because every later task is judged by them, and because two of them are wrong today. `CLAUDE.md`: "A guard is only as wide as its enumeration, and every enumeration in sub-project 1 drifted behind the code." This task pays that debt before adding thirty endpoints to the same lists.

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/security/DirectApiAccessTest.java`

**Interfaces:**
- Consumes: nothing — this task precedes all new code.
- Produces: `noWorkflowDependencyOnJourney` and `noJourneyDependencyOnCustomer` rules (Tasks 5, 9, 10 must satisfy them); the `*Engine` clause of `everyPublicServiceMethodIsGated` (Task 14); the `*Directory` clause of `servicesDoNotCallRepositoryFindersDirectly` (Task 10); `DirectApiAccessTest.everyTenantScopedEndpointRejectsAnonymousAccess` (Tasks 8, 20, 21).

- [ ] **Step 1: Write the derived endpoint enumeration, which will go red on existing gaps**

Replace nothing; add to `DirectApiAccessTest`. This sweeps the live `RequestMappingHandlerMapping` instead of naming paths, so it grows with the code:

```java
@Autowired
private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

/**
 * Derived, not typed. Sub-project 1's hand-written list of this test's endpoints
 * was eleven endpoints short of the controllers that existed, which is the exact
 * failure mode CLAUDE.md warns about: a guard is only as wide as its enumeration.
 *
 * Every mapping under /api/t/{tenantSlug}/ that is not a public auth endpoint must
 * refuse an unauthenticated caller. A new controller is covered the moment it is
 * written, with nothing to remember.
 */
@Test
void everyTenantScopedEndpointRejectsAnonymousAccess() throws Exception {
    // A REAL tenant, not merely a literal slug substituted into the path.
    // TenantContextFilter 404s an unresolvable tenant slug before authentication is
    // even evaluated (spec 6.8: an unknown tenant is indistinguishable from one the
    // caller may not see) -- so probing a slug nobody created tests tenant
    // resolution, not authorization, and every response would be a 404 regardless
    // of whether the endpoint is protected.
    fixture.createTenant("anon-probe");
    var failures = new java.util.ArrayList<String>();

    for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
        var patterns = entry.getKey().getPathPatternsCondition();
        if (patterns == null) continue;
        for (var pattern : patterns.getPatterns()) {
            String path = pattern.getPatternString();
            if (!path.startsWith("/api/t/{tenantSlug}/")) continue;
            if (path.startsWith("/api/t/{tenantSlug}/auth/")) continue;   // public by design

            var methods = entry.getKey().getMethodsCondition().getMethods();
            var httpMethod = methods.isEmpty()
                    ? org.springframework.http.HttpMethod.GET
                    : org.springframework.http.HttpMethod.valueOf(methods.iterator().next().name());

            String concrete = path.replace("{tenantSlug}", "anon-probe")
                                  .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());

            int status = mvc.perform(MockMvcRequestBuilders.request(httpMethod, concrete)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();

            if (status != 401 && status != 403) {
                failures.add(httpMethod + " " + path + " answered " + status);
            }
        }
    }

    assertThat(failures)
            .as("every tenant-scoped endpoint must refuse an anonymous caller")
            .isEmpty();
}
```

- [ ] **Step 2: Run it and read what it reports**

Run: `cd backend && ./gradlew cleanTest test --tests '*DirectApiAccessTest'`
Expected: PASS or FAIL. Either outcome is information, and **you must record which** in the commit body. If it fails, the listed endpoints are real holes — an endpoint answering 200 or 500 to an anonymous request is the finding, and it is fixed in `SecurityConfig`, never by narrowing this test. If it passes, the eleven endpoints sub-project 1 never listed were in fact protected, and the value delivered is that the list can no longer drift.

- [ ] **Step 3: Add the two named module rules**

In `ModuleBoundaryTest`:

```java
/**
 * The cycle rule alone would pass a one-way violation, and a one-way violation is
 * exactly what erodes here: workflow reaching into journey to answer "how many
 * cases are on v4" would compile, pass every other test, and quietly make a
 * definition module depend on runtime state.
 */
@ArchTest
static final ArchRule noWorkflowDependencyOnJourney =
        noClasses().that().resideInAPackage("..workflow..")
            .should().dependOnClassesThat().resideInAPackage("..journey..")
            .because("a version describes an executable definition; where a case sits is journey's alone")
            .allowEmptyShould(true);

/**
 * journey consumes journey.CustomerDirectory, which customer implements. The arrow
 * therefore runs customer -> journey, and journey holds no customer entity, no
 * customer repository, and no CustomerStatus.
 */
@ArchTest
static final ArchRule noJourneyDependencyOnCustomer =
        noClasses().that().resideInAPackage("..journey..")
            .should().dependOnClassesThat().resideInAPackage("..customer..")
            .because("journey consumes CustomerDirectory, never customer's entities or repositories")
            .allowEmptyShould(true);
```

`allowEmptyShould(true)` is required only until Task 5 creates the packages: ArchUnit fails a rule that matched no classes, and "failed to check any classes" is noise, not a finding. **Remove both `allowEmptyShould` clauses in Task 10**, once both packages exist and the rules bind to real code.

- [ ] **Step 4: Prove both rules red**

Temporarily create `backend/src/main/java/co/ara/onboarding/workflow/Probe.java`:

```java
package co.ara.onboarding.workflow;
public class Probe { public co.ara.onboarding.journey.Probe2 p; }
```

and `backend/src/main/java/co/ara/onboarding/journey/Probe2.java`:

```java
package co.ara.onboarding.journey;
public class Probe2 { public co.ara.onboarding.customer.Customer c; }
```

Run: `cd backend && ./gradlew cleanTest test --tests '*ModuleBoundaryTest'`
Expected: **FAIL** — both new rules report a violation naming `Probe`/`Probe2`. Then delete both probe files and re-run: PASS. A guard you have never watched fail is a guard you cannot trust.

- [ ] **Step 5: Widen the permission-gate rule to `*Engine`**

In `AuthorizationCoverageTest`, wherever the rule selects `nameEndingWith("Service")`, add `*Engine`:

```java
/**
 * *Engine joins *Service because sub-project 2's CaseEngine is the first class
 * that orchestrates writes without being named Service. @RequirePermission binds
 * to public service methods, so a public engine outside this pattern would be an
 * ungated entry point -- the same name-shaped-guard hole CLAUDE.md records for
 * *Directory. CaseEngine is additionally package-private, so this rule is the
 * second line, not the only one.
 */
private static final DescribedPredicate<JavaClass> GATED_CLASS_NAMES =
        nameEndingWith("Service").or(nameEndingWith("Engine"));
```

and use `GATED_CLASS_NAMES` in place of the existing `nameEndingWith("Service")` predicate in the gate rule.

- [ ] **Step 6: Widen the finder rule to `*Directory`, with the two existing exclusions**

```java
/**
 * The rule was name-shaped and bound only to *Service, so *Directory classes were
 * invisible to it. CLAUDE.md flagged the consequence: "a future *Directory taking
 * a foreign id would be unguarded in exactly the way auth was." Task 10 writes
 * exactly such a class (CustomerDirectory's implementation, taking a customer id
 * from a request body), so the rule widens before that class exists.
 *
 * Two exclusions, both category one -- runs before there is an actor to authorize:
 *   - IdentityActorDirectory: supplies the department and teams that scope
 *     resolution itself needs. Gating it would require resolving the gate.
 *   - UserRoleDirectory: reads a user's role rows for the same resolution.
 */
@ArchTest
static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
        noClasses().that()
            .haveSimpleNameEndingWith("Service").or().haveSimpleNameEndingWith("Directory")
            .and().resideInAnyPackage("..customer..", "..identity..", "..auth..",
                                      "..workflow..", "..journey..")
            .and().doNotHaveFullyQualifiedName(
                    "co.ara.onboarding.identity.IdentityActorDirectory")
            .and().doNotHaveFullyQualifiedName(
                    "co.ara.onboarding.authz.UserRoleDirectory")
            .should().callMethodWhere(target(nameStartingWith("find"))
                    .and(target(owner(nameEndingWith("Repository")))))
            .because("a finder called directly skips the scope predicate: a silent, total bypass");
```

Keep the existing rule's structure if it differs in detail — the required changes are the `Directory` clause, the two new packages, and the two named exclusions. Do not convert the exclusions into a name pattern; sub-project 1's comment explains why they are per-class.

- [ ] **Step 7: Prove the widening red**

Temporarily delete the `IdentityActorDirectory` exclusion clause.
Run: `cd backend && ./gradlew cleanTest test --tests '*AuthorizationCoverageTest'`
Expected: **FAIL**, naming `IdentityActorDirectory` — which proves the `Directory` clause binds. Restore the exclusion and re-run: PASS.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./gradlew cleanTest test`
Expected: PASS, with no probe files left in the tree (`git status` must be clean of them).

- [ ] **Step 9: Commit**

```bash
git add backend/src/test/java/co/ara/onboarding/architecture backend/src/test/java/co/ara/onboarding/security
git commit -m "test: widen the guards before the code they will judge

DirectApiAccessTest now derives its endpoint list by sweeping the live
RequestMappingHandlerMapping instead of naming paths. Sub-project 1's typed list
was eleven endpoints short of the controllers that existed; this sub-project adds
roughly thirty, so the same drift was guaranteed.

Two named module rules replace reliance on the cycle check, which passes a one-way
violation: workflow must not depend on journey, and journey must not depend on
customer. Both seen red against temporary probe classes, then green with the probes
deleted.

The permission-gate rule now binds *Engine as well as *Service, ahead of Task 14's
CaseEngine, and the finder rule binds *Directory as well as *Service, ahead of Task
10's CustomerDirectory implementation -- the hole CLAUDE.md predicted would be hit
by 'a future *Directory taking a foreign id'. Widening proved red by removing the
IdentityActorDirectory exclusion."
```

---

## Task 2: Light theme tokens

`CLAUDE.md`: the shipped light tokens "are measured by nothing", and `report_shipped("light")` at the close of sub-project 1 found **nine of 49 pairs failing** against 3:1. Light is the default theme and Tasks 24–28 are almost entirely borders, so this is cheaper now than under six more sub-projects of screens.

**Files:**
- Modify: `docs/uispecs/design/scripts/build_tokens.py`
- Modify: `docs/uispecs/design/scripts/contrast.py`
- Regenerate: `docs/uispecs/design/02-tokens/tokens.css`, `tailwind.css`, `tokens.json`, `tokens.md`
- Copy: `frontend/src/app/tokens.css`, `frontend/src/app/tailwind-theme.css`
- Modify: `docs/uispecs/design/05-review/ux-design-review.md` (§1d)

**Interfaces:**
- Consumes: nothing.
- Produces: light-theme token values every frontend task after this one renders against; `report_shipped("light")` as a standing check.

- [ ] **Step 1: Record the failure before changing anything**

Run: `python docs/uispecs/design/scripts/contrast.py`
Then run the shipped-light report explicitly (add the call at the bottom of `__main__` if it is not already invoked):

```python
report_shipped("light")
```

Expected: **nine failures** — `accent-tint-border` on tint 1.06, `accent-weak` on surface 1.53, `solid-at-risk` on surface 2.54, `border-default` on all four grounds 1.15–1.28, `border-strong` 1.44, `border-dashed` 1.68. Paste the exact output into the commit body; it is the before-measurement and the only proof the fix did something.

- [ ] **Step 2: Fix the light values in `build_tokens.py`**

Adjust only the light-theme entries for the six failing roles, darkening each until it clears 3:1 against the **darkest** ground it lands on (`#f2f0ec`), which is the binding case. Do not hand-edit `tokens.css` — it is generated. Do not touch `text-faint`/`text-disabled`, which resolve to the same value deliberately, or `paper-600`, which is a graphics-only tier valid at 3:1 for 20px+ marks and 1px borders. `CLAUDE.md` says these are "not re-derivable by eye — do not fix them."

- [ ] **Step 3: Regenerate and re-measure**

```bash
python docs/uispecs/design/scripts/build_tokens.py
python docs/uispecs/design/scripts/contrast.py
```

Expected: `report_shipped("light")` reports **0 of 49 fail**; `report_shipped("dark")` still reports 0. `PAIRS` still reports its 7 historical failures — those are the evidence behind review finding 1 and are **meant to stay red**.

- [ ] **Step 4: Turn the light report into a standing check**

In `contrast.py`'s `__main__`, call `report_shipped("light")` unconditionally alongside dark, and make a non-zero failure count exit non-zero so it cannot pass unnoticed. Replace the understated banner text that still says "the known, deferred `border-default` at 1.28:1".

- [ ] **Step 5: Copy the generated files into the app**

```bash
cp docs/uispecs/design/02-tokens/tokens.css   frontend/src/app/tokens.css
cp docs/uispecs/design/02-tokens/tailwind.css frontend/src/app/tailwind-theme.css
```

These two are verbatim copies; never edit them in place.

- [ ] **Step 6: Verify nothing visual regressed**

```bash
cd frontend && npx vitest run
npx playwright test --grep accessibility
```

Expected: PASS. Note in the commit body that axe passing is **not** what proves this fix — axe's default rule set evaluates `color-contrast` for text only and has no non-text rule (WCAG 1.4.11), which is why nine failures hid behind a clean sweep. The proof is `report_shipped("light")`.

- [ ] **Step 7: Update the review document**

In `05-review/ux-design-review.md` §1d, replace the "knowingly imperfect" light-theme entry with what is now true: light measured, nine failures fixed, both themes reported on every run.

- [ ] **Step 8: Commit**

```bash
git add docs/uispecs frontend/src/app/tokens.css frontend/src/app/tailwind-theme.css
git commit -m "fix: measure the light theme and fix the nine failing pairs

Light is the default theme and was measured by nothing. report_shipped('light')
found nine of 49 pairs under 3:1 -- border-default at 1.15-1.28 on all four
grounds, border-strong 1.44, border-dashed 1.68, accent-tint-border 1.06,
accent-weak 1.53, solid-at-risk 2.54 -- so every card and control border in the
default rendering sat near 1.2:1.

No text pair failed, which is why Task 28's clean axe run in both themes measured a
different thing: axe's default rules cover text contrast and have no non-text rule.
report_shipped('light') now runs unconditionally and exits non-zero on failure.

Fixed in build_tokens.py and regenerated; tokens.css and tailwind.css copied to
frontend/src/app. text-faint/text-disabled and paper-600 deliberately untouched.
Sub-project 2's builder and journey screens are almost entirely borders, so fixing
this before Tasks 24-28 rather than after is the whole point of the timing."
```

---

## Task 3: Team membership

`ctx.teamIds()` is empty for every real user because nothing in the API writes `team_member` — so every descriptor's `teamScope` returns `cb.disjunction()`, and Account Manager, Project Manager, Technical and Support grant **nothing at all**. Sub-project 2's cases and milestones are TEAM-scoped for exactly those roles, so this blocks the new module's authorization, not just sub-project 1's.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/identity/OrgStructureService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/identity/OrgStructureController.java`
- Test: `backend/src/test/java/co/ara/onboarding/identity/TeamMembershipTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/InsufficientScopeTest.java` (add one case)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/org/page.tsx`
- Create: `frontend/src/components/admin/TeamMembers.tsx`
- Modify: `frontend/src/lib/api/admin.ts`

**Interfaces:**
- Consumes: `TEAM_MANAGE` (existing key), `AppUser`, `Team`, `AuthorizedQuery`.
- Produces:
  - `OrgStructureService.listTeamMembers(UUID teamId) -> List<TeamMemberView>` where `record TeamMemberView(UUID userId, String fullName, String email)`
  - `OrgStructureService.addTeamMember(UUID teamId, UUID userId) -> void`
  - `OrgStructureService.removeTeamMember(UUID teamId, UUID userId) -> void`
  - `GET/POST /api/t/{tenantSlug}/admin/teams/{teamId}/members`, `POST .../members/{userId}/remove`

- [ ] **Step 1: Write the failing test — TEAM scope through the API, not the fixture**

`TenantFixture.addToTeam` writing `team_member` directly is precisely why nobody noticed this gap, so the test must go through the service:

```java
class TeamMembershipTest extends PostgresTestBase {

    @Autowired OrgStructureService org;
    @Autowired CustomerService customers;
    @Autowired TenantFixture fixture;

    /**
     * The assertion that matters is not "a row was written" but "TEAM scope now
     * resolves". Sub-project 1 had the row-writing path in a test fixture and still
     * shipped four role templates that granted nothing.
     */
    @Test
    void aMemberAddedThroughTheApiCanSeeTheTeamsCustomers() {
        UUID tenant = fixture.createTenant("team-live");
        var teamId = new AtomicReference<UUID>();
        var userId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            userId.set(fixture.createUser(tenant, "member@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Acme", null, teamId.get()));
            grantRole(tenant, userId.get(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
        });

        // Before membership: TEAM scope resolves to nothing.
        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))).isEmpty());

        fixture.runAs(tenant, () -> org.addTeamMember(teamId.get(), userId.get()));

        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))
                        .map(CustomerService.CustomerView::id))
                        .containsExactly(customerId.get()));
    }

    @Test
    void removingAMemberRevokesTeamScopeOnTheNextRequest() {
        UUID tenant = fixture.createTenant("team-revoke");
        var teamId = new AtomicReference<UUID>();
        var userId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            userId.set(fixture.createUser(tenant, "leaver@example.com"));
            fixture.createCustomer(tenant, "Acme", null, teamId.get());
            grantRole(tenant, userId.get(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            org.addTeamMember(teamId.get(), userId.get());
        });

        fixture.runAs(tenant, () -> org.removeTeamMember(teamId.get(), userId.get()));

        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))).isEmpty());
    }

    @Test
    void addingAMemberRequiresTeamManage() {
        UUID tenant = fixture.createTenant("team-gate");
        var teamId = new AtomicReference<UUID>();
        var weakUser = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            weakUser.set(fixture.createUser(tenant, "weak@example.com"));
            grantRole(tenant, weakUser.get(), Map.of(PermissionKeys.USER_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, weakUser.get(),
                () -> org.addTeamMember(teamId.get(), weakUser.get())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

Use whatever role-granting helper the existing identity tests use in place of `grantRole`; do not add a new fixture method if one exists.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew cleanTest test --tests '*TeamMembershipTest'`
Expected: FAIL — `addTeamMember` does not exist.

- [ ] **Step 3: Implement the three service methods**

```java
public record TeamMemberView(UUID userId, String fullName, String email) {}

@RequirePermission(PermissionKeys.TEAM_MANAGE)
@Transactional(readOnly = true)
public List<TeamMemberView> listTeamMembers(UUID teamId) {
    Team team = authorizedQuery.getById(teams, Team.class, PermissionKeys.TEAM_MANAGE, teamId);
    return users.findByTeamId(team.getId()).stream()
            .map(u -> new TeamMemberView(u.getId(), u.getFullName(), u.getEmail()))
            .toList();
}

/**
 * Both ids are resolved through AuthorizedQuery before anything is written. A
 * membership row is what TEAM scope resolves against, so writing one from an
 * unresolved id would let a caller widen someone else's visibility -- the write-path
 * shape that produced three separate escalations in sub-project 1.
 */
@RequirePermission(PermissionKeys.TEAM_MANAGE)
@Transactional
public void addTeamMember(UUID teamId, UUID userId) {
    Team team = authorizedQuery.getById(teams, Team.class, PermissionKeys.TEAM_MANAGE, teamId);
    AppUser user = authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, userId);
    if (user.getTeamIds().add(team.getId())) {
        audit.record(AuditActions.TEAM_MEMBER_ADDED, "team", team.getId(),
                "Added " + user.getEmail() + " to team " + team.getName(),
                Map.of("userId", user.getId()));
    }
}

@RequirePermission(PermissionKeys.TEAM_MANAGE)
@Transactional
public void removeTeamMember(UUID teamId, UUID userId) {
    Team team = authorizedQuery.getById(teams, Team.class, PermissionKeys.TEAM_MANAGE, teamId);
    AppUser user = authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, userId);
    if (user.getTeamIds().remove(team.getId())) {
        audit.record(AuditActions.TEAM_MEMBER_REMOVED, "team", team.getId(),
                "Removed " + user.getEmail() + " from team " + team.getName(),
                Map.of("userId", user.getId()));
    }
}
```

`AppUser.teamIds` is the existing `ElementCollection` mapped to `team_member`; mutating it inside a transaction is what writes and deletes the row. `V4` already granted `DELETE ON team_member` with the comment "a pure join table: changing someone's teams means removing rows", so **no migration is needed** — verify that grant exists before assuming it.

Add both action keys to `AuditActions`, `timelineVisible = false` (internal staffing, not the customer's business — the same reasoning `USER_DEACTIVATED` carries):

```java
public static final AuditAction TEAM_MEMBER_ADDED   = of("team.member_added", false);
public static final AuditAction TEAM_MEMBER_REMOVED = of("team.member_removed", false);
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew cleanTest test --tests '*TeamMembershipTest'`
Expected: PASS, all three.

- [ ] **Step 5: Add the three endpoints**

```java
@GetMapping("/teams/{teamId}/members")
public List<OrgStructureService.TeamMemberView> members(@PathVariable UUID teamId) {
    return org.listTeamMembers(teamId);
}

public record AddMemberRequest(UUID userId) {}

@PostMapping("/teams/{teamId}/members")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void addMember(@PathVariable UUID teamId, @RequestBody AddMemberRequest request) {
    org.addTeamMember(teamId, request.userId());
}

@PostMapping("/teams/{teamId}/members/{userId}/remove")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId) {
    org.removeTeamMember(teamId, userId);
}
```

`POST .../remove` rather than `DELETE`, following `POST /customers/{id}/deactivate`.

- [ ] **Step 6: Confirm the derived guard picked them up**

Run: `cd backend && ./gradlew cleanTest test --tests '*DirectApiAccessTest'`
Expected: PASS **with the three new endpoints included** — Task 1's sweep covers them with nothing added by hand. If it fails, the endpoints are reachable anonymously; fix `SecurityConfig`.

- [ ] **Step 7: Add the members panel to the org screen**

`TeamMembers.tsx`: for the selected team, a list of members with a remove button per row, and an add control whose options are users the actor can see (`GET /admin/users`). Every string through `t()`. Reuse `Card`, `Button`, `Dialog`, `EmptyState` — build no new primitive here. Follow `component-specs.md` family 7 for the row rhythm; a member row is 12.5px/500 name over an 11px mono email.

- [ ] **Step 8: Frontend tests and the API hooks**

Add `useTeamMembers`, `useAddTeamMember`, `useRemoveTeamMember` to `lib/api/admin.ts`, and a `TeamMembers.test.tsx` asserting: an empty team renders the empty state; removing a member calls the mutation and invalidates the query; the add control is absent without `team.manage`.

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/identity backend/src/main/java/co/ara/onboarding/audit backend/src/test/java/co/ara/onboarding/identity frontend/src
git commit -m "feat: make TEAM scope real by writing team_member through the API

Nothing in the API wrote team_member, so ctx.teamIds() was empty for every real
user, every descriptor's teamScope returned cb.disjunction(), and Account Manager,
Project Manager, Technical and Support -- which grant only at TEAM -- conferred no
access whatsoever. It failed closed, which is why no test saw it.

The test asserts TEAM scope resolving through CustomerService, not that a row was
written. A fixture writing team_member directly is exactly how this gap survived
sub-project 1, so TeamMembershipTest goes through OrgStructureService and checks
visibility before and after.

No migration: V4 already granted DELETE on team_member with a comment explaining
that a pure join table changes by removing rows. Both ids resolve through
AuthorizedQuery before the write, because a membership row is what TEAM scope
resolves against -- writing one from an unresolved id would widen a third party's
visibility.

Sub-project 2's cases and milestones are TEAM-scoped for precisely these four
templates, so this is a prerequisite for Task 11's descriptors, not cleanup."
```

---

## Task 4: Delegation escalation guard

`CLAUDE.md`: "Until it exists, `user.manage` at any scope is equivalent to the widest role in the tenant. Grant it accordingly." A `user.manage` holder can reach only users inside their scope, but nothing checks the *role being granted* — so they can assign a role wider than their own, to anyone they manage, themselves included. This sub-project adds fifteen permissions, three of which only `Administrator` should ever hold, so the guard lands before them.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/RoleService.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/DelegationGuardTest.java`

**Interfaces:**
- Consumes: `AuthorizationService.effectivePermissions()`, `EffectivePermissions.scopesFor(String)`, `InvalidGrantException`, `Role`, `RoleGrant`.
- Produces: no new signatures — `RoleService.assignRole(...)` gains a precondition. Task 11's fifteen keys inherit its protection automatically.

- [ ] **Step 1: Write the failing tests**

```java
class DelegationGuardTest extends SecurityTestBase {

    /**
     * The escalation sub-project 1 shipped: a DEPARTMENT-scoped user.manage holder
     * assigning a role that grants more than they hold. The target user is inside
     * their scope, so the AuthorizedQuery resolution added in sub-project 1 passes --
     * the hole was never about reaching the user, it was about the role.
     */
    @Test
    void narrowUserManageCannotAssignAWiderRole() {
        UUID tenant = fixture.createTenant("delegate-wide");
        var departmentId = new AtomicReference<UUID>();
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            departmentId.set(fixture.createDepartment(tenant, "Ops"));
            manager.set(fixture.createUserInDepartment(tenant, "mgr@example.com", departmentId.get()));
            target.set(fixture.createUserInDepartment(tenant, "tgt@example.com", departmentId.get()));
            grantRole(tenant, manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide", "", Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(target.get(), wideRole.get())))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("customer.view");
    }

    @Test
    void assigningARoleTheCallerFullyHoldsIsAllowed() {
        UUID tenant = fixture.createTenant("delegate-equal");
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var sameRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "mgr2@example.com", dept));
            target.set(fixture.createUserInDepartment(tenant, "tgt2@example.com", dept));
            grantRole(tenant, manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            sameRole.set(roles.createRole("Same", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT)));
        });

        fixture.runAsUser(tenant, manager.get(), () -> roles.assignRole(target.get(), sameRole.get()));

        fixture.runAs(tenant, () ->
                assertThat(roles.rolesFor(target.get())).contains(sameRole.get()));
    }
    // Executor's amendment (Task 4): `RoleService.rolesFor(UUID)` does not exist,
    // and this task's own "Produces: no new signatures" line rules out adding one.
    // Verify the assignment through the existing `UserRoleDirectory.roleIdsByUser`
    // component instead (autowired directly in the test) -- it is exactly what
    // UserAdminService already uses to answer the same question, so no new
    // production surface is needed:
    //   Set<UUID> held = assignments.roleIdsByUser(Set.of(target.get()))
    //           .getOrDefault(target.get(), Set.of());
    //   assertThat(held).contains(sameRole.get());

    /**
     * DEPARTMENT and TEAM are not comparable, and the guard must not invent an
     * ordering between them. A DEPARTMENT holder assigning a TEAM grant of the same
     * permission is refused -- conservative, and the only answer that cannot be wrong
     * in one direction or the other.
     */
    @Test
    void incomparableScopesAreRefusedRatherThanRanked() {
        UUID tenant = fixture.createTenant("delegate-sideways");
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var teamRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "mgr3@example.com", dept));
            target.set(fixture.createUserInDepartment(tenant, "tgt3@example.com", dept));
            grantRole(tenant, manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            teamRole.set(roles.createRole("Teamish", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(target.get(), teamRole.get())))
                .isInstanceOf(InvalidGrantException.class);
    }

    /**
     * The self-escalation case, which is what makes this a privilege escalation
     * rather than a delegation quirk: nothing stops the manager naming themselves.
     */
    @Test
    void aManagerCannotWidenTheirOwnAuthority() {
        UUID tenant = fixture.createTenant("delegate-self");
        var manager = new AtomicReference<UUID>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "self@example.com", dept));
            grantRole(tenant, manager.get(), Map.of(PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide2", "", Map.of(PermissionKeys.ROLE_MANAGE, Scope.ALL)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(manager.get(), wideRole.get())))
                .isInstanceOf(InvalidGrantException.class);
    }
}
```

Use whatever role-granting helper the existing security tests use in place of `grantRole`; do not add a fixture method if one exists.

- [ ] **Step 2: Run them and watch three fail**

Run: `cd backend && ./gradlew cleanTest test --tests '*DelegationGuardTest'`
Expected: `assigningARoleTheCallerFullyHoldsIsAllowed` PASSES — nothing blocks it today — and the other three FAIL. That split *is* the finding: the current code permits everything.

- [ ] **Step 3: Implement the guard**

```java
/**
 * A caller may only hand out authority they already hold, at a breadth they already
 * have. Comparison, not hierarchy: ALL covers every scope, and anything else must
 * match exactly, because DEPARTMENT and TEAM are sets rather than tiers and ranking
 * them would silently widen one of the two.
 *
 * Every grant is checked and the role is refused whole rather than assigned
 * partially: a partially assigned role is a role whose name no longer describes what
 * it grants.
 */
private void refuseEscalation(Role role) {
    EffectivePermissions mine = authorization.effectivePermissions();
    List<String> exceeded = new ArrayList<>();

    for (RoleGrant grant : role.getGrants()) {
        Set<Scope> held = mine.scopesFor(grant.getPermissionKey());
        boolean covered = held.contains(Scope.ALL) || held.contains(grant.getScope());
        if (!covered) exceeded.add(grant.getPermissionKey() + " at " + grant.getScope());
    }

    if (!exceeded.isEmpty()) {
        throw new InvalidGrantException(
                "Cannot assign a role granting authority you do not hold: " + exceeded);
    }
}
```

Call it in `assignRole` **after** the role and target user are resolved through `AuthorizedQuery` and **before** the `user_role` row is written. Match the real accessors on `Role`/`RoleGrant`; if grants are exposed as a `Map<String, Scope>`, iterate that instead and keep the message format.

Do **not** gate `createRole` the same way: `role.manage` is `ALL`-only and seeded to `Administrator` alone, so creating a wide role already requires the widest authority in the tenant. Put that reasoning in the code comment so the asymmetry does not read as an oversight.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew cleanTest test --tests '*DelegationGuardTest'`
Expected: PASS, all four.

- [ ] **Step 5: Run the full suite — expect existing tests to break**

Run: `cd backend && ./gradlew cleanTest test`
Expected: possible failures in `UserAdminTest`, `RoleLifecycleTest` or `MultipleRolesTest`, wherever a test assigns a role while running as a narrow actor. Each is a real finding about the **test**: fix it by granting the assigner what it hands out, or by performing the assignment as an administrator. If a failure looks like the guard is wrong, re-read Step 3's comment before weakening anything.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/authz/RoleService.java backend/src/test/java/co/ara/onboarding/security/DelegationGuardTest.java
git commit -m "fix: refuse to delegate authority the caller does not hold

Closes the escalation documented at the close of sub-project 1: user.manage at
DEPARTMENT or TEAM reached only users in scope, but nothing checked the role being
granted, so its holder could assign any role in the tenant -- to anyone they
managed, themselves included. user.manage at any scope was therefore equivalent to
the widest role in the tenant.

A comparison, not a hierarchy: ALL covers everything, anything else must match
exactly. DEPARTMENT and TEAM are sets rather than tiers, so ranking them would widen
one of the two; the sideways case is refused deliberately and has its own test.

createRole is intentionally not gated this way -- role.manage is ALL-only and seeded
to Administrator alone, so creating a wide role already needs the widest authority in
the tenant. The reasoning is in the code so the asymmetry does not read as an
oversight.

Sub-project 2 adds fifteen permissions, three of them administrative
(workflow.manage, case.migrate, milestone.force_approve). Without this guard an
unguarded delegation path would hand all three out for free, which is why it lands
before Task 11 rather than after."
```

---

## Task 5: `V12` workflow schema and entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__workflow.sql`
- Create: `backend/src/main/java/co/ara/onboarding/workflow/` — `WorkflowTemplate`, `WorkflowVersion`, `Stage`, `MilestoneDefinition`, `MilestoneDependency`, `RequirementDefinition`, `AttributeDefinition`, `BranchRule`, `Condition`, plus enums `TemplateStatus`, `VersionStatus`, `WriteScope`, `RequirementKind`, `AttributeType`, `ConditionSource`, `ConditionOperator`
- Create: the eight `*Repository` interfaces
- Test: `backend/src/test/java/co/ara/onboarding/workflow/WorkflowPersistenceTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `Uuid7.generate()`, `enable_tenant_rls`.
- Produces: every entity and repository Tasks 6–8 use — `WorkflowVersion.getStatus()`, `Stage.getOrdinal()`, `MilestoneDefinition.getEstimatedDurationDays()`, `RequirementDefinition.getWeight()`, `Condition` (embeddable: `source`, `key`, `operator`, `value`, `values`) — and the `refuse_published_*` triggers Task 7 depends on.

**A deviation from the spec, decided here and carried back into it.** §4 says all fourteen tables get `SELECT, INSERT, UPDATE` and no `DELETE`. That is right for the six `journey` tables and for `workflow_template`, and wrong for the seven definition tables: editing a draft must be able to *remove* a stage, and discarding a draft must delete it. An unpublished definition row is configuration bookkeeping — the same category as `role`, `role_grant`, `user_role`, `team_member` and `login_attempt`, all of which sub-project 1 granted `DELETE` with a comment. So `V12` grants `DELETE` on the seven, and the trigger is what makes that safe: any `UPDATE` or `DELETE` whose version is not explicitly `DRAFT` is refused, so deletion is reachable for drafts only.

- [ ] **Step 1: Write the failing persistence test**

```java
class WorkflowPersistenceTest extends PostgresTestBase {

    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;   // the onboarding_app connection

    @Test
    void aDraftVersionAndItsStagesCanBeWrittenAndRead() {
        UUID tenant = fixture.createTenant("wf-write");
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Standard");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            stages.save(newStage(tenant, v, 1, "Registration"));

            assertThat(stages.findByVersionIdOrderByOrdinal(v.getId()))
                    .extracting(Stage::getName).containsExactly("Registration");
        });
    }

    /**
     * The frozen-workflow promise, at the storage layer. A published version the
     * application can still rewrite is frozen by convention, and every case pinned to
     * it is trusting that convention.
     */
    @Test
    void aPublishedVersionCannotBeUpdatedOrDeleted() {
        UUID tenant = fixture.createTenant("wf-frozen");
        var versionId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Frozen");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            stages.save(newStage(tenant, v, 1, "Registration"));
            versionId.set(v.getId());
        });

        // Publish by the only route that may: a DRAFT -> PUBLISHED update.
        fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE workflow_version SET status = 'PUBLISHED' WHERE id = ?", versionId.get()));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE workflow_version SET version_no = 99 WHERE id = ?", versionId.get())))
                .hasMessageContaining("published");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "DELETE FROM stage WHERE version_id = ?", versionId.get())))
                .hasMessageContaining("published");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "UPDATE stage SET name = 'Renamed' WHERE version_id = ?", versionId.get())))
                .hasMessageContaining("published");
    }

    @Test
    void aDraftsStagesCanBeDeleted() {
        UUID tenant = fixture.createTenant("wf-draft-delete");
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Editable");
            templates.save(t);
            WorkflowVersion v = newDraft(tenant, t, 1);
            versions.save(v);
            Stage s = newStage(tenant, v, 1, "Registration");
            stages.save(s);

            stages.delete(s);
            assertThat(stages.findByVersionIdOrderByOrdinal(v.getId())).isEmpty();
        });
    }

    /**
     * workflow_template is a business record -- deactivated, never deleted -- so the
     * schema-wide DELETE denial still applies to it. The seven definition tables carry
     * an explicit grant; this one must not.
     */
    @Test
    void aTemplateCannotBeDeleted() {
        UUID tenant = fixture.createTenant("wf-template-delete");
        var templateId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Permanent");
            templates.save(t);
            templateId.set(t.getId());
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "DELETE FROM workflow_template WHERE id = ?", templateId.get())))
                .hasMessageContaining("permission denied");
    }

    @Test
    void twoDraftsForOneTemplateAreRefused() {
        UUID tenant = fixture.createTenant("wf-one-draft");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            WorkflowTemplate t = newTemplate(tenant, "Single");
            templates.save(t);
            versions.save(newDraft(tenant, t, 1));
            versions.saveAndFlush(newDraft(tenant, t, 2));
        })).isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

Write `newTemplate`, `newDraft` and `newStage` as private helpers in the test class, each setting `id` via `Uuid7.generate()` and `tenantId` explicitly.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew cleanTest test --tests '*WorkflowPersistenceTest'`
Expected: FAIL — no such tables, no such classes.

- [ ] **Step 3: Write `V12__workflow.sql`**

```sql
-- Sub-project 2, half one: the definition side. Nothing here references a case and
-- nothing here counts them -- "31 cases on v4" is computed in journey at read time. A
-- workflow table gaining a case column would make a definition module depend on
-- runtime state, which ModuleBoundaryTest.noWorkflowDependencyOnJourney forbids in
-- code and this file forbids in schema.

CREATE TABLE workflow_template (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenant(id),
    name               varchar(160) NOT NULL,
    description        text,
    status             varchar(16) NOT NULL,
    current_version_id uuid,
    created_by         uuid REFERENCES app_user(id),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

-- lower(name), not name. Sub-project 1 shipped customer_contact unique on
-- (customer_id, email) case-SENSITIVELY while app_user was unique on
-- (tenant_id, lower(email)); the disagreement accepted two contacts differing only in
-- case and then failed the second one's activation as an "invalid token".
CREATE UNIQUE INDEX workflow_template_tenant_name_key
    ON workflow_template (tenant_id, lower(name));

CREATE TABLE workflow_version (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    template_id  uuid NOT NULL REFERENCES workflow_template(id),
    version_no   int  NOT NULL,
    status       varchar(16) NOT NULL,
    -- JPA @Version. Two administrators editing one draft is the normal case in a small
    -- tenant, and last-writer-wins on a whole-graph PUT silently discards the other's
    -- stages.
    lock_version bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    published_by uuid REFERENCES app_user(id),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (template_id, version_no)
);

-- One draft per template: two admins editing the same workflow collide immediately
-- rather than silently forking two drafts that both later claim to be v5.
CREATE UNIQUE INDEX workflow_version_one_draft_per_template
    ON workflow_version (template_id) WHERE status = 'DRAFT';

ALTER TABLE workflow_template
    ADD CONSTRAINT workflow_template_current_version_fk
    FOREIGN KEY (current_version_id) REFERENCES workflow_version(id);

-- Every child table carries version_id, even where a parent would reach it. Two
-- reasons: the immutability trigger below is then one uniform function rather than one
-- per depth, and loading a whole definition is five indexed reads by version_id
-- instead of a nested join per level.
CREATE TABLE stage (
    id                        uuid PRIMARY KEY,
    tenant_id                 uuid NOT NULL REFERENCES tenant(id),
    version_id                uuid NOT NULL REFERENCES workflow_version(id),
    ordinal                   int  NOT NULL,
    name                      varchar(160) NOT NULL,
    responsible_department_id uuid REFERENCES department(id),
    requires_approval         boolean NOT NULL DEFAULT false,
    auto_advance              boolean NOT NULL DEFAULT true,
    portal_visible            boolean NOT NULL DEFAULT true,
    -- Expected effort drives the schedule (milestone_definition below); sla_days is the
    -- promise the tenant makes about this stage and drives breach in sub-project 6. A
    -- stage can be planned at three days and promised in five.
    sla_days                  int,
    -- Subtractive only: it narrows who may write inside this stage after the permission
    -- gate has already said yes, and has no branch that grants (§6.3).
    write_scope               varchar(16) NOT NULL DEFAULT 'ANY',
    -- Authored here, acted on in sub-project 6. The builder renders it disabled.
    notification_template_key varchar(64),
    entry_source              varchar(16),
    entry_key                 varchar(64),
    entry_operator            varchar(8),
    entry_value               varchar(255),
    entry_values              text[],
    fallback_next_stage_id    uuid REFERENCES stage(id),
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    UNIQUE (version_id, ordinal)
);

CREATE TABLE milestone_definition (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    stage_id                uuid NOT NULL REFERENCES stage(id),
    ordinal                 int  NOT NULL,
    name                    varchar(160) NOT NULL,
    description             text,
    -- Q6's weight, and the schedule's unit. NOT NULL: a milestone with no duration
    -- contributes nothing to a weighted progress calculation, which reads as a
    -- milestone that does not count.
    estimated_duration_days int  NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (stage_id, ordinal)
);

CREATE TABLE milestone_dependency (
    id                                 uuid PRIMARY KEY,
    tenant_id                          uuid NOT NULL REFERENCES tenant(id),
    version_id                         uuid NOT NULL REFERENCES workflow_version(id),
    milestone_definition_id            uuid NOT NULL REFERENCES milestone_definition(id),
    depends_on_milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    created_at                         timestamptz NOT NULL,
    updated_at                         timestamptz NOT NULL,
    UNIQUE (milestone_definition_id, depends_on_milestone_definition_id)
);

CREATE TABLE requirement_definition (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    ordinal                 int  NOT NULL,
    kind                    varchar(16) NOT NULL,
    label                   varchar(200) NOT NULL,
    weight                  int  NOT NULL DEFAULT 1,
    mandatory               boolean NOT NULL DEFAULT true,
    -- Typed nullable columns per kind, not a params jsonb. A JSON bag invites
    -- sub-projects 3-5 to write whatever they like into a column nobody validates; a
    -- typed column makes each of them add a forward-only migration deliberately.
    document_category       varchar(64),
    approver_relationship   varchar(16),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (milestone_definition_id, ordinal)
);

CREATE TABLE attribute_definition (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    version_id     uuid NOT NULL REFERENCES workflow_version(id),
    ordinal        int  NOT NULL,
    key            varchar(64)  NOT NULL,
    label          varchar(200) NOT NULL,
    data_type      varchar(16)  NOT NULL,
    required       boolean NOT NULL DEFAULT false,
    allowed_values text[],
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (version_id, key)
);

CREATE TABLE branch_rule (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    version_id      uuid NOT NULL REFERENCES workflow_version(id),
    stage_id        uuid NOT NULL REFERENCES stage(id),
    ordinal         int  NOT NULL,
    source          varchar(16) NOT NULL,
    key             varchar(64) NOT NULL,
    operator        varchar(8)  NOT NULL,
    value           varchar(255),
    values          text[],
    target_stage_id uuid NOT NULL REFERENCES stage(id),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (stage_id, ordinal)
);

-- Every index leads with tenant_id: RLS adds a tenant_id predicate to every query, so
-- an index without it cannot serve one.
CREATE INDEX workflow_version_tenant_template_idx  ON workflow_version (tenant_id, template_id);
CREATE INDEX stage_tenant_version_idx              ON stage (tenant_id, version_id);
CREATE INDEX milestone_definition_tenant_ver_idx   ON milestone_definition (tenant_id, version_id);
CREATE INDEX milestone_dependency_tenant_ver_idx   ON milestone_dependency (tenant_id, version_id);
CREATE INDEX requirement_definition_tenant_ver_idx ON requirement_definition (tenant_id, version_id);
CREATE INDEX attribute_definition_tenant_ver_idx   ON attribute_definition (tenant_id, version_id);
CREATE INDEX branch_rule_tenant_version_idx        ON branch_rule (tenant_id, version_id);

-- Immutability. audit_event is append-only by GRANT because an audit trail the
-- application can rewrite is not evidence; a published workflow is frozen by trigger
-- for the same reason -- every running case is pinned to it, and "frozen" enforced only
-- in the service that happens to write it is a promise, not a property.
CREATE OR REPLACE FUNCTION refuse_published_version_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'workflow version % is published and cannot be modified', OLD.id;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

-- Fails CLOSED: only an explicitly DRAFT parent permits a write. Written as IS DISTINCT
-- FROM rather than <> for exactly that reason -- a row hidden by RLS or a missing parent
-- yields NULL, `NULL <> 'DRAFT'` is NULL, and an IF treats NULL as false, so the write
-- would have been allowed.
CREATE OR REPLACE FUNCTION refuse_published_child_write() RETURNS trigger AS $$
DECLARE
    v_id     uuid := COALESCE(OLD.version_id, NEW.version_id);
    v_status text;
BEGIN
    SELECT status INTO v_status FROM workflow_version WHERE id = v_id;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'workflow version % is published and cannot be modified', v_id;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_version_frozen
    BEFORE UPDATE OR DELETE ON workflow_version
    FOR EACH ROW EXECUTE FUNCTION refuse_published_version_change();

CREATE TRIGGER stage_frozen BEFORE UPDATE OR DELETE ON stage
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER milestone_definition_frozen BEFORE UPDATE OR DELETE ON milestone_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER milestone_dependency_frozen BEFORE UPDATE OR DELETE ON milestone_dependency
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER requirement_definition_frozen BEFORE UPDATE OR DELETE ON requirement_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER attribute_definition_frozen BEFORE UPDATE OR DELETE ON attribute_definition
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();
CREATE TRIGGER branch_rule_frozen BEFORE UPDATE OR DELETE ON branch_rule
    FOR EACH ROW EXECUTE FUNCTION refuse_published_child_write();

SELECT enable_tenant_rls('workflow_template');
SELECT enable_tenant_rls('workflow_version');
SELECT enable_tenant_rls('stage');
SELECT enable_tenant_rls('milestone_definition');
SELECT enable_tenant_rls('milestone_dependency');
SELECT enable_tenant_rls('requirement_definition');
SELECT enable_tenant_rls('attribute_definition');
SELECT enable_tenant_rls('branch_rule');

GRANT SELECT, INSERT, UPDATE ON
    workflow_template, workflow_version, stage, milestone_definition,
    milestone_dependency, requirement_definition, attribute_definition, branch_rule
    TO onboarding_app;

-- DELETE on the seven definition tables, and deliberately NOT on workflow_template.
-- Editing a draft must be able to remove a stage, and discarding a draft must delete
-- it; an unpublished definition row is configuration bookkeeping -- the same category
-- as role, role_grant, user_role, team_member and login_attempt in sub-project 1. What
-- makes the grant safe is the trigger above: a DELETE whose version is not explicitly
-- DRAFT is refused, so deletion is reachable for drafts only. A template is a business
-- record and is deactivated instead.
GRANT DELETE ON
    workflow_version, stage, milestone_definition, milestone_dependency,
    requirement_definition, attribute_definition, branch_rule
    TO onboarding_app;
```

- [ ] **Step 4: Write the entities, enums and repositories**

Each entity extends `TenantScopedEntity`, uses `@Enumerated(EnumType.STRING)`, and maps `text[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`. `WorkflowVersion` carries `@Version private long lockVersion;` mapped to `lock_version`. `Condition` is an `@Embeddable` (`source`, `key`, `operator`, `value`, `values`) embedded twice — in `Stage` with `@AttributeOverrides` onto the `entry_*` columns, and in `BranchRule` with the bare names.

The complete enum set for this sub-project:

```java
public enum TemplateStatus { ACTIVE, INACTIVE }
public enum VersionStatus { DRAFT, PUBLISHED }
public enum WriteScope { ANY, DEPARTMENT, TEAM, OWNER_ONLY }
public enum RequirementKind { TASK, DOCUMENT, APPROVAL, MANUAL }
public enum AttributeType { STRING, NUMBER, BOOLEAN, ENUM, DATE }
public enum ConditionSource { CUSTOMER, ATTRIBUTE }
public enum ConditionOperator { EQ, NEQ, GT, GTE, LT, LTE, IN, IS_SET }
```

Repositories extend `JpaRepository<T, UUID>` **and `JpaSpecificationExecutor<T>`** — `AuthorizedQuery` requires the latter. The finders later tasks need:

```java
List<Stage> findByVersionIdOrderByOrdinal(UUID versionId);
List<MilestoneDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
List<MilestoneDependency> findByVersionId(UUID versionId);
List<RequirementDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
List<AttributeDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
List<BranchRule> findByVersionIdOrderByOrdinal(UUID versionId);
Optional<WorkflowVersion> findByTemplateIdAndStatus(UUID templateId, VersionStatus status);
List<WorkflowVersion> findByTemplateIdOrderByVersionNoDesc(UUID templateId);
```

The finder rule now covers `..workflow..`, so each of these must be reached from a service that has **already** resolved the version through `AuthorizedQuery` — Task 6 Step 3 shows the pattern: resolve the version under `WORKFLOW_VIEW`, then read its children by `version_id`.

- [ ] **Step 5: Run the persistence test**

Run: `cd backend && ./gradlew cleanTest test --tests '*WorkflowPersistenceTest'`
Expected: PASS, all five. If `aPublishedVersionCannotBeUpdatedOrDeleted` passes for the wrong reason — a permission error rather than the trigger — the message tells you: it must contain "published", not "permission denied".

- [ ] **Step 6: Confirm RLS coverage**

Run: `cd backend && ./gradlew cleanTest test --tests '*RlsCoverageTest'`
Expected: PASS with all eight tables covered and **no allowlist entry added**. A failure naming a new table means the migration is missing its `enable_tenant_rls` call — fix the migration, never the allowlist.

- [ ] **Step 7: Full suite, then commit**

Run: `cd backend && ./gradlew cleanTest test`

```bash
git add backend/src/main/resources/db/migration/V12__workflow.sql backend/src/main/java/co/ara/onboarding/workflow backend/src/test/java/co/ara/onboarding/workflow
git commit -m "feat: add the workflow definition schema, frozen at the storage layer

Eight tables, RLS on every one, no allowlist entry added. Published versions are
immutable by trigger rather than by service discipline: every running case is pinned
to a version, and 'frozen' enforced only in the code that happens to write it is a
promise rather than a property -- the same argument that makes audit_event
append-only by GRANT.

DEVIATION FROM THE SPEC, which is amended to match: §4 said no DELETE on any of the
fourteen tables. Editing a draft must be able to remove a stage and discarding a
draft must delete it, so the seven definition tables carry an explicit GRANT DELETE
-- the role/role_grant/team_member category from sub-project 1, not business
records. What makes it safe is the trigger: a write whose version is not explicitly
DRAFT is refused, so deletion is reachable for drafts only, and workflow_template
still cannot be deleted at all. Both halves have tests.

The child-write trigger uses IS DISTINCT FROM, not <>. A row hidden by RLS or a
missing parent yields NULL, and NULL <> 'DRAFT' is NULL, which an IF treats as false
-- the write would have been allowed. It fails closed instead.

Every child table carries version_id even where a parent would reach it: the trigger
is then one function instead of one per depth, and loading a definition is five
indexed reads rather than a nested join per level.

Template names are unique on (tenant_id, lower(name)). Sub-project 1's
case-sensitive contact uniqueness disagreed with app_user's lower(email) and
accepted two contacts differing only in case."
```

---

## Task 6: Template and draft authoring

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowService.java`
- Create: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowDefinitionView.java`, `WorkflowDefinitionRequest.java`, `WorkflowTemplateView.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`, `PermissionCatalog.java`, `RoleTemplates.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/WorkflowAuthoringTest.java`

**Ordering note.** §6.1 of the spec lists all fifteen keys together. The plan adds the two `workflow.*` keys here, where they are first needed, and the thirteen `journey` keys in Task 11 with their descriptors. Both `workflow.*` keys are `ALL`-only with a null `resourceType`, so they need no descriptor and cannot break `DescriptorRegistry.validate()` — which is exactly why they can be added early and the others cannot.

**Interfaces:**
- Consumes: everything from Task 5; `AuthorizedQuery`, `AuditRecorder`, `AuthContextProvider`.
- Produces:
  - `PermissionKeys.WORKFLOW_VIEW = "workflow.view"`, `WORKFLOW_MANAGE = "workflow.manage"`
  - `WorkflowService.createTemplate(String name, String description) -> WorkflowTemplateView`
  - `WorkflowService.listTemplates() -> List<WorkflowTemplateView>`
  - `WorkflowService.deactivateTemplate(UUID templateId) -> void`
  - `WorkflowService.createDraft(UUID templateId) -> UUID` (deep copy of `currentVersionId`, or an empty draft if there is none)
  - `WorkflowService.getDefinition(UUID versionId) -> WorkflowDefinitionView`
  - `WorkflowService.replaceDraft(UUID versionId, WorkflowDefinitionRequest request) -> WorkflowDefinitionView`
  - `WorkflowService.discardDraft(UUID versionId) -> void`
  - The `WorkflowDefinitionRequest`/`View` records, used verbatim by Tasks 7, 8, 24.

- [ ] **Step 1: Define the two request/view shapes first**

They are the contract Tasks 7, 8 and 24 all bind to, so write them before any test.

```java
/**
 * A draft is edited as ONE document. Reordering stages, deleting one a branch rule
 * targets, renaming a milestone another depends on -- these are graph edits, and
 * per-element endpoints leave dangling references between calls that publish then has
 * to reject. One request validates the graph once and writes it atomically.
 *
 * Cross-references use client-supplied `key` strings, not ids. A newly added stage has
 * no id yet, so a branch rule targeting it cannot name one; asking the client to
 * round-trip through the server for each insert would make reordering three stages a
 * three-request transaction. The server assigns UUIDv7s and resolves keys once.
 */
public record WorkflowDefinitionRequest(
        List<StageRequest> stages,
        List<AttributeRequest> attributes,
        long lockVersion) {

    public record StageRequest(
            String key,                        // client-local, unique within the request
            String name,
            UUID responsibleDepartmentId,
            boolean requiresApproval,
            boolean autoAdvance,
            boolean portalVisible,
            Integer slaDays,
            WriteScope writeScope,
            String notificationTemplateKey,
            ConditionRequest entryCondition,   // null = always enterable
            String fallbackNextStageKey,       // null = next by ordinal
            List<MilestoneRequest> milestones,
            List<BranchRuleRequest> branchRules) {}

    public record MilestoneRequest(
            String key,
            String name,
            String description,
            int estimatedDurationDays,
            List<String> dependsOnMilestoneKeys,
            List<RequirementRequest> requirements) {}

    public record RequirementRequest(
            RequirementKind kind,
            String label,
            int weight,
            boolean mandatory,
            String documentCategory,
            RelationshipType approverRelationship) {}

    public record BranchRuleRequest(
            ConditionRequest condition,
            String targetStageKey) {}       // a key from this same request, resolved server-side

    public record AttributeRequest(
            String key,
            String label,
            AttributeType dataType,
            boolean required,
            List<String> allowedValues) {}

    public record ConditionRequest(
            ConditionSource source,
            String key,
            ConditionOperator operator,
            String value,
            List<String> values) {}
}
```

```java
/**
 * The read side of the same graph. It returns every field the request accepts, plus
 * server-assigned ids and the lock version -- CLAUDE.md's full-replace invariant: a
 * PUT whose view omits a field makes every client that loads, edits one thing and
 * saves erase whatever it was never given.
 *
 * `key` is echoed back as the stage's own id in string form, so a client that GETs
 * then PUTs unchanged is a no-op rather than a rebuild.
 */
public record WorkflowDefinitionView(
        UUID versionId, UUID templateId, int versionNo, VersionStatus status,
        long lockVersion, Instant publishedAt,
        List<StageView> stages, List<AttributeView> attributes) {
    // Nested views mirror the request records field for field, each adding `UUID id`.
}
```

- [ ] **Step 2: Write the failing authoring tests**

```java
class WorkflowAuthoringTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired TenantFixture fixture;

    @Test
    void creatingATemplateCreatesAnEmptyDraft() {
        UUID tenant = fixture.createTenant("author-create");
        fixture.runAs(tenant, () -> {
            var view = workflows.createTemplate("Standard Enterprise", "The default");
            assertThat(view.status()).isEqualTo(TemplateStatus.ACTIVE);
            assertThat(view.currentVersionNo()).isNull();       // nothing published yet

            UUID draftId = workflows.createDraft(view.id());
            var definition = workflows.getDefinition(draftId);
            assertThat(definition.status()).isEqualTo(VersionStatus.DRAFT);
            assertThat(definition.versionNo()).isEqualTo(1);
            assertThat(definition.stages()).isEmpty();
        });
    }

    /**
     * The whole-document replace, including the two things per-element endpoints get
     * wrong: a branch rule targeting a stage that has no id yet, and a dependency
     * between two milestones added in the same request.
     */
    @Test
    void replacingADraftWritesTheWholeGraphAndResolvesKeys() {
        UUID tenant = fixture.createTenant("author-replace");
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Keys", "");
            UUID draftId = workflows.createDraft(template.id());

            var request = new WorkflowDefinitionRequest(
                List.of(
                    stage("reg", "Registration", List.of(
                        milestone("company", "Company details", 2, List.of(),
                                  List.of(manual("Capture legal name"))),
                        milestone("kyc", "KYC pack", 3, List.of("company"),
                                  List.of(document("KYC bundle", "kyc"))))),
                    stage("legal", "Legal Review", List.of(
                        milestone("nda", "NDA signed", 5, List.of(), List.of(manual("Countersign"))))),
                    stage("live", "Go Live", List.of(
                        milestone("switch", "Switch on", 1, List.of(), List.of(manual("Flip"))))))
                .stream().toList(),
                List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                        List.of("ENTERPRISE", "SMB"))),
                0L);

            // Registration branches to Go Live for SMB, skipping Legal Review.
            request = withBranch(request, "reg", "segment", "SMB", "live");

            var saved = workflows.replaceDraft(draftId, request);

            assertThat(saved.stages()).extracting(StageView::name)
                    .containsExactly("Registration", "Legal Review", "Go Live");
            UUID goLiveId = saved.stages().get(2).id();
            assertThat(saved.stages().get(0).branchRules().get(0).targetStageId())
                    .isEqualTo(goLiveId);

            var registration = saved.stages().get(0);
            UUID companyId = registration.milestones().get(0).id();
            assertThat(registration.milestones().get(1).dependsOnMilestoneIds())
                    .containsExactly(companyId);
        });
    }

    @Test
    void replacingADraftRemovesStagesTheRequestOmits() {
        UUID tenant = fixture.createTenant("author-remove");
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Shrink", "");
            UUID draftId = workflows.createDraft(template.id());
            workflows.replaceDraft(draftId, twoStages());

            var one = workflows.replaceDraft(draftId, oneStage(workflows.getDefinition(draftId)));
            assertThat(one.stages()).hasSize(1);
        });
    }

    /**
     * Two administrators on one draft is the normal case in a small tenant, and the
     * loser must be told rather than silently overwritten -- a whole-graph PUT
     * discards the other's entire set of stages, not one field.
     */
    @Test
    void aStaleLockVersionIsRejected() {
        UUID tenant = fixture.createTenant("author-stale");
        fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Race", "");
            UUID draftId = workflows.createDraft(template.id());
            var first = workflows.replaceDraft(draftId, twoStages());

            var stale = new WorkflowDefinitionRequest(
                    twoStages().stages(), twoStages().attributes(), first.lockVersion() - 1);

            assertThatThrownBy(() -> workflows.replaceDraft(draftId, stale))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        });
    }

    @Test
    void authoringRequiresWorkflowManage() {
        UUID tenant = fixture.createTenant("author-gate");
        var reader = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            reader.set(fixture.createUser(tenant, "reader@example.com"));
            grantRole(tenant, reader.get(), Map.of(PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reader.get(),
                () -> workflows.createTemplate("Sneaky", "")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSecondDraftIsRefusedWhileOneIsOpen() {
        UUID tenant = fixture.createTenant("author-two-drafts");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            var template = workflows.createTemplate("Once", "");
            workflows.createDraft(template.id());
            workflows.createDraft(template.id());
        })).isInstanceOf(DraftAlreadyExistsException.class);
    }
}
```

Write `stage`, `milestone`, `manual`, `document`, `withBranch`, `twoStages` and `oneStage` as private builders in the test class. They will be reused verbatim by Tasks 7, 13 and 19, so put them in a shared `WorkflowFixtures` test helper rather than duplicating them later.

- [ ] **Step 3: Run and watch it fail, then implement `WorkflowService`**

Run: `cd backend && ./gradlew cleanTest test --tests '*WorkflowAuthoringTest'` — FAIL, no such class.

The one method worth writing out, because its ordering is what makes the graph consistent:

```java
@RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
@Transactional
public WorkflowDefinitionView replaceDraft(UUID versionId, WorkflowDefinitionRequest request) {
    // Resolved through AuthorizedQuery, never by raw id: this is a write path taking an
    // id from a URL, which is the shape that produced three escalations in sub-project 1.
    WorkflowVersion version = authorizedQuery.getById(
            versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_MANAGE, versionId);

    if (version.getStatus() != VersionStatus.DRAFT) {
        throw new VersionNotEditableException(versionId);
    }
    if (request.lockVersion() != version.getLockVersion()) {
        throw new OptimisticLockingFailureException(
                "Draft " + versionId + " was modified by someone else");
    }

    validateRequestShape(request);   // keys unique, references resolvable, ordinals dense

    // Children first, parents second: branch_rule and milestone_dependency reference
    // stage and milestone_definition, so deleting in the other order trips the FKs. The
    // trigger permits these deletes only because the version is DRAFT.
    branchRules.deleteByVersionId(versionId);
    dependencies.deleteByVersionId(versionId);
    requirementDefinitions.deleteByVersionId(versionId);
    milestoneDefinitions.deleteByVersionId(versionId);
    stages.deleteByVersionId(versionId);
    attributeDefinitions.deleteByVersionId(versionId);

    // Pass 1: stages and milestones, recording key -> assigned id.
    Map<String, UUID> stageIds = new LinkedHashMap<>();
    Map<String, UUID> milestoneIds = new LinkedHashMap<>();
    int stageOrdinal = 1;
    for (var s : request.stages()) {
        Stage stage = newStage(version, s, stageOrdinal++);
        stages.save(stage);
        stageIds.put(s.key(), stage.getId());

        int milestoneOrdinal = 1;
        for (var m : s.milestones()) {
            MilestoneDefinition definition = newMilestone(version, stage, m, milestoneOrdinal++);
            milestoneDefinitions.save(definition);
            milestoneIds.put(m.key(), definition.getId());

            int requirementOrdinal = 1;
            for (var r : m.requirements()) {
                requirementDefinitions.save(
                        newRequirement(version, definition, r, requirementOrdinal++));
            }
        }
    }

    // Pass 2: everything that references a key assigned in pass 1. Two passes rather
    // than one is what allows a branch rule to target a stage declared after it, and a
    // dependency to name a milestone in the same request.
    for (var s : request.stages()) {
        UUID stageId = stageIds.get(s.key());
        if (s.fallbackNextStageKey() != null) {
            stages.findById(stageId).orElseThrow()
                  .setFallbackNextStageId(resolve(stageIds, s.fallbackNextStageKey()));
        }
        int ruleOrdinal = 1;
        for (var rule : s.branchRules()) {
            branchRules.save(newBranchRule(version, stageId, rule, ruleOrdinal++,
                    resolve(stageIds, rule.targetStageKey())));
        }
        for (var m : s.milestones()) {
            for (String dependsOn : m.dependsOnMilestoneKeys()) {
                dependencies.save(newDependency(version,
                        milestoneIds.get(m.key()), resolve(milestoneIds, dependsOn)));
            }
        }
    }

    for (var a : request.attributes()) {
        attributeDefinitions.save(newAttribute(version, a));
    }

    version.setLockVersion(version.getLockVersion() + 1);
    audit.record(AuditActions.WORKFLOW_DRAFT_SAVED, "workflow_version", versionId,
            "Saved draft v" + version.getVersionNo() + " of " + templateName(version),
            Map.of("stages", request.stages().size()));
    return getDefinition(versionId);
}
```

`resolve(map, key)` throws `UnknownReferenceException` naming the key when it is absent — a client typo must be a 400 that says which key, not a null foreign key. `validateRequestShape` checks only what is *structurally* impossible (duplicate keys, a branch target that is not a declared stage, a dependency naming an unknown milestone); the five *semantic* validations belong to publish, Task 7, because a draft is allowed to be temporarily incoherent while an admin edits it.

`createDraft` deep-copies `currentVersionId`'s graph by reading it into a `WorkflowDefinitionRequest` and calling `replaceDraft` on the new empty draft — one code path for copying and editing, so a field added to the graph cannot be forgotten in the copy.

Add to `AuditActions`, all `timelineVisible = false` — workflow authoring is tenant configuration, not the customer's business:

```java
public static final AuditAction WORKFLOW_TEMPLATE_CREATED     = of("workflow.template_created", false);
public static final AuditAction WORKFLOW_TEMPLATE_DEACTIVATED = of("workflow.template_deactivated", false);
public static final AuditAction WORKFLOW_DRAFT_SAVED          = of("workflow.draft_saved", false);
public static final AuditAction WORKFLOW_DRAFT_DISCARDED      = of("workflow.draft_discarded", false);
public static final AuditAction WORKFLOW_PUBLISHED            = of("workflow.published", false);
```

Add the two keys and catalogue them:

```java
add(WORKFLOW_VIEW,   "workflow", null, "View workflow definitions",   ALL_ONLY);
add(WORKFLOW_MANAGE, "workflow", null, "Create and edit workflows",   ALL_ONLY);
```

Both `ALL`-only with a null `resourceType`: a workflow definition is tenant-wide configuration with no owner, so there is nothing for DEPARTMENT or TEAM to resolve against. In `RoleTemplates`, give `WORKFLOW_MANAGE` to `Administrator` only — the reasoning `ROLE_MANAGE` already carries — and `WORKFLOW_VIEW` at `ALL` to every operational template, since anyone working a case needs to read the definition it is frozen on.

- [ ] **Step 4: Run the tests, then the guards**

```bash
cd backend && ./gradlew cleanTest test --tests '*WorkflowAuthoringTest'
./gradlew cleanTest test --tests '*AuthorizationCoverageTest' --tests '*RoleTemplateValidityTest' --tests '*DescriptorRegistryTest'
```
Expected: PASS. `AuthorizationCoverageTest` is the one to watch — every public `WorkflowService` method must carry `@RequirePermission`, and the finder rule now covers `..workflow..`, so a repository finder called without a prior `AuthorizedQuery` resolution fails the build.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow backend/src/main/java/co/ara/onboarding/authz backend/src/main/java/co/ara/onboarding/audit backend/src/test/java/co/ara/onboarding/workflow
git commit -m "feat: author workflow drafts as one document, not per element

A draft is replaced whole. Reordering stages, deleting one a branch rule targets,
renaming a milestone another depends on are graph edits; per-element endpoints leave
dangling references between calls that publish then has to reject. One request
validates the graph once and writes it atomically, and the view returns every field
the request accepts so a load-edit-save cycle cannot erase what it was never given.

Cross-references use client-supplied keys rather than ids, resolved server-side in
two passes. A stage added in this request has no id, so a branch rule targeting it
cannot name one -- and making the client round-trip per insert would turn reordering
three stages into a three-request transaction. Two passes are what let a rule target
a later stage and a dependency name a sibling milestone.

Deletes run children-first: branch_rule and milestone_dependency reference stage and
milestone_definition. V12's trigger permits them only because the version is DRAFT.

lockVersion is checked explicitly rather than left to JPA, because the failure is
worth a clear 409: on a whole-graph PUT the loser does not lose one field, they lose
every stage they entered.

workflow.view and workflow.manage are added here rather than with Task 11's thirteen
keys because both are ALL-only with a null resourceType, so they need no descriptor
and cannot break DescriptorRegistry.validate(). Administrator alone gets
workflow.manage; every operational template gets workflow.view."
```

---

## Task 7: Publish validation and freeze

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/PublishService.java`, `PublishValidationException.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PublishValidationTest.java`

**Interfaces:**
- Consumes: Task 6's `WorkflowService`, `WorkflowDefinitionView`, the repositories, `Condition`.
- Produces:
  - `PublishService.publish(UUID versionId) -> WorkflowDefinitionView`
  - `PublishValidationException.problems() -> List<String>` — the whole list, mapped to 422 in Task 8
  - After publish: `version.status = PUBLISHED`, `publishedAt`/`publishedBy` set, `template.currentVersionId = versionId`

- [ ] **Step 1: Write one failing test per validation, plus the happy path**

Each rule gets its own test because a single "invalid workflow is rejected" test passes when only one rule works:

```java
class PublishValidationTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired TenantFixture fixture;

    @Test
    void aValidWorkflowPublishesAndBecomesCurrent() {
        UUID tenant = fixture.createTenant("pub-ok");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(threeValidStages());
            var published = publisher.publish(draftId);

            assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(published.publishedAt()).isNotNull();
            assertThat(workflows.getTemplate(published.templateId()).currentVersionId())
                    .isEqualTo(draftId);
        });
    }

    /** Rule 1. Without it the skip loop can walk off the end of the stage list. */
    @Test
    void aFinalStageWithAnEntryConditionIsRefused() {
        UUID tenant = fixture.createTenant("pub-final");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(lastStageConditionalOn("segment", "SMB"));
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .hasMessageContaining("final stage");
        });
    }

    /** Rule 2. Forward-only targets are what make the graph a DAG by construction. */
    @Test
    void aBranchTargetingAnEarlierStageIsRefused() {
        UUID tenant = fixture.createTenant("pub-backward");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(branchFromStage(3).toStage(1));
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .hasMessageContaining("forward");
        });
    }

    /** Rule 3. A forward dependency describes a plan the engine will never follow. */
    @Test
    void aDependencyPointingForwardIsRefused() {
        UUID tenant = fixture.createTenant("pub-dep");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(firstMilestoneDependingOnTheSecond());
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .hasMessageContaining("earlier");
        });
    }

    /** Rule 4. An undeclared key would evaluate false forever and skip a stage silently. */
    @Test
    void aConditionNamingAnUndeclaredAttributeIsRefused() {
        UUID tenant = fixture.createTenant("pub-attr");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(conditionOnAttribute("tier"));   // never declared
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .hasMessageContaining("tier");
        });
    }

    /** Rule 5. A stage with no milestones is exitable the instant it is entered. */
    @Test
    void aStageWithNoMilestonesIsRefused() {
        UUID tenant = fixture.createTenant("pub-empty");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(anEmptyStage());
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .hasMessageContaining("milestone");
        });
    }

    /**
     * Every problem, not the first. An admin fixing a nine-stage workflow one error per
     * round trip is the experience this avoids, and it is also how you find out the
     * validator only implements one rule.
     */
    @Test
    void allProblemsAreReportedTogether() {
        UUID tenant = fixture.createTenant("pub-all");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(anEmptyStage().and(branchFromStage(2).toStage(1)));
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(PublishValidationException.class)
                    .satisfies(e -> assertThat(((PublishValidationException) e).problems())
                            .hasSizeGreaterThanOrEqualTo(2));
        });
    }

    @Test
    void publishingTwiceIsRefused() {
        UUID tenant = fixture.createTenant("pub-twice");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(threeValidStages());
            publisher.publish(draftId);
            assertThatThrownBy(() -> publisher.publish(draftId))
                    .isInstanceOf(VersionNotEditableException.class);
        });
    }

    /**
     * Editing a published workflow makes a new draft; the old version keeps its stages
     * exactly as running cases pinned to it expect. This is Q2's freeze-by-default seen
     * from the authoring side.
     */
    @Test
    void editingAfterPublishCreatesV2AndLeavesV1Intact() {
        UUID tenant = fixture.createTenant("pub-v2");
        fixture.runAs(tenant, () -> {
            UUID v1 = draftWith(threeValidStages());
            publisher.publish(v1);
            var templateId = workflows.getDefinition(v1).templateId();

            UUID v2 = workflows.createDraft(templateId);
            assertThat(workflows.getDefinition(v2).versionNo()).isEqualTo(2);
            assertThat(workflows.getDefinition(v2).stages()).hasSize(3);   // deep-copied

            workflows.replaceDraft(v2, twoStages());
            assertThat(workflows.getDefinition(v1).stages()).hasSize(3);   // untouched
        });
    }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd backend && ./gradlew cleanTest test --tests '*PublishValidationTest'`
Expected: FAIL — no `PublishService`.

- [ ] **Step 3: Implement the validator and publish**

```java
@RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
@Transactional
public WorkflowDefinitionView publish(UUID versionId) {
    WorkflowVersion version = authorizedQuery.getById(
            versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_MANAGE, versionId);
    if (version.getStatus() != VersionStatus.DRAFT) throw new VersionNotEditableException(versionId);

    List<String> problems = validate(versionId);
    if (!problems.isEmpty()) throw new PublishValidationException(problems);

    version.setStatus(VersionStatus.PUBLISHED);          // the last legal UPDATE to this row
    version.setPublishedAt(Instant.now());
    version.setPublishedBy(contextProvider.principal().userId());

    WorkflowTemplate template = authorizedQuery.getById(
            templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_MANAGE, version.getTemplateId());
    // "No longer offered to new cases" is this pointer moving -- never a status change on
    // a frozen row, which the trigger would refuse anyway.
    template.setCurrentVersionId(versionId);

    audit.record(AuditActions.WORKFLOW_PUBLISHED, "workflow_version", versionId,
            "Published v" + version.getVersionNo() + " of " + template.getName(), Map.of());
    return definitionOf(versionId);
}

/**
 * Collects every problem rather than throwing on the first. An admin fixing a
 * nine-stage workflow one error per round trip is a bad experience; a validator that
 * only ever reports one problem is also indistinguishable from one that implements
 * only one rule.
 */
private List<String> validate(UUID versionId) {
    List<Stage> stages = this.stages.findByVersionIdOrderByOrdinal(versionId);
    List<MilestoneDefinition> milestones = milestoneDefinitions.findByVersionIdOrderByOrdinal(versionId);
    List<MilestoneDependency> deps = dependencies.findByVersionId(versionId);
    List<BranchRule> rules = branchRules.findByVersionIdOrderByOrdinal(versionId);
    Set<String> declared = attributeDefinitions.findByVersionIdOrderByOrdinal(versionId)
            .stream().map(AttributeDefinition::getKey).collect(toSet());

    List<String> problems = new ArrayList<>();
    if (stages.isEmpty()) problems.add("A workflow needs at least one stage");

    Map<UUID, Integer> stageOrdinal = stages.stream()
            .collect(toMap(Stage::getId, Stage::getOrdinal));
    Map<UUID, MilestoneDefinition> milestoneById = milestones.stream()
            .collect(toMap(MilestoneDefinition::getId, m -> m));

    // Rule 1: the final stage must be unconditionally enterable, or the skip loop in
    // CaseEngine can walk past the end of the workflow with nowhere to land.
    if (!stages.isEmpty()) {
        Stage last = stages.get(stages.size() - 1);
        if (last.getEntryCondition() != null && last.getEntryCondition().getSource() != null) {
            problems.add("The final stage (" + last.getName() + ") must have no entry condition");
        }
    }

    // Rule 5, and Rule 4 for entry conditions.
    for (Stage stage : stages) {
        if (milestones.stream().noneMatch(m -> m.getStageId().equals(stage.getId()))) {
            problems.add("Stage " + stage.getName() + " has no milestone");
        }
        problems.addAll(conditionProblems(stage.getEntryCondition(), declared,
                "entry condition of " + stage.getName()));
    }

    // Rule 2, and Rule 4 for branch conditions.
    for (BranchRule rule : rules) {
        int from = stageOrdinal.getOrDefault(rule.getStageId(), -1);
        int to = stageOrdinal.getOrDefault(rule.getTargetStageId(), -1);
        if (to <= from) {
            problems.add("Branch rule on stage " + from + " must target a forward stage, not " + to);
        }
        problems.addAll(conditionProblems(rule.getCondition(), declared,
                "branch rule on stage " + from));
    }

    // Rule 3: dependencies point strictly earlier in plan order, which is
    // (stage ordinal, milestone ordinal). A forward dependency describes a plan the
    // engine will never follow, because the schedule treats intra-stage milestones as
    // sequential by ordinal.
    for (MilestoneDependency dep : deps) {
        var self = milestoneById.get(dep.getMilestoneDefinitionId());
        var other = milestoneById.get(dep.getDependsOnMilestoneDefinitionId());
        if (self == null || other == null) continue;
        long selfKey  = planOrder(stageOrdinal, self);
        long otherKey = planOrder(stageOrdinal, other);
        if (otherKey >= selfKey) {
            problems.add("Milestone " + self.getName() + " must depend on an earlier milestone, not "
                    + other.getName());
        }
    }
    return problems;
}

private List<String> conditionProblems(Condition condition, Set<String> declared, String where) {
    if (condition == null || condition.getSource() == null) return List.of();
    if (condition.getSource() == ConditionSource.ATTRIBUTE && !declared.contains(condition.getKey())) {
        return List.of("The " + where + " names attribute '" + condition.getKey()
                + "', which this workflow does not declare");
    }
    if (condition.getSource() == ConditionSource.CUSTOMER
            && !CustomerFactKeys.ALL.contains(condition.getKey())) {
        return List.of("The " + where + " names customer field '" + condition.getKey()
                + "', which does not exist");
    }
    return List.of();
}
```

`CustomerFactKeys.ALL` is a `Set<String>` of `status`, `industry`, `country` declared in `workflow` — the three fields `journey.CustomerFacts` exposes for conditions. It lives in `workflow` and names strings only, so it creates no dependency on `journey` or `customer`; Task 10 adds a test asserting the two lists agree, because a key in one and not the other is a condition that can never be true.

`planOrder(stageOrdinal, milestone)` returns `stageOrdinal * 1000L + milestone.getOrdinal()`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew cleanTest test --tests '*PublishValidationTest'`
Expected: PASS, all nine.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow backend/src/test/java/co/ara/onboarding/workflow
git commit -m "feat: validate a workflow at publish, and freeze it there

Five validations, each with its own test, because one 'invalid workflow is rejected'
test passes when only one rule works. The final stage must be unconditionally
enterable, branch targets must move forward, dependencies must point strictly earlier
in plan order, condition keys must be declared, and every stage needs a milestone.

Rules 1 and 2 are load-bearing for the engine rather than tidiness: forward-only
targets make the stage graph a DAG by construction, so Task 15's skip loop is
strictly increasing and needs no visited set, and an unconditional final stage means
it can never run off the end.

Rule 3 exists because the schedule plans intra-stage milestones as sequential by
ordinal: a forward dependency describes a plan the engine will never follow.

All problems are collected, not thrown on the first. An admin fixing a nine-stage
workflow one error per round trip is the experience this avoids -- and a validator
that only ever reports one problem is indistinguishable from one that implements one
rule.

Publish sets status once (the last legal UPDATE the trigger allows) and moves
template.current_version_id. Archiving is that pointer moving, never a status change
on a frozen row. The v1-untouched test is Q2's freeze-by-default from the authoring
side."
```

**Executor's notes, found only by running the above verbatim, not by inspection:**

- The plan never defines the fixture DSL Step 1's test calls (`draftWith`,
  `threeValidStages`, `lastStageConditionalOn`, `branchFromStage(n).toStage(m)`,
  `firstMilestoneDependingOnTheSecond`, `conditionOnAttribute`, `anEmptyStage`, and
  `.and(...)`), and `WorkflowService.getTemplate` (used by the happy-path test) did
  not exist either. Both were designed and added in the same commit: the DSL as a
  `PublishScenario` mutation over one fixed three-stage skeleton in
  `WorkflowFixtures`, each fixture breaking exactly one rule so `.and` can compose
  two into `allProblemsAreReportedTogether`'s draft; `getTemplate` gated
  WORKFLOW_VIEW and resolved through AuthorizedQuery like every other read in the
  module.
- `validate()`'s five direct repository finder calls
  (`this.stages.findByVersionIdOrderByOrdinal(...)` etc.) fail
  `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`, which
  Task 6 already widened to cover `co.ara.onboarding.workflow..` — confirmed by
  running the suite, not a style guess. Fixed by reading all five lists through a
  small `readByVersion` helper built on `AuthorizedQuery.findAll`, same shape as
  `WorkflowService.readChildren`.
- Five of the nine Step 1 tests (`aFinalStageWithAnEntryConditionIsRefused`,
  `aBranchTargetingAnEarlierStageIsRefused`, `aDependencyPointingForwardIsRefused`,
  `aConditionNamingAnUndeclaredAttributeIsRefused`, `aStageWithNoMilestonesIsRefused`)
  plus `allProblemsAreReportedTogether` and `publishingTwiceIsRefused` assert
  `publisher.publish(...)`'s exception via `assertThatThrownBy` *inside*
  `fixture.runAs(...)` — the exact pitfall CLAUDE.md and this same plan's Task 6
  (`WorkflowAuthoringTest.aStaleLockVersionIsRejected`) already name: it leaves the
  shared transaction rollback-only and surfaces `UnexpectedRollbackException`
  instead of the exception under test. Running the brief's original shape hit
  exactly that. Fixed by wrapping the whole `runAs` call in `assertThatThrownBy`,
  matching `aStaleLockVersionIsRejected`'s shape.
- `editingAfterPublishCreatesV2AndLeavesV1Intact` called
  `replaceDraft(v2, twoStages())` with `twoStages()`'s hardcoded `lockVersion` 0, but
  v2 is not "a fresh (lockVersion 0) draft" — `createDraft`'s deep-copy path writes
  the copied graph through `replaceDraft` before returning, which bumps it to 1.
  Fixed by building the request from v2's own current definition's `lockVersion`.
- A `branchFromStage(...).toStage(...)` fixture needs a real `Condition`, not null:
  `branch_rule.source` is `NOT NULL` at the database layer, unlike a stage's
  optional entry condition. `CustomerFactKeys` already declares `status`, so
  `ConditionSource.CUSTOMER`/`status` is used there, which never trips rule 4 as a
  side effect.

---

## Task 8: Workflow HTTP layer

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowController.java`, `WorkflowExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/WorkflowApiTest.java`
- Modify: `frontend/src/lib/api/generated.ts` (regenerated, never edited)

**Interfaces:**
- Consumes: `WorkflowService`, `PublishService`, their exceptions.
- Produces: the eight endpoints of spec §7.1, and the generated TypeScript types Task 24 imports.

- [ ] **Step 1: Write the failing API test**

```java
class WorkflowApiTest extends SecurityTestBase {

    @Test
    void theDefinitionRoundTripsThroughTheApi() throws Exception {
        UUID tenant = fixture.createTenant("wf-api");
        AppUser admin = adminUser(tenant, "wfadmin@example.com");

        String created = mvc.perform(as(post("/api/t/wf-api/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-api/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        mvc.perform(as(get("/api/t/wf-api/workflows/" + templateId + "/versions/" + versionId), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /** A second draft is a 409, not a 500 from the partial unique index. */
    @Test
    void aSecondDraftAnswers409() throws Exception { /* … */ }

    /** Publish failures are a 422 carrying every problem, so the builder can list them. */
    @Test
    void anInvalidPublishAnswers422WithEveryProblem() throws Exception {
        // … build a draft with an empty stage and a backward branch, then:
        mvc.perform(as(post("/api/t/wf-api/workflows/" + templateId + "/versions/" + versionId + "/publish"), admin))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.problems.length()").value(greaterThanOrEqualTo(2)));
    }

    /** A stale draft write is a 409, so the builder can say "someone else saved first". */
    @Test
    void aStaleDraftWriteAnswers409() throws Exception { /* … */ }

    /** Task 1's derived sweep covers these automatically; this asserts the 403 path. */
    @Test
    void aWorkflowViewerCannotPublish() throws Exception {
        // grant workflow.view only, POST publish, expect 403
    }
}
```

- [ ] **Step 2: Implement the controller**

```java
@RestController
@RequestMapping("/api/t/{tenantSlug}/workflows")
public class WorkflowController {

    // Paths live here rather than under /admin because workflow.view belongs to every
    // operational role that must read the definition its case is frozen on. Gating by
    // path would either exclude them or make /admin mean nothing. The frontend route is
    // still under admin/.

    @GetMapping                                        public List<WorkflowTemplateView> list() { … }
    @PostMapping @ResponseStatus(CREATED)              public WorkflowTemplateView create(@Valid @RequestBody CreateTemplateRequest r) { … }
    @GetMapping("/{id}")                               public WorkflowTemplateView get(@PathVariable UUID id) { … }
    @PostMapping("/{id}/deactivate") @ResponseStatus(NO_CONTENT) public void deactivate(@PathVariable UUID id) { … }
    @GetMapping("/{id}/versions/{vid}")                public WorkflowDefinitionView definition(@PathVariable UUID vid) { … }
    @PostMapping("/{id}/versions") @ResponseStatus(CREATED) public WorkflowDefinitionView newDraft(@PathVariable UUID id) { … }
    @PutMapping("/{id}/versions/{vid}")                public WorkflowDefinitionView replace(@PathVariable UUID vid, @Valid @RequestBody WorkflowDefinitionRequest r) { … }
    @PostMapping("/{id}/versions/{vid}/publish")       public WorkflowDefinitionView publish(@PathVariable UUID vid) { … }
    @PostMapping("/{id}/versions/{vid}/discard") @ResponseStatus(NO_CONTENT) public void discard(@PathVariable UUID vid) { … }
}
```

`@Valid` on both request bodies, with `@NotBlank` on names and `@Positive` on `estimatedDurationDays`. Sub-project 1 shipped `PlatformTenantController` with no `@Valid` and created permanently unreachable tenants as a result; do not repeat it.

- [ ] **Step 3: Map every exception, and document every status**

```java
@RestControllerAdvice
class WorkflowExceptionHandler {

    // 422, not 400: the request was well-formed and the graph was understood -- it was
    // semantically rejected, and the builder renders the list against its stage rows.
    @ExceptionHandler(PublishValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ProblemList onValidation(PublishValidationException e) { return new ProblemList(e.problems()); }

    @ExceptionHandler(DraftAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError onDuplicateDraft(DraftAlreadyExistsException e) { … }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError onStale(OptimisticLockingFailureException e) { … }

    @ExceptionHandler({VersionNotEditableException.class, UnknownReferenceException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError onBadRequest(RuntimeException e) { … }

    public record ProblemList(List<String> problems) {}
}
```

This advice lives in `workflow`, not in `platform`: `CLAUDE.md` — "`platform` is the foundation everything depends on, so it must never name a domain type — a domain exception's `@RestControllerAdvice` belongs in that domain's own module."

Declare each status with `@ApiResponse` on the controller methods. Sub-project 1 shipped a real, tested 409 that springdoc never advertised, so `generated.ts` had no branch for it — a client cannot narrow on a status the document does not mention.

- [ ] **Step 4: Run the API test and both guards**

```bash
cd backend && ./gradlew cleanTest test --tests '*WorkflowApiTest' --tests '*DirectApiAccessTest' --tests '*OpenApiDocumentTest'
```
Expected: PASS. `DirectApiAccessTest` should cover the nine new endpoints with nothing added by hand — that is Task 1's derivation earning its keep.

- [ ] **Step 5: Regenerate the client types**

```bash
cd backend && ./gradlew openApiSpec
cd ../frontend && npm run generate:api
```
Expect reordering-only noise in the diff: springdoc orders schema properties nondeterministically. Confirm the `problems` array and both 409s appear in `generated.ts`; if they do not, the `@ApiResponse` declarations are missing.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow backend/src/test/java/co/ara/onboarding/workflow frontend/src/lib/api/generated.ts
git commit -m "feat: expose workflow authoring over HTTP

Nine endpoints at /workflows rather than /admin/workflows: workflow.view belongs to
every operational role that must read the definition its case is frozen on, so
gating by path would either exclude them or make /admin mean nothing. Admin-ness is
a permission; the frontend route stays under admin/.

Publish failures are 422 with the full problem list, not 400 with the first: the
request was well-formed and the graph was understood, and the builder renders the
list against its stage rows. Both concurrency failures are 409s the UI can explain
-- a second draft, and a stale whole-graph write.

Every status is declared with @ApiResponse. Sub-project 1 shipped a real, tested 409
that springdoc never advertised, so generated.ts had no branch for it and no client
could narrow on it.

@Valid on both request bodies. PlatformTenantController shipped without it in
sub-project 1 and created permanently unreachable tenants from unvalidated slugs.

The exception advice lives in workflow, not platform: platform must never name a
domain type."
```

**Executor's notes, found only by running the above verbatim, not by inspection:**

- `SecurityTestBase` was package-private in `co.ara.onboarding.security`, so
  `class WorkflowApiTest extends SecurityTestBase` could not even compile from
  `co.ara.onboarding.workflow` — a package-private type cannot be named outside its
  package at all, regardless of its members' own visibility. Every prior subclass
  lived in `security` alongside it. Fixed by widening the class to `public` and its
  four fields to `protected`; sub-projects 2-10 add one such module-owned `*ApiTest`
  each, so more cross-package subclasses are coming, not fewer. `WorkflowApiTest`
  still declares its own `mvc`/`fixture`/`roles`/`mapper` fields rather than reading
  the parent's directly — only `as()` is actually reused from `SecurityTestBase`.
- No `ApiError` type exists anywhere in the codebase, and one should not be invented:
  `customer.CustomerExceptionHandler`, `authz.AuthzExceptionHandler` and
  `auth.AuthExceptionHandler` already established `org.springframework.http.ProblemDetail`
  as this codebase's one shared error DTO, returned with no `@ResponseStatus` (Spring
  resolves the status from the `ProblemDetail` instance itself). `onDuplicateDraft`,
  `onStale` and `onBadRequest` return `ProblemDetail` for exactly this reason;
  `onValidation` is the one exception, since its body is the typed `problems` list the
  builder renders against its own stage rows, not a generic detail string.
- `WorkflowController.newDraft` combines `createDraft` (returns a bare `UUID`) with
  `WorkflowService.getDefinitionAs` to answer under WORKFLOW_MANAGE rather than
  re-authorizing under WORKFLOW_VIEW — the same manage-without-view gap
  `replaceDraft`'s own return statement already avoids. Running
  `theDefinitionRoundTripsThroughTheApi` verbatim surfaced a second, sharper bug in
  that seam: `getDefinitionAs` carried no `@Transactional` of its own. Its one
  existing caller, `PublishService.publish`, is itself `@Transactional`, so the call
  always landed inside an already-open transaction and the gap never showed. Called
  from a controller straight after `createDraft`'s own transaction has already
  committed and closed, there is no open transaction for `TenantTransactionBinder`'s
  pointcut (`@Transactional`-shaped, not "some transaction happens to be open") to
  match, so the tenant is never bound on that call — the generated SQL was missing
  both the Hibernate `tenantFilter` predicate and the `app.tenant_id` GUC RLS reads,
  and a read of the row `createDraft` had just inserted a moment earlier threw
  `NoSuchElementException` instead of finding it. Fixed by adding
  `@Transactional(readOnly = true)` to `getDefinitionAs` itself, which joins an
  already-open transaction exactly as before when called from `PublishService` and
  now correctly opens (and binds) its own when called standalone.
- Springdoc auto-attaches `onValidation`'s `@ResponseStatus`-annotated 422/`ProblemList`
  response to *every* operation in the whole document, not just `workflow`'s own —
  confirmed in the regenerated `generated.ts`, where e.g. `customers.list_1` now
  carries a 422 it can never actually return. This is springdoc's documented default
  for an unscoped `@RestControllerAdvice` (every existing domain handler in this
  codebase is unscoped the same way) and is not specific to anything this task did
  wrong; noted here so the next reader of a `generated.ts` diff recognises it rather
  than hunting for a cause.

---

## Task 9: `V13` journey schema and entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__journey.sql`
- Create: `backend/src/main/java/co/ara/onboarding/journey/` — `Case`, `CaseParticipant`, `Milestone`, `Requirement`, `CaseAttributeValue`, `Approval`, the six enums, and six repositories
- Test: `backend/src/test/java/co/ara/onboarding/journey/JourneyPersistenceTest.java`

**Interfaces:**
- Consumes: `TenantScopedEntity`, `authz.RelationshipType`, Task 5's tables (FK targets).
- Produces: every entity Tasks 10–22 use; `CaseRepository.lockById(UUID)` (Task 14); `MilestoneRepository.findByCaseIdOrderById`, `RequirementRepository.findByMilestoneId`, `CaseParticipantRepository.findByCaseIdAndStatus`, `ApprovalRepository.findByCaseIdAndStatus`.

- [ ] **Step 1: Write the failing persistence test**

Cover the three things that are schema decisions rather than plumbing:

```java
class JourneyPersistenceTest extends PostgresTestBase {

    @Test
    void aCaseAndItsMilestonesCanBeWrittenAndRead() { /* … */ }

    /**
     * Every journey table is a business record: DELETE stays denied, unlike the seven
     * definition tables of V12. A participant who left transitions to REMOVED; a
     * participation that vanished leaves an unexplained gap in the case's history.
     */
    @Test
    void noJourneyTableCanBeDeletedFrom() {
        UUID tenant = fixture.createTenant("j-nodelete");
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> caseId.set(newCase(tenant).getId()));

        for (String table : List.of("onboarding_case", "case_participant", "milestone",
                                    "requirement", "case_attribute_value", "approval")) {
            assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                    jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant)))
                    .as("DELETE on " + table)
                    .hasMessageContaining("permission denied");
        }
    }

    /**
     * The pinned version is NOT NULL. Invariant 2 of the spec's cross-check depends on
     * it: a case with no pinned version has no definition to execute, and every read
     * that joins one would silently return nothing.
     */
    @Test
    void aCaseCannotBeWrittenWithoutAPinnedVersion() {
        UUID tenant = fixture.createTenant("j-pinned");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> jdbc.update(
                "INSERT INTO onboarding_case (id, tenant_id, customer_id, template_id, "
              + "status, progress_percent, created_at, updated_at) "
              + "VALUES (?,?,?,?,'ACTIVE',0,now(),now())",
                Uuid7.generate(), tenant, someCustomerId, someTemplateId)))
                .hasMessageContaining("version_id");
    }

    @Test
    void oneAttributeValuePerCaseAndDefinition() { /* unique (case_id, attribute_definition_id) */ }
}
```

**Executor's amendment (Task 9):** `noJourneyTableCanBeDeletedFrom`'s
`.hasMessageContaining("permission denied")` fails as written -- PostgreSQL's SQLState 42501
(insufficient privilege) falls in SQLState class "42", which Spring's fallback
`SQLStateSQLExceptionTranslator` maps wholesale to `BadSqlGrammarException`, whose own
top-level message says only "bad SQL grammar"; "permission denied" lives in the wrapped
cause. `WorkflowPersistenceTest.aTemplateCannotBeDeleted` and
`CustomerPersistenceTest.applicationRoleCannotDeleteBusinessRecords` hit the identical shape
in Tasks 5 and 1 and were amended to `.hasStackTraceContaining("permission denied")` instead;
the checked-in test does the same.

- [ ] **Step 2: Run, watch it fail, then write `V13__journey.sql`**

```sql
-- Sub-project 2, half two: the runtime. Note what is NOT here -- no progress column a
-- client can write to, no denormalised milestone name, and no second event store: the
-- Activity Timeline reads audit_event, filtered by resource, because two stores would
-- need dual writes that inevitably drift.

CREATE TABLE onboarding_case (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    customer_id             uuid NOT NULL REFERENCES customer(id),
    template_id             uuid NOT NULL REFERENCES workflow_template(id),
    -- NOT NULL: a case with no pinned version has no definition to execute. Q2's
    -- freeze-by-default is this column plus V12's trigger, nothing else.
    version_id              uuid NOT NULL REFERENCES workflow_version(id),
    status                  varchar(16) NOT NULL,
    current_stage_id        uuid REFERENCES stage(id),
    -- Written only by CaseEngine, never from a request body. Stored rather than derived
    -- because sub-projects 8 and 9 read it per case in list views, and deriving it there
    -- is a join over every requirement in the tenant.
    progress_percent        int  NOT NULL DEFAULT 0,
    target_completion_date  date,
    held_at                 timestamptz,
    total_hold_days         int  NOT NULL DEFAULT 0,
    -- Copied from the customer at creation, so the case and milestone descriptors read
    -- their own columns instead of joining customer to resolve scope -- which is what
    -- keeps journey free of any customer import.
    owner_user_id           uuid REFERENCES app_user(id),
    owning_department_id    uuid REFERENCES department(id),
    owning_team_id          uuid REFERENCES team(id),
    started_at              timestamptz NOT NULL,
    completed_at            timestamptz,
    created_by              uuid REFERENCES app_user(id),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL
);

CREATE TABLE case_participant (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    case_id      uuid NOT NULL REFERENCES onboarding_case(id),
    user_id      uuid NOT NULL REFERENCES app_user(id),
    -- authz.RelationshipType. ASSIGNED scope resolves through these rows, which is the
    -- first real use of ResourceAuthorizationDescriptor.assignedRelationships().
    relationship varchar(16) NOT NULL,
    status       varchar(16) NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (case_id, user_id, relationship)
);

CREATE TABLE milestone (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    -- case_id is on milestone, requirement and approval alike so each descriptor is ONE
    -- subquery hop to onboarding_case rather than a chain, and every index can lead
    -- (tenant_id, case_id).
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    status                  varchar(16) NOT NULL,
    owner_user_id           uuid REFERENCES app_user(id),
    due_date                date,
    progress_percent        int  NOT NULL DEFAULT 0,
    completed_at            timestamptz,
    completed_by            uuid REFERENCES app_user(id),
    -- Only set when a completion was forced. Q5 requires the reason to be recorded, and
    -- a nullable column here plus a NOT NULL one on approval is the difference between
    -- "completed" and "forced".
    completion_reason       text,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (case_id, milestone_definition_id)
);

CREATE TABLE requirement (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid NOT NULL REFERENCES tenant(id),
    case_id                  uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id             uuid NOT NULL REFERENCES milestone(id),
    requirement_definition_id uuid NOT NULL REFERENCES requirement_definition(id),
    status                   varchar(16) NOT NULL,
    satisfied_at             timestamptz,
    satisfied_by             uuid REFERENCES app_user(id),
    -- Deliberately NOT a foreign key: the target is a task, document or agreement in a
    -- module that does not exist yet. It also avoids the shape behind sub-project 1's
    -- cross-tenant existence oracle -- PostgreSQL checks referential integrity with row
    -- security BYPASSED, so an FK answers 200 for another tenant's id and 500 for an
    -- invented one. A soft reference resolved through AuthorizedQuery cannot.
    satisfied_ref            uuid,
    satisfied_ref_type       varchar(32),
    waiver_reason            text,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (milestone_id, requirement_definition_id)
);

CREATE TABLE case_attribute_value (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    attribute_definition_id uuid NOT NULL REFERENCES attribute_definition(id),
    -- Typed columns rather than one text column plus a parse at evaluation time.
    -- Condition evaluation must never fail on a malformed stored value: a branch that
    -- throws mid-transition leaves a case wedged, and one that swallows the error is a
    -- silent false.
    value_text              varchar(255),
    value_number            numeric(18,4),
    value_boolean           boolean,
    value_date              date,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    UNIQUE (case_id, attribute_definition_id)
);

CREATE TABLE approval (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    case_id       uuid NOT NULL REFERENCES onboarding_case(id),
    kind          varchar(24) NOT NULL,
    -- Exactly one of these is set, by kind: STAGE_EXIT approves leaving a stage,
    -- FORCE_COMPLETE approves forcing one milestone.
    stage_id      uuid REFERENCES stage(id),
    milestone_id  uuid REFERENCES milestone(id),
    requested_by  uuid NOT NULL REFERENCES app_user(id),
    requested_at  timestamptz NOT NULL,
    -- NOT NULL is how Q5's "mandatory reason recorded in audit trail" becomes
    -- unavoidable: the flow cannot be built without one.
    reason        text NOT NULL,
    status        varchar(16) NOT NULL,
    decided_by    uuid REFERENCES app_user(id),
    decided_at    timestamptz,
    decision_note text,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT approval_target_matches_kind CHECK (
        (kind = 'STAGE_EXIT'     AND stage_id IS NOT NULL AND milestone_id IS NULL) OR
        (kind = 'FORCE_COMPLETE' AND milestone_id IS NOT NULL AND stage_id IS NULL))
);

CREATE INDEX onboarding_case_tenant_customer_idx ON onboarding_case (tenant_id, customer_id);
CREATE INDEX onboarding_case_tenant_status_idx   ON onboarding_case (tenant_id, status);
CREATE INDEX onboarding_case_tenant_team_idx     ON onboarding_case (tenant_id, owning_team_id);
CREATE INDEX onboarding_case_tenant_owner_idx    ON onboarding_case (tenant_id, owner_user_id);
CREATE INDEX case_participant_tenant_case_idx    ON case_participant (tenant_id, case_id);
CREATE INDEX case_participant_tenant_user_idx    ON case_participant (tenant_id, user_id, status);
CREATE INDEX milestone_tenant_case_idx           ON milestone (tenant_id, case_id);
CREATE INDEX milestone_tenant_owner_idx          ON milestone (tenant_id, owner_user_id);
CREATE INDEX requirement_tenant_case_idx         ON requirement (tenant_id, case_id);
CREATE INDEX requirement_tenant_milestone_idx    ON requirement (tenant_id, milestone_id);
CREATE INDEX case_attribute_value_tenant_case_idx ON case_attribute_value (tenant_id, case_id);
CREATE INDEX approval_tenant_case_status_idx     ON approval (tenant_id, case_id, status);

SELECT enable_tenant_rls('onboarding_case');
SELECT enable_tenant_rls('case_participant');
SELECT enable_tenant_rls('milestone');
SELECT enable_tenant_rls('requirement');
SELECT enable_tenant_rls('case_attribute_value');
SELECT enable_tenant_rls('approval');

-- SELECT, INSERT, UPDATE only, and no DELETE anywhere: every one of these is a business
-- record. V5_1 revoked the schema-wide default, so these tables start with nothing.
GRANT SELECT, INSERT, UPDATE ON
    onboarding_case, case_participant, milestone, requirement,
    case_attribute_value, approval
    TO onboarding_app;
```

- [ ] **Step 3: Write the entities, enums and repositories**

```java
public enum CaseStatus { ACTIVE, ON_HOLD, COMPLETED, CANCELLED }
public enum MilestoneStatus { PENDING, ACTIVE, BLOCKED, DONE, SKIPPED }
public enum RequirementStatus { OPEN, SATISFIED, WAIVED }
public enum ApprovalKind { STAGE_EXIT, FORCE_COMPLETE }
public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
public enum ParticipantStatus { ACTIVE, REMOVED }
```

`Case` is a legal Java class name; the table is `onboarding_case` because `case` is a SQL reserved word. Add the locking finder now, since Task 14 depends on it:

```java
public interface CaseRepository extends JpaRepository<Case, UUID>, JpaSpecificationExecutor<Case> {

    /**
     * Re-reads a row AuthorizedQuery has already resolved, purely to take the row lock
     * that serialises reconciliation. It widens visibility by nothing -- the caller
     * already holds the resolved entity -- which is why the finder rule carries a
     * per-method exclusion for it rather than a class-wide one, and why it is named
     * lockById rather than findById: a reviewer reading a call site should see that a
     * lock is being taken.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Case c where c.id = :id")
    Optional<Case> lockById(@Param("id") UUID id);

    List<Case> findByCustomerIdOrderByStartedAtDesc(UUID customerId);
    List<Case> findByVersionIdAndStatus(UUID versionId, CaseStatus status);
}
```

- [ ] **Step 4: Run persistence and RLS coverage**

```bash
cd backend && ./gradlew cleanTest test --tests '*JourneyPersistenceTest' --tests '*RlsCoverageTest'
```
Expected: PASS, fourteen tables now covered, allowlist still four entries.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V13__journey.sql backend/src/main/java/co/ara/onboarding/journey backend/src/test/java/co/ara/onboarding/journey
git commit -m "feat: add the journey runtime schema

Six tables, RLS on each, and no DELETE on any of them -- unlike V12's definition
tables, every one of these is a business record. A participant who leaves transitions
to REMOVED, because a participation that vanished leaves an unexplained gap in the
case's history.

version_id is NOT NULL: a case with no pinned version has no definition to execute,
and Q2's freeze-by-default is that column plus V12's trigger and nothing else.

case_id is denormalised onto milestone, requirement and approval so each descriptor
is one subquery hop to onboarding_case rather than a chain, and every index leads
(tenant_id, case_id).

requirement.satisfied_ref is deliberately not a foreign key. Its target lives in a
module that does not exist yet, and an FK would reproduce the shape behind
sub-project 1's cross-tenant existence oracle: PostgreSQL checks referential
integrity with row security bypassed, so another tenant's id answers 200 while an
invented one answers 500.

case_attribute_value uses typed columns rather than text plus a parse at evaluation
time. A branch condition that throws mid-transition wedges a case, and one that
swallows the error is a silent false.

approval carries a CHECK tying its target to its kind: STAGE_EXIT has a stage,
FORCE_COMPLETE has a milestone, never both. Task 17's two decide endpoints depend on
that being impossible to violate.

progress_percent exists on the case but appears in no request type anywhere in this
sub-project -- it is written by CaseEngine only."
```

---

## Task 10: The customer port

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/CustomerDirectory.java`, `CustomerFacts.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/JourneyCustomerDirectory.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java` (drop both `allowEmptyShould`)
- Test: `backend/src/test/java/co/ara/onboarding/journey/CustomerDirectoryTest.java`

**Interfaces:**
- Consumes: `AuthorizedQuery`, `CustomerRepository`, `PermissionKeys.CUSTOMER_VIEW`, `workflow.CustomerFactKeys`.
- Produces: `CustomerDirectory.findVisible(UUID) -> Optional<CustomerFacts>` and `CustomerFacts(UUID id, String status, String industry, String country, UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId)` — used by Tasks 13, 15.

- [ ] **Step 1: Write the failing tests, including the boundary and the oracle**

```java
class CustomerDirectoryTest extends PostgresTestBase {

    @Autowired CustomerDirectory directory;
    @Autowired TenantFixture fixture;

    @Test
    void aVisibleCustomerResolvesToItsFacts() {
        UUID tenant = fixture.createTenant("dir-visible");
        var customerId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> customerId.set(
                fixture.createCustomer(tenant, "Acme", null, null)));

        fixture.runAs(tenant, () -> {
            var facts = directory.findVisible(customerId.get());
            assertThat(facts).isPresent();
            assertThat(facts.get().status()).isEqualTo("PROSPECT");
        });
    }

    /**
     * The oracle. Sub-project 1's ownership FKs answer 200 for another tenant's UUID and
     * 500 for an invented one, because PostgreSQL checks referential integrity with row
     * security bypassed -- so the two are distinguishable and the difference is an
     * existence oracle. Resolving through AuthorizedQuery collapses both to empty, and
     * journey turns empty into 404.
     */
    @Test
    void anotherTenantsCustomerIsIndistinguishableFromAnInventedOne() {
        UUID tenantA = fixture.createTenant("dir-a");
        UUID tenantB = fixture.createTenant("dir-b");
        var bCustomer = new AtomicReference<UUID>();
        fixture.runAs(tenantB, () -> bCustomer.set(
                fixture.createCustomer(tenantB, "Beta", null, null)));

        fixture.runAs(tenantA, () -> {
            assertThat(directory.findVisible(bCustomer.get())).isEmpty();
            assertThat(directory.findVisible(Uuid7.generate())).isEmpty();
        });
    }

    /**
     * The port applies the caller's scope, not merely their tenant. A customer the actor
     * cannot see must not become a case they can open.
     */
    @Test
    void aCustomerOutsideTheActorsScopeIsInvisible() {
        UUID tenant = fixture.createTenant("dir-scope");
        var otherOwned = new AtomicReference<UUID>();
        var actor = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID otherUser = fixture.createUser(tenant, "other@example.com");
            otherOwned.set(fixture.createCustomerOwnedBy(tenant, "Theirs", otherUser));
            actor.set(fixture.createUser(tenant, "actor@example.com"));
            grantRole(tenant, actor.get(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
        });

        fixture.runAsUser(tenant, actor.get(), () ->
                assertThat(directory.findVisible(otherOwned.get())).isEmpty());
    }

    /**
     * A condition key that exists in workflow but not in CustomerFacts can never be
     * true, and a publish-time validation that accepts it is worse than useless: the
     * stage it guards would be silently skipped for every case forever.
     */
    @Test
    void theCustomerFactKeysAgreeWithWhatTheFactsRecordExposes() {
        Set<String> exposed = Arrays.stream(CustomerFacts.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(n -> !n.equals("id") && !n.endsWith("Id"))
                .collect(toSet());
        assertThat(CustomerFactKeys.ALL).isEqualTo(exposed);
    }
}
```

- [ ] **Step 2: Run, fail, then write the port and its implementation**

```java
// journey/CustomerDirectory.java
/**
 * The facts journey needs about a customer -- and nothing else.
 *
 * A port, declared by the consumer and implemented by the provider, which is the
 * idiom authz.ActorDirectory and identity.UserSessionRevoker already establish. The
 * dependency therefore runs customer -> journey, and journey holds no Customer, no
 * CustomerRepository and no CustomerStatus. ModuleBoundaryTest enforces it.
 *
 * Deliberately no display data: no legal name, no contacts. The journey workspace
 * header composes this with the useCustomer(customerId) call the customers screen
 * already ships, which costs one client request and keeps the port from growing into
 * a second customer API.
 */
public interface CustomerDirectory {
    Optional<CustomerFacts> findVisible(UUID customerId);
}

// journey/CustomerFacts.java
/**
 * status is a String, not customer.CustomerStatus: the enum stays in its own module,
 * and branch conditions compare strings anyway. The three ownership ids are here
 * because a case copies them at creation, so its descriptors read their own columns.
 */
public record CustomerFacts(UUID id, String status, String industry, String country,
                            UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId) {}
```

```java
// customer/JourneyCustomerDirectory.java
@Component
public class JourneyCustomerDirectory implements CustomerDirectory {

    private final CustomerRepository customers;
    private final AuthorizedQuery authorizedQuery;

    /**
     * Resolution goes through AuthorizedQuery under CUSTOMER_VIEW, so the caller's scope
     * applies and an out-of-scope or foreign-tenant id is empty rather than a row. That
     * is what makes "open a case on another tenant's customer" a 404 instead of the
     * 200-versus-500 pair an unchecked foreign key produces.
     *
     * This class is a *Directory reached with an id from a request body, which is exactly
     * the case CLAUDE.md predicted would slip past the name-shaped finder rule. Task 1
     * widened that rule to *Directory before this class existed.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerFacts> findVisible(UUID customerId) {
        try {
            Customer c = authorizedQuery.getById(
                    customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, customerId);
            return Optional.of(new CustomerFacts(c.getId(), c.getStatus().name(),
                    c.getIndustry(), c.getCountry(), c.getOwnerUserId(),
                    c.getOwningDepartmentId(), c.getOwningTeamId()));
        } catch (NoSuchElementException notVisible) {
            // Empty, not a rethrow: journey maps empty to its own 404 with its own
            // message. Letting AuthorizedQuery's exception escape would make a missing
            // customer read as a missing case.
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 3: Remove both `allowEmptyShould` clauses from Task 1's rules**

Both packages now hold real classes, so the rules must bind rather than pass vacuously. Then verify they still pass — and confirm they can still fail by re-adding a `customer` import to any `journey` class briefly.

- [ ] **Step 4: Run the tests and the boundary guard**

```bash
cd backend && ./gradlew cleanTest test --tests '*CustomerDirectoryTest' --tests '*ModuleBoundaryTest' --tests '*AuthorizationCoverageTest'
```
Expected: PASS. If `ModuleBoundaryTest` reports a `journey → customer` violation, the port has been bypassed somewhere — fix the caller, never the rule.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey backend/src/main/java/co/ara/onboarding/customer backend/src/test/java
git commit -m "feat: reach customers through a port journey declares itself

journey imports nothing from customer. It declares CustomerDirectory and customer
implements it, so the arrow runs customer -> journey -- the ActorDirectory /
UserSessionRevoker idiom from sub-project 1. Both allowEmptyShould clauses are now
removed from the module rules, which bind to real classes from here on.

Resolution goes through AuthorizedQuery under customer.view, which does two things at
once: the caller's scope applies, and a foreign-tenant id becomes indistinguishable
from an invented one. Sub-project 1's ownership foreign keys answer 200 for the former
and 500 for the latter because PostgreSQL checks referential integrity with row
security bypassed -- that difference is an existence oracle, and there is a test here
asserting both cases are empty.

The port carries no display data. CustomerFacts has seven fields and none of them is
a name, so the workspace header composes it with the existing useCustomer call rather
than the port growing into a second customer API.

status is a String: the enum stays in customer, and conditions compare strings.

The implementation is a *Directory taking an id from a request body -- precisely the
case CLAUDE.md predicted would slip past the name-shaped finder rule. Task 1 widened
the rule before this class existed, which is the only reason it is covered."
```

---

## Task 11: Journey permissions, descriptors and role templates

**Files:**
- Modify: `authz/PermissionKeys.java`, `authz/PermissionCatalog.java`, `authz/RoleTemplates.java`
- Create: `scoping/CaseDescriptor.java`, `MilestoneDescriptor.java`, `RequirementDescriptor.java`, `ApprovalDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/scoping/JourneyScopingTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java` (extend)

**Interfaces:**
- Consumes: Task 9's entities, `ResourceAuthorizationDescriptor`, `RelationshipType`, `AuthContext`.
- Produces: the thirteen remaining permission keys; four registered descriptors; template grants per spec §6.4. Tasks 13–22 gate against these keys.

- [ ] **Step 1: Write the failing scoping tests — including the write test at the narrowest scope**

`CLAUDE.md`: "Wherever a permission is catalogued at several scopes, at least one write test must run at the narrowest one." That single missing test is why sub-project 1's escalation survived, so it is written here rather than in Task 22.

```java
class JourneyScopingTest extends PostgresTestBase {

    /** DEPARTMENT and TEAM resolve against the case's own copied columns. */
    @Test
    void departmentScopeSeesOnlyItsDepartmentsCases() { /* … */ }

    @Test
    void teamScopeSeesOnlyItsTeamsCases() { /* … */ }

    /**
     * ASSIGNED resolves through case_participant, which is the first real use of
     * assignedRelationships(): sub-project 1 declared the set on every descriptor and
     * then resolved ASSIGNED from a single column each time, so the set was decorative.
     */
    @Test
    void assignedScopeResolvesThroughParticipantRows() {
        UUID tenant = fixture.createTenant("scope-assigned");
        // participant as PARTICIPANT -> visible; as CREATOR -> not visible; REMOVED -> not visible
    }

    /**
     * CREATOR is excluded on CustomerDescriptor's reasoning: having once created a case
     * is not an ongoing relationship to it, so a salesperson who opens a case and hands
     * it over loses ASSIGNED access rather than keeping it forever.
     */
    @Test
    void creatorAloneDoesNotConferAssignedAccess() { /* … */ }

    /** Milestones, requirements and approvals inherit the case's scope. */
    @Test
    void childrenResolveThroughTheirCase() { /* … */ }

    /** Fail closed: no department, no teams, no participation means no rows. */
    @Test
    void anActorWithNothingSeesNothing() { /* … */ }

    /**
     * The narrow-scope WRITE test. Every write case in sub-project 1's UserAdminTest
     * granted USER_MANAGE at ALL, which is exactly why the escalation survived: not one
     * test asked what a narrow write scope does.
     */
    @Test
    void completingAMilestoneAtAssignedScopeIsRefusedForSomeoneElsesCase() {
        UUID tenant = fixture.createTenant("scope-narrow-write");
        // actor holds milestone.complete at ASSIGNED but is not a participant on the case
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor, () ->
                milestones.complete(otherPeoplesMilestoneId)))
                .isInstanceOf(NoSuchElementException.class);   // 404, never 403
    }
}
```

- [ ] **Step 2: Add the thirteen keys and catalogue them**

```java
add(CASE_VIEW,               "journey", "onboarding_case", "View cases",                    RECORD);
add(CASE_CREATE,             "journey", null,              "Open a case on a customer",     ALL_ONLY);
add(CASE_EDIT,               "journey", "onboarding_case", "Edit case owner, participants and attributes", RECORD);
add(CASE_ADVANCE,            "journey", "onboarding_case", "Advance a case to the next stage", RECORD);
add(CASE_HOLD,               "journey", "onboarding_case", "Place a case on hold or resume it", RECORD);
add(CASE_MIGRATE,            "journey", null,              "Migrate cases to a new workflow version", ALL_ONLY);
add(MILESTONE_EDIT,          "journey", "milestone",       "Reassign or reschedule a milestone", RECORD);
add(MILESTONE_COMPLETE,      "journey", "milestone",       "Satisfy requirements and complete milestones", RECORD);
add(MILESTONE_REOPEN,        "journey", "milestone",       "Reopen a completed milestone",  ORG_SCOPES);
add(MILESTONE_FORCE_COMPLETE,"journey", "milestone",       "Request a forced completion",   ORG_SCOPES);
add(MILESTONE_FORCE_APPROVE, "journey", null,              "Approve a forced completion",   ALL_ONLY);
add(REQUIREMENT_WAIVE,       "journey", "requirement",     "Waive a requirement",           ORG_SCOPES);
add(APPROVAL_DECIDE,         "journey", "approval",        "Decide a stage-exit approval",  ORG_SCOPES);
```

Two of these need their reasoning in a comment, because both look like mistakes:

```java
/**
 * CASE_CREATE is ALL-only for the same reason CUSTOMER_CREATE is: there is no case yet
 * to scope against. It is not a hole -- creation resolves the target customer through
 * CustomerDirectory under customer.view, so a Sales Representative holding
 * customer.view at ASSIGNED can only open a case on a customer they own. Authority to
 * create is bounded by what you can see.
 *
 * MILESTONE_FORCE_APPROVE is ALL-only because Q5 puts that authority strictly above
 * Project Manager. A scoped version would let a TEAM-scoped holder approve their own
 * team's forcings, which is the single thing the approval flow exists to prevent.
 */
```

Note there is deliberately **no** `requirement.satisfy` key: satisfying a requirement *is* progressing the milestone, so it gates on `MILESTONE_COMPLETE`, and sub-projects 3–5 will satisfy requirements through their own objects' permissions.

- [ ] **Step 3: Write the four descriptors**

`CaseDescriptor` is the interesting one; the other three follow `CustomerContactDescriptor`'s `viaCustomer` shape with `onboarding_case` as the parent.

```java
@Component
public class CaseDescriptor implements ResourceAuthorizationDescriptor<Case> {

    @Override public String resourceType() { return "onboarding_case"; }
    @Override public Class<Case> entityType() { return Case.class; }

    /**
     * The first descriptor whose ASSIGNED scope actually reads this set. CREATOR is
     * excluded on CustomerDescriptor's reasoning: having once created a case is not an
     * ongoing relationship to it.
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Case> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Case> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    /**
     * An EXISTS over case_participant rather than a column comparison, because a case
     * has several personal relationships at once and only some of them qualify. RLS
     * still applies to the subquery's own table, so a participant row in another tenant
     * cannot reach a case here.
     */
    @Override public Specification<Case> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return root.get("id").in(sub);
        };
    }
}
```

- [ ] **Step 4: Add the template grants of spec §6.4**

`Administrator` gets all thirteen at `ALL`. `Project Manager` gets `case.view/edit/advance/hold` and `milestone.edit/complete/reopen/force_complete` and `requirement.waive` at `TEAM`. `Account Manager` `case.view/edit` at `TEAM`. `Sales Representative` `case.view` at `ASSIGNED` plus `case.create`. `Operations` `case.view/edit` and `milestone.complete` at `DEPARTMENT`. `Legal`, `Finance`, `Compliance` `case.view` and `milestone.complete` at `ALL`. `Technical`, `Support` at `TEAM`. `Service Provider`, `Business Partner` at `ASSIGNED`. `Administrator` alone holds `workflow.manage`, `case.migrate` and `milestone.force_approve`.

`Map.of` has no overload past ten pairs — `Administrator` already uses `Map.ofEntries` for that reason and several others now cross the boundary too.

- [ ] **Step 5: Run everything that guards this**

```bash
cd backend && ./gradlew cleanTest test --tests '*JourneyScopingTest' --tests '*DescriptorRegistryTest' \
  --tests '*RoleTemplateValidityTest' --tests '*InsufficientScopeTest'
```
Expected: PASS. `DescriptorRegistryTest` is the one that catches a catalogued `resourceType` with no descriptor — the application refuses to start in that state, so a failure here would otherwise surface as every scope resolving to nothing at the first request that exercised it.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/authz backend/src/main/java/co/ara/onboarding/scoping backend/src/test/java
git commit -m "feat: catalogue the journey permissions and resolve their scopes

Thirteen keys, four descriptors, twelve templates updated. Two keys carry their
reasoning in code because both read as mistakes: case.create is ALL-only since there
is no case yet to scope against -- creation is bounded by which customers you can
see, resolved through CustomerDirectory under customer.view -- and
milestone.force_approve is ALL-only because Q5 puts that authority above Project
Manager, and a scoped version would let a TEAM holder approve their own team's
forcings.

There is deliberately no requirement.satisfy: satisfying a requirement is progressing
the milestone, and sub-projects 3-5 will satisfy requirements through their own
objects' permissions.

CaseDescriptor.assignedScope is an EXISTS over case_participant, which is the first
real use of assignedRelationships(). Sub-project 1 declared that set on every
descriptor and then resolved ASSIGNED from a single column each time, so it was
decorative. CREATOR is excluded on CustomerDescriptor's reasoning: having once
created a case is not an ongoing relationship to it.

Includes the narrow-scope WRITE test the TDD convention now requires. Every write
case in sub-project 1's UserAdminTest granted USER_MANAGE at ALL, which is precisely
why the escalation survived -- not one test asked what a narrow write scope does. Here
milestone.complete at ASSIGNED is refused for a case the actor does not participate
in, with a 404 rather than a 403."
```

---

## Task 12: Business calendar

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/BusinessCalendar.java`, `WeekdayBusinessCalendar.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/BusinessCalendarTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BusinessCalendar.plusBusinessDays(LocalDate from, int days) -> LocalDate` and `BusinessCalendar.businessDaysBetween(LocalDate from, LocalDate to) -> int`, used by Tasks 13, 18, 19. Also **a `Clock` bean** (`Clock.systemUTC()`, in `platform`): from Task 13 onward `CaseEngine`, `CaseService`, `MilestoneService` and `ApprovalService` inject it and call `Instant.now(clock)` / `LocalDate.now(clock)` rather than the argless forms. Task 18's hold test cannot pass otherwise, and retrofitting it across four classes later is worse than adopting it here.

- [ ] **Step 1: Write the failing tests, including the boundary cases that bite**

```java
class BusinessCalendarTest {

    private final BusinessCalendar calendar = new WeekdayBusinessCalendar();

    @Test
    void addingBusinessDaysSkipsWeekends() {
        // Friday 2026-08-21 + 1 business day = Monday 2026-08-24
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 1))
                .isEqualTo(LocalDate.of(2026, 8, 24));
        // Friday + 5 = the following Friday
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 5))
                .isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    void addingZeroDaysReturnsTheSameDate() {
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 0))
                .isEqualTo(LocalDate.of(2026, 8, 21));
    }

    /**
     * A milestone planned from a Saturday is not a hypothetical: a case created at the
     * weekend starts its first stage then. Anchoring on the following Monday is what
     * keeps a one-day milestone from being due before any working time has passed.
     */
    @Test
    void aWeekendStartAnchorsOnTheNextWorkingDay() {
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 22), 1))   // Saturday
                .isEqualTo(LocalDate.of(2026, 8, 25));                        // Tue after Mon
    }

    @Test
    void businessDaysBetweenExcludesWeekends() {
        assertThat(calendar.businessDaysBetween(
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 28))).isEqualTo(5);
    }

    @Test
    void businessDaysBetweenIsZeroWhenTheRangeIsInverted() {
        assertThat(calendar.businessDaysBetween(
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 21))).isZero();
    }
}
```

- [ ] **Step 2: Run, fail, implement**

```java
/**
 * Business-day arithmetic, behind an interface because Q8 says "business days, using
 * the configured business calendar" and configuration does not exist yet: there is no
 * tenant_setting table and tenant.settings.view/edit are catalogued permissions with
 * nothing behind them.
 *
 * WeekdayBusinessCalendar is Monday-to-Friday with no holidays. Sub-project 6 replaces
 * it with tenant-configured working days and a holiday table when it builds the SLA
 * machinery that needs them; until then the honest description of this is "a hardcoded
 * weekend rule", which is why it is stated in CLAUDE.md rather than implied to be
 * configurable.
 */
public interface BusinessCalendar {
    LocalDate plusBusinessDays(LocalDate from, int days);
    int businessDaysBetween(LocalDate from, LocalDate to);
}
```

```java
@Component
public class WeekdayBusinessCalendar implements BusinessCalendar {

    @Override
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        // A weekend start anchors forward first, so a one-day milestone created on a
        // Saturday is not due before any working time has passed.
        LocalDate cursor = nextWorkingDay(from);
        for (int i = 0; i < days; i++) cursor = nextWorkingDay(cursor.plusDays(1));
        return cursor;
    }

    @Override
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        int days = 0;
        for (LocalDate cursor = from; cursor.isBefore(to); cursor = cursor.plusDays(1)) {
            if (isWorkingDay(cursor)) days++;
        }
        return days;
    }

    private LocalDate nextWorkingDay(LocalDate date) {
        LocalDate cursor = date;
        while (!isWorkingDay(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }

    private boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
```

- [ ] **Step 3: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*BusinessCalendarTest'
git add backend/src/main/java/co/ara/onboarding/platform backend/src/test/java/co/ara/onboarding/platform
git commit -m "feat: add business-day arithmetic behind an interface

Q8 requires business days for due dates and says 'the configured business calendar'.
Configuration does not exist: there is no tenant_setting table, and
tenant.settings.view/edit are catalogued permissions with nothing behind them. So
this is Monday-to-Friday with no holidays, behind an interface sub-project 6 replaces
when it builds the SLA machinery that needs real configuration.

Deferring is defensible; describing a hardcoded weekend rule as configurable is not,
which is why the limitation is written down rather than implied away.

A weekend start anchors forward before counting, so a one-day milestone on a case
created on Saturday is not due before any working time has passed. businessDaysBetween
returns zero for an inverted range rather than a negative, because its callers add it
to a date."
```

---

## Task 13: Case creation

**Executor's amendment (Tasks 13/14): executed out of numeric order.** This task's
`create()` calls `engine.reconcile(c)`, but `CaseEngine` is only created in Task 14 --
and Task 14's own intro line says it "is written before the things that call it." So
Task 14 was executed first, despite the numbering; Task 13 (this task) is done
afterward and wires `CaseService.create()` to the already-built `CaseEngine`. Task 14
turned out to have no real dependency on this task's `CaseService`/`CaseView` or on
Task 16's `RequirementService`, even though its own test snippets (`ReconcileTest`,
`ReconcileConcurrencyTest`) are written against `cases.get(...)`, `cases.roadmap(...)`
and an autowired `RequirementService` that don't exist yet at that point -- the
executed tests build their fixtures and drive `CaseEngine` directly via repositories,
the same way `JourneyFixtures` already does, and call the package-private engine
directly since the tests share its package. See the amendment above Task 14's own
heading for what else this run found.

**Executor's amendment (Task 13): what running it verbatim found.**

- **`CaseView`'s printed record shape omits `attributes()`.** The surrounding prose
  says "CaseView carries every field UpdateCaseRequest accepts... which is why the
  ownership triple and attributes appear on both", but the record literally printed
  has no `attributes` component. Added it (right after the ownership triple, `Map<String,
  String>`) rather than shipping a view a client's PUT round-trip could not actually
  reconstruct.
- **`CaseParticipant` and `CaseAttributeValue` have no `ResourceAuthorizationDescriptor`.**
  Task 11 registered four (Case, Milestone, Requirement, Approval), and both
  `CASE_VIEW` and `CASE_EDIT` are RECORD-scoped (not ALL-only), so the moment
  `participants()`/`roadmap()`/`toView()` read either type for an actor without ALL
  scope, `DescriptorRegistry.forEntity` would throw with nothing registered --
  invisible under the fixture administrator's full authority, which is exactly why
  a guard like this stays quiet until a narrow-scope test exercises it. Added
  `CaseParticipantDescriptor` and `CaseAttributeValueDescriptor`, the same viaCase
  shape as `MilestoneDescriptor`/`RequirementDescriptor`.
- **Every workflow-definition read inside `CaseService` goes through `AuthorizedQuery`
  under `WORKFLOW_VIEW`, never a repository finder called directly** -- `CaseService`
  is bound by `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`
  the same as every other `*Service`, and the plan's own pseudocode calls
  `attributeDefinitions.findByVersionIdOrderByOrdinal(versionId)` etc. directly,
  which does not compile past that guard. `PublishService.readByVersion` already
  established the fix: read via `authorizedQuery.findAll(repo, type, WORKFLOW_VIEW,
  byVersion, ...)`. `WORKFLOW_VIEW` being ALL-only does not mean the check is
  skipped -- the actor still needs the grant, and `RoleTemplates` already couples
  `WORKFLOW_VIEW` ALL onto every real operational template alongside `CASE_VIEW`
  ("anyone working a [case] needs it"), which is what makes this safe rather than a
  second permission an actual case-viewing role would lack. `CaseEditTest`'s
  narrow-scope fixture needed the same pairing once it exercised a case read from a
  participant rather than the fixture administrator -- granting `CASE_VIEW` alone
  404'd on `versionNoOf`.
- **`case_attribute_value` carries no `GRANT DELETE`**, correctly, per CLAUDE.md's
  DELETE-deny-by-default invariant -- `V13__journey.sql` never grants it. The
  plan's `update()` implies a full replace by clearing and reinserting; run
  verbatim (delete-by-case-id, then insert), it failed at the database with
  "permission denied for table case_attribute_value" against the real
  `onboarding_app` role, not a mocked one. `update()` upserts existing rows in
  place instead: an attribute's row is updated to carry the new value (or nulled
  out if the attribute is now unsupplied and not required), so the full-replace
  invariant holds without ever deleting a row.
- The plan's own `dueDatesAccumulateInBusinessDaysWithinAStage` test cannot exercise
  business-day accumulation yet: due dates are computed at stage entry
  (`CaseEngine.advanceIfExitable`), which is Task 15's no-op stub as of Task 14.
  Rewritten to assert what is actually true today -- every milestone's `dueDate` is
  null immediately after creation -- with a comment pointing at Task 15 for the
  real assertion.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/CaseService.java`, `CaseView.java`, `CreateCaseRequest.java`, `RoadmapView.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/CaseCreationTest.java`

**Interfaces:**
- Consumes: `CustomerDirectory`, `BusinessCalendar`, Task 5's repositories, Task 9's entities, Task 11's keys.
- Produces:
  - `CaseService.create(CreateCaseRequest) -> CaseView` where `CreateCaseRequest(UUID customerId, UUID templateId, Map<String,String> attributes)`
  - `CaseService.get(UUID caseId) -> CaseView`
  - `CaseService.listForCustomer(UUID customerId) -> List<CaseView>`
  - `CaseService.roadmap(UUID caseId) -> RoadmapView`
  - `CaseService.update(UUID caseId, UpdateCaseRequest) -> CaseView` where `UpdateCaseRequest(UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId, Map<String,String> attributes)` — gated on `CASE_EDIT`
  - `CaseService.participants(UUID caseId) -> List<ParticipantView>`, `addParticipant(UUID caseId, UUID userId, RelationshipType)`, `removeParticipant(UUID caseId, UUID userId)` — the last two gated on `CASE_EDIT`
  - `AttributeValidationException`, `TemplateNotPublishedException`
  - The view records, which Tasks 15, 18, 20, 26 and 27 all bind to:

```java
public record CaseView(UUID id, UUID customerId, UUID templateId, UUID versionId, int versionNo,
                       CaseStatus status, UUID currentStageId, String currentStageName,
                       int progressPercent, LocalDate targetCompletionDate,
                       Instant heldAt, int totalHoldDays,
                       UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId,
                       Instant startedAt, Instant completedAt,
                       // Non-null when the current stage is exitable but auto_advance is
                       // false: the engine computed the transition and is waiting for
                       // someone with case.advance. The builder's "Advance" button binds
                       // to this rather than recomputing exitability client-side.
                       AvailableTransitionView availableTransition) {}

public record AvailableTransitionView(UUID nextStageId, String nextStageName,
                                      boolean approvalPending) {}

public record ParticipantView(UUID userId, String fullName, RelationshipType relationship,
                              ParticipantStatus status) {}
```

`CaseView` carries every field `UpdateCaseRequest` accepts — the full-replace invariant — which is why the ownership triple and `attributes` appear on both.

- [ ] **Step 1: Write the failing tests**

```java
class CaseCreationTest extends PostgresTestBase {

    /**
     * Everything the roadmap needs exists from the first moment. The prototype draws all
     * nine stages with future ones pending, so a lazily-built roadmap has nothing to
     * render -- and eager instantiation is also what lets Task 14 compute a denominator.
     */
    @Test
    void creatingACaseInstantiatesEveryMilestoneAndRequirement() {
        UUID tenant = fixture.createTenant("case-create");
        fixture.runAs(tenant, () -> {
            UUID versionId = publishedThreeStageWorkflow();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null);

            var view = cases.create(new CreateCaseRequest(customerId, templateOf(versionId),
                    Map.of("segment", "ENTERPRISE")));

            var roadmap = cases.roadmap(view.id());
            assertThat(roadmap.stages()).hasSize(3);
            assertThat(roadmap.stages().get(0).milestones()).hasSize(2);
            assertThat(roadmap.stages().get(0).milestones().get(0).requirements()).hasSize(1);
            assertThat(view.status()).isEqualTo(CaseStatus.ACTIVE);
            assertThat(view.progressPercent()).isZero();
        });
    }

    /** Q15: the OWNER participant is the default milestone owner. */
    @Test
    void theCustomersOwnerBecomesTheOwnerParticipantAndEveryMilestonesOwner() {
        UUID tenant = fixture.createTenant("case-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID customerId = fixture.createCustomerOwnedBy(tenant, "Acme", owner);
            var view = cases.create(new CreateCaseRequest(customerId, publishedTemplate(), Map.of()));

            assertThat(cases.participants(view.id()))
                    .anySatisfy(p -> {
                        assertThat(p.userId()).isEqualTo(owner);
                        assertThat(p.relationship()).isEqualTo(RelationshipType.OWNER);
                    });
            assertThat(cases.roadmap(view.id()).stages().get(0).milestones().get(0).ownerUserId())
                    .isEqualTo(owner);
        });
    }

    /**
     * Ownership is copied, not joined. It is what the case and milestone descriptors
     * resolve DEPARTMENT and TEAM against, and copying is what keeps journey from
     * needing a customer join to answer an authorization question.
     */
    @Test
    void ownershipIsCopiedFromTheCustomer() { /* … */ }

    /** Dates come from the calendar, and the plan is sequential by ordinal within a stage. */
    @Test
    void dueDatesAccumulateInBusinessDaysWithinAStage() {
        // durations 2 and 3 from a Friday start: first due the following Tuesday,
        // second due the Friday after
    }

    @Test
    void aMissingRequiredAttributeIsRejected() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                cases.create(new CreateCaseRequest(customerId, template, Map.of()))))
                .isInstanceOf(AttributeValidationException.class)
                .hasMessageContaining("segment");
    }

    @Test
    void anAttributeValueOutsideItsAllowedValuesIsRejected() { /* "MIDMARKET" */ }

    @Test
    void aNonNumericValueForANumberAttributeIsRejected() { /* parse at the boundary, never at evaluation */ }

    @Test
    void aTemplateWithNoPublishedVersionCannotStartACase() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                cases.create(new CreateCaseRequest(customerId, draftOnlyTemplate, Map.of()))))
                .isInstanceOf(TemplateNotPublishedException.class);
    }

    /** The oracle again, now at the case-creation boundary: 404, never 200 and never 500. */
    @Test
    void anotherTenantsCustomerIdIsANotFound() {
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                cases.create(new CreateCaseRequest(tenantBCustomerId, template, Map.of()))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** Two cases on one customer are normal: the switcher exists because they coexist. */
    @Test
    void aCustomerCanHaveConcurrentCases() { /* … */ }
}
```

- [ ] **Step 2: Run, fail, implement `create`**

```java
@RequirePermission(PermissionKeys.CASE_CREATE)
@Transactional
public CaseView create(CreateCaseRequest request) {
    // The customer is resolved through the port, which applies the caller's own
    // customer.view scope. case.create being ALL-only is safe precisely because of this
    // line: authority to create is bounded by which customers you can see.
    CustomerFacts customer = customers.findVisible(request.customerId())
            .orElseThrow(() -> new NoSuchElementException("Not found"));

    WorkflowTemplate template = authorizedQuery.getById(
            templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_VIEW, request.templateId());
    if (template.getCurrentVersionId() == null) {
        throw new TemplateNotPublishedException(template.getId());
    }
    UUID versionId = template.getCurrentVersionId();

    List<AttributeDefinition> declared = attributeDefinitions.findByVersionIdOrderByOrdinal(versionId);
    validateAttributes(declared, request.attributes());   // required, allowed values, parseable

    Case c = new Case();
    c.setId(Uuid7.generate());
    c.setTenantId(TenantContext.getRequired());
    c.setCustomerId(customer.id());
    c.setTemplateId(template.getId());
    c.setVersionId(versionId);                 // pinned here, and never reassigned except by migration
    c.setStatus(CaseStatus.ACTIVE);
    c.setStartedAt(Instant.now());
    // Copied, not joined: these three columns are what CaseDescriptor resolves DEPARTMENT
    // and TEAM against.
    c.setOwnerUserId(customer.ownerUserId());
    c.setOwningDepartmentId(customer.owningDepartmentId());
    c.setOwningTeamId(customer.owningTeamId());
    c.setCreatedBy(contextProvider.principal().userId());
    caseRepository.save(c);

    // The creator is a CREATOR participant, which is recorded but confers no ASSIGNED
    // access (CaseDescriptor excludes it). The customer's owner is the OWNER participant
    // and, per Q15, every milestone's default owner.
    addParticipant(c, contextProvider.principal().userId(), RelationshipType.CREATOR);
    if (customer.ownerUserId() != null) {
        addParticipant(c, customer.ownerUserId(), RelationshipType.OWNER);
    }

    storeAttributes(c, declared, request.attributes());
    instantiate(c, versionId, customer.ownerUserId());
    engine.reconcile(c);                       // sets the first stage, statuses and dates

    audit.record(AuditActions.CASE_CREATED, "onboarding_case", c.getId(),
            "Opened case on workflow " + template.getName() + " v" + versionNo(versionId),
            Map.of("customerId", customer.id(), "versionId", versionId));
    return toView(c);
}

/**
 * Every milestone and requirement for every stage, not just the first. The roadmap shows
 * the whole spine from day one with future stages pending, and Task 14's weighted
 * progress needs a denominator that exists before anything is done.
 *
 * Nothing is copied from the definition -- no name, no weight, no duration. Instances
 * join their definitions, because copying drifts and because migration is *meant* to
 * change what a milestone says.
 */
private void instantiate(Case c, UUID versionId, UUID defaultOwner) {
    var definitions = milestoneDefinitions.findByVersionIdOrderByOrdinal(versionId);
    var requirementsByMilestone = requirementDefinitions.findByVersionIdOrderByOrdinal(versionId)
            .stream().collect(groupingBy(RequirementDefinition::getMilestoneDefinitionId));

    for (MilestoneDefinition definition : definitions) {
        Milestone m = new Milestone();
        m.setId(Uuid7.generate());
        m.setTenantId(c.getTenantId());
        m.setCaseId(c.getId());
        m.setMilestoneDefinitionId(definition.getId());
        m.setStatus(MilestoneStatus.PENDING);
        m.setOwnerUserId(defaultOwner);
        milestones.save(m);

        for (RequirementDefinition rd : requirementsByMilestone.getOrDefault(definition.getId(), List.of())) {
            Requirement r = new Requirement();
            r.setId(Uuid7.generate());
            r.setTenantId(c.getTenantId());
            r.setCaseId(c.getId());            // denormalised so the descriptor is one hop
            r.setMilestoneId(m.getId());
            r.setRequirementDefinitionId(rd.getId());
            r.setStatus(RequirementStatus.OPEN);
            requirements.save(r);
        }
    }
}

/**
 * Parsing happens here, at the boundary, and never at evaluation time. A branch
 * condition that throws mid-transition wedges a case; one that swallows the error
 * evaluates false and silently skips a stage.
 */
private void validateAttributes(List<AttributeDefinition> declared, Map<String, String> supplied) {
    List<String> problems = new ArrayList<>();
    Set<String> known = declared.stream().map(AttributeDefinition::getKey).collect(toSet());

    for (String key : supplied.keySet()) {
        if (!known.contains(key)) problems.add("Unknown attribute '" + key + "'");
    }
    for (AttributeDefinition d : declared) {
        String value = supplied.get(d.getKey());
        if (value == null || value.isBlank()) {
            if (d.isRequired()) problems.add("Attribute '" + d.getKey() + "' is required");
            continue;
        }
        if (d.getDataType() == AttributeType.ENUM && d.getAllowedValues() != null
                && !List.of(d.getAllowedValues()).contains(value)) {
            problems.add("Attribute '" + d.getKey() + "' must be one of "
                    + Arrays.toString(d.getAllowedValues()));
        }
        problems.addAll(parseProblems(d, value));   // NUMBER, DATE, BOOLEAN
    }
    if (!problems.isEmpty()) throw new AttributeValidationException(problems);
}
```

Add `CASE_CREATED = of("case.created", true)` to `AuditActions` — **timeline-visible**: the case is the customer's own record, so its creation belongs on their timeline. Compare `USER_CREATED`, which is false because a vendor's staffing is not the customer's business.

- [ ] **Step 3: Write the failing edit tests, then implement `update` and the participant methods**

```java
class CaseEditTest extends PostgresTestBase {

    /** A full replace, so the view must carry everything the request accepts. */
    @Test
    void updatingReplacesTheOwnershipTripleAndAttributes() { /* … */ }

    /**
     * The field that would silently erase: attributes omitted from the body must not
     * blank the case's answers. CLAUDE.md's full-replace invariant says a PUT means
     * replace, so the request carries them and the view returns them -- there is no
     * "just don't send it" mitigation.
     */
    @Test
    void omittingAnAttributeFromAnUpdateIsRejectedRatherThanBlanking() {
        // required attributes are re-validated on update, exactly as on create
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                cases.update(caseId, new UpdateCaseRequest(owner, null, null, Map.of()))))
                .isInstanceOf(AttributeValidationException.class);
    }

    /**
     * Changing the owner re-points DEPARTMENT/TEAM/ASSIGNED scope for the whole case, so
     * it is resolved under CASE_EDIT and the new owner becomes an OWNER participant --
     * otherwise the case has an owner who cannot open it.
     */
    @Test
    void changingTheOwnerAddsAnOwnerParticipant() { /* … */ }

    @Test
    void aRemovedParticipantLosesAssignedAccessOnTheNextRequest() {
        // status REMOVED, not a deleted row: CaseDescriptor's EXISTS filters on ACTIVE
    }

    @Test
    void addingAParticipantRequiresCaseEdit() { /* AccessDeniedException with case.view only */ }

    @Test
    void aUserFromAnotherTenantCannotBeAddedAsAParticipant() {
        // the user id comes from a request body, so it resolves through AuthorizedQuery
        // under USER_VIEW before the row is written -- 404, never a cross-tenant row
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                cases.addParticipant(caseId, tenantBUserId, RelationshipType.PARTICIPANT)))
                .isInstanceOf(NoSuchElementException.class);
    }
}
```

`update` and `addParticipant` both resolve every incoming id through `AuthorizedQuery` before writing — the case under `CASE_EDIT`, the user under `USER_VIEW`, and the department and team ids through their own repositories. This is the write-path shape `CLAUDE.md` calls out as "the one that keeps escaping", and sub-project 1's ownership columns are still open precisely because `CustomerService` wrote them straight from the request.

Audit: `CASE_UPDATED = of("case.updated", true)`, `CASE_PARTICIPANT_ADDED = of("case.participant_added", true)`, `CASE_PARTICIPANT_REMOVED = of("case.participant_removed", true)` — the customer's own case changed hands, which is their business.

- [ ] **Step 4: Run the tests, then the guards, then commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*CaseCreationTest' --tests '*CaseEditTest' --tests '*ModuleBoundaryTest' --tests '*AuthorizationCoverageTest'
```

```bash
git add backend/src/main/java/co/ara/onboarding/journey backend/src/main/java/co/ara/onboarding/audit backend/src/test/java/co/ara/onboarding/journey
git commit -m "feat: open a case, pinned to a published version

Creation resolves the customer through CustomerDirectory, so case.create being
ALL-only is bounded by which customers the caller can see, and another tenant's
customer id is a 404 rather than the 200-or-500 pair an unchecked FK would give.

Every milestone and requirement for every stage is instantiated up front. The
prototype draws the whole nine-stage spine with future stages pending, so a lazy
roadmap has nothing to render -- and weighted progress needs a denominator that
exists before anything is done.

Instances copy nothing from their definitions: no name, no weight, no duration.
Copying drifts, and migration is meant to change what a milestone says.

Ownership IS copied, from the customer's triple, because those three columns are what
CaseDescriptor resolves DEPARTMENT and TEAM against -- joining customer to answer an
authorization question is exactly what the port exists to avoid.

The creator is recorded as a CREATOR participant and gains nothing from it:
CaseDescriptor excludes CREATOR from ASSIGNED. The customer's owner becomes OWNER and,
per Q15, every milestone's default owner.

Attributes are parsed at the boundary. A malformed stored value would otherwise reach
condition evaluation, where throwing wedges a case and swallowing evaluates false and
silently skips a stage.

case.created is timeline-visible: the case is the customer's own record. Compare
user.created, which is not, because a vendor's staffing is not the customer's
business."
```

---

## Task 14: `CaseEngine.reconcile` under a row lock

The heart of the sub-project. Everything that changes a case funnels through here, so it is written before the things that call it.

**Executor's amendment (Task 14):** three findings from running this verbatim.

1. **The concurrency test's real failure shape, without the lock, was "stuck at
   zero," not "double advance."** `lockAndLoad` was temporarily changed to plain
   `cases.findById` (no `PESSIMISTIC_WRITE`, no artificial delay needed) and the
   concurrency test run three times: each run failed with the case's
   `progressPercent` at 0 instead of 100. Neither of the two racing transactions'
   read of the milestone's requirements ever included both satisfactions, so neither
   ever called `markDone`, and no third reconcile arrived afterward to fix it up --
   a lost update, not the plan's "lands two stages on" framing, because Task 15's
   actual stage-advance logic does not exist yet for anything to double. Reverting
   `lockAndLoad` to `cases.lockById` made the same test pass 3/3 reruns. The same
   row lock closes both shapes of the hazard; Task 15's advance logic will rely on
   the lock this task adds rather than needing its own.
2. **Step 4's explicit ArchUnit exclusion for `CaseRepository.lockById` was
   unnecessary and was not added.** `servicesDoNotCallRepositoryFindersDirectly`
   binds only to classes named `*Service` or `*Directory`; `CaseEngine` is neither.
   Separately, `lockById`'s own name does not start with `findBy`, which is exactly
   the reason the method was named that way in the first place (its own javadoc:
   "a reviewer reading a call site should see that a lock is being taken"). Ran
   `AuthorizationCoverageTest`, `ModuleBoundaryTest` and `RlsCoverageTest` green
   with no rule changes to confirm.
3. **`ReconcileTest` and `ReconcileConcurrencyTest` could not be written exactly as
   shown below.** Both call `cases.get(...)`/`cases.roadmap(...)` (Task 13's
   `CaseService`, not built yet at this point) and the concurrency test additionally
   autowires Task 16's `RequirementService`. The executed tests instead autowire
   `CaseEngine` and the journey/workflow repositories directly and build their own
   fixtures the way `JourneyFixtures` already does, calling `engine.reconcile(...)`
   and `engine.lockAndLoad(...)` directly since the tests share `CaseEngine`'s
   package. Coverage is equivalent -- every scenario below is still exercised --
   just not through the not-yet-existing service façades.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java` (**package-private class**)
- Test: `backend/src/test/java/co/ara/onboarding/journey/ReconcileTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/ReconcileConcurrencyTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java` (the `lockById` per-method exclusion)

**Interfaces:**
- Consumes: `CaseRepository.lockById`, Task 5's definition repositories, `BusinessCalendar`.
- Produces (all package-private, callable only from within `journey`):
  - `void reconcile(Case c)` — recomputes milestone statuses, progress, current stage, dates
  - `Case lockAndLoad(UUID caseId)` — the single lock-acquisition point
  - `int progressOf(Case c)`

- [ ] **Step 1: Write the failing reconcile tests**

```java
class ReconcileTest extends PostgresTestBase {

    /**
     * Q6's weighting, against hand-computed numbers rather than an assertion that
     * recomputes the formula it is testing. Three milestones of 2, 3 and 5 days in one
     * stage: completing them in order gives 20%, 50%, 100%.
     */
    @Test
    void caseProgressIsWeightedByEstimatedDuration() {
        UUID tenant = fixture.createTenant("rec-weight");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(2, 3, 5);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 1));
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(20);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 2));
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(50);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 3));
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(100);
        });
    }

    /**
     * A milestone's own percent is its satisfied requirement WEIGHT, not its count -- a
     * weight of 3 next to two weights of 1 is 60% when only it is done.
     */
    @Test
    void milestoneProgressIsWeightedByRequirementWeight() { /* weights 3,1,1 -> 60, 80, 100 */ }

    /** Only MANDATORY requirements gate completion; optional ones still move the bar. */
    @Test
    void anOptionalRequirementDoesNotBlockCompletionButDoesCount() { /* … */ }

    /** A waived requirement completes a milestone the same way a satisfied one does. */
    @Test
    void aWaivedRequirementCountsAsSettled() { /* … */ }

    /** Dependencies gate ACTIVE and nothing else. */
    @Test
    void aMilestoneWithAnUnmetDependencyIsBlocked() { /* … */ }

    /**
     * The invariant locked during design: a blocked milestone goes overdue rather than
     * having its schedule quietly reflowed, because the prototype draws "blocked by X" in
     * red beside a red due date and a plan that hides the blockage is worse than a late
     * one.
     */
    @Test
    void blockingDoesNotMoveADueDate() {
        UUID tenant = fixture.createTenant("rec-dates");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithDependency();
            LocalDate before = milestoneOrdinal(caseId, 2).dueDate();
            // block it for a while, reconcile repeatedly
            engineReconcileTwice(caseId);
            assertThat(milestoneOrdinal(caseId, 2).dueDate()).isEqualTo(before);
        });
    }

    /**
     * Idempotency. Sub-projects 3-5 will each call reconcile after their own writes, and a
     * second call arriving from a retry must be indistinguishable from one call.
     */
    @Test
    void reconcileTwiceChangesNothing() {
        UUID tenant = fixture.createTenant("rec-idem");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(2, 3);
            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 1));

            var first = snapshot(caseId);
            engineReconcileTwice(caseId);
            assertThat(snapshot(caseId)).isEqualTo(first);
        });
    }

    /** SKIPPED milestones leave the calculation entirely, or 100% is unreachable. */
    @Test
    void skippedMilestonesAreExcludedFromBothHalvesOfTheFraction() {
        // one stage skipped by an entry condition; completing the rest reaches exactly 100
    }

    /** A case whose every milestone is DONE or SKIPPED reads 100, never 99 from rounding. */
    @Test
    void aFullyDoneCaseReadsExactlyOneHundred() { /* durations 1,1,1 -> no 33/33/33 drift */ }
}
```

- [ ] **Step 2: Write the failing concurrency test**

This is the test that justifies the lock, and it must fail without it:

```java
class ReconcileConcurrencyTest extends PostgresTestBase {

    @Autowired RequirementService requirements;
    @Autowired TransactionTemplate transactions;

    /**
     * Two transactions satisfy the last two requirements of one milestone at the same
     * moment. Without a row lock on the case, both read a not-yet-complete milestone,
     * both conclude the stage is exitable, and both advance -- so the case lands two
     * stages on, or writes two stage-entered audit events, depending on interleaving.
     *
     * Idempotency does not save this: each call is individually correct on the state it
     * read. Only serialisation makes the outcome deterministic, which is the whole
     * argument for lock-first rather than reconcile-alone.
     */
    @Test
    void twoConcurrentSatisfactionsProduceExactlyOneAdvance() throws Exception {
        UUID tenant = fixture.createTenant("rec-race");
        var ids = new AtomicReference<TwoRequirements>();
        fixture.runAs(tenant, () -> ids.set(caseWithOneMilestoneAndTwoRequirements()));

        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> satisfyAfterBarrier(barrier, tenant, ids.get().first()));
            var b = pool.submit(() -> satisfyAfterBarrier(barrier, tenant, ids.get().second()));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        fixture.runAs(tenant, () -> {
            assertThat(cases.get(ids.get().caseId()).progressPercent()).isEqualTo(100);
            assertThat(auditEventsFor(ids.get().caseId(), "case.stage_entered")).hasSize(1);
            assertThat(cases.roadmap(ids.get().caseId()).stages().get(0).milestones().get(0).status())
                    .isEqualTo(MilestoneStatus.DONE);
        });
    }
}
```

Run it before the lock exists and record what it does in the commit body. If it happens to pass without the lock, it is not proving anything yet — increase the contention (a third requirement, a tighter barrier) until it fails, then add the lock. A concurrency test that has never failed is worth nothing.

- [ ] **Step 3: Implement the engine**

```java
/**
 * Package-private. @RequirePermission binds to public service methods, so a public
 * engine would be an ungated entry point -- the name-shaped-guard hole CLAUDE.md
 * records for *Directory. AuthorizationCoverageTest additionally binds *Engine from
 * Task 1, so this is guarded twice: unreachable from outside journey, and required to
 * be gated if it ever became reachable.
 */
@Component
class CaseEngine {

    /**
     * The single lock-acquisition point. Callers resolve and authorize the case through
     * AuthorizedQuery first; this re-reads the same row FOR UPDATE.
     *
     * Lock BEFORE the mutation, always. Every entry point then acquires locks in one
     * order, so two engine transactions touching overlapping rows serialise instead of
     * deadlocking. Locking after the write would still serialise reconcile, but two
     * transactions could interleave their writes first and each reconcile a state the
     * other had already invalidated.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Case lockAndLoad(UUID caseId) {
        return cases.lockById(caseId)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
    }

    /**
     * Recomputes everything derivable from persisted state. Idempotent: it reads rows and
     * writes conclusions, and never appends to a history or increments a counter.
     *
     * Deliberately NOT an event chain. Each mutation firing the next step double-advances
     * the moment two sub-projects satisfy the last two requirements of a milestone
     * concurrently, and it can only be tested end-to-end.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void reconcile(Case c) {
        if (c.getStatus() == CaseStatus.COMPLETED || c.getStatus() == CaseStatus.CANCELLED) return;

        var stages = stageRepository.findByVersionIdOrderByOrdinal(c.getVersionId());
        var definitions = milestoneDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId());
        var dependencies = dependencyRepository.findByVersionId(c.getVersionId());
        var instances = milestones.findByCaseIdOrderById(c.getId());
        var requirementRows = requirements.findByCaseId(c.getId());
        var requirementDefs = requirementDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId());

        // 1. Milestone percent, from satisfied requirement WEIGHT.
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;
            m.setProgressPercent(weightedPercent(m, requirementRows, requirementDefs));
        }

        // 2. Statuses. Order matters: a milestone can only be DONE once its requirements
        // are settled, and only BLOCKED once its dependencies are known.
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;
            if (mandatorySettled(m, requirementRows, requirementDefs)) {
                markDone(m);
            } else if (hasUnmetDependency(m, instances, dependencies)) {
                m.setStatus(MilestoneStatus.BLOCKED);
            } else if (isInCurrentStage(m, c, definitions)) {
                m.setStatus(MilestoneStatus.ACTIVE);
            } else {
                m.setStatus(MilestoneStatus.PENDING);
            }
        }

        // 3. Case progress: milestones weighted by estimated_duration_days, with SKIPPED
        // excluded from BOTH halves. Leaving them in the denominator makes 100%
        // unreachable for any case that ever skipped a stage.
        c.setProgressPercent(progressOf(c, instances, definitions));

        // 4. Stage transitions, branching and completion. Task 15 fills this in.
        advanceIfExitable(c, stages, definitions, instances);
    }

    /**
     * Integer arithmetic with an exact-100 guard. Three equal milestones would otherwise
     * read 33 + 33 + 33 = 99 when every one of them is done, and a roadmap that says 99%
     * with nothing left to do is a bug report waiting to be filed.
     */
    int progressOf(Case c, List<Milestone> instances, List<MilestoneDefinition> definitions) {
        Map<UUID, MilestoneDefinition> byId = definitions.stream()
                .collect(toMap(MilestoneDefinition::getId, d -> d));

        int total = 0, done = 0;
        boolean anyOutstanding = false;
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;      // out of both halves
            var definition = byId.get(m.getMilestoneDefinitionId());
            if (definition == null) continue;
            int weight = definition.getEstimatedDurationDays();
            total += weight;
            if (m.getStatus() == MilestoneStatus.DONE) done += weight;
            else anyOutstanding = true;
        }
        if (total == 0) return 0;
        if (!anyOutstanding) return 100;
        return Math.min(99, (int) Math.round(done * 100.0 / total));
    }
}
```

`markDone(m)` sets `DONE` and stamps `completedAt` **only if not already set**, so reconcile stays idempotent — re-stamping a timestamp on every call is exactly the kind of write that makes "twice changes nothing" fail.

Due dates are computed once, when a stage is entered (Task 15), and recomputed only by hold-resume (Task 18) and migration (Task 19). `reconcile` must not touch `dueDate` — the test in Step 1 asserts that.

- [ ] **Step 4: Add the per-method finder exclusion**

```java
// CaseRepository.lockById is the one permitted direct finder call in journey. It
// re-reads a row AuthorizedQuery has already resolved, purely to take the row lock that
// serialises reconciliation, and widens visibility by nothing. Excluded per METHOD
// rather than per class: every other CaseRepository finder must still go through
// AuthorizedQuery.
```

Express it in the rule as a `callMethodWhere(...)` predicate excluding `CaseRepository.lockById` specifically, not by excluding `CaseEngine` wholesale.

- [ ] **Step 5: Run everything**

```bash
cd backend && ./gradlew cleanTest test --tests '*ReconcileTest' --tests '*ReconcileConcurrencyTest' \
  --tests '*AuthorizationCoverageTest'
```
Expected: PASS. Run the concurrency test several times (`--tests '*ReconcileConcurrencyTest' --rerun-tasks`) — a race that passes once has not been shown to be closed.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java
git commit -m "feat: reconcile case state under a row lock, idempotently

One function recomputes milestone statuses, progress and transitions from persisted
state; every mutation path resolves and authorizes the case, takes SELECT FOR UPDATE on
its row, writes, then reconciles -- all in one transaction. Lock-first gives every
entry point the same lock order, so two engine transactions serialise instead of
deadlocking.

The concurrency test is the one that justifies the lock: two transactions satisfying
the last two requirements of a milestone both read an incomplete milestone, both
conclude the stage is exitable, and both advance. Idempotency does not save that --
each call is individually correct on the state it read. Recorded in this commit what
the test did before the lock existed, because a race test that has never failed proves
nothing.

Not an event chain, deliberately. Sub-projects 3-5 will each satisfy requirements from
their own modules, and a chain double-fires the moment two of them land together.

Q6 weighting is tested against hand-computed numbers -- 2/3/5-day milestones giving
20, 50, 100 -- rather than an assertion that recomputes the formula. Integer
arithmetic carries an exact-100 guard: three equal milestones would otherwise read 99
with nothing left to do.

SKIPPED milestones leave both halves of the fraction. Left in the denominator, any
case that ever skipped a stage could never reach 100%.

reconcile never touches a due date. Dependencies gate ACTIVE and nothing else, so a
blocked milestone goes overdue rather than having its plan quietly reflowed -- which
is what the prototype draws, and what makes a blockage visible.

CaseEngine is package-private, and AuthorizationCoverageTest binds *Engine from Task 1:
unreachable from outside journey, and required to be gated if it ever became
reachable. CaseRepository.lockById is excluded from the finder rule per method, not by
exempting the engine."
```

---

## Task 15: Transitions, branching and completion

**Executor's amendment: two Spring Data JPA gotchas, a skip-loop gap, and a
manual-stage read-path design that isn't in the plan text.** Running this verbatim
surfaced two real, previously-invisible defects, plus one gap in the plan's own
transition-loop pseudocode.

1. **`CaseService.create()` (Task 13) never persisted `currentStageId`,
   `progressPercent` or `targetCompletionDate`.** `Case`'s id is a pre-assigned
   Uuid7, not `@GeneratedValue`, and `BaseEntity` implements neither `@Version` nor
   `Persistable` -- so Spring Data's `isNew()` heuristic sees a non-null id on a
   brand-new entity and calls `entityManager.merge()` rather than `persist()`.
   `merge()` returns a *different* managed instance; the original `c` that
   `create()` kept mutating (`writeParticipant`, `upsertAttributes`, `instantiate`,
   `engine.reconcile(c)`) was silently detached from that point on, so none of
   those mutations ever reached the database. Every one of Task 13's own tests
   still passed, because they all read the *in-memory* `CaseView` `create()`
   returns (or milestone/requirement data unaffected by this bug) inside the same
   transaction; nothing exercised a case's persisted `current_stage_id` from a
   *separate* transaction until this task's `advance()` did. Fixed by reassigning
   `c = cases.save(c)`.
2. **`WorkflowService.replaceDraft` (Task 6) has had the identical bug since it was
   written, and no workflow's `fallback_next_stage_id` has ever actually
   persisted.** Its two-pass structure saves each `Stage` once in pass 1, then pass
   2 mutates that same reference again (`setFallbackNextStageId`) without
   capturing `stages.save(stage)`'s return value -- same merge/detach shape as
   above, one task earlier. Invisible until `CaseEngine.branchTargetOf` read
   `Stage.getFallbackNextStageId()` at runtime and always got `null`; every
   `WorkflowAuthoringTest` assertion reads the same in-memory
   `WorkflowDefinitionView` `replaceDraft` returns, which reflects the
   (also-detached-but-correctly-mutated) Java object, not the database row. Fixed
   the same way: reassign `stage = stages.save(stage)` in pass 1. Any future task
   that saves a new entity and then keeps mutating the same reference before the
   transaction ends should check for this shape specifically -- it is silent by
   construction, since every same-transaction read resolves back to the identity
   map's (stale) tracked instance regardless of what the database actually holds.
3. **The plan's `nextStage` walk only calls `skipMilestonesOf` on a stage it
   visited and rejected (a false entry condition), never on one a branch rule or
   fallback jumped past entirely without visiting.** A branch rule may legally
   target any stage of higher ordinal, not only the next one (rule 2 requires
   "higher," not "next"), so a stage skipped by a multi-ordinal jump would sit
   PENDING forever -- still in `progressOf`'s denominator, with no way to ever
   reach DONE, making 100% permanently unreachable for any case that took such a
   branch. This directly contradicts Q7's own example ("segment=SMB: Legal
   Review's milestones become SKIPPED"). Fixed by skipping every stage strictly
   between one cursor position and the next on every hop, not only the final
   rejected one -- see `CaseEngine.nextStage`/`skipStagesBetween`.
4. **`CaseView.availableTransition` needed a read path the plan's text never
   describes.** `aManualStageWaitsForSomeoneWithCaseAdvance` calls plain
   `cases.get(...)`, which never calls `reconcile`, yet must still show the
   computed-but-not-taken transition for a manual (`auto_advance=false`) stage.
   Added `CaseEngine.pendingTransition(Case)`, a non-mutating mirror of
   `advanceIfExitable`'s exitability/branch-target logic that `CaseService.toView`
   calls inside its own (possibly read-only) transaction. It shares
   `nextStage`/`branchTargetOf` with the mutating path via a `mutate` boolean
   rather than duplicating the branch/skip logic, specifically so a plain `get()`
   can never skip a milestone as a side effect of a read.


**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java`
- Create: `backend/src/main/java/co/ara/onboarding/journey/ConditionEvaluator.java`
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseService.java` (add `advance`)
- Test: `backend/src/test/java/co/ara/onboarding/journey/TransitionTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/ConditionEvaluatorTest.java`

**Interfaces:**
- Consumes: `CustomerDirectory`, `CaseAttributeValueRepository`, `BranchRuleRepository`, `Condition`.
- Produces:
  - `ConditionEvaluator.matches(Condition, CustomerFacts, Map<String,CaseAttributeValue>) -> boolean`
  - `CaseService.advance(UUID caseId) -> CaseView` gated on `CASE_ADVANCE`
  - `CaseEngine.advanceIfExitable(...)` (package-private), and `AuditActions.CASE_STAGE_ENTERED`, `CASE_COMPLETED`, `MILESTONE_SKIPPED`

- [ ] **Step 1: Write the failing condition tests**

```java
class ConditionEvaluatorTest {

    /** An unset attribute is FALSE, never true: a missing input must not open a path. */
    @Test
    void anUnsetAttributeEvaluatesFalseForEveryOperator() {
        for (ConditionOperator op : ConditionOperator.values()) {
            assertThat(evaluator.matches(condition(ATTRIBUTE, "segment", op, "SMB"),
                    facts(), Map.of())).isFalse();
        }
    }

    @Test
    void isSetIsTrueOnlyWhenAValueIsPresent() { /* IS_SET with a value -> true */ }

    @Test
    void numericComparisonsUseTheNumericColumn() { /* contractValue > 100000 */ }

    /** "9" > "100000" as strings; a NUMBER attribute must not compare lexically. */
    @Test
    void aNumericAttributeIsNotComparedAsText() { /* … */ }

    @Test
    void customerFieldsResolveFromTheFactsRecord() { /* country EQ "SE" */ }

    @Test
    void inMatchesAnyOfTheListedValues() { /* … */ }

    /** A NULL customer field is absent, not empty-string-equal. */
    @Test
    void aNullCustomerFieldEvaluatesFalse() { /* industry EQ "" -> false when null */ }
}
```

- [ ] **Step 2: Write the failing transition tests**

```java
class TransitionTest extends PostgresTestBase {

    @Test
    void completingEveryMilestoneInAStageEntersTheNextOne() { /* … */ }

    @Test
    void anOptionalRequirementDoesNotHoldAStageOpen() { /* … */ }

    /** First match wins, in ordinal order — not "the most specific rule". */
    @Test
    void theFirstMatchingBranchRuleWins() {
        // two rules both match; the lower ordinal decides
    }

    @Test
    void noMatchingRuleFallsBackToTheDeclaredFallbackThenToTheNextOrdinal() { /* … */ }

    /** Q7's own example: SMB skips Legal Review. */
    @Test
    void aBranchSkipsTheStagesItJumpsOver() {
        // segment=SMB: Legal Review's milestones become SKIPPED, Go Live becomes current
    }

    @Test
    void aStageWhoseEntryConditionIsFalseIsSkippedAndTheLoopContinues() { /* … */ }

    /**
     * The terminal rule: current_stage_id lands on a real stage, never on a skipped one
     * and never null, and the case completes.
     */
    @Test
    void exitingTheFinalStageCompletesTheCase() {
        assertThat(view.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.currentStageId()).isEqualTo(finalStageId);
        assertThat(view.progressPercent()).isEqualTo(100);
    }

    @Test
    void aCaseThatSkipsItsWayToTheEndStillCompletesOnARealStage() { /* … */ }

    /** auto_advance false: the engine computes the transition but does not take it. */
    @Test
    void aManualStageWaitsForSomeoneWithCaseAdvance() {
        assertThat(cases.get(caseId).availableTransition()).isNotNull();
        assertThat(cases.get(caseId).currentStageId()).isEqualTo(firstStageId);

        fixture.runAsUser(tenant, advancer, () -> cases.advance(caseId));
        assertThat(cases.get(caseId).currentStageId()).isEqualTo(secondStageId);
    }

    @Test
    void advancingAStageThatIsNotExitableIsRefused() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.advance(caseId)))
                .isInstanceOf(StageNotExitableException.class);
    }

    /** Nothing moves while an approval is pending, however complete the stage is. */
    @Test
    void aPendingApprovalHoldsTheStage() { /* … */ }

    /** Due dates are set on stage entry, in business days, sequential within the stage. */
    @Test
    void enteringAStageSchedulesItsMilestones() { /* … */ }
}
```

- [ ] **Step 3: Implement the evaluator**

```java
/**
 * Evaluates one condition. Fails closed in every direction: an unknown key, an unset
 * value, an unparseable number and a null customer field all evaluate FALSE. A missing
 * input must never open a path -- the same rule every descriptor follows, for the same
 * reason.
 */
@Component
class ConditionEvaluator {

    boolean matches(Condition condition, CustomerFacts customer,
                    Map<String, CaseAttributeValue> attributes) {
        if (condition == null || condition.getSource() == null) return true;   // no condition = always

        return switch (condition.getSource()) {
            case CUSTOMER  -> matchesText(condition, customerField(customer, condition.getKey()));
            case ATTRIBUTE -> matchesAttribute(condition, attributes.get(condition.getKey()));
        };
    }

    private String customerField(CustomerFacts customer, String key) {
        return switch (key) {
            case "status"   -> customer.status();
            case "industry" -> customer.industry();
            case "country"  -> customer.country();
            // Unknown keys cannot reach here -- publish validation rejects them against
            // CustomerFactKeys.ALL -- but a null return is the fail-closed answer for a
            // version published before a key was removed.
            default -> null;
        };
    }
}
```

- [ ] **Step 4: Implement the transition loop**

```java
/**
 * Called at the end of every reconcile. Computes the transition; takes it only when the
 * stage allows it.
 */
private void advanceIfExitable(Case c, List<Stage> stages,
                               List<MilestoneDefinition> definitions, List<Milestone> instances) {
    if (c.getCurrentStageId() == null) {           // a freshly created case
        enterStage(c, stages.get(0), stages, definitions, instances);
        return;
    }
    Stage current = byId(stages, c.getCurrentStageId());

    if (!isExitable(current, definitions, instances)) return;
    if (current.isRequiresApproval() && !hasApproval(c, current)) {
        ensureStageExitApproval(c, current);       // idempotent: one PENDING row per stage
        return;
    }
    if (!current.isAutoAdvance() && !c.isAdvanceRequested()) return;   // waits for case.advance

    Stage next = nextStage(c, current, stages);
    if (next == null) {
        // The terminal rule. current_stage_id stays on this stage -- a real one, never a
        // skipped one and never null -- and the case completes.
        c.setStatus(CaseStatus.COMPLETED);
        c.setCompletedAt(Instant.now());
        audit.record(AuditActions.CASE_COMPLETED, "onboarding_case", c.getId(),
                "Case completed", Map.of());
        return;
    }
    enterStage(c, next, stages, definitions, instances);
}

/**
 * Branch evaluation, then the entry-condition skip loop.
 *
 * Terminates without a visited set because publish guarantees every branch target has a
 * higher ordinal than its stage, so `cursor` strictly increases. That guarantee is why
 * Task 7's rule 2 is not cosmetic.
 */
private Stage nextStage(Case c, Stage from, List<Stage> stages) {
    CustomerFacts customer = customers.findVisible(c.getCustomerId()).orElse(null);
    Map<String, CaseAttributeValue> attributes = attributeValues.findByCaseId(c.getId())
            .stream().collect(toMap(this::keyOf, v -> v));

    Stage cursor = branchTargetOf(from, c, customer, attributes, stages);
    while (cursor != null) {
        if (evaluator.matches(cursor.getEntryCondition(), customer, attributes)) return cursor;

        // Skipped, not deleted: the roadmap still draws the stage, greyed, so a reader can
        // see the path the case did not take.
        skipMilestonesOf(cursor, c);
        cursor = branchTargetOf(cursor, c, customer, attributes, stages);
    }
    return null;
}

/** First match wins, in ordinal order. Ordinal order, not specificity: a rule an admin
 *  put first is a rule an admin means to win. */
private Stage branchTargetOf(Stage from, Case c, CustomerFacts customer,
                            Map<String, CaseAttributeValue> attributes, List<Stage> stages) {
    for (BranchRule rule : branchRules.findByStageIdOrderByOrdinal(from.getId())) {
        if (evaluator.matches(rule.getCondition(), customer, attributes)) {
            return byId(stages, rule.getTargetStageId());
        }
    }
    if (from.getFallbackNextStageId() != null) return byId(stages, from.getFallbackNextStageId());
    return stages.stream().filter(s -> s.getOrdinal() == from.getOrdinal() + 1).findFirst().orElse(null);
}

/**
 * Entering a stage is where due dates are set, in business days, cumulative over prior
 * milestones in this stage by ordinal. Dependencies do not enter into it: they gate
 * ACTIVE, and a plan that reflows around a blockage hides it.
 */
private void enterStage(Case c, Stage stage, List<Stage> stages,
                        List<MilestoneDefinition> definitions, List<Milestone> instances) {
    c.setCurrentStageId(stage.getId());
    LocalDate cursor = LocalDate.now();
    int cumulative = 0;
    for (MilestoneDefinition definition : definitionsOf(stage, definitions)) {
        cumulative += definition.getEstimatedDurationDays();
        Milestone m = instanceOf(definition, instances);
        m.setDueDate(calendar.plusBusinessDays(cursor, cumulative));
    }
    c.setTargetCompletionDate(latestDueDate(c, stages, definitions, instances));
    audit.record(AuditActions.CASE_STAGE_ENTERED, "onboarding_case", c.getId(),
            "Entered stage " + stage.getName(), Map.of("stageId", stage.getId()));
}
```

`isAdvanceRequested()` is a transient flag on `Case` set by `CaseService.advance` before it calls `reconcile`, never a column — the request to advance is not state worth persisting, and a persisted flag would let a manual stage advance itself on the next unrelated reconcile.

New audit actions — `CASE_STAGE_ENTERED = of("case.stage_entered", true)`, `CASE_COMPLETED = of("case.completed", true)`, `MILESTONE_SKIPPED = of("milestone.skipped", true)`. All timeline-visible: they are the customer's own progress, and the timeline is the picture both sides discuss.

- [ ] **Step 5: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*ConditionEvaluatorTest' --tests '*TransitionTest' --tests '*ReconcileTest'
```

```bash
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java/co/ara/onboarding/journey
git commit -m "feat: advance, branch and complete a case

Stage exit needs every milestone DONE or SKIPPED; a pending approval holds the stage
however complete it is; auto_advance false computes the available transition without
taking it, so someone holding case.advance decides.

Branch rules match first-in-ordinal-order, not most-specific: a rule an admin put
first is a rule an admin means to win. No match falls to the declared fallback, then to
the next ordinal.

The skip loop terminates without a visited set because publish guarantees forward-only
targets, so the cursor strictly increases -- which is why Task 7's rule 2 is load-
bearing rather than cosmetic. Skipped stages keep their rows and are drawn greyed, so a
reader can see the path the case did not take.

The terminal rule: exiting the last stage sets COMPLETED and leaves current_stage_id on
a real stage, never on a skipped one and never null. Publish requires the final stage to
be unconditional, so the loop cannot run off the end.

Conditions fail closed in every direction -- unset attribute, unknown key, unparseable
number, null customer field all evaluate false. A missing input must not open a path,
and there is a test asserting that for every operator. NUMBER attributes compare
numerically, because '9' > '100000' as text.

Due dates are set on stage entry and never by reconcile. isAdvanceRequested is
transient, not a column: a persisted flag would let a manual stage advance itself on the
next unrelated reconcile."
```

---

## Task 16: Requirements and `write_scope`

**Executor's amendment: `WriteScopeTest` cannot extend `SecurityTestBase`, and
`MILESTONE_COMPLETED` needed a call site the plan never gives it.** Two findings
from running this verbatim.

1. **`WriteScopeTest extends SecurityTestBase` does not compile against the plan's
   own conventions.** `SecurityTestBase`'s javadoc is explicit that all nine
   negative security tests "go through MockMvc against real HTTP endpoints rather
   than calling services... a service-level test cannot catch a missing security
   rule" -- but journey has no HTTP layer yet (Task 20 builds it), so there is no
   `/api/t/{slug}/...` endpoint for `satisfy`/`waive` to drive. Written instead as
   `extends PostgresTestBase`, calling `RequirementService` directly, in the same
   style as `CaseCreationTest`/`TransitionTest`. This is a real, documented
   narrowing of coverage (it cannot catch a controller that forgets to delegate,
   because there is no controller) that Task 20 should close by adding an
   HTTP-level counterpart once the endpoints exist.
2. **`AuditActions.MILESTONE_COMPLETED` is catalogued by this task's own "Audit
   additions" line but the plan's `satisfy` pseudocode never calls
   `audit.record` for it.** The only place a milestone ever actually transitions to
   DONE is `CaseEngine.markDone` (Task 14), not `RequirementService.satisfy` --
   satisfying the *last* requirement is what triggers it, but `markDone` runs
   inside `reconcile`, once, regardless of which write triggered the call. The
   call site is added there, guarded by the same `completedAt == null` check that
   keeps `reconcile` idempotent, rather than in `RequirementService` where the
   plan implies it. An audit action declared with no call site is a gap the next
   reader would have assumed was already covered.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/RequirementService.java`, `StageWriteScopeGuard.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/RequirementTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/WriteScopeTest.java`

**Interfaces:**
- Consumes: `CaseEngine`, `AuthorizedQuery`, `MILESTONE_COMPLETE`, `REQUIREMENT_WAIVE`.
- Produces:
  - `RequirementService.satisfy(UUID requirementId, UUID ref, String refType) -> RequirementView`
  - `RequirementService.waive(UUID requirementId, String reason) -> RequirementView`
  - `StageWriteScopeGuard.check(Case c, Milestone m, Stage stage)` — throws `WriteScopeException`

- [ ] **Step 1: Write the failing tests, the narrowing one first**

```java
class WriteScopeTest extends SecurityTestBase {

    /**
     * The assertion that proves write_scope is a real constraint and not a rendered
     * field: the caller holds milestone.complete at ALL -- the widest possible grant --
     * and is still refused, because the stage says OWNER_ONLY and they are not the owner.
     */
    @Test
    void allScopeIsStillRefusedInAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("ws-owner");
        var actor = new AtomicReference<UUID>();
        var requirementId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            actor.set(fixture.createUser(tenant, "wide@example.com"));
            grantRole(tenant, actor.get(), Map.of(PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                                                  PermissionKeys.CASE_VIEW, Scope.ALL));
            requirementId.set(firstRequirementOfCaseInOwnerOnlyStage(tenant));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor.get(),
                () -> requirements.satisfy(requirementId.get(), null, null)))
                .isInstanceOf(WriteScopeException.class);
    }

    @Test
    void theMilestoneOwnerMayWriteInAnOwnerOnlyStage() { /* … */ }

    @Test
    void theCaseOwnerMayWriteInAnOwnerOnlyStage() { /* … */ }

    @Test
    void aTeamStageAdmitsOnlyTheOwningTeam() { /* … */ }

    @Test
    void aDepartmentStageAdmitsOnlyTheOwningDepartment() { /* … */ }

    /**
     * The direction that must never work: write_scope cannot let someone in. An actor
     * with no milestone.complete grant is refused by the permission gate first, and
     * ANY does not rescue them.
     */
    @Test
    void anAnyStageStillRequiresThePermission() {
        assertThatThrownBy(() -> fixture.runAsUser(tenant, ungranted,
                () -> requirements.satisfy(requirementId, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

```java
class RequirementTest extends PostgresTestBase {

    @Test
    void satisfyingTheLastMandatoryRequirementCompletesTheMilestone() { /* … */ }

    @Test
    void satisfyingIsIdempotentForAnAlreadySatisfiedRequirement() { /* second call is a no-op, no second audit row */ }

    @Test
    void waivingRequiresAReason() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> requirements.waive(id, "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWaivedRequirementSettlesTheMilestoneAndIsAudited() { /* requirement.waived, timeline-visible */ }

    /** Hold blocks progress; Task 18 owns the transition, this owns the refusal. */
    @Test
    void satisfyingIsRefusedWhileTheCaseIsOnHold() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> requirements.satisfy(id, null, null)))
                .isInstanceOf(CaseOnHoldException.class);
    }

    /**
     * The seam sub-projects 3-5 use. A satisfied requirement records WHAT satisfied it,
     * with no foreign key, so a task or document id can be recorded before those tables
     * exist.
     */
    @Test
    void aSatisfyingReferenceIsRecordedWithoutAForeignKey() {
        UUID pretendTaskId = Uuid7.generate();
        fixture.runAs(tenant, () -> {
            var view = requirements.satisfy(id, pretendTaskId, "task");
            assertThat(view.satisfiedRef()).isEqualTo(pretendTaskId);
            assertThat(view.satisfiedRefType()).isEqualTo("task");
        });
    }
}
```

- [ ] **Step 2: Implement, with the guard as its own class**

```java
/**
 * Subtractive only. It runs AFTER @RequirePermission has said yes and AFTER
 * AuthorizedQuery has resolved the record, and every branch either refuses or does
 * nothing -- there is no path here that grants. A second mechanism able to widen
 * authority would be a parallel authorization system, which is the one thing this
 * codebase must not grow.
 *
 * A separate class rather than a method on the service so there is exactly one place to
 * read, and so a reviewer can see that it has no positive branch.
 */
@Component
class StageWriteScopeGuard {

    void check(Case c, Milestone m, Stage stage) {
        AuthContext ctx = contextProvider.current();
        boolean allowed = switch (stage.getWriteScope()) {
            case ANY        -> true;
            case OWNER_ONLY -> ctx.userId().equals(m.getOwnerUserId())
                            || ctx.userId().equals(c.getOwnerUserId());
            case TEAM       -> c.getOwningTeamId() != null && ctx.teamIds().contains(c.getOwningTeamId());
            case DEPARTMENT -> c.getOwningDepartmentId() != null
                            && c.getOwningDepartmentId().equals(ctx.departmentId());
        };
        if (!allowed) throw new WriteScopeException(stage.getName(), stage.getWriteScope());
    }
}
```

```java
@RequirePermission(PermissionKeys.MILESTONE_COMPLETE)
@Transactional
public RequirementView satisfy(UUID requirementId, UUID ref, String refType) {
    // Resolved under the WRITE permission, not the read one. Fetching with a read
    // permission and then writing is an escalation: someone who may see everything but
    // edit only what they own would be able to edit everything they can see.
    Requirement r = authorizedQuery.getById(
            requirements, Requirement.class, PermissionKeys.MILESTONE_COMPLETE, requirementId);

    Case c = engine.lockAndLoad(r.getCaseId());          // lock BEFORE the write
    if (c.getStatus() == CaseStatus.ON_HOLD) throw new CaseOnHoldException(c.getId());

    Milestone m = milestones.findById(r.getMilestoneId()).orElseThrow();
    writeScope.check(c, m, stageOf(c, m));

    if (r.getStatus() == RequirementStatus.SATISFIED) return toView(r);   // idempotent

    r.setStatus(RequirementStatus.SATISFIED);
    r.setSatisfiedAt(Instant.now());
    r.setSatisfiedBy(contextProvider.principal().userId());
    r.setSatisfiedRef(ref);
    r.setSatisfiedRefType(refType);

    engine.reconcile(c);
    audit.record(AuditActions.REQUIREMENT_SATISFIED, "requirement", r.getId(),
            "Completed " + labelOf(r), Map.of("milestoneId", m.getId()));
    return toView(r);
}
```

Audit additions: `REQUIREMENT_SATISFIED = of("requirement.satisfied", true)`, `REQUIREMENT_WAIVED = of("requirement.waived", true)`, `MILESTONE_COMPLETED = of("milestone.completed", true)` — all the customer's own progress.

- [ ] **Step 3: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*RequirementTest' --tests '*WriteScopeTest'
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java
git commit -m "feat: satisfy and waive requirements, narrowed by stage write scope

The guard is its own class with no positive branch, so a reviewer can see it only ever
subtracts. The test that matters is the inverse one: a holder of milestone.complete at
ALL is still refused inside an OWNER_ONLY stage, and an ANY stage still requires the
permission -- write_scope can close a door, never open one.

Records are resolved under the WRITE permission, not the read one. Fetching with
milestone.complete's read counterpart and then writing would let someone who may see
everything but edit only what they own edit everything they can see.

satisfied_ref is recorded with no foreign key and a type discriminator, which is the
seam sub-projects 3-5 fill: a task or document id can be recorded before those tables
exist, and a soft reference cannot reproduce the cross-tenant oracle an FK checked with
row security bypassed would."
```

---

## Task 17: Approvals and force-complete

> **Amendment (executed verbatim, 2026-08-22):** two findings running this task.
>
> First, the plan gives full pseudocode for `decideForceComplete` but none for
> `decideStageExit` despite listing it under Interfaces. Built it to the same shape
> (kind check, already-decided check, lock, decide, reconcile-on-approve) but without
> the self-approval check -- Q5's self-approval requirement is specific to forced
> completions; nothing in the spec asks a stage-exit approver to differ from whoever
> the engine parked the PENDING row under (`contextProvider.principal()` at the time
> `CaseEngine.ensureStageExitApproval` ran, not a human "requester" in the Q5 sense).
>
> Second, and more load-bearing: `CaseEngine.recomputeStatusesAndProgress` (Task 14)
> recomputed every non-SKIPPED milestone's status from requirement settlement on
> *every* call. A forced completion sets a milestone `DONE` without touching its
> `Requirement` rows, so the `engine.reconcile(c)` call `decideForceComplete` itself
> makes to pick up the change immediately recomputed `mandatorySettled()` as false and
> demoted the milestone straight back to `ACTIVE` -- silently undoing the force-complete
> in the same method call that recorded it. Caught by
> `anApprovedForceCompletionIsAuditedAsForcedWithItsReason` failing with `expected: DONE
> but was: ACTIVE`. This was invisible through Tasks 14-16 because nothing else in the
> codebase can make a `DONE` milestone's requirements read as unsettled again (`satisfy`/
> `waive` never revert a requirement's status) -- force-complete is the first path that
> marks a milestone `DONE` while its requirements stay open. Fixed by making `DONE`
> sticky in the status loop, the same way `SKIPPED` already is (`CaseEngine.java`,
> `recomputeStatusesAndProgress`).

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/ApprovalService.java`, `MilestoneService.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/ApprovalTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/security/ForceCompleteTest.java`

**Interfaces:**
- Produces:
  - `MilestoneService.requestForceComplete(UUID milestoneId, String reason) -> ApprovalView`
  - `ApprovalService.decideStageExit(UUID approvalId, boolean approve, String note) -> ApprovalView`
  - `ApprovalService.decideForceComplete(UUID approvalId, boolean approve, String note) -> ApprovalView`
  - `MilestoneService.reopen(UUID milestoneId, String reason)` (Task 18 uses it)

- [ ] **Step 1: Write the failing security tests — Q5's three requirements, separately**

```java
class ForceCompleteTest extends SecurityTestBase {

    /** Requirement one: a reason, unavoidable because the column is NOT NULL. */
    @Test
    void aForceRequestWithoutAReasonIsRejected() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                milestones.requestForceComplete(milestoneId, "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Requirement two: not a single click. The requester cannot be the decider. */
    @Test
    void theRequesterCannotApproveTheirOwnForceRequest() {
        UUID tenant = fixture.createTenant("force-self");
        var admin = new AtomicReference<UUID>();          // holds BOTH permissions
        var approvalId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "boss@example.com"));
            grantRole(tenant, admin.get(), Map.of(
                    PermissionKeys.MILESTONE_FORCE_COMPLETE, Scope.ALL,
                    PermissionKeys.MILESTONE_FORCE_APPROVE, Scope.ALL,
                    PermissionKeys.CASE_VIEW, Scope.ALL));
        });

        fixture.runAsUser(tenant, admin.get(), () ->
                approvalId.set(milestones.requestForceComplete(milestoneId, "Customer verbally confirmed").id()));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, admin.get(),
                () -> approvals.decideForceComplete(approvalId.get(), true, "ok")))
                .isInstanceOf(SelfApprovalException.class);
    }

    /** Requirement three: recorded, and distinguishable from an ordinary completion. */
    @Test
    void anApprovedForceCompletionIsAuditedAsForcedWithItsReason() {
        // milestone.force_completed, timelineVisible true, payload carries the reason
        assertThat(auditEventsFor(caseId, "milestone.force_completed")).hasSize(1);
        assertThat(milestoneOf(caseId, 1).completionReason()).isEqualTo("Customer verbally confirmed");
    }

    /** Q5's "above Project Manager": the approve key cannot be held narrowly. */
    @Test
    void theForceApprovePermissionCannotBeGrantedBelowAllScope() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.createRole("Sneaky", "",
                Map.of(PermissionKeys.MILESTONE_FORCE_APPROVE, Scope.TEAM))))
                .isInstanceOf(InvalidGrantException.class);
    }

    /**
     * Kind confusion. Deciding a FORCE_COMPLETE through the stage-exit endpoint must fail
     * rather than fall through to the weaker gate -- approval.decide is ORG-scoped and
     * milestone.force_approve is ALL-only, so the two endpoints are not interchangeable.
     */
    @Test
    void aForceRequestCannotBeDecidedThroughTheStageExitPath() {
        assertThatThrownBy(() -> fixture.runAsUser(tenant, approver,
                () -> approvals.decideStageExit(forceApprovalId, true, "ok")))
                .isInstanceOf(ApprovalKindMismatchException.class);
    }

    @Test
    void aStageExitCannotBeDecidedThroughTheForcePath() { /* the mirror case */ }

    @Test
    void aRejectedForceRequestLeavesTheMilestoneAlone() { /* status unchanged, event recorded */ }
}
```

- [ ] **Step 2: Implement, with the self-approval check in one place**

```java
/**
 * Q5 asks for three things and each is a separate mechanism rather than a policy note:
 * the reason is NOT NULL in the schema, the decider must differ from the requester, and
 * the decision writes a distinct action key so a forced completion never reads as an
 * ordinary one.
 */
@RequirePermission(PermissionKeys.MILESTONE_FORCE_APPROVE)
@Transactional
public ApprovalView decideForceComplete(UUID approvalId, boolean approve, String note) {
    Approval a = authorizedQuery.getById(
            approvals, Approval.class, PermissionKeys.MILESTONE_FORCE_APPROVE, approvalId);

    // Checked before anything else: a kind mismatch here means the caller reached a
    // FORCE_COMPLETE through the ORG-scoped stage-exit gate, or the reverse.
    if (a.getKind() != ApprovalKind.FORCE_COMPLETE) throw new ApprovalKindMismatchException(approvalId);
    if (a.getStatus() != ApprovalStatus.PENDING) throw new ApprovalAlreadyDecidedException(approvalId);

    UUID decider = contextProvider.principal().userId();
    // What makes it "not a single click". Without this, a user holding both keys -- which
    // Administrator does -- could request and approve in two calls with no second person
    // involved, and the approval flow would be theatre.
    if (decider.equals(a.getRequestedBy())) throw new SelfApprovalException(approvalId);

    Case c = engine.lockAndLoad(a.getCaseId());
    a.setStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
    a.setDecidedBy(decider);
    a.setDecidedAt(Instant.now());
    a.setDecisionNote(note);

    if (approve) {
        Milestone m = milestones.findById(a.getMilestoneId()).orElseThrow();
        m.setStatus(MilestoneStatus.DONE);
        m.setCompletedAt(Instant.now());
        m.setCompletedBy(decider);
        m.setCompletionReason(a.getReason());      // carried from the request, not the decision
        engine.reconcile(c);
        audit.record(AuditActions.MILESTONE_FORCE_COMPLETED, "milestone", m.getId(),
                "Forced completion of " + nameOf(m) + ": " + a.getReason(),
                Map.of("approvalId", a.getId(), "requestedBy", a.getRequestedBy()));
    } else {
        audit.record(AuditActions.MILESTONE_FORCE_REJECTED, "milestone", a.getMilestoneId(),
                "Rejected forced completion", Map.of("approvalId", a.getId()));
    }
    return toView(a);
}
```

`MILESTONE_FORCE_COMPLETED = of("milestone.force_completed", true)` — timeline-visible, and deliberately a **different key** from `milestone.completed`. Sub-project 1 shipped `UserAdminService.deactivate` writing the `user.created` key, and because `audit_event` is append-only those rows can never be corrected; a forced completion recorded as an ordinary one would be the same mistake with a worse consequence.

`PermissionCatalog` must list `MILESTONE_FORCE_APPROVE` with `ALL_ONLY`, which is what makes the "cannot be granted below ALL" test pass — `RoleService` already validates grants against the catalog.

- [ ] **Step 3: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*ApprovalTest' --tests '*ForceCompleteTest'
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java
git commit -m "feat: gate stage exits and forced completions behind approvals

Q5's three requirements are three mechanisms: the reason is NOT NULL in the schema so
the flow cannot be built without one, the decider must differ from the requester, and
the decision writes milestone.force_completed rather than milestone.completed.

Self-approval is the check that makes this more than theatre. Administrator holds both
keys, so without it one person could request and approve in two calls and the approval
flow would decorate a single click.

Two decide methods, not one, and a kind mismatch is refused before anything else. A
single decide path could not carry approval.decide for a stage exit and
milestone.force_approve for a forcing, and choosing the gate inside the method body
would hide an authorization decision where no coverage test can see it.

A forced completion gets its own action key. Sub-project 1 wrote user.created for
deactivations and, because audit_event is append-only, those rows can never be
corrected -- the same mistake here would make every 'completed' figure include
completions nobody earned."
```

---

## Task 18: Hold, resume, reopen and reassign

> **Amendment (executed verbatim, 2026-08-22):** two findings running this task.
>
> First, and load-bearing: Task 17's fix made `DONE` sticky in
> `CaseEngine.recomputeStatusesAndProgress` -- once a milestone reads `DONE`, the
> status loop skips it entirely, on every future `reconcile()` call, forever. The
> plan's own pseudocode for `reopen` clears `completedAt`/`completedBy`/
> `completionReason` and expects the `engine.reconcile(c)` call it makes afterward to
> recompute the milestone back to `ACTIVE` via `isInCurrentStage` -- exactly what
> happened before Task 17. After it, clearing the completion fields does nothing to
> the status the sticky guard reads, so the milestone stayed `DONE` forever and
> `reopeningAMilestoneMakesItActiveAgainAndReducesProgress` failed with `progressPercent`
> stuck at 100. Fixed by having `reopen` move the milestone to `PENDING` itself before
> calling `reconcile` -- a transient value, since the cascade (`mandatorySettled` /
> `hasUnmetDependency` / `isInCurrentStage`) decides the real one milliseconds later
> in the same transaction, but enough to get the milestone off the sticky value so the
> cascade runs at all. Nothing before this task ever needed to move a milestone off
> `DONE`, which is why Task 17's stickiness fix didn't need to consider it.
>
> Second: `MilestoneEditTest`'s two narrowly-scoped actors (Step 0's outsider, and the
> non-owner in `reassignmentIsRefusedByAnOwnerOnlyStageForANonOwner`) were granted
> `CASE_VIEW`/`MILESTONE_EDIT` at `ASSIGNED`/`ALL` but not `WORKFLOW_VIEW` at `ALL`.
> `CaseService.get()`'s `toView` reads the case's pinned `Stage` under `WORKFLOW_VIEW`,
> and `MilestoneService.update()`'s own `stageOf(m)` does the same before `write_scope`
> is ever consulted -- both 404 instead of the outsider seeing the case (Step 0) or
> `WriteScopeException` being reached (the write-scope test) without it. This is the
> exact invariant `CaseEditTest` already documents ("WORKFLOW_VIEW ALL joins every real
> operational role template alongside CASE_VIEW"); the plan's Step 0 pseudocode for
> this task simply didn't carry it over. Fixed by adding the grant to both actors.

**Files:**
- Modify: `CaseService.java` (`hold`, `resume`), `MilestoneService.java` (`reopen`, `update`)
- Test: `backend/src/test/java/co/ara/onboarding/journey/HoldTest.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/MilestoneEditTest.java`

**Interfaces:**
- Produces: `CaseService.hold(UUID caseId, String reason)`, `CaseService.resume(UUID caseId)`, `MilestoneService.reopen(UUID milestoneId, String reason)`, and `MilestoneService.update(UUID milestoneId, UpdateMilestoneRequest) -> MilestoneView` where `UpdateMilestoneRequest(UUID ownerUserId, LocalDate dueDate)`, gated on `MILESTONE_EDIT`.

- [ ] **Step 0: Write the failing reassignment tests — Q15's manual override**

```java
class MilestoneEditTest extends PostgresTestBase {

    /**
     * Q15: "an authorized user can reassign a milestone to any user". The invariant that
     * makes it usable rather than a trap: assigning an owner also creates their ASSIGNEE
     * participant row. Without it the new owner holds a milestone inside a case they
     * cannot open -- a 404 on the only screen that would explain their work.
     */
    @Test
    void assigningAnOwnerAlsoMakesThemACaseParticipant() {
        UUID tenant = fixture.createTenant("ms-assign");
        var outsider = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            outsider.set(fixture.createUser(tenant, "specialist@example.com"));
            grantRole(tenant, outsider.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED,
                                                     PermissionKeys.MILESTONE_COMPLETE, Scope.ASSIGNED));
            milestones.update(milestoneId, new UpdateMilestoneRequest(outsider.get(), null));
        });

        // The point of the invariant: they can now actually see the case.
        fixture.runAsUser(tenant, outsider.get(), () ->
                assertThat(cases.get(caseId)).isNotNull());
    }

    @Test
    void reschedulingAMilestoneMovesOnlyThatDueDate() { /* siblings untouched */ }

    @Test
    void aUserFromAnotherTenantCannotBeAssigned() {
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                milestones.update(milestoneId, new UpdateMilestoneRequest(tenantBUserId, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reassignmentRequiresMilestoneEditAndIsAudited() { /* milestone.reassigned, timeline-visible */ }

    @Test
    void reassignmentIsRefusedByAnOwnerOnlyStageForANonOwner() { /* write_scope again */ }
}
```

Implement `update` with the owner id resolved through `AuthorizedQuery` under `USER_VIEW`, the participant row created idempotently, and `MILESTONE_REASSIGNED = of("milestone.reassigned", true)`. Note that `reconcile` must **not** recompute the due date afterwards — a hand-set date is a decision, and Task 14's test that reconcile never touches `dueDate` is what protects it.

- [ ] **Step 1: Write the failing tests**

```java
class HoldTest extends PostgresTestBase {

    @Test
    void holdingACaseRefusesFurtherProgress() { /* satisfy -> CaseOnHoldException */ }

    /**
     * Q8's pause, from this side. Resuming shifts every open milestone's due date and the
     * case's target completion by the elapsed business days: a promise made while paused
     * was not a promise broken, and sub-project 6 reads total_hold_days rather than
     * recomputing it.
     */
    @Test
    void resumingShiftsOpenDueDatesByTheHeldBusinessDays() {
        UUID tenant = fixture.createTenant("hold-shift");
        fixture.runAs(tenant, () -> {
            UUID caseId = aCaseInItsFirstStage();
            LocalDate originalDue = milestoneOrdinal(caseId, 1).dueDate();
            LocalDate originalTarget = cases.get(caseId).targetCompletionDate();

            cases.hold(caseId, "Waiting on the customer's bank letter");
            clock.advance(Duration.ofDays(7));            // one week, five business days
            cases.resume(caseId);

            assertThat(milestoneOrdinal(caseId, 1).dueDate())
                    .isEqualTo(calendar.plusBusinessDays(originalDue, 5));
            assertThat(cases.get(caseId).targetCompletionDate())
                    .isEqualTo(calendar.plusBusinessDays(originalTarget, 5));
            assertThat(cases.get(caseId).totalHoldDays()).isEqualTo(5);
        });
    }

    /** A completed milestone's date is history and must not move. */
    @Test
    void resumingDoesNotTouchAlreadyCompletedMilestones() { /* … */ }

    @Test
    void holdAccumulatesAcrossSeveralPauses() { /* 5 + 5 = 10 */ }

    @Test
    void holdingRequiresCaseHoldAndAReason() { /* … */ }

    /**
     * The rework path. Task 7 rejects backward branches, so reopening is how "verification
     * failed, go back" happens -- deliberately an explicit action with a permission, a
     * reason and an event rather than an invisible graph edge.
     */
    @Test
    void reopeningAMilestoneMakesItActiveAgainAndReducesProgress() {
        fixture.runAs(tenant, () -> {
            UUID caseId = aCaseWithAllMilestonesDone();
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(100);

            milestones.reopen(milestoneOrdinal(caseId, 2).id(), "KYC pack was the wrong entity");

            assertThat(cases.get(caseId).progressPercent()).isLessThan(100);
            assertThat(cases.get(caseId).status()).isEqualTo(CaseStatus.ACTIVE);   // un-completes
            assertThat(milestoneOrdinal(caseId, 2).status()).isEqualTo(MilestoneStatus.ACTIVE);
        });
    }

    @Test
    void reopeningRequiresAReasonAndIsAudited() { /* milestone.reopened, timeline-visible */ }
}
```

`clock.advance` requires the engine to read time through an injectable `Clock` rather than `Instant.now()`/`LocalDate.now()`. Make that change here and use `Clock` consistently in `CaseEngine`, `CaseService` and `MilestoneService` — a hold test that sleeps for real is a test nobody will run.

- [ ] **Step 2: Implement**

```java
@RequirePermission(PermissionKeys.CASE_HOLD)
@Transactional
public CaseView resume(UUID caseId) {
    Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_HOLD, caseId);
    if (c.getStatus() != CaseStatus.ON_HOLD) throw new CaseNotOnHoldException(caseId);
    engine.lockAndLoad(caseId);

    int heldBusinessDays = calendar.businessDaysBetween(
            LocalDate.ofInstant(c.getHeldAt(), ZoneOffset.UTC), LocalDate.now(clock));

    // Only OPEN work moves. A completed milestone's due date is history: shifting it would
    // rewrite when the work was actually promised.
    for (Milestone m : milestones.findByCaseIdOrderById(caseId)) {
        if (m.getStatus() == MilestoneStatus.DONE || m.getStatus() == MilestoneStatus.SKIPPED) continue;
        if (m.getDueDate() != null) {
            m.setDueDate(calendar.plusBusinessDays(m.getDueDate(), heldBusinessDays));
        }
    }
    if (c.getTargetCompletionDate() != null) {
        c.setTargetCompletionDate(
                calendar.plusBusinessDays(c.getTargetCompletionDate(), heldBusinessDays));
    }
    c.setTotalHoldDays(c.getTotalHoldDays() + heldBusinessDays);
    c.setHeldAt(null);
    c.setStatus(CaseStatus.ACTIVE);

    engine.reconcile(c);
    audit.record(AuditActions.CASE_RESUMED, "onboarding_case", caseId,
            "Resumed after " + heldBusinessDays + " business days on hold",
            Map.of("totalHoldDays", c.getTotalHoldDays()));
    return toView(c);
}
```

`reopen` sets the milestone back to `ACTIVE`, clears `completedAt`/`completedBy`/`completionReason`, reopens its requirements, and — if the case was `COMPLETED` — returns it to `ACTIVE` and clears `completedAt`, because a completed case with outstanding work is a lie the dashboard would repeat.

Audit: `CASE_HELD = of("case.held", true)`, `CASE_RESUMED = of("case.resumed", true)`, `MILESTONE_REOPENED = of("milestone.reopened", true)`.

- [ ] **Step 3: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*HoldTest'
git add backend/src/main/java/co/ara/onboarding backend/src/test/java
git commit -m "feat: hold, resume and reopen

Resuming shifts every OPEN milestone's due date and the case target by the elapsed
business days, and accumulates total_hold_days for sub-project 6 to read rather than
recompute. Completed milestones keep their dates: shifting them would rewrite when the
work was actually promised.

Reopening is the rework path. Task 7 rejects backward branch targets, so 'verification
failed, go back' is deliberately an explicit action with a permission, a mandatory
reason and an event -- not an invisible graph edge. It also un-completes a COMPLETED
case, because a completed case with outstanding work is a lie every dashboard would
repeat.

Time is read through an injected Clock from here on. A hold test that sleeps for real
is a test nobody runs, and this is the first behaviour where elapsed time is the thing
under test."
```

---

## Task 19: Version migration

> **Amendment (executed verbatim, 2026-08-22):** the plan gives `evaluate`'s
> pseudocode but leaves `migrate` and its supporting helpers as prose
> ("maps surviving milestones by (stage name, milestone name)... instantiates
> anything new... marks orphaned future milestones SKIPPED... then reconciles").
> Filling that in surfaced three things worth recording.
>
> First, `Milestone.milestoneDefinitionId` and `Requirement.requirementDefinitionId`
> must be REMAPPED in place onto the target version's matching definition ids, not
> merely left alone. `CaseEngine`'s progress and status computation (`progressOf`,
> `weightedPercent`, `mandatorySettled`) all join through `c.getVersionId()`-scoped
> definition lists; a milestone whose id still pointed at the OLD version's
> definition would vanish from the roadmap and from progress entirely (silently
> excluded, not merely mis-scored), and a not-yet-done milestone whose requirements
> weren't remapped would read `mandatorySettled() == true` by vacuous default (every
> requirement's definition lookup returns null and is skipped), completing itself on
> the very next `reconcile()`. Requirements are matched to their new definition by
> `label` within the milestone (there is no separate stable key on
> `RequirementDefinition`); an unmatched old requirement is left un-remapped rather
> than deleted, which is functionally identical to Task 17/18's "orphaned, not
> deleted" rule since it is then silently ignored by the same lookups.
>
> Second, the CURRENT stage's due dates need explicit recomputation.
> `CaseEngine.reconcile`'s `enterStage` -- the only place due dates are ever set --
> only runs on an actual stage *transition*; a case's current stage was already
> entered under the old version, so migrating never re-triggers it. `migrateOne`
> duplicates `enterStage`'s cumulative-business-days rule for exactly that one
> stage's still-open milestones (using the target version's durations), which is
> what `migratingRecomputesDatesAndProgressAgainstTheNewDurations` needed to pass.
>
> Third, an unrelated fixture bug in `JourneyFixtures.publishNewVersion` (new in
> this task): `WorkflowService.createDraft` already calls `replaceDraft` once
> internally when copying an existing published version, bumping the fresh draft's
> `lockVersion` from 0 to 1 before this fixture's own `replaceDraft` call ever runs.
> Passing the caller's request through with a hardcoded `lockVersion` of 0 (as every
> other fixture request literal in this file does) failed every test in this task
> with `OptimisticLockingFailureException: Draft ... was modified by someone else`.
> Fixed by reading the draft's actual current `lockVersion` before the second
> `replaceDraft` call rather than trusting the request literal to know it.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/MigrationService.java`, `MigrationPreviewView.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/MigrationTest.java`

**Interfaces:**
- Produces:
  - `MigrationService.preview(UUID versionId) -> MigrationPreviewView` with `record MigrationPreviewView(UUID versionId, int onVersion, int eligible, List<CandidateView> candidates)` and `record CandidateView(UUID caseId, UUID customerId, String currentStageName, boolean eligible, String reason)`
  - `MigrationService.migrate(UUID targetVersionId, List<UUID> caseIds) -> int`

- [ ] **Step 1: Write the failing tests, one per ineligibility reason**

```java
class MigrationTest extends PostgresTestBase {

    /** The panel's two numbers: "31 cases on v4 / 18 eligible to migrate". */
    @Test
    void thePreviewCountsCasesOnTheOldVersionAndThoseEligible() { /* … */ }

    @Test
    void aCaseWhoseStagesAllStillExistIsEligible() { /* … */ }

    /**
     * Reason one: the new version deleted a stage this case has already passed. Migrating
     * would leave completed work pointing at definitions that no longer exist.
     */
    @Test
    void aCaseThatHasPassedADeletedStageIsIneligibleWithThatReason() {
        assertThat(candidate.eligible()).isFalse();
        assertThat(candidate.reason()).contains("Legal Review");
    }

    /** Reason two: a newly required attribute the case has no value for. */
    @Test
    void aCaseMissingANewlyRequiredAttributeIsIneligible() {
        assertThat(candidate.reason()).contains("tier");
    }

    @Test
    void aCompletedCaseIsNotACandidate() { /* nothing left to migrate */ }

    @Test
    void migratingRepinsTheCaseAndReinstantiatesFutureStages() {
        fixture.runAs(tenant, () -> {
            migrations.migrate(v2, List.of(caseId));

            assertThat(cases.get(caseId).versionId()).isEqualTo(v2);
            // Work already done is preserved; stages not yet reached come from v2.
            assertThat(milestoneOrdinal(caseId, 1).status()).isEqualTo(MilestoneStatus.DONE);
            assertThat(cases.roadmap(caseId).stages()).hasSize(4);   // v2 added one
        });
    }

    @Test
    void migratingRecomputesDatesAndProgressAgainstTheNewDurations() { /* … */ }

    @Test
    void migratingAnIneligibleCaseIsRefusedRatherThanSkippedSilently() {
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> migrations.migrate(v2, List.of(badCase))))
                .isInstanceOf(CaseNotMigratableException.class);
    }

    @Test
    void migrationRequiresCaseMigrate() { /* AccessDeniedException for a PM */ }

    @Test
    void eachMigratedCaseIsAuditedIndividually() { /* one case.migrated per case */ }
}
```

- [ ] **Step 2: Implement**

The eligibility rule, which is the part worth writing out:

```java
/**
 * A case is eligible when nothing it has already done would be orphaned, and nothing the
 * new version demands is missing.
 *
 * Deliberately conservative. The alternative -- migrating and repairing -- would rewrite
 * history for completed milestones, and the whole point of Q2's freeze-by-default is that
 * a running case's definition does not change under it without someone deciding so.
 */
private CandidateView evaluate(Case c, UUID targetVersionId,
                               List<Stage> targetStages, List<AttributeDefinition> targetAttributes) {
    Set<String> targetStageNames = targetStages.stream().map(Stage::getName).collect(toSet());

    // Stage identity across versions is the NAME, because a new version's rows are new
    // ids: a deep copy produces different primary keys for the same stage. Name is what
    // the builder shows and what an admin reasons about when they reorder.
    for (Stage passed : stagesAlreadyPassed(c)) {
        if (!targetStageNames.contains(passed.getName())) {
            return ineligible(c, "Stage '" + passed.getName()
                    + "' has already been completed but no longer exists in the new version");
        }
    }
    Set<String> present = attributeValues.findByCaseId(c.getId()).stream()
            .map(this::keyOf).collect(toSet());
    for (AttributeDefinition required : targetAttributes) {
        if (required.isRequired() && !present.contains(required.getKey())) {
            return ineligible(c, "The new version requires attribute '" + required.getKey()
                    + "', which this case has no value for");
        }
    }
    return eligible(c);
}
```

`migrate` repins `versionId`, maps surviving milestones by `(stage name, milestone name)` and preserves their status and completion, instantiates anything new, marks orphaned future milestones `SKIPPED` rather than deleting them (`DELETE` is denied and the history matters), then `reconcile`s. Add `CASE_MIGRATED = of("case.migrated", false)` — **not** timeline-visible: which internal version a case follows is the vendor's configuration, not the customer's business, and the same reasoning `user.created` carries.

- [ ] **Step 3: Run and commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*MigrationTest'
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java
git commit -m "feat: migrate running cases to a newer workflow version

Eligibility is computed per case with the reason attached, because 'eighteen eligible'
without saying why the other thirteen are not is a number an admin cannot act on. Two
reasons: a completed stage the new version deleted, and a newly required attribute the
case has no value for.

Stage identity across versions is the NAME. A deep copy assigns new primary keys, so
ids cannot match, and the name is what the builder shows and what an admin reasons
about when reordering.

Migrating an ineligible case is refused, not silently skipped. A partially applied bulk
action whose failures are invisible is worse than one that stops.

Orphaned future milestones are SKIPPED rather than removed -- DELETE is denied on
journey tables, and the record that the case once expected that work is worth keeping.

case.migrated is NOT timeline-visible: which internal version a case follows is the
vendor's configuration, not the customer's business."
```

---

## Task 20: Journey HTTP layer

> **Amendment (executed verbatim, 2026-08-22):** two findings running this task.
>
> First, `GET /cases/{id}/approvals` (spec §7.2) had no backing service method --
> `ApprovalService` carried only the two decide methods. Added
> `ApprovalService.listForCase(caseId)`, gated on `CASE_VIEW` (matching
> `CaseService.participants`' own "confirm the case is visible first" shape) and
> reading `Approval` under that same permission -- `ApprovalDescriptor` already
> resolves DEPARTMENT/TEAM/ASSIGNED by walking up to the parent case, so no new
> descriptor was needed.
>
> Second, spec §7.2's row `POST /cases/{id}/milestones/{mid}/complete · /reopen`
> pairs a `complete` action with `milestone.complete` -- but no service method
> completes a milestone directly: milestones only ever become `DONE` through
> `CaseEngine.markDone`, called from `reconcile()` once `mandatorySettled()` is
> true, which is what `RequirementService.satisfy`/`waive` trigger. There is
> deliberately no direct "mark this milestone done" write path in Tasks 14-18 --
> forcing one through without settling requirements is exactly what
> `MILESTONE_FORCE_COMPLETE`'s own approval flow (Task 17) exists to gate instead.
> Built `/milestones/{mid}/reopen` (backed by `MilestoneService.reopen`) and left
> `/milestones/{mid}/complete` out rather than inventing a service method with no
> task behind it; the reference to `milestone.complete` in that endpoint is
> otherwise used correctly on `/requirements/{rid}/satisfy` in the row below it,
> which is very likely what the table's `complete` action actually referred to.

**Files:**
- Create: `CaseController.java`, `MilestoneController.java`, `ApprovalController.java`, `MigrationController.java`, `JourneyExceptionHandler.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/JourneyApiTest.java`

**Interfaces:**
- Produces: the sixteen endpoints of spec §7.2 (all but the timeline, which is Task 21), and the generated types Tasks 26–27 import.

- [ ] **Step 1: Write the failing API test**

```java
class JourneyApiTest extends SecurityTestBase {

    @Test
    void aCaseCanBeOpenedAndReadBack() throws Exception { /* 201, then GET 200 */ }

    /** The switcher's payload: several cases for one customer, newest first. */
    @Test
    void casesForACustomerAreListedNewestFirst() throws Exception { /* … */ }

    /** Roadmap is its own call so the header renders before the stage graph arrives. */
    @Test
    void theRoadmapIsASeparateEndpoint() throws Exception { /* … */ }

    @Test
    void anotherTenantsCaseIdAnswers404() throws Exception { /* never 403 */ }

    @Test
    void anAttributeValidationFailureAnswers422WithEveryProblem() throws Exception { /* … */ }

    @Test
    void advancingAStageThatIsNotExitableAnswers409() throws Exception { /* … */ }

    @Test
    void aWriteScopeRefusalAnswers403() throws Exception {
        // 403, not 404: the caller may see this record, so hiding it would be a lie the
        // UI cannot recover from -- they are looking at the milestone on screen.
    }

    @Test
    void participantRemovalUsesPostRemoveRatherThanDelete() throws Exception { /* 204 */ }

    /** Two decide paths, each with its own gate. */
    @Test
    void theTwoApprovalDecideEndpointsAreNotInterchangeable() throws Exception { /* 409 on mismatch */ }
}
```

- [ ] **Step 2: Implement the controllers and the advice**

Thin methods, one per service call, following `CustomerController`. The status mapping that matters:

```java
@RestControllerAdvice
class JourneyExceptionHandler {

    // 404, never 403, for anything AuthorizedQuery could not resolve: an out-of-scope
    // record must be indistinguishable from one that does not exist.
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND) ApiError onMissing(NoSuchElementException e) { … }

    // 422 with the list: the request was understood and semantically rejected, and the
    // create-case dialog renders each problem against its field.
    @ExceptionHandler(AttributeValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) ProblemList onAttributes(AttributeValidationException e) { … }

    // 409: the caller asked for something the case's current state does not allow. All of
    // these are retryable once the state changes, which is what distinguishes them from 422.
    @ExceptionHandler({StageNotExitableException.class, CaseOnHoldException.class,
                       CaseNotOnHoldException.class, ApprovalAlreadyDecidedException.class,
                       ApprovalKindMismatchException.class, CaseNotMigratableException.class,
                       TemplateNotPublishedException.class})
    @ResponseStatus(HttpStatus.CONFLICT) ApiError onConflict(RuntimeException e) { … }

    // 403, deliberately not 404. The caller can see the record -- it is on their screen --
    // so a 404 would be a lie the UI cannot explain. This is the one place in the codebase
    // where a refusal is visible rather than hidden, and it is safe precisely because
    // write_scope only ever subtracts from access the actor demonstrably already has.
    @ExceptionHandler({WriteScopeException.class, SelfApprovalException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN) ApiError onRefused(RuntimeException e) { … }
}
```

That 403 is worth a second look during review: spec §6.8 says out-of-scope records return 404, and this is not a contradiction — the caller passed `case.view`, holds the record, and is being told a stage rule forbids this specific write. Hiding it as a 404 would make the milestone they are looking at appear to vanish.

- [ ] **Step 3: Verify the guards, regenerate types, commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*JourneyApiTest' --tests '*DirectApiAccessTest' --tests '*OpenApiDocumentTest'
./gradlew openApiSpec && cd ../frontend && npm run generate:api
```

```bash
git add backend/src/main/java/co/ara/onboarding/journey backend/src/test/java frontend/src/lib/api/generated.ts
git commit -m "feat: expose the case runtime over HTTP

Sixteen endpoints. 404 for anything AuthorizedQuery cannot resolve, 422 with the full
problem list for attribute validation, 409 for state conflicts -- all retryable once the
case changes, which is what separates them from 422.

One deliberate 403: a write_scope refusal and a self-approval refusal. Spec §6.8 says
out-of-scope records answer 404, and this does not contradict it -- the caller passed
case.view and is looking at the milestone on screen, so a 404 would make it appear to
vanish. It is safe because write_scope only ever subtracts from access the actor
demonstrably already has.

Participant removal is POST /{userId}/remove, following customers/{id}/deactivate:
DELETE is revoked on every journey table.

The two approval decide endpoints stay separate and refuse each other's kinds, because
@RequirePermission is static and one path cannot carry both an ORG-scoped and an
ALL-only gate."
```

---

## Task 21: The audit read path

> **Amendment (executed verbatim, 2026-08-22):** the plan-amendment-within-a-task
> ("journey events now record resource_type 'onboarding_case'...") turned out to
> already be true for every CASE-level event (`case.created`, `case.held`,
> `case.stage_entered`, `case.completed`, `case.stage_exit_approved/rejected`,
> `case.migrated`, ...) -- Tasks 15-19 already recorded those against
> `onboarding_case`. Only the MILESTONE/REQUIREMENT-level events needed the
> amendment: `milestone.completed` (CaseEngine.markDone), `milestone.skipped`
> (CaseEngine.skipMilestonesOf and, separately, MigrationService.migrateOne's own
> orphaning branch), `milestone.force_completed`/`milestone.force_rejected`
> (ApprovalService.decideForceComplete), `milestone.reassigned`
> (MilestoneService.update), `milestone.reopened` (MilestoneService.reopen), and
> `requirement.satisfied`/`requirement.waived` (RequirementService). Each now
> records against `onboarding_case`/the case id, carrying `milestoneId` (and
> `requirementId`, for the two requirement actions) in the payload instead. No
> test asserted the old (resource_type, resource_id) pair for any of these --
> confirmed before changing them -- so nothing needed updating on that side.
>
> `MigrationService.key()`'s stage/milestone composite key was also changed from
> `stageName + " " + milestoneName` to `stageName + "::" + milestoneName`, an
> unrelated latent bug caught while re-reading that file for the amendment above:
> a space-joined key cannot distinguish `("Stage", "One Two")` from
> `("Stage One", "Two")`, silently misrouting a migration if a stage or milestone
> name ever contained a space next to the boundary. No test exercised that
> specific collision, so nothing was failing; fixed as found rather than left for
> whoever hits it.



`AUDIT_VIEW` is catalogued, seeded to four templates, and has **no endpoint**: the `audit` module holds five classes, none of them a controller. This task builds the first audit read path in the codebase, and it must not reuse `AUDIT_VIEW`.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/audit/AuditQuery.java`, `AuditEventView.java`
- Create: `backend/src/main/java/co/ara/onboarding/journey/TimelineService.java`
- Modify: `CaseController.java` (the timeline endpoint)
- Modify: `AuthorizationCoverageTest.java` (the second per-method exclusion)
- Test: `backend/src/test/java/co/ara/onboarding/journey/TimelineTest.java`

**Interfaces:**
- Produces: `AuditQuery.findForResource(String resourceType, UUID resourceId, Pageable) -> Page<AuditEventView>`; `TimelineService.forCase(UUID caseId, Pageable) -> Page<AuditEventView>`; `GET /cases/{id}/timeline`.

- [ ] **Step 1: Write the failing tests, including the reason `AUDIT_VIEW` is wrong here**

```java
class TimelineTest extends PostgresTestBase {

    /**
     * The timeline shows the case's whole history regardless of who acted. This is the
     * test that documents why AUDIT_VIEW could not be reused: AuditEventDescriptor scopes
     * events by ACTOR, so a TEAM-scoped holder would see only their own teammates'
     * events -- Legal's approval, Finance's verification and every SYSTEM transition
     * would silently vanish from a case they own.
     */
    @Test
    void theTimelineIncludesEventsByOtherTeamsAndBySystem() {
        UUID tenant = fixture.createTenant("tl-actors");
        fixture.runAs(tenant, () -> {
            UUID caseId = aCaseWithEventsFromThreeDifferentTeamsAndOneSystemEvent();
            grantRole(tenant, pm, Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
        });

        fixture.runAsUser(tenant, pm, () -> {
            var events = timeline.forCase(caseId, Pageable.ofSize(50));
            assertThat(events).extracting(AuditEventView::action)
                    .contains("case.created", "milestone.completed", "case.stage_entered");
            assertThat(events).anySatisfy(e -> assertThat(e.actorUserId()).isNull());  // SYSTEM
        });
    }

    /** Authorization is the parent resolution: no case, no timeline. */
    @Test
    void aCaseTheActorCannotSeeHasNoTimeline() {
        assertThatThrownBy(() -> fixture.runAsUser(tenant, outsider,
                () -> timeline.forCase(caseId, Pageable.ofSize(10))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The carve-out must not leak: exactly one case's events, never a neighbour's. */
    @Test
    void theTimelineReturnsOnlyThisCasesEvents() { /* two cases, one customer */ }

    @Test
    void anotherTenantsCaseIdHasNoTimeline() { /* RLS plus the parent resolution */ }

    @Test
    void eventsAreNewestFirstWithMonoReadyTimestamps() { /* occurred_at DESC */ }

    /**
     * timeline_visible is NOT the internal filter. Internally the timeline is the whole
     * history; the flag is what sub-project 7's portal will filter on, and asserting that
     * here stops someone "fixing" the internal query later.
     */
    @Test
    void theInternalTimelineIncludesComplianceOnlyEvents() {
        assertThat(events).extracting(AuditEventView::action).contains("case.migrated");
    }
}
```

- [ ] **Step 2: Implement**

```java
// audit/AuditQuery.java
/**
 * A narrow read over audit_event by exact resource. It takes strings and a UUID and knows
 * nothing about cases, milestones or any other domain type, which is what keeps audit
 * free of a dependency on the modules that write to it.
 */
@Component
public class AuditQuery {

    /**
     * DOES NOT go through AuthorizedQuery, and that is a documented carve-out from the
     * read invariant -- the only one in the codebase.
     *
     * Justification, which must survive review: the caller resolves the PARENT resource
     * through AuthorizedQuery first and that resolution IS the authorization, the filter
     * here is an exact (resource_type, resource_id) match rather than a scope-shaped
     * query, and RLS still constrains every row to the bound tenant.
     *
     * The rejected alternative was a generic /audit/timeline endpoint keeping every read
     * inside AuthorizedQuery, which needs a resourceType -> permission-key map whose
     * failure mode is a missing entry defaulting to something.
     *
     * Do not widen this method. A caller that needs events for a SET of resources needs a
     * different mechanism, not another parameter here.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventView> findForResource(String resourceType, UUID resourceId, Pageable pageable) {
        return events.findByResourceTypeAndResourceIdOrderByOccurredAtDesc(
                resourceType, resourceId, pageable).map(AuditEventView::of);
    }
}
```

```java
// journey/TimelineService.java
@RequirePermission(PermissionKeys.CASE_VIEW)
@Transactional(readOnly = true)
public Page<AuditEventView> forCase(UUID caseId, Pageable pageable) {
    // This line is the authorization for everything below it. Out of scope -> 404, and no
    // events are read at all.
    Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);
    // The whole history, not filtered by timeline_visible: internally a reviewer needs
    // every event. timeline_visible is what sub-project 7's portal filters on.
    return audit.findForResource("onboarding_case", c.getId(), pageable);
}
```

The timeline as drawn is per **case**, and events recorded against `milestone` or `requirement` carry their own `resource_id`. So `forCase` must union the case's own events with those of its milestones and requirements: pass the case id plus its children's ids to a second `AuditQuery` method taking a `Collection<UUID>` for one resource type, or record every journey event against `onboarding_case` with the specific id in the payload. **Choose the latter**: one resource id per case keeps the index single-valued, keeps `AuditQuery` un-widened, and matches how the prototype reads ("Entered stage Technical Setup", not "milestone 4f2a… entered"). Amend the audit calls in Tasks 15–19 to use `"onboarding_case"` with the case id as `resource_id`, carrying `milestoneId`/`requirementId` in the payload — and note the amendment in this task's commit, since it changes code written earlier in the plan.

- [ ] **Step 3: Add the second per-method exclusion, run, commit**

```bash
cd backend && ./gradlew cleanTest test --tests '*TimelineTest' --tests '*AuthorizationCoverageTest'
./gradlew openApiSpec && cd ../frontend && npm run generate:api
```

```bash
git add backend/src/main/java/co/ara/onboarding backend/src/test/java frontend/src/lib/api/generated.ts
git commit -m "feat: read a case's timeline, and say plainly what it bypasses

AUDIT_VIEW is catalogued and seeded to four templates and has no endpoint at all, so
this is the first audit read path in the codebase. It deliberately does not reuse that
permission: AuditEventDescriptor scopes events by ACTOR, so a Project Manager holding
AUDIT_VIEW at TEAM would see only their own teammates' events -- Legal's approval,
Finance's verification and every SYSTEM event would silently vanish from the history of
a case they own. There is a test asserting all of those appear.

The timeline is gated on case.view instead, and the parent resolution IS the
authorization: resolve the case through AuthorizedQuery, then read by exact
(resource_type, resource_id) on the index V5 already created.

This is a documented carve-out from 'every read goes through AuthorizedQuery' -- the
only one in the codebase -- with a commented per-method exclusion in the finder rule
rather than a silent bypass. The rejected alternative, a generic /audit/timeline with a
resourceType -> permission map, has a missing-entry failure mode that defaults to
something.

PLAN AMENDMENT: journey events now record resource_type 'onboarding_case' with the case
id, carrying milestoneId/requirementId in the payload, rather than recording against
the child. Tasks 15-19 were written the other way; a per-child resource_id would have
forced AuditQuery to accept a collection on its first day. It also matches how the
prototype reads: 'Entered stage Technical Setup', not a bare uuid.

Internally the timeline is the whole history. timeline_visible stays what sub-project
7's portal filters on, and a test asserts a compliance-only event is present here so
nobody 'fixes' the internal query later."
```

---

## Task 22: Security negatives

> **Amendment (executed verbatim, 2026-08-22):** three findings running this task.
>
> First, every new test that expected a 200 from `GET`/`POST .../advance` and
> granted only the permission under test (`case.advance`, `case.view` at
> `TEAM`/`ASSIGNED`) 404'd instead: `CaseView`'s `toView` reads the case's pinned
> `Stage` under `WORKFLOW_VIEW`, not the permission the endpoint itself is gated
> on. Same invariant `CaseEditTest`/`MilestoneEditTest` already document, hit
> four more times here (`ChangedPermissionsTest` x2, `InsufficientScopeTest`,
> `MultipleRolesTest`) -- every narrow role built for these tests now also grants
> `WORKFLOW_VIEW` at `ALL`.
>
> Second, `.../milestones/{mid}/complete` is not in `CaseIsolationTest`'s probe
> list -- Task 20 found no service method backs it and never built it; probing a
> nonexistent endpoint would 404 for the wrong reason and prove nothing.
>
> Third, `CrossTenantAccessTest.java` was left unmodified. The Files list named it
> alongside the four specs Step 2 actually describes changes for, but Step 2's own
> text has no bullet for it, and `CaseIsolationTest.everyForeignIdAnswers404`
> already covers the identical ground for every journey/workflow id -- the same
> shape `CrossTenantAccessTest.customerOfAnotherTenantIsNotReachableByDirectId`
> covers for customers. Adding a redundant case-flavoured probe to
> `CrossTenantAccessTest` would duplicate `CaseIsolationTest` rather than add
> coverage. `theTableIsInvisibleWithoutABoundTenant` (Step 1's third test) was
> likewise left out: `RlsCoverageTest` already sweeps every tenant-owned table,
> `onboarding_case`/`milestone`/`requirement` included, deny-by-default -- a
> case-specific duplicate of that generic guard was not worth the added test.



The eight specs in `security/` gain the cases this sub-project makes possible. Several were written earlier where they belonged (Task 11's narrow-scope write, Task 16's `write_scope`, Task 17's force-complete); this task covers what is left and is the review gate before any UI work.

**Files:**
- Modify: `security/CrossTenantAccessTest.java`, `InsufficientPermissionTest.java`, `InsufficientScopeTest.java`, `ChangedPermissionsTest.java`, `MultipleRolesTest.java`
- Create: `security/CaseIsolationTest.java`

**Interfaces:** consumes everything; produces no production code.

- [ ] **Step 1: Write the cross-tenant sweep**

```java
class CaseIsolationTest extends SecurityTestBase {

    /**
     * Every id this sub-project accepts from a URL or a body, fed a foreign tenant's
     * value. Derived from a list of the endpoints rather than a hand-picked sample,
     * because a hand-picked sample is how DirectApiAccessTest ended up eleven endpoints
     * short.
     */
    @Test
    void everyForeignIdAnswers404() throws Exception {
        UUID tenantA = fixture.createTenant("iso-a");
        UUID tenantB = fixture.createTenant("iso-b");
        var b = seedFullCase(tenantB);        // case, milestone, requirement, approval, version
        AppUser aAdmin = adminUser(tenantA, "aadmin@example.com");

        record Probe(String description, MockHttpServletRequestBuilder request) {}
        List<Probe> probes = List.of(
            new Probe("case",        get("/api/t/iso-a/cases/" + b.caseId())),
            new Probe("roadmap",     get("/api/t/iso-a/cases/" + b.caseId() + "/roadmap")),
            new Probe("timeline",    get("/api/t/iso-a/cases/" + b.caseId() + "/timeline")),
            new Probe("advance",     post("/api/t/iso-a/cases/" + b.caseId() + "/advance")),
            new Probe("hold",        post("/api/t/iso-a/cases/" + b.caseId() + "/hold").content("{\"reason\":\"x\"}")),
            new Probe("milestone",   put("/api/t/iso-a/cases/" + b.caseId() + "/milestones/" + b.milestoneId()).content("{}")),
            new Probe("complete",    post("/api/t/iso-a/cases/" + b.caseId() + "/milestones/" + b.milestoneId() + "/complete")),
            new Probe("satisfy",     post("/api/t/iso-a/cases/" + b.caseId() + "/requirements/" + b.requirementId() + "/satisfy")),
            new Probe("approval",    post("/api/t/iso-a/cases/" + b.caseId() + "/stage-approvals/" + b.approvalId() + "/decide").content("{\"approve\":true}")),
            new Probe("definition",  get("/api/t/iso-a/workflows/" + b.templateId() + "/versions/" + b.versionId())),
            new Probe("publish",     post("/api/t/iso-a/workflows/" + b.templateId() + "/versions/" + b.versionId() + "/publish")),
            new Probe("migration",   get("/api/t/iso-a/cases/migration?versionId=" + b.versionId())));

        for (Probe probe : probes) {
            mvc.perform(as(probe.request().contentType(MediaType.APPLICATION_JSON), aAdmin))
               .andExpect(status().isNotFound());       // never 200, never 403, never 500
        }
    }

    /**
     * The oracle, one level deeper: creating a case names a customer in the body. A
     * foreign customer id must answer exactly what an invented one answers.
     */
    @Test
    void aForeignCustomerIdAndAnInventedOneAreIndistinguishable() throws Exception {
        int foreign = statusOfCreateCase(tenantA, aAdmin, bCustomerId);
        int invented = statusOfCreateCase(tenantA, aAdmin, Uuid7.generate());
        assertThat(foreign).isEqualTo(invented).isEqualTo(404);
    }

    /** RLS itself, not just the service layer: no tenant filter, no rows. */
    @Test
    void theTableIsInvisibleWithoutABoundTenant() { /* runUnauthenticated -> empty */ }
}
```

- [ ] **Step 2: Extend the four existing specs**

- `InsufficientPermissionTest` — a user with `case.view` alone cannot advance, hold, complete, waive, force, migrate or publish. Seven refusals, each named.
- `InsufficientScopeTest` — `case.view` at `TEAM` cannot read another team's case, and `milestone.complete` at `TEAM` cannot complete inside it.
- `ChangedPermissionsTest` — revoking `case.advance` takes effect on the **next request**, not when the access token expires; and a deactivated user's case reads collapse immediately, since `AuthorizationService` joins `app_user` on `status = 'ACTIVE'`.
- `MultipleRolesTest` — `case.view` at `TEAM` from one role plus `ASSIGNED` from another is the **union**: a case they participate in outside their team is visible, because scopes are sets rather than a hierarchy.

- [ ] **Step 3: Run the whole suite and commit**

```bash
cd backend && ./gradlew cleanTest test
git add backend/src/test/java/co/ara/onboarding/security
git commit -m "test: the negative cases this sub-project made possible

Twelve foreign-id probes across every id sub-project 2 accepts from a URL or a body,
each asserted to answer 404 -- never 200, never 403, never 500. Enumerated from the
endpoint list rather than sampled, because a hand-picked sample is exactly how
DirectApiAccessTest ended up eleven endpoints short.

The oracle test compares statuses rather than asserting one: a foreign customer id and
an invented one must be indistinguishable, which is the property sub-project 1's
ownership foreign keys still lack.

Revocation takes effect on the next request, not at token expiry, and a deactivated
user's case reads collapse immediately -- both because authority is resolved per request
and AuthorizationService joins app_user on status = 'ACTIVE'.

Multiple roles union their scopes: a case someone participates in outside their team is
visible when TEAM and ASSIGNED come from different roles. Scopes are sets, not tiers,
and this is the test that stops someone implementing them as tiers."
```

---

## Task 23: UI primitives

> **Amendment (executed verbatim, 2026-08-22):** the plan's own Step 1 pseudocode
> uses `@testing-library/user-event` (`await user.type(tab, "{ArrowRight}")`) and
> jest-dom matchers (`toBeInTheDocument`, `toBeDisabled`). Neither is wired into
> this project: `user-event` is not a `package.json` dependency, and no vitest
> setup file registers jest-dom's matchers (checked before assuming either was
> just missed by the plan -- `ProgressBar.test.tsx`/`Dialog.test.tsx` already
> avoid both). Every new test uses `fireEvent` for interaction and reads
> DOM/style properties directly for assertions, matching those two files' own
> established convention rather than introducing a project dependency this task
> did not call for.



**Read before starting:** `docs/uispecs/design/04-components/component-specs.md` families **8** (Chips), **9** (Tabs), **10** (Milestone row), **11** (Task row & checkbox), **12** (Workflow stage row & Inspector), **15** (Timeline row), and open `docs/uispecs/Onboarding Platform.html` in a browser.

**Files:**
- Create: `frontend/src/components/ui/Tabs.tsx`, `Chip.tsx`, `Checkbox.tsx`, `Switch.tsx`, `TimelineRow.tsx`
- Test: one `*.test.tsx` beside each

**Interfaces:**
- Produces:
  - `<Tabs items={{id, label, badge?}[]} value onChange>` — `tablist`/`tab`/`tabpanel`, arrow-key movement
  - `<Chip active onClick dot? mono?>` — the case chip and filter chip of family 8
  - `<Checkbox checked onChange label busy>` — a real `<input type="checkbox">`
  - `<Switch checked onChange label>` — `button role="switch" aria-checked`
  - `<TimelineRow timestamp actor summary meta>` — the `92px 22px 1fr` grid of family 15

- [ ] **Step 1: Write the failing tests, accessibility first**

```tsx
describe("Tabs", () => {
  it("exposes tablist semantics and moves with arrow keys", async () => {
    render(<Tabs items={items} value="journey" onChange={onChange} />);
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByRole("tab", { selected: true })).toHaveTextContent("Journey");
    await user.type(screen.getByRole("tab", { selected: true }), "{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("tasks");
  });

  it("renders a count badge without making it part of the accessible name", () => { /* … */ });
});

describe("Checkbox", () => {
  it("is a real input so screen readers and keyboards get it for free", () => {
    render(<Checkbox checked={false} onChange={fn} label="KYC pack received" />);
    expect(screen.getByRole("checkbox", { name: "KYC pack received" })).toBeInTheDocument();
  });

  it("stays disabled while a mutation is in flight", () => {
    // busy is not cosmetic: satisfying a requirement is a server round-trip, and a
    // second click would fire a second mutation against state the first has not
    // returned yet
    render(<Checkbox checked={false} onChange={fn} label="x" busy />);
    expect(screen.getByRole("checkbox")).toBeDisabled();
  });
});

describe("Switch", () => {
  it("is a button with role switch and aria-checked, not a styled div", () => { /* … */ });
});
```

- [ ] **Step 2: Implement against the specs, then run**

Geometry comes from `component-specs.md` — Tabs 13px with a 2px indigo inset bottom border on the active tab and `#75726c` inactive on a 1px `#e6e3dd` rule; case chips with a dot, name and 9.5px mono id, active filled `#191a1e`; checkbox 17px radius 5px, checked green with a white tick and the label struck through in `#9a968f`; switch 34×20 track, radius 20, indigo when on. Use **tokens**, never these literals — the literals are how you recognise the right token.

Every animation honours `prefers-reduced-motion`, which sub-project 1 already wired.

```bash
cd frontend && npx vitest run
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ui
git commit -m "feat: add the five UI primitives sub-project 2 needs

Tabs, Chip, Checkbox, Switch and TimelineRow -- families 8, 9, 11, 12 and 15 of the
component specs. Seven of the seventeen families were already shipped and are reused
untouched, including ProgressBar, which already carries role=progressbar with
aria-valuenow.

Real semantics rather than styled divs: the checkbox is an input, the switch is a button
with role=switch and aria-checked, and the tabs are a tablist with arrow-key movement.
Each of those is a screen-reader and keyboard behaviour that comes free with the right
element and has to be rebuilt badly with the wrong one.

Checkbox takes a busy prop because satisfying a requirement is a server round-trip, not
local state: a second click would fire a second mutation against state the first has not
returned."
```

---

## Task 24: The builder screen

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/admin/workflows/page.tsx`, `[id]/versions/[vid]/page.tsx`
- Create: `frontend/src/components/workflow/StageRow.tsx`, `StageInspector.tsx`, `BranchRuleCard.tsx`, `MilestoneEditor.tsx`
- Create: `frontend/src/lib/api/workflows.ts`
- Test: `frontend/src/components/workflow/*.test.tsx`

**Interfaces:**
- Consumes: `generated.ts` types from Task 8.
- Produces: `useWorkflows`, `useDefinition`, `useSaveDraft`, `usePublish`, `useCreateDraft` hooks; the `DraftState` reducer Task 25 reuses.

- [ ] **Step 1: Write the failing tests — the graph-editing behaviours that break silently**

```tsx
describe("builder draft state", () => {
  it("keeps the selection when a stage moves", async () => {
    // Reorder is the ▲▼ buttons the prototype draws, not drag-and-drop: keyboard-operable
    // and announceable for free, on a screen an admin touches twice a year.
    const { result } = renderHook(() => useDraftState(threeStages));
    act(() => result.current.select("legal"));
    act(() => result.current.moveUp("legal"));
    expect(result.current.selectedKey).toBe("legal");
    expect(result.current.stages.map(s => s.key)).toEqual(["legal", "reg", "live"]);
  });

  it("surfaces an error before Save when a deleted stage is a branch target", () => {
    const { result } = renderHook(() => useDraftState(smbBranchesToGoLive));
    act(() => result.current.removeStage("live"));
    expect(result.current.problems).toContainEqual(
      expect.stringContaining("Registration branches to a stage that no longer exists"));
  });

  it("renumbers ordinals densely after a removal", () => { /* 1,2,3 not 1,3 */ });

  it("marks the draft dirty and blocks navigation while unsaved", () => { /* … */ });
});

describe("StageInspector", () => {
  it("renders the notification template field disabled with an explanation", () => {
    render(<StageInspector stage={stage} onChange={fn} />);
    const field = screen.getByLabelText(/notification template/i);
    expect(field).toBeDisabled();
    expect(screen.getByText(/arrives with notifications/i)).toBeInTheDocument();
  });

  it("offers only forward stages as a branch target", () => {
    // Publish rejects backward targets, so offering one is offering a 422
    render(<BranchRuleCard stageIndex={1} stages={threeStages} onChange={fn} />);
    expect(screen.getByRole("combobox", { name: /target stage/i }))
      .not.toHaveTextContent("Registration");
  });

  it("offers only declared attributes as condition operands", () => { /* closed dropdown, not free text */ });
});

describe("publish", () => {
  it("lists every 422 problem against the stage it names", async () => { /* … */ });
});
```

- [ ] **Step 2: Implement**

The screen holds the whole definition in a client-side reducer and saves it with one `PUT`, matching the atomic whole-draft write. Client-side `problems` mirror the *structural* subset of Task 7's validations so an admin sees a dangling branch target the moment they delete its stage — but the **server remains the authority**: never suppress a 422 because the client thought the graph was fine.

Layout per `uispecs/README.md` §6: `1fr 320px` with the inspector sticky at top 76px; stage rows white, radius 11px, selected showing an indigo border plus a 3px ring; the two mono badges (`APPROVAL`, `AUTO`); the sub-line "department · SLA · write scope"; the inline rule strip reading `IF <condition> → <target stage>` with `else <fallback>` right-aligned in 9.5px mono; three 26px controls (▲ ▼ ✕); the amber publishing panel. The inspector's fields become real inputs and selects at the drawn geometry, with the design system's focus ring.

Below 1280px the inspector unpins; below 1024px it stacks under the stage list.

- [ ] **Step 3: Run, then commit**

```bash
cd frontend && npx vitest run && npx playwright test --grep accessibility
git add frontend/src
git commit -m "feat: build the workflow builder as a real editor

The prototype's inspector fields are 'read-only-styled' because it never saved; they
become real inputs and selects at the same geometry, with the focus ring the design
system defines.

Two things kept rather than improved: reordering stays the ▲▼ buttons the prototype
draws, because they are keyboard-operable and announceable for free and a drag surface
would be a new accessibility burden on a screen an admin touches twice a year; and Save
is explicit, matching the atomic whole-draft PUT, with an unsaved-changes guard so a
half-edited graph is never what publish validates.

Client-side problems mirror only the structural half of publish validation, so deleting
a stage a branch rule targets is visible immediately -- but the server stays the
authority and a 422 is always rendered, never suppressed because the client disagreed.

The branch-target dropdown offers forward stages only, and condition operands are a
closed list of declared attributes and known customer fields. Offering a backward target
is offering a 422.

The notification template field renders disabled with 'arrives with notifications'. The
column is authored and nothing acts on it until sub-project 6, and a field that silently
does nothing is worse than one that says so."
```

**Amendment (2026-08-22) — what running it verbatim, and then driving it in a real browser, found:**

Every scenario below only surfaced by actually clicking through the builder against a live
backend, per CLAUDE.md's "start the dev server and use the feature in a browser" rule — the
unit suite alone (which was green throughout) caught none of them:

- **`WorkflowService.save()` NPE'd on a milestone with no `dependsOnMilestoneKeys`.** The
  field is optional and omitting it is the common case, but both the write loop (~line 340)
  and the validation loop (~line 429) iterated it directly. Fixed with a private `orEmpty()`
  helper on both call sites; the frontend now also always sends `[]` explicitly from
  `MilestoneEditor.addMilestone()` rather than relying on the server to tolerate an absent
  key.
- **`ConditionEditor`'s operator `<select>` showed "equals" as its unselected default, but
  that value was never written into draft state unless the admin touched the dropdown** —
  the single most common path (accept the default) submitted a condition with `operator`
  literally absent, and `branch_rule.operator` is `NOT NULL`. The database rejected the
  insert with a raw `DataIntegrityViolationException`, surfaced to the admin as an opaque
  "The draft could not be saved." Fixed two ways: `StageInspector.addBranchRule()` now seeds
  `{ source: "ATTRIBUTE", operator: "EQ" }` on creation, matching the milestone fix's shape
  (a value only a control *displays* must also be a value the object *holds*); and
  `computeProblems` now flags a branch rule missing a field or target stage before Save, so
  an incomplete rule reads as an actionable banner rather than a database error. `key` and
  `target_stage_id` carry the same `NOT NULL` constraint and are equally reachable by an
  admin who adds a rule and picks nothing — the problem check covers both.
- **`StageRow`'s branch-rule chip rendered the raw target stage UUID**
  (`01a02a38-d249-...`) instead of resolving it to the stage's name, because it read
  `rule.targetStageKey` directly with no lookup against the sibling stages. Fixed by passing
  the full `stages` array down and resolving the name at render time.
- **`Switch` only ever set `aria-label`, never rendered visible text** — caught from a user
  screenshot showing three unlabelled toggles, not from the unit suite, which had asserted
  the accessible name and stopped there. A control with an accessible name but no visible
  text is one a sighted user cannot identify. Fixed via `aria-labelledby` pointing at a real
  `<span>`, with a new test asserting `getByText` finds the label.
- **A published (frozen) version's inspector accepted edits with nowhere to send them.**
  `isPublished` gated the Save/Publish/Add-stage buttons but nothing downstream — every
  field, switch, and the per-stage reorder/delete controls stayed live, so an admin could
  type into a frozen stage's name and see it change with no error and no way to persist or
  discover the change was discarded. Fixed with a native `<fieldset disabled>` wrapping
  `StageInspector`'s body: the HTML disabled-cascade reaches every descendant form control
  (input, select, button) however deeply nested through `MilestoneEditor`, `BranchRuleCard`,
  and `Switch`, with no prop-threading through any of them. `StageRow`'s reorder/delete
  buttons got an explicit `readOnly` prop instead, since selecting a stage to browse its
  frozen configuration must keep working. **jsdom does not implement the fieldset cascade**
  (verified directly: a bare `<fieldset disabled><input /></fieldset>` fails
  `.disabled === true` under vitest/jsdom) — the unit test asserts the fieldset element
  itself carries `disabled`, and the actual cascade onto descendants is verified live in
  Chrome, not by the unit suite.
- **A version's Save round-trip reassigns every stage, milestone, and branch rule a fresh
  id on every single call**, not just the first: `WorkflowService.save()` deletes and
  recreates the whole graph rather than upserting it (a deliberate, already-commented
  decision — see the `merge()` vs `persist()` note ~line 296). This means the "keeps the
  selection across a reset when the selected stage survives" fix from Task 24's own test
  suite can only ever help a no-op reset or a remount; a real Save-then-continue-editing
  round trip always drops the inspector's selection, because the "same" stage never
  actually keeps the same key twice. Safe in isolation (nothing outside a `DRAFT` version's
  own rows can reference these ids yet), but worth knowing before chasing the selection-loss
  symptom again as a frontend bug.

---

## Task 25: Publish and migration screens

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/admin/workflows/[id]/migration/page.tsx`
- Create: `frontend/src/components/workflow/PublishPanel.tsx`, `MigrationTable.tsx`
- Test: beside each

**Interfaces:** consumes `usePublish`, adds `useMigrationPreview`, `useMigrate`.

- [ ] **Step 1: Write the failing tests**

```tsx
describe("PublishPanel", () => {
  it("shows the two counts from the preview", () => {
    // "31 cases on v4 / 18 eligible to migrate", as the prototype draws it
  });
  it("explains freeze-by-default before offering the button", () => { /* … */ });
});

describe("MigrationTable", () => {
  it("shows the computed reason for every ineligible case", () => {
    // 'eighteen eligible' without saying why the other thirteen are not is a number an
    // admin cannot act on
    render(<MigrationTable candidates={mixed} />);
    expect(screen.getByText(/no longer exists in the new version/i)).toBeInTheDocument();
  });

  it("disables selection for ineligible rows", () => { /* … */ });
  it("selects all eligible rows without selecting any ineligible one", () => { /* … */ });
  it("renders the empty state when nothing is eligible", () => { /* a CLAUDE.md gap */ });
});
```

- [ ] **Step 2: Implement and commit**

The migration screen is not in the design system — the prototype gives the amber panel and a "Review migration" button and stops — so build it from existing primitives: `Table` for the candidates, `StatusPill` for eligibility, mono for case ids and stage names, `Button` for the action, `EmptyState` when nothing is eligible.

```bash
cd frontend && npx vitest run
git add frontend/src
git commit -m "feat: publish panel and the migration review screen

The migration screen does not exist in the design system -- the prototype draws the
amber panel and a Review migration button and stops -- so it is composed from existing
primitives rather than new ones: Table, StatusPill, mono ids, EmptyState.

Every ineligible row shows the reason the engine computed. 'Eighteen eligible' without
saying why the other thirteen are not is a number an admin cannot act on, and it is
also the number they will ask about first.

Ineligible rows cannot be selected, and select-all skips them, because the API refuses
an ineligible case rather than silently skipping it -- the UI should not build a request
it knows will fail."
```

**Amendment (2026-08-22) — what running it verbatim found:**

- **`MigrationController.preview`'s `versionId` query param is the TARGET version, not the
  source.** `MigrationService.preview(targetVersionId)` counts cases sitting on an *older*
  version of the same template and evaluates them against the target's stages/attributes.
  The plan's own example — "31 cases on v4 / 18 eligible to migrate" — reads naturally as
  "the current version has 31 stragglers," which only makes sense if `PublishPanel` calls
  `useMigrationPreview` with the *currently viewed published version's own id* as the
  target, not with some other version. Built that way: the panel appears on any published
  version's builder page (not only immediately after clicking Publish), which also means an
  admin can come back later and see newly-eligible cases without republishing anything.
- **No "list versions" or "customer name" lookup exists**, so two simplifications were
  necessary and are worth knowing about rather than rediscovering: the migration page's
  subtitle drops the `v{n}` the prototype's copy included (`CandidateView`/
  `MigrationPreviewView` carry no version number, only an id, and threading `versionNo`
  through as a second search-param felt like more surface than the display was worth); and
  `MigrationTable`'s customer column links to `/customers/{customerId}` by mono id rather
  than showing a name, the same trade-off `CustomerTable`'s own doc comment already made
  for a different missing field ("render the columns that exist rather than inventing ones
  that do not").
- **Live verification covered the wiring, not a populated table.** There is no
  case-creation UI yet (Task 26/27) and fabricating one via raw API calls would have needed
  a hand-extracted bearer token from React's closed-over module state, which was judged
  disproportionate to what it would prove. What *was* verified live: `PublishPanel` renders
  correctly on the real published v1 with a genuine `0 cases on v1 / 0 eligible` from an
  actual `GET .../cases/migration?versionId=...` 200 response (not a mock), the "Review
  migration" link navigates correctly with both route and query params, and the migration
  page's empty state renders with no console or network errors. The eligible/ineligible
  rendering, per-row reason, disabled-selection-on-ineligible, and select-all-skips-
  ineligible behaviours are covered by `MigrationTable.test.tsx` against realistic mock
  `CandidateView` data, not by a live populated screen — worth re-verifying with a real case
  once Task 26/27 makes one creatable.

---

## Task 26: Journey workspace shell

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx`
- Create: `frontend/src/components/journey/CaseHeader.tsx`, `CaseSwitcher.tsx`, `CreateCaseDialog.tsx`
- Create: `frontend/src/lib/api/cases.ts`
- Modify: `frontend/src/app/(app)/t/[slug]/customers/[id]/page.tsx` (link into the workspace)
- Test: beside each

**Interfaces:** produces `useCases`, `useCase`, `useRoadmap`, `useCreateCase`, `useAdvance`, `useHold`, `useResume`.

- [ ] **Step 1: Write the failing tests**

```tsx
describe("CaseHeader", () => {
  it("composes case facts with the customer name from the existing customer query", () => {
    // The case API returns customerId and no display data -- the port carries none --
    // so the header joins them client-side. This test is what documents that.
  });

  it("renders machine values in mono and human text in Archivo", () => {
    // case id, dates, counts and the percentage are mono; the company name is not
  });

  it("shows the frozen version as 'workflow v4 (frozen)'", () => { /* mono meta line */ });

  it("reflows the five fact columns to two rows below 1280px", () => { /* review finding 11 */ });
});

describe("CaseSwitcher", () => {
  it("renders one chip per case plus a dashed new-case chip", () => { /* … */ });
  it("navigates rather than filtering in place, so the URL carries the case", () => { /* … */ });
  it("hides the new-case chip without case.create", () => { /* … */ });
});

describe("CreateCaseDialog", () => {
  it("renders a field per declared attribute with its label and allowed values", () => { /* … */ });
  it("marks required attributes required", () => { /* … */ });
  it("renders each 422 problem against its own field", () => { /* … */ });
  it("offers only templates that have a published version", () => { /* … */ });
});

describe("workspace", () => {
  it("renders the tab from the query param so a reload lands where the reader was", () => { /* … */ });
  it("shows the empty state and a create action when the customer has no cases", () => { /* … */ });
});
```

- [ ] **Step 2: Implement and commit**

Header per `uispecs` §5: 46px rounded-square avatar, company name 19px/600 with a health pill, an 11px mono meta line carrying the case id and `workflow v4 (frozen)`, five fact columns with 9.5px mono uppercase labels over 12.5px/500 values, and the right-aligned 34px/600 percentage with a 150px 7px bar. Case switcher below the meta line: a `CASES` label, one chip per case, and the dashed `＋ New case` chip.

```bash
cd frontend && npx vitest run
git add frontend/src
git commit -m "feat: the journey workspace shell

The case is the unit of work, so it is in the URL:
/customers/{customerId}/cases/{caseId}?tab=journey. The switcher navigates rather than
filtering in place, and the tab is a query param so reload, back and a shared link all
land where the reader was.

The header composes the case response with the existing useCustomer query, because
CustomerFacts carries no display data by design. One extra client call is the price of
journey never importing customer, and it is the right price.

The create-case dialog renders a field per declared attribute with its allowed values,
and puts each 422 problem against its own field. It offers only templates with a
published version, because the API refuses the rest.

Mono for machine values -- case id, dates, counts, the percentage -- and Archivo for
anything a person wrote. Five fact columns reflow to two rows below 1280px, which is
review finding 11's other casualty."
```

**Amendment (2026-08-22) — what running it verbatim found:**

- **The five fact columns are not Stage/Account manager/Primary contact/Teams/Est.
  completion as `uispecs` draws them.** Three of those need a name resolved from an id
  `CaseView` doesn't carry (`ownerUserId` → a person, `owningTeamId` → a team) and Task 26's
  own Interfaces line names no user- or team-lookup hook to get one. Rather than invent an
  unplanned call or show a raw UUID where the design draws a name, the five columns became
  Stage, Started, Est. completion, Hold days, Status -- every one a field `CaseView` already
  returns, the same trade-off `CustomerTable`'s own doc comment made when the design called
  for a health/progress column the customer API cannot back.
- **"Shows the empty state and a create action when the customer has no cases" lives on the
  customer detail page, not the workspace route.** The workspace URL is
  `/customers/{id}/cases/{caseId}` -- it requires a case id to exist at all, so there is no
  way to land on it with zero cases to be empty about. The empty state belongs to the
  customer page's own new "Cases" card, which is also where Task 26's other file-list entry
  ("Modify: customers/[id]/page.tsx, link into the workspace") already pointed.
  `CaseSwitcher` is reused there with `activeCaseId=""` (nothing highlighted) rather than
  building a second, near-identical case list component.
- **Tasks/Documents/Agreements/Timeline render as inline `EmptyState` placeholders, not
  their own components.** Task 27's own commit message says it is the one that "renders
  \[them] as empty states rather than being hidden," and Task 28 explicitly creates
  `TimelineTab.tsx` as its own file. Building full versions of either now would be
  redoing work those tasks already plan to do; the placeholders here exist only so the tab
  strip has something honest under each tab today, and get replaced rather than extended.
- **Found live, fixed, unrelated to this task:** `CustomerTable`'s `<col>` elements were
  keyed on their own width string (`COLUMN_WIDTHS`), and two columns share an identical
  width (both 1fr of the design's fr-share, converted to the same percentage) --
  `Encountered two children with the same key` in the console the first time a real
  customer list ever rendered in this session. Harmless in practice (`<col>` has no state
  to lose), but a real collision; keyed on array index instead.
- **Verified live end-to-end, closing the gap Task 25's own amendment flagged:** created a
  real customer and case against the running backend, confirmed the "no cases" empty state
  and the populated switcher, the workspace header/tabs/reload-preserves-tab behaviour, and
  the journey preview's stage list -- then published a second workflow version and drove
  Task 25's migration screen against real (not mocked) data for the first time: one case
  showed as on-an-older-version and eligible, migrating it moved its meta line from
  "workflow v1" to "workflow v2" with a clean network/console trail throughout.

---

## Task 27: The journey tab

**Files:**
- Create: `frontend/src/components/journey/MilestoneRow.tsx`, `RequirementList.tsx`, `ApprovalPanel.tsx`, `ForceCompleteDialog.tsx`, `HoldDialog.tsx`, `StageGroupHeader.tsx`
- Test: beside each

**Interfaces:** consumes `useRoadmap`, adds `useSatisfy`, `useWaive`, `useForceComplete`, `useDecideApproval`, `useReopen`.

- [ ] **Step 1: Write the failing tests, starting with the rendering rule that would break silently**

```tsx
describe("roadmap rendering", () => {
  /**
   * The decision from design: milestone rows, with the stage header suppressed when a
   * stage holds one milestone of the same name. A 1:1 workflow must render byte-identically
   * to the prototype's nine rows, and this is the only test that would catch a regression
   * to nine headers over nine rows.
   */
  it("suppresses the stage header for a single same-named milestone", () => {
    render(<Roadmap stages={nineOneToOneStages} />);
    expect(screen.queryByRole("heading", { name: "Registration" })).not.toBeInTheDocument();
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(9);
  });

  it("shows the stage header when a stage fans out", () => {
    render(<Roadmap stages={registrationWithTwoMilestones} />);
    expect(screen.getByRole("heading", { name: "Registration" })).toBeInTheDocument();
  });

  it("shows the header when one milestone has a different name from its stage", () => { /* … */ });
});

describe("MilestoneRow", () => {
  /** Review finding 10: colour is never the only signal. */
  it("pairs every status colour with a word", () => {
    render(<MilestoneRow milestone={blocked} />);
    expect(screen.getByText(/blocked/i)).toBeInTheDocument();
    expect(screen.getByText(/blocked by/i)).toBeInTheDocument();
  });

  it("renders due dates in mono and marks overdue ones with more than colour", () => { /* … */ });
  it("rotates the chevron and animates the panel only when motion is allowed", () => { /* … */ });
  it("exposes the progress bar with an accessible value", () => { /* ProgressBar already does */ });
});

describe("RequirementList", () => {
  /**
   * The one deliberate departure from prototype behaviour. §5a says checkbox state is
   * "real and local"; here it cannot be -- satisfying a requirement recomputes the
   * milestone, possibly the stage transition and the case percentage, all server-side.
   */
  it("waits for the server rather than flipping locally", async () => {
    render(<RequirementList requirements={[open]} />);
    await user.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("checkbox")).toBeDisabled();       // in flight
    expect(screen.getByRole("checkbox")).not.toBeChecked();     // not yet
    await waitFor(() => expect(screen.getByRole("checkbox")).toBeChecked());
  });

  it("renders a write-scope 403 as an explanation, not a disappearance", async () => {
    // The 403 exists precisely so the UI can say "this stage is owner-only" instead of
    // the milestone vanishing
  });

  it("renders DOCUMENT requirements as the design's document chips", () => { /* … */ });
  it("hides waive without requirement.waive", () => { /* … */ });
});

describe("dialogs", () => {
  it("requires a reason to force-complete", () => { /* required textarea; NOT NULL in schema */ });
  it("requires a reason to hold", () => { /* … */ });
  it("tells the requester they cannot approve their own request", async () => { /* the 403 */ });
});

describe("empty tabs", () => {
  it("renders Tasks, Documents and Agreements as empty states with no affordance", () => {
    // Not hidden: a missing tab reads as a missing feature. Not "arrives in sub-project
    // 3" either -- that is a sentence about our plan, not about their onboarding.
  });
});
```

- [ ] **Step 2: Implement and commit**

Milestone row per `uispecs` §5a: white card, radius 13px, `#e6e3dd` border, opening to `#dbd7cf` plus the one elevation the design permits; grid `26px 1fr auto`; the 26px status circle (green ✓ done, indigo active, red ! blocked, `#d6d1c9` pending); title 13.5px/600 with a status pill and, when blocked, "blocked by X" in 10px mono red; right side due date over owner, a 74px 6px bar, and the chevron rotating 180° in 0.18s. Expanded panel: 1px `#efece7` top border, padding `16px 18px 18px 58px`, grid `1.4fr 1fr`, `fadeUp` 0.16s — requirements on the left, dependencies/comments column on the right with the dependency prose. Comments are sub-project 3; render the section header with an empty state rather than the dashed affordance, which would promise a feature that does not exist.

```bash
cd frontend && npx vitest run
git add frontend/src
git commit -m "feat: the journey tab

Milestone rows are the expandable unit and the stage header is suppressed when a stage
holds one milestone of the same name, so a 1:1 workflow renders exactly the nine rows
the prototype draws. There is a test for the suppression, because a regression to nine
headers over nine rows is invisible to every other test.

Requirement checkboxes wait for the server. §5a says the prototype's checkbox state is
'real and local', and here it cannot be: satisfying a requirement recomputes the
milestone, possibly the stage transition and the case percentage, inside one locked
transaction. Local optimism would show a milestone completing that write_scope then
refuses.

A write_scope 403 renders as an explanation. That status exists so the UI can say 'this
stage is owner-only' rather than the milestone the user is looking at appearing to
vanish, which is what a 404 would have meant here.

Every status colour is paired with a word, and a blocked row keeps 'blocked by X' in
text -- review finding 10, on the screen where four colours and three glyphs carry the
most meaning.

Tasks, Documents and Agreements render as empty states rather than being hidden: a
missing tab reads as a missing feature. The copy does not mention sub-project numbers,
which are a fact about our plan and not about the customer's onboarding."
```

---

## Task 28: Timeline, responsive and accessibility

**Files:**
- Create: `frontend/src/components/journey/TimelineTab.tsx`
- Modify: the workspace and builder pages (breakpoints)
- Test: `TimelineTab.test.tsx`; extend `frontend/e2e/accessibility.spec.ts`

- [ ] **Step 1: Write the failing tests**

```tsx
describe("TimelineTab", () => {
  it("renders the immutable list with mono timestamps and an event count", () => { /* §5e */ });
  it("says 'Immutable' in the header, because the audit trail is the point", () => { /* … */ });
  it("paginates rather than truncating silently", () => { /* a silent cap reads as complete */ });
  it("renders the empty state for a case with no events yet", () => { /* … */ });
});
```

```ts
// e2e/accessibility.spec.ts — extend the existing sweep
for (const width of [1440, 1280, 1024, 768]) {
  test(`journey workspace has no axe violations at ${width}px in both themes`, async ({ page }) => {
    // Note in the spec's comment what axe does NOT cover: its default rules evaluate
    // color-contrast for text and have no non-text rule, which is why Task 2's
    // report_shipped('light') exists. A clean sweep here is not a contrast guarantee.
  });
  test(`workflow builder has no axe violations at ${width}px in both themes`, async ({ page }) => { /* … */ });
}
```

- [ ] **Step 2: Implement the breakpoints and commit**

At ≤1280px: the header's five fact columns become two rows; the builder's inspector unpins. At ≤1024px: the inspector stacks below the stage list; case chips scroll horizontally rather than wrapping to three lines; the roadmap's right-hand column moves under the left inside the expanded panel.

```bash
cd frontend && npx vitest run && npx playwright test --grep accessibility
git add frontend/src frontend/e2e
git commit -m "feat: the timeline tab, and the layouts below 1440px

The timeline is the immutable list of §5e -- mono timestamps, a dot on a rule, the event
count in the header -- and it paginates rather than truncating, because a silent cap
reads as a complete history.

Two breakpoints, extending the fallback sub-project 1 already ships: at 1280px the five
fact columns reflow and the builder inspector unpins; at 1024px the inspector stacks,
the case chips scroll horizontally, and the expanded panel's two columns become one.
Review finding 11 named the journey workspace as one of its two casualties.

The axe sweep now covers both new screens in both themes at four widths, with a comment
recording what it does not cover: axe's default rules evaluate color-contrast for text
and have no non-text rule, so a clean run says nothing about borders. That is Task 2's
report_shipped('light'), and the two are not substitutes."
```

---

## Task 29: Playwright and `CLAUDE.md`

**Files:**
- Create: `frontend/e2e/workflow-authoring.spec.ts`, `case-lifecycle.spec.ts`, `migration.spec.ts`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Write the three specs**

Each provisions its own tenant (the harness does this per spec file and never truncates, so point it at a scratch database) and reads activation tokens from `frontend/e2e/.artifacts/backend.log`, which is the only place they exist.

1. **`workflow-authoring`** — sign in as the tenant admin, create a template, add three stages with milestones and requirements, declare a `segment` attribute, add a branch rule, attempt to publish with an empty stage and assert the 422 list renders against the offending stage, fix it, publish, and assert the version reads `v1 (frozen)`.
2. **`case-lifecycle`** — open a case with `segment=ENTERPRISE`, satisfy the first stage's requirements and watch the roadmap advance, open a case with `segment=SMB` and assert the branch **skips** Legal Review and marks it skipped, request a force-complete as a PM, assert the PM cannot approve it, approve as the administrator, and drive the case to 100% and `COMPLETED`.
3. **`migration`** — publish v2 adding a stage, open the migration review screen, assert one eligible and one ineligible case with its reason, migrate the eligible one, and assert its roadmap now shows four stages while the ineligible one still reads `v1`.

- [ ] **Step 2: Run the whole suite, all three of them**

```bash
cd backend && ./gradlew cleanTest test
cd ../frontend && npx vitest run && npx playwright test
python ../docs/uispecs/design/scripts/contrast.py
```
Record the outcome. `CLAUDE.md` deliberately pins no test counts — "a number in this file that drifts is a number that gets trusted" — so report the suites' own summary lines and treat a *failure* as the signal.

- [ ] **Step 3: Update `CLAUDE.md`**

Additions, keeping it dense — "add a line only when its absence would cost a future session real time":

- **Project shape** — the two new modules and what each owns; `journey → workflow`, and `customer → journey` through the port.
- **Non-negotiable invariants** — the ten of spec §10, each in one line.
- **Where the guards live** — the two named module rules, the `*Engine` and `*Directory` widenings, the two per-method finder exclusions and why each exists, and the derived `DirectApiAccessTest`.
- **Open at the close of sub-project 2** — whatever is actually still open, verified against the running system, not copied from this plan's intentions.
- **Closed since sub-project 1** — TEAM scope, the delegation guard, the light-theme tokens. Delete those bullets from the open list; a stale open item costs a future session the time it takes to re-verify.
- **What sub-project 3 inherits** — the requirement seam (`kind`, `satisfied_ref`, `satisfied_ref_type`), `reconcile` and the rule that any mutation calls it under the lock, the `write_scope` guard, and the question "what does this new entity's deactivation revoke?" which sub-project 1 made a required design question.
- **Running it locally** — how to seed a workflow and open a case, since nothing in the product does it for you.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e CLAUDE.md
git commit -m "test: end-to-end specs for authoring, lifecycle and migration; update CLAUDE.md

Three specs on the existing harness. The lifecycle spec is the one that matters: it
drives a case through a branch that skips a stage, a force-complete the requester cannot
approve, and on to completion at exactly 100% -- the four engine behaviours that no unit
test can prove are wired to the screens.

CLAUDE.md gains the two modules, the ten invariants from the spec's cross-check, the new
guards with the reasoning behind both per-method finder exclusions, and what sub-project
3 inherits. Three bullets leave the open list: TEAM scope, the delegation guard and the
light-theme tokens are closed, and a stale open item costs a future session the time it
takes to re-verify.

No test counts pinned, per the file's own rule: a number that drifts is a number that
gets trusted."
```

---

## Notes for the Executor

**On elided test bodies.** Most test code in this plan is written out. Where a body reads `/* … */`, the test **name plus the comment above it is the specification**, and the setup follows whichever sibling test in the same class is shown in full — every such class has at least one. Write the body from the name; if the name is not enough to know what to assert, that is a defect in this plan, so fix the name and say so in the commit. Do not skip an elided test because it looked optional: the elision is about length, never importance. `WriteScopeTest.allScopeIsStillRefusedInAnOwnerOnlyStage` is written out in full precisely because it is the one people would otherwise write as a happy-path test.

**Read the spec, not just this plan.** `docs/superpowers/specs/2026-08-21-workflow-engine-and-case-lifecycle-design.md` §10 is a cross-check of ten invariants against where each is expressed and what proves it. If a task seems to require breaking one, the task is wrong or the invariant has moved — stop and say which.

**The order is not arbitrary in three places.** Tasks 1–4 come first because they are the guards and the two escalation-shaped gaps that everything after them relies on: adding fifteen permissions before the delegation guard exists means handing three administrative keys to any `user.manage` holder in the interim. Task 12 precedes 13 because dates cannot be computed without a calendar. Task 14 precedes 15–19 because every one of them calls `reconcile`.

**Two per-method exclusions are added to the finder rule, and no more.** `CaseRepository.lockById` (Task 14) and `AuditQuery.findForResource` (Task 21). Both are commented at the exclusion site. If a third seems necessary, that is the signal to re-read the invariant rather than to add it: sub-project 1's experience is that each individual bypass looked reasonable and the pattern only became visible after three of them.

**Expect existing tests to fail in Task 4, and read the failures as findings about the tests.** Anything in `UserAdminTest`, `RoleLifecycleTest` or `MultipleRolesTest` that assigns a role while running as a narrow actor was relying on the missing guard.

**The concurrency test in Task 14 must be seen failing.** If it passes before the lock exists, raise the contention until it does not. A race test that has never failed is a comment.

**Amend the plan when you find a defect in it.** Two are already known and marked: Task 5 deviates from the spec's blanket no-`DELETE` rule for the seven definition tables, and Task 21 amends the audit `resource_type` choice made in Tasks 15–19. Both are recorded in their commit bodies. Sub-project 1's plan carries several such amendments; they are how a finding survives the session that found it.

**Things sub-project 1 learned the hard way, which apply directly here:**

- `./gradlew cleanTest test`, never a bare `test` — Gradle prints `BUILD SUCCESSFUL` having executed nothing.
- Never assert an exception inside a `fixture.runAs(...)` lambda; wrap the helper.
- Fixture create-helpers must run inside `runAs` — the tables are RLS-protected.
- A structural guard's enumeration drifts. Prefer a derived list to a typed one, every time.
- Whenever a permission is catalogued at several scopes, at least one **write** test runs at the narrowest.
- `frontend/src/app/tokens.css` and `tailwind-theme.css` are verbatim copies; regenerate and copy, never edit.
- The e2e harness tees the backend log to `frontend/e2e/.artifacts/backend.log`, which is the only place an activation token exists.

**What this sub-project deliberately leaves undone**, so nobody spends a day rediscovering it: tenant-configurable business calendars and holidays (sub-project 6 needs them for SLA and will build both), rework loops as branch targets (reopening covers the real case), compound AND conditions, `notification_template_key` behaviour, and a generic audit read endpoint. Spec §11 carries the reasoning for each.

