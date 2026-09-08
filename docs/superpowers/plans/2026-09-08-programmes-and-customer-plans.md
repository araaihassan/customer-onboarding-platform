# Programmes & Customer-Scoped Plans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build sub-project 3A — a `programme` grouping a customer's parallel journeys with a derived duration-weighted rollup, workflow templates cloneable and tailorable per customer, a plan approved twice (shape at the customer template version, schedule per journey as a dated snapshot), a journey held until its first schedule approval, and milestone-level portal visibility.

**Architecture:** One new backend module, `co.ara.onboarding.programme`, depending one-way on `journey`; membership is a `programme_case` link table so `journey` never learns programmes exist. There is **no `plan` module**: gate 1 (shape) lives in `workflow` because the thing approved is a workflow version, and gate 2 (schedule) lives in `journey` because its release writes `Case.held_at` and that column's every other write is journey's. Q23's hold is the pre-existing `Case.held_at` / `CaseStatus.ON_HOLD` / `CaseOnHoldException`, not a new blocking mechanism. Descriptors for all six new entity types live in `scoping/`, as the existing thirteen do.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Playwright, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-08-programmes-and-customer-plans-design.md`

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — the current bundle. Do **not** read `docs/uispecs_legacy/` for tokens, copy, layout or component behaviour. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 7, and Tasks 5–7 in Phase 1), per CLAUDE.md. The bundle covers **none** of this sub-project's screens — see "Screens the design system does not cover" below.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is loaded into every session and is the authority for everything sub-projects 1–3 established; this section carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. One new module: `co.ara.onboarding.programme`. Gate 1 code goes in `co.ara.onboarding.workflow`, gate 2 code in `co.ara.onboarding.journey`. All descriptors go in `co.ara.onboarding.scoping`. Nothing else moves.
- **`journey` must never import `programme`, and `customer` must never import `programme`.** Both are enforced by **named** `ModuleBoundaryTest` rules — `noJourneyDependencyOnProgramme` and `noCustomerDependencyOnProgramme` — not by the cycle rule, which a one-way import would still pass. Each must be **seen red** before the module exists (Task 11).
- **Six new tables**, all tenant-owned: `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `SELECT enable_tenant_rls('<table>')` in the same migration, and `GRANT SELECT, INSERT, UPDATE ON <table> TO onboarding_app` — never `DELETE`. **`plan_revision_item` is the one exception and gets `GRANT SELECT, INSERT` only**, with `UPDATE` withheld as well as `DELETE` (Task 22). `RlsCoverageTest` is deny-by-default over the live schema; **its allowlist stays at four entries.**
- **Migration filenames are decided at dispatch time, never fixed here.** `V16` is the highest committed number as this plan is written, but Phase 1 lands first and Task 3 may add one. **Before writing any migration, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>`.** Forward-only — never edit a committed migration, not even temporarily.
- **UUIDv7 keys** via `co.ara.onboarding.platform.Uuid7.generate()`. All timestamps `timestamptz` in UTC; `due_date` is a bare `date`.
- **Every public `*Service` and `*Engine` method carries `@RequirePermission`.**
- **Every read of tenant business data goes through `AuthorizedQuery`**, and so does **every id a write path takes from a URL or a request body**, before it writes. **No new `AuthorizedQuery` exclusion is created in this sub-project.** The codebase has exactly one carve-out (`AuditQuery.findForResource`). If you find yourself wanting a second, the design is wrong, not the rule.
- **`programme..` is added to `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` in the same commit that adds the services**, never afterwards — against the **rebound** rule Task 2 produces, not the name-shaped one.
- **Out-of-scope records return 404, never 403.** `AuthorizedQuery.getById` throws `NoSuchElementException`, which maps to 404.
- **A `PUT` is a full replace,** so its view type must carry every field its request type accepts. Adding a field to an `Update*Request` without adding it to the matching `*View` makes every client silently erase it.
- **Permission keys** are declared in `PermissionKeys`, catalogued in `PermissionCatalog`, and referenced as constants — never as string literals.
- **Audit: record the cause before the calls that record its effects.** `AuditRecorder` stamps `occurred_at` from the clock, so call order is timeline order. `journey.CauseBeforeEffectTest` guards this; Task 25 adds `plan.revision_decided` → `case.resumed`.
- **Progress is derived by `CaseEngine` and is one number for every audience.** `portal_visible` filters *rendering* only, never a numerator or denominator. **`CaseEngine` is not modified anywhere in this plan.** If a task appears to need an engine change, stop and escalate.
- **Programme progress is derived on read, never stored.** No `progress_percent` column on `programme`, and no request type accepts one.
- **Programme participation grants read of the programme alone.** Journey access comes only from a real `CaseParticipant` row. A programme read that returned a journey its viewer could not otherwise open is the scope-widening backdoor three sub-project 1 escalations took — Task 13's `ProgrammeScopeTest` is the guard.
- **TDD.** Failing test first; security tests before the mechanism they verify. A new structural guard must be **seen red** before the code it protects exists.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper instead, or `UnexpectedRollbackException` masks the exception under test.
- **Fixture create-helpers must run inside `runAs`** — the tables they write are RLS-protected.
- **Backend tests need Docker running.** Use `./gradlew cleanTest test`, never a bare `test` — Gradle marks an unchanged test task UP-TO-DATE and prints `BUILD SUCCESSFUL` having executed nothing. On PowerShell use `.\gradlew.bat`.
- **Java has been observed both blocked and working on this machine within single days** (Application Control policy). Run `java -version` at the start of any backend task; do not carry forward either state as durable.
- **Conventional Commits.** Explain *why* in the body, especially when deviating from this plan. When you find a plan defect, fix the code **and** amend the plan, and say so in the commit body.

---

## File structure

**Phase 1 (backlog) modifies existing files only.** Task 3 adds one migration.

**Phases 2–6 (backend feature) create:**

```
backend/src/main/resources/db/migration/
  V<n>__programme.sql              programme, programme_participant, programme_case
  V<n+1>__customer_template.sql    workflow_template.customer_id, cloned_from_template_id
  V<n+2>__milestone_portal_visible.sql
  V<n+3>__plan_approval.sql        plan_shape_approval, plan_revision, plan_revision_item

backend/src/main/java/co/ara/onboarding/programme/
  Programme.java  ProgrammeRepository.java  ProgrammeStatus.java
  ProgrammeParticipant.java  ProgrammeParticipantRepository.java  ProgrammeParticipantStatus.java
  ProgrammeCase.java  ProgrammeCaseRepository.java
  ProgrammeService.java            create, read, update, deactivate
  ProgrammeMembershipService.java  add/remove journeys, add/remove participants
  ProgrammeRollup.java             derived, duration-weighted, over visible cases only
  ProgrammeController.java
  Programme*Request/View records
  ProgrammeExceptionHandler.java   @RestControllerAdvice, in THIS module

backend/src/main/java/co/ara/onboarding/journey/
  CaseWeight.java                  record(UUID caseId, int progressPercent, int weightDays)
  CaseWeightReader.java            exposes the rollup inputs; gated case.view
  PlanRevision.java  PlanRevisionRepository.java  PlanRevisionStatus.java
  PlanRevisionItem.java  PlanRevisionItemRepository.java
  PlanRevisionService.java         issue, decide, diff, hold release
  PlanRevisionController.java
  Plan*Request/View records

backend/src/main/java/co/ara/onboarding/workflow/
  PlanShapeApproval.java  PlanShapeApprovalRepository.java  PlanShapeApprovalStatus.java
  PlanShapeService.java            submit, decide, the portal-visible rendering
  CustomerTemplateService.java     clone, refresh-from-source
  PlanShapeController.java (endpoints added to WorkflowController where they fit)

backend/src/main/java/co/ara/onboarding/scoping/
  ProgrammeDescriptor.java  ProgrammeParticipantDescriptor.java  ProgrammeCaseDescriptor.java
  PlanRevisionDescriptor.java  PlanRevisionItemDescriptor.java  PlanShapeApprovalDescriptor.java
```

**Phase 7 (frontend) creates:**

```
frontend/src/lib/api/programmes.ts    useProgrammes, useProgramme, useProgrammeMutations
frontend/src/lib/api/plans.ts         useShapeApproval, usePlanRevisions, usePlanMutations
frontend/src/components/programme/ProgrammeDetail.tsx  ProgrammeJourneyList.tsx
frontend/src/components/programme/ProgrammeRollupBar.tsx  ProgrammeParticipants.tsx
frontend/src/components/workflow/CloneTemplateDialog.tsx  RefreshFromSourceDialog.tsx
frontend/src/components/workflow/ShapeApprovalPanel.tsx
frontend/src/components/journey/PlanTab.tsx  PlanRevisionList.tsx  PlanRevisionDiff.tsx
frontend/src/components/journey/AwaitingApprovalBanner.tsx
frontend/src/app/(app)/t/[slug]/programmes/page.tsx
frontend/src/app/(app)/t/[slug]/programmes/[id]/page.tsx
```

---

## Screens the design system does not cover

All four areas in Phase 7 are designed **in-repo**, extending the bundle's tokens and components. The bundle's 19 screens contain no programme screen, no customer-template screen and no plan-approval screen. Binding decisions, from spec §8.5:

1. Colour always means status, never decoration.
2. Instrument Sans for human text, Spline Sans Mono for machine values — revision numbers, dates, counts, progress figures and ids are mono.
3. Cards are flat.
4. Colour is never the only signal — every status colour is paired with a word or an icon.

Also uncovered and required anyway: empty states, loading skeletons, error states, and any layout below 1440px.

---

# Phase 0 — Verify the ground before building on it

### Task 1: Establish a green baseline across all three suites

**Files:**
- Modify: only what triage requires — each fix in its own commit
- Modify: `CLAUDE.md` (record the outcome)

**Interfaces:**
- Consumes: nothing
- Produces: a known-good baseline. Every later task's "the suite is green" claim is meaningless without it.

- [ ] **Step 1: Confirm Java is not blocked**

```powershell
java -version
```

If this fails with an Application Control error, stop and report — no backend task in this plan can proceed, and Phase 7's frontend tasks are the only ones that can run without it.

- [ ] **Step 2: Point the e2e harness at a scratch database**

The harness provisions a tenant per spec file and **never truncates**, so it must not run against the database holding real work.

```bash
docker ps --format '{{.Names}} {{.Ports}}'
# expect onboarding-db-verify ... 0.0.0.0:5433->5432/tcp
```

If absent:

```bash
docker run -d --name onboarding-db-verify -p 5433:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=onboarding postgres:16-alpine
```

- [ ] **Step 3: Free ports 3000 and 8080**

Playwright reuses whatever is already bound, which silently tests the wrong build. Kill strays — unless a port is held by another person's session on this shared machine, in which case leave it and say so.

```powershell
Get-NetTCPConnection -LocalPort 3000,8080 -State Listen -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

- [ ] **Step 4: Run all three suites**

```powershell
cd backend; .\gradlew.bat cleanTest test
cd ..\frontend; npx vitest run
$env:DB_URL = "jdbc:postgresql://localhost:5433/onboarding"; npx playwright test
```

Expected: three `BUILD SUCCESSFUL` / all-pass summary lines. **Read each suite's own summary line — never a pinned count**, which drifts every time a task adds a test.

- [ ] **Step 5: Triage every failure before writing any feature code**

For each failure, rule it into exactly one of three categories and say which in the commit body: a real product bug (fix it with its own test first), a stale spec assertion (fix the spec, never weaken the assertion), or malformed seed data in the spec itself. **No assertion is weakened to make a spec pass.** Sub-project 3's Task 1 report (`.superpowers/sdd/2026-08-29-tasks-and-collaboration/task-1-report.md`) is the worked example of this triage.

- [ ] **Step 6: Commit the baseline record**

```bash
git add CLAUDE.md
git commit -m "test: establish sub-project 3A baseline across all three suites"
```

---

# Phase 1 — Close the six items open from sub-projects 2 and 3

Nothing in this phase is 3A feature work. It repeats the shape sub-project 3 used for sub-project 1's eight backlog items, and two of the six are directly in 3A's path: Task 2's rule rebinding (a new module lands under that rule) and Task 8's role review (3A adds six more permissions and would otherwise leave four seeded to Administrator only).

### Task 2: Rebind the finder rule from name-shaped to repository-injection-shaped

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java:196-260`
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskInstantiation.java:22-31` (javadoc)
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskDirectoryAdapter.java:16-27` (javadoc)

**Interfaces:**
- Consumes: nothing
- Produces: a rule every later task in this plan is measured against. Phases 2–6 add `programme..` and the plan packages to **this** rule, not the old one.

The current rule binds to `haveSimpleNameEndingWith("Service").or().haveSimpleNameEndingWith("Directory")`. Three `task` classes are named specifically to fall outside it — `TaskInstantiation`'s own javadoc says so outright — and `customer.OrgUnitResolver`'s exclusion is a **no-op**, because that name matches neither suffix either, so it excludes nothing.

- [ ] **Step 1: Write the failing test — prove the current rule is blind**

Add to `AuthorizationCoverageTest`:

```java
@Test
void finderRuleBindsToRepositoryInjectionNotClassName() {
    // TaskInstantiation injects repositories and calls finders directly. Under the
    // name-shaped rule it is invisible. Under the rebound rule it must appear as a
    // NAMED exclusion, never as a class the rule silently fails to see.
    assertThat(FINDER_RULE_EXCLUSIONS)
            .contains("co.ara.onboarding.task.TaskInstantiation",
                      "co.ara.onboarding.task.TaskDirectoryAdapter",
                      "co.ara.onboarding.task.TaskLifecycleAdapter");
}
```

- [ ] **Step 2: Run it and watch it fail**

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*AuthorizationCoverageTest*'
```

Expected: FAIL — `FINDER_RULE_EXCLUSIONS` does not exist.

- [ ] **Step 3: Rebind the rule**

Replace the `haveSimpleNameEndingWith(...)` clause with an injection-shaped predicate, and lift every exclusion into one named constant so the exemptions are readable in one place:

```java
/**
 * Every class in the covered packages that INJECTS a repository is covered,
 * whatever it is named. The previous rule bound to a "Service"/"Directory" name
 * suffix, which three task classes were deliberately named to fall outside --
 * making their exemption invisible to a reviewer of the guard itself -- and which
 * made customer.OrgUnitResolver's exclusion a no-op, since that name matches
 * neither suffix. An exemption must be a line in this list, not a naming choice.
 */
static final List<String> FINDER_RULE_EXCLUSIONS = List.of(
        // Run before there is an actor to authorize -- they SUPPLY the department
        // and team scope that resolution itself needs.
        "co.ara.onboarding.identity.IdentityActorDirectory",
        "co.ara.onboarding.authz.UserRoleDirectory",
        // Resolves department and team ids through plain repository lookups
        // because no DEPARTMENT_VIEW or TEAM_VIEW permission exists to scope
        // against -- only the ALL-only DEPARTMENT_MANAGE and TEAM_MANAGE.
        // (The "RLS handles it" half of this exclusion's original justification
        // is deleted: RLS is tenant isolation, not record scope, and restating
        // it is the argument this rule exists to reject.)
        "co.ara.onboarding.customer.OrgUnitResolver",
        // Fed only pre-authorized ids by a caller that already resolved them
        // through AuthorizedQuery -- the CaseEngine/lockById precedent.
        "co.ara.onboarding.task.TaskInstantiation",
        "co.ara.onboarding.task.TaskDirectoryAdapter",
        "co.ara.onboarding.task.TaskLifecycleAdapter");

