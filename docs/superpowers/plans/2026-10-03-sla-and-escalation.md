# SLA & Escalation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build sub-project 6 — a per-tenant business calendar, business-day SLA clocks per stage visit that pause on hold and on open document requests, breach and at-risk detection, a manager → department head → administrators escalation chain, mandatory automatic escalation of overdue tasks, milestones and breached clocks, the platform's first tenant-iterating scheduler, the audit partition roll-forward job, and the SLA war room, chips, calendar admin screen and builder toggle.

**Architecture:** One new domain module, `co.ara.onboarding.sla`, implementing two ports declared by the modules it reads (`journey.SlaClockLifecycle`, `document.CustomerWaitLifecycle`) so nothing imports `sla`. One new orchestration slice, `co.ara.onboarding.scheduling`, runs a job once per active tenant as a code-constant system actor inside a synthetic request scope. Clocks and pause intervals are written synchronously in the caller's transaction; elapsed time is always derived on read through `platform.CalendarRules`; a five-minute sweep stamps breaches and inserts escalations idempotently under a database unique key.

**Tech Stack:** Java 21, Spring Boot 3.4 (`@Scheduled`), Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, JdbcTemplate, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-03-sla-and-escalation-design.md` — read it before any task, **including §1.2 "Amendments from plan research"**, which overrides later sections where they disagree. Section numbers below (§5, §1.2.3 …) refer to it.

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — `SCREENS.md` §3 (case header chip, right-rail SLA CLOCK), §4 (war room), §9 (builder inspector); `STATE_AND_DATA.md` L124–138 (`SlaClock`, the one-formatter rule); `DOMAIN_RULES.md` L145–147, L182–184. Do **not** read `docs/uispecs_legacy/`. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 5), per CLAUDE.md.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is authoritative for everything sub-projects 1–5 established; this carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. New module `sla`; new slice `scheduling`. Descriptors go in `scoping`. New `journey` type: the `SlaClockLifecycle` port. New `document` type: the `CustomerWaitLifecycle` port. New `platform` types: `CalendarRules`, `OffsetClock`, `JobLock`. New `authz` types: `SystemPrincipal`, `SystemPermissions`. New `identity` type: `ReportingLineDirectory`. New `tenancy` type: `TenantBusinessCalendar`.
- **`journey` and `document` must never import `sla`.** Two **named** `ModuleBoundaryTest` rules — `noJourneyDependencyOnSla`, `noDocumentDependencyOnSla` — each **seen red** before it is trusted (Task 6).
- **`platform` names no domain type.** `scheduling` may depend on `tenancy`, `authz`, `platform`; nothing depends on `scheduling` except Spring wiring.
- **Migration numbers:** `V27` is highest as this plan is written. Before writing a migration, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>`. Forward-only.
- **Every tenant-owned table** has `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `created_at`/`updated_at timestamptz NOT NULL` (every entity extends `TenantScopedEntity` → `BaseEntity`), `SELECT enable_tenant_rls('<t>')` in the same migration, and explicit grants. **No `DELETE` grant anywhere except `business_holiday`**, with a comment (spec §4.1). `RlsCoverageTest`'s allowlist stays at four entries.
- **UUIDv7 keys** via `Uuid7.generate()`. Timestamps `timestamptz` UTC. Due dates and holidays are `LocalDate`. "Now" is always `Instant.now(clock)` / the calendar's `today()`, **never** the zero-arg `LocalDate.now()`/`Instant.now()` in new code.
- **Every public `*Service` method carries `@RequirePermission`.** Name sweep and admin classes `*Service` so the rule binds. `sla..` joins `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`'s package list **in the same commit that adds the first `sla` class reading a repository** (Task 11). **Exactly one new `FINDER_RULE_EXCLUSIONS` entry is permitted:** `co.ara.onboarding.sla.SlaClockWriter`, the shared body of both lifecycle adapters, for the `TaskLifecycleAdapter` reason (it runs only inside a caller's already-authorized transaction on a case id the caller resolved). Any other exclusion means the design is wrong — stop and escalate.
- **No new caller of `CaseEngine.reconcile`.** Clock calls are made only where `currentStageId` changes (spec §1.2.1).
- **The system actor's permission set is a code constant** (`authz.SystemPermissions`), never a `user_role` row. `security.SystemActorTest` asserts the exact set.
- **Out-of-scope and cross-tenant ids are 404, never 403.** `IllegalStateException` → 409, `IllegalArgumentException` → 400, `NoSuchElementException` → 404 (all mapped in `platform.ApiExceptionHandler`). A rule violation the spec calls 422 throws `sla.UnprocessableException` (mapped by `sla.SlaExceptionHandler`) or, in `document`, `document.ReminderNotPossibleException` (mapped by `DocumentExceptionHandler`).
- **A `PUT` is a full replace; its view carries every field its request accepts.** New boolean request fields are **boxed `Boolean`, normalised in exactly one place**, never primitive (the `autoAdvance` trap).
- **Positional records keep their old arity** via a secondary constructor (spec §1.2.11).
- **Audit:** new actions in `AuditActions` with the `timeline_visible` values in spec §4.7. **Cause before effect** — record an action before the calls that record its consequences.
- **TDD.** Failing test first; security tests before the mechanism; every new structural guard seen red.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper call.
- **Fixture create-helpers run inside `runAs`.**
- **Backend:** `.\gradlew.bat cleanTest test` (PowerShell) — never a bare `test`. Docker must be running. Run `java -version` **and** `javac -version` at the start of every backend task (Application Control can block `javac.exe` alone). Run Gradle from `backend/`, never with a shell sitting inside `backend/build/` (it locks `test-results`).
- **Frontend:** every task runs `npx vitest run`, `npx tsc --noEmit` and `npm run lint`.
- **API types are generated, never hand-written** — `.\gradlew.bat openApiSpec` then `npm run generate:api`.
- **i18n:** every user-facing string through `t()`; keys are flat dotted entries in `frontend/src/lib/i18n/messages/en.json`, new ones under `sla.`, `calendar.`.
- **Design non-negotiables:** colour means status and is always paired with a word; Instrument Sans for human text, Spline Sans Mono (`var(--ob-font-family-data)` / `font-data`) for every number, date and id; cards flat; light theme only.
- **Conventional Commits**, *why* in the body; end every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. When you find a plan defect, fix the code **and** amend this plan, and say so in the commit body. **Do not push**; the user approves every push.

## Review Focus

Inputs and conditions the spec implies but no happy-path test would meet — each has its pinning test in the owning task:

1. **A case put on hold while a document request is also open, then resumed while the request is still open.** The clock must stay paused (the request interval is still open) and the paused time must be counted once, not twice. Pinned in Task 10 (`overlappingPausesCountOnce`) and Task 12 (`resumeWithAnOpenRequestStaysPaused`).
2. **A tenant whose timezone is far from UTC, around midnight.** A task due "today" in Auckland must not escalate because UTC is still yesterday, and a holiday must be read in the tenant's zone. Pinned in Task 2 (`businessDurationRespectsTheTenantZoneAcrossMidnight`) and Task 15 (`overdueIsJudgedInTheTenantZone`).
3. **Two sweeps of the same tenant at once** (two app instances, or a dev-triggered sweep racing the scheduled one). Exactly one escalation row and one set of notifications. Pinned in Task 15 (`concurrentSweepsEscalateOnce`).
4. **A milestone reopened into an earlier stage on a completed case.** The old clock (already stopped) stays as recorded and a fresh one starts for the stage the case re-enters. Pinned in Task 11 (`reopeningIntoAnEarlierStageStartsAFreshClock`).
5. **An escalation chain whose manager and department head are both deactivated, or who is the late person themselves.** It must fall through to administrators, never notify the late person about their own lateness, and never resolve to nobody silently. Pinned in Task 16 (`fallsThroughInactiveAndSelfToAdministrators`, `noActiveAdministratorStillRecordsTheEscalation`).

---

## File structure

```
backend/src/main/resources/db/migration/
  V28__business_calendar.sql            Task 2  — business_calendar, business_holiday, sla_policy (+ backfill)
  V29__reporting_lines.sql              Task 3  — app_user.manager_id, department.head_user_id
  V30__stage_pauses_on_customer.sql     Task 4  — stage.pauses_on_customer
  V31__sla.sql                          Task 5  — sla_clock, sla_pause, escalation, notification, reminder cols
  V32__app_user_type_check.sql          Task 8  - CHECK (user_type IN ('INTERNAL','PORTAL'))
  V33__audit_partition_maintenance.sql  Task 17 — ensure_audit_event_partitions (SECURITY DEFINER)
  V34__audit_partition_utc.sql         Task 17 fix — pins TimeZone=UTC inside ensure_audit_event_partitions

backend/src/main/java/co/ara/onboarding/
  platform/  CalendarRules (2, +startOfDay 10), BusinessCalendar (2, 10), WeekdayBusinessCalendar DELETED (2),
             JobLock (9), UserType +SYSTEM (8), OffsetClock + PlatformBeansConfig (21)
  tenancy/   TenantBusinessCalendar (2), TenantContext.runAsReturning (9)
  authz/     SystemPrincipal, SystemPermissions, AuthContextProvider, AuthorizationService (8);
             PermissionKeys, PermissionCatalog, RoleTemplates (7)
  identity/  AppUser, Department, UserAdminService, OrgStructureService/Controller, ReportingLineDirectory (3)
  provisioning/TenantProvisioningService — seed calendar + policy (2)
  workflow/  Stage, WorkflowDefinitionRequest/View, WorkflowService — pausesOnCustomer (4)
  journey/   SlaClockLifecycle; CaseEngine, CaseService, MilestoneService call sites (11)
  document/  DocumentRequest (5); DocumentRequestRepository.countByCaseIdAndStatus (11);
             CustomerWaitLifecycle; DocumentRequestService, DocumentInstantiation call sites (12);
             remind + ReminderNotPossibleException + DocumentRequestView/Controller/ExceptionHandler (20)
  scoping/   SlaClockDescriptor, EscalationDescriptor (7); SlaPauseDescriptor (13); NotificationDescriptor (16)
  scheduling/JobRequestAttributes, TenantJobRunner (9); SchedulingConfig, SlaSweepJob, AuditPartitionJob (17);
             DevToolsService, DevToolsController — @Profile("dev") (21)
  sla/       entities, enums, repositories (5); SlaClockReader, SlaClockView, SlaClockState, SlaPolicy,
             SlaPolicyReader (10); SlaClockWriter, SlaClockLifecycleAdapter (11); CustomerWaitLifecycleAdapter (12);
             SlaClockService/Controller, UnprocessableException, SlaExceptionHandler (13); SlaSweepService (14–16);
             RecipientResolver, EscalationWriter, RaisedEscalation (15); EscalationMailer (16);
             CalendarAdminService/Controller + records (18); SlaExceptionsService/Controller, ExceptionsView (19)
  audit/     AuditActions — new actions (3, 14, 18, 20)

backend/src/test/java/co/ara/onboarding/
  platform/CalendarRulesTest (2, replaces BusinessCalendarTest), OffsetClockTest (21)
  tenancy/TenantBusinessCalendarTest (2)
  identity/ReportingLinesTest, ReportingLineDirectoryTest (3)
  workflow/PausesOnCustomerTest (4)
  scoping/SlaDescriptorsTest (7)
  security/SystemActorTest (8)
  scheduling/TenantJobRunnerTest (9), SchedulingConfigTest, SlaSweepJobTest (17), DevToolsProfileTest (21)
  sla/SlaSchemaTest (5), SlaClockReaderTest (10), SlaTestSupport + SlaClockLifecycleTest (11), CustomerWaitTest (12),
      SlaClockApiTest (13), SlaSweepBreachTest (14), SlaSweepEscalationTest + SweepConcurrencyTest (15),
      RecipientResolverTest + EscalationDeliveryTest + EscalationRetryTest (16), CalendarAdminTest (18),
      SlaExceptionsTest (19), SlaIsolationTest + SlaScopeTest (22)
  document/CustomerReminderTest (20)
  audit/AuditPartitionJobTest (17)
  architecture/ModuleBoundaryTest (6), AuthorizationCoverageTest (11)
  journey/CauseBeforeEffectTest (20)

frontend/src/
  lib/api/sla.ts, calendar.ts; cases.ts, admin.ts, documents.ts (23)
  components/sla/formatSlaClock.ts, SlaChip.tsx, SlaClockCallout.tsx (24)
  components/journey/CaseHeader.tsx; app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx (25)
  components/workflow/StageInspector.tsx; admin/workflows/[id]/versions/[vid]/page.tsx (26)
  app/(app)/t/[slug]/admin/users/page.tsx, admin/org/page.tsx (27); components/admin/UserSelect.tsx (30)
  app/(app)/t/[slug]/admin/business-calendar/page.tsx, admin/layout.tsx (28)
  app/(app)/t/[slug]/sla/page.tsx; components/sla/WarRoomCard.tsx, escalationNote.ts; components/shell/Sidebar.tsx (29)
  components/sla/ReassignDialog.tsx, RemindCustomerDialog.tsx (30)
frontend/e2e/sla.spec.ts; e2e/support/tenant.ts (31)
```

---

## Phase 0 — Baseline

### Task 1: Establish a green baseline across all suites

**Files:** none modified (report only).

- [ ] **Step 1: Branch.** From an up-to-date `main` that contains the spec and this plan, create the feature branch (or the worktree the execution skill creates): `git checkout -b feat/sla-and-escalation`.
- [ ] **Step 2: Check Java.** `& "C:\Program Files\OpenLogic\jdk-21.0.11.10-hotspot\bin\java.exe" -version` and the same with `javac.exe`. Both must print a version. If `javac` is blocked by Application Control, stop and report — do not attempt to bypass it.
- [ ] **Step 3: Backend.** From `backend/`: `.\gradlew.bat cleanTest test`. Expected: `BUILD SUCCESSFUL`. Count from the XML results, not a pinned number:
  ```bash
  cd backend/build/test-results/test && cat *.xml | grep -o '<testsuite [^>]*' | awk -F'"' '{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ failures=$/)f+=$(i+1);if($i~/ errors=$/)e+=$(i+1)}} END{print t, f, e}'
  ```
  then `cd` back out of `build/` before running Gradle again.
- [ ] **Step 4: Frontend.** From `frontend/`: `npx vitest run`, `npx tsc --noEmit`, `npm run lint`. All clean (pre-existing warnings allowed).
- [ ] **Step 5: Playwright.** From `frontend/`, against a scratch database: `$env:DB_URL="jdbc:postgresql://localhost:5432/onboarding_e2e_sla"; npx playwright test`. Create the database first (`docker exec onboarding-db createdb -U postgres onboarding_e2e_sla`). Kill stray `:8080`/`:3000` processes first **unless another session on this machine owns them**.
- [ ] **Step 6: Record.** Write the four results (counts, any failures and their cause) into `.superpowers/sdd/2026-10-03-sla-and-escalation/task-1-report.md`. A failure here is a pre-existing defect: diagnose it with superpowers:systematic-debugging and fix it in its own commit before Phase 1, never by weakening an assertion.

---

## Phase 1 — Foundations

### Task 2: The business calendar — `CalendarRules`, `TenantBusinessCalendar`, schema, seeding

Replaces `WeekdayBusinessCalendar` (spec §3.3, §4.1, §1.2.13). After this task every due date computed honours the tenant's working days, holidays and timezone.

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__business_calendar.sql`
- Create: `backend/src/main/java/co/ara/onboarding/platform/CalendarRules.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/BusinessCalendar.java`
- Delete: `backend/src/main/java/co/ara/onboarding/platform/WeekdayBusinessCalendar.java`
- Create: `backend/src/main/java/co/ara/onboarding/tenancy/TenantBusinessCalendar.java`
- Modify: `backend/src/main/java/co/ara/onboarding/provisioning/TenantProvisioningService.java:122`
- Delete: `backend/src/test/java/co/ara/onboarding/platform/BusinessCalendarTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/platform/CalendarRulesTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/tenancy/TenantBusinessCalendarTest.java`

**Interfaces:**
- Produces:
  ```java
  // platform
  public record CalendarRules(ZoneId zone, Set<DayOfWeek> workingDays, Set<LocalDate> holidays) {
      public static CalendarRules weekdaysUtc();
      public boolean isBusinessDay(LocalDate d);
      public LocalDate plusBusinessDays(LocalDate from, int days);
      public int businessDaysBetween(LocalDate from, LocalDate to);
      public double businessDuration(Instant from, Instant to);
      public LocalDate today(Clock clock);
      public LocalDate localDate(Instant instant);
  }
  public interface BusinessCalendar {
      LocalDate plusBusinessDays(LocalDate from, int days);
      int businessDaysBetween(LocalDate from, LocalDate to);
      double businessDuration(Instant from, Instant to);
      LocalDate today();
      LocalDate localDate(Instant instant);
      String name();
  }
  // tenancy
  @Component public class TenantBusinessCalendar implements BusinessCalendar
  ```

- [ ] **Step 1: Write the failing `CalendarRulesTest`.** It replaces `BusinessCalendarTest` — copy that file's existing weekday assertions across first (open it and port every case to `CalendarRules.weekdaysUtc()` so no existing behaviour is dropped), then add:

```java
package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.EnumSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CalendarRulesTest {

    private static final Set<DayOfWeek> MON_FRI =
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    // 2026-10-02 is a Friday.
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);

    @Test
    void plusBusinessDaysSkipsHolidays() {
        var rules = new CalendarRules(ZoneOffset.UTC, MON_FRI, Set.of(LocalDate.of(2026, 10, 5))); // Monday holiday
        assertThat(rules.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void businessDaysBetweenSkipsHolidaysAndWeekends() {
        var rules = new CalendarRules(ZoneOffset.UTC, MON_FRI, Set.of(LocalDate.of(2026, 10, 5)));
        // [Fri, Wed) = Fri, (Sat, Sun, Mon-holiday skipped), Tue = 2
        assertThat(rules.businessDaysBetween(FRI, LocalDate.of(2026, 10, 7))).isEqualTo(2);
    }

    @Test
    void customWorkingDaysAreHonoured() {
        var sunThu = EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        var rules = new CalendarRules(ZoneOffset.UTC, sunThu, Set.of());
        assertThat(rules.isBusinessDay(FRI)).isFalse();
        assertThat(rules.isBusinessDay(LocalDate.of(2026, 10, 4))).isTrue(); // Sunday
    }

    @Test
    void businessDurationCountsFractionalWorkingTime() {
        var rules = CalendarRules.weekdaysUtc();
        Instant fri06 = Instant.parse("2026-10-02T06:00:00Z");
        Instant mon12 = Instant.parse("2026-10-05T12:00:00Z");
        // Fri 06:00→24:00 = 0.75, Sat/Sun 0, Mon 00:00→12:00 = 0.5
        assertThat(rules.businessDuration(fri06, mon12)).isCloseTo(1.25, within(1e-9));
    }

    @Test
    void businessDurationIsZeroForAnEmptyOrReversedInterval() {
        var rules = CalendarRules.weekdaysUtc();
        Instant t = Instant.parse("2026-10-02T06:00:00Z");
        assertThat(rules.businessDuration(t, t)).isZero();
        assertThat(rules.businessDuration(t, t.minusSeconds(60))).isZero();
    }

    @Test
    void businessDurationRespectsTheTenantZoneAcrossMidnight() {
        // Review Focus 2. Auckland is UTC+13 in October (NZDT).
        var auckland = ZoneId.of("Pacific/Auckland");
        var rules = new CalendarRules(auckland, MON_FRI, Set.of());
        // 2026-10-02T11:00Z = Sat 2026-10-03 00:00 Auckland. Sat→Sun in Auckland: no business time.
        Instant from = Instant.parse("2026-10-02T11:00:00Z");
        Instant to = Instant.parse("2026-10-04T11:00:00Z");
        assertThat(rules.businessDuration(from, to)).isZero();
        // The same instants are Friday 11:00Z → Sunday 11:00Z in UTC: 0.5416… business days there.
        assertThat(CalendarRules.weekdaysUtc().businessDuration(from, to)).isCloseTo(13.0 / 24, within(1e-9));
    }

    @Test
    void todayIsTheDateInTheTenantZone() {
        var rules = new CalendarRules(ZoneId.of("Pacific/Auckland"), MON_FRI, Set.of());
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
        assertThat(rules.today(clock)).isEqualTo(LocalDate.of(2026, 10, 3));
    }
}
```

- [ ] **Step 2: Run it to verify it fails.** `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.platform.CalendarRulesTest"`. Expected: compilation failure, `CalendarRules` not found.

- [ ] **Step 3: Implement `CalendarRules`.**

```java
package co.ara.onboarding.platform;

import java.time.*;
import java.util.EnumSet;
import java.util.Set;

/**
 * The pure business-calendar maths every tenant calendar delegates to (spec §3.3, §1.2.13).
 * A business day counts as a whole 24-hour day in the calendar's zone; any other day counts
 * zero. No working hours -- nothing in the PRD or QA Q8 asks for them.
 */
public record CalendarRules(ZoneId zone, Set<DayOfWeek> workingDays, Set<LocalDate> holidays) {

    public CalendarRules {
        workingDays = workingDays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(workingDays);
        holidays = Set.copyOf(holidays);
        if (workingDays.isEmpty()) {
            throw new IllegalArgumentException("A calendar needs at least one working day");
        }
    }

    public static CalendarRules weekdaysUtc() {
        return new CalendarRules(ZoneOffset.UTC,
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), Set.of());
    }

    public boolean isBusinessDay(LocalDate d) {
        return workingDays.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    /** Next business day on or after {@code from}, then {@code days} more. Matches the retired weekday calendar. */
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        LocalDate cursor = nextBusinessDay(from);
        for (int i = 0; i < days; i++) cursor = nextBusinessDay(cursor.plusDays(1));
        return cursor;
    }

    /** Business days in {@code [from, to)}; 0 when {@code to} is not after {@code from}. */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) if (isBusinessDay(d)) count++;
        return count;
    }

    /** Fractional business days in {@code [from, to)}, walking local days in this calendar's zone. */
    public double businessDuration(Instant from, Instant to) {
        if (!to.isAfter(from)) return 0;
        double total = 0;
        LocalDate day = localDate(from);
        LocalDate last = localDate(to);
        while (!day.isAfter(last)) {
            if (isBusinessDay(day)) {
                Instant dayStart = day.atStartOfDay(zone).toInstant();
                Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
                Instant a = from.isAfter(dayStart) ? from : dayStart;
                Instant b = to.isBefore(dayEnd) ? to : dayEnd;
                if (b.isAfter(a)) {
                    double dayLength = Duration.between(dayStart, dayEnd).toMillis();
                    total += Duration.between(a, b).toMillis() / dayLength;
                }
            }
            day = day.plusDays(1);
        }
        return total;
    }

    public LocalDate today(Clock clock) { return LocalDate.ofInstant(clock.instant(), zone); }

    public LocalDate localDate(Instant instant) { return LocalDate.ofInstant(instant, zone); }

    private LocalDate nextBusinessDay(LocalDate d) {
        LocalDate cursor = d;
        while (!isBusinessDay(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }
}
```

Note `businessDuration` divides by the real local day length, so a 23- or 25-hour DST day still counts as exactly one business day when fully covered.

- [ ] **Step 4: Run `CalendarRulesTest` — expect PASS.** Then delete `BusinessCalendarTest.java` (its cases now live in `CalendarRulesTest`).

- [ ] **Step 5: Write the migration `V28__business_calendar.sql`.**

```sql
-- Sub-project 6, spec §4.1. One calendar and one SLA policy per tenant; holidays are
-- configuration, not business records.

CREATE TABLE business_calendar (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL UNIQUE REFERENCES tenant(id),
    name          text        NOT NULL DEFAULT 'Business calendar',
    timezone      text        NOT NULL DEFAULT 'UTC',
    working_days  smallint[]  NOT NULL DEFAULT '{1,2,3,4,5}',   -- ISO day-of-week, 1 = Monday
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT business_calendar_working_days_ck
        CHECK (cardinality(working_days) BETWEEN 1 AND 7 AND working_days <@ '{1,2,3,4,5,6,7}')
);
SELECT enable_tenant_rls('business_calendar');
GRANT SELECT, INSERT, UPDATE ON business_calendar TO onboarding_app;

CREATE TABLE business_holiday (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant(id),
    holiday_date  date        NOT NULL,
    name          text        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT business_holiday_tenant_date_uq UNIQUE (tenant_id, holiday_date)
);
SELECT enable_tenant_rls('business_holiday');
-- DELETE is deliberate: a holiday is tenant configuration, not a business record, and removing
-- a mistaken one has to actually remove it (spec §4.1). Its removal is audited
-- (calendar.holiday_removed), which is the record that it existed.
GRANT SELECT, INSERT, UPDATE, DELETE ON business_holiday TO onboarding_app;

CREATE TABLE sla_policy (
    id                          uuid         PRIMARY KEY,
    tenant_id                   uuid         NOT NULL UNIQUE REFERENCES tenant(id),
    at_risk_days                numeric(4,1) NOT NULL DEFAULT 1.0 CHECK (at_risk_days >= 0),
    escalate_after_overdue_days int          NOT NULL DEFAULT 1 CHECK (escalate_after_overdue_days >= 1),
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL
);
SELECT enable_tenant_rls('sla_policy');
GRANT SELECT, INSERT, UPDATE ON sla_policy TO onboarding_app;

-- Backfill every existing tenant. Runs as the Flyway owner (a superuser), so RLS does not
-- filter it -- the V15/V26 precedent. gen_random_uuid() is acceptable here only because these
-- are one-time backfill keys; application code always uses Uuid7.generate().
INSERT INTO business_calendar (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
INSERT INTO sla_policy (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
```