static final ArchRule servicesDoNotCallRepositoryFindersDirectly =
        noClasses().that()
            .resideInAnyPackage("co.ara.onboarding.customer..",
                                "co.ara.onboarding.identity..",
                                "co.ara.onboarding.auth..",
                                "co.ara.onboarding.workflow..",
                                "co.ara.onboarding.journey..",
                                "co.ara.onboarding.task..")
            .and(injectsARepository())
            .and().areNotAssignableTo(UserDetailsService.class)
            .and().areNotAssignableTo(LoginService.class)
            .and().areNotAssignableTo(LoginThrottleService.class)
            .and().areNotAssignableTo(RefreshTokenService.class)
            .and().areNotAssignableTo(ActivationService.class)
            .and().areNotAssignableTo(PasswordResetService.class)
            .and().areNotAssignableTo(MeService.class)
            .and(not(haveFullyQualifiedNameIn(FINDER_RULE_EXCLUSIONS)))
            .should().callMethodWhere(/* unchanged target predicate */);
```

Write the two helper predicates alongside it:

```java
private static DescribedPredicate<JavaClass> injectsARepository() {
    return new DescribedPredicate<>("injects a repository") {
        @Override public boolean test(JavaClass c) {
            return c.getAllFields().stream()
                    .anyMatch(f -> f.getRawType().getSimpleName().endsWith("Repository"));
        }
    };
}

private static DescribedPredicate<JavaClass> haveFullyQualifiedNameIn(List<String> names) {
    return new DescribedPredicate<>("fully qualified name in the exclusion list") {
        @Override public boolean test(JavaClass c) { return names.contains(c.getFullName()); }
    };
}
```

- [ ] **Step 4: Run the whole suite — the rebound rule may catch something real**

```powershell
cd backend; .\gradlew.bat cleanTest test
```

Expected: PASS. If the rebound rule flags a class not in the list, **do not add it to the list reflexively** — first establish whether that class actually reaches a finder with an unauthorized id. If it does, it is a real bypass and the fix is the code, not the exclusion.

- [ ] **Step 5: Correct the two javadocs that documented the dodge**

`TaskInstantiation`'s javadoc states it is named to fall outside the rule's suffix match. That is no longer true and no longer a defence. Replace with the substantive reason (it is fed only pre-authorized ids), and do the same for `TaskDirectoryAdapter`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java \
        backend/src/main/java/co/ara/onboarding/task/TaskInstantiation.java \
        backend/src/main/java/co/ara/onboarding/task/TaskDirectoryAdapter.java
git commit -m "test(arch): bind the finder rule to repository injection, not class name"
```

### Task 3: Record `task.assigned`, and `task.created` on the ad-hoc path

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskService.java` (`create`, `update`)
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskAuditTest.java` (create)

**Interfaces:**
- Consumes: `AuditRecorder.record(...)`, `AuditActions.of(String, boolean)`
- Produces: `AuditActions.TASK_ASSIGNED`, referenced by nothing else in this plan but subscribed to by sub-project 6

Design spec §5.5 of sub-project 3 named seven audit actions; five exist. `task.assigned` has no constant and nothing records a reassignment. `TaskService.create`'s ad-hoc path records no `task.created` at all — only `TaskInstantiation`'s requirement-instantiated path does.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void adHocCreateRecordsTaskCreated() {
    UUID taskId = fixture.runAs(pm, () -> taskService.create(adHocRequest(caseId, milestoneId)).id());
    assertThat(auditActionsFor("task", taskId)).contains("task.created");
}

@Test
void reassignmentRecordsTaskAssigned() {
    UUID taskId = fixture.runAs(pm, () -> taskService.create(adHocRequest(caseId, milestoneId)).id());
    fixture.runAs(pm, () -> taskService.update(taskId, updateWithAssignee(taskId, otherUserId)));
    assertThat(auditActionsFor("task", taskId)).contains("task.assigned");
}

@Test
void anUpdateThatDoesNotChangeTheAssigneeRecordsNoAssignment() {
    UUID taskId = fixture.runAs(pm, () -> taskService.create(adHocRequest(caseId, milestoneId)).id());
    fixture.runAs(pm, () -> taskService.update(taskId, updateWithTitle(taskId, "Renamed")));
    assertThat(auditActionsFor("task", taskId)).doesNotContain("task.assigned");
}
```

The third test is the one that matters: `task.assigned` must fire on a **transition** of the assignee, never on every update that merely carries the same assignee — the same distinction `contact.deactivated` draws against a phone-number correction.

- [ ] **Step 2: Run and watch all three fail**

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*TaskAuditTest*'
```

Expected: first two FAIL (action absent), third passes vacuously.

- [ ] **Step 3: Add the constant**

In `AuditActions`, beside the existing `TASK_*` constants:

```java
public static final AuditAction TASK_ASSIGNED = of("task.assigned", true);
```

`true` because a task's owner changing is the customer's business in the same way `contact.*` is — it is a business record, not internal staffing.

- [ ] **Step 4: Record both actions**

In `TaskService.create`, after the task is persisted and **before** any call that records a consequence, record `task.created`. In `TaskService.update`, capture the previous assignee before mutating and record `task.assigned` only when `!Objects.equals(previousAssignee, request.assigneeId())`.

- [ ] **Step 5: Run and verify all three pass, then the full suite**

```powershell
cd backend; .\gradlew.bat cleanTest test
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/audit/AuditActions.java \
        backend/src/main/java/co/ara/onboarding/task/TaskService.java \
        backend/src/test/java/co/ara/onboarding/task/TaskAuditTest.java
git commit -m "fix(task): record task.assigned on reassignment and task.created ad-hoc"
```

### Task 4: Sort "Do now" by due date

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskService.java:204-210` (`forCase`), `:385-391` (`myWork`)
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskOrderingTest.java` (create)

**Interfaces:**
- Consumes: `org.springframework.data.domain.Sort`
- Produces: a deterministic order `WorkColumn.tsx` can rely on without sorting client-side

Sub-project 3's design spec §8.2 specified "Do now, sorted by due date". Both methods pass `Pageable.unpaged()` with no `Sort`, so `WorkColumn.tsx:97` maps whatever order the query returns and an overdue item can sit below one due next month.

- [ ] **Step 1: Write the failing test**

```java
@Test
void myWorkReturnsDueSoonestFirstWithUndatedLast() {
    seedTask("due in 30 days", LocalDate.now().plusDays(30));
    seedTask("overdue",        LocalDate.now().minusDays(2));
    seedTask("no due date",    null);
    seedTask("due tomorrow",   LocalDate.now().plusDays(1));

    List<String> titles = fixture.runAs(pm, () -> taskService.myWork(new MyWorkFilters("do_now")))
            .stream().map(TaskView::title).toList();

    assertThat(titles).containsExactly(
            "overdue", "due tomorrow", "due in 30 days", "no due date");
}
```

An undated task sorting **last** rather than first is the point: `NULLS FIRST` is Postgres's default for `ASC`, which would put every undated task above every overdue one — the exact inversion this task exists to fix.

- [ ] **Step 2: Run and watch it fail**

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*TaskOrderingTest*'
```

Expected: FAIL on ordering.

- [ ] **Step 3: Apply the sort in both methods**

```java
private static final Sort BY_DUE_DATE =
        Sort.by(Sort.Order.asc("dueDate").nullsLast(), Sort.Order.asc("createdAt"));
```

Pass `Pageable.unpaged(BY_DUE_DATE)` in `myWork` and `forCase`. The `createdAt` tiebreak makes the order total, so two tasks due the same day do not swap between requests.

- [ ] **Step 4: Run and verify, then the full suite**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/task/TaskService.java \
        backend/src/test/java/co/ara/onboarding/task/TaskOrderingTest.java
git commit -m "fix(task): sort work-board and case task reads by due date, undated last"
```

### Task 5: Scope-filter `taskSummary` and render it on the roadmap

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskDirectoryAdapter.java:55` (`summaryFor`)
- Modify: `frontend/src/components/journey/MilestoneRow.tsx`
- Test: `backend/src/test/java/co/ara/onboarding/task/TaskSummaryScopeTest.java` (create)
- Test: `frontend/src/components/journey/__tests__/MilestoneRow.test.tsx` (modify)

**Interfaces:**
- Consumes: `journey.TaskSummary` — `record TaskSummary(int open, int total)`
- Produces: a scope-correct summary. **This is the same aggregate-only leak shape spec §6.4 refuses to repeat for the programme rollup** — closing it here and opening it there in the same branch would be indefensible.

`summaryFor` counts every task on the milestone regardless of the reader's scope, so an ASSIGNED-scoped reader (Sales Representative, Service Provider, Business Partner) sees counts including tasks they cannot open. `generated.ts:1729` has the field; `Roadmap.tsx` / `MilestoneRow.tsx` never read it.

- [ ] **Step 1: Write the failing backend test**

```java
@Test
void summaryCountsOnlyTasksTheReaderCanOpen() {
    UUID mine    = seedTaskAssignedTo(salesRep, milestoneId);
    UUID theirs  = seedTaskAssignedTo(otherUser, milestoneId);

    TaskSummary summary = fixture.runAs(salesRep,
            () -> taskDirectory.summaryFor(List.of(milestoneId)).get(milestoneId));

    // salesRep holds task.view at ASSIGNED. `theirs` is invisible to them --
    // and must be invisible in the COUNT too, not merely unopenable.
    assertThat(summary.total()).isEqualTo(1);
    assertThat(fixture.runAs(salesRep, () -> taskService.forCase(caseId)).size()).isEqualTo(1);
}
```

- [ ] **Step 2: Run and watch it fail** — expected `2` where `1` is asserted.

- [ ] **Step 3: Route the count through `AuthorizedQuery`**

`summaryFor` currently calls a repository finder directly. Replace the count with an `authorizedQuery.findAll(tasks, Task.class, PermissionKeys.TASK_VIEW, byMilestones, Pageable.unpaged())` and group in memory. **Note this may remove `TaskDirectoryAdapter`'s need for Task 2's exclusion** — if it no longer injects a repository or no longer reaches a finder, delete its line from `FINDER_RULE_EXCLUSIONS` rather than leaving a stale exemption.

- [ ] **Step 4: Render it, with a failing frontend test first**

```tsx
it("shows open and total task counts on a milestone that has tasks", () => {
  render(<MilestoneRow milestone={{ ...base, taskSummary: { open: 2, total: 5 } }} />);
  expect(screen.getByText("2 of 5 tasks open")).toBeInTheDocument();
});

it("renders no task count at all when a milestone has none", () => {
  render(<MilestoneRow milestone={{ ...base, taskSummary: { open: 0, total: 0 } }} />);
  expect(screen.queryByText(/tasks open/)).not.toBeInTheDocument();
});
```

The counts are machine-generated values: render them in **Spline Sans Mono**, per the binding design decisions. The string goes through `t()` — `t("journey.milestone.taskCount", { open, total })`.

- [ ] **Step 5: Run both suites**

```powershell
cd backend; .\gradlew.bat cleanTest test
cd ..\frontend; npx vitest run
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/task/TaskDirectoryAdapter.java \
        backend/src/test/java/co/ara/onboarding/task/TaskSummaryScopeTest.java \
        frontend/src/components/journey/MilestoneRow.tsx \
        frontend/src/components/journey/__tests__/MilestoneRow.test.tsx \
        frontend/src/lib/i18n/en.ts
git commit -m "fix(task): scope-filter the roadmap task summary and render it"
```

### Task 6: A task-edit dialog — title, description, priority, due date, milestone

**Files:**
- Modify: `frontend/src/lib/api/tasks.ts` (add `useUpdateTask`)
- Create: `frontend/src/components/task/TaskEditDialog.tsx`
- Modify: `frontend/src/components/task/TaskDetail.tsx`
- Test: `frontend/src/components/task/__tests__/TaskEditDialog.test.tsx` (create)

**Interfaces:**
- Consumes: `PUT /api/t/{slug}/tasks/{taskId}`, typed as `UpdateTaskRequest` in `generated.ts`
- Produces: `useUpdateTask()` — used by Task 7's assignee picker

**Invoke `frontend-design` and `ui-ux-pro-max` before starting.**

`PUT /tasks/{taskId}` has no frontend hook at all. Title, description, priority, due date and milestone are as uneditable as the assignee — confirmed independently three times (Task 27's and Task 28's implementer reports, and `tasks.spec.ts`, which had to seed an assignment through a direct API call).

- [ ] **Step 1: Write the failing test**

```tsx
it("submits every field the request type accepts, not only the changed one", async () => {
  const update = vi.fn();
  render(<TaskEditDialog task={existing} onSubmit={update} open />);
  await userEvent.clear(screen.getByLabelText("Title"));
  await userEvent.type(screen.getByLabelText("Title"), "Renamed");
  await userEvent.click(screen.getByRole("button", { name: "Save" }));

  // A PUT is a full replace: a field omitted from the body is written as null.
  expect(update).toHaveBeenCalledWith(expect.objectContaining({
    title: "Renamed",
    description: existing.description,
    priority: existing.priority,
    dueDate: existing.dueDate,
    milestoneId: existing.milestoneId,
    assigneeId: existing.assigneeId,
  }));
});
```

That assertion is the whole point of the test. A dialog that sends only the edited field silently blanks the other five.

- [ ] **Step 2: Run and watch it fail** — `npx vitest run TaskEditDialog`

- [ ] **Step 3: Add the hook**

```ts
export function useUpdateTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, body }: { taskId: string; body: UpdateTaskRequest }) =>
      api.put(`/tasks/${taskId}`, body),
    onSuccess: (_data, { taskId }) => {
      queryClient.invalidateQueries({ queryKey: taskKeys.detail(taskId) });
      queryClient.invalidateQueries({ queryKey: taskKeys.all });
    },
  });
}
```

- [ ] **Step 4: Build the dialog, seeded from the current task**

Initialise every field from the existing task so an untouched field round-trips its current value rather than a null. Every label goes through `t()`. Due date is a machine value — mono.

- [ ] **Step 5: Run the frontend suite**

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/api/tasks.ts frontend/src/components/task/TaskEditDialog.tsx \
        frontend/src/components/task/TaskDetail.tsx \
        frontend/src/components/task/__tests__/TaskEditDialog.test.tsx \
        frontend/src/lib/i18n/en.ts
git commit -m "feat(task): add a task edit dialog wired to PUT /tasks/{id}"
```

### Task 7: An assignee picker on the task edit dialog

**Files:**
- Modify: `frontend/src/components/task/TaskEditDialog.tsx`
- Modify: `frontend/src/lib/api/admin.ts` (reuse the existing user list hook; add none if one fits)
- Test: `frontend/src/components/task/__tests__/TaskEditDialog.test.tsx` (extend)