- [ ] **Step 6: Write the failing `TenantBusinessCalendarTest`.**

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantBusinessCalendarTest extends PostgresTestBase {

    @Autowired BusinessCalendar calendar;
    @Autowired TenantFixture fixture;
    @Autowired JdbcTemplate jdbc;   // onboarding_app, RLS-bound

    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);

    @Test
    void theOnlyCalendarBeanIsTheTenantOne() {
        assertThat(calendar).isInstanceOf(TenantBusinessCalendar.class);
    }

    @Test
    void aNewTenantGetsAWeekdayUtcCalendar() {
        UUID tenant = fixture.createTenant("cal-default");
        fixture.runAs(tenant, () -> {
            assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(calendar.name()).isEqualTo("Business calendar");
        });
    }

    @Test
    void holidaysAndWorkingDaysAreReadPerTenant() {
        UUID a = fixture.createTenant("cal-a");
        UUID b = fixture.createTenant("cal-b");
        fixture.runAs(a, () -> jdbc.update("""
                INSERT INTO business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, '2026-10-05', 'Labour Day', now(), now())""", a));
        fixture.runAs(a, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6)));
        fixture.runAs(b, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5)));
    }

    @Test
    void theCacheNeverOutlivesATransaction() {
        UUID tenant = fixture.createTenant("cal-cache");
        fixture.runAs(tenant, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5)));
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, '2026-10-05', 'Labour Day', now(), now())""", tenant));
        fixture.runAs(tenant, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6)));
    }

    @Test
    void provisioningSeedsACalendarAndAPolicy() {
        UUID tenant = fixture.createTenant("cal-seeded");
        fixture.runAs(tenant, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_calendar", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sla_policy", Integer.class)).isOne();
        });
    }
}
```

> Check before running: does `TenantFixture.createTenant` go through `TenantProvisioningService.provision`, or insert the `tenant` row directly? Open `TenantFixture.java:101`. If it inserts directly, `provisioningSeedsACalendarAndAPolicy` must instead call `TenantProvisioningService.provision(...)` (inject it) and `aNewTenantGetsAWeekdayUtcCalendar` relies on `TenantBusinessCalendar`'s fallback (Step 7: no row ⇒ `CalendarRules.weekdaysUtc()`), which is the behaviour every fixture-created tenant across the existing suite depends on. Record which in the task report.

- [ ] **Step 7: Run it to verify it fails** (the bean is still `WeekdayBusinessCalendar`). Then implement `BusinessCalendar` and `TenantBusinessCalendar`.

`platform/BusinessCalendar.java` — replace the interface body, keep and update its javadoc:

```java
/**
 * Business-day arithmetic for the current tenant (spec §3.3). The only implementation is
 * tenancy.TenantBusinessCalendar, which reads the bound tenant's calendar; platform names no
 * domain type, so the interface lives here and the implementation there.
 */
public interface BusinessCalendar {
    LocalDate plusBusinessDays(LocalDate from, int days);
    int businessDaysBetween(LocalDate from, LocalDate to);
    /** Fractional business days in [from, to). */
    double businessDuration(Instant from, Instant to);
    /** Today's date in the tenant's timezone, from the injected Clock. */
    LocalDate today();
    /** The tenant-zone date of an instant. */
    LocalDate localDate(Instant instant);
    /** The calendar's display name, e.g. for the war room eyebrow. */
    String name();
}
```

`tenancy/TenantBusinessCalendar.java`:

```java
package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.CalendarRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/**
 * The tenant's configured business calendar (spec §3.3). Reads business_calendar and
 * business_holiday for the tenant bound to this thread, through the RLS-bound application
 * connection, and caches the result for the current transaction only -- never across
 * transactions, so a holiday added in one request is honoured by the next.
 *
 * A tenant with no business_calendar row (a test fixture that inserts the tenant directly)
 * falls back to Monday-Friday UTC, which is exactly what the retired WeekdayBusinessCalendar
 * did for everyone.
 */
@Component
public class TenantBusinessCalendar implements BusinessCalendar {

    private static final String DEFAULT_NAME = "Business calendar";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TenantBusinessCalendar(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public LocalDate plusBusinessDays(LocalDate from, int days) { return rules().rules().plusBusinessDays(from, days); }
    @Override public int businessDaysBetween(LocalDate from, LocalDate to) { return rules().rules().businessDaysBetween(from, to); }
    @Override public double businessDuration(Instant from, Instant to) { return rules().rules().businessDuration(from, to); }
    @Override public LocalDate today() { return rules().rules().today(clock); }
    @Override public LocalDate localDate(Instant instant) { return rules().rules().localDate(instant); }
    @Override public String name() { return rules().name(); }

    private record Loaded(CalendarRules rules, String name) {}

    private Loaded rules() {
        UUID tenantId = TenantContext.getRequired();
        String key = TenantBusinessCalendar.class.getName() + ":" + tenantId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Object cached = TransactionSynchronizationManager.getResource(key);
            if (cached instanceof Loaded l) return l;
            Loaded loaded = load(tenantId);
            TransactionSynchronizationManager.bindResource(key, loaded);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(key);
                }
            });
            return loaded;
        }
        return load(tenantId);
    }

    private Loaded load(UUID tenantId) {
        List<Loaded> rows = jdbc.query("""
                SELECT name, timezone, working_days FROM business_calendar WHERE tenant_id = ?""",
                (rs, i) -> {
                    Integer[] days = (Integer[]) rs.getArray("working_days").getArray();
                    EnumSet<DayOfWeek> working = EnumSet.noneOf(DayOfWeek.class);
                    for (Integer d : days) working.add(DayOfWeek.of(d));
                    return new Loaded(new CalendarRules(ZoneId.of(rs.getString("timezone")), working,
                            holidays(tenantId)), rs.getString("name"));
                }, tenantId);
        return rows.isEmpty() ? new Loaded(CalendarRules.weekdaysUtc(), DEFAULT_NAME) : rows.get(0);
    }

    private Set<LocalDate> holidays(UUID tenantId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT holiday_date FROM business_holiday WHERE tenant_id = ?", LocalDate.class, tenantId));
    }
}
```

> `smallint[]` comes back from the PostgreSQL driver as `Short[]`, not `Integer[]`. If the cast throws `ClassCastException`, read it as `Object[]` and convert with `((Number) d).intValue()`. Write the code that way from the start if in doubt.

Delete `WeekdayBusinessCalendar.java`. `CaseEngine`, `CaseService` and `MigrationService` need **no** code change — they inject the interface.

- [ ] **Step 8: Seed on provisioning.** In `TenantProvisioningService.provision`, immediately after `seedRoles(tenant.getId());` (L122), call a new private method:

```java
    /** Spec §4.1: every tenant starts with a Monday-Friday UTC calendar and the default SLA policy. */
    private void seedCalendarAndPolicy(UUID tenantId) {
        jdbc.update("""
                INSERT INTO business_calendar (id, tenant_id, created_at, updated_at)
                VALUES (?, ?, now(), now())""", Uuid7.generate(), tenantId);
        jdbc.update("""
                INSERT INTO sla_policy (id, tenant_id, created_at, updated_at)
                VALUES (?, ?, now(), now())""", Uuid7.generate(), tenantId);
    }
```

Add a `JdbcTemplate jdbc` constructor parameter (the application datasource; the tenant is already bound by `binder.bind` at this point, so RLS's `WITH CHECK` passes).

- [ ] **Step 9: Run the calendar tests, then the journey suites that touch due dates.** `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.tenancy.*" --tests "co.ara.onboarding.platform.*" --tests "co.ara.onboarding.journey.*" --tests "co.ara.onboarding.provisioning.*"`. Expected: PASS. `CaseCreationTest`, `HoldTest`, `MigrationTest`, `TransitionTest` autowire `BusinessCalendar` and must still pass unchanged — a fixture tenant has the fallback calendar.

- [ ] **Step 10: Run the full backend suite**, then commit.

```bash
git add -A backend
git commit -m "feat(calendar): tenant business calendar with holidays and timezone

Replaces WeekdayBusinessCalendar with tenancy.TenantBusinessCalendar over the pure
platform.CalendarRules (spec 3.3, 1.2.13). V28 adds business_calendar, business_holiday
(the one table with a DELETE grant -- a holiday is configuration) and sla_policy, backfilled
for existing tenants and seeded by provisioning. Existing due-date callers are unchanged in
code; dates computed from now on honour holidays and the tenant zone.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Reporting lines — `manager_id`, `head_user_id`, `ReportingLineDirectory`

Spec §4.2, §1.2.7, §1.2.10. `managerId` on users, `headUserId` on departments, a department update endpoint, and the recipient lookups the sweep needs.

**Files:**
- Create: `backend/src/main/resources/db/migration/V29__reporting_lines.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/identity/AppUser.java`, `Department.java`
- Modify: `backend/src/main/java/co/ara/onboarding/identity/UserAdminService.java` (records at L27–35, `create` L104, `update` L134)
- Modify: `backend/src/main/java/co/ara/onboarding/identity/OrgStructureService.java`, `OrgStructureController.java`
- Create: `backend/src/main/java/co/ara/onboarding/identity/ReportingLineDirectory.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/identity/ReportingLinesTest.java`, `ReportingLineDirectoryTest.java`

**Interfaces:**
- Produces:
  ```java
  public record CreateUserRequest(String email, String fullName, UUID departmentId, UUID managerId)  // + 3-arg ctor
  public record UpdateUserRequest(String fullName, UUID departmentId, UUID managerId)               // + 2-arg ctor
  public record UserView(UUID id, String email, String fullName, UserType userType, UserStatus status,
                         UUID departmentId, UUID managerId, Set<UUID> teamIds, Set<UUID> roleIds)
  public record DepartmentRequest(String name, String description, UUID headUserId)                 // + 2-arg ctor
  public record DepartmentView(UUID id, String name, String description, UUID headUserId)
  public DepartmentView updateDepartment(UUID id, DepartmentRequest request)   // @RequirePermission(DEPARTMENT_MANAGE)
  // identity.ReportingLineDirectory
  public record Recipient(UUID userId, String email, String fullName) {}
  public Optional<Recipient> activeUser(UUID userId);
  public Optional<Recipient> activeManagerOf(UUID userId);
  public Optional<Recipient> activeDepartmentHeadOf(UUID userId);
  public List<Recipient> activeAdministrators();
  ```
- `UserView` gains `managerId` **after `departmentId`**. It is positional: grep `new UserView(` in main and test and update every site (main has only `UserAdminService.toView`; fix test sites in this same task).

- [ ] **Step 1: Migration `V29__reporting_lines.sql`.**

```sql
-- Sub-project 6, spec §4.2. Who an escalation goes to: a user's manager, else their
-- department's head, else the administrators. Both nullable; neither is a business record of
-- its own, so no new table. Deactivation revokes nothing here -- the resolver skips inactive
-- people and falls through (spec §4.2), so deactivating someone can never break the chain.
ALTER TABLE app_user   ADD COLUMN manager_id   uuid NULL REFERENCES app_user(id);
ALTER TABLE app_user   ADD CONSTRAINT app_user_not_own_manager_ck CHECK (manager_id <> id);
ALTER TABLE department ADD COLUMN head_user_id uuid NULL REFERENCES app_user(id);
```

- [ ] **Step 2: Write the failing `ReportingLinesTest`** (service-level, against `UserAdminService` and `OrgStructureService`). Use `fixture.createAdminUser(tenant, email)` for the acting administrator and `fixture.runAsUser(tenant, admin.getId(), () -> …)`.

```java
package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.NoSuchElementException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ReportingLinesTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired UserAdminService users;
    @Autowired OrgStructureService org;

    @Test
    void aUserCanBeGivenAManagerAndTheViewCarriesIt() {
        UUID tenant = fixture.createTenant("rl-manager");
        var admin = fixture.createAdminUser(tenant, "admin@rl.test");
        UUID manager = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "boss@rl.test"));
        UUID report = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "report@rl.test"));
        fixture.runAsUser(tenant, admin.getId(), () -> {
            var view = users.update(report, new UserAdminService.UpdateUserRequest("Report", null, manager));
            assertThat(view.managerId()).isEqualTo(manager);
            assertThat(users.get(report).managerId()).isEqualTo(manager);
        });
    }

    @Test
    void aUserCannotManageThemselves() {
        UUID tenant = fixture.createTenant("rl-self");
        var admin = fixture.createAdminUser(tenant, "admin@self.test");
        UUID u = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "u@self.test"));
        assertThatThrownBy(() -> fixture.runAsUser(tenant, admin.getId(), () ->
                users.update(u, new UserAdminService.UpdateUserRequest("U", null, u))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aManagerFromAnotherTenantIsNotFound() {
        UUID mine = fixture.createTenant("rl-mine");
        UUID theirs = fixture.createTenant("rl-theirs");
        var admin = fixture.createAdminUser(mine, "admin@mine.test");
        UUID report = fixture.runAsReturning(mine, () -> fixture.createUser(mine, "r@mine.test"));
        UUID foreign = fixture.runAsReturning(theirs, () -> fixture.createUser(theirs, "x@theirs.test"));
        assertThatThrownBy(() -> fixture.runAsUser(mine, admin.getId(), () ->
                users.update(report, new UserAdminService.UpdateUserRequest("R", null, foreign))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aDepartmentCanBeGivenAHeadThroughAFullReplace() {
        UUID tenant = fixture.createTenant("rl-head");
        var admin = fixture.createAdminUser(tenant, "admin@head.test");
        UUID head = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "head@head.test"));
        fixture.runAsUser(tenant, admin.getId(), () -> {
            var created = org.createDepartment(new OrgStructureService.DepartmentRequest("Finance", "Money"));
            var updated = org.updateDepartment(created.id(),
                    new OrgStructureService.DepartmentRequest("Finance", "Money", head));
            assertThat(updated.headUserId()).isEqualTo(head);
            assertThat(updated.description()).isEqualTo("Money");
        });
    }

    @Test
    void theUpdateRequestAndViewStayFieldForFieldAligned() {
        var request = java.util.Arrays.stream(OrgStructureService.DepartmentRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        var view = java.util.Arrays.stream(OrgStructureService.DepartmentView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(view).containsAll(request);
        var userRequest = java.util.Arrays.stream(UserAdminService.UpdateUserRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        var userView = java.util.Arrays.stream(UserAdminService.UserView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(userView).containsAll(userRequest);
    }
}
```

> If `fixture.runAsReturning`/`createUser` signatures differ from the ones above (see `TenantFixture.java:122`, `:384`), adapt the calls — not the assertions.

- [ ] **Step 3: Run to verify it fails** (compilation: no `managerId`, no `updateDepartment`).

- [ ] **Step 4: Implement.**

`AppUser`: add `@Column(name = "manager_id") private UUID managerId;` with getter/setter. `Department`: add `@Column(name = "head_user_id") private UUID headUserId;` with getter/setter.

`UserAdminService` records:

```java
public record CreateUserRequest(String email, String fullName, UUID departmentId, UUID managerId) {
    /** Spec §1.2.11: the pre-sub-project-6 arity, so existing callers compile unchanged. */
    public CreateUserRequest(String email, String fullName, UUID departmentId) { this(email, fullName, departmentId, null); }
}
public record UpdateUserRequest(String fullName, UUID departmentId, UUID managerId) {
    public UpdateUserRequest(String fullName, UUID departmentId) { this(fullName, departmentId, null); }
}
public record UserView(UUID id, String email, String fullName, UserType userType,
                       UserStatus status, UUID departmentId, UUID managerId,
                       Set<UUID> teamIds, Set<UUID> roleIds) {}
```

In both `create` and `update`, after the department handling, resolve the manager **through `AuthorizedQuery`** (the write-path rule) and refuse self-management:

```java
    /** A managerId from a request body is a foreign id: resolve it under the actor's own scope first. */
    private UUID resolveManager(UUID managerId, UUID subjectUserId) {
        if (managerId == null) return null;
        if (managerId.equals(subjectUserId)) {
            throw new IllegalArgumentException("A user cannot be their own manager");
        }
        return authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, managerId).getId();
    }
```

(`UserAdminService` already injects `AuthorizedQuery` and `AppUserRepository` — confirm the field names when editing.) In `create`, the subject id is the new user's id, assigned before this call; in `update`, it is the path id. Set `u.setManagerId(resolveManager(request.managerId(), u.getId()))`. **`update` is a full replace**: a null `managerId` clears the manager. When the value changes, record `AuditActions.USER_MANAGER_CHANGED` with payload `{"managerId": "<id or null>"}` after the save. `toView` passes `u.getManagerId()` after `getDepartmentId()`.

`OrgStructureService`:

```java
public record DepartmentRequest(String name, String description, UUID headUserId) {
    public DepartmentRequest(String name, String description) { this(name, description, null); }
}
public record DepartmentView(UUID id, String name, String description, UUID headUserId) {}

@RequirePermission(DEPARTMENT_MANAGE)
@Transactional
public DepartmentView updateDepartment(UUID id, DepartmentRequest request) {
    Department d = authorizedQuery.getById(departments, Department.class, DEPARTMENT_MANAGE, id);
    UUID before = d.getHeadUserId();
    d.setName(request.name());
    d.setDescription(request.description());
    d.setHeadUserId(request.headUserId() == null ? null
            : authorizedQuery.getById(users, AppUser.class, USER_VIEW, request.headUserId()).getId());
    d = departments.save(d);
    if (!java.util.Objects.equals(before, d.getHeadUserId())) {
        audit.record(AuditActions.DEPARTMENT_HEAD_CHANGED, "department", d.getId(),
                "Department head changed", java.util.Collections.singletonMap("headUserId",
                        d.getHeadUserId() == null ? null : d.getHeadUserId().toString()));
    }
    return toView(d);
}
```

> Read `OrgStructureService` before editing: how does `createDepartment`/`listDepartments` read and write today — through `AuthorizedQuery` or the repository directly, and does it hold `AuthorizedQuery`/`AppUserRepository`/`AuditRecorder`? `identity..` is in the finder rule, so any read must go through `AuthorizedQuery` (`Department` has no descriptor: `department.manage` is ALL-only with a `null` resourceType, and `AuthorizedQuery` at ALL scope needs no descriptor — confirm by running the test; if `getById` refuses for want of a descriptor, add `scoping.DepartmentDescriptor` whose three scopes all return `cb.disjunction()`, since an ALL-only permission never reaches them). `createDepartment` should also accept `headUserId` through the same resolution. `toView` gains `d.getHeadUserId()`.

`OrgStructureController`: add

```java
    @PutMapping("/departments/{id}")
    public DepartmentView updateDepartment(@PathVariable UUID id, @RequestBody DepartmentRequest request) {
        return service.updateDepartment(id, request);
    }
```

with the same `@ApiResponses` shape as its neighbours (200, 403, 404).

`AuditActions` — add, in a new commented group:

```java
    // Identity reporting lines (sub-project 6). Compliance-only, like every other user.* action.
    public static final AuditAction USER_MANAGER_CHANGED    = of("user.manager_changed", false);
    public static final AuditAction DEPARTMENT_HEAD_CHANGED = of("department.head_changed", false);
```

- [ ] **Step 5: Run `ReportingLinesTest` — PASS.**

- [ ] **Step 6: Write the failing `ReportingLineDirectoryTest`.**

```java
package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ReportingLineDirectoryTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired ReportingLineDirectory lines;

    @Test
    void resolvesAnActiveManagerAndDepartmentHead() {
        UUID tenant = fixture.createTenant("rld-basic");
        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID head = fixture.createUserInDepartment(tenant, "head@rld.test", dept);
            UUID boss = fixture.createUser(tenant, "boss@rld.test");
            UUID worker = fixture.createUserInDepartment(tenant, "worker@rld.test", dept);
            ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", boss, worker);
            ownerJdbc().update("UPDATE department SET head_user_id = ? WHERE id = ?", head, dept);

            assertThat(lines.activeManagerOf(worker)).map(ReportingLineDirectory.Recipient::userId).contains(boss);
            assertThat(lines.activeDepartmentHeadOf(worker)).map(ReportingLineDirectory.Recipient::userId).contains(head);
        });
    }

    @Test
    void inactivePeopleAreNeverReturned() {
        UUID tenant = fixture.createTenant("rld-inactive");
        fixture.runAs(tenant, () -> {
            UUID boss = fixture.createUser(tenant, "boss@inactive.test");
            UUID worker = fixture.createUser(tenant, "worker@inactive.test");
            ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", boss, worker);
            ownerJdbc().update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", boss);
            assertThat(lines.activeManagerOf(worker)).isEmpty();
        });
    }

    @Test
    void administratorsAreTheActiveHoldersOfTheAdministratorTemplate() {
        UUID tenant = fixture.createTenant("rld-admins");
        var admin = fixture.createAdminUser(tenant, "admin@admins.test");
        fixture.runAs(tenant, () ->
                assertThat(lines.activeAdministrators()).extracting(ReportingLineDirectory.Recipient::userId)
                        .contains(admin.getId()));
    }

    @Test
    void anotherTenantsPeopleAreInvisible() {
        UUID a = fixture.createTenant("rld-a");
        UUID b = fixture.createTenant("rld-b");
        UUID foreign = fixture.runAsReturning(b, () -> fixture.createUser(b, "x@b.test"));
        fixture.runAs(a, () -> assertThat(lines.activeUser(foreign)).isEmpty());
    }
}
```

> `createUser` may create users `INVITED`, not `ACTIVE` — check `TenantFixture.java:122`. The directory filters on `status = 'ACTIVE'`, so if fixture users are `INVITED`, activate them in the test with `ownerJdbc().update("UPDATE app_user SET status='ACTIVE' WHERE id=?", id)`. Same for `createAdminUser`'s role name: the directory matches `role.name = 'Administrator'`; if the fixture's admin role is named "Fixture Superuser" (`TenantFixture.java:419` mentions both), the third test must grant the real template role instead — use `fixture.administratorRoleId(tenant)` and a `user_role` insert.

- [ ] **Step 7: Implement `ReportingLineDirectory`.**

```java
package co.ara.onboarding.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Who an escalation goes to (spec §6.2, §1.2.10). Read with JdbcTemplate under the bound
 * tenant's RLS -- the IdentityActorDirectory precedent: it runs as the system actor on ids the
 * sweep itself derived from tenant rows, never on an id taken from a request, so there is no
 * caller scope to apply. It calls no repository finder, so the finder rule needs no exclusion.
 */
@Component
public class ReportingLineDirectory {

    public record Recipient(UUID userId, String email, String fullName) {}

    private static final String ACTIVE_USER = """
            SELECT id, email, full_name FROM app_user WHERE id = ? AND status = 'ACTIVE' AND user_type = 'INTERNAL'""";

    private final JdbcTemplate jdbc;

    public ReportingLineDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeUser(UUID userId) {
        if (userId == null) return Optional.empty();
        return jdbc.query(ACTIVE_USER, (rs, i) -> map(rs), userId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeManagerOf(UUID userId) {
        UUID manager = single("SELECT manager_id FROM app_user WHERE id = ?", userId);
        return activeUser(manager);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeDepartmentHeadOf(UUID userId) {
        UUID head = single("""
                SELECT d.head_user_id FROM app_user u JOIN department d ON d.id = u.department_id
                 WHERE u.id = ?""", userId);
        return activeUser(head);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Recipient> activeAdministrators() {
        return jdbc.query("""
                SELECT DISTINCT u.id, u.email, u.full_name
                  FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE r.name = 'Administrator' AND r.enabled AND u.status = 'ACTIVE' AND u.user_type = 'INTERNAL'
                 ORDER BY u.email""", (rs, i) -> map(rs));
    }

    private UUID single(String sql, UUID id) {
        if (id == null) return null;
        List<UUID> rows = jdbc.queryForList(sql, UUID.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Recipient map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Recipient(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"));
    }
}
```

> Confirm the column names `full_name`, `user_type`, `status` (`V4__identity.sql`) and `role.enabled` (`V7__roles.sql`) before running.

- [ ] **Step 8: Fix every positional `new UserView(` test site** (`grep -rn "new UserView(" backend/src`), then run `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.identity.*" --tests "co.ara.onboarding.security.*"` — PASS — then the full suite. Regenerate nothing yet (frontend types come in Task 22).

- [ ] **Step 9: Commit** — `feat(identity): manager and department-head reporting lines for escalation` with a body naming spec §4.2/§1.2.7/§1.2.10 and the secondary-constructor rule.

---

### Task 4: `stage.pauses_on_customer`

Spec §4.3, §8 (the `autoAdvance` trap avoided), §1.2.11.

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__stage_pauses_on_customer.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/Stage.java`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowDefinitionRequest.java:28-41` (`StageRequest`)
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowDefinitionView.java:29-44` (`StageView`)
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowService.java:546-559, 746-750, 802-808`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/PausesOnCustomerTest.java`

**Interfaces:**
- Produces: `StageRequest` gains a trailing `Boolean pausesOnCustomer` component plus a secondary constructor at the old 13-arg arity; `StageView` gains a trailing `boolean pausesOnCustomer` (positional — fix every `new StageView(` site); `Stage.isPausesOnCustomer()`.

- [ ] **Step 1: Migration.**

```sql
-- Sub-project 6, spec §4.3. Whether an open customer document request pauses this stage's SLA
-- clock. Existing stages -- published ones included -- read true; ADD COLUMN ... DEFAULT fires no
-- row trigger, so V12's published-version freeze is unaffected (V19's precedent).
ALTER TABLE stage ADD COLUMN pauses_on_customer boolean NOT NULL DEFAULT true;
```

- [ ] **Step 2: Write the failing `PausesOnCustomerTest`.** Use the existing workflow test helpers (`WorkflowFixtures.stage(...)`/`twoStages()` and `JourneyFixtures.publish(...)` — read their signatures first) and a raw JSON round trip through MockMvc for the omission case, because only real deserialisation shows what Jackson does with a missing key:

```java
package co.ara.onboarding.workflow;

import co.ara.onboarding.security.SecurityTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class PausesOnCustomerTest extends SecurityTestBase {

    @Autowired ObjectMapper json;

    @Test
    void anOmittedKeyDeserialisesToTrueNotFalse() throws Exception {
        var request = json.readValue("""
                {"key":"s1","name":"S","autoAdvance":true,"milestones":[],"branchRules":[]}""",
                WorkflowDefinitionRequest.StageRequest.class);
        assertThat(request.pausesOnCustomer()).isNull();
        assertThat(WorkflowService.pausesOnCustomer(request)).isTrue();
    }

    @Test
    void anExplicitFalseIsKept() throws Exception {
        var request = json.readValue("""
                {"key":"s1","name":"S","autoAdvance":true,"pausesOnCustomer":false,"milestones":[],"branchRules":[]}""",
                WorkflowDefinitionRequest.StageRequest.class);
        assertThat(WorkflowService.pausesOnCustomer(request)).isFalse();
    }

    @Test
    void theOldArityConstructorDefaultsToNull() {
        var s = new WorkflowDefinitionRequest.StageRequest("k", "n", null, false, true, true, null,
                null, null, null, null, java.util.List.of(), java.util.List.of());
        assertThat(s.pausesOnCustomer()).isNull();
    }
}
```

Add a fourth test, `aSavedDraftRoundTripsTheFlag`, that saves a draft whose one stage has `pausesOnCustomer = false` through `WorkflowService` (copy the arrange block of an existing `WorkflowServiceTest` draft-save test — read it first), reads the definition back and asserts the `StageView` carries `false`; then creates a new draft from that published version (`createDraftVersion` deep-copies via `toStageRequest`) and asserts the copy still carries `false`. That last assertion is what catches a forgotten `toStageRequest` line.

- [ ] **Step 3: Run it — fails to compile.**

- [ ] **Step 4: Implement.** `Stage`: `@Column(name = "pauses_on_customer", nullable = false) private boolean pausesOnCustomer = true;` + accessors. `StageRequest`:

```java
public record StageRequest(String key, String name, UUID responsibleDepartmentId, boolean requiresApproval,
    boolean autoAdvance, boolean portalVisible, Integer slaDays, WriteScope writeScope,
    String notificationTemplateKey, ConditionRequest entryCondition, String fallbackNextStageKey,
    @Valid List<MilestoneRequest> milestones, List<BranchRuleRequest> branchRules,
    /** Boxed on purpose: an omitted key must mean true, not Jackson's false (spec §8). */
    Boolean pausesOnCustomer) {
    /** Spec §1.2.11: the pre-sub-project-6 arity, so ~31 positional call sites compile unchanged. */
    public StageRequest(String key, String name, UUID responsibleDepartmentId, boolean requiresApproval,
            boolean autoAdvance, boolean portalVisible, Integer slaDays, WriteScope writeScope,
            String notificationTemplateKey, ConditionRequest entryCondition, String fallbackNextStageKey,
            List<MilestoneRequest> milestones, List<BranchRuleRequest> branchRules) {
        this(key, name, responsibleDepartmentId, requiresApproval, autoAdvance, portalVisible, slaDays,
                writeScope, notificationTemplateKey, entryCondition, fallbackNextStageKey, milestones,
                branchRules, null);
    }
}
```

> Jackson binds a record through its **canonical** constructor; with a second constructor present, confirm the round trip still works (`anExplicitFalseIsKept` proves it). If Jackson picks the wrong constructor, annotate the canonical one with `@JsonCreator`.

`WorkflowService`: add the one normalisation point and use it in `newStage`:

```java
    /** The one place a null pausesOnCustomer becomes true (spec §8). */
    static boolean pausesOnCustomer(StageRequest s) {
        return s.pausesOnCustomer() == null || s.pausesOnCustomer();
    }
```

`stage.setPausesOnCustomer(pausesOnCustomer(s));` after L559. `StageView` gains `boolean pausesOnCustomer` last; the entity→view mapping (L746) passes `s.isPausesOnCustomer()`; `toStageRequest` (L802) passes `s.pausesOnCustomer()` as the new last argument. Fix every `new StageView(` site (`grep -rn "new StageView(" backend/src`).

- [ ] **Step 5: Run `PausesOnCustomerTest` and `co.ara.onboarding.workflow.*` — PASS. Full suite. Commit** — `feat(workflow): pausesOnCustomer stage flag, defaulting true on omission`.

---

### Task 5: The SLA tables, entities and repositories

Spec §4.4–§4.6. Schema and persistence only; no behaviour.

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__sla.sql`
- Create in `backend/src/main/java/co/ara/onboarding/sla/`: `SlaClock.java`, `SlaClockOutcome.java`, `SlaClockRepository.java`, `SlaPause.java`, `PauseReason.java`, `SlaPauseRepository.java`, `Escalation.java`, `EscalationSubject.java`, `EscalationRoute.java`, `EscalationRepository.java`, `Notification.java`, `NotificationType.java`, `NotificationRepository.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequest.java` (two fields)
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaSchemaTest.java`

**Interfaces:**
- Produces (all entities extend `TenantScopedEntity`, getters/setters for every field):
  ```java
  enum SlaClockOutcome { MET, BREACHED }
  enum PauseReason { CASE_HOLD, OPEN_DOCUMENT_REQUEST }
  enum EscalationSubject { TASK, MILESTONE, SLA_CLOCK }
  enum EscalationRoute { MANAGER, DEPARTMENT_HEAD, ADMINISTRATORS }
  enum NotificationType { ESCALATION }
  class SlaClock { UUID caseId, stageId; int targetDays; boolean pauseEligible; Instant startedAt, stoppedAt, breachedAt; SlaClockOutcome outcome; }
  class SlaPause { UUID clockId; PauseReason reason; Instant startedAt, endedAt; }
  class Escalation { EscalationSubject subjectType; UUID subjectId, caseId, lateUserId, escalatedToUserId; EscalationRoute route; LocalDate dueDateAtEscalation; int overdueDays; Instant escalatedAt; }
  class Notification { UUID recipientUserId, caseId, escalationId; NotificationType type; String title, body, linkPath; Instant readAt, emailedAt; }  // createdAt from BaseEntity
  interface SlaClockRepository extends JpaRepository<SlaClock, UUID>, JpaSpecificationExecutor<SlaClock> {
      Optional<SlaClock> findByCaseIdAndStoppedAtIsNull(UUID caseId);
      Optional<SlaClock> findFirstByCaseIdOrderByStartedAtDesc(UUID caseId);
  }
  interface SlaPauseRepository extends JpaRepository<SlaPause, UUID>, JpaSpecificationExecutor<SlaPause> {
      List<SlaPause> findByClockId(UUID clockId);
      List<SlaPause> findByClockIdIn(Collection<UUID> clockIds);
      Optional<SlaPause> findByClockIdAndReasonAndEndedAtIsNull(UUID clockId, PauseReason reason);
      List<SlaPause> findByClockIdAndEndedAtIsNull(UUID clockId);
  }
  interface EscalationRepository extends JpaRepository<Escalation, UUID>, JpaSpecificationExecutor<Escalation> {
      List<Escalation> findByCaseIdInOrderByEscalatedAtDesc(Collection<UUID> caseIds);
  }
  interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
      List<Notification> findByTypeAndEmailedAtIsNull(NotificationType type);
  }
  // document.DocumentRequest: int remindersSent; Instant lastRemindedAt
  ```

- [ ] **Step 1: Write the failing `SlaSchemaTest`** — the database-level guarantees, proven against the live schema through `withAppConnection` (RLS-bound, the real role):

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SlaSchemaTest extends PostgresTestBase {

    @Test
    void theApplicationRoleCannotDeleteFromAnySlaTable() {
        for (String table : new String[]{"sla_clock", "sla_pause", "escalation", "notification"}) {
            withAppConnection(jdbc -> assertThatThrownBy(() -> jdbc.execute("DELETE FROM " + table))
                    .hasStackTraceContaining("permission denied for table " + table));
        }
    }

    @Test
    void escalationIsUniquePerSubjectAndDueDate() {
        Integer constraints = ownerJdbc().queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'escalation'::regclass AND contype = 'u'""", Integer.class);
        assertThat(constraints).isPositive();
    }

    @Test
    void aCaseHasAtMostOneOpenClock() {
        Integer index = ownerJdbc().queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE indexname = 'sla_clock_one_open_per_case'""", Integer.class);
        assertThat(index).isOne();
    }

    @Test
    void documentRequestCarriesReminderCounters() {
        Integer cols = ownerJdbc().queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'document_request' AND column_name IN ('reminders_sent', 'last_reminded_at')""",
                Integer.class);
        assertThat(cols).isEqualTo(2);
    }
}
```

Add one behavioural test per partial-unique index, `aSecondOpenClockForTheSameCaseIsRefused` and `aSecondOpenPauseForTheSameReasonIsRefused`: seed a tenant, a case (through `JourneyFixtures.newCase(tenant)` inside `fixture.runAs`) and a stage id (any `stage` row — `journey.publishedThreeStageWorkflow()` gives one), insert two open rows with `ownerJdbc()` and assert the second throws `DuplicateKeyException`. (`RlsCoverageTest` covers RLS for every new table automatically — do not add an allowlist entry.)

- [ ] **Step 2: Run — fails** (no tables).

- [ ] **Step 3: Migration `V31__sla.sql`.**

```sql
-- Sub-project 6, spec §4.4-§4.6. Clocks and pause intervals are written synchronously by the
-- ports; elapsed time is derived on read and never stored (invariant 4). None of these tables
-- grants DELETE: a clock, a pause, an escalation and a notification are each the only record
-- that the thing happened.