**Interfaces:**
- Consumes: `useUpdateTask()` from Task 6; the tenant's user list
- Produces: the last piece of the "no task-edit UI at all" gap

**Invoke `frontend-design` and `ui-ux-pro-max` before starting.**

- [ ] **Step 1: Write the failing test**

```tsx
it("offers an assignee picker and can clear an assignment", async () => {
  const update = vi.fn();
  render(<TaskEditDialog task={assignedTask} users={[alice, bob]} onSubmit={update} open />);
  await userEvent.click(screen.getByLabelText("Assignee"));
  await userEvent.click(screen.getByRole("option", { name: "Unassigned" }));
  await userEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(update).toHaveBeenCalledWith(expect.objectContaining({ assigneeId: null }));
});
```

Clearing to `null` must be reachable: `assignee_id` is nullable and a task whose owner leaves must be un-assignable without being cancelled.

- [ ] **Step 2: Run and watch it fail**

- [ ] **Step 3: Add the picker**, defaulting to the task's current assignee and offering an explicit "Unassigned" option distinct from the empty state.

- [ ] **Step 4: Run the frontend suite**

- [ ] **Step 5: Update `tasks.spec.ts`'s comment**

`frontend/e2e/tasks.spec.ts` carries a case named "a task assigned through the API (no picker exists in the UI)…". A picker now exists. Either drive the assignment through the UI in that spec or amend the name — leaving it is a stale claim in a file future sessions read as fact.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/task/TaskEditDialog.tsx \
        frontend/src/components/task/__tests__/TaskEditDialog.test.tsx \
        frontend/e2e/tasks.spec.ts frontend/src/lib/i18n/en.ts
git commit -m "feat(task): add an assignee picker, closing the task-edit gap"
```

### Task 8: Role-template review — seed `task.manage` and `approval.decide` beyond Administrator

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/RoleTemplates.java:119-129` and the `approval.decide` grants
- Test: `backend/src/test/java/co/ara/onboarding/authz/RoleTemplateCoverageTest.java` (create)

**Interfaces:**
- Consumes: `PermissionCatalog`, `RoleTemplates`
- Produces: the seeding convention Phase 2's six new permissions follow

`task.manage` is catalogued at ALL/DEPARTMENT/TEAM but granted to none of the other eleven templates, so no seeded role can create an ad-hoc task, add a checklist item or reassign one. Project Manager holds `TASK_VIEW`/`TASK_COMPLETE`/`COMMENT_CREATE` at TEAM but not `TASK_MANAGE`. `approval.decide` has the same shape.

- [ ] **Step 1: Write the failing test**

```java
@Test
void everyRecordScopedPermissionIsHeldByAtLeastOneNonAdministratorTemplate() {
    List<String> administratorOnly = catalog.all().stream()
            .filter(p -> p.scopes().size() > 1)          // catalogued at more than ALL
            .map(Permission::key)
            .filter(key -> RoleTemplates.all().stream()
                    .filter(t -> !t.name().equals("Administrator"))
                    .noneMatch(t -> t.grants().stream().anyMatch(g -> g.permissionKey().equals(key))))
            .toList();

    assertThat(administratorOnly)
            .as("a permission catalogued at several scopes but granted only to "
              + "Administrator cannot be exercised at any narrower scope by any seeded role")
            .isEmpty();
}
```

This is a **derivable** guard, not a typed list — the lesson CLAUDE.md draws from all five hand-written enumerations in sub-project 1 drifting behind the code. It will fail again the moment Phase 2 adds `programme.manage` without seeding it, which is the intent.

- [ ] **Step 2: Run and watch it fail** — expect `["task.manage", "approval.decide"]`.

- [ ] **Step 3: Seed the two grants**

Grant `TASK_MANAGE` at TEAM to Project Manager (it already holds `TASK_VIEW`/`TASK_COMPLETE` at TEAM — a role that can complete a task but not create one is incoherent), and `APPROVAL_DECIDE` at DEPARTMENT to the department-lead-shaped template. Do **not** grant `MILESTONE_FORCE_APPROVE`: it is ALL-only in the catalog itself and cannot be narrower by construction, which is Q5's deliberate choice.

- [ ] **Step 4: Run the whole suite** — `RoleLifecycleTest`, `MultipleRolesTest` and `DelegationGuardTest` all read the templates and may need their expectations updated. **Update expectations, never the guard.**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/authz/RoleTemplates.java \
        backend/src/test/java/co/ara/onboarding/authz/RoleTemplateCoverageTest.java
git commit -m "fix(authz): seed task.manage and approval.decide beyond Administrator"
```

- [ ] **Step 6: Update CLAUDE.md**

Move all six Phase 1 items out of "Open at the close of sub-project 3" into a "Closed since sub-project 3" paragraph, naming what closed each. Commit separately as `docs(claude-md): record sub-project 3A Phase 1 closures`.

---

# Phase 2 — The `programme` module (Q20)

### Task 9: Migration and entities for `programme`, `programme_participant`, `programme_case`

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__programme.sql`
- Create: `backend/src/main/java/co/ara/onboarding/programme/Programme.java`, `ProgrammeStatus.java`, `ProgrammeRepository.java`
- Create: `.../ProgrammeParticipant.java`, `ProgrammeParticipantStatus.java`, `ProgrammeParticipantRepository.java`
- Create: `.../ProgrammeCase.java`, `ProgrammeCaseRepository.java`
- Test: `backend/src/test/java/co/ara/onboarding/programme/ProgrammeSchemaTest.java` (create)

**Interfaces:**
- Consumes: `co.ara.onboarding.tenancy.TenantScopedEntity`, `platform.Uuid7.generate()`, `authz.RelationshipType`
- Produces: `Programme` (`name`, `customerId`, `description`, `ownerUserId`, `owningDepartmentId`, `owningTeamId`, `status`, `createdBy`), `ProgrammeParticipant` (`programmeId`, `userId`, `relationshipType`, `status`), `ProgrammeCase` (`programmeId`, `caseId`, `addedAt`, `addedBy`, `removedAt`). All three repositories extend `JpaRepository<T, UUID>, JpaSpecificationExecutor<T>`.

- [ ] **Step 1: List the migration directory and take the next free number**

```bash
ls backend/src/main/resources/db/migration/
```

Do not assume `V17` — Phase 1's Task 3 may have taken it.

- [ ] **Step 2: Write the failing test first**

```java
@Test
void allThreeProgrammeTablesAreTenantIsolatedAndUndeletable() {
    assertThat(rlsEnabledOn("programme")).isTrue();
    assertThat(rlsEnabledOn("programme_participant")).isTrue();
    assertThat(rlsEnabledOn("programme_case")).isTrue();
    assertThat(privilegesFor("programme")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
}

@Test
void aJourneyCanLeaveOneProgrammeAndJoinAnother() {
    // The partial unique index, not a plain UNIQUE(case_id): DELETE is revoked, so a
    // journey that moves must leave a removed row behind and still be insertable.
    insertProgrammeCase(programmeA, caseId, /* removedAt */ null);
    updateProgrammeCaseRemovedAt(programmeA, caseId, Instant.now());
    assertThatNoException().isThrownBy(() -> insertProgrammeCase(programmeB, caseId, null));
}

@Test
void aJourneyCannotBeInTwoProgrammesAtOnce() {
    insertProgrammeCase(programmeA, caseId, null);
    assertThatThrownBy(() -> insertProgrammeCase(programmeB, caseId, null))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 3: Run and watch it fail**

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*ProgrammeSchemaTest*'
```

Expected: FAIL — relation "programme" does not exist.

- [ ] **Step 4: Write the migration**

```sql
-- Sub-project 3A, Task 9: a programme groups a customer's parallel journeys (QA Q20).
-- It has NO lifecycle -- no hold, no approval, no engine. `status` exists only
-- because business records are deactivated and never deleted, and DELETE is revoked
-- at the database layer.

CREATE TABLE programme (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    customer_id          uuid NOT NULL REFERENCES customer(id),
    name                 text NOT NULL,
    description          text,
    -- All three are what ProgrammeDescriptor's DEPARTMENT and TEAM predicates read.
    -- Without them both collapse to cb.disjunction() and only ALL-scoped holders
    -- ever see a programme.
    owner_user_id        uuid     NULL REFERENCES app_user(id),
    owning_department_id uuid     NULL REFERENCES department(id),
    owning_team_id       uuid     NULL REFERENCES team(id),
    status               text NOT NULL,
    created_by           uuid     NULL REFERENCES app_user(id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT programme_status_ck CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT programme_name_ck   CHECK (length(btrim(name)) > 0)
);
CREATE INDEX programme_tenant_customer_idx ON programme (tenant_id, customer_id);

CREATE TABLE programme_participant (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    programme_id      uuid NOT NULL REFERENCES programme(id),
    user_id           uuid NOT NULL REFERENCES app_user(id),
    relationship_type text NOT NULL,
    status            text NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT programme_participant_status_ck CHECK (status IN ('ACTIVE','REMOVED')),
    CONSTRAINT programme_participant_rel_ck CHECK (
        relationship_type IN ('OWNER','ASSIGNEE','PARTICIPANT','APPROVER','CREATOR'))
);
CREATE UNIQUE INDEX programme_participant_uq
    ON programme_participant (programme_id, user_id);

CREATE TABLE programme_case (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    programme_id uuid NOT NULL REFERENCES programme(id),
    case_id      uuid NOT NULL REFERENCES onboarding_case(id),
    added_at     timestamptz NOT NULL,
    added_by     uuid NULL REFERENCES app_user(id),
    removed_at   timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);
-- Partial, not plain: a journey belongs to at most one programme AT A TIME, but
-- DELETE is revoked, so leaving one and joining another must stay possible.
CREATE UNIQUE INDEX programme_case_active_uq
    ON programme_case (case_id) WHERE removed_at IS NULL;
CREATE INDEX programme_case_programme_idx ON programme_case (programme_id, removed_at);

SELECT enable_tenant_rls('programme');
SELECT enable_tenant_rls('programme_participant');
SELECT enable_tenant_rls('programme_case');

GRANT SELECT, INSERT, UPDATE ON programme             TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON programme_participant TO onboarding_app;
GRANT SELECT, INSERT, UPDATE ON programme_case        TO onboarding_app;
```

- [ ] **Step 5: Write the three entities and three repositories**

Each entity `extends TenantScopedEntity`, `@Enumerated(EnumType.STRING)` on every enum column, `@Column(name = "...")` spelled explicitly. `ProgrammeParticipantStatus` is programme's **own** enum — do not import `journey.ParticipantStatus`, so a later change to journey's participant lifecycle cannot silently change programme's.

- [ ] **Step 6: Run the schema test, then the full suite**

`RlsCoverageTest` is deny-by-default and will fail if any of the three tables missed `enable_tenant_rls`. Its allowlist stays at four entries.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/programme/ backend/src/test/java/co/ara/onboarding/programme/ProgrammeSchemaTest.java
git commit -m "feat(programme): add programme, participant and case-membership tables"
```

### Task 10: The two module-boundary rules, proven red

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: the `programme` package from Task 9
- Produces: two named rules every later task is measured against

A one-way `journey → programme` or `customer → programme` import would still pass the plain no-cycles rule, which is why each gets its own named method — the reasoning sub-project 2's §3.3 established and sub-project 3 repeated for `noJourneyDependencyOnTask`.

- [ ] **Step 1: Write both rules**

```java
@Test
void noJourneyDependencyOnProgramme() {
    noClasses().that().resideInAPackage("co.ara.onboarding.journey..")
        .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.programme..")
        .because("programme depends on journey, never the reverse. Membership lives in "
               + "programme_case precisely so journey never learns programmes exist.")
        .check(classes);
}

@Test
void noCustomerDependencyOnProgramme() {
    noClasses().that().resideInAPackage("co.ara.onboarding.customer..")
        .should().dependOnClassesThat().resideInAPackage("co.ara.onboarding.programme..")
        .because("programme reaches customer through a facts port, the CustomerDirectory "
               + "inversion. An import back would close the cycle.")
        .check(classes);
}
```

- [ ] **Step 2: Prove each red with a temporary violation**

Add `import co.ara.onboarding.programme.Programme;` and an unused field to `journey/CaseService.java`. Run:

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*ModuleBoundaryTest*'
```

Expected: `noJourneyDependencyOnProgramme` FAILS naming `CaseService`. Repeat the same temporary violation in `customer/CustomerService.java` for the second rule. **A guard you have never seen fail is a guard you cannot trust** — do not skip this.

- [ ] **Step 3: Revert both temporary violations and re-run** — expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java
git commit -m "test(arch): forbid journey->programme and customer->programme imports"
```

### Task 11: Permissions and the three programme descriptors

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`, `PermissionCatalog.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/ProgrammeDescriptor.java`, `ProgrammeParticipantDescriptor.java`, `ProgrammeCaseDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java` (extend)

**Interfaces:**
- Consumes: `ResourceAuthorizationDescriptor<T>` — `resourceType()`, `entityType()`, `assignedRelationships()`, `departmentScope(AuthContext)`, `teamScope(AuthContext)`, `assignedScope(AuthContext)`
- Produces: `PermissionKeys.PROGRAMME_CREATE` = `"programme.create"`, `PROGRAMME_VIEW` = `"programme.view"`, `PROGRAMME_MANAGE` = `"programme.manage"`

- [ ] **Step 1: Catalogue the three permissions FIRST and watch the application refuse to start**

```java
add(PROGRAMME_VIEW,   "programme", "programme", "View programmes",                   RECORD);
add(PROGRAMME_CREATE, "programme", null,        "Create a programme for a customer", ALL_ONLY);
add(PROGRAMME_MANAGE, "programme", "programme", "Edit a programme, its journeys and its participants", ORG_SCOPES);
```

`PROGRAMME_CREATE` is ALL-only for the same reason `CASE_CREATE` and `CUSTOMER_CREATE` are: there is no record yet to scope against.

```powershell
cd backend; .\gradlew.bat cleanTest test --tests '*DescriptorRegistryTest*'
```

Expected: FAIL — `DescriptorRegistry.validate()` refuses startup naming resource type `programme`, because `programme.view` is catalogued at RECORD and no descriptor covers it. **This is the red state. Confirm you see it before writing the descriptor** — it is the same order sub-project 2 used, and it is the only way to know `validate()` actually guards this.

- [ ] **Step 2: Write `ProgrammeDescriptor`**

```java
@Component
public class ProgrammeDescriptor implements ResourceAuthorizationDescriptor<Programme> {

    @Override public String resourceType() { return "programme"; }
    @Override public Class<Programme> entityType() { return Programme.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.PARTICIPANT);
    }

    @Override public Specification<Programme> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Programme> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    /**
     * ASSIGNED resolves through programme_participant on the actor -- and that is the
     * ONLY thing programme participation grants. It confers no access to any journey
     * the programme contains; those come from case_participant rows written explicitly
     * (spec 6.3). A predicate here that reached into onboarding_case would be exactly
     * the scope-widening backdoor three sub-project 1 escalations took.
     */
    @Override public Specification<Programme> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> {
            if (ctx.userId() == null) return cb.disjunction();
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<ProgrammeParticipant> p = sub.from(ProgrammeParticipant.class);
            sub.select(p.get("programmeId")).where(
                    cb.equal(p.get("userId"), ctx.userId()),
                    cb.equal(p.get("status"), ProgrammeParticipantStatus.ACTIVE));
            return root.get("id").in(sub);
        };
    }
}
```