CREATE TABLE sla_clock (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    case_id         uuid        NOT NULL REFERENCES onboarding_case(id),
    stage_id        uuid        NOT NULL REFERENCES stage(id),
    target_days     int         NOT NULL CHECK (target_days > 0),
    pause_eligible  boolean     NOT NULL,
    started_at      timestamptz NOT NULL,
    stopped_at      timestamptz NULL,
    outcome         text        NULL CHECK (outcome IN ('MET','BREACHED')),
    breached_at     timestamptz NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT sla_clock_stopped_has_outcome_ck CHECK ((stopped_at IS NULL) = (outcome IS NULL))
);
CREATE UNIQUE INDEX sla_clock_one_open_per_case ON sla_clock (case_id) WHERE stopped_at IS NULL;
CREATE INDEX sla_clock_case_idx ON sla_clock (case_id, started_at DESC);
SELECT enable_tenant_rls('sla_clock');
GRANT SELECT, INSERT, UPDATE ON sla_clock TO onboarding_app;

CREATE TABLE sla_pause (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant(id),
    clock_id    uuid        NOT NULL REFERENCES sla_clock(id),
    reason      text        NOT NULL CHECK (reason IN ('CASE_HOLD','OPEN_DOCUMENT_REQUEST')),
    started_at  timestamptz NOT NULL,
    ended_at    timestamptz NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
CREATE UNIQUE INDEX sla_pause_one_open_per_reason ON sla_pause (clock_id, reason) WHERE ended_at IS NULL;
SELECT enable_tenant_rls('sla_pause');
GRANT SELECT, INSERT, UPDATE ON sla_pause TO onboarding_app;

CREATE TABLE escalation (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenant(id),
    subject_type            text        NOT NULL CHECK (subject_type IN ('TASK','MILESTONE','SLA_CLOCK')),
    subject_id              uuid        NOT NULL,
    case_id                 uuid        NOT NULL REFERENCES onboarding_case(id),
    late_user_id            uuid        NULL REFERENCES app_user(id),
    route                   text        NOT NULL CHECK (route IN ('MANAGER','DEPARTMENT_HEAD','ADMINISTRATORS')),
    escalated_to_user_id    uuid        NULL REFERENCES app_user(id),
    due_date_at_escalation  date        NOT NULL,
    overdue_days            int         NOT NULL,
    escalated_at            timestamptz NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    -- Invariant 5: the database, not the sweep, is what makes escalation idempotent.
    CONSTRAINT escalation_once_per_subject_and_due_date
        UNIQUE (tenant_id, subject_type, subject_id, due_date_at_escalation)
);
CREATE INDEX escalation_case_idx ON escalation (case_id, escalated_at DESC);
SELECT enable_tenant_rls('escalation');
GRANT SELECT, INSERT ON escalation TO onboarding_app;

CREATE TABLE notification (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenant(id),
    recipient_user_id  uuid        NOT NULL REFERENCES app_user(id),
    type               text        NOT NULL CHECK (type IN ('ESCALATION')),   -- 6B widens this by migration
    title              text        NOT NULL,
    body               text        NOT NULL,
    link_path          text        NOT NULL,
    case_id            uuid        NULL REFERENCES onboarding_case(id),
    escalation_id      uuid        NULL REFERENCES escalation(id),
    read_at            timestamptz NULL,
    emailed_at         timestamptz NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);
CREATE INDEX notification_unsent_idx ON notification (type) WHERE emailed_at IS NULL;
SELECT enable_tenant_rls('notification');
GRANT SELECT, INSERT, UPDATE ON notification TO onboarding_app;

ALTER TABLE document_request ADD COLUMN reminders_sent   int         NOT NULL DEFAULT 0;
ALTER TABLE document_request ADD COLUMN last_reminded_at timestamptz NULL;
```

> The unique key includes `tenant_id` first (the spec's three columns, scoped per tenant — two tenants' subject ids never collide anyway, but the key should not look cross-tenant). Note this as a plan-level refinement in the commit body.

- [ ] **Step 4: Entities and repositories.** Each entity: `@Entity @Table(name = "...")`, extends `TenantScopedEntity`, `@Enumerated(EnumType.STRING)` for enum columns, `@Column(name = "...")` for every snake_case column, plain getters/setters. Example — `SlaClock`:

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One stage visit's SLA clock (spec §4.4, §5). Elapsed time is never stored -- SlaClockReader derives it. */
@Entity
@Table(name = "sla_clock")
public class SlaClock extends TenantScopedEntity {
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "stage_id", nullable = false) private UUID stageId;
    @Column(name = "target_days", nullable = false) private int targetDays;
    @Column(name = "pause_eligible", nullable = false) private boolean pauseEligible;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "stopped_at") private Instant stoppedAt;
    @Enumerated(EnumType.STRING) @Column(name = "outcome") private SlaClockOutcome outcome;
    @Column(name = "breached_at") private Instant breachedAt;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }
    public int getTargetDays() { return targetDays; }
    public void setTargetDays(int targetDays) { this.targetDays = targetDays; }
    public boolean isPauseEligible() { return pauseEligible; }
    public void setPauseEligible(boolean pauseEligible) { this.pauseEligible = pauseEligible; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
    public SlaClockOutcome getOutcome() { return outcome; }
    public void setOutcome(SlaClockOutcome outcome) { this.outcome = outcome; }
    public Instant getBreachedAt() { return breachedAt; }
    public void setBreachedAt(Instant breachedAt) { this.breachedAt = breachedAt; }
}
```

Write `SlaPause`, `Escalation`, `Notification` the same way, one field per column in the migration above (`Escalation.dueDateAtEscalation` is `LocalDate`). Repositories exactly as in **Interfaces**. `DocumentRequest`: `@Column(name = "reminders_sent", nullable = false) private int remindersSent;` and `@Column(name = "last_reminded_at") private Instant lastRemindedAt;` with accessors — do **not** add them to `DocumentRequestView` yet (Task 19 does, with the endpoint that uses them).

- [ ] **Step 5: Run `SlaSchemaTest` and `co.ara.onboarding.architecture.*` — PASS** (`RlsCoverageTest` must be green with no allowlist change). **Commit** — `feat(sla): clock, pause, escalation and notification tables`.

---

### Task 6: The `sla` module boundary, proven red

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`

- [ ] **Step 1: Add the two rules**, after `noDocumentDependencyOnAgreement`:

```java
    @ArchTest
    static final ArchRule noJourneyDependencyOnSla =
            noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..sla..")
                .because("sla implements journey.SlaClockLifecycle; journey never imports sla. A one-way "
                       + "import would still pass the plain no-cycles rule, which is why this is its own "
                       + "named rule (spec 3.1).");

    @ArchTest
    static final ArchRule noDocumentDependencyOnSla =
            noClasses().that().resideInAPackage("..document..")
                .should().dependOnClassesThat().resideInAPackage("..sla..")
                .because("sla implements document.CustomerWaitLifecycle; document never imports sla (spec 3.1).");
```

- [ ] **Step 2: Prove each red.** Temporarily add `private co.ara.onboarding.sla.SlaClock probe;` to any `journey` class (e.g. `CaseService`), run `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.architecture.ModuleBoundaryTest"`, and confirm **exactly** `noJourneyDependencyOnSla` fails (and possibly the cycle rule). Remove it. Repeat with a `document` class for `noDocumentDependencyOnSla`. Record both failure messages in the task report.
- [ ] **Step 3: Run green. Commit** — `test(architecture): journey and document never depend on sla` (body: both rules seen red, how).

---

### Task 7: Permissions, role templates and descriptors

Spec §7.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/PermissionKeys.java`, `PermissionCatalog.java`, `RoleTemplates.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/SlaClockDescriptor.java`, `EscalationDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/scoping/SlaDescriptorsTest.java`

**Interfaces:**
- Produces: `PermissionKeys.SLA_VIEW = "sla.view"`, `PermissionKeys.CALENDAR_MANAGE = "calendar.manage"`; descriptors with `resourceType()` `"sla_clock"` and `"escalation"`.

- [ ] **Step 1: Write the failing `SlaDescriptorsTest`.** Model it on the existing narrowest-scope descriptor tests (read `scoping.JourneyScopingTest` first and reuse its arrange helpers): a TEAM-scoped holder of `sla.view` on a hand-built role, two cases — one owned by their team, one not — each with an `sla_clock` row inserted via `ownerJdbc()`; assert `authorizedQuery.findAll(slaClocks, SlaClock.class, SLA_VIEW, null, Pageable.unpaged())` returns only the first. Same for `Escalation`. Add `aHolderWithNoGrantSeesNothing` (a user with no `sla.view` at all → empty, never an exception) and a DEPARTMENT-scope case.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `PermissionKeys`:

```java
    public static final String SLA_VIEW        = "sla.view";
    public static final String CALENDAR_MANAGE = "calendar.manage";
```

`PermissionCatalog` static block:

```java
        add(SLA_VIEW,        "sla",      "sla_clock", "View SLA clocks and the war room", RECORD);
        add(CALENDAR_MANAGE, "tenant",   null,        "Manage the business calendar and SLA policy", ALL_ONLY);
```

`RoleTemplates`: Administrator gains `entry(SLA_VIEW, ALL), entry(CALENDAR_MANAGE, ALL)`; Operations gains `entry(SLA_VIEW, DEPARTMENT)`; Project Manager gains `entry(SLA_VIEW, TEAM)`. **`RoleTemplateCoverageTest` must pass with no change to `ADMINISTRATOR_ONLY_PENDING_REVIEW`.**

`SlaClockDescriptor` — copy `CaseParticipantDescriptor` exactly, substituting `SlaClock` for `CaseParticipant`, `resourceType()` `"sla_clock"`, the same `assignedRelationships()` set, and `root.get("caseId")` (the column the subquery joins on is the same name). `EscalationDescriptor` — the same again for `Escalation`. Both fail closed exactly as the original does (no department ⇒ `cb.disjunction()`, no teams ⇒ `cb.disjunction()`).

> `DescriptorRegistry.validate()` requires a descriptor for `sla_clock` (the catalog entry's resourceType). `EscalationDescriptor` exists for `AuthorizedQuery`'s entity-type dispatch only — CLAUDE.md "Six descriptors exist" names that pattern.

- [ ] **Step 4: Run `SlaDescriptorsTest`, `co.ara.onboarding.authz.*`, `co.ara.onboarding.architecture.*` — PASS. Commit** — `feat(authz): sla.view and calendar.manage, with case-scoped descriptors`.

---

### Task 8: The system actor

Spec §3.4, §1.2.3, invariant 7.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/platform/UserType.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/SystemPrincipal.java`, `SystemPermissions.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/AuthContextProvider.java`, `AuthorizationService.java:39-60`
- Test: `backend/src/test/java/co/ara/onboarding/security/SystemActorTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum UserType { INTERNAL, PORTAL, SYSTEM }
  public record SystemPrincipal(UUID tenantId) {
      public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);
      public static Authentication authentication(UUID tenantId);
  }
  public final class SystemPermissions { public static Map<String, Scope> forJobs(); }
  ```

- [ ] **Step 1: Write the failing `SystemActorTest`.**

```java
package co.ara.onboarding.security;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SystemActorTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AuthorizationService authorization;
    @Autowired AuthContextProvider contexts;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theSystemActorHoldsExactlyTheJobPermissionsAtAll() {
        UUID tenant = fixture.createTenant("sys-perms");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(tenant));
        fixture.runUnauthenticated(tenant, () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.byPermission().keySet()).containsExactlyInAnyOrder(
                    PermissionKeys.CASE_VIEW, PermissionKeys.TASK_VIEW, PermissionKeys.SLA_VIEW);
            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).containsExactly(Scope.ALL);
            var ctx = contexts.current();
            assertThat(ctx.userType()).isEqualTo(UserType.SYSTEM);
            assertThat(ctx.tenantId()).isEqualTo(tenant);
            assertThat(ctx.departmentId()).isNull();
            assertThat(ctx.teamIds()).isEmpty();
        });
    }

    @Test
    void theSystemPrincipalIsBoundToItsOwnTenant() {
        UUID a = fixture.createTenant("sys-a");
        UUID b = fixture.createTenant("sys-b");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(a));
        fixture.runUnauthenticated(b, () ->
                assertThat(authorization.effectivePermissions().byPermission()).isEmpty());
    }

    @Test
    void noJwtCanCarryTheSystemUserId() {
        // The JWT filter builds AuthenticatedPrincipal, never SystemPrincipal; the nil UUID is not
        // a user id any app_user row can hold (Uuid7 never generates it).
        assertThat(SystemPrincipal.SYSTEM_USER_ID).isEqualTo(new UUID(0, 0));
        assertThat(SystemPrincipal.class).isNotEqualTo(AuthenticatedPrincipal.class);
    }
}
```

- [ ] **Step 2: Run — fails to compile.**
- [ ] **Step 3: Implement.**

`UserType`: add `SYSTEM` with a javadoc line: *"The scheduler's actor (spec §1.2.3). Never persisted — no app_user row has it."*

```java
package co.ara.onboarding.authz;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;

/**
 * The principal a scheduled job runs as (spec §3.4, §1.2.3). A distinct type, so no JWT --
 * which JwtAuthenticationFilter only ever turns into an AuthenticatedPrincipal -- can produce
 * it. Bound to one tenant: AuthorizationService grants nothing when the thread's tenant differs.
 */
public record SystemPrincipal(UUID tenantId) {

    /** The nil UUID: never a Uuid7, so never an app_user id. Used as AuthContext.userId(). */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    public static Authentication authentication(UUID tenantId) {
        return new UsernamePasswordAuthenticationToken(new SystemPrincipal(tenantId), null, List.of());
    }
}
```

```java
package co.ara.onboarding.authz;

import java.util.Map;
import static co.ara.onboarding.authz.PermissionKeys.*;

/**
 * The system actor's permission set (invariant 7): a code constant, never a user_role row --
 * the PortalPermissions shape. Read-only on purpose: every write the sweep makes goes to sla's
 * own tables through its own gated service methods, which require sla.view.
 */
public final class SystemPermissions {
    private SystemPermissions() {}

    public static Map<String, Scope> forJobs() {
        return Map.of(CASE_VIEW, Scope.ALL, TASK_VIEW, Scope.ALL, SLA_VIEW, Scope.ALL);
    }
}
```

`AuthContextProvider.current()`:

```java
    public AuthContext current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SystemPrincipal s) {
            return new AuthContext(s.tenantId(), SystemPrincipal.SYSTEM_USER_ID, UserType.SYSTEM, null, java.util.Set.of());
        }
        AuthenticatedPrincipal p = principal();
        return actors.findActor(p.userId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }
```

`principal()` is unchanged: a system job that calls it gets "Not authenticated", which is correct — nothing a job runs should need a user principal.

`AuthorizationService.effectivePermissions()`, before the PORTAL branch:

```java
        if (actor.userType() == UserType.SYSTEM) {
            memo = actor.tenantId().equals(co.ara.onboarding.tenancy.TenantContext.getOrNull())
                    ? EffectivePermissions.of(SystemPermissions.forJobs())
                    : EffectivePermissions.none();
            return memo;
        }
```

> Check `authz` may import `tenancy.TenantContext` without creating a cycle: grep `authz/` for an existing `tenancy` import (`JwtAuthenticationFilter` lives in `auth`, not `authz`). If `authz` does not already depend on `tenancy`, and `tenancy` depends on `authz` anywhere, do **not** add the import — instead compare against `actor.tenantId()` only in `scheduling.TenantJobRunner` (which always binds the same tenant it authenticates) and drop `theSystemPrincipalIsBoundToItsOwnTenant`'s middle assertion, recording why. `ModuleBoundaryTest.noCyclesBetweenModules` is the arbiter — run it.

- [ ] **Step 4: Run `SystemActorTest`, `security.*`, `architecture.*` — PASS. Full suite** (`UserType` gained a value; any exhaustive `switch` over it fails to compile — fix each by treating `SYSTEM` like `INTERNAL` only where that is provably right, otherwise throw). **Commit** — `feat(authz): a code-constant system actor for scheduled jobs`.

---

### Task 9: The tenant job runner and job lock

Spec §3.4, §1.2.2–§1.2.4.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/JobLock.java`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/JobRequestAttributes.java`, `TenantJobRunner.java`
- Test: `backend/src/test/java/co/ara/onboarding/scheduling/TenantJobRunnerTest.java`

**Interfaces:**
- Produces:
  ```java
  // platform
  @Component public class JobLock { public boolean tryLock(String job, UUID scope); }   // MANDATORY tx
  // scheduling
  @Component public class TenantJobRunner {
      /** Runs body once per ACTIVE tenant, each in its own transaction, as the system actor. Returns tenants that ran. */
      public List<UUID> forEachTenant(String job, Consumer<UUID> body);
      /** Runs body for one tenant (the dev endpoint and tests use this). Returns false if the lock was held. */
      public boolean forTenant(String job, UUID tenantId, Consumer<UUID> body);
  }
  ```

- [ ] **Step 1: Write the failing `TenantJobRunnerTest`.**

```java
package co.ara.onboarding.scheduling;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class TenantJobRunnerTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired TenantJobRunner runner;
    @Autowired AuthContextProvider contexts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void runsOncePerActiveTenantBoundAndAsTheSystemActor() {
        UUID a = fixture.createTenant("job-a");
        UUID b = fixture.createTenant("job-b");
        ownerJdbc().update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", b);
        Map<UUID, String> seen = new ConcurrentHashMap<>();
        runner.forEachTenant("test-job", tenant -> {
            assertThat(TenantContext.getRequired()).isEqualTo(tenant);
            assertThat(contexts.current().userType()).isEqualTo(UserType.SYSTEM);
            // RLS is bound: the GUC equals the tenant.
            seen.put(tenant, jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class));
        });
        assertThat(seen).containsEntry(a, a.toString()).doesNotContainKey(b);
    }

    @Test
    void oneTenantsFailureDoesNotStopTheOthers() {
        UUID a = fixture.createTenant("job-fail-a");
        UUID b = fixture.createTenant("job-fail-b");
        Set<UUID> ran = ConcurrentHashMap.newKeySet();
        runner.forEachTenant("test-fail", tenant -> {
            ran.add(tenant);
            if (tenant.equals(a)) throw new IllegalStateException("boom");
        });
        assertThat(ran).contains(a, b);
    }

    @Test
    void aSecondRunnerForTheSameTenantAndJobSkips() throws Exception {
        UUID tenant = fixture.createTenant("job-lock");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> first = pool.submit(() -> runner.forTenant("locked-job", tenant, t -> {
            inside.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> second = pool.submit(() -> runner.forTenant("locked-job", tenant, t -> {}));
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    @Test
    void theRequestScopeAndPrincipalDoNotLeakOutOfTheRun() {
        UUID tenant = fixture.createTenant("job-leak");
        runner.forTenant("leak-job", tenant, t -> {});
        assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void aCallersOwnRequestScopeAndPrincipalAreRestored() {
        UUID tenant = fixture.createTenant("job-restore");
        var callerAttributes = new org.springframework.web.context.request.ServletRequestAttributes(
                new org.springframework.mock.web.MockHttpServletRequest());
        var callerAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new co.ara.onboarding.authz.AuthenticatedPrincipal(tenant, UUID.randomUUID()), null, java.util.List.of());
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(callerAttributes);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(callerAuth);
        try {
            runner.forTenant("restore-job", tenant, t -> {});
            assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(callerAttributes);
            assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isSameAs(callerAuth);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
```

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * A transaction-scoped advisory lock keyed by (job, scope) -- spec §1.2.4. Released at commit or
 * rollback by Postgres itself, so a pooled connection can never keep it. A second instance that
 * fails to take it skips this tick.
 */
@Component
public class JobLock {
    private final JdbcTemplate jdbc;
    public JobLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(String job, UUID scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))",
                Boolean.class, job, scope.toString()));
    }
}
```

```java
package co.ara.onboarding.scheduling;