- [ ] **Step 3: Write the two child descriptors**

`ProgrammeParticipantDescriptor` and `ProgrammeCaseDescriptor` exist **not** because `validate()` demands them — it does not, neither has a permission of its own — but because `AuthorizedQuery.findAll`/`getById` dispatch by **entity type**. This is the trap that produced `CaseParticipantDescriptor` and `CaseAttributeValueDescriptor` in sub-project 2, and `validate()` will not remind anyone. Each delegates to the parent programme's predicate through the denormalised `programme_id`:

```java
@Override public Specification<ProgrammeCase> departmentScope(AuthContext ctx) {
    return viaProgramme((root, query, cb, p) -> ctx.departmentId() == null
            ? cb.disjunction()
            : cb.equal(p.get("owningDepartmentId"), ctx.departmentId()));
}
```

Write `viaProgramme` as a private helper mirroring `TaskDescriptor.viaCase`.

- [ ] **Step 4: Add a regression test proving the registry is satisfied**

```java
@Test
void everyRecordScopedResourceTypeHasADescriptor() {
    assertThatNoException().isThrownBy(() -> registry.validate());
    assertThat(registry.resourceTypes()).contains("programme");
}
```

- [ ] **Step 5: Run the full suite — expect green, and the startup refusal gone**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java backend/src/main/java/co/ara/onboarding/authz/PermissionCatalog.java backend/src/main/java/co/ara/onboarding/scoping/ backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java
git commit -m "feat(authz): catalogue programme permissions and their three descriptors"
```

### Task 12: `ProgrammeService` — create, read, update, deactivate

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/programme/ProgrammeService.java`
- Create: `.../CreateProgrammeRequest.java`, `UpdateProgrammeRequest.java`, `ProgrammeView.java`
- Create: `.../ProgrammeExceptionHandler.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java` (add `co.ara.onboarding.programme..`)
- Test: `backend/src/test/java/co/ara/onboarding/programme/ProgrammeServiceTest.java` (create)

**Interfaces:**
- Consumes: `AuthorizedQuery`, `AuditRecorder`, the `customer` facts port
- Produces: `ProgrammeView(UUID id, String name, UUID customerId, String customerName, String description, UUID ownerUserId, UUID owningDepartmentId, UUID owningTeamId, ProgrammeStatus status)`

- [ ] **Step 1: Add `programme..` to the finder rule in the SAME commit**

Per Global Constraints, add the package to Task 2's rebound `servicesDoNotCallRepositoryFindersDirectly` **before** writing the service, not afterwards.

- [ ] **Step 2: Write the failing tests**

```java
@Test
void createResolvesTheCustomerThroughAuthorizedQueryBeforeWriting() {
    // The customer id comes straight from a request body. @RequirePermission cannot
    // see arguments, so a passing gate proves only that the actor may touch SOME
    // customer. Three sub-project 1 escalations were this exact shape.
    UUID foreignCustomer = fixture.customerInAnotherDepartment();
    assertThatThrownBy(() -> fixture.runAs(narrowPm,
            () -> programmeService.create(new CreateProgrammeRequest("P", foreignCustomer, null, null, null, null))))
            .isInstanceOf(NoSuchElementException.class);
    assertThat(programmesFor(foreignCustomer)).isEmpty();   // nothing half-written
}

@Test
void updateIsAFullReplaceAndTheViewCarriesEveryFieldTheRequestAccepts() {
    // Field-for-field alignment: a field on UpdateProgrammeRequest with no twin on
    // ProgrammeView makes every client silently erase it on the next PUT.
    Set<String> requestFields = componentNames(UpdateProgrammeRequest.class);
    Set<String> viewFields    = componentNames(ProgrammeView.class);
    assertThat(viewFields).containsAll(requestFields);
}

@Test
void deactivationRevokesTheCrossJourneyReadStructurally() {
    UUID programmeId = seedProgrammeWithParticipant(sponsor);
    fixture.runAs(admin, () -> programmeService.deactivate(programmeId));
    assertThatThrownBy(() -> fixture.runAs(sponsor, () -> programmeService.get(programmeId)))
            .isInstanceOf(NoSuchElementException.class);
}
```

The third test is the "what does deactivation revoke?" question answered in code rather than in convention — the required design question sub-project 1 established.

- [ ] **Step 3: Run and watch them fail**

- [ ] **Step 4: Write the service**

Every public method carries `@RequirePermission`. Every read goes through `AuthorizedQuery`. `create` resolves `customerId` through the customer facts port before writing. `deactivate` sets `status = INACTIVE` and does nothing else — the descriptor's `assignedScope` already excludes inactive programmes, which is why the revocation is structural rather than a cleanup step.

Three audit actions, all `timelineVisible = true`:

```java
public static final AuditAction PROGRAMME_CREATED     = of("programme.created", true);
public static final AuditAction PROGRAMME_UPDATED     = of("programme.updated", true);
public static final AuditAction PROGRAMME_DEACTIVATED = of("programme.deactivated", true);
```

- [ ] **Step 5: Run the full suite**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/programme/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java backend/src/test/java/co/ara/onboarding/programme/ProgrammeServiceTest.java
git commit -m "feat(programme): add ProgrammeService with create, update and deactivate"
```

### Task 13: Membership and participants — and this sub-project's most important test

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/programme/ProgrammeMembershipService.java`
- Create: `.../AddJourneyRequest.java`, `AddProgrammeParticipantRequest.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/programme/ProgrammeScopeTest.java` (create)
- Test: `backend/src/test/java/co/ara/onboarding/programme/ProgrammeIsolationTest.java` (create)

**Interfaces:**
- Consumes: `journey`'s existing gated participant API — never `CaseParticipantRepository` directly from `programme`
- Produces: `addJourney(UUID, AddJourneyRequest)`, `removeJourney(UUID, UUID)`, `addParticipant(UUID, AddProgrammeParticipantRequest)`, `removeParticipant(UUID, UUID)`

`AddProgrammeParticipantRequest` carries `boolean alsoGrantJourneyAccess`. When true the service writes a real `CaseParticipant` row per journey — an explicit, audited, individually revocable grant authorized by `programme.manage`, **never** by participation itself (spec §6.3).

- [ ] **Step 1: Write `ProgrammeScopeTest` first — before any membership code exists**

```java
@Test
void aProgrammeParticipantSeesOnlyTheJourneysTheyCouldOtherwiseOpen() {
    UUID programmeId = seedProgramme();
    UUID visible   = seedCaseWithParticipant(sponsor);   // sponsor has a case_participant row
    UUID invisible = seedCaseWithNoParticipants();       // sponsor has nothing
    addBothJourneysToProgramme(programmeId, visible, invisible);
    addProgrammeParticipant(programmeId, sponsor, /* alsoGrantJourneyAccess */ false);

    ProgrammeDetailView view = fixture.runAs(sponsor, () -> programmeService.get(programmeId));

    // The container must not be a backdoor. Programme participation grants read of
    // the PROGRAMME; journey access comes only from a real case_participant row.
    assertThat(view.journeys()).extracting(ProgrammeJourneyView::caseId)
            .containsExactly(visible);
}

@Test
void theInvisibleJourneyIs404NotAnEmptyFieldWhenOpenedDirectly() {
    assertThatThrownBy(() -> fixture.runAs(sponsor, () -> caseService.get(invisible)))
            .isInstanceOf(NoSuchElementException.class);
}

@Test
void grantingJourneyAccessIsAnExplicitWriteThatShowsUpInTheAuditTrail() {
    addProgrammeParticipant(programmeId, sponsor, /* alsoGrantJourneyAccess */ true);
    ProgrammeDetailView view = fixture.runAs(sponsor, () -> programmeService.get(programmeId));
    assertThat(view.journeys()).hasSize(2);
    assertThat(auditActionsFor("onboarding_case", invisible)).contains("case.participant_added");
}
```

- [ ] **Step 2: Write `ProgrammeIsolationTest`**

```java
@Test
void aProgrammeFromAnotherTenantIs404() {
    UUID foreign = fixture.programmeInTenantB();
    assertThatThrownBy(() -> fixture.runAs(tenantAAdmin, () -> programmeService.get(foreign)))
            .isInstanceOf(NoSuchElementException.class);   // 404, never 200 and never 500
}

@Test
void aCaseFromAnotherTenantCannotBeAddedToAProgramme() {
    assertThatThrownBy(() -> fixture.runAs(tenantAAdmin,
            () -> membershipService.addJourney(programmeA, new AddJourneyRequest(foreignCaseId))))
            .isInstanceOf(NoSuchElementException.class);
}
```

- [ ] **Step 3: Run both and watch them fail** — the services do not exist yet.

- [ ] **Step 4: Write `ProgrammeMembershipService`**

`addJourney` resolves the case id through `AuthorizedQuery` under `case.view` **before** writing the link row — an id from a URL, so the write-path obligation applies. `removeJourney` sets `removed_at`, never deletes.

Four more audit actions, all `timelineVisible = true`: `programme.journey_added`, `programme.journey_removed`, `programme.participant_added`, `programme.participant_removed`.

- [ ] **Step 5: Run the full suite**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/programme/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/programme/
git commit -m "feat(programme): journey membership and participants, read-only by design"
```

### Task 14: The derived rollup, and the controller

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/CaseWeight.java`, `CaseWeightReader.java`
- Create: `backend/src/main/java/co/ara/onboarding/programme/ProgrammeRollup.java`
- Create: `.../ProgrammeController.java`, `ProgrammeDetailView.java`, `ProgrammeJourneyView.java`
- Test: `backend/src/test/java/co/ara/onboarding/programme/ProgrammeRollupTest.java` (create)

**Interfaces:**
- Consumes: `CaseWeightReader.weightsFor(Collection<UUID> caseIds)` → `List<CaseWeight>`, gated `@RequirePermission(CASE_VIEW)`, reading through `AuthorizedQuery`
- Produces: `record CaseWeight(UUID caseId, int progressPercent, int weightDays)`; `ProgrammeDetailView(ProgrammeView programme, List<ProgrammeJourneyView> journeys, int rolledUpProgressPercent, int journeysCovered)`

A case's weight is the sum of `estimated_duration_days` over its non-`SKIPPED` milestones — the same weighting `CaseEngine.progressOf` applies within a case. `programme` never reaches into `workflow` for `MilestoneDefinition`; `journey` already depends on `workflow` and exposes the number.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void progressIsWeightedByEachJourneysTotalEstimatedDuration() {
    // 10-day journey at 100%, 30-day journey at 0% -> 25%, not the unweighted 50%.
    seedJourneyInProgramme(programmeId, /* durationDays */ 10, /* progress */ 100);
    seedJourneyInProgramme(programmeId, /* durationDays */ 30, /* progress */ 0);

    ProgrammeDetailView view = fixture.runAs(admin, () -> programmeService.get(programmeId));
    assertThat(view.rolledUpProgressPercent()).isEqualTo(25);
}

@Test
void theRollupCoversOnlyTheJourneysTheReaderCanSeeAndSaysHowMany() {
    // Computing over journeys the viewer cannot open would be an aggregate-only leak
    // -- the identical shape to the taskSummary gap Phase 1 Task 5 just closed.
    ProgrammeDetailView view = fixture.runAs(sponsor, () -> programmeService.get(programmeId));
    assertThat(view.journeysCovered()).isEqualTo(1);
    assertThat(view.rolledUpProgressPercent()).isEqualTo(100);
}

@Test
void aProgrammeWithNoVisibleJourneysReportsZeroPercentOverZeroJourneys() {
    // Not a divide-by-zero, and not "100% complete".
    ProgrammeDetailView view = fixture.runAs(strangerWithProgrammeView, () -> programmeService.get(programmeId));
    assertThat(view.journeysCovered()).isZero();
    assertThat(view.rolledUpProgressPercent()).isZero();
}

@Test
void noProgrammeCodePathEverCallsTheEngine() {
    assertThat(classesIn("co.ara.onboarding.programme"))
            .noneMatch(c -> dependsOn(c, "co.ara.onboarding.journey.CaseEngine"));
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement `CaseWeightReader` in `journey`**

Gated `@RequirePermission(PermissionKeys.CASE_VIEW)`; reads cases through `AuthorizedQuery` so the filter is applied by the same mechanism as everything else, then sums milestone-definition durations for the cases that survived.

- [ ] **Step 4: Implement `ProgrammeRollup`** as a pure function over `List<CaseWeight>`, unit-testable with no database. `weightDays == 0` across the whole set returns `0` — never a division by zero.

- [ ] **Step 5: Write the controller and regenerate the API types**

```powershell
cd backend; .\gradlew.bat openApiSpec
cd ..\frontend; npm run generate:api
```

springdoc orders schema properties nondeterministically — a reordering-only diff is noise, not a contract change.

- [ ] **Step 6: Run the full suite and commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey/ backend/src/main/java/co/ara/onboarding/programme/ backend/src/test/java/co/ara/onboarding/programme/ProgrammeRollupTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(programme): derive the duration-weighted rollup over visible journeys"
```

---

# Phase 3 — Customer-scoped workflow templates (Q21)

### Task 15: `customer_id` and `cloned_from_template_id` on `workflow_template`

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__customer_template.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowTemplate.java`, `WorkflowTemplateView.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/CustomerTemplateSchemaTest.java` (create)

**Interfaces:**
- Consumes: nothing new
- Produces: `WorkflowTemplate.customerId` (nullable), `WorkflowTemplate.clonedFromTemplateId` (nullable); `WorkflowTemplateView` gains both

- [ ] **Step 1: Write the failing tests**

```java
@Test
void oneCustomerHoldsAtMostOneCloneOfAGivenCatalogueTemplate() {
    insertTemplate(cloneId1, /* customerId */ acme, /* clonedFrom */ standard);
    assertThatThrownBy(() -> insertTemplate(cloneId2, acme, standard))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void twoCustomersMayEachCloneTheSameCatalogueTemplate() {
    insertTemplate(cloneId1, acme,  standard);
    assertThatNoException().isThrownBy(() -> insertTemplate(cloneId2, globex, standard));
}

@Test
void theCatalogueIsUnconstrained() {
    // Every template today has customer_id NULL. A plain UNIQUE would collapse the
    // entire catalogue into one row -- which is why the index is partial.
    insertTemplate(t1, null, null);
    assertThatNoException().isThrownBy(() -> insertTemplate(t2, null, null));
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 3A, Task 15: a catalogue template may be cloned for one customer and
-- tailored for them alone (QA Q21). NULL customer_id = the tenant catalogue, which
-- is every template that exists today.
ALTER TABLE workflow_template ADD COLUMN customer_id             uuid NULL REFERENCES customer(id);
ALTER TABLE workflow_template ADD COLUMN cloned_from_template_id uuid NULL REFERENCES workflow_template(id);

-- Provenance, NOT a propagation path: nothing follows this pointer to push an edit
-- downstream. It exists so a clone can be REFRESHED from its source (Task 17) and so
-- Q21's "one clone per customer" has something to enforce against. Without it there
-- is no path from a clone back to its catalogue template at all, and QA Q21's
-- original claim that migration bridges them is false -- MigrationService filters by
-- template_id, so it only ever moves a case between versions of the SAME template.
CREATE UNIQUE INDEX workflow_template_customer_clone_uq
    ON workflow_template (cloned_from_template_id, customer_id)
    WHERE customer_id IS NOT NULL;

CREATE INDEX workflow_template_customer_idx
    ON workflow_template (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
```

- [ ] **Step 4: Add both fields to the entity and the view** — the view too, or a client PUT-ing a template back silently blanks them.

- [ ] **Step 5: Run the full suite and commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/workflow/ backend/src/test/java/co/ara/onboarding/workflow/CustomerTemplateSchemaTest.java
git commit -m "feat(workflow): allow a template to be owned by one customer"
```

### Task 16: Clone a catalogue template for a customer

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/CustomerTemplateService.java`
- Create: `.../CloneTemplateRequest.java`, `NotCloneableException.java`, `DuplicateCloneException.java`
- Modify: `.../WorkflowController.java`, `WorkflowExceptionHandler.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/CloneTest.java` (create)

**Interfaces:**
- Consumes: `WorkflowService.createDraftVersion`'s deep-copy machinery — reuse it, do not reimplement the graph copy
- Produces: `CustomerTemplateService.clone(UUID sourceTemplateId, CloneTemplateRequest)` → `WorkflowTemplateView`

- [ ] **Step 1: Write the failing tests — all three refusals plus the write-path guard**

```java
@Test
void cloningCopiesTheSourcesCurrentPublishedVersionIntoADraft() {
    WorkflowTemplateView clone = fixture.runAs(admin,
            () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Acme Onboarding")));

    assertThat(clone.customerId()).isEqualTo(acme);
    assertThat(clone.clonedFromTemplateId()).isEqualTo(standardId);
    assertThat(versionsOf(clone.id())).singleElement()
            .extracting(WorkflowVersion::getStatus).isEqualTo(VersionStatus.DRAFT);
    assertThat(stageKeysOf(clone.id())).isEqualTo(stageKeysOf(standardId));
}

@Test
void aSourceWithNoPublishedVersionCannotBeCloned() {
    assertThatThrownBy(() -> fixture.runAs(admin,
            () -> customerTemplateService.clone(draftOnlyTemplateId, new CloneTemplateRequest(acme, "X"))))
            .isInstanceOf(NotCloneableException.class);   // 422 -- never clone a shape that was never frozen
}

@Test
void aSecondCloneOfTheSameSourceForTheSameCustomerIsRefused() {
    fixture.runAs(admin, () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "First")));
    assertThatThrownBy(() -> fixture.runAs(admin,
            () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Second"))))
            .isInstanceOf(DuplicateCloneException.class);  // 409
}

@Test
void aCustomerTemplateCannotItselfBeCloned() {
    UUID cloneId = fixture.runAs(admin,
            () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Acme"))).id();
    assertThatThrownBy(() -> fixture.runAs(admin,
            () -> customerTemplateService.clone(cloneId, new CloneTemplateRequest(globex, "Globex"))))
            .isInstanceOf(NotCloneableException.class);     // lineage stays one level deep
}

@Test
void theCustomerIdIsResolvedThroughAuthorizedQueryBeforeAnythingIsWritten() {
    assertThatThrownBy(() -> fixture.runAs(narrowAdmin,
            () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(foreignCustomer, "X"))))
            .isInstanceOf(NoSuchElementException.class);
    assertThat(templatesFor(foreignCustomer)).isEmpty();    // nothing half-written
}
```

- [ ] **Step 2: Run and watch all five fail**

- [ ] **Step 3: Implement `clone`**

Gated `@RequirePermission(PermissionKeys.WORKFLOW_MANAGE)`. Resolve the customer id through the customer facts port first. Refuse when the source has no `currentVersionId`, and when the source's own `customerId` is non-null. Record `workflow.cloned_for_customer` — `timelineVisible = false`, matching where every existing `workflow.*` action sits.

- [ ] **Step 4: Map the two exceptions**

`NotCloneableException` → 422, `DuplicateCloneException` → 409, in `WorkflowExceptionHandler` — this module's own advice, never `platform`, which must never name a domain type.

- [ ] **Step 5: Regenerate the API types, run the full suite**

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/workflow/CloneTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(workflow): clone a catalogue template for one customer"
```

### Task 17: Refresh a clone from its source

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/CustomerTemplateService.java`
- Modify: `.../WorkflowController.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/RefreshFromSourceTest.java` (create)

**Interfaces:**
- Consumes: `DraftAlreadyExistsException` (already exists — do not add a second)
- Produces: `CustomerTemplateService.refreshFromSource(UUID customerTemplateId)` → the new draft's `WorkflowVersionView`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void refreshDeepCopiesTheSourcesPublishedVersionIntoANewDraftOfTheCustomerTemplate() {
    publishANewVersionOf(standardId, /* adds */ "extra-stage");
    WorkflowVersionView draft = fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId));

    assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(draft.templateId()).isEqualTo(acmeCloneId);       // the CUSTOMER's template
    assertThat(stageKeysOf(draft.id())).contains("extra-stage");
}

@Test
void refreshReplacesRatherThanMerging() {
    // The customer's tailoring is NOT carried across. This is the stated cost of
    // Q21's answer (spec 5.2): a three-way merge would need per-node identity across
    // two lineages that Q2's freeze deliberately severs.
    tailorAndPublish(acmeCloneId, /* adds */ "acme-only-stage");
    WorkflowVersionView draft = fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId));
    assertThat(stageKeysOf(draft.id())).doesNotContain("acme-only-stage");
}

@Test
void refreshIsRefusedWhileTheCustomerTemplateAlreadyHasADraft() {
    fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId));
    assertThatThrownBy(() -> fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId)))
            .isInstanceOf(DraftAlreadyExistsException.class);
}

@Test
void aCatalogueTemplateHasNoSourceToRefreshFrom() {
    assertThatThrownBy(() -> fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(standardId)))
            .isInstanceOf(NotCloneableException.class);
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement `refreshFromSource`** — record `workflow.refreshed_from_source`, `timelineVisible = false`.

- [ ] **Step 4: Run the full suite and commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow/ backend/src/test/java/co/ara/onboarding/workflow/RefreshFromSourceTest.java
git commit -m "feat(workflow): refresh a customer clone from its catalogue source"
```

---

# Phase 4 — Milestone portal visibility (Q24)

### Task 18: `milestone_definition.portal_visible`

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__milestone_portal_visible.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/MilestoneDefinition.java`, `WorkflowDefinitionRequest.java`, `WorkflowDefinitionView.java`, `WorkflowService.java:459`, `:701`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PortalVisibilityTest.java` (create)

**Interfaces:**
- Consumes: the existing `stage.portalVisible` round-trip as the pattern to mirror exactly
- Produces: `MilestoneRequest.portalVisible` / `MilestoneView.portalVisible`, consumed by Task 21's rendering and Task 32's builder toggle

`stage.portal_visible` has existed since `V12` and is read by nothing. This adds the milestone-level twin; Task 21 becomes the first consumer of either.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aMilestoneDefinitionRoundTripsItsPortalVisibleFlag() {
    UUID versionId = createDraft();
    putDefinition(versionId, stageWith(milestone("m1", /* portalVisible */ false)));
    assertThat(getDefinition(versionId).stages().get(0).milestones().get(0).portalVisible()).isFalse();
}

@Test
void anOmittedFlagDefaultsToVisibleRatherThanHidden() {
    // Jackson binds a missing boolean key to false. A milestone that silently became
    // internal-only because a client omitted a field is the wrong failure direction:
    // it would hide work from the customer without anyone choosing to. Use Boolean
    // with an explicit null-to-true coalesce, NOT a primitive boolean -- sub-project
    // 2's live run already found this exact defect on StageRequest.autoAdvance.
    putDefinitionWithNoPortalVisibleKey(versionId);
    assertThat(getDefinition(versionId).stages().get(0).milestones().get(0).portalVisible()).isTrue();
}

@Test
void theFlagCannotBeChangedOnceItsVersionIsPublished() {
    UUID versionId = publishADraft();
    assertThatThrownBy(() -> setPortalVisibleDirectly(versionId, false))
            .hasMessageContaining("published and cannot be modified");
}

@Test
void progressIgnoresThePortalVisibleFlagEntirely() {
    // Q24: one number for every audience. Hiding a milestone must not move the bar.
    int before = progressOf(caseId);
    hideOneMilestoneInANewVersionAndMigrate(caseId);
    assertThat(progressOf(caseId)).isEqualTo(before);
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 3A, Task 18: the milestone-level twin of stage.portal_visible (QA Q24).
-- milestone_definition is a frozen-child table: refuse_published_child_write already
-- refuses every UPDATE whose parent version is not DRAFT, so this flag is authorable
-- only while a version is a draft. Every row that predates this migration takes TRUE
-- permanently -- which is correct, not a gap: retro-hiding a milestone inside a shape
-- a customer already approved is exactly what the freeze exists to prevent.
ALTER TABLE milestone_definition
    ADD COLUMN portal_visible boolean NOT NULL DEFAULT true;
```

- [ ] **Step 4: Thread the field through request, view and `WorkflowService`'s two mapping sites** — mirror `stage.portalVisible` at `WorkflowService.java:459` and `:701` exactly.

- [ ] **Step 5: Confirm `CaseEngine` was not touched**

```bash
git diff --name-only
```

Per Global Constraints, `CaseEngine` is not modified anywhere in this plan. If it appears in that list, stop and escalate.

- [ ] **Step 6: Regenerate API types, run the full suite, commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/workflow/ backend/src/test/java/co/ara/onboarding/workflow/PortalVisibilityTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(workflow): add milestone-level portal visibility"
```

---

# Phase 5 — Gate 1: the shape, approved at the customer template version (Q22)

### Task 19: `plan_shape_approval`, its entity, its permission and its fail-closed descriptor

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__plan_shape_approval.sql`
- Create: `backend/src/main/java/co/ara/onboarding/workflow/PlanShapeApproval.java`, `PlanShapeApprovalStatus.java`, `PlanShapeApprovalRepository.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/PlanShapeApprovalDescriptor.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`, `PermissionCatalog.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PlanShapeSchemaTest.java` (create)

**Interfaces:**
- Consumes: `ResourceAuthorizationDescriptor<PlanShapeApproval>`
- Produces: `PermissionKeys.PLAN_APPROVE_SHAPE` = `"plan.approve_shape"`; `PlanShapeApproval` (`versionId`, `templateId`, `customerId`, `status`, `submittedAt/By`, `decidedAt/By`, `decidedOnBehalfOf`, `decisionNote`)

This table exists — rather than as columns on `workflow_version` — because `workflow_version_frozen` refuses **every** `UPDATE` to a non-`DRAFT` row. Approval columns on the version would be unwritable by construction. The schema settled this, not a preference.

- [ ] **Step 1: Write the failing test that proves why the table exists**

```java
@Test
void approvalColumnsCouldNotHaveLivedOnTheVersionItself() {
    // Not a hypothetical: this is the constraint that forced a separate table.
    UUID versionId = publishADraft();
    assertThatThrownBy(() -> jdbc.update(
            "UPDATE workflow_version SET updated_at = now() WHERE id = ?", versionId))
            .hasMessageContaining("published and cannot be modified");
}

@Test
void aVersionMayBeSubmittedMoreThanOnceAfterARejection() {
    // Re-submission creates a NEW row; the latest row is the current state. There is
    // no unique index on version_id, deliberately.
    insertShapeApproval(versionId, "REJECTED");
    assertThatNoException().isThrownBy(() -> insertShapeApproval(versionId, "SUBMITTED"));
}

@Test
void theTableIsTenantIsolatedAndUndeletable() {
    assertThat(rlsEnabledOn("plan_shape_approval")).isTrue();
    assertThat(privilegesFor("plan_shape_approval")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 3A, Task 19: gate 1 of QA Q22 -- the customer approves the SHAPE of a
-- plan (stages, milestones, requirements, estimated durations), once per version of
-- their own template. Every journey pinned to that version inherits the approval.
--
-- A separate table rather than columns on workflow_version because
-- workflow_version_frozen refuses every UPDATE to a non-DRAFT row (V12). There is no
-- snapshot here and none is needed: publish already froze the graph and its
-- portal_visible flags, so "what shape did they approve?" is answered by reading the
-- version. Gate 2 needs a snapshot only because milestone.due_date and owner_user_id
-- are mutable runtime columns with nothing freezing them.
CREATE TABLE plan_shape_approval (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    version_id           uuid NOT NULL REFERENCES workflow_version(id),
    template_id          uuid NOT NULL REFERENCES workflow_template(id),
    customer_id          uuid NOT NULL REFERENCES customer(id),
    status               text NOT NULL,
    submitted_at         timestamptz NOT NULL,
    submitted_by         uuid NOT NULL REFERENCES app_user(id),
    decided_at           timestamptz,
    -- The user who pressed it. Internal until sub-project 7, the sponsor thereafter --
    -- and nothing about this record's shape changes when that happens.
    decided_by           uuid NULL REFERENCES app_user(id),
    -- The customer's own person, while the approver is still internal:
    -- "the sponsor approved by email, logged by the account manager".
    decided_on_behalf_of uuid NULL REFERENCES customer_contact(id),
    decision_note        text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT plan_shape_approval_status_ck CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
    CONSTRAINT plan_shape_approval_decided_ck CHECK (
        (status = 'SUBMITTED' AND decided_at IS NULL AND decided_by IS NULL)
     OR (status <> 'SUBMITTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX plan_shape_approval_version_idx ON plan_shape_approval (version_id, submitted_at DESC);

SELECT enable_tenant_rls('plan_shape_approval');
GRANT SELECT, INSERT, UPDATE ON plan_shape_approval TO onboarding_app;
```

- [ ] **Step 4: Catalogue the permission**

```java
add(PLAN_APPROVE_SHAPE, "plan", null, "Record the customer's decision on a plan's shape", ALL_ONLY);
```

ALL-only because a workflow version is not a record-scoped resource. Inventing a scope for it would be fiction.

- [ ] **Step 5: Write the fail-closed descriptor**

```java
/**
 * The one descriptor in this sub-project that does NOT delegate to a parent record
 * predicate, because its parent is a workflow version and workflow permissions are
 * catalogued ALL-only throughout. It fails closed: DEPARTMENT, TEAM and ASSIGNED all
 * match nothing, so only an ALL-scoped holder ever reads one. Returning
 * cb.conjunction() here instead would be a silent, total bypass -- the exact failure
 * the ResourceAuthorizationDescriptor contract warns about.
 */
@Component
public class PlanShapeApprovalDescriptor implements ResourceAuthorizationDescriptor<PlanShapeApproval> {
    @Override public String resourceType() { return "plan_shape_approval"; }
    @Override public Class<PlanShapeApproval> entityType() { return PlanShapeApproval.class; }
    @Override public Set<RelationshipType> assignedRelationships() { return Set.of(); }
    @Override public Specification<PlanShapeApproval> departmentScope(AuthContext c) { return (r,q,cb) -> cb.disjunction(); }
    @Override public Specification<PlanShapeApproval> teamScope(AuthContext c)       { return (r,q,cb) -> cb.disjunction(); }
    @Override public Specification<PlanShapeApproval> assignedScope(AuthContext c)   { return (r,q,cb) -> cb.disjunction(); }
}
```