import org.springframework.web.context.request.RequestAttributes;
import java.util.*;

/**
 * A plain in-memory RequestAttributes, so request-scoped beans (AuthorizationService,
 * RequestAuditContext) resolve inside a scheduled job exactly as inside a request -- one fresh
 * instance per tenant run, discarded afterwards (spec §1.2.3).
 */
final class JobRequestAttributes implements RequestAttributes {
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, Runnable> destructionCallbacks = new LinkedHashMap<>();

    @Override public Object getAttribute(String name, int scope) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value, int scope) { attributes.put(name, value); }
    @Override public void removeAttribute(String name, int scope) { attributes.remove(name); }
    @Override public String[] getAttributeNames(int scope) { return attributes.keySet().toArray(String[]::new); }
    @Override public void registerDestructionCallback(String name, Runnable callback, int scope) { destructionCallbacks.put(name, callback); }
    @Override public Object resolveReference(String key) { return null; }
    @Override public String getSessionId() { return "scheduled-job"; }
    @Override public Object getSessionMutex() { return this; }

    void complete() { destructionCallbacks.values().forEach(Runnable::run); destructionCallbacks.clear(); }
}
```

```java
package co.ara.onboarding.scheduling;

import co.ara.onboarding.authz.SystemPrincipal;
import co.ara.onboarding.platform.JobLock;
import co.ara.onboarding.tenancy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs a job body once per ACTIVE tenant (spec §3.4, §1.2.2-§1.2.4): its own transaction, its
 * tenant bound (TenantContext + the RLS GUC), a fresh request scope, the system principal, and a
 * per-(job, tenant) advisory lock. A tenant's failure is logged and never stops the rest.
 */
@Component
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private final TenantRepository tenants;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;
    private final JobLock lock;

    public TenantJobRunner(TenantRepository tenants, TenantConnectionCustomizer binder,
                           TransactionTemplate tx, JobLock lock) {
        this.tenants = tenants;
        this.binder = binder;
        this.tx = tx;
        this.lock = lock;
    }

    public List<UUID> forEachTenant(String job, Consumer<UUID> body) {
        List<UUID> ran = new ArrayList<>();
        for (Tenant t : tenants.findAll()) {
            if (t.getStatus() != TenantStatus.ACTIVE) continue;
            try {
                if (forTenant(job, t.getId(), body)) ran.add(t.getId());
            } catch (RuntimeException e) {
                log.error("Job {} failed for tenant {}", job, t.getId(), e);
            }
        }
        return ran;
    }

    /**
     * Saves and restores whatever request scope and security context the calling thread already
     * had, so the dev endpoint (Task 21), which calls this from inside a real HTTP request, gets its
     * own user's context back afterwards instead of a cleared one.
     */
    public boolean forTenant(String job, UUID tenantId, Consumer<UUID> body) {
        var previousAttributes = RequestContextHolder.getRequestAttributes();
        var previousSecurity = SecurityContextHolder.getContext();
        var attributes = new JobRequestAttributes();
        RequestContextHolder.setRequestAttributes(attributes);
        var jobSecurity = SecurityContextHolder.createEmptyContext();
        jobSecurity.setAuthentication(SystemPrincipal.authentication(tenantId));
        SecurityContextHolder.setContext(jobSecurity);
        try {
            return TenantContext.runAsReturning(tenantId, () -> Boolean.TRUE.equals(tx.execute(status -> {
                binder.bind(tenantId);
                if (!lock.tryLock(job, tenantId)) return false;
                body.accept(tenantId);
                return true;
            })));
        } finally {
            attributes.complete();
            SecurityContextHolder.setContext(previousSecurity);
            if (previousAttributes == null) RequestContextHolder.resetRequestAttributes();
            else RequestContextHolder.setRequestAttributes(previousAttributes);
        }
    }
}
```

> `TenantContext` has `runAs(UUID, Runnable)` but no returning variant. Either add `public static <T> T runAsReturning(UUID, Supplier<T>)` to `TenantContext` (same restore-in-finally shape as `runAs`), or capture the result in a local `boolean[]` inside `runAs`. Prefer adding the method; it is three lines. `TransactionTemplate` — confirm a bean exists (the fixture uses `tx.executeWithoutResult`, so one does); if not, construct one from the `PlatformTransactionManager`. `tenants.findAll()` is in `scheduling`, which is not in the finder rule's package list and reads the non-RLS registry — correct.

- [ ] **Step 4: Run `TenantJobRunnerTest`, `architecture.*` — PASS** (`noCyclesBetweenModules` must stay green with the new slice). **Commit** — `feat(scheduling): tenant-iterating job runner under the system actor`.

---

## Phase 2 — Clocks

### Task 10: `SlaClockReader` — the one place a clock's numbers are computed

Spec §5, invariant 4, STATE_AND_DATA L138. Pure derivation; no persistence, no HTTP.

> **Amendment (review ruling):** `dueToday` is `stoppedAt == null && state != BREACHED` (RUNNING or PAUSED), per spec �5 rule 4 (a paused clock due today belongs in the war room's Due today column); the `state == SlaClockState.RUNNING` in the code below is superseded. Also: 1e-9 epsilon on breach/at-risk/due-today, and `OPEN_DOCUMENT_REQUEST` pauses are ignored on a non-pause-eligible clock.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/platform/CalendarRules.java`, `BusinessCalendar.java`, `backend/src/main/java/co/ara/onboarding/tenancy/TenantBusinessCalendar.java` (add `startOfDay`)
- Create: `backend/src/main/java/co/ara/onboarding/sla/SlaClockReader.java`, `SlaClockView.java`, `SlaClockState.java`, `SlaPolicy.java`, `SlaPolicyReader.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaClockReaderTest.java`

**Interfaces:**
- Consumes: `BusinessCalendar` (Task 2), `SlaClock`, `SlaPause`, `PauseReason`, `SlaClockOutcome`, `EscalationRoute` (Task 5).
- Produces:
  ```java
  // platform (amends Task 2): CalendarRules.startOfDay(LocalDate), BusinessCalendar.startOfDay(LocalDate)
  public enum SlaClockState { RUNNING, PAUSED, BREACHED, MET }
  public record SlaPolicy(double atRiskDays, int escalateAfterOverdueDays) { public static SlaPolicy defaults(); }
  @Component public class SlaPolicyReader { @Transactional(propagation = MANDATORY, readOnly = true) public SlaPolicy current(); }
  public record SlaClockView(UUID clockId, UUID caseId, UUID stageId, int targetDays, double elapsedDays,
          double pausedDays, double remainingDays, SlaClockState state, boolean atRisk, boolean dueToday,
          PauseReason pauseReason, boolean pauseEligible, Instant startedAt, Instant stoppedAt,
          Instant breachedAt, EscalatedTo escalatedTo, String calendarName) {
      public record EscalatedTo(EscalationRoute route, UUID userId, String name, Instant at) {}
  }
  @Component public class SlaClockReader {
      public double elapsed(SlaClock clock, List<SlaPause> pauses, Instant now);
      public double paused(SlaClock clock, List<SlaPause> pauses, Instant now);
      public SlaClockView view(SlaClock clock, List<SlaPause> pauses, Instant now, SlaPolicy policy,
                               SlaClockView.EscalatedTo escalatedTo);
  }
  ```

- [ ] **Step 1: Amend Task 2's calendar.** Add `public Instant startOfDay(LocalDate d) { return d.atStartOfDay(zone).toInstant(); }` to `CalendarRules`, `Instant startOfDay(LocalDate d);` to `BusinessCalendar`, and a delegating override to `TenantBusinessCalendar`. Add one `CalendarRulesTest` case: `startOfDayIsMidnightInTheZone` (Auckland 2026-10-03 → `2026-10-02T11:00:00Z`). Say so in this task's commit body.