- [ ] **Step 6: Run the full suite and commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/workflow/ backend/src/main/java/co/ara/onboarding/scoping/ backend/src/main/java/co/ara/onboarding/authz/ backend/src/test/java/co/ara/onboarding/workflow/PlanShapeSchemaTest.java
git commit -m "feat(workflow): add the shape-approval table, permission and descriptor"
```

### Task 20: Submit and decide a shape approval

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/PlanShapeService.java`
- Create: `.../DecidePlanRequest.java`, `PlanGateException.java`
- Modify: `.../WorkflowController.java`, `WorkflowExceptionHandler.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PlanShapeGateTest.java` (create)

**Interfaces:**
- Consumes: `PlanShapeApprovalRepository`, `AuthorizedQuery`, `AuditRecorder`
- Produces: `PlanShapeService.submit(UUID versionId)`, `decide(UUID versionId, DecidePlanRequest)`, and `currentApproval(UUID versionId)` → `Optional<PlanShapeApprovalView>` — Task 24 calls the last one to enforce its ordering rule

`DecidePlanRequest(PlanDecision outcome, String note, UUID decidedOnBehalfOfContactId)` where `PlanDecision` is `APPROVED` or `REJECTED`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aDraftVersionCannotBeSubmittedForApproval() {
    // You cannot approve a shape that can still change, and a DRAFT can.
    assertThatThrownBy(() -> fixture.runAs(admin, () -> planShapeService.submit(draftVersionId)))
            .isInstanceOf(PlanGateException.class);       // 422
}

@Test
void aCatalogueVersionCannotBeSubmittedForApproval() {
    // The whole two-gate story is defined at the customer tier.
    assertThatThrownBy(() -> fixture.runAs(admin, () -> planShapeService.submit(cataloguePublishedVersionId)))
            .isInstanceOf(PlanGateException.class);       // 422
}

@Test
void decidingRecordsWhoPressedItAndWhoTheyPressedItFor() {
    fixture.runAs(accountManager, () -> planShapeService.submit(customerVersionId));
    fixture.runAs(accountManager, () -> planShapeService.decide(customerVersionId,
            new DecidePlanRequest(PlanDecision.APPROVED, "Approved by email 2026-09-09", sponsorContactId)));

    PlanShapeApprovalView current = fixture.runAs(admin, () -> planShapeService.currentApproval(customerVersionId)).orElseThrow();
    assertThat(current.status()).isEqualTo(PlanShapeApprovalStatus.APPROVED);
    assertThat(current.decidedBy()).isEqualTo(accountManager.id());       // sub-project 7 makes this the sponsor
    assertThat(current.decidedOnBehalfOf()).isEqualTo(sponsorContactId);  // and this null
}

@Test
void aDecisionIsOneShot() {
    fixture.runAs(accountManager, () -> planShapeService.submit(customerVersionId));
    fixture.runAs(accountManager, () -> planShapeService.decide(customerVersionId, approve()));
    assertThatThrownBy(() -> fixture.runAs(accountManager, () -> planShapeService.decide(customerVersionId, approve())))
            .isInstanceOf(PlanGateException.class);
}

@Test
void resubmittingAfterARejectionStartsANewApprovalAndTheLatestRowWins() {
    fixture.runAs(accountManager, () -> planShapeService.submit(customerVersionId));
    fixture.runAs(accountManager, () -> planShapeService.decide(customerVersionId, reject()));
    fixture.runAs(accountManager, () -> planShapeService.submit(customerVersionId));

    assertThat(fixture.runAs(admin, () -> planShapeService.currentApproval(customerVersionId)).orElseThrow().status())
            .isEqualTo(PlanShapeApprovalStatus.SUBMITTED);
}
```

- [ ] **Step 2: Run and watch all five fail**

- [ ] **Step 3: Implement the service**

`submit` gated `@RequirePermission(WORKFLOW_MANAGE)` — submitting is authoring, so it needs no new key. `decide` gated `@RequirePermission(PLAN_APPROVE_SHAPE)`. `currentApproval` returns the most recent row by `submittedAt`.

Two audit actions, both `timelineVisible = true` — the customer's own side of the story depends on them:

```java
public static final AuditAction PLAN_SHAPE_SUBMITTED = of("plan.shape_submitted", true);
public static final AuditAction PLAN_SHAPE_DECIDED   = of("plan.shape_decided", true);
```

- [ ] **Step 4: Map `PlanGateException` → 422** in `WorkflowExceptionHandler`.

- [ ] **Step 5: Regenerate API types, run the full suite, commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/workflow/PlanShapeGateTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(workflow): submit and decide a plan's shape approval"
```

### Task 21: The approved artifact — the portal-visible rendering

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/PlanShapeService.java`
- Create: `.../PlanShapeView.java`, `PlanShapeStageView.java`, `PlanShapeMilestoneView.java`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PlanShapeRenderingTest.java` (create)

**Interfaces:**
- Consumes: `stage.portalVisible` (existing since `V12`) and `milestoneDefinition.portalVisible` (Task 18)
- Produces: `PlanShapeService.render(UUID versionId)` → `PlanShapeView(PlanShapeApprovalView approval, List<PlanShapeStageView> stages)`, served by `GET /workflows/{id}/versions/{vid}/plan`

**This is the first thing in the codebase that reads either `portal_visible` flag.** Both have been authored and inert since sub-project 2.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theRenderingOmitsInternalOnlyStagesAndMilestones() {
    UUID versionId = publishVersionWith(
            stage("s1", /* portalVisible */ true,  milestone("m1", true), milestone("m2", /* internal */ false)),
            stage("s2", /* portalVisible */ false, milestone("m3", true)));

    PlanShapeView rendered = fixture.runAs(admin, () -> planShapeService.render(versionId));

    assertThat(rendered.stages()).extracting(PlanShapeStageView::key).containsExactly("s1");
    assertThat(rendered.stages().get(0).milestones()).extracting(PlanShapeMilestoneView::key)
            .containsExactly("m1");
}

@Test
void anInternalOnlyStageHidesItsMilestonesEvenWhenTheyAreVisibleThemselves() {
    // m3 is portal_visible but its stage is not. A milestone the customer cannot
    // reach must not appear in the artifact they are asked to approve.
    assertThat(renderedKeys(versionId)).doesNotContain("m3");
}

@Test
void theRenderingCarriesEstimatedDurationsButNoDatesOrOwners() {
    // Gate 1 approves a SHAPE and a duration. Dates and owners are runtime columns on
    // the case and belong to gate 2 -- a rendering that showed them would be
    // promising the customer a schedule at the wrong gate.
    Set<String> fields = componentNames(PlanShapeMilestoneView.class);
    assertThat(fields).contains("estimatedDurationDays");
    assertThat(fields).doesNotContain("dueDate", "ownerUserId");
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement `render`**, gated `@RequirePermission(WORKFLOW_VIEW)`. Filter stages by `portalVisible`, then their milestones by `portalVisible`. A hidden stage drops its whole subtree.

- [ ] **Step 4: Wire `GET /workflows/{id}/versions/{vid}/plan`** to return approval state **and** rendering in one response — the rendering *is* the artifact, so a client that had to fetch them separately could show an approval next to the wrong plan.

- [ ] **Step 5: Regenerate API types, run the full suite, commit**

```bash
git add backend/src/main/java/co/ara/onboarding/workflow/ backend/src/test/java/co/ara/onboarding/workflow/PlanShapeRenderingTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(workflow): render the portal-visible plan as the approved artifact"
```

---

# Phase 6 — Gate 2: the schedule, snapshotted and approved per journey (Q22, Q23)

### Task 22: `plan_revision` and `plan_revision_item`, the second append-only table in the codebase

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__plan_revision.sql`
- Create: `backend/src/main/java/co/ara/onboarding/journey/PlanRevision.java`, `PlanRevisionStatus.java`, `PlanRevisionRepository.java`, `PlanRevisionItem.java`, `PlanRevisionItemRepository.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/PlanSnapshotImmutabilityTest.java` (create)

**Interfaces:**
- Consumes: nothing new
- Produces: `PlanRevision` (`caseId`, `revisionNumber`, `status`, `issuedAt/By`, `issueNote`, `decidedAt/By`, `decidedOnBehalfOf`, `decisionNote`); `PlanRevisionItem` (`planRevisionId`, `caseId`, `milestoneId`, `milestoneDefinitionId`, `stageName`, `milestoneName`, `dueDate`, `ownerUserId`, `estimatedDurationDays`, `portalVisible`, `sortOrder`)

- [ ] **Step 1: Write the failing immutability test, and prove it red**

```java
@Test
void aSnapshotItemCannotBeUpdated() {
    // audit_event's argument, applied to a second table: a snapshot the application
    // can rewrite is not a snapshot. "What did we send on 15 October?" has to have an
    // answer, for governance packs and for disputes.
    UUID itemId = insertRevisionItem(revisionId, LocalDate.of(2026, 10, 15));
    assertThatThrownBy(() -> jdbc.update(
            "UPDATE plan_revision_item SET due_date = ? WHERE id = ?", LocalDate.now(), itemId))
            .hasMessageContaining("permission denied");
}

@Test
void aSnapshotItemCannotBeDeleted() {
    assertThatThrownBy(() -> jdbc.update("DELETE FROM plan_revision_item WHERE id = ?", itemId))
            .hasMessageContaining("permission denied");
}

@Test
void theRevisionItselfMayStillTransition() {
    // plan_revision KEEPS its UPDATE grant -- its status genuinely transitions from
    // ISSUED to APPROVED/REJECTED/SUPERSEDED. Only the captured ITEMS are frozen.
    assertThatNoException().isThrownBy(() ->
            jdbc.update("UPDATE plan_revision SET status = 'APPROVED' WHERE id = ?", revisionId));
}

@Test
void revisionNumbersAreUniquePerCase() {
    insertRevision(caseId, 1);
    assertThatThrownBy(() -> insertRevision(caseId, 1))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: Run and watch them fail** — the tables do not exist.

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 3A, Task 22: gate 2 of QA Q22 -- the customer approves the SCHEDULE
-- (calendar dates and named owners), per journey, as a dated snapshot revision.
--
-- Typed rows rather than a jsonb blob for three reasons: diffing revision N against
-- N-1 is a join instead of JSON extraction in application code; Q28's "milestones
-- closed in the interval" is a query; and V12's own comment already rejects a JSON
-- bag in favour of typed columns.
CREATE TABLE plan_revision (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    case_id              uuid NOT NULL REFERENCES onboarding_case(id),
    revision_number      integer NOT NULL,
    status               text NOT NULL,
    issued_at            timestamptz NOT NULL,
    issued_by            uuid NOT NULL REFERENCES app_user(id),
    issue_note           text,
    decided_at           timestamptz,
    decided_by           uuid NULL REFERENCES app_user(id),
    decided_on_behalf_of uuid NULL REFERENCES customer_contact(id),
    decision_note        text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT plan_revision_status_ck CHECK (
        status IN ('ISSUED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT plan_revision_number_ck CHECK (revision_number > 0)
);
CREATE UNIQUE INDEX plan_revision_case_number_uq ON plan_revision (case_id, revision_number);
-- At most one outstanding revision per case: issuing a new one supersedes the old.
CREATE UNIQUE INDEX plan_revision_one_outstanding_uq
    ON plan_revision (case_id) WHERE status = 'ISSUED';

CREATE TABLE plan_revision_item (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant(id),
    plan_revision_id        uuid NOT NULL REFERENCES plan_revision(id),
    -- Denormalised so PlanRevisionItemDescriptor is one subquery hop to
    -- onboarding_case instead of a chain -- the reason milestone and comment both
    -- carry case_id too.
    case_id                 uuid NOT NULL REFERENCES onboarding_case(id),
    milestone_id            uuid NOT NULL REFERENCES milestone(id),
    milestone_definition_id uuid NOT NULL REFERENCES milestone_definition(id),
    stage_name              text NOT NULL,
    milestone_name          text NOT NULL,
    due_date                date,
    owner_user_id           uuid NULL REFERENCES app_user(id),
    estimated_duration_days integer NOT NULL,
    portal_visible          boolean NOT NULL,
    sort_order              integer NOT NULL,
    created_at              timestamptz NOT NULL
);
CREATE INDEX plan_revision_item_revision_idx ON plan_revision_item (plan_revision_id, sort_order);

SELECT enable_tenant_rls('plan_revision');
SELECT enable_tenant_rls('plan_revision_item');

GRANT SELECT, INSERT, UPDATE ON plan_revision TO onboarding_app;
-- No UPDATE and no DELETE. The second append-only table in this codebase, after
-- audit_event, and for the same reason: a snapshot the application can rewrite is
-- not evidence of what was sent.
GRANT SELECT, INSERT ON plan_revision_item TO onboarding_app;
```

Note there is no `updated_at` on `plan_revision_item` — a column that can never change has no business carrying a timestamp claiming it did.

- [ ] **Step 4: Write the entities**, `PlanRevisionItem` with no setters beyond construction, so the Java layer says the same thing the GRANT does.

- [ ] **Step 5: Run the immutability test and confirm it now PASSES for the right reason** — `permission denied`, from the database, not an application check.

- [ ] **Step 6: Run the full suite and commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/main/java/co/ara/onboarding/journey/ backend/src/test/java/co/ara/onboarding/journey/PlanSnapshotImmutabilityTest.java
git commit -m "feat(journey): add append-only plan revision snapshot tables"
```

### Task 23: Gate 2's two permissions and two descriptors

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`, `PermissionCatalog.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/PlanRevisionDescriptor.java`, `PlanRevisionItemDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/DescriptorRegistryTest.java` (extend)

**Interfaces:**
- Produces: `PermissionKeys.PLAN_ISSUE` = `"plan.issue"`, `PLAN_APPROVE_SCHEDULE` = `"plan.approve_schedule"`

- [ ] **Step 1: Catalogue both at RECORD on `onboarding_case`**

```java
add(PLAN_ISSUE,            "plan", "onboarding_case", "Issue a schedule revision for a journey", RECORD);
add(PLAN_APPROVE_SCHEDULE, "plan", "onboarding_case", "Record the customer's decision on a schedule revision", RECORD);
```

Both are record-scoped on the case because both act on one journey — unlike gate 1, which has no record to scope against.

- [ ] **Step 2: Write both descriptors, delegating via `case_id`**

Both mirror `TaskDescriptor.viaCase` exactly: DEPARTMENT reads the case's `owningDepartmentId`, TEAM its `owningTeamId`, ASSIGNED its `case_participant` rows. They exist because `AuthorizedQuery` dispatches by **entity type**, not because `validate()` demands them.

- [ ] **Step 3: Run `RoleTemplateCoverageTest` from Phase 1 Task 8 and watch it fail**

Expected: FAIL listing `plan.issue` and `plan.approve_schedule` as catalogued at several scopes but granted only to Administrator. **This is the guard doing its job** — it is why Task 8 wrote it derivably rather than as a typed list.

- [ ] **Step 4: Seed both to the Project Manager and Account Manager templates**, at TEAM and DEPARTMENT respectively.

- [ ] **Step 5: Run the full suite and commit**

```bash
git add backend/src/main/java/co/ara/onboarding/authz/ backend/src/main/java/co/ara/onboarding/scoping/ backend/src/test/java/co/ara/onboarding/authz/
git commit -m "feat(authz): catalogue and seed the two schedule-gate permissions"
```

### Task 24: Issue a schedule revision

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/PlanRevisionService.java`
- Create: `.../IssueRevisionRequest.java`, `PlanRevisionView.java`, `PlanRevisionItemView.java`
- Modify: `.../JourneyExceptionHandler.java`, `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/PlanRevisionTest.java` (create)

**Interfaces:**
- Consumes: `workflow.PlanShapeService.currentApproval(UUID versionId)` from Task 20 — `journey → workflow` already exists and is allowed
- Produces: `PlanRevisionService.issue(UUID caseId, IssueRevisionRequest)` → `PlanRevisionView`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void issuingSnapshotsEveryPortalVisibleMilestoneAsItStandsNow() {
    PlanRevisionView rev = fixture.runAs(pm, () -> planRevisionService.issue(caseId, new IssueRevisionRequest("v1")));

    assertThat(rev.revisionNumber()).isEqualTo(1);
    assertThat(rev.items()).extracting(PlanRevisionItemView::milestoneName)
            .containsExactly("Kickoff", "Go live");        // the internal-only one is absent
    assertThat(rev.items().get(0).dueDate()).isEqualTo(currentDueDateOf("Kickoff"));
}

@Test
void aLaterDateChangeDoesNotAlterAnIssuedRevision() {
    // The whole point of the snapshot. Without this, "what did we send?" reads back
    // today's dates and the record proves nothing.
    PlanRevisionView rev = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));
    LocalDate captured = rev.items().get(0).dueDate();
    fixture.runAs(pm, () -> milestoneService.update(kickoffId, rescheduleTo(captured.plusDays(30))));

    assertThat(fixture.runAs(pm, () -> planRevisionService.get(rev.id())).items().get(0).dueDate())
            .isEqualTo(captured);
}

@Test
void issuingASecondRevisionSupersedesTheOutstandingOne() {
    PlanRevisionView first = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));
    PlanRevisionView second = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));

    assertThat(fixture.runAs(pm, () -> planRevisionService.get(first.id())).status())
            .isEqualTo(PlanRevisionStatus.SUPERSEDED);
    assertThat(second.revisionNumber()).isEqualTo(2);
}