- [ ] **Step 2: Write the failing `SlaClockReaderTest`** — a plain unit test with a hand-built `BusinessCalendar`:

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.CalendarRules;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SlaClockReaderTest {

    /** Weekdays-UTC calendar whose today() follows the supplied clock. */
    static BusinessCalendar calendar(Clock clock) {
        CalendarRules r = CalendarRules.weekdaysUtc();
        return new BusinessCalendar() {
            public LocalDate plusBusinessDays(LocalDate f, int d) { return r.plusBusinessDays(f, d); }
            public int businessDaysBetween(LocalDate f, LocalDate t) { return r.businessDaysBetween(f, t); }
            public double businessDuration(Instant f, Instant t) { return r.businessDuration(f, t); }
            public LocalDate today() { return r.today(clock); }
            public LocalDate localDate(Instant i) { return r.localDate(i); }
            public Instant startOfDay(LocalDate d) { return r.startOfDay(d); }
            public String name() { return "Test calendar"; }
        };
    }

    // Monday 2026-10-05 09:00Z
    static final Instant MON_09 = Instant.parse("2026-10-05T09:00:00Z");

    static SlaClock clock(int targetDays, boolean eligible) {
        SlaClock c = new SlaClock();
        c.setId(UUID.randomUUID());
        c.setTargetDays(targetDays);
        c.setPauseEligible(eligible);
        c.setStartedAt(MON_09);
        return c;
    }

    static SlaPause pause(PauseReason reason, String from, String to) {
        SlaPause p = new SlaPause();
        p.setReason(reason);
        p.setStartedAt(Instant.parse(from));
        p.setEndedAt(to == null ? null : Instant.parse(to));
        return p;
    }

    static SlaClockReader reader(Instant now) {
        return new SlaClockReader(calendar(Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void elapsedIsBusinessTimeSinceStart() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z"); // Tue 09:00
        assertThat(reader(now).elapsed(clock(3, true), List.of(), now)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void overlappingPausesCountOnce() {
        // Review Focus 1. Hold Mon 12:00→Tue 12:00 and a request Mon 18:00→Tue 18:00 overlap;
        // their union is Mon 12:00→Tue 18:00 = 1.25 business days paused of 2.0 elapsed.
        Instant now = Instant.parse("2026-10-07T09:00:00Z"); // Wed 09:00
        var pauses = List.of(
                pause(PauseReason.CASE_HOLD, "2026-10-05T12:00:00Z", "2026-10-06T12:00:00Z"),
                pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T18:00:00Z", "2026-10-06T18:00:00Z"));
        var r = reader(now);
        assertThat(r.paused(clock(5, true), pauses, now)).isCloseTo(1.25, within(1e-9));
        assertThat(r.elapsed(clock(5, true), pauses, now)).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void anOpenPauseRunsToNowAndMakesTheClockPaused() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T21:00:00Z", null));
        var v = reader(now).view(clock(3, true), pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.PAUSED);
        assertThat(v.pauseReason()).isEqualTo(PauseReason.CASE_HOLD);
        assertThat(v.elapsedDays()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void theEarliestOpenPauseIsTheReasonShown() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(
                pause(PauseReason.CASE_HOLD, "2026-10-05T20:00:00Z", null),
                pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T10:00:00Z", null));
        assertThat(reader(now).view(clock(3, true), pauses, now, SlaPolicy.defaults(), null).pauseReason())
                .isEqualTo(PauseReason.OPEN_DOCUMENT_REQUEST);
    }

    @Test
    void reachingTheTargetIsBreached() {
        Instant now = Instant.parse("2026-10-07T09:00:00Z"); // 2.0 elapsed
        var v = reader(now).view(clock(2, true), List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.BREACHED);
        assertThat(v.remainingDays()).isCloseTo(0.0, within(1e-9));
        assertThat(v.atRisk()).isFalse();
    }

    @Test
    void atRiskWhenRemainingIsWithinThePolicy() {
        Instant now = Instant.parse("2026-10-06T21:00:00Z"); // 1.5 elapsed of 2 → 0.5 left
        var v = reader(now).view(clock(2, true), List.of(), now, new SlaPolicy(1.0, 1), null);
        assertThat(v.atRisk()).isTrue();
        assertThat(v.remainingDays()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void dueTodayWhenTheRemainingTimeRunsOutBeforeMidnight() {
        Instant now = Instant.parse("2026-10-06T21:00:00Z"); // 0.5 left, only 0.125 of Tuesday left
        assertThat(reader(now).view(clock(2, true), List.of(), now, SlaPolicy.defaults(), null).dueToday()).isFalse();
        Instant later = Instant.parse("2026-10-07T06:00:00Z"); // Wed 06:00: 0.25 left, 0.75 of Wednesday left
        assertThat(reader(later).view(clock(2, true), List.of(), later, SlaPolicy.defaults(), null).dueToday()).isTrue();
    }

    @Test
    void aStoppedClockReportsItsOutcomeAndStopsCounting() {
        SlaClock c = clock(3, true);
        c.setStoppedAt(Instant.parse("2026-10-06T09:00:00Z"));
        c.setOutcome(SlaClockOutcome.MET);
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        var v = reader(now).view(c, List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.MET);
        assertThat(v.elapsedDays()).isCloseTo(1.0, within(1e-9));
        assertThat(v.atRisk()).isFalse();
        assertThat(v.dueToday()).isFalse();
    }

    @Test
    void aBreachStampOutranksTheArithmetic() {
        // Stamped breached by the sweep, then a back-dated pause shrank elapsed below target: still BREACHED.
        SlaClock c = clock(2, true);
        c.setBreachedAt(Instant.parse("2026-10-07T09:00:00Z"));
        Instant now = Instant.parse("2026-10-07T10:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T09:00:00Z", "2026-10-06T09:00:00Z"));
        assertThat(reader(now).view(c, pauses, now, SlaPolicy.defaults(), null).state()).isEqualTo(SlaClockState.BREACHED);
    }
}
```

- [ ] **Step 3: Run — fails to compile.**
- [ ] **Step 4: Implement.**

```java
package co.ara.onboarding.sla;

public enum SlaClockState { RUNNING, PAUSED, BREACHED, MET }
```

```java
package co.ara.onboarding.sla;

/** The tenant's SLA policy (spec §4.1); defaults match V28's column defaults. */
public record SlaPolicy(double atRiskDays, int escalateAfterOverdueDays) {
    public static SlaPolicy defaults() { return new SlaPolicy(1.0, 1); }
}
```

```java
package co.ara.onboarding.sla;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Reads the bound tenant's sla_policy row (RLS-bound); a tenant without one gets the defaults. */
@Component
public class SlaPolicyReader {
    private final JdbcTemplate jdbc;
    public SlaPolicyReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public SlaPolicy current() {
        List<SlaPolicy> rows = jdbc.query(
                "SELECT at_risk_days, escalate_after_overdue_days FROM sla_policy",
                (rs, i) -> new SlaPolicy(rs.getDouble(1), rs.getInt(2)));
        return rows.isEmpty() ? SlaPolicy.defaults() : rows.get(0);
    }
}
```

```java
package co.ara.onboarding.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the browser needs to render a clock, computed once on the server (spec §8,
 * STATE_AND_DATA L138: never `now - startedAt` in the browser). escalatedTo is null until the
 * clock itself has been escalated.
 */
public record SlaClockView(UUID clockId, UUID caseId, UUID stageId, int targetDays, double elapsedDays,
        double pausedDays, double remainingDays, SlaClockState state, boolean atRisk, boolean dueToday,
        PauseReason pauseReason, boolean pauseEligible, Instant startedAt, Instant stoppedAt,
        Instant breachedAt, EscalatedTo escalatedTo, String calendarName) {

    /** userId and name are null when route is ADMINISTRATORS. */
    public record EscalatedTo(EscalationRoute route, UUID userId, String name, Instant at) {}
}
```

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/**
 * The one computation of a clock's numbers (spec §5, invariant 4). Elapsed is business time from
 * start to stop-or-now minus the business time of the UNION of the pause intervals, so overlapping
 * hold and document-request pauses count once (rule 2).
 */
@Component
public class SlaClockReader {

    private final BusinessCalendar calendar;

    public SlaClockReader(BusinessCalendar calendar) { this.calendar = calendar; }

    public double elapsed(SlaClock clock, List<SlaPause> pauses, Instant now) {
        Instant end = end(clock, now);
        return Math.max(0, calendar.businessDuration(clock.getStartedAt(), end) - paused(clock, pauses, now));
    }

    public double paused(SlaClock clock, List<SlaPause> pauses, Instant now) {
        Instant end = end(clock, now);
        double total = 0;
        for (Instant[] interval : union(pauses, clock.getStartedAt(), end)) {
            total += calendar.businessDuration(interval[0], interval[1]);
        }
        return total;
    }

    public SlaClockView view(SlaClock clock, List<SlaPause> pauses, Instant now, SlaPolicy policy,
                             SlaClockView.EscalatedTo escalatedTo) {
        double elapsed = elapsed(clock, pauses, now);
        double paused = paused(clock, pauses, now);
        double remaining = Math.max(0, clock.getTargetDays() - elapsed);
        Optional<SlaPause> open = clock.getStoppedAt() == null
                ? pauses.stream().filter(p -> p.getEndedAt() == null).min(Comparator.comparing(SlaPause::getStartedAt))
                : Optional.empty();

        SlaClockState state;
        if (clock.getStoppedAt() != null) {
            state = clock.getOutcome() == SlaClockOutcome.BREACHED ? SlaClockState.BREACHED : SlaClockState.MET;
        } else if (clock.getBreachedAt() != null || elapsed >= clock.getTargetDays()) {
            state = SlaClockState.BREACHED;
        } else if (open.isPresent()) {
            state = SlaClockState.PAUSED;
        } else {
            state = SlaClockState.RUNNING;
        }

        boolean live = clock.getStoppedAt() == null && state != SlaClockState.BREACHED;
        boolean atRisk = live && remaining <= policy.atRiskDays();
        boolean dueToday = state == SlaClockState.RUNNING && remaining <= calendar.businessDuration(now,
                calendar.startOfDay(calendar.localDate(now).plusDays(1)));

        return new SlaClockView(clock.getId(), clock.getCaseId(), clock.getStageId(), clock.getTargetDays(),
                elapsed, paused, remaining, state, atRisk, dueToday,
                open.map(SlaPause::getReason).orElse(null), clock.isPauseEligible(),
                clock.getStartedAt(), clock.getStoppedAt(), clock.getBreachedAt(), escalatedTo, calendar.name());
    }

    private static Instant end(SlaClock clock, Instant now) {
        return clock.getStoppedAt() != null ? clock.getStoppedAt() : now;
    }

    /** Pause intervals clipped to [start, end] and merged where they overlap. */
    private static List<Instant[]> union(List<SlaPause> pauses, Instant start, Instant end) {
        List<Instant[]> clipped = new ArrayList<>();
        for (SlaPause p : pauses) {
            Instant a = p.getStartedAt().isBefore(start) ? start : p.getStartedAt();
            Instant b = p.getEndedAt() == null || p.getEndedAt().isAfter(end) ? end : p.getEndedAt();
            if (b.isAfter(a)) clipped.add(new Instant[]{a, b});
        }
        clipped.sort(Comparator.comparing(i -> i[0]));
        List<Instant[]> merged = new ArrayList<>();
        for (Instant[] i : clipped) {
            if (!merged.isEmpty() && !i[0].isAfter(merged.get(merged.size() - 1)[1])) {
                Instant[] last = merged.get(merged.size() - 1);
                if (i[1].isAfter(last[1])) last[1] = i[1];
            } else {
                merged.add(new Instant[]{i[0], i[1]});
            }
        }
        return merged;
    }
}
```

- [ ] **Step 5: Run `SlaClockReaderTest` and `CalendarRulesTest` — PASS. Commit** — `feat(sla): derive clock elapsed, state, at-risk and due-today in one place`.

---

### Task 11: The journey port — stage, hold and resume

Spec §3.2, §1.2.1, invariants 1–3.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/SlaClockLifecycle.java`
- Create: `backend/src/main/java/co/ara/onboarding/sla/SlaClockWriter.java`, `SlaClockLifecycleAdapter.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequestRepository.java` (one count method)
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java` (constructor L72–82; `advanceIfExitable` after L343; `enterStage` after L517)
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseService.java` (constructor; `create` L270–276; `hold` after L493; `doResume` after L598)
- Modify: `backend/src/main/java/co/ara/onboarding/journey/MilestoneService.java` (constructor; `reopen` around L208)
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/sla/SlaTestSupport.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaClockLifecycleTest.java`

**Interfaces:**
- Produces:
  ```java
  // journey
  public interface SlaClockLifecycle {
      void stageEntered(UUID caseId, UUID stageId, Instant at);
      void stageExited(UUID caseId, Instant at);
      void held(UUID caseId, Instant at);
      void resumed(UUID caseId, Instant at);
  }
  // document
  long countByCaseIdAndStatus(UUID caseId, DocumentRequestStatus status);   // on DocumentRequestRepository
  // sla (package-private; Task 12's adapter uses it too)
  @Component class SlaClockWriter {
      void start(UUID caseId, UUID stageId, Instant at);
      void stopOpen(UUID caseId, Instant at);
      void openPause(UUID caseId, PauseReason reason, Instant at);
      void closePause(UUID caseId, PauseReason reason, Instant at);
      long openRequestCount(UUID caseId);
  }
  // test support (Tasks 12-22 reuse it)
  @Component class SlaTestSupport {
      UUID caseWithSla(UUID tenant, int slaDays, boolean pausesOnCustomer);          // one stage, one MANUAL requirement; runs inside runAs
      UUID twoStageCaseWithSla(UUID tenant, int firstSla, int secondSla);
      UUID openClockId(UUID caseId);                                                 // null if none
      List<String> openPauseReasons(UUID clockId);
      long closedPauses(UUID clockId);
      List<Map<String, Object>> clocks(UUID caseId);                                 // ordered by started_at
      UUID firstRequirementId(UUID caseId);
  }
  ```

- [ ] **Step 1: Write `SlaTestSupport`.** Build workflows with the existing builders — read `WorkflowFixtures` (`stage`, `milestone`, `manual`) and `JourneyFixtures.publish(WorkflowDefinitionRequest)`/`templateOf(versionId)` first, and use whichever `StageRequest` construction they use, setting `slaDays` and `pausesOnCustomer` (the new canonical constructor's last argument) and **`autoAdvance = true`** on every stage. Create the customer with `fixture.createCustomerOwnedBy(tenant, "Customer", ownerUserId)` and the case with `cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), "SLA case", Map.of()))`, exactly as `CaseCreationTest:45` does. `firstRequirementId` copies `CauseBeforeEffectTest.firstRequirementId`. The read helpers use `ownerJdbc()` (assertions only — never assert RLS through it).

- [ ] **Step 2: Write the failing `SlaClockLifecycleTest`.** Each test runs inside `fixture.runAs(tenant, …)` unless it asserts an exception:
  1. `enteringAStageWithAnSlaStartsAClock` — `caseWithSla(t, 3, true)` ⇒ one open clock, `target_days = 3`, `stage_id` = the case's `current_stage_id`.
  2. `aStageWithoutAnSlaGetsNoClock` — a stage with `slaDays = null` ⇒ zero rows.
  3. `advancingStopsTheClockAsMetAndStartsTheNext` — `twoStageCaseWithSla(t, 3, 2)`, satisfy the first requirement through `RequirementService.satisfy(id, null, null)` (read the real signature) ⇒ two clocks, the first stopped `MET`, the second open.
  4. `completingTheCaseStopsTheLastClock` — one stage, satisfy ⇒ stopped `MET`, none open.
  5. `anOverdueExitIsRecordedBreached` — `caseWithSla(t, 1, true)`, `clock.advance(Duration.ofDays(4))`, satisfy ⇒ `BREACHED`.
  6. `aSkippedStageGetsNoClock` — three stages with SLAs where the middle one's `entryCondition` is false: copy `TransitionTest`'s branch-skip arrange block (it seeds an `ATTRIBUTE` condition); ⇒ clocks for stages 1 and 3 only.
  7. `holdingPausesAndResumingCloses` — `cases.hold(caseId, "waiting")` ⇒ one open `CASE_HOLD`; `cases.resume(caseId)` ⇒ none open, one closed.
  8. `aCustomerTemplateCaseStartsPaused` — copy `CauseBeforeEffectTest.openHeldCaseOnApprovedCustomerTemplate`'s arrange, give its stage an SLA ⇒ the first clock has an open `CASE_HOLD` pause starting within one second of the clock's `started_at`.
  9. `reopeningIntoAnEarlierStageStartsAFreshClock` (Review Focus 4) — `twoStageCaseWithSla`, satisfy both requirements (case `COMPLETED`), then `MilestoneService.reopen(<stage-1 milestone id>, "rework")` ⇒ the two earlier clocks unchanged and stopped, a third open clock on stage 1.
  10. `reopeningWithinTheCurrentStageLeavesTheClockAlone` — reopen a milestone of the current stage ⇒ exactly one open clock, same id as before.
  11. `everyClockWriteRollsBackWithItsCause` — run `cases.hold(caseId, "x")` inside a `TransactionTemplate` that calls `status.setRollbackOnly()` afterwards ⇒ no `CASE_HOLD` pause exists (invariant 1).

- [ ] **Step 3: Run — fails.**
- [ ] **Step 4: Implement the port, the writer and the adapter.**

```java
package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.UUID;

/**
 * The SLA clock's view of a case's lifecycle (spec §3.2). Declared here, implemented by sla, so
 * journey never imports sla (invariant 3). Called only where currentStageId changes -- CaseEngine
 * and MilestoneService.reopen -- and from hold/resume, always inside the caller's transaction
 * (invariants 1, 2).
 */
public interface SlaClockLifecycle {
    void stageEntered(UUID caseId, UUID stageId, Instant at);
    void stageExited(UUID caseId, Instant at);
    void held(UUID caseId, Instant at);
    void resumed(UUID caseId, Instant at);
}
```

`DocumentRequestRepository`: add `long countByCaseIdAndStatus(UUID caseId, DocumentRequestStatus status);`

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Every write to sla_clock/sla_pause (spec §5). Runs only inside a caller's transaction
 * (MANDATORY) on a case id that caller already resolved under its own gate -- which is why it
 * carries a FINDER_RULE_EXCLUSIONS entry and reads repositories directly (the
 * TaskLifecycleAdapter precedent). Both lifecycle adapters delegate here and inject nothing else.
 */
@Component
class SlaClockWriter {

    private final SlaClockRepository clocks;
    private final SlaPauseRepository pauses;
    private final StageRepository stages;
    private final CaseRepository cases;
    private final DocumentRequestRepository requests;
    private final SlaClockReader reader;

    SlaClockWriter(SlaClockRepository clocks, SlaPauseRepository pauses, StageRepository stages,
                   CaseRepository cases, DocumentRequestRepository requests, SlaClockReader reader) {
        this.clocks = clocks; this.pauses = pauses; this.stages = stages;
        this.cases = cases; this.requests = requests; this.reader = reader;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void start(UUID caseId, UUID stageId, Instant at) {
        stopOpen(caseId, at);   // defensive; one open clock per case is also a unique index
        Stage stage = stages.findById(stageId).orElseThrow();
        if (stage.getSlaDays() == null || stage.getSlaDays() <= 0) return;
        SlaClock c = new SlaClock();
        c.setId(Uuid7.generate());
        c.setTenantId(TenantContext.getRequired());
        c.setCaseId(caseId);
        c.setStageId(stageId);
        c.setTargetDays(stage.getSlaDays());
        c.setPauseEligible(stage.isPausesOnCustomer());
        c.setStartedAt(at);
        clocks.saveAndFlush(c);
        // A stage entered while the case is already waiting starts paused.
        Case kase = cases.findById(caseId).orElseThrow();
        if (kase.getStatus() == CaseStatus.ON_HOLD) openPause(caseId, PauseReason.CASE_HOLD, at);
        if (openRequestCount(caseId) > 0) openPause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void stopOpen(UUID caseId, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c -> {
            for (SlaPause p : pauses.findByClockIdAndEndedAtIsNull(c.getId())) {
                p.setEndedAt(at);
                pauses.save(p);
            }
            double elapsed = reader.elapsed(c, pauses.findByClockId(c.getId()), at);
            c.setStoppedAt(at);
            c.setOutcome(c.getBreachedAt() != null || elapsed >= c.getTargetDays()
                    ? SlaClockOutcome.BREACHED : SlaClockOutcome.MET);
            clocks.saveAndFlush(c);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void openPause(UUID caseId, PauseReason reason, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c -> {
            if (reason == PauseReason.OPEN_DOCUMENT_REQUEST && !c.isPauseEligible()) return;
            if (pauses.findByClockIdAndReasonAndEndedAtIsNull(c.getId(), reason).isPresent()) return;
            SlaPause p = new SlaPause();
            p.setId(Uuid7.generate());
            p.setTenantId(c.getTenantId());
            p.setClockId(c.getId());
            p.setReason(reason);
            p.setStartedAt(at);
            pauses.saveAndFlush(p);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void closePause(UUID caseId, PauseReason reason, Instant at) {
        clocks.findByCaseIdAndStoppedAtIsNull(caseId).ifPresent(c ->
                pauses.findByClockIdAndReasonAndEndedAtIsNull(c.getId(), reason).ifPresent(p -> {
                    p.setEndedAt(at);
                    pauses.saveAndFlush(p);
                }));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    long openRequestCount(UUID caseId) {
        return requests.countByCaseIdAndStatus(caseId, DocumentRequestStatus.OPEN);
    }
}
```

> `Case`, `CaseRepository`, `CaseStatus`, `StageRepository`, `DocumentRequestRepository`, `DocumentRequestStatus` must be `public`. Check each; make any package-private one public with a one-line javadoc saying `sla` reads it.

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.journey.SlaClockLifecycle;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class SlaClockLifecycleAdapter implements SlaClockLifecycle {
    private final SlaClockWriter writer;
    public SlaClockLifecycleAdapter(SlaClockWriter writer) { this.writer = writer; }

    @Override public void stageEntered(UUID caseId, UUID stageId, Instant at) { writer.start(caseId, stageId, at); }
    @Override public void stageExited(UUID caseId, Instant at) { writer.stopOpen(caseId, at); }
    @Override public void held(UUID caseId, Instant at) { writer.openPause(caseId, PauseReason.CASE_HOLD, at); }
    @Override public void resumed(UUID caseId, Instant at) { writer.closePause(caseId, PauseReason.CASE_HOLD, at); }
}
```

- [ ] **Step 5: Wire the call sites.** Add a `SlaClockLifecycle slaClocks` constructor parameter and field to `CaseEngine`, `CaseService` and `MilestoneService` (all Spring-constructed; if any test constructs one by hand, `grep -rn "new CaseEngine(\|new CaseService(\|new MilestoneService(" backend/src/test` and pass a no-op lambda-implemented instance). Then:

  - **`CaseEngine.enterStage`**, after the `CASE_STAGE_ENTERED` audit record (L516–517): `slaClocks.stageEntered(c.getId(), stage.getId(), Instant.now(clock));` — cause before effect.
  - **`CaseEngine.advanceIfExitable`**, immediately after `Stage next = nextStage(c, current, stages, definitions, instances, true);` (L343), before `if (next == null)`: `slaClocks.stageExited(c.getId(), Instant.now(clock));` — the single point covering advance and completion, reached only once every gate has passed. The exit is not separately audited, so its position relative to `CASE_COMPLETED` does not matter.
  - **`CaseService.create`**, inside the `if (startsHeldPendingPlanApproval && …)` block, after its `CASE_HELD` audit record (L276): `slaClocks.held(c.getId(), c.getHeldAt());`.
  - **`CaseService.hold`**, after the `CASE_HELD` audit record (L493): `slaClocks.held(c.getId(), c.getHeldAt());`.
  - **`CaseService.doResume`**, after the `CASE_RESUMED` audit record (L598), before `engine.reconcile(c)` (L600): `slaClocks.resumed(c.getId(), Instant.now(clock));`.
  - **`MilestoneService.reopen`**: before L208 capture `UUID previousStage = c.getCurrentStageId(); boolean wasCompleted = c.getStatus() == CaseStatus.COMPLETED;`; after the `MILESTONE_REOPENED` audit record (L215):

    ```java
            // Spec 1.2.1: reopen is the one stage-change path outside CaseEngine.
            if (wasCompleted || !definition.getStageId().equals(previousStage)) {
                Instant now = Instant.now(clock);
                slaClocks.stageExited(c.getId(), now);
                slaClocks.stageEntered(c.getId(), definition.getStageId(), now);
            }
    ```

  **No other `CaseEngine` change.** `reconcile`'s mutation logic and lock are untouched (spec §12.2).

- [ ] **Step 6: `AuthorizationCoverageTest`.** Add `"co.ara.onboarding.sla.."` to `servicesDoNotCallRepositoryFindersDirectly`'s package list in this commit, and one entry to `FINDER_RULE_EXCLUSIONS`:

```java
            // Runs only inside CaseEngine/CaseService/MilestoneService/DocumentRequestService's own
            // transaction (MANDATORY) on a case id the caller already resolved under its own gate; it
            // never receives an id from a request. The TaskLifecycleAdapter precedent. Both SLA
            // lifecycle adapters delegate here and inject no repository themselves (spec 3.2).
            "co.ara.onboarding.sla.SlaClockWriter",
```

Update the pin test (`AuthorizationCoverageTest:539-559`) to include it. This is the one exclusion the Global Constraints permit; `CustomerWaitLifecycleAdapter` (Task 12) injects only the writer and needs none.

- [ ] **Step 7: Run `SlaClockLifecycleTest`, then `journey.*`, `architecture.*`, `sla.*` — PASS. Full suite. Commit** — `feat(sla): clocks start, stop and pause with the case lifecycle`.

---

### Task 12: The document port — customer waits

Spec §3.2, §5 rule 3.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/document/CustomerWaitLifecycle.java`
- Create: `backend/src/main/java/co/ara/onboarding/sla/CustomerWaitLifecycleAdapter.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequestService.java` (constructor; `create` after L135; `withdraw` after L229; `fulfil` after L343, before L347)
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentInstantiation.java` (constructor; after L128)
- Test: `backend/src/test/java/co/ara/onboarding/sla/CustomerWaitTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface CustomerWaitLifecycle {
      void requestOpened(UUID caseId, Instant at);
      void requestClosed(UUID caseId, Instant at);
  }
  ```

- [ ] **Step 1: Write the failing `CustomerWaitTest`.** Arrange with `SlaTestSupport.caseWithSla`; act through `DocumentRequestService` as an administrator (`fixture.runAsUser(tenant, admin.getId(), …)`), creating requests with `new CreateDocumentRequestRequest(DocumentCategory.<any>, "desc", null, false, null)`; fulfil with a document uploaded through the existing test helper for documents (read `DocumentRequestServiceTest`'s fulfil arrange and copy it).
  1. `openingARequestPausesAnEligibleClock` — ⇒ one open `OPEN_DOCUMENT_REQUEST` pause.
  2. `anIneligibleStageIsNotPausedByARequest` — `pausesOnCustomer = false` ⇒ no pause; then `hold` ⇒ a `CASE_HOLD` pause (rule 3).
  3. `twoRequestsShareOneIntervalUntilBothClose` — two requests, fulfil one ⇒ one open pause; withdraw the other ⇒ none open, exactly one closed.
  4. `fulfilmentResumesTheClockEvenWhenReviewIsPending` — `requiresReview = true`, fulfilled ⇒ pause closed.
  5. `resumeWithAnOpenRequestStaysPaused` (Review Focus 1) — open a request, hold, resume ⇒ the `OPEN_DOCUMENT_REQUEST` pause is still open and `SlaClockService.forCase(caseId).state()` is `PAUSED` (Task 13 adds the service — if running Task 12 first, assert on `openPauseReasons` only and add the state assertion in Task 13's commit).
  6. `anInstantiatedRequestPausesTheFirstClock` — a DOCUMENT requirement in the stage (`WorkflowFixtures.document(...)`) ⇒ the first clock starts with an open `OPEN_DOCUMENT_REQUEST` pause. `DocumentInstantiation` runs at `CaseService.create` L247, **before** `engine.reconcile` (L256) enters the first stage, so `requestOpened` is a no-op there and the pause comes from `SlaClockWriter.start`'s "requests already open" check — this test is that check's reason to exist.

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.document;

import java.time.Instant;
import java.util.UUID;

/**
 * The SLA clock's view of a case waiting on its customer (spec §3.2). Declared here, implemented
 * by sla, so document never imports sla. Called at every document_request.status write, inside the
 * caller's transaction: OPEN (create, DocumentInstantiation) opens; FULFILLED or WITHDRAWN closes.
 * FULFILLED means "the customer uploaded", even while internal review is pending -- review is our
 * work, not the customer's.
 */
public interface CustomerWaitLifecycle {
    void requestOpened(UUID caseId, Instant at);
    void requestClosed(UUID caseId, Instant at);
}
```

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.document.CustomerWaitLifecycle;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class CustomerWaitLifecycleAdapter implements CustomerWaitLifecycle {
    private final SlaClockWriter writer;
    public CustomerWaitLifecycleAdapter(SlaClockWriter writer) { this.writer = writer; }

    @Override public void requestOpened(UUID caseId, Instant at) {
        writer.openPause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
    }

    /** One interval for any number of open requests (spec §5 rule 3): close only when none remain. */
    @Override public void requestClosed(UUID caseId, Instant at) {
        if (writer.openRequestCount(caseId) == 0) {
            writer.closePause(caseId, PauseReason.OPEN_DOCUMENT_REQUEST, at);
        }
    }
}
```

Call sites — add a `CustomerWaitLifecycle customerWaits` constructor parameter to `DocumentRequestService` and `DocumentInstantiation`:
  - `create`, after the `DOCUMENT_REQUESTED` audit record (L135): `customerWaits.requestOpened(c.getId(), dr.getRequestedAt());`
  - `withdraw`, inside the `if (status != WITHDRAWN)` branch after its audit record (L229): `customerWaits.requestClosed(dr.getCaseId(), Instant.now(clock));`
  - `fulfil`, after the `DOCUMENT_REQUEST_FULFILLED` audit record (L343) and **before** `requirementService.satisfy` (L347), so a satisfy that advances the stage sees the pause already closed: `customerWaits.requestClosed(dr.getCaseId(), Instant.now(clock));`
  - `DocumentInstantiation.instantiateForCase`, after `documentRequests.save(dr)` (L128): `customerWaits.requestOpened(caseId, dr.getRequestedAt());`

> `requestClosed` counts OPEN rows, so the status change must already be flushed: `withdraw` (L222) and `fulfil` (L338) use `saveAndFlush`. Confirm before relying on it. If `requestedAt` is not set on the instantiated request, use `Instant.now(clock)`.

- [ ] **Step 4: Run `CustomerWaitTest`, `document.*`, `architecture.*` — PASS. Full suite. Commit** — `feat(sla): open customer document requests pause eligible clocks`.

---

### Task 13: Reading a case's clock — `GET /cases/{id}/sla-clock`

Spec §8.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/sla/SlaClockService.java`, `SlaClockController.java`, `UnprocessableException.java`, `SlaExceptionHandler.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/SlaPauseDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaClockApiTest.java`

**Interfaces:**
- Consumes: `SlaClockReader`, `SlaPolicyReader`, `ReportingLineDirectory.activeUser`, `AuthorizedQuery`.
- Produces:
  ```java
  @Service public class SlaClockService {
      @RequirePermission(PermissionKeys.CASE_VIEW) @Transactional(readOnly = true)
      public SlaClockView forCase(UUID caseId);   // 404 when out of scope, cross-tenant, or no clock
      @RequirePermission({PermissionKeys.SLA_VIEW, PermissionKeys.CASE_VIEW}) @Transactional(readOnly = true)
      public Map<UUID, SlaClockView> views(List<SlaClock> clocks);   // keyed by clock id, input order
  }
  public class UnprocessableException extends RuntimeException { public UnprocessableException(String message); }
  ```

- [ ] **Step 1: Write the failing `SlaClockApiTest`** (extends `SecurityTestBase`; MockMvc `as(get("/api/t/{slug}/cases/{id}/sla-clock"), user)`):
  1. `anAdministratorReadsTheCurrentClock` — 200, `$.state` `RUNNING`, `$.targetDays` 3, `$.calendarName` non-blank.
  2. `theLastStoppedClockIsReturnedWhenNoneIsOpen` — complete a one-stage case ⇒ `$.state` `MET`.
  3. `aCaseWithNoClockIs404` — stage without SLA.
  4. `anOutOfScopeCaseIs404NotForbidden` — a user holding `case.view` at TEAM on a case outside their teams ⇒ 404 (build the role with `roles`/`RoleService` as `security.InsufficientScopeTest` does — read it first).
  5. `anotherTenantsCaseIs404`.
  6. `theViewCarriesItsEscalation` — insert an `escalation` row for the clock (`subject_type 'SLA_CLOCK'`, `route 'MANAGER'`, `escalated_to_user_id` = an active fixture user) via `ownerJdbc()` ⇒ `$.escalatedTo.route` `MANAGER`, `$.escalatedTo.name` = that user's full name.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `scoping.SlaPauseDescriptor` — the `CaseParticipantDescriptor` shape, two levels deep: `root.get("clockId").in(<subquery selecting SlaClock.id where SlaClock.caseId in (the case condition)>)`; `resourceType()` `"sla_pause"`; same relationships; fails closed identically.

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.ReportingLineDirectory;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SlaClockService {

    private final AuthorizedQuery authorizedQuery;
    private final CaseRepository cases;
    private final SlaClockRepository clocks;
    private final SlaPauseRepository pauses;
    private final EscalationRepository escalations;
    private final SlaClockReader reader;
    private final SlaPolicyReader policies;
    private final ReportingLineDirectory people;
    private final Clock clock;

    public SlaClockService(AuthorizedQuery authorizedQuery, CaseRepository cases, SlaClockRepository clocks,
                           SlaPauseRepository pauses, EscalationRepository escalations, SlaClockReader reader,
                           SlaPolicyReader policies, ReportingLineDirectory people, Clock clock) {
        this.authorizedQuery = authorizedQuery; this.cases = cases; this.clocks = clocks; this.pauses = pauses;
        this.escalations = escalations; this.reader = reader; this.policies = policies; this.people = people;
        this.clock = clock;
    }

    /** The case's most recent clock (the open one if any). 404 when out of scope or none exists. */
    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public SlaClockView forCase(UUID caseId) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);
        Specification<SlaClock> ofCase = (root, q, cb) -> cb.equal(root.get("caseId"), c.getId());
        SlaClock latest = authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.CASE_VIEW, ofCase,
                        PageRequest.of(0, 1, Sort.by("startedAt").descending()))
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Not found"));
        return views(List.of(latest)).get(latest.getId());
    }

    @RequirePermission({PermissionKeys.SLA_VIEW, PermissionKeys.CASE_VIEW})
    @Transactional(readOnly = true)
    public Map<UUID, SlaClockView> views(List<SlaClock> list) {
        if (list.isEmpty()) return Map.of();
        Instant now = Instant.now(clock);
        SlaPolicy policy = policies.current();
        Set<UUID> clockIds = list.stream().map(SlaClock::getId).collect(Collectors.toSet());
        Set<UUID> caseIds = list.stream().map(SlaClock::getCaseId).collect(Collectors.toSet());
        Specification<SlaPause> ofClocks = (root, q, cb) -> root.get("clockId").in(clockIds);
        Map<UUID, List<SlaPause>> pausesByClock = authorizedQuery
                .findAll(pauses, SlaPause.class, PermissionKeys.CASE_VIEW, ofClocks, Pageable.unpaged())
                .stream().collect(Collectors.groupingBy(SlaPause::getClockId));
        Specification<Escalation> clockEscalations = (root, q, cb) -> cb.and(
                root.get("caseId").in(caseIds),
                cb.equal(root.get("subjectType"), EscalationSubject.SLA_CLOCK));
        Map<UUID, Escalation> escalationByClock = authorizedQuery
                .findAll(escalations, Escalation.class, PermissionKeys.CASE_VIEW, clockEscalations,
                        PageRequest.of(0, Integer.MAX_VALUE, Sort.by("escalatedAt").descending()))
                .stream().collect(Collectors.toMap(Escalation::getSubjectId, e -> e, (a, b) -> a));
        Map<UUID, SlaClockView> out = new LinkedHashMap<>();
        for (SlaClock c : list) {
            Escalation e = escalationByClock.get(c.getId());
            SlaClockView.EscalatedTo to = e == null ? null : new SlaClockView.EscalatedTo(e.getRoute(),
                    e.getEscalatedToUserId(),
                    people.activeUser(e.getEscalatedToUserId())
                            .map(ReportingLineDirectory.Recipient::fullName).orElse(null),
                    e.getEscalatedAt());
            out.put(c.getId(), reader.view(c, pausesByClock.getOrDefault(c.getId(), List.of()), now, policy, to));
        }
        return out;
    }
}
```

> `PageRequest.of(0, Integer.MAX_VALUE, …)` — if `AuthorizedQuery.findAll` rejects that, use `Pageable.unpaged()` and sort the result in Java by `escalatedAt` descending. `people.activeUser` of a since-deactivated recipient returns empty — the name is then null and the UI shows the route only; acceptable and tested nowhere else, so add a one-line comment.

```java
package co.ara.onboarding.sla;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class SlaClockController {
    private final SlaClockService service;
    public SlaClockController(SlaClockService service) { this.service = service; }

    @GetMapping("/cases/{id}/sla-clock")
    public SlaClockView forCase(@PathVariable UUID id) { return service.forCase(id); }
}
```

Give it `@ApiResponses` (200, 404) in `CaseController`'s style.

```java
package co.ara.onboarding.sla;

/** A well-formed request that breaks a rule the spec answers with 422 (spec §8). */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) { super(message); }
}
```

`SlaExceptionHandler`: a `@RestControllerAdvice` mapping `UnprocessableException` to `ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage())` — copy `tenancy.TenantExceptionHandler`'s form.

- [ ] **Step 4: Run `SlaClockApiTest`, `sla.*`, `architecture.*` (DescriptorRegistry + finder rule) — PASS. Commit** — `feat(sla): read a case's clock`.

---

## Phase 3 — The sweep

### Task 14: Breach stamping

Spec §6.1 step 1, invariant 9.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/sla/SlaSweepService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaSweepBreachTest.java`

**Interfaces:**
- Produces:
  ```java
  @Service public class SlaSweepService {
      @RequirePermission(PermissionKeys.SLA_VIEW) @Transactional(propagation = Propagation.MANDATORY)
      public int stampBreaches();
      @RequirePermission(PermissionKeys.SLA_VIEW) @Transactional(propagation = Propagation.MANDATORY)
      public void sweep();   // the escalation-writing steps, in spec order; Task 15 and 16 extend it
  }
  AuditActions.SLA_BREACHED, ESCALATION_RAISED, NOTIFICATION_SENT   // all timeline_visible = false
  ```

- [ ] **Step 1: Write the failing `SlaSweepBreachTest`.** Arrange with `SlaTestSupport.caseWithSla(tenant, 1, true)`; run the way production will, `runner.forTenant("sla-sweep", tenant, t -> count[0] = sweep.stampBreaches())`:
  1. `aClockPastItsTargetIsStampedOnce` — `clock.advance(Duration.ofDays(4))`; the first run stamps 1 and sets `breached_at`; the second stamps 0 and leaves `breached_at` unchanged.
  2. `aClockWithinTargetIsNotStamped` — no advance ⇒ 0.
  3. `aPausedClockIsNotStampedForPausedTime` — hold first, then advance ⇒ 0.
  4. `breachIsAuditedAndHiddenFromTheTimeline` — one `sla.breached` `audit_event` row for the clock with `timeline_visible = false` and `actor_type = 'SYSTEM'` (via `ownerJdbc()`), and `TimelineService.forCase(caseId, …)` (as an admin) contains no `sla.breached` (invariant 9).
  5. `stoppedClocksAreNeverStamped` — complete the case, then advance ⇒ 0.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `AuditActions`, a new commented group:

```java
    // SLA & escalation (sub-project 6). All compliance-only: the design hides SLA mechanics from the
    // customer (SCREENS L344) and the portal reads this same flag (spec 4.7, invariant 9).
    public static final AuditAction SLA_BREACHED      = of("sla.breached", false);
    public static final AuditAction ESCALATION_RAISED = of("escalation.raised", false);
    public static final AuditAction NOTIFICATION_SENT = of("notification.sent", false);
```

`SlaSweepService.stampBreaches()`: read open, unstamped clocks through `authorizedQuery.findAll(clocks, SlaClock.class, SLA_VIEW, (r, q, cb) -> cb.and(cb.isNull(r.get("stoppedAt")), cb.isNull(r.get("breachedAt"))), Pageable.unpaged())`; read their pauses through `AuthorizedQuery` under `CASE_VIEW` (the Task 13 shape); for each clock with `reader.elapsed(c, pauses, now) >= c.getTargetDays()`: **first** run the conditional `clocks.stampBreach(id, now)` (`@Modifying(flushAutomatically = true, clearAutomatically = true)`, `UPDATE ... WHERE breached_at IS NULL AND stopped_at IS NULL`; read clocks ordered by id), **then**, only when exactly one row changed, `audit.record(AuditActions.SLA_BREACHED, "sla_clock", c.getId(), "SLA breached", Map.of("caseId", c.getCaseId().toString(), "targetDays", c.getTargetDays()))` in the same transaction (deviation from the original audit-first order: the sweep does not hold the case lock, so audit-first could leave an audit row for a clock a concurrent stop won; a lost race is 0 rows and is skipped). `SlaClock` is `@DynamicUpdate` and `SlaClockWriter.stopOpen` refreshes under `PESSIMISTIC_WRITE`. Return the count. `sweep()` calls `stampBreaches()`.

> Saving through `clocks.save` is a write, not a finder — the finder rule allows it. `AuditRecorder.record` is `MANDATORY` and reads the request-scoped `RequestAuditContext`; inside `TenantJobRunner` both hold, and the recorded actor is `SYSTEM` with a null user id — confirm `audit_event.actor_user_id` is nullable in `V5` (it is: `actor_user_id uuid` with no `NOT NULL`).

- [ ] **Step 4: Run — PASS. Commit** — `feat(sla): the sweep stamps breaches once`.

---

### Task 15: Finding overdue work and raising escalations exactly once

Spec §6.1 steps 2–3, §1.2.8–§1.2.9, invariant 5. This task routes every escalation to administrators; Task 16 completes the chain and adds notifications. The `escalation` row's shape does not change between them.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/sla/SlaSweepService.java`
- Create: `backend/src/main/java/co/ara/onboarding/sla/RecipientResolver.java` (first version), `EscalationWriter.java`, `RaisedEscalation.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaSweepEscalationTest.java`, `SweepConcurrencyTest.java`

**Interfaces:**
- Produces:
  ```java
  @Component public class RecipientResolver {
      public record Resolution(EscalationRoute route, List<ReportingLineDirectory.Recipient> recipients) {
          public UUID primaryUserId();   // the single recipient for MANAGER/DEPARTMENT_HEAD, else null
      }
      @Transactional(propagation = MANDATORY, readOnly = true) public Resolution resolve(UUID latePersonId);
  }
  public record RaisedEscalation(UUID escalationId, EscalationSubject subjectType, UUID subjectId, UUID caseId,
                                 String caseName, UUID customerId, UUID latePersonId,
                                 RecipientResolver.Resolution resolution, int overdueDays) {}
  @Component class EscalationWriter {
      Optional<UUID> insert(EscalationSubject type, UUID subjectId, UUID caseId, UUID latePersonId,
                            RecipientResolver.Resolution resolution, LocalDate dueDate, int overdueDays, Instant at);
  }
  // SlaSweepService
  @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public List<RaisedEscalation> escalateOverdue();
  ```

- [ ] **Step 1: Write the failing `SlaSweepEscalationTest`.** Seed through real services where cheap (`TaskService.create` for tasks — read its request record) and `ownerJdbc()` updates where a precondition is only a column (`task.due_date`, `milestone.due_date`, `milestone.owner_user_id`, `sla_clock.breached_at`). Compute every date **from `calendar.today()` inside `fixture.runAs`**, never the wall clock. `today.minusDays(7)` is always at least one business day back under any calendar with a working day per week. Run with `runner.forTenant("sla-sweep", tenant, t -> raised.addAll(sweep.escalateOverdue()))`.
  1. `anOverdueTaskEscalatesOnce` — task with an assignee, due `today.minusDays(7)` ⇒ one row, `subject_type TASK`, `late_user_id` = assignee; a second run raises nothing.
  2. `aTaskDueTodayDoesNotEscalate`.
  3. `cancelledAndCompletedTasksNeverEscalate`.
  4. `anOverdueMilestoneEscalatesToItsOwnerElseTheCaseOwner` — two milestones, one with `owner_user_id` set ⇒ `late_user_id` = that owner, the other ⇒ the case's `owner_user_id`.
  5. `aBreachedClockEscalatesAfterThePolicyDelay` — `breached_at` = 7 days ago ⇒ one `SLA_CLOCK` row with `due_date_at_escalation = calendar.localDate(breached_at)`; with `breached_at` = now ⇒ nothing.
  6. `heldCasesAreSkipped` — overdue task on a held case ⇒ nothing.
  7. `aRedatedTaskCanEscalateAgain` — escalate, move `due_date` to `today.minusDays(14)` ⇒ a second row; run again ⇒ no third.
  8. `overdueIsJudgedInTheTenantZone` (Review Focus 2) — set the tenant calendar's `timezone` to `Pacific/Auckland` (`ownerJdbc().update`) and fix `MutableClock` so the Auckland date is one day ahead of the UTC date (advance until `calendar.today()` ≠ `LocalDate.now(ZoneOffset.UTC)`; at most 24 one-hour steps); a task due on `calendar.today()` ⇒ not escalated; due `calendar.today().minusDays(7)` ⇒ escalated.
  9. `escalationIsAuditedButNotOnTheTimeline` — `escalation.raised`, `timeline_visible = false`.

`SweepConcurrencyTest.concurrentSweepsEscalateOnce` (Review Focus 3) — the `ReconcileConcurrencyTest` shape: a `CyclicBarrier(2)`, two pool threads, each `runner.forTenant("sla-race-" + i, tenant, t -> { barrier.await(); sweep.escalateOverdue(); })` with **different** job names so the advisory lock does not serialise them — the database key, not the lock, must be what holds. Expect exactly one `escalation` row and neither future throwing.

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.identity.ReportingLineDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Spec §6.2. Task 16 completes the chain; this first version routes everything to administrators. */
@Component
public class RecipientResolver {

    public record Resolution(EscalationRoute route, List<ReportingLineDirectory.Recipient> recipients) {
        public UUID primaryUserId() {
            return route == EscalationRoute.ADMINISTRATORS || recipients.isEmpty() ? null : recipients.get(0).userId();
        }
    }

    private final ReportingLineDirectory people;
    public RecipientResolver(ReportingLineDirectory people) { this.people = people; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Resolution resolve(UUID latePersonId) {
        return new Resolution(EscalationRoute.ADMINISTRATORS, people.activeAdministrators());
    }
}
```

```java
package co.ara.onboarding.sla;

import java.util.UUID;

/** An escalation this sweep actually inserted -- the input to notification (Task 16). */
public record RaisedEscalation(UUID escalationId, EscalationSubject subjectType, UUID subjectId, UUID caseId,
                               String caseName, UUID customerId, UUID latePersonId,
                               RecipientResolver.Resolution resolution, int overdueDays) {}
```

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Invariant 5: the database's unique key is what makes escalation idempotent, so this is plain SQL
 * with ON CONFLICT DO NOTHING ... RETURNING id -- an empty result means another sweep (or an
 * earlier run) already escalated this subject for this due date.
 */
@Component
class EscalationWriter {
    private final JdbcTemplate jdbc;
    EscalationWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> insert(EscalationSubject type, UUID subjectId, UUID caseId, UUID latePersonId,
                          RecipientResolver.Resolution resolution, LocalDate dueDate, int overdueDays, Instant at) {
        Timestamp ts = Timestamp.from(at);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO escalation (id, tenant_id, subject_type, subject_id, case_id, late_user_id, route,
                                        escalated_to_user_id, due_date_at_escalation, overdue_days, escalated_at,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT escalation_once_per_subject_and_due_date DO NOTHING
                RETURNING id""", UUID.class,
                Uuid7.generate(), TenantContext.getRequired(), type.name(), subjectId, caseId, latePersonId,
                resolution.route().name(), resolution.primaryUserId(), dueDate, overdueDays, ts, ts, ts);
        return inserted.stream().findFirst();
    }
}
```

`SlaSweepService.escalateOverdue()`:
  - `LocalDate today = calendar.today(); SlaPolicy policy = policies.current(); Instant now = Instant.now(clock);`
  - **Tasks:** `authorizedQuery.findAll(tasks, Task.class, TASK_VIEW, spec, Pageable.unpaged())` with spec `status IN (PENDING, IN_PROGRESS, WAITING) AND dueDate IS NOT NULL AND dueDate < today`.
  - **Milestones:** `authorizedQuery.findAll(milestones, Milestone.class, <the permission CaseService's roadmap uses for Milestone reads — read it; expected CASE_VIEW>, spec, unpaged)` with spec `status IN (PENDING, ACTIVE, BLOCKED) AND dueDate IS NOT NULL AND dueDate < today`.
  - **Clocks:** open clocks with `breachedAt IS NOT NULL`, through `AuthorizedQuery` under `SLA_VIEW`.
  - **Cases:** load every involved case once with `authorizedQuery.findAll(cases, Case.class, CASE_VIEW, (r,q,cb) -> r.get("id").in(ids), unpaged)` into a map; **skip any candidate whose case status is not `ACTIVE`** (spec §6.1 step 2).
  - Per candidate: `dueDate` (clock: `calendar.localDate(breachedAt)`), `int overdueDays = calendar.businessDaysBetween(dueDate, today)`; continue only when `today.isAfter(dueDate) && overdueDays >= policy.escalateAfterOverdueDays()`.
  - Late person: task → `getAssigneeId()`; milestone → `getOwnerUserId()` else the case's `getOwnerUserId()`; clock → the case's `getOwnerUserId()`.
  - `var resolution = resolver.resolve(latePerson); writer.insert(type, id, caseId, latePerson, resolution, dueDate, overdueDays, now)` — **only when an id comes back**: `audit.record(AuditActions.ESCALATION_RAISED, "escalation", escalationId, "Escalated overdue " + type.name().toLowerCase() + " to " + resolution.route(), Map.of("caseId", …, "subjectType", type.name(), "subjectId", …, "route", resolution.route().name(), "overdueDays", overdueDays))` and add a `RaisedEscalation` to the result.
  - `sweep()` now calls `stampBreaches()` then `escalateOverdue()` (Task 16 adds `notify`).

`sla` now depends on `task` (`Task`, `TaskRepository`, `TaskStatus` — make each `public` if not). `task` must never import `sla`; `noCyclesBetweenModules` catches it.

- [ ] **Step 4: Run both test classes and `architecture.*` — PASS. Commit** — `feat(sla): escalate overdue tasks, milestones and breached clocks exactly once`.

---

### Task 16: Recipients, notifications and email after commit

Spec §6.2, §6.3, invariant 6.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/sla/RecipientResolver.java`, `SlaSweepService.java`
- Create: `backend/src/main/java/co/ara/onboarding/sla/EscalationMailer.java`
- Create: `backend/src/main/java/co/ara/onboarding/scoping/NotificationDescriptor.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/RecipientResolverTest.java`, `EscalationDeliveryTest.java`

**Interfaces:**
- Produces:
  ```java
  // SlaSweepService
  @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public int notify(List<RaisedEscalation> raised);
  @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public int retryUnsentEmail();
  // EscalationMailer (package-private)
  boolean send(Notification n, String to);
  // SlaTestSupport gains: void sweepAndEmail(UUID tenant)  -- the two TenantJobRunner runs, in order
  ```

**Why two runs.** Spec §6.3 says email goes out after commit, never inside the transaction. `sla` cannot call `scheduling.TenantJobRunner` (that would be a cycle: `scheduling` calls `sla`). So the sweep is **two separate tenant runs**: run 1 (`sweep()`) writes breaches, escalations and notifications and commits; run 2 (`retryUnsentEmail()`) sends every committed, unsent `ESCALATION` notification and stamps `emailed_at`. Run 2 is also the retry. `SlaSweepJob` (Task 17) performs both. This refines spec §6.1 step 4's wording — say so in the commit body.

- [ ] **Step 1: Write the failing `RecipientResolverTest`:**
  1. `aManagerIsFirst` → `MANAGER`, exactly the manager.
  2. `noManagerFallsToTheDepartmentHead` → `DEPARTMENT_HEAD`.
  3. `fallsThroughInactiveAndSelfToAdministrators` (Review Focus 5) — the late person's manager deactivated and the late person heads their own department ⇒ `ADMINISTRATORS`, and the late person (not an administrator) is not among the recipients.
  4. `aNullLatePersonGoesStraightToAdministrators`.
  5. `noActiveAdministratorYieldsAnEmptyAdministratorsRoute`.
- [ ] **Step 2: Write the failing `EscalationDeliveryTest`** (`@Autowired RecordingEmailSender emails`; distinct addresses per test, since it has no clear method):
  1. `anEscalationNotifiesAndEmailsTheManager` — after `support.sweepAndEmail(tenant)`: one `notification` (`ESCALATION`, `recipient_user_id` = manager, `link_path` = `/t/<slug>/customers/<customerId>/cases/<caseId>`, `escalation_id` set, `emailed_at` set) and `emails.lastTo(managerEmail)` present with a subject starting `Escalation:`.
  2. `administratorsEachGetANotification` — two active admins ⇒ two rows, two emails.
  3. `noActiveAdministratorStillRecordsTheEscalation` (Review Focus 5) — every admin deactivated ⇒ the `escalation` row exists, zero `notification` rows, no exception.
  4. `aFailedSendIsRetriedNextRun` — a nested `@TestConfiguration` registering a `@Primary` `EmailSender` that throws while a static `AtomicBoolean failing` is true ⇒ after run pair 1 `emailed_at` is null; flip the flag; after run pair 2 it is set. (A context with a different `@Primary` bean is a separate Spring context — acceptable for this one class; put this test in its own class `EscalationRetryTest` so the rest of the suite keeps the shared context.)
  5. `notificationSentIsAuditedOffTheTimeline`.
  6. `aRolledBackSweepSendsNothing` — call `sweep.sweep()` inside a `TransactionTemplate` that marks rollback-only (with the job's request scope and principal installed as `TenantJobRunner` does — or simply via `runner.forTenant` with a body that calls `sweep()` then throws), then the email run ⇒ no email to the manager and no `notification` row.
- [ ] **Step 3: Run — fail.**
- [ ] **Step 4: Implement.** `RecipientResolver.resolve`, replacing the first version's body:

```java
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Resolution resolve(UUID latePersonId) {
        if (latePersonId != null) {
            var manager = people.activeManagerOf(latePersonId).filter(r -> !r.userId().equals(latePersonId));
            if (manager.isPresent()) return new Resolution(EscalationRoute.MANAGER, List.of(manager.get()));
            var head = people.activeDepartmentHeadOf(latePersonId).filter(r -> !r.userId().equals(latePersonId));
            if (head.isPresent()) return new Resolution(EscalationRoute.DEPARTMENT_HEAD, List.of(head.get()));
        }
        return new Resolution(EscalationRoute.ADMINISTRATORS, people.activeAdministrators());
    }
```

`scoping.NotificationDescriptor` — `resourceType()` `"notification"`, `entityType()` `Notification.class`, empty `assignedRelationships()`, and all three scopes `cb.disjunction()`: notifications are read only at `ALL` (the system actor's `SLA_VIEW`) in this sub-project, and 6B adds the recipient-scoped read.

`SlaSweepService.notify(List<RaisedEscalation> raised)`: read the tenant slug once (`jdbc.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, TenantContext.getRequired())`) and take each case's name and customer id from its `RaisedEscalation` (Task 15 already loaded the case). For each recipient of each raised escalation: build and save a `Notification` (`Uuid7`, tenant, `recipientUserId`, `ESCALATION`, title `"Escalation: " + <"Task"|"Milestone"|"SLA"> + " overdue by " + overdueDays + " business day(s)"`, body naming the case and the late person (`people.activeUser(latePerson)` full name, or "unassigned"), `linkPath` `"/t/" + slug + "/customers/" + customerId + "/cases/" + caseId`, `caseId`, `escalationId`), then `audit.record(AuditActions.NOTIFICATION_SENT, "notification", n.getId(), "Escalation notification queued", Map.of("escalationId", …, "recipientUserId", …))`. If a resolution has no recipients, `log.error("Escalation {} in tenant {} has no active administrator to deliver to", id, tenant)`. `sweep()` = `stampBreaches(); notify(escalateOverdue());`.

`SlaSweepService.retryUnsentEmail()`: `authorizedQuery.findAll(notifications, Notification.class, SLA_VIEW, (r,q,cb) -> cb.and(cb.equal(r.get("type"), NotificationType.ESCALATION), cb.isNull(r.get("emailedAt"))), Pageable.unpaged())`; for each, `people.activeUser(n.getRecipientUserId())` — absent ⇒ log and skip (left unsent); present ⇒ `if (mailer.send(n, recipient.email())) { n.setEmailedAt(Instant.now(clock)); notifications.save(n); }`. Return the count sent.

```java
package co.ara.onboarding.sla;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Spec §6.3: plain text through the existing EmailSender. Never throws; a failure is retried next run. */
@Component
class EscalationMailer {
    private static final Logger log = LoggerFactory.getLogger(EscalationMailer.class);
    private final EmailSender email;
    EscalationMailer(EmailSender email) { this.email = email; }

    boolean send(Notification n, String to) {
        try {
            email.send(new EmailMessage(to, n.getTitle(), n.getBody() + "\n\nOpen the case: " + n.getLinkPath()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Escalation email {} to {} failed; will retry next run", n.getId(), to, e);
            return false;
        }
    }
}
```

> The email body carries a path, not an absolute URL: there is no configured public base URL in this codebase (`EmailMessage` is plain text, sub-project 1's activation email carries a bare token for the same reason). Record that 6B owns adding one.

`SlaTestSupport.sweepAndEmail(UUID tenant)`: `runner.forTenant("sla-sweep", tenant, t -> sweep.sweep()); runner.forTenant("sla-email", tenant, t -> sweep.retryUnsentEmail());`.

- [ ] **Step 5: Run `RecipientResolverTest`, `EscalationDeliveryTest`, `EscalationRetryTest`, `sla.*`, `architecture.*` — PASS. Full suite. Commit** — `feat(sla): resolve the escalation chain, notify, and email after commit`.

---

### Task 17: Scheduling the sweep and the partition job

Spec §3.4, §6.4, invariant 8.

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__audit_partition_maintenance.sql`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/SchedulingConfig.java`, `SlaSweepJob.java`, `AuditPartitionJob.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/co/ara/onboarding/audit/AuditPartitionJobTest.java`, `backend/src/test/java/co/ara/onboarding/scheduling/SchedulingConfigTest.java`, `SlaSweepJobTest.java`

**Interfaces:**
- Produces: `SlaSweepJob.runAll(): List<UUID>`, `SlaSweepJob.runOne(UUID tenantId): boolean` (Task 21's dev endpoint calls it), `AuditPartitionJob.run(): int`; SQL `ensure_audit_event_partitions(months_ahead int) RETURNS int`.

- [ ] **Step 1: Write the failing tests.** `AuditPartitionJobTest`:
  1. `theApplicationRoleCanEnsurePartitionsOnlyThroughTheFunction` — `withAppConnection(jdbc -> jdbc.queryForObject("SELECT ensure_audit_event_partitions(3)", Integer.class))` succeeds; `withAppConnection(jdbc -> jdbc.execute("CREATE TABLE x_probe (id int)"))` fails with `permission denied for schema public`; `withAppConnection(jdbc -> jdbc.execute("SELECT create_audit_event_partition('2030-01-01')"))` fails with `permission denied for function`.
  2. `itCreatesOnlyMissingMonthsAndHardensThem` — `ensure_audit_event_partitions(30)` ⇒ partitions through 30 months ahead exist; re-running returns 0; then run `AuditAppendOnlyTest`'s two sweeps (it covers every partition by derivation) — they pass.
  3. `itRejectsAnOutOfRangeArgument` — `-1` and `37` both raise.
  4. `theJobRunsTheFunction` — `AuditPartitionJob.run()` returns ≥ 0 and `AuditPartitionCoverageTest` still holds.

  `SchedulingConfigTest.schedulingIsOffUnderTheTestProfile` — the context contains no `org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor` bean.
  `SlaSweepJobTest.runAllSweepsEveryActiveTenantAndEmails` — two tenants each with an overdue task ⇒ after `runAll()`, an escalation and an emailed notification in each.

- [ ] **Step 2: Run — fail.**
- [ ] **Step 3: Migration `V33__audit_partition_maintenance.sql`.**  *(Amended in Task 17 review: V33 as written reads the session TimeZone; `V34__audit_partition_utc.sql` re-declares the function with `SET TimeZone = 'UTC'`.)*

```sql
-- Sub-project 6, spec §6.4, invariant 8. The roll-forward job runs as onboarding_app, which holds
-- no DDL privilege and must not gain one. This function is the one narrow thing it may ask for: it
-- takes a bounded integer, builds no identifier from input, and only calls V27's
-- create_audit_event_partition -- which creates AND hardens (no grant, forced RLS) one month.
--
-- If DEFAULT ever holds rows for a month about to be created, create_audit_event_partition fails
-- and this whole call rolls back, loudly, in the job log. That is the right failure: V27's
-- relocation is the fix, and a daily job makes it unnecessary.
CREATE OR REPLACE FUNCTION ensure_audit_event_partitions(months_ahead int)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    created int := 0;
    m date;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 36 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 36, got %', months_ahead;
    END IF;
    FOR m IN SELECT generate_series(date_trunc('month', now())::date,
                                    (date_trunc('month', now()) + make_interval(months => months_ahead))::date,
                                    interval '1 month')::date LOOP
        IF to_regclass(format('audit_event_%s', to_char(m, 'YYYY_MM'))) IS NULL THEN
            PERFORM create_audit_event_partition(m);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION ensure_audit_event_partitions(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ensure_audit_event_partitions(int) TO onboarding_app;
-- create_audit_event_partition stays callable only by its owner and the definer function above.
REVOKE ALL ON FUNCTION create_audit_event_partition(date) FROM PUBLIC;
```

- [ ] **Step 4: Implement the jobs.**

```java
package co.ara.onboarding.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Off under the test profile; tests invoke job bodies directly (spec §3.4). */
@Configuration
@EnableScheduling
@Profile("!test")
class SchedulingConfig {}
```

```java
package co.ara.onboarding.scheduling;

import co.ara.onboarding.sla.SlaSweepService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Spec §3.4, §6.3: run 1 writes and commits; run 2 emails what run 1 committed (and retries failures). */
@Component
public class SlaSweepJob {
    private final TenantJobRunner runner;
    private final SlaSweepService sweep;

    public SlaSweepJob(TenantJobRunner runner, SlaSweepService sweep) { this.runner = runner; this.sweep = sweep; }

    @Scheduled(fixedDelayString = "${app.sla.sweep-interval:PT5M}", initialDelayString = "PT1M")
    public void scheduled() { runAll(); }

    public List<UUID> runAll() {
        List<UUID> ran = runner.forEachTenant("sla-sweep", t -> sweep.sweep());
        runner.forEachTenant("sla-email", t -> sweep.retryUnsentEmail());
        return ran;
    }

    public boolean runOne(UUID tenantId) {
        boolean ran = runner.forTenant("sla-sweep", tenantId, t -> sweep.sweep());
        runner.forTenant("sla-email", tenantId, t -> sweep.retryUnsentEmail());
        return ran;
    }
}
```

```java
package co.ara.onboarding.scheduling;

import co.ara.onboarding.platform.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Spec §6.4: keeps three months of audit_event partitions ahead. Global, not per tenant; no actor. */
@Component
public class AuditPartitionJob {
    private static final Logger log = LoggerFactory.getLogger(AuditPartitionJob.class);
    private static final UUID GLOBAL = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobLock lock;

    public AuditPartitionJob(JdbcTemplate jdbc, TransactionTemplate tx, JobLock lock) {
        this.jdbc = jdbc; this.tx = tx; this.lock = lock;
    }

    @Scheduled(cron = "${app.audit.partition-cron:0 15 2 * * *}", zone = "UTC")
    public void scheduled() { run(); }

    public int run() {
        Integer created = tx.execute(status -> lock.tryLock("audit-partitions", GLOBAL)
                ? jdbc.queryForObject("SELECT ensure_audit_event_partitions(3)", Integer.class)
                : Integer.valueOf(0));
        log.info("Audit partition job created {} partition(s)", created);
        return created == null ? 0 : created;
    }
}
```

> Also run it once at startup (`@EventListener(ApplicationReadyEvent.class)` calling `run()`, inside `!test`), so a deployment that was down past a month boundary heals before the first cron tick. Put that listener in `SchedulingConfig` or a tiny `@Profile("!test")` component.

`application.yml` under `app:`:

```yaml
  sla:
    sweep-interval: PT5M
  audit:
    partition-cron: "0 15 2 * * *"
```

- [ ] **Step 5: Run the three test classes, `audit.*`, full suite — PASS. Commit** — `feat(scheduling): sweep SLAs every five minutes and roll audit partitions forward daily`.

---

## Phase 4 — The API surface

### Task 18: Calendar, holidays and SLA policy administration

Spec §8 (admin rows), §4.7 (audit), §7.1 (`calendar.manage`).

**Files:**
- Create in `backend/src/main/java/co/ara/onboarding/sla/`: `CalendarAdminService.java`, `CalendarAdminController.java`, `BusinessCalendarView.java`, `UpdateBusinessCalendarRequest.java`, `HolidayView.java`, `CreateHolidayRequest.java`, `SlaPolicyView.java`, `UpdateSlaPolicyRequest.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/CalendarAdminTest.java`

**Interfaces:**
- Produces:
  ```java
  public record BusinessCalendarView(String name, String timezone, List<Integer> workingDays, List<HolidayView> holidays) {}
  public record UpdateBusinessCalendarRequest(@NotBlank String name, @NotBlank String timezone, @NotEmpty List<Integer> workingDays) {}
  public record HolidayView(UUID id, LocalDate date, String name) {}
  public record CreateHolidayRequest(@NotNull LocalDate date, @NotBlank String name) {}
  public record SlaPolicyView(double atRiskDays, int escalateAfterOverdueDays) {}
  public record UpdateSlaPolicyRequest(@NotNull @PositiveOrZero Double atRiskDays, @NotNull @Min(1) Integer escalateAfterOverdueDays) {}
  @Service public class CalendarAdminService {   // every method @RequirePermission(CALENDAR_MANAGE)
      BusinessCalendarView calendar();
      BusinessCalendarView updateCalendar(UpdateBusinessCalendarRequest r);
      HolidayView addHoliday(CreateHolidayRequest r);       // 409 on a duplicate date
      void removeHoliday(UUID id);                          // 404 if not this tenant's
      SlaPolicyView policy();
      SlaPolicyView updatePolicy(UpdateSlaPolicyRequest r);
  }
  // Routes, base /api/t/{tenantSlug}/admin:
  // GET/PUT /business-calendar, POST /business-calendar/holidays, POST /business-calendar/holidays/{id}/remove,
  // GET/PUT /sla-policy
  AuditActions: CALENDAR_UPDATED "calendar.updated", CALENDAR_HOLIDAY_ADDED "calendar.holiday_added",
                CALENDAR_HOLIDAY_REMOVED "calendar.holiday_removed", SLA_POLICY_UPDATED "sla_policy.updated"   // all false
  ```

`UpdateBusinessCalendarRequest`'s components are a subset of `BusinessCalendarView`'s (the `PUT` rule); `holidays` are managed by their own routes and are not part of the `PUT`, which is why the view carries them but the request does not — say so in the record's javadoc.

- [ ] **Step 1: Write the failing `CalendarAdminTest`** (extends `SecurityTestBase`, MockMvc as an administrator unless stated):
  1. `anAdministratorReadsTheDefaultCalendar` — `GET …/admin/business-calendar` ⇒ `timezone` `UTC`, `workingDays` `[1,2,3,4,5]`, `holidays` `[]`.
  2. `updatingTheCalendarIsAFullReplace` — `PUT` `{name, timezone:"Europe/Berlin", workingDays:[1,2,3,4]}` ⇒ 200 and a re-read returns the same.
  3. `anUnknownTimezoneIs400` — `"Mars/Olympus"` ⇒ 400.
  4. `workingDaysOutsideOneToSevenAre400` — `[0]`, `[8]`, `[]` ⇒ 400.
  5. `addingAndRemovingAHoliday` — POST ⇒ 201 with an id; GET lists it; `POST …/holidays/{id}/remove` ⇒ 204; GET no longer lists it.
  6. `aDuplicateHolidayDateIs409`.
  7. `aHolidayFromAnotherTenantIs404` — remove using tenant B's holiday id under tenant A ⇒ 404 (RLS hides the row; the service treats zero rows deleted as `NoSuchElementException`).
  8. `aHolidayChangesTheNextDueDateOnly` — add a holiday on the next business day, then open a case on a stage whose milestone has `estimatedDurationDays = 1` ⇒ its due date skips the holiday; an existing case's stored due date is unchanged (spec §1.1 decision 4).
  9. `policyRoundTripsAndValidates` — `PUT` `{atRiskDays: 2.5, escalateAfterOverdueDays: 2}` ⇒ 200; `{atRiskDays: -1, …}` ⇒ 400; `{…, escalateAfterOverdueDays: 0}` ⇒ 400.
  10. `withoutCalendarManageEveryRouteIs403` — a user holding only `case.view` ⇒ 403 on all six routes (403, not 404: there is no record id to hide — the same reason `DirectApiAccessTest` expects 403 for gated collection routes).
  11. `changesAreAuditedOffTheTimeline` — each write records its action with `timeline_visible = false`.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `CalendarAdminService` reads and writes through `JdbcTemplate` under the bound tenant's RLS (no repository, so the finder rule is satisfied; `calendar.manage` is ALL-only with a `null` resource type, so there is no descriptor to apply). Key points:
  - `updateCalendar`: `ZoneId.of(timezone)` inside a `try` — `DateTimeException` ⇒ `throw new IllegalArgumentException("Unknown timezone: " + timezone)`; every working day in `1..7`, de-duplicated, non-empty, else `IllegalArgumentException`. `UPDATE business_calendar SET name=?, timezone=?, working_days=?::smallint[], updated_at=now()`; if zero rows updated (a fixture tenant with no row), `INSERT` one (`Uuid7`). Bind the array with `connection.createArrayOf("smallint", …)` via a `PreparedStatementCreator`, or as a literal `"{1,2,3}"` string cast with `?::smallint[]` — the second is simpler and safe because every element was validated as an integer 1–7.
  - `addHoliday`: `INSERT … ON CONFLICT ON CONSTRAINT business_holiday_tenant_date_uq DO NOTHING RETURNING id`; empty ⇒ `throw new IllegalStateException("A holiday already exists on " + date)` (409).
  - `removeHoliday`: read the row first (`SELECT holiday_date, name … WHERE id = ?`) — empty ⇒ `NoSuchElementException`; record `CALENDAR_HOLIDAY_REMOVED` with `{date, name}` **before** the `DELETE` (cause before effect, and the audit row is the only record it existed); then `DELETE FROM business_holiday WHERE id = ?`.
  - `updatePolicy`: `UPDATE sla_policy …`, insert if absent, as above.
  - Every write records its `AuditActions` constant after validation and before returning; payloads carry the new values.
  - `AuditActions` group comment: *"Tenant configuration (sub-project 6). Compliance-only."*

`CalendarAdminController` at `@RequestMapping("/api/t/{tenantSlug}/admin")`: `@GetMapping("/business-calendar")`, `@PutMapping("/business-calendar")` with `@Valid @RequestBody`, `@PostMapping("/business-calendar/holidays") @ResponseStatus(CREATED)`, `@PostMapping("/business-calendar/holidays/{id}/remove") @ResponseStatus(NO_CONTENT)` (javadoc: *"POST …/remove, following the codebase's convention; the row is genuinely deleted — a holiday is configuration (V28)"*), `@GetMapping("/sla-policy")`, `@PutMapping("/sla-policy")`. `@ApiResponses` on each listing 200/201/204, 400, 403, 404, 409 as applicable — the 409 and 400 must be documented so `generated.ts` carries them.

- [ ] **Step 4: Run `CalendarAdminTest`, `sla.*`, `architecture.*` (`AuthorizationCoverageTest` — every public method gated) — PASS. Commit** — `feat(sla): administer the business calendar, holidays and SLA policy`.

---

### Task 19: The war room feed — `GET /sla/exceptions`

Spec §8, §9.2, §1.2.6 (no cases list).

**Files:**
- Create in `backend/src/main/java/co/ara/onboarding/sla/`: `SlaExceptionsService.java`, `SlaExceptionsController.java`, `ExceptionsView.java`
- Test: `backend/src/test/java/co/ara/onboarding/sla/SlaExceptionsTest.java`

**Interfaces:**
- Consumes: `SlaClockService.views` (Task 13), `AuthorizedQuery`, `ReportingLineDirectory.activeUser`.
- Produces:
  ```java
  public record ExceptionsView(Summary summary, List<Card> breached, List<Card> dueToday, List<Card> watch,
                               String calendarName) {
      public record Summary(int breached, int dueToday, int clocksPaused, int autoEscalated) {}
      public record Card(UUID caseId, String caseName, UUID customerId, String customerName, String stageName,
                         UUID ownerUserId, String ownerName, SlaClockView clock, boolean hasOpenRequests,
                         List<EscalationNote> escalations) {}
      public record EscalationNote(EscalationSubject subjectType, UUID subjectId, EscalationRoute route,
                                   UUID escalatedToUserId, String escalatedToName, String latePersonName,
                                   LocalDate dueDate, int overdueDays, Instant escalatedAt) {}
  }
  @Service public class SlaExceptionsService {
      @RequirePermission(PermissionKeys.SLA_VIEW) @Transactional(readOnly = true) public ExceptionsView exceptions();
  }
  ```

- [ ] **Step 1: Write the failing `SlaExceptionsTest`** (service-level, as an administrator via `runAsUser`, plus one MockMvc smoke test):
  1. `breachedDueTodayAndWatchAreSeparated` — three cases: one breached (`breached_at` set), one due today (advance the `MutableClock` so 0.5 of a 1-day target remains, inside a business day — compute from `calendar.today()`), one at risk but not due today (a 2-day target with 0.9 remaining where the business day ends before 0.9 elapses) ⇒ one card in each column; a fourth, comfortably within target, appears nowhere.
  2. `summaryCountsMatch` — `breached`, `dueToday` match the column sizes; `clocksPaused` counts open clocks in `PAUSED` (hold one case); `autoEscalated` counts escalations with `escalated_at` within the last 7 days and excludes one 8 days old.
  3. `cardsCarryEscalationHistoryNewestFirst` — two escalation rows on one case (a task and its clock) ⇒ `escalations` has both, newest first, with `escalatedToName` resolved and `latePersonName` resolved.
  4. `stoppedClocksAreNotExceptions` — a completed case with a `BREACHED` outcome appears nowhere.
  5. `aMissingStageOrCustomerNameIsNullNotAnError` — run as a user holding `sla.view` and `case.view` at ALL but **not** `workflow.view` or `customer.view` ⇒ cards render with `stageName`/`customerName` null — never a 404 (the `workflow.view` nested-lookup trap, CLAUDE.md Tests section, deliberately avoided).
  6. `hasOpenRequestsReflectsOpenDocumentRequests`.
  7. MockMvc: `GET /api/t/{slug}/sla/exceptions` ⇒ 200 for an administrator, 403 for a user without `sla.view`.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `SlaExceptionsService.exceptions()`:
  - Open clocks: `authorizedQuery.findAll(clocks, SlaClock.class, SLA_VIEW, (r,q,cb) -> cb.isNull(r.get("stoppedAt")), Pageable.unpaged())` — this is where record scope narrows the war room (Task 22's `SlaScopeTest` proves it).
  - `Map<UUID, SlaClockView> views = clockService.views(openClocks)`.
  - Cases: `authorizedQuery.findAll(cases, Case.class, CASE_VIEW, idIn(caseIds), unpaged)`; a clock whose case is not returned is dropped (out of the viewer's case scope).
  - Stage names: `authorizedQuery.findAll(stages, Stage.class, WORKFLOW_VIEW, idIn(stageIds), unpaged)` — **wrapped so a viewer with no `workflow.view` grant yields an empty map rather than an exception** (if `findAll` with no grant returns empty via `cb.disjunction()`, nothing more is needed — confirm; it should, per `AuthorizationPredicateBuilder`).
  - Customer names: `authorizedQuery.findAll(customers, Customer.class, CUSTOMER_VIEW, idIn(customerIds), unpaged)` → `displayName` (confirm the field name on `customer.Customer`). `sla` → `customer` is a new one-way dependency; nothing in `customer` may import `sla`.
  - Owner names: `people.activeUser(case.getOwnerUserId())`.
  - Escalations for these cases: `authorizedQuery.findAll(escalations, Escalation.class, SLA_VIEW, caseIdIn, unpaged)`, grouped by case, sorted `escalatedAt` desc; names through `people.activeUser`.
  - `hasOpenRequests`: `documentRequests.countByCaseIdAndStatus(caseId, OPEN) > 0` — a `count` query on a case the viewer already resolved; not a finder (`find*`) so the rule does not bind. Add a comment saying so.
  - Columns: `breached` = state `BREACHED`; `dueToday` = `dueToday`; `watch` = `atRisk && !dueToday && state != BREACHED`. Order breached by `elapsed − target` desc, dueToday by `remaining` asc, watch by `remaining` asc.
  - `autoEscalated` = escalations (in scope) with `escalatedAt >= now − 7 days`.
  - `calendarName` = `calendar.name()`.

`SlaExceptionsController`: `@GetMapping("/api/t/{tenantSlug}/sla/exceptions")`.

- [ ] **Step 4: Run `SlaExceptionsTest`, `sla.*`, `architecture.*` — PASS. Commit** — `feat(sla): the war room exceptions feed`.

---

### Task 20: Remind customer — `POST /document-requests/{id}/remind`

Spec §8, §4.6, §4.7. Lives in `document`, beside the other request writes: it is a `document_request` write that needs `applyWriteScope` and `resolveContact`, both already private to `DocumentRequestService`.

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequestService.java`, `DocumentRequestController.java`, `DocumentRequestView.java`, `DocumentExceptionHandler.java`
- Create: `backend/src/main/java/co/ara/onboarding/document/ReminderNotPossibleException.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Test: `backend/src/test/java/co/ara/onboarding/document/CustomerReminderTest.java`

**Interfaces:**
- Produces:
  ```java
  // DocumentRequestService
  @RequirePermission(PermissionKeys.DOCUMENT_REQUEST) @Transactional
  public DocumentRequestView remind(UUID requestId);
  // DocumentRequestView gains, at the end: int remindersSent, Instant lastRemindedAt
  AuditActions.DOCUMENT_REQUEST_REMINDED = of("document_request.reminded", true)
  ```

- [ ] **Step 1: Write the failing `CustomerReminderTest`** (MockMvc + `RecordingEmailSender`):
  1. `remindingEmailsTheContactAndCounts` — a request with a contact ⇒ 200, `remindersSent` 1, `lastRemindedAt` set, and `emails.lastTo(contactEmail)` present after the request completes (after commit).
  2. `aSecondReminderWithin24HoursIs409` — then `clock.advance(Duration.ofHours(25))` ⇒ 200, `remindersSent` 2.
  3. `aRequestWithNoContactIs422`.
  4. `aFulfilledOrWithdrawnRequestIs422`.
  5. `anOutOfScopeOrForeignRequestIs404`.
  6. `theWriteScopeGuardApplies` — a stage with `writeScope OWNER_ONLY`, an actor who is not the owner but holds `document.request` and `workflow.view` ⇒ the guard's refusal (same status `security.WriteScopeTest` expects).
  7. `theReminderIsOnTheTimeline` — `document_request.reminded` appears in `TimelineService.forCase` (timeline-visible by design: the customer received it).
  8. `aRolledBackReminderSendsNoEmail` — not reachable through HTTP; call `service.remind` inside a `TransactionTemplate` marked rollback-only ⇒ no email.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.document;

/** A reminder the spec answers with 422: no contact on the request, or the request is not OPEN. */
public class ReminderNotPossibleException extends RuntimeException {
    public ReminderNotPossibleException(String message) { super(message); }
}
```

`DocumentExceptionHandler` — add `@ExceptionHandler(ReminderNotPossibleException.class)` ⇒ `ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage())`, in the existing handlers' style.

`DocumentRequestService.remind(UUID requestId)`:

```java
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional
    public DocumentRequestView remind(UUID requestId) {
        DocumentRequest dr = authorizedQuery.getById(requests, DocumentRequest.class, PermissionKeys.DOCUMENT_REQUEST, requestId);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.DOCUMENT_REQUEST, dr.getCaseId());
        applyWriteScope(c);
        if (dr.getStatus() != DocumentRequestStatus.OPEN) {
            throw new ReminderNotPossibleException("Only an open request can be reminded");
        }
        if (dr.getRequestedOfContactId() == null) {
            throw new ReminderNotPossibleException("This request has no contact to remind");
        }
        Instant now = Instant.now(clock);
        if (dr.getLastRemindedAt() != null && dr.getLastRemindedAt().isAfter(now.minus(Duration.ofHours(24)))) {
            throw new IllegalStateException("This request was already reminded in the last 24 hours");
        }
        CustomerContact contact = authorizedQuery.getById(contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW,
                dr.getRequestedOfContactId());
        audit.record(AuditActions.DOCUMENT_REQUEST_REMINDED, "document_request", dr.getId(),
                "Reminded the customer about a requested document",
                Map.of("caseId", dr.getCaseId().toString(), "reminder", dr.getRemindersSent() + 1));
        dr.setRemindersSent(dr.getRemindersSent() + 1);
        dr.setLastRemindedAt(now);
        dr = requests.saveAndFlush(dr);
        String to = contact.getEmail();
        String subject = "Reminder: a document is still needed";
        String body = "We're still waiting on a document from you for \"" + c.getName() + "\": "
                + dr.getCategory() + (dr.getDescription() == null ? "" : " — " + dr.getDescription());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { email.send(new EmailMessage(to, subject, body)); }
                catch (RuntimeException e) { log.warn("Reminder email for request {} failed", requestId, e); }
            }
        });
        return toView(dr);
    }
```

> The order: validate, audit (cause), then the counter write, then the email after commit. Read the existing `withdraw`'s exact `getById` permission key for the case (`DOCUMENT_REQUEST`, per L104) and match it. `email` is a new `EmailSender` constructor parameter; `document` → `auth` is a new one-way dependency — `noCyclesBetweenModules` must stay green (if `auth` already depends on `document`, stop and move the send behind a `document`-declared port implemented in `auth`). A failed reminder email is logged; the counter still advances (a reminder the customer may not have received) — record this as an open item for 6B's delivery tracking in Task 32.

`DocumentRequestView` gains `int remindersSent, Instant lastRemindedAt` at the end; update `toView` and every positional `new DocumentRequestView(` site. `DocumentRequestController`: `@PostMapping("/document-requests/{id}/remind")` returning the view, with `@ApiResponses` 200, 403, 404, 409, 422. `AuditActions`, next to the other `document.*` request actions: `public static final AuditAction DOCUMENT_REQUEST_REMINDED = of("document_request.reminded", true);` with a comment: *"Timeline-visible: the customer received it (spec 4.7)."*

- [ ] **Step 4: Add the cause-before-effect subsequence.** In `journey.CauseBeforeEffectTest`, add `aReminderReadsAfterTheRequestItRemindsAbout` asserting `containsSubsequence("document.requested", "document_request.reminded")`.
- [ ] **Step 5: Run `CustomerReminderTest`, `CauseBeforeEffectTest`, `document.*`, `architecture.*` — PASS. Commit** — `feat(document): remind a customer about an open document request`.

---

### Task 21: Dev-only time travel and sweep trigger

Spec §10.3, §1.2.5.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/OffsetClock.java`
- Modify: `backend/src/main/java/co/ara/onboarding/platform/PlatformBeansConfig.java`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/DevToolsService.java`, `DevToolsController.java`
- Test: `backend/src/test/java/co/ara/onboarding/platform/OffsetClockTest.java`, `backend/src/test/java/co/ara/onboarding/scheduling/DevToolsProfileTest.java`

**Interfaces:**
- Produces:
  ```java
  public class OffsetClock extends Clock { public void shift(Duration d); public Duration offset(); }
  // dev profile only: POST /api/t/{slug}/dev/clock/offset {"seconds": n} -> {"offsetSeconds": total}
  //                   POST /api/t/{slug}/dev/jobs/sla-sweep           -> {"ran": true|false}
  ```

- [ ] **Step 1: Write the failing tests.** `OffsetClockTest` (unit): a fresh clock is within a second of `Instant.now()`; after `shift(Duration.ofDays(2))` it is two days ahead; shifts accumulate; `offset()` reports the total. `DevToolsProfileTest` (extends `PostgresTestBase`, profile `test`): `@Autowired RequestMappingHandlerMapping mapping` — no registered pattern contains `/dev/`; the `Clock` bean (`@Autowired @Qualifier("clock") Clock`) is **not** an `OffsetClock`; no `DevToolsController` bean exists (`ApplicationContext.getBeanNamesForType(DevToolsController.class)` is empty).
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.platform;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A system clock that a developer can shift forward, registered as the application Clock under the
 * dev profile only (spec §10.3). Playwright uses it to cross a business day. Never present in any
 * other profile -- DevToolsProfileTest proves the test profile does not get it.
 */
public class OffsetClock extends Clock {
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final ZoneId zone;

    public OffsetClock() { this(ZoneOffset.UTC); }
    private OffsetClock(ZoneId zone) { this.zone = zone; }

    public void shift(Duration d) { offset.updateAndGet(o -> o.plus(d)); }
    public Duration offset() { return offset.get(); }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return Clock.offset(Clock.system(z), offset.get()); }
    @Override public Instant instant() { return Instant.now().plus(offset.get()); }
}
```

`PlatformBeansConfig`:

```java
    @Bean
    @Profile("!dev")
    public Clock clock() { return Clock.systemUTC(); }

    /** Dev only: a shiftable clock for end-to-end time travel (spec §10.3). Bean name stays "clock". */
    @Bean(name = "clock")
    @Profile("dev")
    public OffsetClock devClock() { return new OffsetClock(); }
```

`scheduling.DevToolsService` (`@Service @Profile("dev")`) — a `*Service`, so `AuthorizationCoverageTest` requires its methods gated:

```java
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public long shiftClock(long seconds) {
        if (seconds <= 0 || seconds > Duration.ofDays(60).toSeconds()) {
            throw new IllegalArgumentException("seconds must be between 1 and 60 days");
        }
        clock.shift(Duration.ofSeconds(seconds));
        return clock.offset().toSeconds();
    }

    /** Not @Transactional: TenantJobRunner opens its own transactions and restores this request's context. */
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public boolean runSweep() { return sweepJob.runOne(TenantContext.getRequired()); }
```

with `OffsetClock clock` and `SlaSweepJob sweepJob` injected. `TENANT_SETTINGS_EDIT` is ALL-only and held by Administrator alone — a dev tool needs an authenticated administrator even where it exists (spec §10.3). `DevToolsController` (`@RestController @Profile("dev") @RequestMapping("/api/t/{tenantSlug}/dev")`): `@PostMapping("/clock/offset")` taking `record OffsetRequest(long seconds)` and returning `Map.of("offsetSeconds", …)`; `@PostMapping("/jobs/sla-sweep")` returning `Map.of("ran", …)`. No `@ApiResponses`: these never appear in the OpenAPI document (the test profile generates it, and the controller does not exist there).

> `AuthorizationCoverageTest`'s rules scan **classes**, not beans, so they see `DevToolsService` under every profile — both methods are gated, so it passes. `ModuleBoundaryTest.controllersDoNotUseRepositoriesDirectly` — the controller uses none.

- [ ] **Step 4: Prove it works under `dev`.** The suite cannot load the `dev` profile cheaply, so prove it once by hand and record the transcript in the task report: start the backend with `SPRING_PROFILES_ACTIVE=dev` (CLAUDE.md "Running it locally"), log in as a provisioned administrator, `POST /api/t/<slug>/dev/clock/offset {"seconds":86400}` ⇒ `{"offsetSeconds":86400}`; `POST /api/t/<slug>/dev/jobs/sla-sweep` ⇒ `{"ran":true}`; as a non-administrator ⇒ 403. Playwright (Task 31) exercises it continuously thereafter.
- [ ] **Step 5: Run `OffsetClockTest`, `DevToolsProfileTest`, `architecture.*`, full suite — PASS. Commit** — `feat(dev): dev-profile-only clock offset and sweep trigger for end-to-end tests`.

---

### Task 22: The negative suite, the OpenAPI document, and the invariants pass

Spec §10.1 (security negatives), §11.

**Files:**
- Create: `backend/src/test/java/co/ara/onboarding/sla/SlaIsolationTest.java`, `SlaScopeTest.java`
- Modify: whatever `OpenApiDocumentTest` needs (nothing, if every new controller documents its responses)
- Regenerate: `frontend/src/lib/api/generated.ts`

- [ ] **Step 1: `SlaIsolationTest`** — tenant A and tenant B, each with an administrator, a case with a clock, an escalation, a holiday and an open document request. As A's administrator under A's slug, every one of B's ids is a 404: `GET /cases/{B case}/sla-clock`, `POST /admin/business-calendar/holidays/{B holiday}/remove`, `POST /document-requests/{B request}/remind`. `GET /sla/exceptions` under A lists only A's case. `PUT /admin/users/{A user}` with `managerId` = a B user ⇒ 404. `PUT /admin/departments/{A dept}` with `headUserId` = a B user ⇒ 404.
- [ ] **Step 2: `SlaScopeTest`** — the narrowest-scope test CLAUDE.md requires for a permission catalogued at several scopes. A user holding a hand-built role with `sla.view` and `case.view` at **TEAM**, member of team T. Cases: one owned by T, one by another team, both breached. `GET /sla/exceptions` ⇒ exactly the first, and `summary.breached == 1`. Repeat at **DEPARTMENT** with two departments. Repeat at **ASSIGNED**: a user who is an `OWNER` participant on one case and nothing on another ⇒ only the first. Add `aHolderOfSlaViewWithoutCaseViewSeesNoCards` — `sla.view` at ALL but no `case.view` ⇒ empty columns, never an error (the cases join drops them).
- [ ] **Step 3: Run both — they should PASS on first run if Tasks 7, 13, 18–20 are right.** A negative test that has never failed proves nothing, so prove each red once: temporarily change `SlaClockDescriptor.teamScope` to `cb.conjunction()` and confirm `SlaScopeTest`'s TEAM case fails; revert. Temporarily make `SlaClockService.forCase` read with `clocks.findById` directly and confirm `SlaIsolationTest` fails (or the finder rule fails); revert. Record both in the task report.
- [ ] **Step 4: Regenerate API types.** `.\gradlew.bat openApiSpec` then, in `frontend/`, `npm run generate:api`. Confirm `generated.ts` gains `SlaClockView`, `ExceptionsView`, `BusinessCalendarView`, `SlaPolicyView`, `HolidayView`, the `managerId`/`headUserId`/`pausesOnCustomer`/`remindersSent` fields, and the new paths; no `/dev/` path. Reordering-only diffs elsewhere are springdoc noise (CLAUDE.md).
- [ ] **Step 5: Walk the ten invariants (spec §11) against the code, one line each, and write the result into the task report** — the sub-project 4 close-out's shape: #1 `SlaClockLifecycleTest.everyClockWriteRollsBackWithItsCause`; #2 `git log -p` on `CaseEngine.java` for this branch shows only the two port calls and the constructor parameter, plus `MilestoneService.reopen`; #3 `ModuleBoundaryTest`'s two rules; #4 `V31` has no elapsed column and no request type accepts one; #5 the unique key plus `SweepConcurrencyTest`; #6 `noActiveAdministratorStillRecordsTheEscalation`; #7 `SystemActorTest`; #8 `AuditPartitionJobTest.theApplicationRoleCanEnsurePartitionsOnlyThroughTheFunction`; #9 the three breach/escalation/notification timeline assertions; #10 `SlaIsolationTest` and the request/view reflection tests in Tasks 3 and 18.
- [ ] **Step 6: Full backend suite. Commit** — `test(sla): isolation and narrowest-scope negatives; regenerate API types`.

---

## Phase 5 — Frontend

**Before every task in this phase:** invoke the `frontend-design` and `ui-ux-pro-max` skills (CLAUDE.md), and open `docs/uispecs_latest/design_handoff_onboarding_platform/Onboarding Platform.dc.html` at the screen the task builds. Restyle in place where a primitive exists (`StatusPill`, `Dialog`, `Switch`, `Field`, `Button`, `DataTable`, `Card`, `EmptyState`/`SkeletonRows`/`ErrorState`); never add a parallel one. Do not port the prototype's inline styles. Every task ends with `npx vitest run`, `npx tsc --noEmit`, `npm run lint` — all clean.

Test pattern for every component test in this phase (from `components/journey/DocumentsTab.test.tsx`): mock `@/lib/auth/useAuth` with a mutable `permissions` record, replace `global.fetch` with a `vi.fn()` routed by URL, wrap in a fresh `QueryClient` with `retry: false`, call `setTenantSlug("acme")` and `__setAccessToken("token")` in `beforeEach`, and import jest-dom per file. Page tests mock `next/navigation` (`useParams: () => ({ slug: "acme" })`) and import the page dynamically.

### Task 23: API hooks — `sla.ts`, `calendar.ts`, and the four widened modules

**Files:**
- Create: `frontend/src/lib/api/sla.ts`, `frontend/src/lib/api/calendar.ts`, and their `.test.tsx`
- Modify: `frontend/src/lib/api/cases.ts` (add `useUpdateCase`), `admin.ts` (add `useUpdateDepartment`), `documents.ts` (add `useRemindRequest`)
- Modify tests: `cases.test.tsx`, `admin.test.tsx`, `documents.test.tsx` (whichever exist; create if not)

**Interfaces:**
- Consumes: `generated.ts` from Task 22.
- Produces:
  ```ts
  // sla.ts
  export type SlaClock = components["schemas"]["SlaClockView"];
  export type SlaClockState = NonNullable<SlaClock["state"]>;
  export type Exceptions = components["schemas"]["ExceptionsView"];
  export type ExceptionCard = components["schemas"]["Card"];          // confirm the generated schema name
  export type EscalationNote = components["schemas"]["EscalationNote"];
  export const slaKeys = { all: ["sla"] as const, clock: (caseId: string) => [...slaKeys.all, "clock", caseId] as const,
                           exceptions: () => [...slaKeys.all, "exceptions"] as const };
  export function useCaseSlaClock(caseId: string): UseQueryResult<SlaClock | null>;   // 404 -> null, not an error
  export function useSlaExceptions(): UseQueryResult<Exceptions>;                     // refetchInterval: 60_000
  // calendar.ts
  export type BusinessCalendar = components["schemas"]["BusinessCalendarView"];
  export type Holiday = components["schemas"]["HolidayView"];
  export type SlaPolicy = components["schemas"]["SlaPolicyView"];
  export const calendarKeys = { all: ["calendar"] as const, calendar: () => [...], policy: () => [...] };
  export function useBusinessCalendar(); useUpdateBusinessCalendar(); useAddHoliday(); useRemoveHoliday();
  export function useSlaPolicy(); useUpdateSlaPolicy();
  // cases.ts
  export function useUpdateCase(): UseMutationResult<Case, Error, { caseId: string; body: UpdateCaseRequest }>;
  export function toUpdateCaseRequest(c: Case, patch: Partial<UpdateCaseRequest>): UpdateCaseRequest;
  // admin.ts
  export type DepartmentRequest = components["schemas"]["DepartmentRequest"];
  export function useUpdateDepartment(): UseMutationResult<Department, Error, { id: string; body: DepartmentRequest }>;
  // documents.ts
  export function useRemindRequest(): UseMutationResult<DocumentRequest, Error, { requestId: string; caseId: string }>;
  ```

- [ ] **Step 1: Write the failing hook tests.** For each hook, assert the URL and method sent and the invalidation: `useCaseSlaClock` → `GET /api/t/acme/cases/c1/sla-clock`, and a 404 resolves to `data === null` with `isError === false` (a stage without an SLA is not an error); `useSlaExceptions` → `GET …/sla/exceptions` and its options carry `refetchInterval: 60000`; `useUpdateCase` → `PUT /cases/c1` with exactly the body given, then `caseKeys.detail("c1")` is set from the response and `slaKeys.clock("c1")` and `slaKeys.exceptions()` are invalidated; `toUpdateCaseRequest(case, { ownerUserId: "u2" })` returns `name`, `ownerUserId: "u2"`, `owningDepartmentId`, `owningTeamId` and `attributes` copied from the case — **every** `UpdateCaseRequest` field (the full-replace rule — a test that lists the generated type's keys and checks each is present guards it); `useRemindRequest` → `POST /document-requests/r1/remind`, then invalidates `documentKeys.requestsForCase("c1")` and `slaKeys.exceptions()`; the calendar mutations invalidate `calendarKeys.calendar()`/`policy()`; `useUpdateDepartment` → `PUT /admin/departments/d1`, invalidates `adminKeys.departments()`.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** in the established shape (`tasks.ts:70`, `tasks.ts:222`; every file starts `"use client";`). `useCaseSlaClock`:

```ts
export function useCaseSlaClock(caseId: string) {
  return useQuery({
    queryKey: slaKeys.clock(caseId),
    queryFn: async () => {
      try {
        return await apiFetch<SlaClock>(`/cases/${caseId}/sla-clock`);
      } catch (e) {
        // No SLA on this stage is a normal state, not an error (spec §8: 404 if none).
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: Boolean(caseId),
  });
}
```

`toUpdateCaseRequest`:

```ts
/** PUT /cases/{id} is a full replace (CLAUDE.md invariant): start from the current view, change only `patch`. */
export function toUpdateCaseRequest(c: Case, patch: Partial<UpdateCaseRequest>): UpdateCaseRequest {
  return {
    name: c.name ?? "",
    ownerUserId: c.ownerUserId,
    owningDepartmentId: c.owningDepartmentId,
    owningTeamId: c.owningTeamId,
    attributes: c.attributes ?? {},
    ...patch,
  };
}
```

> If `generated.ts`'s `UpdateCaseRequest` has any field not listed here, add it — the key-coverage test from Step 1 fails until you do.

- [ ] **Step 4: Run — PASS; `tsc`, lint clean. Commit** — `feat(frontend): SLA, calendar, case-update, department and reminder hooks`.

---

### Task 24: `formatSlaClock`, `SlaChip`, `SlaClockCallout`

Spec §9.1; STATE_AND_DATA L138; SCREENS §3 (chip `SLA PAUSED 3.1d`, rail "Paused · 3.1 business days").

**Files:**
- Create: `frontend/src/components/sla/formatSlaClock.ts`, `SlaChip.tsx`, `SlaClockCallout.tsx`, and `.test.ts(x)` for each
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces:
  ```ts
  export type SlaChipTone = "risk" | "warn" | "info" | "neutral" | "ok";
  export function formatSlaClock(clock: SlaClock): { label: string; tone: SlaChipTone };
  export function SlaChip({ clock, prefix }: { clock: SlaClock; prefix?: boolean }): JSX.Element;   // prefix -> "SLA " before the label
  export function SlaClockCallout({ clock }: { clock: SlaClock | null | undefined; loading?: boolean }): JSX.Element | null;
  ```

- [ ] **Step 1: Write the failing `formatSlaClock.test.ts`** — table-driven, one row per state:

| input | label | tone |
|---|---|---|
| `state BREACHED, elapsedDays 4.04, targetDays 2` | `BREACHED 2.0d` | `risk` |
| `state PAUSED, pausedDays 3.14` | `PAUSED 3.1d` | `info` |
| `state MET` | `MET` | `ok` |
| `state RUNNING, dueToday true` | `DUE TODAY` | `warn` |
| `state RUNNING, atRisk true, remainingDays 0.44` | `0.4d LEFT` | `warn` |
| `state RUNNING, remainingDays 1.25` | `1.2d LEFT` | `ok` |

Numbers use one decimal, truncated toward zero, never rounded up (a chip must never claim more time than remains: `0.96d` shows `0.9d LEFT`). Every label string comes from `t()` with a `{days}` param — e.g. `t("sla.chip.left", { days: "1.2" })` → `"1.2d LEFT"`.

- [ ] **Step 2: Write the failing `SlaChip.test.tsx` and `SlaClockCallout.test.tsx`.** `SlaChip` renders a `StatusPill` whose text is the label (with `prefix`, `SLA PAUSED 3.1d`) — the word is always present, so colour is never the only signal; the number is in the data font (`font-data` class or `var(--ob-font-family-data)`). `SlaClockCallout`: `null` clock ⇒ renders nothing; `loading` ⇒ a `SkeletonRows`-style placeholder; `PAUSED` ⇒ heading `SLA CLOCK`, body `Paused · 3.1 business days`, the reason (`Waiting on the customer's documents` / `Case on hold`), and the calendar name; `pauseEligible false` ⇒ the line `NOT ELIGIBLE FOR PAUSE — INTERNAL REVIEW`; `RUNNING` ⇒ `1.2 of 3 business days left`; `BREACHED` ⇒ `Breached by 2.0 business days`, plus `Escalated to <name> on <date>` when `escalatedTo` is set (or `Escalated to administrators`), the date in the data font.
- [ ] **Step 3: Run — fails.**
- [ ] **Step 4: Implement.**

```ts
import type { SlaClock } from "@/lib/api/sla";
import { t } from "@/lib/i18n";

export type SlaChipTone = "risk" | "warn" | "info" | "neutral" | "ok";

/** One decimal, truncated: a chip never claims more time than there is. */
export function days(value: number | undefined): string {
  return (Math.trunc(Math.max(0, value ?? 0) * 10) / 10).toFixed(1);
}

/** The one formatter for every SLA chip (STATE_AND_DATA L138). The server computed the numbers. */
export function formatSlaClock(clock: SlaClock): { label: string; tone: SlaChipTone } {
  switch (clock.state) {
    case "BREACHED":
      return { label: t("sla.chip.breached", { days: days((clock.elapsedDays ?? 0) - (clock.targetDays ?? 0)) }), tone: "risk" };
    case "PAUSED":
      return { label: t("sla.chip.paused", { days: days(clock.pausedDays) }), tone: "info" };
    case "MET":
      return { label: t("sla.chip.met"), tone: "ok" };
    default:
      if (clock.dueToday) return { label: t("sla.chip.dueToday"), tone: "warn" };
      return { label: t("sla.chip.left", { days: days(clock.remainingDays) }), tone: clock.atRisk ? "warn" : "neutral" };
  }
}
```

Map `SlaChipTone` to `StatusRole` (`risk`, `warn`, `info`, `neutral`, `ok` exist in `StatusRole`). The callout follows `AwaitingApprovalBanner`'s structure (role `status`, a heading, body text, flat `Card`-like border using the tone's `--ob-<tone>-border`/`-bg`), not its warn colour unconditionally — the tone follows the state.

`en.json` keys (flat): `sla.chip.breached` `"BREACHED {days}d"`, `sla.chip.paused` `"PAUSED {days}d"`, `sla.chip.met` `"MET"`, `sla.chip.dueToday` `"DUE TODAY"`, `sla.chip.left` `"{days}d LEFT"`, `sla.chip.prefix` `"SLA"`, `sla.callout.title` `"SLA CLOCK"`, `sla.callout.paused` `"Paused · {days} business days"`, `sla.callout.running` `"{left} of {target} business days left"`, `sla.callout.breached` `"Breached by {days} business days"`, `sla.callout.met` `"Met in {days} business days"`, `sla.callout.reason.CASE_HOLD` `"Case on hold"`, `sla.callout.reason.OPEN_DOCUMENT_REQUEST` `"Waiting on the customer's documents"`, `sla.callout.ineligible` `"NOT ELIGIBLE FOR PAUSE — INTERNAL REVIEW"`, `sla.callout.escalatedTo` `"Escalated to {name} on {date}"`, `sla.callout.escalatedToAdmins` `"Escalated to administrators on {date}"`, `sla.callout.calendar` `"{name} · business days"`.

- [ ] **Step 5: Run — PASS. Commit** — `feat(frontend): the SLA chip and clock callout`.

---

### Task 25: The case workspace — header chip and right-rail callout

SCREENS §3. The page comment at L155–158 already reserves the rail slot for an SLA CLOCK card.

**Files:**
- Modify: `frontend/src/components/journey/CaseHeader.tsx` (beside `<StatusPill status={caseData.status} />`, L54)
- Modify: `frontend/src/app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx` (the `<aside>` at L159–192; update the L155–158 comment)
- Modify tests: `CaseHeader.test.tsx`, the page's `page.test.tsx`

- [ ] **Step 1: Failing tests.** `CaseHeader` given a clock renders `SLA PAUSED 3.1d` next to the status pill, and renders no SLA chip when the hook returns `null`. The page renders `SlaClockCallout` as the first card in the rail when a clock exists, nothing when the endpoint 404s, and a skeleton while loading; the existing Hold/Resume buttons and `CaseSwitcher` still render.
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** `CaseHeader` calls `useCaseSlaClock(caseData.id)` itself (a header that owns its own small query keeps the page's prop list unchanged) and renders `{clock && <SlaChip clock={clock} prefix />}` after the status pill. The page's `<aside>` gains `<SlaClockCallout clock={slaClock.data} loading={slaClock.isLoading} />` above `CaseSwitcher`; the comment now says the SLA CLOCK card is sub-project 6's and CUSTOMER/PARTICIPANTS remain later scope. `useHold`/`useResume`'s `onSuccess` should also invalidate `slaKeys.clock(caseId)` so the callout flips to PAUSED immediately — add that to `cases.ts` in this task, with a test.
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): SLA chip and clock callout on the case workspace`.

---

### Task 26: The builder — a live "Pause on customer" toggle

SCREENS §9 (inspector: "SLA days (number), 'Pause on customer' toggle").

**Files:**
- Modify: `frontend/src/components/workflow/StageInspector.tsx` (after the SLA field, L125–131)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/workflows/[id]/versions/[vid]/page.tsx` (view→draft mapping, L73–75)
- Modify: `frontend/src/components/workflow/StageInspector.test.tsx`, `draftState.test.ts`
- Modify: `en.json`

- [ ] **Step 1: Failing tests.** The inspector renders a `Switch` labelled `Pause on customer`, checked when `stage.pausesOnCustomer !== false` (an undefined value — a draft created before this field existed — reads as on, matching the server's default); toggling calls `onChange({ pausesOnCustomer: false })`; it is disabled when `readOnly`; a hint below reads `Open customer document requests pause this stage's SLA clock.`; when `slaDays` is empty the toggle is still shown but the hint reads `Takes effect once the stage has an SLA.` The builder page's view→draft mapping carries `pausesOnCustomer` (a `draftState`/page test that loads a version with `pausesOnCustomer: false` and saves it unchanged must send `false`, not omit it).
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** with the existing `Switch({checked, onChange, label})`. The save path must send the boolean explicitly — never omit it (the `autoAdvance` trap's frontend half). The notification-template field stays exactly as it is (inert until 6B).
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): pause-on-customer toggle in the stage inspector`.

---

### Task 27: Manager and department-head pickers

**Files:**
- Modify: `frontend/src/app/(app)/t/[slug]/admin/users/page.tsx` (`InviteForm` L658, `EditForm` L772; reuse the `DepartmentSelect` pattern at L615)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/org/page.tsx` (department rows, `Row` L243, `OrgForm` L274)
- Modify tests: the two pages' `page.test.tsx`
- Modify: `en.json`

- [ ] **Step 1: Failing tests.** Users page: the edit dialog shows a `Manager` select listing active internal users (from `useUsers`) except the user being edited, with a `No manager` option; saving sends `managerId` (or `null`); the invite form shows the same select; a 404 from the server (a manager outside the actor's scope) shows the problem detail via `parseProblemDetail`. Org page: each department row shows its head's name (or `No head`) and an `Edit` button opening a dialog with name, description and a `Department head` select; saving calls `useUpdateDepartment` with **all three** fields (full replace — the test asserts the body has `name`, `description` and `headUserId`).
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** A local `UserSelect({ id, label, value, options, onChange, excludeId })` in the users page beside `DepartmentSelect`, the same native `<select>` styling (`bg-surface border border-line text-ink`) and `<label htmlFor>`. The users list for the picker: `useUsers("", 0)` is paged at 25 — if a tenant can exceed that, pass a larger page size if the hook allows it, otherwise note "first 25 users" as a known limit in the task report (no search-as-you-type in this sub-project). Department edit uses the existing `Dialog` and `Field`.
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): manager and department-head pickers`.

---

### Task 28: Administration → Business calendar

No design exists for this screen (spec §9.3); build it in the bundle's language: page header via `useSetPageHeader`, flat cards, mono for every date and number.

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/admin/business-calendar/page.tsx`, `page.test.tsx`
- Modify: `frontend/src/app/(app)/t/[slug]/admin/layout.tsx` (a tab gated by `useHasPermission("calendar.manage")`)
- Modify: `en.json`

- [ ] **Step 1: Failing tests.**
  1. Without `calendar.manage` the admin tab strip has no "Business calendar" tab.
  2. The page shows three cards: **Calendar** (name field, timezone select, seven working-day toggles Mon–Sun), **Holidays** (a list of date + name rows, newest-first by date, each with a Remove button; an add row with a date input and a name field), **SLA policy** (`At risk when this many business days or fewer remain` number input, step 0.5, min 0; `Escalate after this many business days overdue` number input, min 1).
  3. Saving the calendar sends `{ name, timezone, workingDays }` (full replace) and shows a success toast; unchecking every working day disables Save with the message `Pick at least one working day.`
  4. A 409 on adding a holiday shows `A holiday already exists on that date.`; a 400 on the timezone shows the server's detail.
  5. Removing a holiday asks nothing (no `confirm()` — browser dialogs are banned in this codebase's e2e) but shows an undo-less toast `Holiday removed`.
  6. The policy card explains in one sentence: `Escalation to a manager is mandatory and cannot be turned off; these numbers only set when it happens.`
  7. Loading shows skeletons; a failed load shows `ErrorState`; zero holidays shows `EmptyState` `No holidays yet.`
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** Timezone options: `Intl.supportedValuesOf("timeZone")` (available in every evergreen browser and in Node ≥ 18 for jsdom), with the current value first if it is missing from the list. Working days use ISO numbers 1–7 (Mon–Sun), matching the API. Dates in inputs are `YYYY-MM-DD` strings straight to the API (`LocalDate`), never `Date` objects (no timezone shifting).
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): the business calendar administration screen`.

---

### Task 29: The SLA war room

SCREENS §4; DOMAIN_RULES L145–147, L182–184.

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/sla/page.tsx`, `page.test.tsx`
- Create: `frontend/src/components/sla/WarRoomCard.tsx`, `WarRoomCard.test.tsx`, `escalationNote.ts`, `escalationNote.test.ts`
- Modify: `frontend/src/components/shell/Sidebar.tsx` (+ `Sidebar.test.tsx`), `en.json`

**Interfaces:**
- Produces: `WarRoomCard({ card, slug, onReassign, onForceComplete, onRemind }: { card: ExceptionCard; slug: string; onReassign(card): void; onForceComplete(card): void; onRemind(card): void })`; `formatEscalationNote(note: EscalationNote): string`.

- [ ] **Step 1: Failing tests.**
  - `escalationNote.test.ts`: `MANAGER` with name `Priya Shah`'s manager resolved as `Sam Lee`, due `2026-08-20`, escalated `2026-08-21`, `overdueDays 1` ⇒ `Escalated to Sam Lee (Priya Shah's manager) on 21 Aug (automatic, day 1 overdue)`; `DEPARTMENT_HEAD` ⇒ `… (department head) …`; `ADMINISTRATORS` ⇒ `Escalated to administrators on …`; a null late-person name ⇒ no possessive clause. Dates formatted `d MMM` in the browser's locale-independent English (use a fixed `Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" })` — the value is a `LocalDate`, so format in UTC to avoid shifting it).
  - `Sidebar.test.tsx`: an `SLA war room` item appears with `sla.view` and an INTERNAL user, never for a PORTAL user (the sub-project 4 close-out's leak, not repeated).
  - `WarRoomCard.test.tsx`: shows the case name (a link to `/t/acme/customers/<customerId>/cases/<caseId>`), the customer name (or nothing when null), the stage name (or nothing), `Elapsed / target` as `4.0 / 2` in the data font, the `SlaChip`, the owner name (or `Unassigned`), the newest escalation note or — if none — the pause reason or the ineligibility line; `Remind customer` only when `hasOpenRequests`; `Reassign` always; `Force-complete` only when the viewer holds `milestone.force_complete` (the existing `ForceCompleteDialog`'s own gate — read which permission the case workspace checks before showing it, and use the same).
  - `page.test.tsx`: eyebrow `SLA WAR ROOM · BUSINESS DAYS · <CALENDAR NAME>` (uppercase); summary strip `BREACHED 2 · DUE TODAY 1 · CLOCKS PAUSED 3 · AUTO-ESCALATED 4` from `summary`, each number in the data font and each count paired with its word; three column headings `Breached`, `Due today`, `Watch` with their cards; an all-empty response shows one `EmptyState` `Nothing is breached, due today or at risk.` instead of three empty columns; the policy panel reads `Escalation to the assignee's manager is automatic and cannot be opted out of.`; loading ⇒ skeletons; error ⇒ `ErrorState`; columns stack below 900px (assert the container's responsive class, e.g. `grid-cols-1 min-[900px]:grid-cols-3`).
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement.** The page uses `useSlaExceptions()` (60 s polling from Task 23) and `useSetPageHeader(t("sla.title"))`. Sidebar: add `const canViewSla = useHasPermission("sla.view") && user?.userType !== "PORTAL";` to the fixed-order hook block (L95–105) and push `{ label: t("nav.sla"), href: `/t/${slug}/sla`, section: `/t/${slug}/sla`, Icon: <an existing icon — reuse a clock/alarm icon from `@/components/icons` if one exists; otherwise `BellIcon`, already exported at icons/index.tsx:268> }` after Documents. Cards are flat (border, no shadow). The card's three buttons call its three callback props. In this task the page passes no-op callbacks (`() => {}`) and the page test asserts only that the buttons render; Task 30 replaces the no-ops with dialog state and adds the click-through assertions.
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): the SLA war room`.

---

### Task 30: War room actions — Reassign, Force-complete, Remind customer

Spec §1.1 decision 8, §1.2.12–§1.2.13.

**Files:**
- Create: `frontend/src/components/sla/ReassignDialog.tsx`, `RemindCustomerDialog.tsx`, and tests
- Modify: `frontend/src/app/(app)/t/[slug]/sla/page.tsx` and its test; `en.json`

- [ ] **Step 1: Failing tests.**
  - `ReassignDialog({ caseId, onClose })`: loads the case (`useCase(caseId)`) and the case's open tasks (`useCaseTasks(caseId)`, filtered to non-terminal statuses); offers a `Case owner` user select (pre-selected to the current owner) and, per open task with an assignee, an `Assignee` select; saving the owner calls `useUpdateCase` with `toUpdateCaseRequest(case, { ownerUserId })` — the test asserts the PUT body carries the case's existing `name`, `owningDepartmentId`, `owningTeamId` and `attributes` untouched; saving a task calls the existing `useUpdateTask` with that task's full current fields and only `assigneeId` changed (read `UpdateTaskRequest`'s fields and assert each is carried); a 404 shows the problem detail; success closes and toasts `Reassigned`.
  - `RemindCustomerDialog({ caseId, onClose })`: lists the case's OPEN document requests (`useDocumentRequests(caseId)`), each with category, description, `Reminded 2× · last 3 Oct` (data font) or `Not reminded yet`, and a `Remind` button; clicking calls `useRemindRequest`; a 409 shows `Already reminded in the last 24 hours.` on that row and keeps the dialog open; a 422 shows the server detail; a row with no contact shows `No contact on this request` and a disabled button.
  - Page: clicking a card's `Reassign` opens `ReassignDialog` for that case; `Remind customer` opens `RemindCustomerDialog`; `Force-complete` opens the existing `ForceCompleteDialog` — it needs a `milestoneId`: use the card's newest `MILESTONE` escalation's `subjectId` if present; otherwise open a small picker of the case's current-stage incomplete milestones from `useRoadmap(caseId)` first. Closing any dialog refetches the exceptions (`slaKeys.exceptions()` invalidation is already done by the mutations).
- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement** with the existing `Dialog` (`eyebrow`, `maxWidth`), `DialogActions`, `Button` variants and the users-page `UserSelect` (extract it to `frontend/src/components/admin/UserSelect.tsx` in this task, since it now has three consumers, and update Task 27's two call sites to import it — say so in the commit body).
- [ ] **Step 4: Run — PASS. Commit** — `feat(frontend): war room reassign, force-complete and remind actions`.

---

## Phase 6 — Verification and close-out

### Task 31: `sla.spec.ts`

Spec §10.3.

**Files:**
- Create: `frontend/e2e/sla.spec.ts`
- Modify: `frontend/e2e/support/tenant.ts` (helpers below)

**Interfaces:**
- Produces in `tenant.ts`: `Api.shiftClock(seconds: number): Promise<number>` (`POST /dev/clock/offset`), `Api.runSlaSweep(): Promise<boolean>` (`POST /dev/jobs/sla-sweep`), `Api.setManager(userId: string, managerId: string)` (a full-replace `PUT /admin/users/{id}` built from a `GET` of the user).

- [ ] **Step 1: Write the spec.**
  - `beforeAll`: provision a tenant (`provisionTenant(request, "sla")`), log in as its admin, create a customer, seed a user `owner` and a user `boss` with `seedUser`, `setManager(owner, boss)`, publish a one-stage workflow whose stage has `slaDays: 1`, `autoAdvance: true`, `pausesOnCustomer: true` **sent explicitly**, and one MANUAL requirement; open a case and make `owner` its owner (`PUT /cases/{id}` full replace).
  - **Test 1 — breach, escalation, war room.** Shift the clock by 4 days (`shiftClock(4 * 86400)` — four calendar days always contain ≥ 1 business day past a 1-day target), `runSlaSweep()` twice (breach is stamped on the first, the clock escalates when `escalate_after_overdue_days` business days have passed since the breach: shift another 3 days and sweep again). Then `readEmailToken`-style polling on `backend.log` for an `[email] to=<boss email> subject=Escalation:` line (add a `readEmail(email, subjectContains)` helper beside `readEmailToken` that returns the matched block). Sign in as the admin, open `/t/<slug>/sla`: the case is in **Breached**, its card shows `BREACHED`, and the note names `boss`.
  - **Test 2 — pause and remind.** A second case: create a document request with a contact through the API (the contact from `createContact`); open the case workspace: the header chip reads `SLA PAUSED`; in the war room open `Remind customer`, click `Remind`; the row shows `Reminded 1×`; clicking again shows the 409 message; `backend.log` holds a reminder email to the contact.
  - **Test 3 — the builder toggle.** Open the builder on a new draft: the stage inspector shows `Pause on customer` checked; uncheck, save, reload: still unchecked.
  - Every action/assertion pair uses a click followed by an auto-retrying `expect(...)` — never `locator.check()` on a control whose state waits on a round trip (CLAUDE.md Tests section).
  - **The clock offset is process-wide and never resets.** Run this spec's tests in the order written (`test.describe.configure({ mode: "serial" })`), and note in the spec header that it shifts the backend's clock forward for every spec that runs after it in the same Playwright invocation — that is harmless for the existing specs (none asserts an absolute date against "now" — confirm by grepping `e2e/` for `new Date(` / `Date.now(` assertions before relying on it; if one does, run `sla.spec.ts` last via the file name ordering or `playwright.config.ts` `testMatch` ordering, and record which).
- [ ] **Step 2: Run it** against a scratch database: `$env:DB_URL="jdbc:postgresql://localhost:5432/onboarding_e2e_sla"; npx playwright test e2e/sla.spec.ts`. Fix real defects in the product with their own failing unit test first; fix spec defects in the spec. Never weaken an assertion to pass.
- [ ] **Step 3: Run the whole Playwright suite** — all specs, including the fourteen existing ones. **Commit** — `test(e2e): SLA breach, escalation, pause and remind end to end`.

---

### Task 32: Whole-branch verification and close-out

**Files:**
- Modify: `CLAUDE.md`
- Modify: this plan (amendments found during execution)
- Create: `.superpowers/sdd/2026-10-03-sla-and-escalation/task-32-report.md`

- [ ] **Step 1: Run all four suites in one pass**, reading each summary line rather than a pinned count: `.\gradlew.bat cleanTest test` (count from XML as in Task 1), `npx vitest run`, `npx tsc --noEmit` + `npm run lint`, `npx playwright test` against a fresh scratch database. All green, nothing skipped.
- [ ] **Step 2: Re-verify the ten invariants** against the final code (Task 22 Step 5's list), re-deriving each rather than trusting the earlier report.
- [ ] **Step 3: Update `CLAUDE.md`** — keep it dense:
  - **"Sub-project 6 delivered:"** paragraph after sub-project 4's (and 5's, if present on `main` by then): the `sla` module, the `scheduling` slice, the business calendar, clocks and pauses, breach and escalation, the war room and chips, the calendar admin screen, reporting lines, the partition job.
  - **Sequence line:** show 6 split into 6 and 6B.
  - **Correct the `portal_visible` attribution:** CLAUDE.md says sub-project 6 gives `stage.portal_visible` a consumer; QA Q24 assigns it to 7, and this sub-project did not touch it (spec §2.2).
  - **"Also operational":** the partition job now exists (daily, plus once at startup, through `ensure_audit_event_partitions`); `AuditPartitionCoverageTest` stays as the alarm if it ever stops.
  - **Non-negotiable invariants:** add "Sub-project 6's own ten" (spec §11) with one verification line each, the shape the other sub-projects use.
  - **"Where the guards live":** `security.SystemActorTest`, `sla.SlaIsolationTest`, `sla.SlaScopeTest`, `sla.SweepConcurrencyTest`, `audit.AuditPartitionJobTest`, `scheduling.DevToolsProfileTest`.
  - **"What sub-project 6B inherits"** (new): the `notification` table and its `type` check to widen by migration; `TenantJobRunner` and the system actor (and the fact that `SystemPermissions` is read-only — 6B adding a write permission to it is a design decision, not a convenience); `atRisk` for risk alerts; `reminders_sent`/`last_reminded_at` for automatic reminders; no public base URL exists for email links yet; a failed reminder email still advances the counter; the inbox must make `TopBar.test.tsx`'s "no dead notification controls" assertion true by shipping a real control, not by deleting the assertion.
  - **Open at the close of sub-project 6:** the cases-list SLA column/filter (no screen 2 exists — §1.2.6); the war room's user pickers load only the first page of users; anything else found during execution.
  - **Tests section:** `sla.spec.ts` added, and that it shifts the dev backend's clock for the rest of the run.
- [ ] **Step 4: Update the memory note** `subproject6_sla_and_escalation.md` to COMPLETE with the branch/PR state, and its `MEMORY.md` line.
- [ ] **Step 5: Commit** — `docs: close out sub-project 6, SLA & Escalation`. **Do not push or open a PR** — report to the user and wait for their go-ahead (they approve every git push/PR/merge).

---

## Plan self-review

Checked against the spec after writing:

- **Coverage.** §3.2 ports → Tasks 11–12; §3.3 calendar → 2, 10; §3.4 scheduler/system actor → 8, 9, 17; §4.1 → 2; §4.2 → 3; §4.3 → 4; §4.4–4.6 → 5, 20; §4.7 audit → 3, 14, 15, 16, 18, 20; §5 clock rules → 10–12; §6.1–6.3 → 14–16; §6.4 → 17; §7 → 7, 8; §8 API → 13, 18–21; §9 screens → 24–30; §10 testing → every task, 22, 31; §11 invariants → 22, 32; §1.2 amendments → 3 (7, 10, 11), 8 (3), 9 (2, 4), 11 (1, 8), 2/10 (13), 21 (5), 23 (12). §1.2.6 (no cases list) is a deliberate omission, recorded in Task 32.
- **Deliberate deviations from the spec's body, each traceable to §1.2 or recorded in a task:** remind lives in `document` not `sla` (Task 20 — it needs `DocumentRequestService`'s private write-scope and contact resolution); the dev controller lives in `scheduling` not `sla` (Task 21 — it calls `SlaSweepJob`, and `sla` must not depend on `scheduling`); the email step is a second tenant run (Task 16 — the same dependency direction); the finder-rule exclusion names `SlaClockWriter` alone (Task 11); the escalation unique key includes `tenant_id` (Task 5).
- **Types used across tasks:** `SlaClockView`/`EscalatedTo` (10) → 13, 19, 23; `RecipientResolver.Resolution` (15) → 16; `RaisedEscalation` (15) → 16; `SlaTestSupport` (11) → 12–22; `TenantJobRunner.forTenant/forEachTenant` (9) → 14–17, 21; `SlaSweepJob.runOne` (17) → 21; `toUpdateCaseRequest` (23) → 30; `UserSelect` (27, extracted in 30).