@Test
void aRevisionCannotBeIssuedUntilTheShapeIsApproved() {
    // Gate 1 blocks nothing at runtime; it gets its teeth from THIS ordering rule
    // instead of a second hold (spec 5.3).
    UUID unapprovedCase = openCaseOnCustomerTemplateWithNoShapeApproval();
    assertThatThrownBy(() -> fixture.runAs(pm, () -> planRevisionService.issue(unapprovedCase, note())))
            .isInstanceOf(PlanGateException.class);       // 422
}
```

- [ ] **Step 2: Run and watch all four fail**

- [ ] **Step 3: Implement `issue`**

Gated `@RequirePermission(PLAN_ISSUE)`. Resolve `caseId` through `AuthorizedQuery` first. Consult `PlanShapeService.currentApproval` for the case's pinned version and refuse unless `APPROVED`. Mark any `ISSUED` revision `SUPERSEDED` **before** inserting the new one, so the partial unique index never sees two.

Record `plan.revision_issued`, `timelineVisible = true`.

- [ ] **Step 4: Run the full suite and commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/journey/PlanRevisionTest.java
git commit -m "feat(journey): issue a dated schedule revision as an immutable snapshot"
```

### Task 25: Decide a revision, and release the hold on the first approval

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/journey/PlanRevisionService.java`, `CaseService.java` (`resume`)
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/CauseBeforeEffectTest.java` (extend)
- Test: `backend/src/test/java/co/ara/onboarding/journey/PlanRevisionTest.java` (extend)

**Interfaces:**
- Consumes: `CaseService.resume`'s existing hold-accounting path — call it, do not reimplement `total_hold_days`
- Produces: `PlanRevisionService.decide(UUID revisionId, DecidePlanRequest)` → `PlanRevisionView`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theFirstApprovalReleasesTheHoldAndAccruesTheDaysWaited() {
    UUID caseId = openCaseOnCustomerTemplate();               // starts ON_HOLD (Task 26)
    clock.advance(Duration.ofDays(3));
    PlanRevisionView rev = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));
    fixture.runAs(am, () -> planRevisionService.decide(rev.id(), approve()));

    CaseView c = fixture.runAs(pm, () -> caseService.get(caseId));
    assertThat(c.status()).isEqualTo(CaseStatus.ACTIVE);
    assertThat(c.heldAt()).isNull();
    // Q8's SLA pause is correct for free because this goes through resume's own path.
    assertThat(c.totalHoldDays()).isGreaterThanOrEqualTo(1);
}

@Test
void aLaterRevisionIsAdvisoryAndDoesNotReHoldTheJourney() {
    // Q23: re-holding on every revision means an internal typo correction freezes a
    // live project until the customer replies.
    approveFirstRevision(caseId);
    PlanRevisionView second = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));
    assertThat(fixture.runAs(pm, () -> caseService.get(caseId)).status()).isEqualTo(CaseStatus.ACTIVE);
    fixture.runAs(am, () -> planRevisionService.decide(second.id(), reject()));
    assertThat(fixture.runAs(pm, () -> caseService.get(caseId)).status()).isEqualTo(CaseStatus.ACTIVE);
}

@Test
void theDecisionIsRecordedBeforeTheResumeItCauses() {
    approveFirstRevision(caseId);
    List<String> actions = auditActionsInOrderFor("onboarding_case", caseId);
    assertThat(actions.indexOf("plan.revision_decided")).isLessThan(actions.indexOf("case.resumed"));
}

@Test
void decidingARevisionThatIsNotOutstandingIsRefused() {
    PlanRevisionView first = fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));
    fixture.runAs(pm, () -> planRevisionService.issue(caseId, note()));   // supersedes `first`
    assertThatThrownBy(() -> fixture.runAs(am, () -> planRevisionService.decide(first.id(), approve())))
            .isInstanceOf(PlanGateException.class);
}
```

The third test goes in `CauseBeforeEffectTest`, not `PlanRevisionTest` — it is that guard's subject, and nine `journey` call sites already got this wrong once, permanently misordering every audit row written before 2026-08-29.

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement `decide`**

Gated `@RequirePermission(PLAN_APPROVE_SCHEDULE)`. Refuse unless the revision's status is `ISSUED`. Record `plan.revision_decided` (`timelineVisible = true`) **first**, then — only when this is the case's first `APPROVED` revision — call `CaseService.resume`.

- [ ] **Step 4: Run the full suite and commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey/ backend/src/main/java/co/ara/onboarding/audit/AuditActions.java backend/src/test/java/co/ara/onboarding/journey/
git commit -m "feat(journey): release the plan hold on a journey's first schedule approval"
```

### Task 26: Hold a customer-template journey at creation, and refuse a manual resume

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseService.java` (`create`, `resume`)
- Create: `.../PlanApprovalOutstandingException.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/PlanHoldTest.java` (create)

**Interfaces:**
- Consumes: `CaseStatus.ON_HOLD`, `CaseOnHoldException`, `Case.heldAt` — all pre-existing since `V13`
- Produces: nothing new for later tasks; this closes Q23

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aJourneyOnACustomerTemplateStartsHeld() {
    CaseView c = fixture.runAs(pm, () -> caseService.create(onCustomerTemplate()));
    assertThat(c.status()).isEqualTo(CaseStatus.ON_HOLD);
    assertThat(c.heldAt()).isNotNull();
    assertThat(auditActionsFor("onboarding_case", c.id())).contains("case.held");
}

@Test
void aJourneyOnACatalogueTemplateIsUnaffected() {
    // Every existing flow and e2e spec depends on this. The gate is real precisely
    // where a customer-tailored plan exists, and nowhere else.
    CaseView c = fixture.runAs(pm, () -> caseService.create(onCatalogueTemplate()));
    assertThat(c.status()).isEqualTo(CaseStatus.ACTIVE);
    assertThat(c.heldAt()).isNull();
}

@Test
void noRequirementCanBeSatisfiedWhileAwaitingTheFirstApproval() {
    UUID caseId = fixture.runAs(pm, () -> caseService.create(onCustomerTemplate())).id();
    assertThatThrownBy(() -> fixture.runAs(pm, () -> requirementService.satisfy(firstRequirementOf(caseId), satisfy())))
            .isInstanceOf(CaseOnHoldException.class);      // the EXISTING mechanism, not a new one
}

@Test
void aManualResumeCannotBeUsedToBypassTheGate() {
    UUID caseId = fixture.runAs(pm, () -> caseService.create(onCustomerTemplate())).id();
    assertThatThrownBy(() -> fixture.runAs(admin, () -> caseService.resume(caseId)))
            .isInstanceOf(PlanApprovalOutstandingException.class);   // 409
}

@Test
void aManualHoldLayeredOnTopStillRefusesResumeForItsOwnReason() {
    // The condition is derived, so both reasons compose: clearing one does not clear
    // the other.
    UUID caseId = approvedCustomerJourney();
    fixture.runAs(admin, () -> caseService.hold(caseId, "Waiting on legal"));
    assertThatNoException().isThrownBy(() -> fixture.runAs(admin, () -> caseService.resume(caseId)));
}
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement both halves**

In `create`: when the pinned version's template has a non-null `customerId`, set `status = ON_HOLD` and `heldAt`, and record `case.held` with the fixed reason `"Awaiting first plan approval"` so the trail distinguishes it from a manual hold.

In `resume`: refuse with `PlanApprovalOutstandingException` when the case is on a customer-owned template **and** no `APPROVED` revision exists. Derived — **do not add a column**.

- [ ] **Step 4: Map the exception → 409** in `JourneyExceptionHandler`.

- [ ] **Step 5: Run the full suite — pay attention to existing journey tests**

Any test that opens a case on a customer template will now start held. If one breaks, check whether it *should* have been on a catalogue template; fix the fixture, never the guard.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey/ backend/src/test/java/co/ara/onboarding/journey/PlanHoldTest.java
git commit -m "feat(journey): hold a customer-template journey until its first approval"
```

### Task 27: The revision diff

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/journey/PlanRevisionService.java`, `PlanRevisionController.java`
- Create: `.../PlanRevisionDiffView.java`, `PlanRevisionDiffRowView.java`
- Test: `backend/src/test/java/co/ara/onboarding/journey/PlanRevisionDiffTest.java` (create)

**Interfaces:**
- Produces: `PlanRevisionService.diff(UUID revisionId, UUID againstRevisionId)` → `PlanRevisionDiffView(List<PlanRevisionDiffRowView> rows)`, where each row carries `milestoneName`, `previousDueDate`, `currentDueDate`, `previousOwnerUserId`, `currentOwnerUserId`, `changeKind` (`ADDED`, `REMOVED`, `DATE_CHANGED`, `OWNER_CHANGED`, `UNCHANGED`)

Computed **server-side**: the typed snapshot rows are the reason we chose them over a JSON blob, and recomputing the diff per client would let two clients disagree about what changed.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aShiftedDateShowsAsDateChangedWithBothValues() {
    PlanRevisionDiffView d = fixture.runAs(pm, () -> planRevisionService.diff(rev2, rev1));
    assertThat(rowFor(d, "Kickoff").changeKind()).isEqualTo(ChangeKind.DATE_CHANGED);
    assertThat(rowFor(d, "Kickoff").previousDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(rowFor(d, "Kickoff").currentDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
}

@Test
void aMilestoneMadeInternalBetweenRevisionsShowsAsRemoved() {
    // It left the customer-visible plan. That is a change the sponsor is entitled to
    // see, not a silent disappearance.
    assertThat(rowFor(diff(rev2, rev1), "Internal staging").changeKind()).isEqualTo(ChangeKind.REMOVED);
}

@Test
void diffingARevisionAgainstItselfReportsEveryRowUnchanged() {
    assertThat(fixture.runAs(pm, () -> planRevisionService.diff(rev1, rev1)).rows())
            .allMatch(r -> r.changeKind() == ChangeKind.UNCHANGED);
}

@Test
void aRevisionFromAnotherCaseCannotBeDiffedAgainst() {
    assertThatThrownBy(() -> fixture.runAs(pm, () -> planRevisionService.diff(rev1, revisionOfAnotherCase)))
            .isInstanceOf(NoSuchElementException.class);
}
```

The last test matters: `againstRevisionId` is an id from a query string, so it carries the same write-path-style resolution obligation as anything else — resolve it through `AuthorizedQuery` and confirm it belongs to the same case.

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Implement `diff`**, matching rows by `milestoneDefinitionId` — not by name, which is mutable, and not by `milestoneId`, which a migration can repoint.

- [ ] **Step 4: Regenerate API types, run the full suite, commit**

```bash
git add backend/src/main/java/co/ara/onboarding/journey/ backend/src/test/java/co/ara/onboarding/journey/PlanRevisionDiffTest.java frontend/src/lib/api/generated.ts
git commit -m "feat(journey): diff two schedule revisions server-side"
```

---

# Phase 7 — Frontend

**Invoke `frontend-design` and `ui-ux-pro-max` before every task in this phase.** The design bundle covers none of these screens; extend its tokens and components rather than inventing a second visual language. Every user-facing string goes through `t()`.

### Task 28: API hooks for programmes and plans

**Files:**
- Create: `frontend/src/lib/api/programmes.ts`, `frontend/src/lib/api/plans.ts`
- Test: `frontend/src/lib/api/__tests__/programmes.test.ts` (create)

**Interfaces:**
- Consumes: `frontend/src/lib/api/generated.ts` — regenerated by Tasks 14, 16, 21, 27. **Do not hand-write a request or response type.**
- Produces: `useProgrammes(customerId?)`, `useProgramme(id)`, `useCreateProgramme()`, `useUpdateProgramme()`, `useDeactivateProgramme()`, `useAddJourney()`, `useRemoveJourney()`, `useAddProgrammeParticipant()`, `useRemoveProgrammeParticipant()`; `useShapeApproval(templateId, versionId)`, `useSubmitShape()`, `useDecideShape()`, `usePlanRevisions(caseId)`, `useIssueRevision()`, `useDecideRevision()`, `useRevisionDiff(revisionId, againstId)`

- [ ] **Step 1: Write the failing test**

```ts
it("invalidates the programme detail and the case list after adding a journey", async () => {
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const { result } = renderHook(() => useAddJourney(), { wrapper });
  await act(() => result.current.mutateAsync({ programmeId: "p1", caseId: "c1" }));
  expect(invalidate).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p1") });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: caseKeys.all });
});
```

- [ ] **Step 2: Run and watch it fail**

- [ ] **Step 3: Write both modules**, following `tasks.ts`'s existing `taskKeys` structure for the query-key factories.

- [ ] **Step 4: Run `npx vitest run` and commit**

```bash
git add frontend/src/lib/api/
git commit -m "feat(frontend): add programme and plan API hooks"
```

### Task 29: The programme index and detail screens

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/programmes/page.tsx`, `.../[id]/page.tsx`
- Create: `frontend/src/components/programme/ProgrammeDetail.tsx`, `ProgrammeRollupBar.tsx`, `ProgrammeJourneyList.tsx`, `ProgrammeParticipants.tsx`
- Modify: `frontend/src/components/shell/Sidebar.tsx` (add the nav entry beside `nav.customers`, ~line 106), `frontend/src/lib/i18n/i18n.test.ts` (its key list is asserted)
- Test: `frontend/src/components/programme/__tests__/ProgrammeRollupBar.test.tsx`, `ProgrammeDetail.test.tsx` (create)

**Interfaces:**
- Consumes: `useProgramme(id)` from Task 28; `ProgrammeDetailView` from `generated.ts`
- Produces: the first screen in this sub-project

- [ ] **Step 1: Write the failing tests**

```tsx
it("states how many journeys the rollup covers, in words", () => {
  render(<ProgrammeRollupBar percent={62} journeysCovered={3} />);
  // A scope-limited view must read as partial rather than as wrong (spec 6.4).
  expect(screen.getByText("62%")).toBeInTheDocument();
  expect(screen.getByText("across 3 journeys")).toBeInTheDocument();
});

it("renders an empty state, not a zero bar, when no journey is visible", () => {
  render(<ProgrammeRollupBar percent={0} journeysCovered={0} />);
  expect(screen.getByText("No journeys you can see")).toBeInTheDocument();
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});

it("pairs every journey status colour with a word", () => {
  render(<ProgrammeJourneyList journeys={[{ ...j, status: "ON_HOLD" }]} />);
  expect(screen.getByText("On hold")).toBeInTheDocument();
});
```

The third test enforces binding decision 4 — colour is never the only signal — as a test rather than as a hope.

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Build the screens**

Percent, journey counts and dates are machine values: **Spline Sans Mono**. Cards are flat. Supply the empty, loading (skeleton) and error states the bundle never covers.

- [ ] **Step 4: Run `npx vitest run`, then look at it in a browser**

```powershell
cd frontend; npm run dev
```

Never run `npm run build` or switch branches while a dev server is live — it 404s the stylesheet and reads as an app bug.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/ frontend/src/components/programme/ frontend/src/components/layout/Sidebar.tsx frontend/src/lib/i18n/en.ts
git commit -m "feat(frontend): add the programme index and detail screens"
```

### Task 30: Customer templates on the workflows screen

**Files:**
- Modify: `frontend/src/app/(app)/t/[slug]/admin/workflows/page.tsx`
- Create: `frontend/src/components/workflow/CloneTemplateDialog.tsx`, `RefreshFromSourceDialog.tsx`
- Test: `frontend/src/components/workflow/__tests__/RefreshFromSourceDialog.test.tsx` (create)

**Interfaces:**
- Consumes: `useCloneTemplate()`, `useRefreshFromSource()` from Task 28
- Produces: the operator path to Q21

- [ ] **Step 1: Write the failing test**

```tsx
it("says plainly that tailoring is not carried across before refreshing", async () => {
  render(<RefreshFromSourceDialog template={acmeClone} open />);
  expect(screen.getByText(/will not carry across your changes/i)).toBeInTheDocument();
  // The confirm button stays disabled until the warning is acknowledged: refresh
  // REPLACES (spec 5.2), and discovering that afterwards means re-tailoring by hand.
  expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
});
```

- [ ] **Step 2: Run and watch it fail**

- [ ] **Step 3: Build both dialogs and the list segmentation** — catalogue templates and customer templates in labelled groups, with a provenance line on each clone ("Cloned from Standard Onboarding · 12 Aug", the date in mono).

- [ ] **Step 4: Run `npx vitest run` and commit**

```bash
git add frontend/src/app/ frontend/src/components/workflow/ frontend/src/lib/i18n/en.ts
git commit -m "feat(frontend): clone and refresh customer templates from the workflows screen"
```

### Task 31: The two approval gates, and the held-case banner

**Files:**
- Create: `frontend/src/components/workflow/ShapeApprovalPanel.tsx`
- Create: `frontend/src/components/journey/PlanTab.tsx`, `PlanRevisionList.tsx`, `PlanRevisionDiff.tsx`, `AwaitingApprovalBanner.tsx`
- Modify: `frontend/src/app/(app)/t/[slug]/admin/workflows/[id]/versions/[vid]/page.tsx`, the case workspace tab strip
- Test: `frontend/src/components/journey/__tests__/AwaitingApprovalBanner.test.tsx`, `PlanTab.test.tsx` (create)

**Interfaces:**
- Consumes: `useShapeApproval`, `usePlanRevisions`, `useIssueRevision`, `useDecideRevision`, `useRevisionDiff`
- Produces: the last screens in this sub-project

- [ ] **Step 1: Write the failing tests**

```tsx
it("tells a held journey WHY it is inert and offers the action", () => {
  render(<AwaitingApprovalBanner caseId="c1" hasOutstandingRevision={false} />);
  // A case that silently refuses every checkbox is the worst thing this sub-project
  // could ship.
  expect(screen.getByText(/waiting for the customer to approve the schedule/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Issue a schedule revision" })).toBeInTheDocument();
});

it("previews exactly what will be snapshotted before issuing", async () => {
  render(<PlanTab caseId="c1" milestones={[visible, internalOnly]} />);
  await userEvent.click(screen.getByRole("button", { name: "Issue a schedule revision" }));
  expect(screen.getByText("Kickoff")).toBeInTheDocument();
  expect(screen.queryByText("Internal staging")).not.toBeInTheDocument();
});

it("shows a rejected shape approval as blocking, with the reason", () => {
  render(<ShapeApprovalPanel approval={{ status: "REJECTED", decisionNote: "Dates too aggressive" }} />);
  expect(screen.getByText("Rejected")).toBeInTheDocument();
  expect(screen.getByText("Dates too aggressive")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Build all five components**

The gate-1 panel shows the portal-visible rendering as the artifact, next to Submit and Record decision. The Plan tab shows the revision list (numbers and dates in mono), the issue preview, Record decision, and the diff with moved dates marked by both a colour and a word.

- [ ] **Step 4: Run `npx vitest run`, check it in a browser, commit**

```bash
git add frontend/src/components/ frontend/src/app/ frontend/src/lib/i18n/en.ts
git commit -m "feat(frontend): add both plan approval gates and the held-journey banner"
```

### Task 32: The milestone visibility toggle and the Internal badge

**Files:**
- Modify: `frontend/src/components/workflow/MilestoneEditor.tsx`
- Modify: `frontend/src/components/journey/MilestoneRow.tsx`
- Test: `frontend/src/components/journey/__tests__/MilestoneRow.test.tsx` (extend)

**Interfaces:**
- Consumes: `portalVisible` on the milestone request/view types from Task 18
- Produces: the operator path to Q24

- [ ] **Step 1: Write the failing tests**

```tsx
it("badges an internal-only milestone with a word, not only a colour", () => {
  render(<MilestoneRow milestone={{ ...base, portalVisible: false }} />);
  expect(screen.getByText("Internal")).toBeInTheDocument();
});

it("adds no badge to a shared milestone", () => {
  render(<MilestoneRow milestone={{ ...base, portalVisible: true }} />);
  expect(screen.queryByText("Internal")).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run and watch them fail**

- [ ] **Step 3: Add the toggle**, mirroring `StageInspector.tsx:166-168`'s existing `portalVisible` switch exactly, and the badge on the roadmap row.

- [ ] **Step 4: Run `npx vitest run` and commit**

```bash
git add frontend/src/components/ frontend/src/lib/i18n/en.ts
git commit -m "feat(frontend): author milestone visibility and badge internal milestones"
```

---

# Phase 8 — End-to-end and close-out

### Task 33: An e2e spec for the whole arc

**Files:**
- Create: `frontend/e2e/customer-plan.spec.ts`

**Interfaces:**
- Consumes: `e2e/support/tenant.ts`, `e2e/support/backend.mjs`
- Produces: the proof that Phases 3–6 work together in a real browser against a real server

- [ ] **Step 1: Write the spec**

Cover, in one test: clone a catalogue template for a customer → tailor it → publish → submit the shape → record approval → open a journey and confirm it starts **held** → issue a schedule revision → record approval → confirm the hold releases and the first requirement can now be satisfied.

- [ ] **Step 2: Heed the five defects sub-project 2's first live run found**

Every one was in a spec's own seeded payload, not the product:

- `MilestoneRequest.dependsOnMilestoneKeys` and `StageRequest.branchRules` NPE the server when omitted. Send them, even empty.
- `estimatedDurationDays` is `@Positive int` — omitting it 400s.
- `StageRequest.autoAdvance` is a primitive `boolean`: a missing key binds to `false`, not the `true` the builder shows. Send `autoAdvance: true` on every stage or the case never leaves stage one.
- A fresh draft's `lockVersion` is **not** reliably `0` when the template already has a published version. Round-trip whatever the create response returned.
- Never `.check()` a checkbox whose state depends on a server round trip — `.click()` then `await expect(locator).toBeChecked()`.

And one from this sub-project: a role holding `case.view` but not `workflow.view` gets a **404 on the whole case read**, because the view resolves `currentStageName` from a `workflow` entity. A hand-built test role must declare both.

- [ ] **Step 3: Run it live against the scratch database**

```powershell
cd frontend; $env:DB_URL = "jdbc:postgresql://localhost:5433/onboarding"; npx playwright test customer-plan
```

The activation token exists **only** in `frontend/e2e/.artifacts/backend.log`; Playwright gives a test no way to read a `webServer`'s stdout.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/customer-plan.spec.ts
git commit -m "test(e2e): prove the clone-approve-hold-release arc in a real browser"
```

### Task 34: An e2e spec for the programme and its scope filter

**Files:**
- Create: `frontend/e2e/programme.spec.ts`

- [ ] **Step 1: Write the spec**

Two cases. First: an administrator creates a programme, adds two journeys, and the rollup reads the duration-weighted figure with "across 2 journeys". Second — the one worth the file: a user who is a programme participant but a case participant on only **one** of the two journeys sees the programme, sees one journey, and gets a 404 navigating directly to the other. That is Q20's non-negotiable proven through the real stack, not just in a service test.

- [ ] **Step 2: Run it live, then run the whole suite**

```powershell
cd frontend; $env:DB_URL = "jdbc:postgresql://localhost:5433/onboarding"; npx playwright test
```

Twelve specs now. Read the summary line, never a pinned count.

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/programme.spec.ts
git commit -m "test(e2e): prove a programme never widens a viewer's journey access"
```

### Task 35: Whole-branch verification and close-out

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/plans/2026-09-08-programmes-and-customer-plans.md` (amend any defect this plan turned out to have)

- [ ] **Step 1: Run all three suites in one pass, from clean**

```powershell
cd backend; .\gradlew.bat cleanTest test
cd ..\frontend; npx vitest run
$env:DB_URL = "jdbc:postgresql://localhost:5433/onboarding"; npx playwright test
```

All three must be green **in the same pass**. A suite that was green three tasks ago is not evidence.

- [ ] **Step 2: Verify the ten invariants of spec §10 individually**

Do not assert them — check each against the actual code, and record how you checked:

1. Nothing in `programme` calls `CaseEngine.reconcile` (Task 14's test).
2. `noJourneyDependencyOnProgramme` passes and was seen red (Task 10).
3. Programme participation grants read of the programme only (`ProgrammeScopeTest`).
4. No `progress_percent` column on `programme`; no request type accepts one.
5. `workflow_version_frozen` still refuses every non-DRAFT update (Task 19's test).
6. A case still has exactly one pinned version; clone and refresh create versions and never repin.
7. `plan_revision_item` has no `UPDATE` or `DELETE` grant (Task 22's test).
8. The hold is `Case.held_at` / `ON_HOLD` / `CaseOnHoldException` — grep for any second pause mechanism.
9. `git log --oneline -- backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java` shows no commit from this branch.
10. Out-of-scope and cross-tenant ids are 404; every new `PUT` request/view pair is field-for-field aligned.

- [ ] **Step 3: Update CLAUDE.md**

Add a "Sub-project 3A delivered" paragraph to Project shape; add 3A's own ten invariants; record what stays open (§11.3's three items, plus anything this branch deliberately deferred); note the twelve-spec Playwright suite. Delete any line that stopped being true.

- [ ] **Step 4: Commit and finish the branch**

```bash
git add CLAUDE.md docs/superpowers/plans/
git commit -m "docs(claude-md): close out sub-project 3A"
```

Then invoke `superpowers:finishing-a-development-branch`.

---

## Plan self-review

Run against the spec after writing, and recorded here so an executor knows what was already checked:

- **Spec coverage.** §3.1 → Tasks 9–10. §3.2 → Phases 5 and 6's placement. §3.3 → Task 9's partial index. §4.1–4.3 → Task 9. §4.4 → Task 15. §4.5 → Task 18. §4.6 → Task 19. §4.7–4.8 → Task 22. §5.1 → Task 16. §5.2 → Task 17. §5.3 → Tasks 20, 24. §5.4 → Tasks 24, 27. §5.5 → Tasks 25, 26. §5.6 → Task 14. §5.7 → Tasks 12, 13, 16, 20, 24, 25. §6.1 → Tasks 11, 19, 23. §6.2 → Tasks 11, 19, 23. §6.3 → Task 13. §6.4 → Task 14. §6.5 → every write-path test. §7 → Tasks 14, 16, 17, 20, 21, 24, 25, 27. §8.1–8.4 → Tasks 29–32. §9 → Tasks 33–35. §10 → Task 35 Step 2. §11.1 → Tasks 2–8. §11.2 → committed with the spec. §11.3 → Task 35 Step 3.
- **Type consistency.** `PlanShapeApprovalStatus` (SUBMITTED/APPROVED/REJECTED) and `PlanRevisionStatus` (ISSUED/APPROVED/REJECTED/SUPERSEDED) are separate enums and are not interchanged. `DecidePlanRequest` is shared by both gates. `PlanGateException` is raised by both `PlanShapeService` and `PlanRevisionService`; it is declared once, in `workflow`, and `journey` already depends on `workflow`. `CaseWeight` is produced in Task 14 and consumed nowhere else.
- **Two paths were verified against the working tree rather than guessed**, after the first draft of this plan named both wrongly. The sidebar is `frontend/src/components/shell/Sidebar.tsx` (not `components/layout/`), and its nav entries are inline objects carrying a `t("nav.…")` label — Task 29 adds `nav.programmes` alongside `nav.customers` at line ~106. The builder's milestone editing surface is `frontend/src/components/workflow/MilestoneEditor.tsx` (not `MilestoneInspector.tsx`), and `StageInspector.tsx:166-168` is the `portalVisible` switch Task 32 mirrors. `i18n.test.ts` asserts against the key list, so a new `nav.*` key needs adding there too.

