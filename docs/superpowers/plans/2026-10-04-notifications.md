# Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build sub-project 6B — an in-app inbox with per-user preferences and email digests, fourteen opt-out-able notification types beside the mandatory escalation, tenant deadline horizons and expiry/renewal reminders, automatic customer reminders, stage-alert templates for `stage.notification_template_key`, and one email outbox with attempt tracking for every notification-class email.

**Architecture:** One new domain module, `co.ara.onboarding.notification`. Producer modules (task, journey, document, agreement, workflow, customer) publish small event records through Spring's `ApplicationEventPublisher` after their own audit action; synchronous `@EventListener`s in `notification` run inside the producer's transaction, resolve recipients from a fixed relationship table, drop anyone `authz.RecipientAccess` says cannot view the subject, apply preferences, and insert `notification` rows plus `email_outbox` rows. A one-minute dispatcher claims outbox rows, sends outside any transaction, and stamps the result with backoff and a cap; an hourly sweep and a fifteen-minute digest job run on sub-project 6's `TenantJobRunner`.

**Tech Stack:** Java 21, Spring Boot 3.4 (`ApplicationEventPublisher`, `@EventListener`, `@Scheduled`), Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, JdbcTemplate, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind v4, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-04-notifications-design.md` — read it before any task, **including §1.2**, and then **this plan's "Spec amendments from plan research" below**, which overrides the spec where they disagree. Section numbers (§5.3 …) refer to the spec.

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — `COMPONENTS.md` §1 (top bar), §16 (inbox drawer), §17 (toggle); `SCREENS.md` overlays table L430–443; `DOMAIN_RULES.md` §Q10; `DESIGN_TOKENS.md` semantic pairs, `drawer` shadow, `om-slide`. Open `Onboarding Platform.dc.html` (search "Inbox") before Task 29. Do **not** read `docs/uispecs_legacy/`. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 6), per CLAUDE.md.

---

## Spec amendments from plan research (2026-10-04)

Verified against the code at `bafdef0`. Each overrides the spec section named.

1. **Publish sites (§3.2).** Workflow publish is `workflow.PublishService.publish`, not `WorkflowService`. Agreement transitions span three services: `AgreementService` (`submit`, `send`, `cancel`), `AgreementReviewService.review` (approve → `APPROVED`, reject → `DRAFT`) and `AgreementSignatureService.record` (`SIGNED`, only when `last`). A force-completed milestone becomes `DONE` in `journey.ApprovalService.decideForceComplete`, not in `CaseEngine` (which handles natural completion in `markDone`). `TaskService.create` sets an assignee without recording `task.assigned`, and `TaskInstantiation` assigns the milestone owner — both publish `TaskAssigned` too.
2. **`RiskChanged` lives in `notification`, not `sla` (§3.2).** `sla → notification` is the allowed arrow (escalation writes), so an event record declared in `sla` and consumed by `notification` would be a cycle. `sla` publishes `notification.RiskChanged`.
3. **Customer reminders reach the outbox by event (§6.2, §12.2).** `document` must never import `notification` (amendment-free invariant 1), so `DocumentRequestService.remind`/`remindAutomatically` publish `document.CustomerReminderQueued`; `notification.ReminderNotifications` turns it into a `CUSTOMER_REMINDER` outbox row in the same transaction.
4. **How `notification` reads other modules' data (§3.3, §7).** Recipient candidates, subject facts (names, owners, slugs) and sweep candidates are read with **RLS-bound `JdbcTemplate` SQL** (`SubjectFacts`, `DeadlineCandidates`), not `AuthorizedQuery`. The safety argument, which the whole-branch security review must check: none of these reads is ever returned to a caller; every row the pipeline writes is gated **per recipient** by `RecipientAccess.canView`, which runs the real predicate (record scope + audience filter) as that recipient. This is also why `SystemPermissions` does not need `document.view`/`agreement.view` for the sweep — and why it must not use them: `DocumentAudienceFilter` would hide department-targeted documents from a context with no department, silently dropping their expiry reminders. `notification` therefore imports producer modules only for **event record types** and the **entity classes** it passes to `RecipientAccess`.
5. **Publish validation reaches templates through a port (§8).** `workflow` may not import `notification`, so `workflow.NotificationTemplateKeys { boolean exists(String key); }` is declared in `workflow` and implemented by `notification.TemplateKeysAdapter` — the `CustomerDirectory` inversion.
6. **`notification.tone` column (§4.1).** The inbox row's tone varies within a type (document approved vs rejected, at-risk vs breached), so the row stores it: `tone text NOT NULL CHECK (tone IN ('RISK','WARN','OK','INFO'))`, backfilled `RISK` for escalations.
7. **`email_outbox.link_path` (§4.5).** The outbox stores the record's path, not an absolute link; the dispatcher appends `"\n\nOpen: " + baseUrl + link_path` at send time. This lets `V35` move unsent escalation emails into the outbox in SQL (no base URL is known to a migration) and keeps a base-URL change effective for queued mail.
8. **New customer (§5.2).** `CustomerService.create` always sets the owner to the actor, so the create-time event can never reach anyone. `NEW_CUSTOMER` also fires from `CustomerService.update` when the owner changes to someone else ("A customer was assigned to you").
9. **Job services are gated `sla.view` (§7.1).** `NotificationSweepService`, `DigestService` and `EmailDispatchService` are `*Service`s, so `AuthorizationCoverageTest` requires a gate; they carry `@RequirePermission(PermissionKeys.SLA_VIEW)`, the same marker `SlaSweepService` carries, which the system actor holds. No controller exposes them; the dev endpoints gate on `tenant.settings.edit`. They are **not** renamed to dodge the rule (the three `task` classes that once did that are why the rule is now repository-shaped).
10. **`TenantJobRunner.forTenantUnlocked` (§6.4).** The dispatcher's stamp run must never be skipped by another dispatcher holding the advisory lock (the claimed rows are leased to *this* dispatcher), so the runner gains an unlocked variant. The claim run keeps the lock.
11. **Preferences `PUT` lists every type (§8).** A full replace must list all fourteen opt-out types exactly once (400 otherwise); `ESCALATION` may be listed only with both channels `true` (422 otherwise). Omitting a type is never a silent reset.
12. **Notification ordering test (§10.1).** `notification.sent` is compliance-only, so it never appears in `TimelineService`; `CauseBeforeEffectTest` cannot see it. `notification.NotificationOrderingTest` reads `audit_event` through the owner connection and asserts each cause's `occurred_at` is not after its `notification.sent`.
13. **Automatic reminders never throw (§6.2).** `remindAutomatically` runs inside the sweep's tenant transaction; any exception escaping a `@Transactional` method marks that whole transaction rollback-only. It returns `false` for every not-remindable condition and reads through `AuthorizedQuery.findAll` (empty → `false`), never `getById`.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is authoritative for everything sub-projects 1–6 established; this carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. New module `notification`. New `authz` types: `RecipientAccess`, `GrantLookup`. New `platform` type: `PublicBaseUrl`. New `workflow` type: the `NotificationTemplateKeys` port. New event records live in their producer's package (`task.TaskAssigned`, `task.CommentAdded`, `journey.MilestoneCompleted`, `journey.StageEntered`, `journey.StageExited`, `document.DocumentRequested`, `document.DocumentUploaded`, `document.DocumentReviewed`, `document.CustomerReminderQueued`, `agreement.AgreementStatusChanged`, `workflow.WorkflowVersionPublished`, `customer.CustomerOwnerAssigned`), except `notification.RiskChanged` (amendment 2).
- **No producer imports `notification`.** Six named `ModuleBoundaryTest` rules — `noTaskDependencyOnNotification`, `noJourneyDependencyOnNotification`, `noDocumentDependencyOnNotification`, `noAgreementDependencyOnNotification`, `noWorkflowDependencyOnNotification`, `noCustomerDependencyOnNotification` — plus `noNotificationDependencyOnSla`, each **seen red** before it is trusted (Task 2).
- **No new caller of `CaseEngine.reconcile`.** Event publishes sit beside existing audit records and `SlaClockLifecycle` calls.
- **Migration numbers:** `V34` is highest as this plan is written. Before writing a migration, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>`. Forward-only; never edit a committed migration.
- **Every tenant-owned table** has `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `created_at`/`updated_at timestamptz NOT NULL`, `SELECT enable_tenant_rls('<t>')` in the same migration, and explicit grants. **No `DELETE` grant except `deadline_horizon`**, with a comment (spec §4.4). `RlsCoverageTest`'s allowlist stays at four entries.
- **UUIDv7 keys** via `Uuid7.generate()` in application code (`gen_random_uuid()` only in one-time migration backfills, the `V28` precedent). "Now" is `Instant.now(clock)` / `calendar.today()`, never zero-arg `Instant.now()`/`LocalDate.now()` in new code.
- **Every public `*Service` method carries `@RequirePermission`**, except the two self-service classes `InboxService` and `NotificationPreferenceService`, excluded by name in both `AuthorizationCoverageTest` rules on `MeService`'s stated basis (Tasks 11, 12). **`co.ara.onboarding.notification..` joins `servicesDoNotCallRepositoryFindersDirectly`'s package list in Task 2.** No other `FINDER_RULE_EXCLUSIONS` entry is permitted; needing one means the design is wrong — stop and escalate.
- **`authz.SystemPermissions.forJobs()` gains exactly `document.request` at `ALL`** (Task 25), and `security.SystemActorTest` is updated in the same commit. Nothing else.
- **Out-of-scope, other-recipient and cross-tenant ids are 404, never 403.** `NoSuchElementException` → 404, `IllegalStateException` → 409, `IllegalArgumentException` → 400 (`platform.ApiExceptionHandler`); a rule the spec answers with 422 throws `notification.NotificationRuleException` (mapped by `notification.NotificationExceptionHandler`).
- **A `PUT` is a full replace; its view carries every field its request accepts.** Boolean request fields are boxed `Boolean` with `@NotNull`, never primitive (the `autoAdvance` trap).
- **Audit:** new actions in `AuditActions`, all `timeline_visible=false` (spec §4.7). **Cause before effect:** a producer records its own audit action **before** publishing its event.
- **Email is never sent inside a transaction that can roll back.** Every notification-class email is an `email_outbox` row; only `EmailDispatchJob` calls `EmailSender` for them.
- **TDD.** Failing test first; security tests before the mechanism; every new structural guard seen red.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper call. **Fixture create-helpers run inside `runAs`.**
- **Backend:** `.\gradlew.bat cleanTest test` (PowerShell), never a bare `test`. Docker must be running. Run `java -version` **and** `javac -version` at the start of every backend task (Application Control can block `javac.exe` alone). On this 8 GB machine run per package: `.\gradlew.bat cleanTest test --max-workers=1 --tests "co.ara.onboarding.<package>.*"` and read totals from `build/test-results/test/*.xml`. Never run Gradle with a shell sitting inside `backend/build/`.
- **Frontend:** every task runs `npx vitest run`, `npx tsc --noEmit` and `npm run lint`.
- **API types are generated, never hand-written** — `.\gradlew.bat openApiSpec` then `npm run generate:api` (Task 28, and again after any later backend contract change).
- **i18n:** every user-facing string through `t()`; keys are flat dotted entries in `frontend/src/lib/i18n/messages/en.json`, new ones under `inbox.` and `notifications.`.
- **Design non-negotiables:** colour means status and is always paired with a word or icon; Instrument Sans for human text, Spline Sans Mono (`var(--ob-font-family-data)`) for every count, time and id; cards flat (the drawer is the one thing here that genuinely floats); light theme only.
- **Conventional Commits**, *why* in the body; end every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. When you find a plan defect, fix the code **and** amend this plan, and say so in the commit body. **Do not push**; the user approves every push.

## Review Focus

Inputs and conditions the spec implies but no happy-path test would meet — each has its pinning test in the owning task:

1. **A targeted document uploaded on a case whose owner sits outside the document's audience.** The owner holds `document.view` at `ALL` but the document targets another department; `DOCUMENT_UPLOADED` must not reach them, or the title leaks a document they cannot open. Pinned in Task 8 (`aTargetedDocumentIsInvisibleToAnAllScopedReaderOutsideItsAudience`) and Task 16 (`aTargetedUploadDoesNotReachAnOwnerOutsideItsAudience`).
2. **The actor is also a candidate** — self-assigning a task, completing a milestone on your own case, commenting on your own thread. Nobody is told about their own action. Pinned in Task 10 (`theActorIsNeverNotified`) and Task 13 (`selfAssignmentNotifiesNobody`).
3. **A tenant far from UTC around 08:00 and midnight.** A daily digest in Auckland fires at 08:00 Auckland on an Auckland working day, and "due in 2 business days" is counted in the tenant's zone. Pinned in Task 23 (`deadlineIsJudgedInTheTenantZone`) and Task 26 (`aDailyDigestFollowsTheTenantZone`).
4. **Two dispatchers at once** (two instances, or the dev endpoint racing the scheduled run). Every outbox row is sent once, and a stamp is never skipped because the other dispatcher holds the lock. Pinned in Task 5 (`concurrentDispatchersNeverDoubleSend`).
5. **A user switches from daily digest to immediate with notifications pending.** The pending rows are delivered exactly once, never lost and never duplicated. Pinned in Task 26 (`switchingToImmediateFlushesPendingOnce`).

---

## File structure

```
backend/src/main/resources/db/migration/
  V35__notification_outbox.sql        Task 3  — widen notification (+tone), email_outbox(+_item), sla_clock.at_risk_alerted_at, backfill
  V36__notification_preferences.sql   Task 9  — notification_preference, notification_settings
  V37__notification_template.sql      Task 19 — notification_template; notification.manage grant for existing Administrators
  V38__notification_policy.sql        Task 21 — deadline_horizon, notification_policy (+ backfill)

backend/src/main/java/co/ara/onboarding/
  platform/   PublicBaseUrl (4)
  authz/      GrantLookup, RecipientAccess, AuthorizationService, AuthorizationPredicateBuilder (8);
              PermissionKeys, PermissionCatalog, RoleTemplates (19); SystemPermissions (25)
  notification/
              Notification, NotificationRepository, NotificationType (2, moved from sla)
              NotificationCatalog, Tone, EmailState, OutboxKind (3)
              OutboxWriter, EmailDispatchService (5); NotificationWriter (6); ReminderNotifications (7)
              EmailCadence, PreferenceReader (9); SubjectFacts, Links, Text, NotificationPipeline (10)
              InboxService, InboxController (11); NotificationPreferenceService, NotificationPreferenceController,
              NotificationRuleException, NotificationExceptionHandler (12)
              TaskNotifications (13, 14); JourneyNotifications (15); DocumentNotifications (16);
              AgreementNotifications (17); WorkflowNotifications, CustomerNotifications (18)
              NotificationTemplate, NotificationTemplateRepository, TemplatePlaceholders, NotificationAdminService,
              NotificationAdminController (19); TemplateKeysAdapter, stage alerts in JourneyNotifications (20)
              HorizonKind, PolicyReader (21); RiskChanged, RiskNotifications (22)
              DeadlineCandidates, NotificationSweepService (23–25); DigestService (26)
  sla/        SlaSweepService, RecipientResolver (6, 22); SlaClock, SlaClockRepository (22); EscalationMailer DELETED (6)
  scheduling/ TenantJobRunner.forTenantUnlocked, EmailDispatchJob (5); SlaSweepJob (6); NotificationSweepJob (23);
              DigestJob (26); DevToolsService, DevToolsController (27)
  task/       TaskAssigned, TaskService, TaskInstantiation (13); CommentAdded, CommentService (14)
  journey/    MilestoneCompleted, CaseEngine, ApprovalService (15); StageEntered, StageExited, MilestoneService (20)
  document/   CustomerReminderQueued, DocumentRequestService (7, 25); DocumentRequested, DocumentUploaded,
              DocumentReviewed, DocumentInstantiation, DocumentService, DocumentReviewService (16)
  agreement/  AgreementStatusChanged, AgreementService, AgreementReviewService, AgreementSignatureService (17)
  workflow/   WorkflowVersionPublished, PublishService (18); NotificationTemplateKeys (20)
  customer/   CustomerOwnerAssigned, CustomerService (18)
  provisioning/TenantProvisioningService — seed horizons + policy (21)
  scoping/    NotificationDescriptor — import moved (2)
  audit/      AuditActions — EMAIL_FAILED (5), NOTIFICATION_PREFERENCES_CHANGED (12),
              NOTIFICATION_TEMPLATE_* (19), NOTIFICATION_POLICY_UPDATED (21)

backend/src/test/java/co/ara/onboarding/
  architecture/ModuleBoundaryTest, AuthorizationCoverageTest (2, 11, 12)
  platform/PublicBaseUrlGuardTest (4)
  security/RecipientAccessTest (8), SystemActorTest (25)
  notification/ NotificationSchemaTest (3), NotificationTestSupport + FlakyEmail (5), EmailDispatchTest (5),
              CustomerReminderOutboxTest (7), PreferenceReaderTest (9), NotificationPipelineTest (10),
              InboxApiTest (11), PreferencesApiTest (12), TaskNotificationTest + NotificationOrderingTest (13),
              CommentNotificationTest (14), MilestoneNotificationTest (15), DocumentNotificationTest (16),
              AgreementNotificationTest (17), WorkflowAndCustomerNotificationTest (18), TemplateAdminTest (19),
              StageAlertTest (20), PolicyAdminTest (21), RiskAlertTest (22), DeadlineSweepTest (23),
              ExpirySweepTest (24), AutoReminderTest (25), DigestScheduleTest (26), NotificationIsolationTest (27)
  sla/        EscalationDeliveryTest, EscalationRetryTest, SlaTestSupport, RecipientResolverTest (6)
  scheduling/ TenantJobRunnerTest (5), SlaSweepJobTest (6), DevToolsProfileTest (27)

frontend/src/
  lib/api/notifications.ts (+ test) (28)
  lib/format/date.ts — formatRelative (29)
  components/inbox/ InboxList, InboxRow, notificationVisuals (29); PreferencesPane (30);
                    InboxButton, InboxDrawer, useInboxShortcut (31)
  components/ui/Switch.tsx — disabled, ariaLabel, knob shadow (30); components/ui/Dialog.tsx — export focus helpers (31)
  components/shell/TopBar.tsx (+ test) (31)
  app/(app)/t/[slug]/admin/notifications/page.tsx, admin/layout.tsx; components/notifications/HorizonsCard (32);
  components/notifications/TemplatesCard, TemplateDialog (33)
  components/workflow/StageInspector.tsx (34)
frontend/e2e/notifications.spec.ts; e2e/support/tenant.ts; e2e/support/backend.mjs (35)
```

---

## Phase 0 — Baseline

### Task 1: Establish a green baseline across all suites

**Files:** none modified (report only).

- [ ] **Step 1: Branch.** From an up-to-date `main` that contains the spec and this plan, create the feature branch (or the worktree the execution skill creates): `git checkout -b feat/notifications`.
- [ ] **Step 2: Check Java.** `java -version` and `javac -version` (or the full paths under `C:\Program Files\OpenLogic\jdk-21*\bin\`). Both must print a version. If `javac` is blocked by Application Control, stop and report — do not try to bypass it.
- [ ] **Step 3: Backend, per package.** From `backend/`, for each top-level package under `co.ara.onboarding` (`architecture`, `agreement`, `audit`, `auth`, `authz`, `customer`, `document`, `identity`, `journey`, `platform`, `programme`, `provisioning`, `scheduling`, `scoping`, `security`, `sla`, `task`, `tenancy`, `workflow`, and any other directory under `backend/src/test/java/co/ara/onboarding/`): `.\gradlew.bat cleanTest test --max-workers=1 --tests "co.ara.onboarding.<package>.*"`. After each, total the XML results:
  ```bash
  cd backend/build/test-results/test && cat *.xml | grep -o '<testsuite [^>]*' | awk -F'"' '{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ failures=$/)f+=$(i+1);if($i~/ errors=$/)e+=$(i+1)}} END{print t, f, e}'
  ```
  then `cd` back out of `build/` before the next Gradle run.
- [ ] **Step 4: Frontend.** From `frontend/`: `npx vitest run`, `npx tsc --noEmit`, `npm run lint`. All clean (pre-existing warnings allowed).
- [ ] **Step 5: Playwright.** The `onboarding-db` container is mapped to **5434** on this machine (5432 is a different native Postgres). Create a scratch database: `docker exec onboarding-db createdb -U postgres onboarding_e2e_6b`. Then from `frontend/`: `$env:DB_URL="jdbc:postgresql://localhost:5434/onboarding_e2e_6b"; npx playwright test`. Kill stray `:8080`/`:3000` processes first **unless another session on this machine owns them**.
- [ ] **Step 6: Record.** Write the four results (counts, failures and their cause) to `.superpowers/sdd/2026-10-04-notifications/task-1-report.md`. A failure here is a pre-existing defect: diagnose it with superpowers:systematic-debugging and fix it in its own commit before Phase 1, never by weakening an assertion.

---

## Phase 1 — Module, schema, outbox

### Task 2: The `notification` module and its boundary rules

Moves `Notification`, `NotificationRepository` and `NotificationType` out of `sla` (spec §3.1) and makes the one-way arrows structural before anything else depends on them.

**Files:**
- Move: `backend/src/main/java/co/ara/onboarding/sla/{Notification,NotificationRepository,NotificationType}.java` → `backend/src/main/java/co/ara/onboarding/notification/`
- Modify: `backend/src/main/java/co/ara/onboarding/sla/SlaSweepService.java` (imports only)
- Modify: `backend/src/main/java/co/ara/onboarding/scoping/NotificationDescriptor.java` (import only)
- Modify: `backend/src/test/java/co/ara/onboarding/scoping/NotificationDescriptorTest.java` (imports only)
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java` (finder-rule package list)

**Interfaces:**
- Produces: `co.ara.onboarding.notification.Notification` (entity), `NotificationRepository`, `NotificationType` (still `{ ESCALATION }` until Task 3).

- [ ] **Step 1: Write the seven boundary rules** in `ModuleBoundaryTest`, after `noDocumentDependencyOnSla`:

```java
    // Sub-project 6B (spec 3.1): producers publish event records and never import notification.
    // Each arrow is its own rule because a one-way import would still pass the no-cycles check.
    @ArchTest
    static final ArchRule noTaskDependencyOnNotification =
            noClasses().that().resideInAPackage("..task..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("task publishes TaskAssigned/CommentAdded; notification listens (spec 3.2)");

    @ArchTest
    static final ArchRule noJourneyDependencyOnNotification =
            noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("journey publishes milestone and stage events; notification listens (spec 3.2)");

    @ArchTest
    static final ArchRule noDocumentDependencyOnNotification =
            noClasses().that().resideInAPackage("..document..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("document publishes request, upload, review and reminder events (spec 3.2, plan amendment 3)");

    @ArchTest
    static final ArchRule noAgreementDependencyOnNotification =
            noClasses().that().resideInAPackage("..agreement..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("agreement publishes AgreementStatusChanged; notification listens (spec 3.2)");

    @ArchTest
    static final ArchRule noWorkflowDependencyOnNotification =
            noClasses().that().resideInAPackage("..workflow..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("workflow declares the NotificationTemplateKeys port; notification implements it (plan amendment 5)");

    @ArchTest
    static final ArchRule noCustomerDependencyOnNotification =
            noClasses().that().resideInAPackage("..customer..")
                .should().dependOnClassesThat().resideInAPackage("..notification..")
                .because("customer publishes CustomerOwnerAssigned; notification listens (plan amendment 8)");

    @ArchTest
    static final ArchRule noNotificationDependencyOnSla =
            noClasses().that().resideInAPackage("..notification..")
                .should().dependOnClassesThat().resideInAPackage("..sla..")
                .because("sla writes escalations through notification; the reverse arrow would be a cycle (plan amendment 2)");
```

- [ ] **Step 2: See every rule red — do this after Step 3's move.** Before the move, `notification` does not exist and the rules pass vacuously, which proves nothing. After it, temporarily add `private static final Class<?> PROBE = co.ara.onboarding.notification.NotificationType.class;` to `co.ara.onboarding.task.TaskService`, run `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.architecture.ModuleBoundaryTest"`, and confirm `noTaskDependencyOnNotification` fails. Repeat the probe once in each of `journey.CaseService`, `document.DocumentService`, `agreement.AgreementService`, `workflow.WorkflowService`, `customer.CustomerService`, confirming each named rule fails; and once in the reverse direction (`co.ara.onboarding.sla.SlaClockView` referenced from a class in `notification`) for `noNotificationDependencyOnSla`. Remove every probe. Record the seven red runs in the task report.

- [ ] **Step 3: Move the three files.** `git mv` each into `backend/src/main/java/co/ara/onboarding/notification/`, change their `package` line to `co.ara.onboarding.notification`, and fix `Notification`'s javadoc to `/** An in-app notification (6B spec §4.1; created by sub-project 6, spec §4.5). */`. Remove the unused `java.time.LocalDate` import. Update imports in `SlaSweepService`, `NotificationDescriptor`, `NotificationDescriptorTest`, and anything else `rg "co\.ara\.onboarding\.sla\.Notification"` finds (test sources included).

- [ ] **Step 4: Bind the finder rule.** In `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`, add `"co.ara.onboarding.notification.."` to the package list beside `"co.ara.onboarding.sla.."`.

- [ ] **Step 5: Run.** `.\gradlew.bat cleanTest test --max-workers=1 --tests "co.ara.onboarding.architecture.*"`, then `--tests "co.ara.onboarding.sla.*"` and `--tests "co.ara.onboarding.scoping.*"`. Expected: all green.

- [ ] **Step 6: Commit.**

```bash
git add -A backend/src
git commit -m "refactor(notification): move Notification out of sla into its own module

Sub-project 6B (spec 3.1). notification owns the table from here on; sla
writes escalations through it, so the arrow is sla -> notification and
never the reverse. Seven named ModuleBoundaryTest rules pin the one-way
arrows (six producers never import notification; notification never
imports sla), each seen red with a probe. notification.. joins the
finder rule's package list.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Schema — widen `notification`, add the outbox, the catalogue

**Files:**
- Create: `backend/src/main/resources/db/migration/V35__notification_outbox.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{Notification,NotificationType}.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/{NotificationCatalog,Tone,EmailState,OutboxKind}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/sla/SlaClock.java` (`atRiskAlertedAt`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/NotificationSchemaTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum NotificationType { ESCALATION, TASK_ASSIGNED, TASK_OVERDUE, NEW_CUSTOMER, MILESTONE_COMPLETED,
      STAGE_CHANGED, DOCUMENT_REQUESTED, DOCUMENT_UPLOADED, DOCUMENT_DECIDED, AGREEMENT_STATUS, NEW_COMMENT,
      WORKFLOW_PUBLISHED, RISK_CHANGED, DEADLINE_APPROACHING, EXPIRY_RENEWAL }
  public enum Tone { RISK, WARN, OK, INFO }
  public enum EmailState { NONE, QUEUED, DIGEST_PENDING, DIGESTED }
  public enum OutboxKind { NOTIFICATION, DIGEST, CUSTOMER_REMINDER }
  public final class NotificationCatalog {
      public record Entry(NotificationType type, String label, boolean inAppDefault, boolean emailDefault, boolean locked) {}
      public static Entry of(NotificationType type);
      public static List<Entry> all();          // enum order
      public static List<Entry> optOut();       // all() minus locked
  }
  // Notification gains: subjectType (String), subjectId (UUID), inApp (boolean), emailState (EmailState),
  // dedupeKey (String), tone (Tone) — getters/setters.
  // SlaClock gains: Instant atRiskAlertedAt — getter/setter.
  ```

- [ ] **Step 1: Confirm the constraint and table names.** `docker exec onboarding-db psql -U postgres -d onboarding -c "\d notification"` and `-c "\d customer_contact"`. Expected: a check named `notification_type_check`; a table `customer_contact` with columns `id`, `status`, `email`. If either name differs, use the real one below and note it in the commit body.

- [ ] **Step 2: Write the failing `NotificationSchemaTest`.**

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSchemaTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;

    @Test
    void everyNotificationTypeIsAllowedByTheCheck() {
        UUID t = fixture.createTenant("ns-types");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-types.test"));
        for (NotificationType type : NotificationType.values()) {
            ownerJdbc().update("""
                insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path,
                    subject_type, subject_id, in_app, email_state, tone, created_at, updated_at)
                values (gen_random_uuid(), ?, ?, ?, 't', 'b', '/x', 'case', gen_random_uuid(), true, 'NONE', 'INFO', now(), now())""",
                t, u, type.name());
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ?", Long.class, t))
                .isEqualTo(NotificationType.values().length);
    }

    @Test
    void aDedupeKeyIsUniquePerRecipientButNullsAreNot() {
        UUID t = fixture.createTenant("ns-dedupe");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-dedupe.test"));
        String insert = """
            insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path,
                subject_type, subject_id, in_app, email_state, tone, dedupe_key, created_at, updated_at)
            values (gen_random_uuid(), ?, ?, 'TASK_OVERDUE', 't', 'b', '/x', 'task', gen_random_uuid(), true, 'NONE', 'WARN', ?, now(), now())""";
        ownerJdbc().update(insert, t, u, null);
        ownerJdbc().update(insert, t, u, null);                       // NULLs are distinct
        ownerJdbc().update(insert, t, u, "TASK_OVERDUE:x:2026-10-05");
        assertThatThrownBy(() -> ownerJdbc().update(insert, t, u, "TASK_OVERDUE:x:2026-10-05"))
                .hasMessageContaining("notification_once_per_dedupe_key");
    }

    @Test
    void anOutboxRowNamesExactlyOneRecipient() {
        UUID t = fixture.createTenant("ns-outbox");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-outbox.test"));
        assertThatThrownBy(() -> ownerJdbc().update("""
            insert into email_outbox (id, tenant_id, kind, to_address, subject, body, status, attempts,
                next_attempt_at, created_at, updated_at)
            values (gen_random_uuid(), ?, 'NOTIFICATION', 'a@b.c', 's', 'b', 'PENDING', 0, now(), now(), now())""", t))
                .hasMessageContaining("email_outbox_one_recipient_ck");
        ownerJdbc().update("""
            insert into email_outbox (id, tenant_id, kind, to_address, recipient_user_id, subject, body, status,
                attempts, next_attempt_at, created_at, updated_at)
            values (gen_random_uuid(), ?, 'NOTIFICATION', 'a@b.c', ?, 's', 'b', 'PENDING', 0, now(), now(), now())""", t, u);
    }

    @Test
    void theApplicationRoleCannotDeleteNotificationsOrOutboxRows() {
        withAppConnection(jdbc -> {
            for (String table : new String[]{"notification", "email_outbox", "email_outbox_item"}) {
                assertThatThrownBy(() -> jdbc.update("delete from " + table))
                        .hasMessageContaining("permission denied");
            }
        });
    }

    @Test
    void theCatalogueCoversEveryTypeAndLocksOnlyEscalation() {
        assertThat(NotificationCatalog.all()).extracting(NotificationCatalog.Entry::type)
                .containsExactly(NotificationType.values());
        assertThat(NotificationCatalog.all()).filteredOn(NotificationCatalog.Entry::locked)
                .extracting(NotificationCatalog.Entry::type).containsExactly(NotificationType.ESCALATION);
        assertThat(NotificationCatalog.optOut()).hasSize(14);
    }
}
```

- [ ] **Step 3: Run it — expect FAIL** (`email_outbox` does not exist; `TASK_ASSIGNED` violates the check). `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.notification.NotificationSchemaTest"`.

- [ ] **Step 4: Write `V35__notification_outbox.sql`.**

```sql
-- Sub-project 6B, spec §4.1, §4.5, §4.6 and plan amendments 6-7. notification widens to every
-- type; email_outbox becomes the single queue for every notification-class email. Neither
-- notification nor email_outbox grants DELETE: each row is the only record that a message
-- was raised or sent.

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN (
    'ESCALATION','TASK_ASSIGNED','TASK_OVERDUE','NEW_CUSTOMER','MILESTONE_COMPLETED','STAGE_CHANGED',
    'DOCUMENT_REQUESTED','DOCUMENT_UPLOADED','DOCUMENT_DECIDED','AGREEMENT_STATUS','NEW_COMMENT',
    'WORKFLOW_PUBLISHED','RISK_CHANGED','DEADLINE_APPROACHING','EXPIRY_RENEWAL'));

ALTER TABLE notification ADD COLUMN subject_type text    NULL;
ALTER TABLE notification ADD COLUMN subject_id   uuid    NULL;
ALTER TABLE notification ADD COLUMN in_app       boolean NOT NULL DEFAULT true;
ALTER TABLE notification ADD COLUMN email_state  text    NOT NULL DEFAULT 'NONE'
    CHECK (email_state IN ('NONE','QUEUED','DIGEST_PENDING','DIGESTED'));
ALTER TABLE notification ADD COLUMN tone         text    NOT NULL DEFAULT 'INFO'
    CHECK (tone IN ('RISK','WARN','OK','INFO'));
ALTER TABLE notification ADD COLUMN dedupe_key   text    NULL CHECK (length(dedupe_key) <= 200);

-- Every existing row is a sub-project 6 escalation about a case. Runs as the Flyway owner, so
-- RLS does not filter it (the V28 precedent).
UPDATE notification SET subject_type = 'case', subject_id = COALESCE(case_id, escalation_id),
    email_state = 'QUEUED', tone = 'RISK';
ALTER TABLE notification ALTER COLUMN subject_type SET NOT NULL;
ALTER TABLE notification ALTER COLUMN subject_id   SET NOT NULL;

-- Invariant 7: the database, not the sweep, makes a reminder idempotent. NULLs are distinct,
-- so event-driven rows (no key) are unconstrained.
ALTER TABLE notification ADD CONSTRAINT notification_once_per_dedupe_key
    UNIQUE (tenant_id, recipient_user_id, dedupe_key);
DROP INDEX notification_unsent_idx;
CREATE INDEX notification_inbox_idx  ON notification (tenant_id, recipient_user_id, id DESC) WHERE in_app;
CREATE INDEX notification_unread_idx ON notification (tenant_id, recipient_user_id) WHERE in_app AND read_at IS NULL;
CREATE INDEX notification_digest_idx ON notification (tenant_id, recipient_user_id) WHERE email_state = 'DIGEST_PENDING';

CREATE TABLE email_outbox (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant(id),
    kind                 text        NOT NULL CHECK (kind IN ('NOTIFICATION','DIGEST','CUSTOMER_REMINDER')),
    to_address           text        NOT NULL,
    recipient_user_id    uuid        NULL REFERENCES app_user(id),
    contact_id           uuid        NULL REFERENCES customer_contact(id),
    notification_id      uuid        NULL REFERENCES notification(id),
    document_request_id  uuid        NULL REFERENCES document_request(id),
    subject              text        NOT NULL,
    body                 text        NOT NULL,
    link_path            text        NULL,       -- appended as an absolute link at send time (plan amendment 7)
    status               text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SENDING','SENT','FAILED','SKIPPED')),
    attempts             int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL,
    lease_until          timestamptz NULL,
    last_error           text        NULL,
    sent_at              timestamptz NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT email_outbox_one_recipient_ck CHECK ((recipient_user_id IS NULL) <> (contact_id IS NULL))
);
CREATE INDEX email_outbox_due_idx ON email_outbox (next_attempt_at) WHERE status IN ('PENDING','SENDING');
SELECT enable_tenant_rls('email_outbox');
GRANT SELECT, INSERT, UPDATE ON email_outbox TO onboarding_app;

-- Which notifications one digest email carried. Append-only, the audit_event shape.
CREATE TABLE email_outbox_item (
    outbox_id        uuid        NOT NULL REFERENCES email_outbox(id),
    notification_id  uuid        NOT NULL REFERENCES notification(id),
    tenant_id        uuid        NOT NULL REFERENCES tenant(id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (outbox_id, notification_id)
);
SELECT enable_tenant_rls('email_outbox_item');
GRANT SELECT, INSERT ON email_outbox_item TO onboarding_app;

-- Unsent sub-project 6 escalation emails move into the outbox, so the dispatcher (not the
-- retired SLA retry step) delivers them.
INSERT INTO email_outbox (id, tenant_id, kind, to_address, recipient_user_id, notification_id, subject,
                          body, link_path, status, attempts, next_attempt_at, created_at, updated_at)
SELECT gen_random_uuid(), n.tenant_id, 'NOTIFICATION', u.email, n.recipient_user_id, n.id, n.title,
       n.body, n.link_path, 'PENDING', 0, now(), now(), now()
  FROM notification n JOIN app_user u ON u.id = n.recipient_user_id
 WHERE n.emailed_at IS NULL;

ALTER TABLE sla_clock ADD COLUMN at_risk_alerted_at timestamptz NULL;
```

- [ ] **Step 5: The enums and catalogue.** `Tone.java`, `EmailState.java`, `OutboxKind.java` are one-line enums as in **Interfaces**. Replace `NotificationType` with the fifteen values. Then:

```java
package co.ara.onboarding.notification;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static co.ara.onboarding.notification.NotificationType.*;

/**
 * Spec §5.1: every type, its preferences-pane label and its defaults. Defaults come from the
 * prototype's channel captions; ESCALATION is the one locked type (QA Q10).
 */
public final class NotificationCatalog {

    public record Entry(NotificationType type, String label, boolean inAppDefault, boolean emailDefault,
                        boolean locked) {}

    private static final Map<NotificationType, Entry> ENTRIES = new EnumMap<>(NotificationType.class);

    static {
        put(ESCALATION,           "Escalation to you",             true, true,  true);
        put(TASK_ASSIGNED,        "Task assigned to me",           true, true,  false);
        put(TASK_OVERDUE,         "Task overdue",                  true, true,  false);
        put(NEW_CUSTOMER,         "Customer assigned to me",       true, false, false);
        put(MILESTONE_COMPLETED,  "Milestone completed",           true, false, false);
        put(STAGE_CHANGED,        "Stage entered or exited",       true, false, false);
        put(DOCUMENT_REQUESTED,   "Document requested",            true, true,  false);
        put(DOCUMENT_UPLOADED,    "Document uploaded",             true, true,  false);
        put(DOCUMENT_DECIDED,     "Document approved or rejected", true, true,  false);
        put(AGREEMENT_STATUS,     "Agreement status changed",      true, true,  false);
        put(NEW_COMMENT,          "New comment",                   true, true,  false);
        put(WORKFLOW_PUBLISHED,   "Workflow version published",    true, false, false);
        put(RISK_CHANGED,         "Journey at risk or breached",   true, true,  false);
        put(DEADLINE_APPROACHING, "Deadline approaching",          true, false, false);
        put(EXPIRY_RENEWAL,       "Expiry and renewal",            true, true,  false);
    }

    private static void put(NotificationType t, String label, boolean inApp, boolean email, boolean locked) {
        ENTRIES.put(t, new Entry(t, label, inApp, email, locked));
    }

    private NotificationCatalog() {}

    public static Entry of(NotificationType type) { return ENTRIES.get(type); }

    public static List<Entry> all() { return Arrays.stream(NotificationType.values()).map(ENTRIES::get).toList(); }

    public static List<Entry> optOut() { return all().stream().filter(e -> !e.locked()).toList(); }
}
```

Add to `Notification` (columns as in the migration; enums `@Enumerated(EnumType.STRING)`): `subjectType` (`subject_type`), `subjectId` (`subject_id`), `inApp` (`in_app`, `boolean`), `emailState` (`email_state`), `tone` (`tone`), `dedupeKey` (`dedupe_key`), each with a getter and setter. Add `@Column(name = "at_risk_alerted_at") private Instant atRiskAlertedAt;` with getter/setter to `SlaClock`.

- [ ] **Step 6: Run** `NotificationSchemaTest`, then `--tests "co.ara.onboarding.sla.*"` and `--tests "co.ara.onboarding.architecture.RlsCoverageTest"`. Expected: PASS. The sub-project 6 escalation tests still pass at this point: `SlaSweepService.notify` still saves through JPA, and the new columns all have defaults except `subject_type`/`subject_id` — **so `notify` must set them now**: add `n.setSubjectType("case"); n.setSubjectId(e.caseId()); n.setInApp(true); n.setEmailState(EmailState.QUEUED); n.setTone(Tone.RISK);` before `notifications.save(n)`. (Task 6 replaces this method entirely.)

- [ ] **Step 7: Commit** — `feat(notification): widen notification to every type and add the email outbox` with a body naming spec §4.1/§4.5 and plan amendments 6-7.

---

### Task 4: The public base URL

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/platform/PublicBaseUrl.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `frontend/e2e/support/backend.mjs`
- Create: `backend/src/test/java/co/ara/onboarding/platform/PublicBaseUrlGuardTest.java`

**Interfaces:**
- Produces: `@Component public class PublicBaseUrl { public String value(); public String absolute(String path); static String validate(String configured); }`

- [ ] **Step 1: Write the failing test.**

```java
package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicBaseUrlGuardTest {

    private static MockEnvironment profiles(String... active) {
        var env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void blankIsRefusedOutsideDevAndTestAndNamesTheVariable() {
        assertThatThrownBy(() -> new PublicBaseUrl("", profiles("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_PUBLIC_BASE_URL");
        assertThatThrownBy(() -> new PublicBaseUrl("", profiles()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devAndTestDefaultToTheLocalFrontend() {
        assertThat(new PublicBaseUrl("", profiles("dev")).value()).isEqualTo("http://localhost:3000");
        assertThat(new PublicBaseUrl(null, profiles("test")).value()).isEqualTo("http://localhost:3000");
    }

    @Test
    void anExplicitValueWinsOnEveryProfile() {
        assertThat(new PublicBaseUrl("https://app.example.com", profiles("dev")).value())
                .isEqualTo("https://app.example.com");
    }

    @Test
    void malformedValuesAreRefused() {
        for (String bad : new String[]{"app.example.com", "ftp://app.example.com", "https://app.example.com/",
                "https://app.example.com?x=1", "https://"}) {
            assertThatThrownBy(() -> PublicBaseUrl.validate(bad)).as(bad).isInstanceOf(IllegalStateException.class);
        }
        assertThat(PublicBaseUrl.validate("https://example.com/onboarding")).isEqualTo("https://example.com/onboarding");
    }

    @Test
    void absoluteJoinsAPath() {
        assertThat(new PublicBaseUrl("https://app.example.com", profiles("prod")).absolute("/t/acme/x"))
                .isEqualTo("https://app.example.com/t/acme/x");
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (class missing).

- [ ] **Step 3: Implement.**

```java
package co.ara.onboarding.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Arrays;

/**
 * The absolute URL people open the application at (6B spec §6.5), used to turn a notification's
 * link path into a link an email can carry. Required outside the dev and test profiles: an email
 * with a relative link is a broken email. Unlike JWT_SECRET this is not a secret, so a dev/test
 * default is safe.
 */
@Component
public class PublicBaseUrl {

    static final String LOCAL_DEFAULT = "http://localhost:3000";
    private static final String REMEDY = " Set APP_PUBLIC_BASE_URL to the absolute URL users open the"
            + " application at, without a trailing slash (for example https://onboarding.example.com).";

    private final String value;

    public PublicBaseUrl(@Value("${app.public-base-url:}") String configured, Environment env) {
        boolean local = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("dev") || p.equals("test"));
        String candidate = (configured == null || configured.isBlank()) && local ? LOCAL_DEFAULT : configured;
        this.value = validate(candidate);
    }

    static String validate(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("app.public-base-url is not set." + REMEDY);
        }
        URI uri;
        try {
            uri = URI.create(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.public-base-url is not a URL." + REMEDY);
        }
        boolean httpish = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!httpish || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
                || configured.endsWith("/")) {
            throw new IllegalStateException("app.public-base-url must be an absolute http(s) URL with no query,"
                    + " fragment or trailing slash." + REMEDY);
        }
        return configured;
    }

    public String value() { return value; }

    public String absolute(String path) { return value + path; }
}
```

In `application.yml`, under `app:` after `platform-admin:`, add:

```yaml
  # Absolute URL users open the app at; every notification email links through it (6B spec 6.5).
  # Required outside dev/test -- PublicBaseUrl refuses to start without it, naming the variable.
  public-base-url: ${APP_PUBLIC_BASE_URL:}
```

In `frontend/e2e/support/backend.mjs`'s `env` block add `APP_PUBLIC_BASE_URL: process.env.APP_PUBLIC_BASE_URL ?? "http://localhost:3000",`.

- [ ] **Step 4: Run** `PublicBaseUrlGuardTest` — PASS. Run `--tests "co.ara.onboarding.platform.*"`.
- [ ] **Step 5: Commit** — `feat(platform): require a public base URL for links in email`.

---

### Task 5: The outbox writer and the dispatcher

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/OutboxWriter.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/EmailDispatchService.java`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/EmailDispatchJob.java`
- Modify: `backend/src/main/java/co/ara/onboarding/scheduling/TenantJobRunner.java` (`forTenantUnlocked`, `activeTenantIds`)
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java` (`EMAIL_FAILED`)
- Modify: `backend/src/main/resources/application.yml` (`app.notifications.dispatch-interval`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/NotificationTestSupport.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/EmailDispatchTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/scheduling/TenantJobRunnerTest.java`

**Interfaces:**
- Consumes: `OutboxKind`, `PublicBaseUrl` (Tasks 3, 4).
- Produces:
  ```java
  // notification
  @Component public class OutboxWriter {
      public record OutboxMessage(OutboxKind kind, String toAddress, UUID recipientUserId, UUID contactId,
                                  UUID notificationId, UUID documentRequestId, String subject, String body,
                                  String linkPath) {}
      @Transactional(propagation = MANDATORY) public UUID queue(OutboxMessage m);
  }
  @Service public class EmailDispatchService {
      public static final int MAX_ATTEMPTS = 5;
      public static final List<Duration> BACKOFF;   // 1m, 5m, 30m, 2h
      public record Claimed(UUID id, OutboxKind kind, String to, String subject, String body, String linkPath,
                            UUID notificationId, int attempts) {}
      public enum Outcome { SENT, FAILED }
      public record Result(UUID id, Outcome outcome, String error) {}
      @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public List<Claimed> claim(int limit);
      @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public void stamp(List<Result> results);
  }
  // scheduling
  public boolean forTenantUnlocked(String job, UUID tenantId, Consumer<UUID> body);   // TenantJobRunner
  public List<UUID> activeTenantIds();                                               // TenantJobRunner
  @Component public class EmailDispatchJob { public int runOne(UUID tenantId); public void runAll(); }
  // audit
  AuditActions.EMAIL_FAILED = of("email.failed", false)
  // test support
  @Component public class NotificationTestSupport {
      public UUID queueEmail(UUID tenant, UUID recipientUserId, String to, String subject);  // runs inside runAs
      public List<Map<String, Object>> outbox(UUID tenant);
      public List<Map<String, Object>> notifications(UUID tenant);
  }
  ```

- [ ] **Step 1: `TenantJobRunner` additions, test first.** Add to `TenantJobRunnerTest`:

```java
    @Test
    void anUnlockedRunIsNotSkippedWhileTheLockedRunHoldsTheLock() throws Exception {
        UUID t = fixture.createTenant("job-unlocked");
        var ranInside = new java.util.concurrent.atomic.AtomicBoolean();
        runner.forTenant("contended", t, x -> ranInside.set(runner.forTenantUnlocked("contended", t, y -> {})));
        assertThat(ranInside).isTrue();
    }

    @Test
    void activeTenantIdsSkipsSuspendedTenants() {
        UUID a = fixture.createTenant("job-ids-a");
        UUID b = fixture.createTenant("job-ids-b");
        ownerJdbc().update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", b);
        assertThat(runner.activeTenantIds()).contains(a).doesNotContain(b);
    }
```

Run — FAIL. Implement by extracting the body of `forTenant` into `private boolean run(String job, UUID tenantId, Consumer<UUID> body, boolean locked)` (the only change inside: `if (locked && !lock.tryLock(job, tenantId)) return false;`), with `forTenant` calling `run(job, tenantId, body, true)` and:

```java
    /**
     * Plan amendment 10: for a run that must never be skipped because another run of the same job
     * holds the advisory lock -- the dispatcher's stamp step, whose rows are leased to this caller.
     */
    public boolean forTenantUnlocked(String job, UUID tenantId, Consumer<UUID> body) {
        return run(job, tenantId, body, false);
    }

    public List<UUID> activeTenantIds() {
        return tenants.findAll().stream().filter(t -> t.getStatus() == TenantStatus.ACTIVE).map(Tenant::getId).toList();
    }
```

Run `TenantJobRunnerTest` — PASS.

- [ ] **Step 2: Test support.**

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.support.PostgresTestBase;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Arrange/assert helpers for the notification tests. Reads go through the owner connection (assertions only). */
@Component
public class NotificationTestSupport {

    private final OutboxWriter outbox;

    NotificationTestSupport(OutboxWriter outbox) { this.outbox = outbox; }

    /** Must run inside fixture.runAs. */
    public UUID queueEmail(UUID tenant, UUID recipientUserId, String to, String subject) {
        return outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, to, recipientUserId, null,
                null, null, subject, "body of " + subject, "/t/x/path"));
    }

    public List<Map<String, Object>> outbox(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from email_outbox where tenant_id = ? order by created_at, id", tenant);
    }

    public List<Map<String, Object>> notifications(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from notification where tenant_id = ? order by created_at, id", tenant);
    }
}
```

- [ ] **Step 3: Write the failing `EmailDispatchTest`.** First a shared test configuration, reused by every outbox test from here on (Tasks 7, 25, 26). It wraps the `@Primary RecordingEmailSender` the same way `EscalationRetryTest.Flaky` does — and, because the wrapper *replaces* that bean, tests that import it read sent mail from `FlakyEmail.sent`, never by autowiring `RecordingEmailSender`.

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.support.RecordingEmailSender;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** An EmailSender that can be told to fail or to hold sends, recording what it delivered. */
@TestConfiguration
public class FlakyEmail {

    public static final AtomicBoolean failing = new AtomicBoolean();
    public static final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    public static volatile CountDownLatch hold = null;

    public static void reset() { failing.set(false); sent.clear(); hold = null; }

    public static List<String> recipients() { return sent.stream().map(EmailMessage::to).toList(); }

    public static EmailMessage lastTo(String address) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).reduce((a, b) -> b).orElseThrow();
    }

    @Bean static BeanPostProcessor flakySender() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof RecordingEmailSender)) return bean;
                return (EmailSender) (EmailMessage m) -> {
                    if (failing.get()) throw new IllegalStateException("smtp down");
                    var latch = hold;
                    if (latch != null) {
                        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    sent.add(m);
                };
            }
        };
    }
}
```

Then the test:

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.scheduling.EmailDispatchJob;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static co.ara.onboarding.notification.FlakyEmail.failing;
import static org.assertj.core.api.Assertions.assertThat;

@Import(FlakyEmail.class)
class EmailDispatchTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired EmailDispatchJob dispatch;

    @AfterEach void reset() { FlakyEmail.reset(); }

    private UUID[] tenantWithUser(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@" + slug + ".test"));
        return new UUID[]{t, u};
    }

    @Test
    void aQueuedEmailIsSentOnceWithAnAbsoluteLinkAndStamped() {
        UUID[] x = tenantWithUser("disp-ok");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-ok.test", "Hello"));
        assertThat(dispatch.runOne(x[0])).isEqualTo(1);
        var row = support.outbox(x[0]).get(0);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(FlakyEmail.lastTo("u@disp-ok.test").body()).endsWith("Open: http://localhost:3000/t/x/path");
        assertThat(dispatch.runOne(x[0])).isZero();
        assertThat(FlakyEmail.recipients()).containsOnlyOnce("u@disp-ok.test");
    }

    @Test
    void failuresBackOffThenFailAfterFiveAttemptsAndAreAudited() {
        UUID[] x = tenantWithUser("disp-fail");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-fail.test", "Hello"));
        failing.set(true);
        Duration[] waits = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        dispatch.runOne(x[0]);
        for (Duration wait : waits) {
            var row = support.outbox(x[0]).get(0);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("last_error")).asString().contains("smtp down");
            assertThat(dispatch.runOne(x[0])).as("not due yet").isZero();
            clock.advance(wait.plusSeconds(1));
            dispatch.runOne(x[0]);
        }
        var row = support.outbox(x[0]).get(0);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(5);
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where tenant_id = ? and action = 'email.failed' and timeline_visible = false",
                Long.class, x[0])).isEqualTo(1L);
    }

    @Test
    void anInactiveRecipientIsSkippedNotSent() {
        UUID[] x = tenantWithUser("disp-inactive");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-inactive.test", "Hello"));
        ownerJdbc().update("update app_user set status = 'INACTIVE' where id = ?", x[1]);
        dispatch.runOne(x[0]);
        assertThat(support.outbox(x[0]).get(0).get("status")).isEqualTo("SKIPPED");
        assertThat(FlakyEmail.sent).isEmpty();
    }

    @Test
    void anExpiredLeaseIsReclaimed() {
        UUID[] x = tenantWithUser("disp-lease");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-lease.test", "Hello"));
        // Simulate a dispatcher that claimed and then crashed before stamping.
        ownerJdbc().update("update email_outbox set status = 'SENDING', attempts = 1, lease_until = now() + interval '5 minutes' where tenant_id = ?", x[0]);
        assertThat(dispatch.runOne(x[0])).isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(dispatch.runOne(x[0])).isEqualTo(1);
        assertThat(support.outbox(x[0]).get(0).get("attempts")).isEqualTo(2);
    }

    @Test
    void concurrentDispatchersNeverDoubleSend() throws Exception {   // Review Focus 4
        UUID[] x = tenantWithUser("disp-race");
        fixture.runAs(x[0], () -> {
            for (int i = 0; i < 20; i++) support.queueEmail(x[0], x[1], "u@disp-race.test", "Hello " + i);
        });
        FlakyEmail.hold = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> dispatch.runOne(x[0]));
            Future<Integer> b = pool.submit(() -> dispatch.runOne(x[0]));
            Thread.sleep(300);
            FlakyEmail.hold.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(FlakyEmail.sent).hasSize(20);
        assertThat(support.outbox(x[0])).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SENT"));
    }
}
```

Run — expect FAIL (classes missing).

- [ ] **Step 4: `OutboxWriter`.**

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The only way a notification-class email is sent (invariant 8): a row written in the caller's
 * transaction, delivered later by EmailDispatchJob. A rolled-back caller leaves no row, so nothing
 * is ever emailed about an action that did not happen.
 */
@Component
public class OutboxWriter {

    public record OutboxMessage(OutboxKind kind, String toAddress, UUID recipientUserId, UUID contactId,
                                UUID notificationId, UUID documentRequestId, String subject, String body,
                                String linkPath) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    OutboxWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID queue(OutboxMessage m) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO email_outbox (id, tenant_id, kind, to_address, recipient_user_id, contact_id,
                    notification_id, document_request_id, subject, body, link_path, status, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)""",
                id, TenantContext.getRequired(), m.kind().name(), m.toAddress(), m.recipientUserId(),
                m.contactId(), m.notificationId(), m.documentRequestId(), m.subject(), m.body(), m.linkPath(),
                now, now, now);
        return id;
    }
}
```

- [ ] **Step 5: `EmailDispatchService`.** Claim and stamp each run in their own tenant run (spec §6.4); the send in between runs in `EmailDispatchJob`, outside any transaction.

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec §6.4. claim() leases due rows (FOR UPDATE SKIP LOCKED, so concurrent dispatchers never
 * take the same row) and skips any whose recipient has gone inactive; stamp() records each send's
 * outcome with backoff and a cap. Gated sla.view, the job marker the system actor holds (plan
 * amendment 9); no controller exposes it.
 */
@Service
public class EmailDispatchService {

    public static final int MAX_ATTEMPTS = 5;
    public static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
    static final Duration LEASE = Duration.ofMinutes(5);

    public record Claimed(UUID id, OutboxKind kind, String to, String subject, String body, String linkPath,
                          UUID notificationId, int attempts) {}
    public enum Outcome { SENT, FAILED }
    public record Result(UUID id, Outcome outcome, String error) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditRecorder audit;

    public EmailDispatchService(JdbcTemplate jdbc, Clock clock, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Claimed> claim(int limit) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        Timestamp leaseUntil = Timestamp.from(Instant.now(clock).plus(LEASE));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE email_outbox o SET status = 'SENDING', lease_until = ?, attempts = o.attempts + 1, updated_at = ?
                 WHERE o.id IN (SELECT id FROM email_outbox
                                 WHERE status IN ('PENDING','SENDING') AND next_attempt_at <= ?
                                   AND (lease_until IS NULL OR lease_until < ?)
                                 ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING o.id, o.kind, o.to_address, o.subject, o.body, o.link_path, o.notification_id,
                          o.attempts, o.recipient_user_id, o.contact_id""",
                leaseUntil, now, now, now, limit);
        List<Claimed> claimed = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            if (!recipientStillActive((UUID) r.get("recipient_user_id"), (UUID) r.get("contact_id"))) {
                jdbc.update("UPDATE email_outbox SET status = 'SKIPPED', lease_until = NULL, updated_at = ? WHERE id = ?",
                        now, id);
                continue;
            }
            claimed.add(new Claimed(id, OutboxKind.valueOf((String) r.get("kind")), (String) r.get("to_address"),
                    (String) r.get("subject"), (String) r.get("body"), (String) r.get("link_path"),
                    (UUID) r.get("notification_id"), ((Number) r.get("attempts")).intValue()));
        }
        return claimed;
    }

    private boolean recipientStillActive(UUID userId, UUID contactId) {
        if (userId != null) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND status = 'ACTIVE')", Boolean.class, userId));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customer_contact WHERE id = ? AND status = 'ACTIVE')", Boolean.class, contactId));
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void stamp(List<Result> results) {
        Instant at = Instant.now(clock);
        Timestamp now = Timestamp.from(at);
        for (Result r : results) {
            if (r.outcome() == Outcome.SENT) {
                jdbc.update("UPDATE email_outbox SET status = 'SENT', sent_at = ?, lease_until = NULL, last_error = NULL,"
                        + " updated_at = ? WHERE id = ? AND status = 'SENDING'", now, now, r.id());
                jdbc.update("UPDATE notification SET emailed_at = ?, updated_at = ? WHERE id ="
                        + " (SELECT notification_id FROM email_outbox WHERE id = ? AND kind = 'NOTIFICATION')"
                        + " AND emailed_at IS NULL", now, now, r.id());
                continue;
            }
            int attempts = jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE id = ?", Integer.class, r.id());
            String error = r.error() == null ? "unknown" : r.error().substring(0, Math.min(500, r.error().length()));
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE email_outbox SET status = 'FAILED', lease_until = NULL, last_error = ?, updated_at = ?"
                        + " WHERE id = ?", error, now, r.id());
                audit.record(AuditActions.EMAIL_FAILED, "email_outbox", r.id(),
                        "Email delivery failed after " + attempts + " attempts", Map.of("attempts", attempts));
            } else {
                Timestamp next = Timestamp.from(at.plus(BACKOFF.get(attempts - 1)));
                jdbc.update("UPDATE email_outbox SET status = 'PENDING', next_attempt_at = ?, lease_until = NULL,"
                        + " last_error = ?, updated_at = ? WHERE id = ?", next, error, now, r.id());
            }
        }
    }
}
```

Add to `AuditActions`, after the sub-project 6 tenant-configuration block:

```java
    // Notifications (sub-project 6B). All compliance-only (6B spec 4.7).
    public static final AuditAction EMAIL_FAILED = of("email.failed", false);
```

- [ ] **Step 6: `EmailDispatchJob`.**

```java
package co.ara.onboarding.scheduling;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.notification.EmailDispatchService;
import co.ara.onboarding.notification.EmailDispatchService.Claimed;
import co.ara.onboarding.notification.EmailDispatchService.Outcome;
import co.ara.onboarding.notification.EmailDispatchService.Result;
import co.ara.onboarding.platform.PublicBaseUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spec §6.4: claim (locked tenant run) -> send (no transaction) -> stamp (unlocked tenant run).
 * A crash between send and stamp leaves the lease to expire and the row is sent again:
 * at-least-once, one duplicate at most per crash.
 */
@Component
public class EmailDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchJob.class);
    static final int BATCH = 50;
    static final int MAX_BATCHES = 10;

    private final TenantJobRunner runner;
    private final EmailDispatchService dispatch;
    private final EmailSender email;
    private final PublicBaseUrl baseUrl;

    public EmailDispatchJob(TenantJobRunner runner, EmailDispatchService dispatch, EmailSender email,
                            PublicBaseUrl baseUrl) {
        this.runner = runner;
        this.dispatch = dispatch;
        this.email = email;
        this.baseUrl = baseUrl;
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval:PT1M}", initialDelayString = "PT30S")
    public void scheduled() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("Email dispatch run failed", e);
        }
    }

    public void runAll() {
        for (UUID tenant : runner.activeTenantIds()) {
            try {
                runOne(tenant);
            } catch (RuntimeException e) {
                log.error("Email dispatch failed for tenant {}", tenant, e);
            }
        }
    }

    /** Returns how many emails were sent. */
    public int runOne(UUID tenantId) {
        int sent = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Claimed> claimed = new ArrayList<>();
            runner.forTenant("email-claim", tenantId, t -> claimed.addAll(dispatch.claim(BATCH)));
            if (claimed.isEmpty()) break;
            List<Result> results = claimed.stream().map(this::send).toList();
            runner.forTenantUnlocked("email-stamp", tenantId, t -> dispatch.stamp(results));
            sent += (int) results.stream().filter(r -> r.outcome() == Outcome.SENT).count();
            if (claimed.size() < BATCH) break;
        }
        return sent;
    }

    private Result send(Claimed c) {
        String body = c.linkPath() == null ? c.body() : c.body() + "\n\nOpen: " + baseUrl.absolute(c.linkPath());
        try {
            email.send(new EmailMessage(c.to(), c.subject(), body));
            return new Result(c.id(), Outcome.SENT, null);
        } catch (RuntimeException e) {
            log.warn("Email {} ({}) to {} failed on attempt {}", c.id(), c.kind(), c.to(), c.attempts(), e);
            return new Result(c.id(), Outcome.FAILED, e.getMessage());
        }
    }
}
```

In `application.yml` under `app:` add `notifications:` with `dispatch-interval: PT1M`, `sweep-interval: PT1H`, `digest-interval: PT15M` (the last two are read in Tasks 23 and 26).

- [ ] **Step 7: Run** `EmailDispatchTest` and `TenantJobRunnerTest` — PASS. Run `--tests "co.ara.onboarding.architecture.*"`: `EmailDispatchService` is gated and calls no repository finder, so both coverage rules stay green.
- [ ] **Step 8: Commit** — `feat(notification): an email outbox with attempt tracking, backoff and a cap` (body: spec §6.4, plan amendment 10, Review Focus 4).

---

### Task 6: Escalations through the outbox, and the sole-administrator fallback

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/NotificationWriter.java`
- Modify: `backend/src/main/java/co/ara/onboarding/sla/SlaSweepService.java`
- Delete: `backend/src/main/java/co/ara/onboarding/sla/EscalationMailer.java`
- Modify: `backend/src/main/java/co/ara/onboarding/sla/RecipientResolver.java`
- Modify: `backend/src/main/java/co/ara/onboarding/scheduling/SlaSweepJob.java`
- Modify: `backend/src/test/java/co/ara/onboarding/sla/{SlaTestSupport,EscalationDeliveryTest,EscalationRetryTest,RecipientResolverTest}.java`
- Modify: `backend/src/test/java/co/ara/onboarding/scheduling/SlaSweepJobTest.java`

**Interfaces:**
- Consumes: `OutboxWriter`, `EmailState`, `Tone`, `EmailDispatchJob` (Tasks 3, 5).
- Produces:
  ```java
  @Component public class NotificationWriter {
      public record EscalationNotice(UUID recipientUserId, String recipientEmail, String title, String body,
                                     String linkPath, UUID caseId, UUID escalationId) {}
      @Transactional(propagation = MANDATORY) public UUID escalation(EscalationNotice n);
      // Task 10 adds: Optional<UUID> write(Row row)
  }
  ```

- [ ] **Step 1: Update the sub-project 6 tests to the outbox first** — they describe behaviour that must survive:
  - `SlaTestSupport.sweepAndEmail(tenant)`: replace the second line with `dispatch.runOne(tenant);` and inject `co.ara.onboarding.scheduling.EmailDispatchJob dispatch` instead of nothing new (keep `runner` and `sweep`).
  - `EscalationDeliveryTest.aRolledBackSweepSendsNothing`: replace `runner.forTenant("sla-email", x[0], t -> sweep.retryUnsentEmail());` with `dispatch.runOne(x[0]);` (autowire `EmailDispatchJob dispatch`), and additionally assert `select count(*) from email_outbox where tenant_id = ?` is 0.
  - `EscalationRetryTest`: `unsent(t)` now counts `select count(*) from email_outbox where tenant_id = ? and status <> 'SENT'`. Before the second `sla.sweepAndEmail(t)`, add `clock.advance(java.time.Duration.ofMinutes(2));` (the first retry is due one minute after the failure). Rename the test `aFailedSendIsRetriedAfterBackoffAndOnlyToWhoWasNotReached`.
  - `SlaSweepJobTest`: where it asserts `emailed_at is not null`, assert instead that `select count(*) from email_outbox where tenant_id = ?` is positive (sweeping queues; the dispatcher sends).
  - Add to `RecipientResolverTest` (its existing fixture builds users and the Administrator role — reuse its helpers):

```java
    @Test
    void aSoleAdministratorWhoIsLateIsTheirOwnRecipient() {
        // 6B spec 5.4: the only administrator owns the late milestone and has no manager or head.
        // Before 6B this resolved to nobody; now it reaches the one person who can act.
        UUID t = fixture.createTenant("res-sole");
        UUID admin = fixture.createAdminUser(t, "only@res-sole.test").getId();
        // activeAdministrators() matches the role by name (sub-project 6's open item); the
        // fixture role is renamed exactly as EscalationRetryTest does.
        ownerJdbc().update("update role set name = 'Administrator' where id = ?", fixture.administratorRoleId(t));
        var resolution = fixture.runAsReturning(t, () -> resolver.resolve(admin));
        assertThat(resolution.route()).isEqualTo(EscalationRoute.ADMINISTRATORS);
        assertThat(resolution.recipients()).extracting(ReportingLineDirectory.Recipient::userId)
                .containsExactly(admin);
    }
```

  The late person has no `manager_id` and no department, so the chain falls straight to administrators — and the only one is the late person. If the fixture's own superuser administrator (the one `runAs` uses) also holds the renamed role, deactivate it in this test with owner SQL (`update app_user set status = 'INACTIVE' where id = ?`) **after** the last `runAs`-based arrangement, and resolve through `fixture.runAsUser(t, admin, …)` instead so `admin` is the only active administrator. (`RecipientResolverTest` must extend `PostgresTestBase` and autowire `TenantFixture fixture` and `RecipientResolver resolver`; add whichever it lacks.)

  Run `--tests "co.ara.onboarding.sla.*"` — expect the resolver test and the outbox assertions to FAIL.

- [ ] **Step 2: `NotificationWriter`.**

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The one place a notification row is inserted. Escalations (sla) call {@link #escalation}
 * directly: spec 5.3 step 5 -- no preference, no visibility drop, always in-app and always an
 * immediate email. Everything else goes through NotificationPipeline (Task 10).
 */
@Component
public class NotificationWriter {

    public record EscalationNotice(UUID recipientUserId, String recipientEmail, String title, String body,
                                   String linkPath, UUID caseId, UUID escalationId) {}

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final Clock clock;

    NotificationWriter(JdbcTemplate jdbc, OutboxWriter outbox, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID escalation(EscalationNotice n) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO notification (id, tenant_id, recipient_user_id, type, title, body, link_path, case_id,
                    escalation_id, subject_type, subject_id, in_app, email_state, tone, created_at, updated_at)
                VALUES (?, ?, ?, 'ESCALATION', ?, ?, ?, ?, ?, 'case', ?, true, 'QUEUED', 'RISK', ?, ?)""",
                id, TenantContext.getRequired(), n.recipientUserId(), n.title(), n.body(), n.linkPath(),
                n.caseId(), n.escalationId(), n.caseId(), now, now);
        outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, n.recipientEmail(), n.recipientUserId(),
                null, id, null, n.title(), n.body(), n.linkPath()));
        audit.record(AuditActions.NOTIFICATION_SENT, "notification", id, "Escalation notification queued",
                Map.of("escalationId", n.escalationId().toString(), "recipientUserId", n.recipientUserId().toString()));
        return id;
    }
}
```

- [ ] **Step 3: Rewire `SlaSweepService`.** Remove the `NotificationRepository notifications` and `EscalationMailer mailer` fields, constructor parameters and imports; remove `stampEmailed`, `retryUnsentEmail` and the now-unused `perRow`/`binder`/`TransactionTemplate` plumbing if nothing else uses it (keep it if `stampBreaches` or another method does — check with `rg "perRow|binder" sla/SlaSweepService.java`). Add `NotificationWriter writer` (name the field `notifications`). Replace the inner loop of `notify` with:

```java
            for (ReportingLineDirectory.Recipient r : recipients) {
                notifications.escalation(new NotificationWriter.EscalationNotice(r.userId(), r.email(),
                        "Escalation: " + what + " overdue by " + e.overdueDays() + " business day(s)",
                        what + " overdue on case '" + e.caseName() + "'. Late person: " + late + ".",
                        "/t/" + slug + "/customers/" + e.customerId() + "/cases/" + e.caseId(),
                        e.caseId(), e.escalationId()));
                written++;
            }
```

and change the empty-recipients log to `log.error("Escalation {} in tenant {} has no active administrator to deliver to", ...)` — unchanged wording, but it is now reached **only** when the tenant has no active administrator at all (Step 4), so the message is finally true. Delete `EscalationMailer.java`.

- [ ] **Step 4: The fallback in `RecipientResolver.resolve`.** Replace the administrators tail with:

```java
        // Never the late person about their own lateness -- unless they are the tenant's only
        // active administrator, in which case they are the only person who can act (6B spec 5.4,
        // closing sub-project 6 spec 6.2's amendment). An empty list now means no administrator exists.
        var admins = people.activeAdministrators();
        var others = admins.stream().filter(r -> !r.userId().equals(latePersonId)).toList();
        return new Resolution(EscalationRoute.ADMINISTRATORS, others.isEmpty() ? admins : others);
```

- [ ] **Step 5: `SlaSweepJob`.** Inject `EmailDispatchJob dispatch`. `runAll()` keeps only the sweep run (the dispatcher's own minute schedule delivers); `runOne(tenantId)` sweeps then calls `dispatch.runOne(tenantId)` so the dev endpoint and `sla.spec.ts` still see the email immediately. Update the class javadoc: "Run 1 writes and commits; delivery is the outbox dispatcher's (6B spec 6.4) — runOne also dispatches, for the dev endpoint."

- [ ] **Step 6: Run** `--tests "co.ara.onboarding.sla.*"`, `--tests "co.ara.onboarding.scheduling.*"`, `--tests "co.ara.onboarding.scoping.*"`, `--tests "co.ara.onboarding.architecture.*"`. Expected: all PASS, including the three `EscalationDeliveryTest` cases about administrators (the late-admin-with-others case still excludes the late person).
- [ ] **Step 7: Commit** — `feat(sla): deliver escalations through the outbox; fall back to a sole late administrator` (body: closes sub-project 6's two open items — unbounded retry, recipientless sole-admin escalation; spec §5.4).

---

### Task 7: Customer reminders through the outbox

Closes sub-project 6's "a failed reminder email is silently lost" (spec §12.3). `document` cannot import `notification` (plan amendment 3), so it publishes an event.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/document/CustomerReminderQueued.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequestService.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/ReminderNotifications.java`
- Modify: `backend/src/test/java/co/ara/onboarding/document/CustomerReminderTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/CustomerReminderOutboxTest.java`

**Interfaces:**
- Produces:
  ```java
  // document
  public record CustomerReminderQueued(UUID requestId, UUID caseId, UUID contactId, String toAddress,
                                       String subject, String body, boolean automatic) {}
  // notification
  @Component public class ReminderNotifications { @EventListener public void on(CustomerReminderQueued e); }
  ```

- [ ] **Step 1: Write the failing test.**

First move `CustomerReminderTest`'s private arrange helper (the one that builds a case, a contact and an OPEN request naming that contact) into `NotificationTestSupport` as

```java
    public record Arranged(UUID tenant, UUID caseId, UUID requestId, UUID contactId, String contactEmail) {}
    /** A tenant with one case and one OPEN document request naming an ACTIVE contact. */
    public Arranged openRequestWithContact(String slug)
```

and make `CustomerReminderTest` call it, so both tests (and Task 25's) share one arrangement.

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.scheduling.EmailDispatchJob;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.Duration;
import static co.ara.onboarding.notification.FlakyEmail.failing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(FlakyEmail.class)
class CustomerReminderOutboxTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired EmailDispatchJob dispatch;
    @Autowired DocumentRequestService requests;

    @AfterEach void reset() { FlakyEmail.reset(); }

    @Test
    void aReminderIsQueuedNotSentInline() {
        var x = support.openRequestWithContact("rem-queued");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        var row = support.outbox(x.tenant()).get(0);
        assertThat(row.get("kind")).isEqualTo("CUSTOMER_REMINDER");
        assertThat(row.get("contact_id")).isEqualTo(x.contactId());
        assertThat(row.get("recipient_user_id")).isNull();
        assertThat(row.get("document_request_id")).isEqualTo(x.requestId());
        assertThat(FlakyEmail.sent).isEmpty();               // nothing sent inside the request
        dispatch.runOne(x.tenant());
        assertThat(FlakyEmail.recipients()).containsExactly(x.contactEmail());
    }

    @Test
    void aFailedReminderEmailIsRetriedNotLost() {
        var x = support.openRequestWithContact("rem-retry");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        failing.set(true);
        dispatch.runOne(x.tenant());
        assertThat(support.outbox(x.tenant()).get(0).get("status")).isEqualTo("PENDING");
        failing.set(false);
        clock.advance(Duration.ofMinutes(2));
        dispatch.runOne(x.tenant());
        assertThat(FlakyEmail.recipients()).containsExactly(x.contactEmail());
    }

    @Test
    void aRolledBackReminderQueuesNothing() {
        var x = support.openRequestWithContact("rem-rollback");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        // The 24h floor refuses a second reminder: its transaction rolls back, so no second row.
        assertThatThrownBy(() -> fixture.runAs(x.tenant(), () -> requests.remind(x.requestId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(support.outbox(x.tenant())).hasSize(1);
    }
}
```

Run — FAIL (no outbox row; the email is sent after commit).

- [ ] **Step 2: The event and the publish.** Create the record (as **Interfaces**). In `DocumentRequestService`: remove the `EmailSender email` constructor parameter, field and the `afterCommit` synchronization block (and the now-unused `TransactionSynchronization*` imports); add `ApplicationEventPublisher events` as the last constructor parameter. After the `markReminded(...) == 0` guard (which must stay where it is — a refused reminder must publish nothing), add:

```java
        // Plan amendment 3: document never imports notification. The listener queues the outbox
        // row in this same transaction, so a rolled-back reminder queues nothing.
        events.publishEvent(new CustomerReminderQueued(requestId, caseId, contact.getId(), to, subject, body, false));
```

- [ ] **Step 3: The listener.**

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.document.CustomerReminderQueued;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A customer reminder becomes a CUSTOMER_REMINDER outbox row (spec 6.2; plan amendment 3). */
@Component
public class ReminderNotifications {

    private final OutboxWriter outbox;

    ReminderNotifications(OutboxWriter outbox) { this.outbox = outbox; }

    @EventListener
    public void on(CustomerReminderQueued e) {
        outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.CUSTOMER_REMINDER, e.toAddress(), null, e.contactId(),
                null, e.requestId(), e.subject(), e.body(), null));
    }
}
```

- [ ] **Step 4: Update `CustomerReminderTest`.** Wherever it reads the sent email from `RecordingEmailSender` right after `remind`, first call `dispatch.runOne(tenant)` (autowire `EmailDispatchJob dispatch`). Any test asserting "the reminder counter advances even when the send fails" now asserts the outbox row instead.
- [ ] **Step 5: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.document.*"`, `--tests "co.ara.onboarding.architecture.*"` (the new import is `notification → document`, allowed). PASS.
- [ ] **Step 6: Commit** — `fix(document): queue customer reminders in the outbox so a failed send is retried` (body: closes sub-project 6's lost-reminder item; plan amendment 3).

---

## Phase 2 — Recipients, preferences, the pipeline, the inbox

### Task 8: `RecipientAccess` — can *this other user* view *this record*?

The one genuinely new authorization capability (spec §7.2). Security tests first.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/authz/GrantLookup.java`
- Create: `backend/src/main/java/co/ara/onboarding/authz/RecipientAccess.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/AuthorizationPredicateBuilder.java`
- Create: `backend/src/test/java/co/ara/onboarding/security/RecipientAccessTest.java`

**Interfaces:**
- Produces:
  ```java
  @Component class GrantLookup { EffectivePermissions forInternalUser(UUID userId); }   // package-private
  // AuthorizationPredicateBuilder, package-private overload:
  <T> Specification<T> forPermission(String permissionKey, Class<T> entityType, AuthContext ctx, EffectivePermissions permissions);
  @Component public class RecipientAccess {
      @Transactional(propagation = MANDATORY)
      public boolean canView(UUID userId, String permissionKey, Class<?> entityType, UUID id);
  }
  ```

- [ ] **Step 1: Write the failing negative tests.** `RecipientAccessTest extends PostgresTestBase`, autowiring `TenantFixture fixture`, `JourneyFixtures journey`, `RoleService roles`, `RecipientAccess access`, `TaskService tasks` and the case/task builders the `task` tests use. Each test arranges as the fixture administrator and then evaluates **while running as a different user** (`fixture.runAsUser(t, adminId, ...)`), proving the answer is the recipient's, never the caller's:

```java
    @Test
    void anInScopeTeamViewerCanView() { … user in team T, role {CASE_VIEW: TEAM}; case owned by team T → canView(user, CASE_VIEW, Case.class, caseId) is true }

    @Test
    void anOutOfScopeUserCannot() { … same role, case owned by another team → false }

    @Test
    void theAnswerIsTheRecipientsNotTheCallers() {
        // The caller is the tenant administrator (CASE_VIEW at ALL); the recipient holds nothing.
        … fixture.runAs(t, () -> assertThat(access.canView(nobody, CASE_VIEW, Case.class, caseId)).isFalse());
    }

    @Test
    void aDeactivatedUserCannot() { … in-scope user, then ownerJdbc "update app_user set status = 'INACTIVE'" → false }

    @Test
    void aPortalUserCannotEvenForTheirOwnCustomersCase() { … fixture.createPortalUserForContact(...) → false }

    @Test
    void aTargetedDocumentIsInvisibleToAnAllScopedReaderOutsideItsAudience() {   // Review Focus 1
        // Recipient holds DOCUMENT_VIEW at ALL in department A; the document targets department B.
        // DocumentAudienceFilter narrows even ALL (CLAUDE.md, AudienceFilter invariant).
        … → canView(reader, DOCUMENT_VIEW, Document.class, docId) is false; a reader in department B → true
    }

    @Test
    void aUserFromAnotherTenantCannot() { … user of tenant A, case of tenant B, evaluated in tenant B → false }

    @Test
    void anUnknownIdIsFalseNotAnException() { … canView(user, CASE_VIEW, Case.class, Uuid7.generate()) is false }
```

Write each body in full. For the document case, copy `scoping.DocumentScopingTest.newDocument(UUID tenant, Case c, UUID uploadedBy)` (line ~461) into this test as a private helper and set `targetDepartmentId` on the result before saving; build the two departments with `fixture.createDepartment` and the users with `fixture.createUserInDepartment`. Use the `grant(userId, Map<String, Scope>)` helper shape from `TaskOrderingTest` (`roles.assignRole(userId, roles.createRole(name, "", grants))`) inside `runAs`. Run — FAIL (class missing).

- [ ] **Step 2: Extract `GrantLookup`.** Move the internal-user grant SQL from `AuthorizationService.effectivePermissions` (the `SELECT rg.permission_key ... JOIN app_user u ON u.id = ur.user_id AND u.status = 'ACTIVE' ...` block and the map building) into:

```java
package co.ara.onboarding.authz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The internal-user grant query, uncached, for any user id. AuthorizationService (the current
 * actor, memoised per request) and RecipientAccess (another user) share it so the two can never
 * disagree about what a grant means -- including that an inactive user holds nothing.
 */
@Component
class GrantLookup {
    private final JdbcTemplate jdbc;

    GrantLookup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    EffectivePermissions forInternalUser(UUID userId) {
        Map<String, Set<Scope>> byPermission = new HashMap<>();
        jdbc.query("""
            SELECT rg.permission_key AS k, rg.scope AS s
            FROM user_role ur
            JOIN app_user u ON u.id = ur.user_id AND u.status = 'ACTIVE'
            JOIN role r ON r.id = ur.role_id AND r.enabled = true
            JOIN role_grant rg ON rg.role_id = r.id
            WHERE ur.user_id = ?
            """,
            rs -> {
                byPermission.computeIfAbsent(rs.getString("k"), k -> EnumSet.noneOf(Scope.class))
                        .add(Scope.valueOf(rs.getString("s")));
            },
            userId);
        return new EffectivePermissions(byPermission.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))));
    }
}
```

Make `AuthorizationService` take `GrantLookup grants` (drop its `JdbcTemplate` parameter if nothing else in it uses it) and replace the inline block with `memo = grants.forInternalUser(userId);`. Run `--tests "co.ara.onboarding.security.*"` — the existing nine negative tests must stay green: this is a pure extraction.

- [ ] **Step 3: The predicate overload.** In `AuthorizationPredicateBuilder`:

```java
    public <T> Specification<T> forPermission(String permissionKey, Class<T> entityType) {
        return forPermission(permissionKey, entityType, contextProvider.current(), authorization.effectivePermissions());
    }

    /** The same predicate, for an explicit actor and grant set (RecipientAccess). Never widens: identical logic. */
    <T> Specification<T> forPermission(String permissionKey, Class<T> entityType, AuthContext ctx,
                                       EffectivePermissions permissions) {
        Set<Scope> scopes = permissions.scopesFor(permissionKey);
        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();
        Specification<T> scopePredicate = scopePredicate(scopes, entityType, ctx);
        return withAudience(scopePredicate, entityType, ctx, permissionKey);
    }
```

Note the public method's behaviour is unchanged: it still reads `effectivePermissions()` first and returns disjunction on no grant before touching the context. (Keep the original ordering exactly — if `effectivePermissions()` is empty the original never called `contextProvider.current()`; preserve that by checking `scopesFor` before resolving the context in the public method if your refactor changes the order.)

- [ ] **Step 4: `RecipientAccess`.**

```java
package co.ara.onboarding.authz;

import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/**
 * 6B spec 7.2: whether ANOTHER user -- a notification recipient, never the caller -- can view a
 * record. Builds that user's context and grants from scratch (no request cache, the ACTIVE join
 * included) and runs exactly the predicate a request would: record scope AND any audience filter.
 * Internal users only; a portal or system recipient is always false (portal notifications are
 * sub-project 7's). Infrastructure the notification pipeline depends on, like AuthorizedQuery.
 */
@Component
public class RecipientAccess {

    private final ActorDirectory actors;
    private final GrantLookup grants;
    private final AuthorizationPredicateBuilder predicates;
    private final EntityManager em;

    RecipientAccess(ActorDirectory actors, GrantLookup grants, AuthorizationPredicateBuilder predicates,
                    EntityManager em) {
        this.actors = actors;
        this.grants = grants;
        this.predicates = predicates;
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canView(UUID userId, String permissionKey, Class<?> entityType, UUID id) {
        if (userId == null || id == null) return false;
        Optional<AuthContext> ctx = actors.findActor(userId);
        if (ctx.isEmpty() || ctx.get().userType() != UserType.INTERNAL) return false;
        if (!ctx.get().tenantId().equals(TenantContext.getRequired())) return false;
        return exists(entityType, permissionKey, ctx.get(), grants.forInternalUser(userId), id);
    }

    private <T> boolean exists(Class<T> type, String permissionKey, AuthContext ctx, EffectivePermissions perms, UUID id) {
        Specification<T> spec = predicates.forPermission(permissionKey, type, ctx, perms);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<T> root = q.from(type);
        Predicate authorized = spec.toPredicate(root, q, cb);
        Predicate byId = cb.equal(root.get("id"), id);
        q.select(cb.count(root)).where(authorized == null ? byId : cb.and(authorized, byId));
        return em.createQuery(q).getSingleResult() > 0;
    }
}
```

(`UserType` lives in `co.ara.onboarding.platform` — confirm with `rg "enum UserType"` and adjust the import.)

- [ ] **Step 5: Run** `RecipientAccessTest` — PASS. Run `--tests "co.ara.onboarding.security.*"`, `--tests "co.ara.onboarding.authz.*"`, `--tests "co.ara.onboarding.scoping.*"`, `--tests "co.ara.onboarding.architecture.*"`.
- [ ] **Step 6: Commit** — `feat(authz): RecipientAccess answers whether another user can view a record` (body: spec §7.2; the grant SQL is now shared so the two paths cannot drift; negative tests written first; Review Focus 1).

---

### Task 9: Preferences schema and `PreferenceReader`

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__notification_preferences.sql`
- Create: `backend/src/main/java/co/ara/onboarding/notification/{EmailCadence,PreferenceReader}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/PreferenceReaderTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum EmailCadence { IMMEDIATE, DAILY, WEEKLY }
  @Component public class PreferenceReader {
      public record Resolved(boolean inApp, boolean email, EmailCadence cadence) {}
      @Transactional(propagation = MANDATORY) public Resolved resolve(UUID userId, NotificationType type);
      @Transactional(propagation = MANDATORY) public EmailCadence cadence(UUID userId);
  }
  ```

- [ ] **Step 1: Failing test.**

```java
class PreferenceReaderTest extends PostgresTestBase {
    @Autowired TenantFixture fixture;
    @Autowired PreferenceReader prefs;

    @Test
    void withNoRowsEveryTypeUsesItsCatalogueDefaultAndCadenceIsImmediate() {
        UUID t = fixture.createTenant("pref-defaults");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-defaults.test"));
        fixture.runAs(t, () -> {
            for (var e : NotificationCatalog.all()) {
                var r = prefs.resolve(u, e.type());
                assertThat(r.inApp()).as(e.type().name()).isEqualTo(e.inAppDefault());
                assertThat(r.email()).as(e.type().name()).isEqualTo(e.emailDefault());
                assertThat(r.cadence()).isEqualTo(EmailCadence.IMMEDIATE);
            }
        });
    }

    @Test
    void aRowOverridesTheDefaultAndSettingsSetTheCadence() {
        UUID t = fixture.createTenant("pref-override");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-override.test"));
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, true, now(), now())", t, u);
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'DAILY', now(), now())", t, u);
        fixture.runAs(t, () -> {
            var r = prefs.resolve(u, NotificationType.TASK_ASSIGNED);
            assertThat(r.inApp()).isFalse();
            assertThat(r.email()).isTrue();
            assertThat(r.cadence()).isEqualTo(EmailCadence.DAILY);
        });
    }

    @Test
    void escalationIsAlwaysOnAndImmediate() {
        UUID t = fixture.createTenant("pref-esc");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-esc.test"));
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'WEEKLY', now(), now())", t, u);
        fixture.runAs(t, () -> assertThat(prefs.resolve(u, NotificationType.ESCALATION))
                .isEqualTo(new PreferenceReader.Resolved(true, true, EmailCadence.IMMEDIATE)));
    }

    @Test
    void escalationCannotBeStoredAsAPreferenceRow() {
        UUID t = fixture.createTenant("pref-esc-row");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-esc-row.test"));
        assertThatThrownBy(() -> ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'ESCALATION', false, false, now(), now())", t, u))
                .hasMessageContaining("notification_preference_type_check");
    }
}
```

Run — FAIL.

- [ ] **Step 2: Migration.**

```sql
-- Sub-project 6B, spec §4.2. A preference row exists only where a user overrides a default;
-- ESCALATION can never be stored (QA Q10: it cannot be opted out of).
CREATE TABLE notification_preference (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    user_id         uuid        NOT NULL REFERENCES app_user(id),
    type            text        NOT NULL,
    in_app_enabled  boolean     NOT NULL,
    email_enabled   boolean     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT notification_preference_type_check CHECK (type IN (
        'TASK_ASSIGNED','TASK_OVERDUE','NEW_CUSTOMER','MILESTONE_COMPLETED','STAGE_CHANGED',
        'DOCUMENT_REQUESTED','DOCUMENT_UPLOADED','DOCUMENT_DECIDED','AGREEMENT_STATUS','NEW_COMMENT',
        'WORKFLOW_PUBLISHED','RISK_CHANGED','DEADLINE_APPROACHING','EXPIRY_RENEWAL')),
    CONSTRAINT notification_preference_once UNIQUE (tenant_id, user_id, type)
);
SELECT enable_tenant_rls('notification_preference');
GRANT SELECT, INSERT, UPDATE ON notification_preference TO onboarding_app;

CREATE TABLE notification_settings (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant(id),
    user_id         uuid        NOT NULL REFERENCES app_user(id),
    email_cadence   text        NOT NULL DEFAULT 'IMMEDIATE' CHECK (email_cadence IN ('IMMEDIATE','DAILY','WEEKLY')),
    last_digest_at  timestamptz NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT notification_settings_one_per_user UNIQUE (tenant_id, user_id)
);
SELECT enable_tenant_rls('notification_settings');
GRANT SELECT, INSERT, UPDATE ON notification_settings TO onboarding_app;
```

- [ ] **Step 3: `PreferenceReader`** (JdbcTemplate, RLS-bound; plan amendment 4).

```java
@Component
public class PreferenceReader {

    public record Resolved(boolean inApp, boolean email, EmailCadence cadence) {}

    private final JdbcTemplate jdbc;

    PreferenceReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Resolved resolve(UUID userId, NotificationType type) {
        if (type == NotificationType.ESCALATION) return new Resolved(true, true, EmailCadence.IMMEDIATE);
        var entry = NotificationCatalog.of(type);
        var rows = jdbc.query(
                "SELECT in_app_enabled, email_enabled FROM notification_preference WHERE user_id = ? AND type = ?",
                (rs, i) -> new boolean[]{rs.getBoolean(1), rs.getBoolean(2)}, userId, type.name());
        boolean inApp = rows.isEmpty() ? entry.inAppDefault() : rows.get(0)[0];
        boolean email = rows.isEmpty() ? entry.emailDefault() : rows.get(0)[1];
        return new Resolved(inApp, email, cadence(userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EmailCadence cadence(UUID userId) {
        var rows = jdbc.queryForList("SELECT email_cadence FROM notification_settings WHERE user_id = ?",
                String.class, userId);
        return rows.isEmpty() ? EmailCadence.IMMEDIATE : EmailCadence.valueOf(rows.get(0));
    }
}
```

- [ ] **Step 4: Run** `PreferenceReaderTest` and `RlsCoverageTest` — PASS.
- [ ] **Step 5: Commit** — `feat(notification): per-user preferences and email cadence`.

---

### Task 10: Subject facts, links, and the notification pipeline

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/{SubjectFacts,Links,Text,NotificationPipeline}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/NotificationWriter.java` (`write`, `writeMarker`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/NotificationPipelineTest.java`

**Interfaces:**
- Consumes: `RecipientAccess` (8), `PreferenceReader` (9), `NotificationWriter`, `OutboxWriter` (5, 6).
- Produces:
  ```java
  @Component public class SubjectFacts {           // RLS-bound SQL; plan amendment 4
      public record CaseFacts(UUID id, String name, UUID customerId, String customerName, UUID ownerUserId,
                              UUID templateId, UUID versionId) {}
      public String tenantSlug();
      public CaseFacts caseFacts(UUID caseId);
      public Set<UUID> caseAudience(UUID caseId);            // owner + ACTIVE case_participant users
      public Optional<String> activeInternalEmail(UUID userId);
      public String userName(UUID userId);                   // full_name, or "Someone"
  }
  public final class Links {
      public static String caseLink(String slug, UUID customerId, UUID caseId);   // /t/{slug}/customers/{c}/cases/{id}
      public static String customerLink(String slug, UUID customerId);           // /t/{slug}/customers/{c}
  }
  public final class Text { public static String clip(String s, int max); }       // null-safe, adds "…"
  @Component public class NotificationPipeline {
      public record Draft(NotificationType type, String subjectType, UUID subjectId, UUID caseId, String title,
                          String body, String linkPath, Tone tone, String dedupeKey) {}
      public record Visibility(String permissionKey, Class<?> entityType, UUID id) {}
      @Transactional(propagation = MANDATORY) public int deliver(Draft draft, Collection<UUID> candidates, UUID actorId, Visibility visibility);
      @Transactional(propagation = MANDATORY) public void consume(Draft draft, UUID recipientUserId);
  }
  // NotificationWriter additions (package-private):
  Optional<UUID> write(NotificationPipeline.Draft d, UUID recipient, boolean inApp, EmailState state, String email);
  void writeMarker(NotificationPipeline.Draft d, UUID recipient);
  ```

- [ ] **Step 1: Confirm column names** the facts SQL uses: `\d onboarding_case` (`name`, `customer_id`, `owner_user_id`, `template_id`, `version_id`), `\d customer` (display name column — expected `display_name`), `\d case_participant` (`case_id`, `user_id`, `status`), `\d app_user` (`full_name`, `email`, `status`, `user_type`). Use the real names.

- [ ] **Step 2: Failing `NotificationPipelineTest`.** It drives the pipeline directly with a `TASK_ASSIGNED` draft about a real task, as the actor, and asserts on rows.

```java
class NotificationPipelineTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationPipeline pipeline;
    @Autowired NotificationTestSupport support;
    @Autowired RoleService roles;
    @Autowired SlaTestSupport sla;      // co.ara.onboarding.sla test support: a real case
    @Autowired TaskService tasks;

    record World(UUID tenant, UUID actor, UUID viewer, UUID blind, UUID caseId, UUID taskId) {}

    /** viewer and actor hold TASK_VIEW at ALL; blind holds nothing. */
    private World world(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "actor@" + slug + ".test"));
        UUID viewer = fixture.runAsReturning(t, () -> fixture.createUser(t, "viewer@" + slug + ".test"));
        UUID blind = fixture.runAsReturning(t, () -> fixture.createUser(t, "blind@" + slug + ".test"));
        for (UUID u : List.of(actor, viewer)) {
            fixture.runAs(t, () -> roles.assignRole(u, roles.createRole("r-" + u, "",
                    Map.of(PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL))));
        }
        UUID taskId = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        return new World(t, actor, viewer, blind, caseId, taskId);
    }

    private NotificationPipeline.Draft draft(World w, String dedupe) {
        return new NotificationPipeline.Draft(NotificationType.TASK_ASSIGNED, "task", w.taskId(), w.caseId(),
                "Task assigned to you: Chase", "body", "/t/" + "x" + "/path", Tone.INFO, dedupe);
    }

    private int deliver(World w, List<UUID> to, String dedupe) {
        var written = new int[1];
        fixture.runAsUser(w.tenant(), w.actor(), () -> written[0] = pipeline.deliver(draft(w, dedupe), to, w.actor(),
                new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId())));
        return written[0];
    }

    @Test
    void aVisibleRecipientGetsOneRowAndOneQueuedEmail() {
        var w = world("pipe-ok");
        assertThat(deliver(w, List.of(w.viewer()), null)).isEqualTo(1);
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("recipient_user_id")).isEqualTo(w.viewer());
        assertThat(n.get("in_app")).isEqualTo(true);
        assertThat(n.get("email_state")).isEqualTo("QUEUED");
        assertThat(support.outbox(w.tenant())).hasSize(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_event where tenant_id = ? and action = 'notification.sent'", Long.class, w.tenant())).isEqualTo(1L);
    }

    @Test
    void theActorIsNeverNotified() {   // Review Focus 2
        var w = world("pipe-actor");
        assertThat(deliver(w, List.of(w.actor()), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
    }

    @Test
    void aRecipientWhoCannotViewTheSubjectGetsNothing() {
        var w = world("pipe-blind");
        assertThat(deliver(w, List.of(w.blind()), null)).isZero();
    }

    @Test
    void inactiveAndPortalRecipientsGetNothing() {
        var w = world("pipe-inactive");
        ownerJdbc().update("update app_user set status = 'INACTIVE' where id = ?", w.viewer());
        UUID customerId = ownerJdbc().queryForObject("select customer_id from onboarding_case where id = ?", UUID.class, w.caseId());
        UUID portal = fixture.createPortalUserForContact(w.tenant(), customerId, "portal@pipe-inactive.test");
        assertThat(deliver(w, List.of(w.viewer(), portal), null)).isZero();
    }

    @Test
    void preferencesRouteEachChannel() {
        var w = world("pipe-prefs");
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, true, now(), now())", w.tenant(), w.viewer());
        deliver(w, List.of(w.viewer()), null);
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("in_app")).isEqualTo(false);
        assertThat(n.get("email_state")).isEqualTo("QUEUED");
    }

    @Test
    void bothChannelsOffWritesNothing() {
        var w = world("pipe-off");
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, false, now(), now())", w.tenant(), w.viewer());
        assertThat(deliver(w, List.of(w.viewer()), null)).isZero();
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void aDigestUserIsMarkedPendingAndNotQueued() {
        var w = world("pipe-digest");
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'DAILY', now(), now())", w.tenant(), w.viewer());
        deliver(w, List.of(w.viewer()), null);
        assertThat(support.notifications(w.tenant()).get(0).get("email_state")).isEqualTo("DIGEST_PENDING");
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void aDedupeKeyDeliversOnce() {
        var w = world("pipe-dedupe");
        assertThat(deliver(w, List.of(w.viewer()), "K1")).isEqualTo(1);
        assertThat(deliver(w, List.of(w.viewer()), "K1")).isZero();
        assertThat(support.outbox(w.tenant())).hasSize(1);
    }

    @Test
    void aRolledBackActionLeavesNoNotificationAndNoOutboxRow() {   // invariant 3
        var w = world("pipe-rollback");
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.actor(), () -> {
            pipeline.deliver(draft(w, null), List.of(w.viewer()), w.actor(),
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, w.taskId()));
            throw new IllegalStateException("the action failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(support.outbox(w.tenant())).isEmpty();
    }

    @Test
    void aConsumedMarkerIsInvisibleUnsentAndUnaudited() {
        var w = world("pipe-marker");
        fixture.runAs(w.tenant(), () -> pipeline.consume(draft(w, "M1"), w.viewer()));
        var n = support.notifications(w.tenant()).get(0);
        assertThat(n.get("in_app")).isEqualTo(false);
        assertThat(n.get("email_state")).isEqualTo("NONE");
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_event where tenant_id = ? and action = 'notification.sent'", Long.class, w.tenant())).isZero();
        assertThat(deliver(w, List.of(w.viewer()), "M1")).as("the marker consumed the key").isZero();
    }
}
```

Write `world(...)` and the portal user construction in full. Run — FAIL.

- [ ] **Step 3: `Text` and `Links`.**

```java
public final class Text {
    private Text() {}
    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}

public final class Links {
    private Links() {}
    public static String caseLink(String slug, UUID customerId, UUID caseId) {
        return "/t/" + slug + "/customers/" + customerId + "/cases/" + caseId;
    }
    public static String customerLink(String slug, UUID customerId) {
        return "/t/" + slug + "/customers/" + customerId;
    }
}
```

- [ ] **Step 4: `SubjectFacts`.** Every method runs RLS-bound SQL in the caller's transaction (`@Transactional(propagation = MANDATORY)` on each public method). The class javadoc states plan amendment 4 verbatim: *"Never returned to a caller. Every notification written from these facts is gated per recipient by RecipientAccess."*

```java
@Component
public class SubjectFacts {

    public record CaseFacts(UUID id, String name, UUID customerId, String customerName, UUID ownerUserId,
                            UUID templateId, UUID versionId) {}

    private final JdbcTemplate jdbc;

    SubjectFacts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public String tenantSlug() {
        return jdbc.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, TenantContext.getRequired());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseFacts caseFacts(UUID caseId) {
        return jdbc.queryForObject("""
                SELECT c.id, c.name, c.customer_id, cu.display_name, c.owner_user_id, c.template_id, c.version_id
                  FROM onboarding_case c JOIN customer cu ON cu.id = c.customer_id WHERE c.id = ?""",
                (rs, i) -> new CaseFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class)), caseId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> caseAudience(UUID caseId) {
        Set<UUID> audience = new LinkedHashSet<>();
        UUID owner = jdbc.queryForObject("SELECT owner_user_id FROM onboarding_case WHERE id = ?", UUID.class, caseId);
        if (owner != null) audience.add(owner);
        audience.addAll(jdbc.queryForList(
                "SELECT user_id FROM case_participant WHERE case_id = ? AND status = 'ACTIVE'", UUID.class, caseId));
        return audience;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> activeInternalEmail(UUID userId) {
        return jdbc.queryForList("SELECT email FROM app_user WHERE id = ? AND status = 'ACTIVE' AND user_type = 'INTERNAL'",
                String.class, userId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String userName(UUID userId) {
        if (userId == null) return "Someone";
        return jdbc.queryForList("SELECT full_name FROM app_user WHERE id = ?", String.class, userId)
                .stream().findFirst().orElse("Someone");
    }
}
```

Later tasks add methods to this class; each adds its own SQL and its own javadoc line.

- [ ] **Step 5: `NotificationWriter.write` / `writeMarker`.**

```java
    /** Inserts one row; with a dedupe key, a conflict inserts nothing and returns empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> write(NotificationPipeline.Draft d, UUID recipient, boolean inApp, EmailState state, String email) {
        Optional<UUID> id = insert(d, recipient, inApp, state);
        if (id.isEmpty()) return id;
        if (state == EmailState.QUEUED) {
            outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, email, recipient, null, id.get(),
                    null, d.title(), d.body(), d.linkPath()));
        }
        audit.record(AuditActions.NOTIFICATION_SENT, "notification", id.get(), d.type().name() + " notification",
                Map.of("type", d.type().name(), "recipientUserId", recipient.toString(),
                        "subjectType", d.subjectType(), "subjectId", d.subjectId().toString()));
        return id;
    }

    /** Spec 6.2: a consumed lead time -- never in the inbox, never emailed, never audited. */
    @Transactional(propagation = Propagation.MANDATORY)
    void writeMarker(NotificationPipeline.Draft d, UUID recipient) {
        insert(d, recipient, false, EmailState.NONE);
    }

    private Optional<UUID> insert(NotificationPipeline.Draft d, UUID recipient, boolean inApp, EmailState state) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        return jdbc.queryForList("""
                INSERT INTO notification (id, tenant_id, recipient_user_id, type, title, body, link_path, case_id,
                    subject_type, subject_id, in_app, email_state, tone, dedupe_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_once_per_dedupe_key DO NOTHING
                RETURNING id""", UUID.class,
                Uuid7.generate(), TenantContext.getRequired(), recipient, d.type().name(), Text.clip(d.title(), 120),
                Text.clip(d.body(), 500), d.linkPath(), d.caseId(), d.subjectType(), d.subjectId(), inApp,
                state.name(), d.tone().name(), d.dedupeKey(), now, now).stream().findFirst();
    }
```

- [ ] **Step 6: `NotificationPipeline`.**

```java
/**
 * 6B spec 5.3, steps 1-6, for every type but ESCALATION. Runs inside the producer's (or the
 * sweep's) transaction, so a notification commits or rolls back with its cause (invariant 3).
 */
@Component
public class NotificationPipeline {

    public record Draft(NotificationType type, String subjectType, UUID subjectId, UUID caseId, String title,
                        String body, String linkPath, Tone tone, String dedupeKey) {}
    public record Visibility(String permissionKey, Class<?> entityType, UUID id) {}

    private final RecipientAccess access;
    private final PreferenceReader prefs;
    private final NotificationWriter writer;
    private final SubjectFacts facts;

    NotificationPipeline(RecipientAccess access, PreferenceReader prefs, NotificationWriter writer, SubjectFacts facts) {
        this.access = access;
        this.prefs = prefs;
        this.writer = writer;
        this.facts = facts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int deliver(Draft draft, Collection<UUID> candidates, UUID actorId, Visibility visibility) {
        if (draft.type() == NotificationType.ESCALATION) {
            throw new IllegalArgumentException("Escalations are written by NotificationWriter.escalation");
        }
        int written = 0;
        for (UUID recipient : new LinkedHashSet<>(candidates)) {
            if (recipient == null || recipient.equals(actorId)) continue;                       // step 2
            Optional<String> email = facts.activeInternalEmail(recipient);
            if (email.isEmpty()) continue;                                                      // step 2
            if (!access.canView(recipient, visibility.permissionKey(), visibility.entityType(), visibility.id())) continue; // step 3
            var pref = prefs.resolve(recipient, draft.type());                                  // step 4
            if (!pref.inApp() && !pref.email()) continue;
            EmailState state = !pref.email() ? EmailState.NONE
                    : pref.cadence() == EmailCadence.IMMEDIATE ? EmailState.QUEUED : EmailState.DIGEST_PENDING;
            if (writer.write(draft, recipient, pref.inApp(), state, email.get()).isPresent()) written++; // step 6
        }
        return written;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Draft draft, UUID recipientUserId) {
        if (draft.dedupeKey() == null) throw new IllegalArgumentException("A marker needs a dedupe key");
        writer.writeMarker(draft, recipientUserId);
    }
}
```

- [ ] **Step 7: Run** `NotificationPipelineTest`, then `--tests "co.ara.onboarding.notification.*"` and `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 8: Commit** — `feat(notification): the delivery pipeline -- recipients, visibility, preferences` (body: spec §5.3, plan amendment 4, invariants 3-5).

---

### Task 11: The inbox API

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/{InboxService,InboxController,NotificationView,InboxPage}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/NotificationRepository.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/InboxApiTest.java`

**Interfaces:**
- Produces:
  ```java
  public record NotificationView(UUID id, NotificationType type, String title, String body, String linkPath,
                                 Tone tone, boolean read, Instant createdAt) {}
  public record InboxPage(List<NotificationView> items, long unreadCount, String nextCursor) {}
  @Service public class InboxService {     // ungated: the caller's own rows only (MeService's basis)
      public InboxPage list(String cursor, int limit);
      public long unreadCount();
      public void markRead(UUID id);
      public int markAllRead();
  }
  // HTTP: GET /api/t/{slug}/notifications?cursor=&limit=30 · GET /notifications/unread-count ({"unreadCount":n})
  //       POST /notifications/{id}/read (204) · POST /notifications/read-all ({"marked":n})
  ```

- [ ] **Step 1: Failing `InboxApiTest extends SecurityTestBase`.** Seed rows with an owner-connection helper:

```java
    private UUID row(UUID tenant, UUID recipient, String title, boolean inApp, boolean read) {
        UUID id = co.ara.onboarding.platform.Uuid7.generate();
        ownerJdbc().update("""
            insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path, subject_type,
                subject_id, in_app, email_state, tone, read_at, created_at, updated_at)
            values (?, ?, ?, 'TASK_ASSIGNED', ?, 'b', '/t/x', 'task', gen_random_uuid(), ?, 'NONE', 'INFO', ?, now(), now())""",
            id, tenant, recipient, title, inApp, read ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return id;
    }
```

  Tests (write each in full, `mvc.perform(as(get("/api/t/{slug}/notifications"), user))…`):
  - `listsMyInAppRowsNewestFirstWithACursor` — five rows for me (created in order), one `in_app=false`, two for another user; `limit=2` returns my two newest, `nextCursor` set; following the cursor returns the next two; the last page has `nextCursor` null; never another user's row, never the `in_app=false` row.
  - `unreadCountCountsOnlyMyUnreadInAppRows`.
  - `markReadIsIdempotentAndMine` — 204 twice; `read` true after.
  - `anotherUsersNotificationIs404` — POST read on someone else's id → 404, their row unchanged.
  - `aCrossTenantIdIs404`.
  - `markAllReadClearsTheBadge` — returns `{"marked":n}`, then `unread-count` is 0.
  - `aLimitOutsideOneToHundredIs400`.
  - `aPortalUserSeesAnEmptyInbox` — 200, empty items, count 0.

  Run — FAIL.

- [ ] **Step 2: Repository additions.**

```java
public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :at, n.updatedAt = :at "
            + "where n.recipientUserId = :recipient and n.inApp = true and n.readAt is null")
    int markAllRead(@Param("recipient") UUID recipient, @Param("at") Instant at);
}
```

  Delete the obsolete `findByTypeAndEmailedAtIsNull` finder (nothing calls it after Task 6; `rg findByTypeAndEmailedAtIsNull` to confirm). If `BaseEntity.updatedAt` has no JPQL-writable mapping name `updatedAt`, use the field name `rg` shows.

- [ ] **Step 3: `InboxService`.**

```java
/**
 * The caller's own inbox (6B spec 7.3). NOT permission-gated, on MeService's stated basis: it only
 * ever touches rows whose recipient_user_id is the caller, and a permission every role must hold
 * is no permission. Every id it accepts is looked up with recipient = caller in the predicate, so
 * another user's id and a cross-tenant id are both 404.
 */
@Service
public class InboxService {

    private final NotificationRepository notifications;
    private final AuthContextProvider contexts;
    private final Clock clock;

    public InboxService(NotificationRepository notifications, AuthContextProvider contexts, Clock clock) { … }

    private UUID me() { return contexts.principal().userId(); }

    private Specification<Notification> mine() {
        UUID me = me();
        return (r, q, cb) -> cb.and(cb.equal(r.get("recipientUserId"), me), cb.isTrue(r.get("inApp")));
    }

    @Transactional(readOnly = true)
    public InboxPage list(String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        Specification<Notification> spec = mine();
        if (cursor != null && !cursor.isBlank()) {
            UUID after = parseCursor(cursor);
            spec = spec.and((r, q, cb) -> cb.lessThan(r.get("id"), after));
        }
        var page = notifications.findAll(spec, PageRequest.of(0, limit + 1, Sort.by(Sort.Direction.DESC, "id")));
        var rows = page.getContent();
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(InboxService::view).toList();
        String next = more ? items.get(items.size() - 1).id().toString() : null;
        return new InboxPage(items, unreadCount(), next);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.count(mine().and((r, q, cb) -> cb.isNull(r.get("readAt"))));
    }

    @Transactional
    public void markRead(UUID id) {
        Notification n = notifications.findOne(mine().and((r, q, cb) -> cb.equal(r.get("id"), id)))
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        if (n.getReadAt() == null) n.setReadAt(Instant.now(clock));
    }

    @Transactional
    public int markAllRead() { return notifications.markAllRead(me(), Instant.now(clock)); }

    private static UUID parseCursor(String cursor) {
        try { return UUID.fromString(cursor); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Malformed cursor"); }
    }

    static NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLinkPath(), n.getTone(),
                n.getReadAt() != null, n.getCreatedAt());
    }
}
```

  (UUIDv7 ids sort by creation time in Postgres's `uuid` ordering, which is why the cursor is the id.)

- [ ] **Step 4: `InboxController`.**

```java
@RestController
@RequestMapping("/api/t/{tenantSlug}/notifications")
public class InboxController {
    private final InboxService inbox;
    public InboxController(InboxService inbox) { this.inbox = inbox; }

    @GetMapping
    public InboxPage list(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        return inbox.list(cursor, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() { return Map.of("unreadCount", inbox.unreadCount()); }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) { inbox.markRead(id); }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() { return Map.of("marked", inbox.markAllRead()); }
}
```

- [ ] **Step 5: Coverage exclusions.** In `AuthorizationCoverageTest`, add `.and().areNotDeclaredIn(InboxService.class)` to `serviceMethodsAreGated` and `InboxService` beside `MeService` in the finder rule's class exclusions, each with the comment `// 6B spec 7.3: the caller's own rows only (recipient = caller in every predicate) -- MeService's basis.` Extend the class javadoc's "Returns only the caller's own record" category to name `InboxService`.
- [ ] **Step 6: Run** `InboxApiTest`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 7: Commit** — `feat(notification): the inbox API -- list, unread count, mark read`.

---

### Task 12: The preferences API

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/{NotificationPreferenceService,NotificationPreferenceController,NotificationRuleException,NotificationExceptionHandler}.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/{PreferencesView,TypePreferenceView,UpdatePreferencesRequest,TypePreferenceRequest}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/AuthorizationCoverageTest.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/PreferencesApiTest.java`

**Interfaces:**
- Produces:
  ```java
  public record TypePreferenceView(NotificationType type, String label, boolean inApp, boolean email, boolean locked) {}
  public record PreferencesView(EmailCadence emailCadence, List<TypePreferenceView> types) {}
  public record TypePreferenceRequest(@NotNull NotificationType type, @NotNull Boolean inApp, @NotNull Boolean email) {}
  public record UpdatePreferencesRequest(@NotNull EmailCadence emailCadence, @NotNull @Valid List<TypePreferenceRequest> types) {}
  @Service public class NotificationPreferenceService { public PreferencesView get(); public PreferencesView replace(UpdatePreferencesRequest r); }
  public class NotificationRuleException extends RuntimeException { public NotificationRuleException(String message); }
  // HTTP: GET/PUT /api/t/{slug}/notifications/preferences
  AuditActions.NOTIFICATION_PREFERENCES_CHANGED = of("notification.preferences_changed", false)
  ```

- [ ] **Step 1: Failing `PreferencesApiTest extends SecurityTestBase`.** Tests, each in full:
  - `getListsEveryTypeWithDefaultsAndEscalationLocked` — 15 entries in enum order; escalation `locked: true, inApp: true, email: true`; cadence `IMMEDIATE`.
  - `putReplacesEveryTypeAndTheCadence` — PUT all 14 opt-out types with `TASK_ASSIGNED` `(false, true)` and cadence `DAILY`; GET reflects it; a `notification.preferences_changed` audit row exists with `timeline_visible = false`.
  - `turningEscalationOffIs422` — include `{type: ESCALATION, inApp: false, email: true}` → 422, detail mentions "required by policy".
  - `escalationListedOnWithBothTrueIsAccepted`.
  - `omittingATypeIs400NotASilentReset` (plan amendment 11).
  - `aDuplicateTypeIs400`.
  - `aMissingBooleanIs400` — `{type: TASK_ASSIGNED, inApp: null, email: true}`.
  - `preferencesAreMineAlone` — user A's PUT does not change user B's GET.
  - `theRequestAndViewStayAligned` — reflection: every record component name of `UpdatePreferencesRequest` exists on `PreferencesView`, and every component of `TypePreferenceRequest` exists on `TypePreferenceView` (the `SignatoryRequestViewAlignmentTest` shape).

  Run — FAIL.

- [ ] **Step 2: The exception and its handler.**

```java
/** A well-formed request that breaks a rule the 6B spec answers with 422. */
public class NotificationRuleException extends RuntimeException {
    public NotificationRuleException(String message) { super(message); }
}

/** notification's own exception mapping; platform may not name a domain type. */
@RestControllerAdvice
public class NotificationExceptionHandler {
    @ExceptionHandler(NotificationRuleException.class)
    ProblemDetail unprocessable(NotificationRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
```

- [ ] **Step 3: The service.** Ungated, on the same basis as `InboxService`; it writes with RLS-bound upserts.

```java
@Service
public class NotificationPreferenceService {

    private final JdbcTemplate jdbc;
    private final AuthContextProvider contexts;
    private final PreferenceReader prefs;
    private final AuditRecorder audit;
    private final Clock clock;
    // constructor …

    @Transactional
    public PreferencesView get() {
        UUID me = contexts.principal().userId();
        List<TypePreferenceView> types = NotificationCatalog.all().stream().map(e -> {
            var r = prefs.resolve(me, e.type());
            return new TypePreferenceView(e.type(), e.label(), r.inApp(), r.email(), e.locked());
        }).toList();
        return new PreferencesView(prefs.cadence(me), types);
    }

    @Transactional
    public PreferencesView replace(UpdatePreferencesRequest r) {
        UUID me = contexts.principal().userId();
        Map<NotificationType, TypePreferenceRequest> byType = new EnumMap<>(NotificationType.class);
        for (TypePreferenceRequest t : r.types()) {
            if (byType.put(t.type(), t) != null) throw new IllegalArgumentException("Each notification type may be listed once");
        }
        TypePreferenceRequest esc = byType.remove(NotificationType.ESCALATION);
        if (esc != null && !(esc.inApp() && esc.email())) {
            throw new NotificationRuleException(
                    "Escalation to your manager is required by policy and cannot be turned off");
        }
        if (!byType.keySet().equals(EnumSet.copyOf(NotificationCatalog.optOut().stream().map(NotificationCatalog.Entry::type).toList()))) {
            throw new IllegalArgumentException("Every notification type must be listed");
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        UUID tenant = TenantContext.getRequired();
        for (TypePreferenceRequest t : byType.values()) {
            jdbc.update("""
                INSERT INTO notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_preference_once
                DO UPDATE SET in_app_enabled = EXCLUDED.in_app_enabled, email_enabled = EXCLUDED.email_enabled, updated_at = EXCLUDED.updated_at""",
                Uuid7.generate(), tenant, me, t.type().name(), t.inApp(), t.email(), now, now);
        }
        jdbc.update("""
            INSERT INTO notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT notification_settings_one_per_user
            DO UPDATE SET email_cadence = EXCLUDED.email_cadence, updated_at = EXCLUDED.updated_at""",
            Uuid7.generate(), tenant, me, r.emailCadence().name(), now, now);
        audit.record(AuditActions.NOTIFICATION_PREFERENCES_CHANGED, "app_user", me,
                "Changed notification preferences", Map.of("emailCadence", r.emailCadence().name()));
        return get();
    }
}
```

  Controller at `/api/t/{tenantSlug}/notifications/preferences`: `@GetMapping get()` and `@PutMapping replace(@Valid @RequestBody UpdatePreferencesRequest)`. Make sure the `/{id}/read` mapping in `InboxController` cannot capture `preferences` (it is a `POST` with a `/read` suffix, so it does not — but run the GET test to prove it).

  Add `NOTIFICATION_PREFERENCES_CHANGED` to `AuditActions` under the 6B block; add the two coverage exclusions for `NotificationPreferenceService` exactly as for `InboxService` in Task 11.

- [ ] **Step 4: Run** `PreferencesApiTest`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 5: Commit** — `feat(notification): preferences API -- per-type channels and an email cadence` (body: spec §8, plan amendment 11; escalation locked by 422).

---

## Phase 3 — Event producers

Every task in this phase has the same shape: an event record in the producer's package, published with `ApplicationEventPublisher.publishEvent` **after** the producer's own `audit.record(...)` (cause before effect), a listener method in `notification`, any `SubjectFacts` additions it needs, and tests that drive the producer's real service method. Each task also adds one case to `NotificationOrderingTest` (created in Task 13).

A shared test helper is created in Task 13 and reused: `NotificationTestSupport.rowsFor(UUID tenant, UUID recipient)` (owner-connection list of that recipient's rows) and `NotificationTestSupport.grant(UUID tenant, UUID userId, Map<String, Scope> grants)` (runs `roles.assignRole(userId, roles.createRole("r-" + Uuid7.generate(), "", grants))` inside `fixture.runAs`).

### Task 13: Task assigned — and the ordering test

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/task/TaskAssigned.java`
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskService.java` (constructor; `create`, `update`)
- Modify: `backend/src/main/java/co/ara/onboarding/task/TaskInstantiation.java` (constructor; after its `TASK_CREATED` record)
- Create: `backend/src/main/java/co/ara/onboarding/notification/TaskNotifications.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/SubjectFacts.java` (`task`)
- Modify: `backend/src/test/java/co/ara/onboarding/notification/NotificationTestSupport.java` (`rowsFor`, `grant`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/{TaskNotificationTest,NotificationOrderingTest}.java`

**Interfaces:**
- Produces:
  ```java
  public record TaskAssigned(UUID taskId, UUID caseId, UUID assigneeId, UUID actorId) {}                 // task
  public record TaskFacts(UUID id, String title, UUID caseId, UUID assigneeId, LocalDate dueDate) {}      // SubjectFacts nested
  public TaskFacts task(UUID taskId);                                                                    // SubjectFacts
  @Component public class TaskNotifications { @EventListener public void on(TaskAssigned e); }
  ```

- [ ] **Step 1: Failing `TaskNotificationTest`.** Build a case with `SlaTestSupport.caseWithSla(t, 5, true)` inside `runAs`, users with `fixture.createUser`, grants via `support.grant`, and drive `TaskService.create`/`update`/`changeStatus` as a specific actor with `fixture.runAsUser`. Tests:
  - `assigningATaskToSomeoneElseNotifiesThem` — actor (TASK_MANAGE + TASK_VIEW + CASE_VIEW + WORKFLOW_VIEW at ALL) creates a task assigned to `bob` (TASK_VIEW at ALL); bob has exactly one `TASK_ASSIGNED` row, `subject_type = 'task'`, `link_path` is the case link, `tone = 'INFO'`, title starts `Task assigned to you:`.
  - `selfAssignmentNotifiesNobody` (Review Focus 2) — the actor assigns to themselves → zero rows.
  - `anAssigneeWhoCannotSeeTheTaskIsNotNotified` — assignee holds nothing → zero rows (the task is still created).
  - `reassigningNotifiesOnlyTheNewAssignee` — update from bob to carol → carol one row, bob still one (from create).
  - `unassigningNotifiesNobody` — update to `null` assignee → no new rows.
  - `cancellingATaskNotifiesNobody` — `changeStatus(CANCELLED)` → no new rows (sub-project 3: a cancelled task must not fire assignee notifications).
  - `anInstantiatedTaskNotifiesTheMilestoneOwner` — a workflow with a `TASK` requirement (`WorkflowFixtures.task("Prepare")`) on a milestone whose owner is set before the task is instantiated (follow the shape `TaskInstantiation`'s own tests use) → the owner gets one row unless they are the actor.

  Run — FAIL.

- [ ] **Step 2: The event and the publishes.** `TaskAssigned` as **Interfaces**. Add `ApplicationEventPublisher events` as the last constructor parameter of `TaskService` and of `TaskInstantiation` (`TaskInstantiation` also gains `AuthContextProvider contextProvider` for the actor). Publish:

```java
// TaskService.create, after the TASK_CREATED audit.record, before return:
        if (t.getAssigneeId() != null) {
            events.publishEvent(new TaskAssigned(t.getId(), c.getId(), t.getAssigneeId(),
                    contextProvider.principal().userId()));
        }

// TaskService.update, inside `if (!Objects.equals(previousAssigneeId, newAssigneeId))`, after TASK_ASSIGNED:
            if (newAssigneeId != null) {
                events.publishEvent(new TaskAssigned(t.getId(), c.getId(), newAssigneeId,
                        contextProvider.principal().userId()));
            }

// TaskInstantiation, after its TASK_CREATED audit.record:
            if (t.getAssigneeId() != null) {
                events.publishEvent(new TaskAssigned(t.getId(), t.getCaseId(), t.getAssigneeId(),
                        contextProvider.current().userId()));
            }
```

  `changeStatus` publishes nothing.

- [ ] **Step 3: Facts and listener.**

```java
    // SubjectFacts
    public record TaskFacts(UUID id, String title, UUID caseId, UUID assigneeId, LocalDate dueDate) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public TaskFacts task(UUID taskId) {
        return jdbc.queryForObject("SELECT id, title, case_id, assignee_id, due_date FROM task WHERE id = ?",
                (rs, i) -> new TaskFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class)), taskId);
    }
```

```java
package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskAssigned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;

/** TASK_ASSIGNED (spec 5.2) and, from Task 14, NEW_COMMENT. */
@Component
public class TaskNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    TaskNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(TaskAssigned e) {
        var task = facts.task(e.taskId());
        var kase = facts.caseFacts(e.caseId());
        String due = task.dueDate() == null ? "" : ", due " + task.dueDate();
        var draft = new NotificationPipeline.Draft(NotificationType.TASK_ASSIGNED, "task", task.id(), kase.id(),
                "Task assigned to you: " + Text.clip(task.title(), 80),
                "\"" + Text.clip(task.title(), 120) + "\" on " + kase.name() + " (" + kase.customerName() + ")" + due + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, List.of(e.assigneeId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, task.id()));
    }
}
```

- [ ] **Step 4: `NotificationOrderingTest`** (plan amendment 12). One test per cause, added task by task; this task adds the first:

```java
class NotificationOrderingTest extends PostgresTestBase {
    // autowire TenantFixture, SlaTestSupport, TaskService, NotificationTestSupport

    /** Cause before effect: the cause's audit row is not later than the notification it led to. */
    private void assertCauseFirst(UUID tenant, String cause) {
        var causeAt = ownerJdbc().queryForObject(
                "select max(occurred_at) from audit_event where tenant_id = ? and action = ?",
                java.sql.Timestamp.class, tenant, cause);
        var effectAt = ownerJdbc().queryForObject(
                "select min(occurred_at) from audit_event where tenant_id = ? and action = 'notification.sent'",
                java.sql.Timestamp.class, tenant);
        assertThat(causeAt).isNotNull();
        assertThat(effectAt).isNotNull();
        assertThat(causeAt).isBeforeOrEqualTo(effectAt);
    }

    @Test
    void reassigningATaskIsRecordedBeforeItsNotification() {
        // arrange as in TaskNotificationTest.reassigningNotifiesOnlyTheNewAssignee, in a fresh tenant
        …
        assertCauseFirst(tenant, "task.assigned");
    }
}
```

- [ ] **Step 5: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.task.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 6: Commit** — `feat(task): notify a task's new assignee` (body: spec §5.2, plan amendment 1 — create and instantiation publish too; cancel never does).

---

### Task 14: New comment

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/task/CommentAdded.java`
- Modify: `backend/src/main/java/co/ara/onboarding/task/CommentService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{TaskNotifications,SubjectFacts}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/CommentNotificationTest.java`
- Modify: `backend/src/test/java/co/ara/onboarding/notification/NotificationOrderingTest.java`

**Interfaces:**
- Produces:
  ```java
  public record CommentAdded(UUID commentId, CommentResourceType resourceType, UUID resourceId, UUID caseId, UUID authorId) {}  // task
  public List<UUID> earlierCommenters(UUID commentId);      // SubjectFacts: distinct authors on the same thread, before this comment
  public String commentBody(UUID commentId);                 // SubjectFacts
  ```

- [ ] **Step 1: Confirm the stored value of `comment.resource_type`** — `docker exec onboarding-db psql -U postgres -d onboarding -c "select distinct resource_type from comment"` on a database with comments, or read `CommentResourceTypeConverter`. The SQL below compares within the same table, so it is independent of the stored spelling.

- [ ] **Step 2: Failing `CommentNotificationTest`.** Tests:
  - `aCommentOnATaskReachesItsAssigneeAndEarlierCommenters` — bob assigned (TASK_VIEW), carol commented earlier (TASK_VIEW), dave comments → bob and carol one `NEW_COMMENT` each, dave none.
  - `aCommentOnTheJourneyReachesTheCaseOwnerAndEarlierCommenters` — `CommentResourceType.CASE`; owner (CASE_VIEW) and earlier commenter notified; visibility is `case.view`.
  - `theAuthorIsNeverNotifiedEvenAsAnEarlierCommenter`.
  - `aCommenterWhoLostAccessIsNotNotified` — carol's role revoked (`roles.unassignRole` or deactivate) before dave's comment → no row for carol.

  Run — FAIL.

- [ ] **Step 3: Publish** in `CommentService.create`, after `COMMENT_ADDED` (constructor gains `ApplicationEventPublisher events`):

```java
        events.publishEvent(new CommentAdded(comment.getId(), request.resourceType(), request.resourceId(),
                c.getId(), comment.getAuthorId()));
```

- [ ] **Step 4: Facts.**

```java
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> earlierCommenters(UUID commentId) {
        return jdbc.queryForList("""
                SELECT DISTINCT e.author_id FROM comment e JOIN comment c
                  ON c.resource_type = e.resource_type AND c.resource_id = e.resource_id
                 WHERE c.id = ? AND e.id <> c.id AND e.created_at <= c.created_at""", UUID.class, commentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String commentBody(UUID commentId) {
        return jdbc.queryForObject("SELECT body FROM comment WHERE id = ?", String.class, commentId);
    }
```

- [ ] **Step 5: Listener** in `TaskNotifications`:

```java
    @EventListener
    public void on(CommentAdded e) {
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>();
        NotificationPipeline.Visibility visibility;
        String about;
        if (e.resourceType() == CommentResourceType.TASK) {
            var task = facts.task(e.resourceId());
            if (task.assigneeId() != null) candidates.add(task.assigneeId());
            visibility = new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, task.id());
            about = "\"" + Text.clip(task.title(), 80) + "\"";
        } else {
            if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
            visibility = new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id());
            about = kase.name();
        }
        candidates.addAll(facts.earlierCommenters(e.commentId()));
        var draft = new NotificationPipeline.Draft(NotificationType.NEW_COMMENT, "comment", e.commentId(), kase.id(),
                "New comment on " + Text.clip(about, 90),
                facts.userName(e.authorId()) + ": " + Text.clip(facts.commentBody(e.commentId()), 300),
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, candidates, e.authorId(), visibility);
    }
```

- [ ] **Step 6: Ordering case** — `commentingIsRecordedBeforeItsNotification` asserting `comment.added` (check the real key in `AuditActions.COMMENT_ADDED`).
- [ ] **Step 7: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.task.*"`. PASS.
- [ ] **Step 8: Commit** — `feat(task): notify the thread when someone comments`.

---

### Task 15: Milestone completed

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/MilestoneCompleted.java`
- Modify: `backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java` (constructor; `markDone`)
- Modify: `backend/src/main/java/co/ara/onboarding/journey/ApprovalService.java` (constructor; `decideForceComplete`)
- Create: `backend/src/main/java/co/ara/onboarding/notification/JourneyNotifications.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/SubjectFacts.java` (`milestone`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/MilestoneNotificationTest.java`

**Interfaces:**
- Produces:
  ```java
  public record MilestoneCompleted(UUID milestoneId, UUID caseId, boolean forced, UUID actorId) {}   // journey
  public record MilestoneFacts(UUID id, String name, UUID caseId, UUID ownerUserId, LocalDate dueDate) {} // SubjectFacts
  public MilestoneFacts milestone(UUID milestoneId);   // name from milestone_definition
  @Component public class JourneyNotifications { @EventListener public void on(MilestoneCompleted e); }  // + Task 20 stage events
  ```

- [ ] **Step 1: Failing test.** `MilestoneNotificationTest`:
  - `completingAMilestoneNotifiesTheCaseAudience` — owner (CASE_VIEW ALL) and a participant (`journey.addParticipant(t, caseId, userId, RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE)`, CASE_VIEW ALL) each get one `MILESTONE_COMPLETED` row, tone `OK`, when a third user satisfies the last requirement (`RequirementService.satisfy`).
  - `theCompleterIsNotNotified` — the owner satisfies it themselves → the owner gets nothing, the participant does.
  - `aForcedCompletionSaysSo` — drive `ApprovalService.requestForceComplete` + `decideForceComplete(approve=true)` as two different users (follow `security.ForceCompleteTest`'s arrangement) → title starts `Milestone force-completed:`.
  - `aRemovedParticipantIsNotNotified` — participant with `ParticipantStatus.REMOVED` → nothing.

  Run — FAIL.

- [ ] **Step 2: Publish.** `CaseEngine` gains `ApplicationEventPublisher events` (last constructor parameter). In `markDone`, inside `if (firstTime)`, after the `MILESTONE_COMPLETED` audit record:

```java
            events.publishEvent(new MilestoneCompleted(m.getId(), c.getId(), false, contextProvider.current().userId()));
```

  `ApprovalService` gains `ApplicationEventPublisher events`; in `decideForceComplete`'s approve branch, after `MILESTONE_FORCE_COMPLETED` and **before** `engine.reconcile(c)`:

```java
            events.publishEvent(new MilestoneCompleted(m.getId(), c.getId(), true, decider));
```

  A forced milestone is set `DONE` before `reconcile`, so `markDone`'s `firstTime` is false for it and it publishes once. Do not change any other line of `CaseEngine` (sub-project 2's invariant: no new `reconcile` caller, no mutation-path change).

- [ ] **Step 3: Facts.**

```java
    public record MilestoneFacts(UUID id, String name, UUID caseId, UUID ownerUserId, LocalDate dueDate) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public MilestoneFacts milestone(UUID milestoneId) {
        return jdbc.queryForObject("""
                SELECT m.id, d.name, m.case_id, m.owner_user_id, m.due_date
                  FROM milestone m JOIN milestone_definition d ON d.id = m.milestone_definition_id WHERE m.id = ?""",
                (rs, i) -> new MilestoneFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class)), milestoneId);
    }
```

- [ ] **Step 4: Listener.**

```java
@Component
public class JourneyNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;
    // constructor …

    @EventListener
    public void on(MilestoneCompleted e) {
        var m = facts.milestone(e.milestoneId());
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>(facts.caseAudience(kase.id()));
        if (m.ownerUserId() != null) candidates.add(m.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.MILESTONE_COMPLETED, "milestone", m.id(), kase.id(),
                (e.forced() ? "Milestone force-completed: " : "Milestone completed: ") + Text.clip(m.name(), 80),
                kase.name() + " (" + kase.customerName() + ")",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.OK, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
}
```

- [ ] **Step 5: Ordering case** — `completingAMilestoneIsRecordedBeforeItsNotification` (`milestone.completed`).
- [ ] **Step 6: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.journey.*"`, `--tests "co.ara.onboarding.security.*"`, `--tests "co.ara.onboarding.architecture.*"`. The whole `journey` package must stay green — `CaseEngine` is load-bearing.
- [ ] **Step 7: Commit** — `feat(journey): notify the case audience when a milestone completes` (body: plan amendment 1, force-complete publishes from ApprovalService; no reconcile change).

---

### Task 16: Documents — requested, uploaded, reviewed

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/document/{DocumentRequested,DocumentUploaded,DocumentReviewed}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/{DocumentRequestService,DocumentInstantiation,DocumentService,DocumentReviewService}.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/DocumentNotifications.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/SubjectFacts.java` (`document`, `documentRequest`, `requesterOfDocument`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/DocumentNotificationTest.java`

**Interfaces:**
- Produces:
  ```java
  public record DocumentRequested(UUID requestId, UUID caseId, UUID actorId) {}                                   // document
  public record DocumentUploaded(UUID documentId, UUID requestId, UUID caseId, UUID actorId) {}                   // requestId nullable
  public record DocumentReviewed(UUID documentId, int versionNo, UUID caseId, String decision, UUID actorId) {}   // "APPROVED" | "REJECTED"
  public record DocumentFacts(UUID id, String name, UUID caseId, UUID uploadedBy, Instant expiresAt) {}          // SubjectFacts
  public record RequestFacts(UUID id, UUID caseId, String category, String description, UUID requestedBy) {}
  public DocumentFacts document(UUID documentId);
  public RequestFacts documentRequest(UUID requestId);
  public Optional<UUID> requesterOfDocument(UUID documentId);   // requested_by of the request this document fulfilled
  public Optional<UUID> internalVersionUploader(UUID documentId, int versionNo);
  ```

- [ ] **Step 1: Failing test.** `DocumentNotificationTest`:
  - `aNewRequestNotifiesTheCaseOwner` — actor (DOCUMENT_REQUEST + WORKFLOW_VIEW at ALL) creates a request on a case owned by `owner` (CASE_VIEW ALL) → one `DOCUMENT_REQUESTED` row, visibility `case.view`.
  - `anInstantiatedRequestNotifiesTheCaseOwnerToo` — a workflow with a `DOCUMENT` requirement; the case creator is not the owner.
  - `aPortalUploadNotifiesTheRequesterAndTheCaseOwner` — drive the portal upload path the way `DocumentService.uploadFromPortal`'s own tests do, with an open request; requester and owner (both DOCUMENT_VIEW ALL, no targeting) each get exactly one `DOCUMENT_UPLOADED` row **even though both the upload and the fulfil publish** (dedupe key `UPLOADED:{documentId}`).
  - `aTargetedUploadDoesNotReachAnOwnerOutsideItsAudience` (Review Focus 1) — the document targets department B; the owner sits in department A with DOCUMENT_VIEW at ALL → no row for the owner; a requester in department B does get one.
  - `aReviewNotifiesTheRequesterAndOwnerWithTheDecisionTone` — approve → tone `OK`, title contains `approved`; reject → tone `RISK`, `rejected`.

  Run — FAIL.

- [ ] **Step 2: Events and publishes.** Each service gains `ApplicationEventPublisher events` as its last constructor parameter (`DocumentInstantiation` also uses its existing `AuthContextProvider`).

```java
// DocumentRequestService.create, after customerWaits.requestOpened(...):
        events.publishEvent(new DocumentRequested(dr.getId(), c.getId(), contextProvider.current().userId()));

// DocumentInstantiation.instantiateForCase, after customerWaits.requestOpened(caseId, dr.getRequestedAt()):
            events.publishEvent(new DocumentRequested(dr.getId(), caseId, contextProvider.current().userId()));

// DocumentRequestService.fulfil, after customerWaits.requestClosed(...), before the satisfy branch:
        events.publishEvent(new DocumentUploaded(d.getId(), dr.getId(), dr.getCaseId(), contextProvider.current().userId()));

// DocumentService.uploadFromPortal: capture the view, publish, return it:
        DocumentView view = persistNewDocument(c, actor, request, null, null, actingContactId, stored, sizeBytes);
        events.publishEvent(new DocumentUploaded(view.id(), null, c.getId(), actor));
        return view;

// DocumentReviewService.review, after DOCUMENT_REVIEWED, before the APPROVED branch:
        events.publishEvent(new DocumentReviewed(d.getId(), versionNo, c.getId(), decision.name(),
                contextProvider.current().userId()));
```

  (Use whatever local variable `uploadFromPortal` already holds for the acting user id; it is `actor` at line ~576.)

- [ ] **Step 3: Facts.**

```java
    public record DocumentFacts(UUID id, String name, UUID caseId, UUID uploadedBy, Instant expiresAt) {}
    public record RequestFacts(UUID id, UUID caseId, String category, String description, UUID requestedBy) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentFacts document(UUID documentId) {
        return jdbc.queryForObject("SELECT id, name, case_id, uploaded_by, expires_at FROM document WHERE id = ?",
                (rs, i) -> new DocumentFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
                documentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestFacts documentRequest(UUID requestId) {
        return jdbc.queryForObject("SELECT id, case_id, category, description, requested_by FROM document_request WHERE id = ?",
                (rs, i) -> new RequestFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class)), requestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> requesterOfDocument(UUID documentId) {
        return jdbc.queryForList("SELECT requested_by FROM document_request WHERE fulfilled_document_id = ? ORDER BY requested_at DESC LIMIT 1",
                UUID.class, documentId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> internalVersionUploader(UUID documentId, int versionNo) {
        return jdbc.queryForList("""
                SELECT v.uploaded_by FROM document_version v JOIN app_user u ON u.id = v.uploaded_by
                 WHERE v.document_id = ? AND v.version_no = ? AND u.user_type = 'INTERNAL'""",
                UUID.class, documentId, versionNo).stream().findFirst();
    }
```

  Confirm `document`, `document_request` and `document_version` column names (`\d`) before running; adjust to the real ones.

- [ ] **Step 4: Listener.**

```java
@Component
public class DocumentNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;
    // constructor …

    @EventListener
    public void on(DocumentRequested e) {
        var req = facts.documentRequest(e.requestId());
        var kase = facts.caseFacts(e.caseId());
        String what = humanise(req.category()) + (req.description() == null ? "" : " - " + Text.clip(req.description(), 120));
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_REQUESTED, "document_request", req.id(),
                kase.id(), "Document requested on " + Text.clip(kase.name(), 80), what,
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, kase.ownerUserId() == null ? List.of() : List.of(kase.ownerUserId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }

    @EventListener
    public void on(DocumentUploaded e) {
        var doc = facts.document(e.documentId());
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>();
        if (e.requestId() != null) candidates.add(facts.documentRequest(e.requestId()).requestedBy());
        else facts.requesterOfDocument(doc.id()).ifPresent(candidates::add);
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_UPLOADED, "document", doc.id(), kase.id(),
                kase.customerName() + " uploaded a document", Text.clip(doc.name(), 120) + " on " + kase.name() + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO,
                "UPLOADED:" + doc.id());   // the portal upload and the fulfil both publish; deliver once
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, doc.id()));
    }

    @EventListener
    public void on(DocumentReviewed e) {
        var doc = facts.document(e.documentId());
        var kase = facts.caseFacts(e.caseId());
        boolean approved = "APPROVED".equals(e.decision());
        Set<UUID> candidates = new LinkedHashSet<>();
        facts.requesterOfDocument(doc.id()).ifPresent(candidates::add);
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        facts.internalVersionUploader(doc.id(), e.versionNo()).ifPresent(candidates::add);
        var draft = new NotificationPipeline.Draft(NotificationType.DOCUMENT_DECIDED, "document", doc.id(), kase.id(),
                Text.clip(doc.name(), 80) + (approved ? " approved" : " rejected"),
                "Version " + e.versionNo() + " on " + kase.name() + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), approved ? Tone.OK : Tone.RISK, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, doc.id()));
    }

    /** "TAX_CERTIFICATE" -> "Tax certificate": never the raw enum in a message (sub-project 6's open item). */
    static String humanise(String category) {
        if (category == null || category.isBlank()) return "Document";
        String s = category.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
```

  Note the uploaded dedupe key: `NotificationWriter.insert` uses `ON CONFLICT DO NOTHING`, so the second publish writes nothing — but `DocumentUploaded` notifications are not sweep reminders; record in the class javadoc that the key exists only to collapse the portal-upload/fulfil double publish.

- [ ] **Step 5: Ordering cases** — `requestingADocumentIsRecordedBeforeItsNotification` (`document.requested`) and `reviewingIsRecordedBeforeItsNotification` (`document.reviewed`).
- [ ] **Step 6: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.document.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 7: Commit** — `feat(document): notify on document requests, uploads and review decisions` (body: Review Focus 1; the audience filter is honoured through RecipientAccess).

---

### Task 17: Agreement status changes

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/agreement/AgreementStatusChanged.java`
- Modify: `backend/src/main/java/co/ara/onboarding/agreement/{AgreementService,AgreementReviewService,AgreementSignatureService}.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/AgreementNotifications.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/SubjectFacts.java` (`agreement`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/AgreementNotificationTest.java`

**Interfaces:**
- Produces:
  ```java
  public record AgreementStatusChanged(UUID agreementId, UUID caseId, Change change, UUID actorId) {   // agreement
      public enum Change { SUBMITTED, APPROVED, REJECTED, SENT, SIGNED, CANCELLED }
  }
  public record AgreementFacts(UUID id, String name, UUID caseId, UUID ownerUserId, String status,
                               LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays) {}   // SubjectFacts
  public AgreementFacts agreement(UUID agreementId);
  ```

- [ ] **Step 1: Failing test.** Use `AgreementTestSupport.openCaseWithSignatureRequirement` and the drive sequence from `CauseBeforeEffectTest.signingAnAgreementIsRecordedBeforeTheRequirementItSatisfies` (editor, submitter, reviewer, recorder are four administrators; set the agreement's `owner_user_id` and the case owner to a fifth user `watcher` with AGREEMENT_VIEW + CASE_VIEW at ALL via owner SQL). Tests:
  - `eachTransitionNotifiesTheOwnerAndCaseOwner` — after submit, approve, send and record: `watcher` has rows whose titles end `Submitted for review`, `Approved`, `Sent for signature`, `Signed` (the case owner is the same person here, so exactly one per transition).
  - `aRejectionIsRiskToned` — reject → tone `RISK`, title ends `Rejected`.
  - `aCancellationNotifies` — cancel → `Cancelled`, tone `RISK`.
  - `aPartialSignatureIsNotAnnounced` — two signatories, record one → no `Signed` row (spec §1.2.7).

  Run — FAIL.

- [ ] **Step 2: Publishes**, each after the transition's audit record, each service gaining `ApplicationEventPublisher events`:
  - `AgreementService.submit` → `Change.SUBMITTED`; `send` → `SENT`; `cancel` → `CANCELLED` for `old` (after `AGREEMENT_CANCELLED`, before the successor is created).
  - `AgreementReviewService.review` → `approved ? APPROVED : REJECTED`.
  - `AgreementSignatureService.record` → `SIGNED`, inside `if (last)`, after `AGREEMENT_SIGNED`, before `satisfyIfStillOpen(a)`.
  The actor is the method's existing actor variable (`contextProvider.principal().userId()` where none is in scope).

- [ ] **Step 3: Facts** (`agreement` table: confirm `name`, `case_id`, `owner_user_id`, `status`, `expires_at`, `renewal_date`, `notice_period_days`).

```java
    public record AgreementFacts(UUID id, String name, UUID caseId, UUID ownerUserId, String status,
                                 LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public AgreementFacts agreement(UUID agreementId) {
        return jdbc.queryForObject("""
                SELECT id, name, case_id, owner_user_id, status, expires_at, renewal_date, notice_period_days
                  FROM agreement WHERE id = ?""",
                (rs, i) -> new AgreementFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), (Integer) rs.getObject(8)), agreementId);
    }
```

- [ ] **Step 4: Listener.**

```java
@Component
public class AgreementNotifications {

    private static final Map<AgreementStatusChanged.Change, String> WORDS = Map.of(
            AgreementStatusChanged.Change.SUBMITTED, "Submitted for review",
            AgreementStatusChanged.Change.APPROVED, "Approved",
            AgreementStatusChanged.Change.REJECTED, "Rejected",
            AgreementStatusChanged.Change.SENT, "Sent for signature",
            AgreementStatusChanged.Change.SIGNED, "Signed",
            AgreementStatusChanged.Change.CANCELLED, "Cancelled");

    // pipeline, facts, constructor …

    @EventListener
    public void on(AgreementStatusChanged e) {
        var a = facts.agreement(e.agreementId());
        var kase = facts.caseFacts(e.caseId());
        Tone tone = switch (e.change()) {
            case APPROVED, SIGNED -> Tone.OK;
            case REJECTED, CANCELLED -> Tone.RISK;
            default -> Tone.INFO;
        };
        Set<UUID> candidates = new LinkedHashSet<>();
        if (a.ownerUserId() != null) candidates.add(a.ownerUserId());
        if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
        var draft = new NotificationPipeline.Draft(NotificationType.AGREEMENT_STATUS, "agreement", a.id(), kase.id(),
                Text.clip(a.name(), 80) + ": " + WORDS.get(e.change()), kase.name() + " (" + kase.customerName() + ")",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), tone, null);
        pipeline.deliver(draft, candidates, e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.AGREEMENT_VIEW, Agreement.class, a.id()));
    }
}
```

- [ ] **Step 5: Ordering case** — `signingIsRecordedBeforeItsNotification` (`agreement.signed`).
- [ ] **Step 6: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.agreement.*"`, `--tests "co.ara.onboarding.journey.CauseBeforeEffectTest"`. PASS.
- [ ] **Step 7: Commit** — `feat(agreement): notify the agreement and case owners of status changes` (body: plan amendment 1 — three services publish; partial signatures are not announced).

---

### Task 18: Workflow version published, customer assigned

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/workflow/WorkflowVersionPublished.java`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/PublishService.java`
- Create: `backend/src/main/java/co/ara/onboarding/customer/CustomerOwnerAssigned.java`
- Modify: `backend/src/main/java/co/ara/onboarding/customer/CustomerService.java` (`create`, `update`)
- Create: `backend/src/main/java/co/ara/onboarding/notification/{WorkflowNotifications,CustomerNotifications}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/SubjectFacts.java` (`openCasesOnEarlierVersions`, `customer`, `templateName`)
- Create: `backend/src/test/java/co/ara/onboarding/notification/WorkflowAndCustomerNotificationTest.java`

**Interfaces:**
- Produces:
  ```java
  public record WorkflowVersionPublished(UUID templateId, UUID versionId, int versionNo, UUID actorId) {}   // workflow
  public record CustomerOwnerAssigned(UUID customerId, UUID ownerUserId, UUID actorId) {}                 // customer
  public record OutdatedCase(UUID caseId, UUID ownerUserId, UUID customerId) {}                           // SubjectFacts
  public List<OutdatedCase> openCasesOnEarlierVersions(UUID templateId, UUID publishedVersionId);
  public record CustomerFacts(UUID id, String displayName, UUID ownerUserId) {}
  public CustomerFacts customer(UUID customerId);
  public String templateName(UUID templateId);
  ```

- [ ] **Step 1: Failing test.** Tests:
  - `publishingANewVersionTellsOwnersOfOpenCasesOnEarlierVersions` — publish v1, open three cases owned by `alice` (CASE_VIEW ALL) and one owned by `bob` (CASE_VIEW ALL) and one COMPLETED case owned by bob (set status by owner SQL); publish v2 via `PublishService` → alice one row, body `3 of your open cases are on an earlier version.`; bob one row with `1 of your open cases`; title `<template> v2 is live`.
  - `anOwnerWhoCannotSeeTheirCasesIsNotTold` — carol owns a case but holds no CASE_VIEW → nothing.
  - `assigningACustomerToSomeoneElseNotifiesThem` — `CustomerService.update` changing the owner to `dave` (CUSTOMER_VIEW ALL) → one `NEW_CUSTOMER` row, link `/t/{slug}/customers/{id}`.
  - `creatingACustomerNotifiesNobody` — create always owns it to the actor (plan amendment 8).

  Run — FAIL.

- [ ] **Step 2: Publishes.** `PublishService` gains `ApplicationEventPublisher events`; after `WORKFLOW_PUBLISHED`:

```java
        events.publishEvent(new WorkflowVersionPublished(template.getId(), versionId, version.getVersionNo(),
                contextProvider.principal().userId()));
```

  `CustomerService` gains `ApplicationEventPublisher events`. In `create`, after `CUSTOMER_CREATED`: `events.publishEvent(new CustomerOwnerAssigned(c.getId(), c.getOwnerUserId(), actor));` (it reaches nobody today because owner = actor; it is published so a future create-for-someone-else is covered). In `update`, inside the owner-changed branch, after the update's audit record: `events.publishEvent(new CustomerOwnerAssigned(c.getId(), c.getOwnerUserId(), contextProvider.principal().userId()));`.

- [ ] **Step 3: Facts.**

```java
    public record OutdatedCase(UUID caseId, UUID ownerUserId, UUID customerId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutdatedCase> openCasesOnEarlierVersions(UUID templateId, UUID publishedVersionId) {
        return jdbc.query("""
                SELECT id, owner_user_id, customer_id FROM onboarding_case
                 WHERE template_id = ? AND version_id <> ? AND status IN ('ACTIVE','ON_HOLD') AND owner_user_id IS NOT NULL
                 ORDER BY id""",
                (rs, i) -> new OutdatedCase(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)),
                templateId, publishedVersionId);
    }

    public record CustomerFacts(UUID id, String displayName, UUID ownerUserId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerFacts customer(UUID customerId) {
        return jdbc.queryForObject("SELECT id, display_name, owner_user_id FROM customer WHERE id = ?",
                (rs, i) -> new CustomerFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)),
                customerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String templateName(UUID templateId) {
        return jdbc.queryForObject("SELECT name FROM workflow_template WHERE id = ?", String.class, templateId);
    }
```

- [ ] **Step 4: Listeners.**

```java
/** WORKFLOW_PUBLISHED (spec 1.2.6): one row per case owner, counting only cases they can view. */
@Component
public class WorkflowNotifications {

    // pipeline, facts, access (RecipientAccess), constructor …

    @EventListener
    public void on(WorkflowVersionPublished e) {
        String slug = facts.tenantSlug();
        String template = facts.templateName(e.templateId());
        Map<UUID, List<SubjectFacts.OutdatedCase>> byOwner = facts.openCasesOnEarlierVersions(e.templateId(), e.versionId())
                .stream().collect(Collectors.groupingBy(SubjectFacts.OutdatedCase::ownerUserId, LinkedHashMap::new, Collectors.toList()));
        byOwner.forEach((owner, cases) -> {
            var visible = cases.stream()
                    .filter(c -> access.canView(owner, PermissionKeys.CASE_VIEW, Case.class, c.caseId())).toList();
            if (visible.isEmpty()) return;
            var first = visible.get(0);
            var draft = new NotificationPipeline.Draft(NotificationType.WORKFLOW_PUBLISHED, "workflow_version",
                    e.versionId(), null, Text.clip(template, 80) + " v" + e.versionNo() + " is live",
                    visible.size() + " of your open cases " + (visible.size() == 1 ? "is" : "are") + " on an earlier version.",
                    Links.caseLink(slug, first.customerId(), first.caseId()), Tone.INFO, null);
            pipeline.deliver(draft, List.of(owner), e.actorId(),
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, first.caseId()));
        });
    }
}

/** NEW_CUSTOMER (plan amendment 8): a customer assigned to someone other than the actor. */
@Component
public class CustomerNotifications {

    // pipeline, facts, constructor …

    @EventListener
    public void on(CustomerOwnerAssigned e) {
        if (e.ownerUserId() == null) return;
        var c = facts.customer(e.customerId());
        var draft = new NotificationPipeline.Draft(NotificationType.NEW_CUSTOMER, "customer", c.id(), null,
                "Customer assigned to you: " + Text.clip(c.displayName(), 80),
                facts.userName(e.actorId()) + " made you the owner of " + Text.clip(c.displayName(), 120) + ".",
                Links.customerLink(facts.tenantSlug(), c.id()), Tone.INFO, null);
        pipeline.deliver(draft, List.of(e.ownerUserId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.CUSTOMER_VIEW, Customer.class, c.id()));
    }
}
```

  The `workflow_version` subject with a null `case_id` is fine (`case_id` is nullable).

- [ ] **Step 5: Ordering case** — `publishingIsRecordedBeforeItsNotification` (`workflow.published`).
- [ ] **Step 6: Run** `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.workflow.*"`, `--tests "co.ara.onboarding.customer.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 7: Commit** — `feat(workflow,customer): notify owners of a new workflow version and of a customer assignment` (body: spec §1.2.6, plan amendment 8).

---

## Phase 4 — Templates and stage alerts

### Task 19: Notification templates and `notification.manage`

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__notification_template.sql`
- Modify: `backend/src/main/java/co/ara/onboarding/authz/{PermissionKeys,PermissionCatalog,RoleTemplates}.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/{NotificationTemplate,NotificationTemplateRepository,TemplatePlaceholders,NotificationAdminService,NotificationAdminController,TemplateView,CreateTemplateRequest,UpdateTemplateRequest,TemplateOption}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/TemplateAdminTest.java`

**Interfaces:**
- Produces:
  ```java
  PermissionKeys.NOTIFICATION_MANAGE = "notification.manage"     // ALL-only, Administrator
  public record TemplateView(UUID id, String key, String name, String enteredSubject, String enteredBody,
                             String exitedSubject, String exitedBody, boolean active) {}
  public record CreateTemplateRequest(@NotBlank String key, @NotBlank @Size(max = 120) String name,
          @NotBlank @Size(max = 200) String enteredSubject, @NotBlank @Size(max = 2000) String enteredBody,
          @Size(max = 200) String exitedSubject, @Size(max = 2000) String exitedBody, @NotNull Boolean active) {}
  public record UpdateTemplateRequest(/* identical components to CreateTemplateRequest */) {}
  public record TemplateOption(String key, String name) {}
  @Service public class NotificationAdminService {
      @RequirePermission(NOTIFICATION_MANAGE) public List<TemplateView> templates();
      @RequirePermission(NOTIFICATION_MANAGE) public TemplateView createTemplate(CreateTemplateRequest r);
      @RequirePermission(NOTIFICATION_MANAGE) public TemplateView updateTemplate(UUID id, UpdateTemplateRequest r);
      @RequirePermission(WORKFLOW_MANAGE)     public List<TemplateOption> templateOptions();
      // Task 21 adds getPolicy/replacePolicy
  }
  public final class TemplatePlaceholders {
      public static final Set<String> ALLOWED = Set.of("case", "customer", "stage", "owner");
      public static void validate(String field, String text);           // throws NotificationRuleException
      public static String render(String text, Map<String, String> values);
  }
  // HTTP: GET/POST /api/t/{slug}/admin/notification-templates · PUT /admin/notification-templates/{id}
  //       GET /api/t/{slug}/notification-templates/options
  AuditActions.NOTIFICATION_TEMPLATE_CREATED / _UPDATED / _DEACTIVATED (all false)
  ```

- [ ] **Step 1: How did sub-project 6 grant `calendar.manage` to existing tenants' Administrator roles?** `rg -n "role_grant" backend/src/main/resources/db/migration`. Mirror that exact statement for `notification.manage` at `ALL` in V37. If sub-project 6 relied on something else (for example a startup reconciler of role templates), follow that instead and say so in the commit body.

- [ ] **Step 2: Failing `TemplateAdminTest extends SecurityTestBase`.** Tests:
  - `anAdministratorCreatesListsAndUpdatesATemplate` — POST `{key: "kickoff", name: "Kickoff", enteredSubject: "{case} entered {stage}", enteredBody: "Owner: {owner}", exitedSubject: null, exitedBody: null, active: true}` → 200 with an id; GET lists it; PUT changes the name; audit rows `notification_template.created`/`.updated`, `timeline_visible = false`.
  - `aDuplicateKeyIs409`.
  - `anUnknownPlaceholderIs422` — `"{case} for {contact}"` → 422 naming `{contact}`.
  - `aMalformedKeyIs400` — `"Kick Off"`.
  - `changingTheKeyIs422`.
  - `exitedSubjectWithoutBodyIs422` — both or neither.
  - `deactivatingRecordsItsOwnAction` — PUT `active: false` → `notification_template.deactivated` (not `.updated`).
  - `aUserWithoutNotificationManageIs403OnTheAdminRoutes` and `aWorkflowManagerCanReadOptionsButNotTheAdminList`.
  - `optionsListOnlyActiveTemplates`.
  - `templatesAreTenantIsolated` — another tenant's template id → 404 on PUT.
  - `requestAndViewAreAligned` — reflection, `CreateTemplateRequest`/`UpdateTemplateRequest` components ⊆ `TemplateView` components.

  Run — FAIL.

- [ ] **Step 3: Migration `V37__notification_template.sql`.**

```sql
-- Sub-project 6B, spec §4.3. A template is referenced by key from stage.notification_template_key,
-- so the key is immutable and templates are deactivated, never deleted.
CREATE TABLE notification_template (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant(id),
    key              text        NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'),
    name             text        NOT NULL CHECK (length(name) <= 120),
    entered_subject  text        NOT NULL CHECK (length(entered_subject) <= 200),
    entered_body     text        NOT NULL CHECK (length(entered_body) <= 2000),
    exited_subject   text        NULL CHECK (length(exited_subject) <= 200),
    exited_body      text        NULL CHECK (length(exited_body) <= 2000),
    active           boolean     NOT NULL DEFAULT true,
    created_by       uuid        NULL REFERENCES app_user(id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT notification_template_key_uq UNIQUE (tenant_id, key),
    CONSTRAINT notification_template_exit_pair_ck CHECK ((exited_subject IS NULL) = (exited_body IS NULL))
);
SELECT enable_tenant_rls('notification_template');
GRANT SELECT, INSERT, UPDATE ON notification_template TO onboarding_app;

-- notification.manage for every existing tenant's Administrator role (mirror Step 1's finding).
```

- [ ] **Step 4: Permission.** `PermissionKeys`: `// Sub-project 6B Task 19.` then `public static final String NOTIFICATION_MANAGE = "notification.manage";`. `PermissionCatalog`: `add(NOTIFICATION_MANAGE, "tenant", null, "Manage notification templates, deadline horizons and automatic reminders", ALL_ONLY);`. `RoleTemplates` Administrator: `entry(NOTIFICATION_MANAGE, ALL)` beside `CALENDAR_MANAGE`. `RoleTemplateValidityTest.administratorGrantsEveryPermissionInTheCatalog` proves the Administrator side; `RoleTemplateCoverageTest` exempts ALL-only keys.

- [ ] **Step 5: `TemplatePlaceholders`.**

```java
public final class TemplatePlaceholders {

    public static final Set<String> ALLOWED = Set.of("case", "customer", "stage", "owner");
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");

    private TemplatePlaceholders() {}

    /** Refused at save, never at send (spec 5.5). */
    public static void validate(String field, String text) {
        if (text == null) return;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!ALLOWED.contains(m.group(1))) {
                throw new NotificationRuleException("Unknown placeholder {" + m.group(1) + "} in " + field
                        + "; use {case}, {customer}, {stage} or {owner}");
            }
        }
    }

    public static String render(String text, Map<String, String> values) {
        String out = text;
        for (var e : values.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return out;
    }
}
```

- [ ] **Step 6: Entity, repository, service, controller.** `NotificationTemplate extends TenantScopedEntity` mapping the table (fields `key`, `name`, `enteredSubject`, `enteredBody`, `exitedSubject`, `exitedBody`, `active`, `createdBy`). `NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID>, JpaSpecificationExecutor<NotificationTemplate>`. The service reads through `AuthorizedQuery` (`NOTIFICATION_MANAGE` and `WORKFLOW_MANAGE` are ALL-only with a null resource type, so no descriptor is needed — `AuthorizationPredicateBuilder.scopePredicate` returns a conjunction for `ALL` without consulting the registry):

```java
    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional
    public TemplateView createTemplate(CreateTemplateRequest r) {
        validate(r.key(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody());
        boolean taken = authorizedQuery.count(templates, NotificationTemplate.class, PermissionKeys.NOTIFICATION_MANAGE,
                (root, q, cb) -> cb.equal(root.get("key"), r.key())) > 0;
        if (taken) throw new IllegalStateException("A template with key '" + r.key() + "' already exists");
        NotificationTemplate t = new NotificationTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(TenantContext.getRequired());
        t.setKey(r.key());
        apply(t, r.name(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody(), r.active());
        t.setCreatedBy(contexts.principal().userId());
        templates.save(t);
        audit.record(AuditActions.NOTIFICATION_TEMPLATE_CREATED, "notification_template", t.getId(),
                "Created notification template " + t.getKey(), Map.of("key", t.getKey()));
        return view(t);
    }

    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional
    public TemplateView updateTemplate(UUID id, UpdateTemplateRequest r) {
        NotificationTemplate t = authorizedQuery.getById(templates, NotificationTemplate.class,
                PermissionKeys.NOTIFICATION_MANAGE, id);
        if (!t.getKey().equals(r.key())) throw new NotificationRuleException("A template's key cannot change; stages refer to it");
        validate(r.key(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody());
        boolean deactivating = t.isActive() && !r.active();
        apply(t, r.name(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody(), r.active());
        audit.record(deactivating ? AuditActions.NOTIFICATION_TEMPLATE_DEACTIVATED : AuditActions.NOTIFICATION_TEMPLATE_UPDATED,
                "notification_template", t.getId(), (deactivating ? "Deactivated" : "Updated") + " notification template " + t.getKey(),
                Map.of("key", t.getKey()));
        return view(t);
    }

    private static void validate(String key, String enteredSubject, String enteredBody, String exitedSubject, String exitedBody) {
        if (!key.matches("^[a-z0-9][a-z0-9_.-]{0,63}$")) {
            throw new IllegalArgumentException("A key is lowercase letters, digits, '.', '_' or '-', up to 64 characters");
        }
        if ((exitedSubject == null || exitedSubject.isBlank()) != (exitedBody == null || exitedBody.isBlank())) {
            throw new NotificationRuleException("An exit alert needs both a subject and a body, or neither");
        }
        TemplatePlaceholders.validate("the entered subject", enteredSubject);
        TemplatePlaceholders.validate("the entered body", enteredBody);
        TemplatePlaceholders.validate("the exited subject", exitedSubject);
        TemplatePlaceholders.validate("the exited body", exitedBody);
    }
```

  Blank exited fields are stored as `null`. `templates()` lists all ordered by `key`; `templateOptions()` (gated `WORKFLOW_MANAGE`) lists active ones as `TemplateOption`. A unique-constraint race still surfaces as a 409 via `DataIntegrityViolationException` → wrap the `save` in a catch that rethrows `IllegalStateException`. Controller routes as **Interfaces**; `@Valid @RequestBody`.

  `AuditActions`: `NOTIFICATION_TEMPLATE_CREATED = of("notification_template.created", false)`, `…_UPDATED`, `…_DEACTIVATED`.

- [ ] **Step 7: Run** `TemplateAdminTest`, `--tests "co.ara.onboarding.authz.*"`, `--tests "co.ara.onboarding.architecture.*"`, `--tests "co.ara.onboarding.provisioning.*"`. PASS.
- [ ] **Step 8: Commit** — `feat(notification): tenant notification templates and the notification.manage permission`.

---

### Task 20: Stage entered/exited alerts and the publish rule

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/journey/{StageEntered,StageExited}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/journey/{CaseEngine,MilestoneService}.java`
- Create: `backend/src/main/java/co/ara/onboarding/workflow/NotificationTemplateKeys.java`
- Modify: `backend/src/main/java/co/ara/onboarding/workflow/PublishService.java`
- Create: `backend/src/main/java/co/ara/onboarding/notification/TemplateKeysAdapter.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{JourneyNotifications,SubjectFacts}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/StageAlertTest.java`

**Interfaces:**
- Produces:
  ```java
  public record StageEntered(UUID caseId, UUID stageId, UUID actorId) {}       // journey
  public record StageExited(UUID caseId, UUID stageId, UUID actorId) {}        // journey
  public interface NotificationTemplateKeys { boolean exists(String key); }   // workflow port
  public record StageFacts(UUID id, String name, String templateKey) {}         // SubjectFacts
  public StageFacts stage(UUID stageId);
  public record TemplateFacts(String enteredSubject, String enteredBody, String exitedSubject, String exitedBody) {}
  public Optional<TemplateFacts> activeTemplate(String key);
  ```

- [ ] **Step 1: Failing `StageAlertTest`.** Workflows built with `WorkflowFixtures`/`SlaTestSupport.slaStage` and a `notificationTemplateKey` set on the stage request (the `StageRequest` record's component for it — check its position with the `slaStage` helper, which passes `null` there today). Tests:
  - `enteringAKeyedStageAlertsTheCaseAudience` — template `kickoff` with `enteredSubject: "{case} entered {stage}"`; a two-stage case advances into the keyed stage → owner gets one `STAGE_CHANGED` row titled `<case> entered <stage name>`, body rendered with `{customer}` and `{owner}`.
  - `exitingAlertsOnlyWhenTheTemplateHasAnExitPair`.
  - `anUnkeyedStageSendsNothing`.
  - `aDeactivatedTemplateSendsNothing` — template deactivated after publish → advance → nothing.
  - `publishingAStageThatNamesAMissingTemplateIs422` — `PublishService.publish` → `PublishValidationException` whose problems name the stage and key; with the template present, publish succeeds; a template that exists but is inactive also passes (spec §8).

  Run — FAIL.

- [ ] **Step 2: The port and the publish rule.**

```java
package co.ara.onboarding.workflow;

/**
 * Plan amendment 5: workflow cannot import notification, so publish validation asks through this
 * port -- the CustomerDirectory inversion. notification.TemplateKeysAdapter implements it.
 */
public interface NotificationTemplateKeys {
    /** True when a template with this key exists in the current tenant, active or not. */
    boolean exists(String key);
}
```

  `PublishService` gains `NotificationTemplateKeys templateKeys` as a constructor parameter. In `validate(versionId)`, after Rule 6:

```java
        // Rule 7 (6B spec 8): a stage's notification template key must name a template in this
        // tenant. A template deactivated later just sends nothing -- a frozen version can't be fixed.
        for (Stage s : stages) {
            String key = s.getNotificationTemplateKey();
            if (key != null && !key.isBlank() && !templateKeys.exists(key)) {
                problems.add("Stage " + s.getName() + " uses notification template '" + key + "', which does not exist");
            }
        }
```

  (Use the local list variable the earlier rules iterate.)

```java
package co.ara.onboarding.notification;

@Component
class TemplateKeysAdapter implements NotificationTemplateKeys {
    private final JdbcTemplate jdbc;
    TemplateKeysAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_template WHERE key = ?)", Boolean.class, key));
    }
}
```

- [ ] **Step 3: Publish stage events.** `CaseEngine` already has `events` (Task 15). In `enterStage`, after `slaClocks.stageEntered(...)`: `events.publishEvent(new StageEntered(c.getId(), stage.getId(), contextProvider.current().userId()));`. In `advanceIfExitable`, after `slaClocks.stageExited(...)`: `events.publishEvent(new StageExited(c.getId(), current.getId(), contextProvider.current().userId()));`. `MilestoneService` gains `ApplicationEventPublisher events`; in `reopen`'s stage-change branch, after the two `slaClocks` calls:

```java
            UUID actor = contextProvider.principal().userId();
            if (previousStage != null) events.publishEvent(new StageExited(c.getId(), previousStage, actor));
            events.publishEvent(new StageEntered(c.getId(), definition.getStageId(), actor));
```

  No other line of either class changes.

- [ ] **Step 4: Facts and listener.**

```java
    public record StageFacts(UUID id, String name, String templateKey) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public StageFacts stage(UUID stageId) {
        return jdbc.queryForObject("SELECT id, name, notification_template_key FROM stage WHERE id = ?",
                (rs, i) -> new StageFacts(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), stageId);
    }

    public record TemplateFacts(String enteredSubject, String enteredBody, String exitedSubject, String exitedBody) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TemplateFacts> activeTemplate(String key) {
        return jdbc.query("""
                SELECT entered_subject, entered_body, exited_subject, exited_body
                  FROM notification_template WHERE key = ? AND active""",
                (rs, i) -> new TemplateFacts(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), key)
                .stream().findFirst();
    }
```

  In `JourneyNotifications`:

```java
    @EventListener public void on(StageEntered e) { stageAlert(e.caseId(), e.stageId(), e.actorId(), true); }
    @EventListener public void on(StageExited e)  { stageAlert(e.caseId(), e.stageId(), e.actorId(), false); }

    private void stageAlert(UUID caseId, UUID stageId, UUID actorId, boolean entered) {
        var stage = facts.stage(stageId);
        if (stage.templateKey() == null || stage.templateKey().isBlank()) return;         // keyed stages only
        var template = facts.activeTemplate(stage.templateKey());
        if (template.isEmpty()) return;                                                    // missing or inactive
        String subject = entered ? template.get().enteredSubject() : template.get().exitedSubject();
        String body = entered ? template.get().enteredBody() : template.get().exitedBody();
        if (subject == null) return;                                                       // entry-only template
        var kase = facts.caseFacts(caseId);
        Map<String, String> values = Map.of("case", kase.name(), "customer", kase.customerName(),
                "stage", stage.name(), "owner", facts.userName(kase.ownerUserId()));
        var draft = new NotificationPipeline.Draft(NotificationType.STAGE_CHANGED, "stage", stage.id(), kase.id(),
                TemplatePlaceholders.render(subject, values), TemplatePlaceholders.render(body, values),
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, facts.caseAudience(kase.id()), actorId,
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
```

- [ ] **Step 5: Run** `StageAlertTest`, then `--tests "co.ara.onboarding.journey.*"`, `--tests "co.ara.onboarding.workflow.*"`, `--tests "co.ara.onboarding.sla.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 6: Commit** — `feat(journey): stage entered/exited alerts from tenant notification templates` (body: Q19; `notification_template_key` finally has a consumer; plan amendment 5).

---

## Phase 5 — Time-based alerts, reminders, digests

### Task 21: Deadline horizons and the automatic-reminder policy

**Files:**
- Create: `backend/src/main/resources/db/migration/V38__notification_policy.sql`
- Create: `backend/src/main/java/co/ara/onboarding/notification/{HorizonKind,PolicyReader,PolicyView,UpdatePolicyRequest,AutoRemindView}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{NotificationAdminService,NotificationAdminController}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/provisioning/TenantProvisioningService.java`
- Modify: `backend/src/main/java/co/ara/onboarding/audit/AuditActions.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/PolicyAdminTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum HorizonKind {
      TASK_DUE(true), MILESTONE_DUE(true), DOCUMENT_REQUEST_DUE(true),
      DOCUMENT_EXPIRY(false), AGREEMENT_EXPIRY(false), AGREEMENT_RENEWAL(false);
      public final boolean businessDays;
  }
  public record AutoRemindView(@NotNull Boolean enabled, @NotNull @Min(1) @Max(30) Integer intervalDays,
                               @NotNull @Min(1) @Max(10) Integer max) {}
  public record PolicyView(AutoRemindView autoRemind, Map<HorizonKind, List<Integer>> horizons) {}
  public record UpdatePolicyRequest(@NotNull @Valid AutoRemindView autoRemind, @NotNull Map<HorizonKind, List<Integer>> horizons) {}
  @Component public class PolicyReader {
      public record Policy(boolean autoRemindEnabled, int intervalDays, int max, Map<HorizonKind, List<Integer>> horizons) {}
      @Transactional(propagation = MANDATORY) public Policy current();   // leads ascending
  }
  // NotificationAdminService: getPolicy() / replacePolicy(UpdatePolicyRequest) — @RequirePermission(NOTIFICATION_MANAGE)
  // HTTP: GET/PUT /api/t/{slug}/admin/notification-policy
  AuditActions.NOTIFICATION_POLICY_UPDATED = of("notification_policy.updated", false)
  ```

- [ ] **Step 1: Failing `PolicyAdminTest extends SecurityTestBase`.**
  - `aNewTenantStartsWithTheDefaults` — `*_DUE` → `[2]`; the three expiry/renewal kinds → `[7, 14, 30]` (ascending); auto-remind `{enabled: false, intervalDays: 3, max: 3}` (spec §1.2.1).
  - `putReplacesEveryKindAndTheReminderPolicy` — round-trips; audit `notification_policy.updated`, `timeline_visible = false`.
  - `aMissingKindIs400`; `aLeadOutsideOneToNinetyIs422`; `moreThanFiveLeadsIs422`; `duplicateLeadsAre422`; `anEmptyListTurnsAKindOff` (allowed — `[]`).
  - `onlyNotificationManageMayReadOrWrite` — 403 for a user holding everything but it.
  - `policyIsTenantIsolated` — tenant B's PUT does not change tenant A.
  - `requestAndViewAreAligned` — reflection.

  Run — FAIL.

- [ ] **Step 2: Migration `V38__notification_policy.sql`.**

```sql
-- Sub-project 6B, spec §4.4. Lead times are configuration replaced as a set, so deadline_horizon
-- is the one 6B table that grants DELETE: removing a lead time has to actually remove it, and
-- notification_policy.updated is the record that it existed.
CREATE TABLE deadline_horizon (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant(id),
    kind        text        NOT NULL CHECK (kind IN ('TASK_DUE','MILESTONE_DUE','DOCUMENT_REQUEST_DUE',
                                                     'DOCUMENT_EXPIRY','AGREEMENT_EXPIRY','AGREEMENT_RENEWAL')),
    lead_days   int         NOT NULL CHECK (lead_days BETWEEN 1 AND 90),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT deadline_horizon_once UNIQUE (tenant_id, kind, lead_days)
);
SELECT enable_tenant_rls('deadline_horizon');
GRANT SELECT, INSERT, UPDATE, DELETE ON deadline_horizon TO onboarding_app;

CREATE TABLE notification_policy (
    id                         uuid        PRIMARY KEY,
    tenant_id                  uuid        NOT NULL UNIQUE REFERENCES tenant(id),
    auto_remind_enabled        boolean     NOT NULL DEFAULT false,   -- spec 1.2.1: off until a tenant decides
    auto_remind_interval_days  int         NOT NULL DEFAULT 3 CHECK (auto_remind_interval_days BETWEEN 1 AND 30),
    auto_remind_max            int         NOT NULL DEFAULT 3 CHECK (auto_remind_max BETWEEN 1 AND 10),
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL
);
SELECT enable_tenant_rls('notification_policy');
GRANT SELECT, INSERT, UPDATE ON notification_policy TO onboarding_app;

-- Backfill (Flyway owner; the V28 precedent).
INSERT INTO notification_policy (id, tenant_id, created_at, updated_at)
    SELECT gen_random_uuid(), id, now(), now() FROM tenant;
INSERT INTO deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at)
    SELECT gen_random_uuid(), t.id, k.kind, k.lead_days, now(), now()
      FROM tenant t CROSS JOIN (VALUES
           ('TASK_DUE', 2), ('MILESTONE_DUE', 2), ('DOCUMENT_REQUEST_DUE', 2),
           ('DOCUMENT_EXPIRY', 30), ('DOCUMENT_EXPIRY', 14), ('DOCUMENT_EXPIRY', 7),
           ('AGREEMENT_EXPIRY', 30), ('AGREEMENT_EXPIRY', 14), ('AGREEMENT_EXPIRY', 7),
           ('AGREEMENT_RENEWAL', 30), ('AGREEMENT_RENEWAL', 14), ('AGREEMENT_RENEWAL', 7)) AS k(kind, lead_days);
```

- [ ] **Step 3: Provisioning.** In `TenantProvisioningService.seedCalendarAndPolicy` (rename it `seedCalendarAndPolicies` and fix its javadoc), add the `notification_policy` insert and the twelve `deadline_horizon` inserts with `Uuid7.generate()` keys — the same values as the backfill. Extract the twelve `(kind, lead)` pairs to a `private static final List<Object[]> DEFAULT_HORIZONS` so the code reads as one table.

- [ ] **Step 4: `PolicyReader` and the admin methods** (JdbcTemplate, RLS-bound).

```java
    @Transactional(propagation = Propagation.MANDATORY)
    public Policy current() {
        var p = jdbc.queryForMap("SELECT auto_remind_enabled, auto_remind_interval_days, auto_remind_max FROM notification_policy");
        Map<HorizonKind, List<Integer>> horizons = new EnumMap<>(HorizonKind.class);
        for (HorizonKind k : HorizonKind.values()) horizons.put(k, new ArrayList<>());
        jdbc.query("SELECT kind, lead_days FROM deadline_horizon ORDER BY kind, lead_days",
                rs -> { horizons.get(HorizonKind.valueOf(rs.getString(1))).add(rs.getInt(2)); });
        return new Policy((Boolean) p.get("auto_remind_enabled"), ((Number) p.get("auto_remind_interval_days")).intValue(),
                ((Number) p.get("auto_remind_max")).intValue(), horizons);
    }
```

  `replacePolicy(UpdatePolicyRequest r)`: require `r.horizons().keySet()` equals all six kinds (400 otherwise); per kind, each lead in 1..90, at most five, no duplicates (each a `NotificationRuleException`, 422); then `DELETE FROM deadline_horizon`, insert the new rows, `UPDATE notification_policy SET …`, audit `NOTIFICATION_POLICY_UPDATED` with the new values as payload, return `getPolicy()`. Controller: `GET/PUT /api/t/{tenantSlug}/admin/notification-policy`.

- [ ] **Step 5: Run** `PolicyAdminTest`, `--tests "co.ara.onboarding.provisioning.*"`, `--tests "co.ara.onboarding.architecture.RlsCoverageTest"`. PASS.
- [ ] **Step 6: Commit** — `feat(notification): tenant deadline horizons and the automatic-reminder policy`.

---

### Task 22: Risk alerts from the SLA sweep

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/{RiskChanged,RiskNotifications}.java`
- Modify: `backend/src/main/java/co/ara/onboarding/sla/{SlaSweepService,SlaClockRepository}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/RiskAlertTest.java`

**Interfaces:**
- Produces:
  ```java
  public record RiskChanged(UUID clockId, UUID caseId, UUID stageId, State state, double remainingDays, int targetDays) {  // notification
      public enum State { AT_RISK, BREACHED }
  }
  // SlaClockRepository:
  int stampAtRiskAlert(UUID id, Instant at);   // where at_risk_alerted_at is null and breached_at is null and stopped_at is null
  // SlaSweepService: public int stampAtRisk()   (gated SLA_VIEW, MANDATORY), called from sweep() after stampBreaches()
  ```

- [ ] **Step 1: Failing `RiskAlertTest`.** Arrange like `SlaSweepBreachTest` (a case with a 3-day SLA, owner and a participant holding CASE_VIEW ALL), then move time with `clock.advance(...)` and run `sla.sweepAndEmail(t)`:
  - `aClockCrossingTheAtRiskThresholdAlertsOnce` — advance until ≤ 1.0 business day remains (default `at_risk_days`) → one `RISK_CHANGED` row each for owner and participant, tone `WARN`, title `<case> is at risk`; sweep again → no new rows.
  - `aBreachAlertsOnceWithRiskTone` — advance past the target → one more row each, tone `RISK`, title `<case> breached its SLA`.
  - `aClockThatBreachesBeforeAnyAtRiskSweepSendsOnlyTheBreach` — first sweep after a long jump → only `BREACHED`.
  - `aNewStageVisitReArms` — complete the stage, enter the next one with its own SLA, cross its threshold → a new `AT_RISK` row.
  - `aPausedClockIsNotAtRisk` — a hold (pause) holding remaining above the threshold → nothing.

  Use the working-day arithmetic note from sub-project 6's `SlaSweepBreachTest` when choosing `advance` amounts (weekends count zero). Run — FAIL.

- [ ] **Step 2: Repository and sweep.**

```java
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SlaClock c set c.atRiskAlertedAt = :at, c.updatedAt = :at where c.id = :id "
            + "and c.atRiskAlertedAt is null and c.breachedAt is null and c.stoppedAt is null")
    int stampAtRiskAlert(@Param("id") UUID id, @Param("at") Instant at);
```

  `SlaSweepService` gains `ApplicationEventPublisher events`. In `stampBreaches`, after `SLA_BREACHED` is recorded: `events.publishEvent(new RiskChanged(c.getId(), c.getCaseId(), c.getStageId(), RiskChanged.State.BREACHED, 0, c.getTargetDays()));`. New method:

```java
    /** 6B spec 6.1: the first sweep that finds a live clock at risk stamps it and alerts once. */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public int stampAtRisk() {
        Instant now = Instant.now(clock);
        double threshold = policies.current().atRiskDays();
        Specification<SlaClock> candidates = (r, q, cb) -> cb.and(cb.isNull(r.get("stoppedAt")),
                cb.isNull(r.get("breachedAt")), cb.isNull(r.get("atRiskAlertedAt")));
        List<SlaClock> open = authorizedQuery.findAll(clocks, SlaClock.class, PermissionKeys.SLA_VIEW, candidates,
                Pageable.unpaged()).getContent();
        // pausesByClock: load exactly as stampBreaches does
        int stamped = 0;
        for (SlaClock c : open) {
            double remaining = c.getTargetDays() - reader.elapsed(c, pausesByClock.getOrDefault(c.getId(), List.of()), now);
            if (remaining <= 0 || remaining > threshold + 1e-9) continue;
            if (clocks.stampAtRiskAlert(c.getId(), now) != 1) continue;
            events.publishEvent(new RiskChanged(c.getId(), c.getCaseId(), c.getStageId(), RiskChanged.State.AT_RISK,
                    remaining, c.getTargetDays()));
            stamped++;
        }
        return stamped;
    }
```

  (Use the policy accessor `SlaPolicyReader` actually exposes — read it first; the field is `policies`.) `sweep()` becomes `stampBreaches(); stampAtRisk(); notify(escalateOverdue()); warnOfUndeliverableEscalations();`.

  The at-risk stamp is a write to `sla`'s own table under `sla.view` — the same shape as `stampBreach`, which `SystemPermissions`' javadoc already describes ("every write the sweep makes goes to sla's own tables through its own gated service methods").

- [ ] **Step 3: Listener.**

```java
@Component
public class RiskNotifications {
    // pipeline, facts, constructor …

    @EventListener
    public void on(RiskChanged e) {
        var kase = facts.caseFacts(e.caseId());
        var stage = facts.stage(e.stageId());
        boolean breached = e.state() == RiskChanged.State.BREACHED;
        String body = breached
                ? "Stage " + stage.name() + " passed its " + e.targetDays() + "-business-day SLA."
                : "Stage " + stage.name() + ": " + String.format(java.util.Locale.ROOT, "%.1f", e.remainingDays())
                  + " of " + e.targetDays() + " business days remain.";
        var draft = new NotificationPipeline.Draft(NotificationType.RISK_CHANGED, "sla_clock", e.clockId(), kase.id(),
                Text.clip(kase.name(), 80) + (breached ? " breached its SLA" : " is at risk"), body,
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), breached ? Tone.RISK : Tone.WARN,
                "RISK:" + e.clockId() + ":" + e.state());
        pipeline.deliver(draft, facts.caseAudience(kase.id()), null,
                new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id()));
    }
}
```

- [ ] **Step 4: Run** `RiskAlertTest`, `--tests "co.ara.onboarding.sla.*"`, `--tests "co.ara.onboarding.architecture.*"` (`noNotificationDependencyOnSla` must stay green: `RiskChanged` lives in `notification`). PASS.
- [ ] **Step 5: Commit** — `feat(sla): alert the case audience when a stage goes at risk or breaches` (body: Q19, spec §6.1, plan amendment 2).

---

### Task 23: The notification sweep — task overdue and deadline approaching

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/{DeadlineCandidates,NotificationSweepService}.java`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/NotificationSweepJob.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/DeadlineSweepTest.java`

**Interfaces:**
- Consumes: `PolicyReader` (21), `NotificationPipeline` (10), `BusinessCalendar`.
- Produces:
  ```java
  @Component public class DeadlineCandidates {       // RLS-bound SQL; plan amendment 4
      public record Dated(UUID id, UUID caseId, UUID ownerUserId, LocalDate date, String label) {}
      public List<Dated> openTasksDueBefore(LocalDate today);        // owner = assignee
      public List<Dated> openTasksDueOnOrAfter(LocalDate today);
      public List<Dated> openMilestonesDueOnOrAfter(LocalDate today); // owner = milestone owner; label = definition name
      public List<Dated> openRequestsDue();                          // OPEN, due_at not null; date = due_at (tenant-local, set by caller); owner = requested_by
  }
  @Service public class NotificationSweepService {
      @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public void sweep();
      // package-visible steps: taskOverdue(), deadlines(); Task 24 adds expiries(); Task 25 autoReminders()
  }
  @Component public class NotificationSweepJob { public void runAll(); public boolean runOne(UUID tenantId); }
  ```

- [ ] **Step 1: Failing `DeadlineSweepTest`.** Drive `NotificationSweepJob.runOne(t)` (it wraps `TenantJobRunner.forTenant("notification-sweep", …)`) and `dispatch.runOne(t)` where email is asserted. Set due dates with owner SQL relative to `calendar.today()` read inside `runAs`, as `EscalationDeliveryTest.overdue` does. Tests:
  - `anOverdueTaskNotifiesItsAssigneeOnce` — due yesterday → one `TASK_OVERDUE` row (tone `WARN`, key `TASK_OVERDUE:<id>:<due>`); a second sweep → none.
  - `movingTheDueDateReArmsTheOverdueNotice`.
  - `aTaskDueWithinTheHorizonGetsOneDeadlineNotice` — default horizon 2 business days; due in 1 business day → one `DEADLINE_APPROACHING` row; the next sweep → none.
  - `whenSeveralLeadsMatchOnlyTheSmallestIsSentAndTheRestAreConsumed` — set `TASK_DUE` leads to `[1, 5]`; a new task due in 1 business day → one row (lead 1) plus one invisible marker (lead 5); a later sweep sends nothing.
  - `aMilestoneAndADocumentRequestAreRemindedToTheirOwners`.
  - `aCompletedOrCancelledTaskIsNeverReminded`.
  - `deadlineIsJudgedInTheTenantZone` (Review Focus 3) — tenant timezone `Pacific/Auckland` (owner SQL on `business_calendar`); a task due on the Auckland date two business days ahead of Auckland-today is reminded when the UTC date would say three. Choose the instants with `clock.advance` so the Auckland and UTC dates differ at sweep time; assert on the dedupe key's date.
  - `anAssigneeWhoTurnedTheTypeOffGetsNothingButTheMarkersStillBurn` — preference `DEADLINE_APPROACHING (false,false)` for a two-lead case; re-enable; next sweep sends nothing for the consumed larger lead.

  Run — FAIL.

- [ ] **Step 2: `DeadlineCandidates`.** Confirm table/column names (`task.status`, `task.due_date`, `task.assignee_id`, `milestone.status`, `milestone.due_date`, `milestone.owner_user_id`, `document_request.status`, `due_at`, `requested_by`).

```java
@Component
public class DeadlineCandidates {

    public record Dated(UUID id, UUID caseId, UUID ownerUserId, LocalDate date, String label) {}

    private final JdbcTemplate jdbc;
    DeadlineCandidates(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Dated> DATED = (rs, i) -> new Dated(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getString(5));

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openTasksDueBefore(LocalDate today) {
        return jdbc.query("""
                SELECT id, case_id, assignee_id, due_date, title FROM task
                 WHERE status NOT IN ('COMPLETED','CANCELLED') AND due_date < ? AND assignee_id IS NOT NULL""",
                DATED, today);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openTasksDueOnOrAfter(LocalDate today) {
        return jdbc.query("""
                SELECT id, case_id, assignee_id, due_date, title FROM task
                 WHERE status NOT IN ('COMPLETED','CANCELLED') AND due_date >= ? AND assignee_id IS NOT NULL""",
                DATED, today);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Dated> openMilestonesDueOnOrAfter(LocalDate today) {
        return jdbc.query("""
                SELECT m.id, m.case_id, m.owner_user_id, m.due_date, d.name
                  FROM milestone m JOIN milestone_definition d ON d.id = m.milestone_definition_id
                  JOIN onboarding_case c ON c.id = m.case_id
                 WHERE m.status IN ('PENDING','ACTIVE','BLOCKED') AND m.due_date >= ? AND m.owner_user_id IS NOT NULL
                   AND c.status = 'ACTIVE'""", DATED, today);
    }

    /** date is filled by the caller from due_at in the tenant zone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> openRequestsDue() {
        return jdbc.queryForList("""
                SELECT r.id, r.case_id, r.requested_by, r.due_at, r.category FROM document_request r
                  JOIN onboarding_case c ON c.id = r.case_id
                 WHERE r.status = 'OPEN' AND r.due_at IS NOT NULL AND c.status = 'ACTIVE'""");
    }
}
```

  (A held case — `ON_HOLD` — is not reminded about deadlines: its clock is paused and its due dates move on resume.)

- [ ] **Step 3: `NotificationSweepService`.**

```java
/**
 * 6B spec 6.2. Candidates come from RLS-bound SQL; every notification is still gated per recipient
 * by RecipientAccess inside the pipeline (plan amendment 4). Gated sla.view, the job marker (plan
 * amendment 9). Every send carries a dedupe key, so repeat runs are free and a moved date re-arms.
 */
@Service
public class NotificationSweepService {

    private final DeadlineCandidates candidates;
    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;
    private final PolicyReader policies;
    private final BusinessCalendar calendar;
    // constructor …

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void sweep() {
        LocalDate today = calendar.today();
        var policy = policies.current();
        taskOverdue(today);
        deadlines(today, policy);
        // Task 24: expiries(today, policy);  Task 25: autoReminders(policy);
    }

    void taskOverdue(LocalDate today) {
        String slug = facts.tenantSlug();
        for (var t : candidates.openTasksDueBefore(today)) {
            var kase = facts.caseFacts(t.caseId());
            int late = calendar.businessDaysBetween(t.date(), today);
            var draft = new NotificationPipeline.Draft(NotificationType.TASK_OVERDUE, "task", t.id(), kase.id(),
                    "Overdue: " + Text.clip(t.label(), 90),
                    kase.name() + ": due " + t.date() + (late > 0 ? ", " + late + " business day(s) ago." : "."),
                    Links.caseLink(slug, kase.customerId(), kase.id()), Tone.WARN,
                    "TASK_OVERDUE:" + t.id() + ":" + t.date());
            pipeline.deliver(draft, List.of(t.ownerUserId()), null,
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, t.id()));
        }
    }

    void deadlines(LocalDate today, PolicyReader.Policy policy) {
        for (var t : candidates.openTasksDueOnOrAfter(today)) {
            remind(HorizonKind.TASK_DUE, "task", t, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, t.id()));
        }
        for (var m : candidates.openMilestonesDueOnOrAfter(today)) {
            remind(HorizonKind.MILESTONE_DUE, "milestone", m, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, m.caseId()));
        }
        for (var r : candidates.openRequestsDue()) {
            LocalDate due = calendar.localDate(((java.sql.Timestamp) r.get("due_at")).toInstant());
            if (due.isBefore(today)) continue;
            var dated = new DeadlineCandidates.Dated((UUID) r.get("id"), (UUID) r.get("case_id"),
                    (UUID) r.get("requested_by"), due, DocumentNotifications.humanise((String) r.get("category")));
            remind(HorizonKind.DOCUMENT_REQUEST_DUE, "document_request", dated, today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, dated.caseId()));
        }
    }

    /** The smallest matching lead is sent; larger matching leads are consumed so they never fire late. */
    private void remind(HorizonKind kind, String subjectType, DeadlineCandidates.Dated item, LocalDate today,
                        PolicyReader.Policy policy, NotificationPipeline.Visibility visibility) {
        int away = kind.businessDays ? calendar.businessDaysBetween(today, item.date())
                : (int) java.time.temporal.ChronoUnit.DAYS.between(today, item.date());
        List<Integer> matching = policy.horizons().get(kind).stream().filter(lead -> away <= lead).sorted().toList();
        if (matching.isEmpty()) return;
        var kase = facts.caseFacts(item.caseId());
        String when = away == 0 ? "today" : "in " + away + (kind.businessDays ? " business day(s)" : " day(s)");
        for (int i = 0; i < matching.size(); i++) {
            int lead = matching.get(i);
            var draft = new NotificationPipeline.Draft(NotificationType.DEADLINE_APPROACHING, subjectType, item.id(),
                    kase.id(), Text.clip(item.label(), 90) + " is due " + when,
                    kase.name() + " (" + kase.customerName() + "): due " + item.date() + ".",
                    Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.WARN,
                    "DEADLINE:" + kind + ":" + item.id() + ":" + item.date() + ":" + lead);
            if (i == 0) pipeline.deliver(draft, List.of(item.ownerUserId()), null, visibility);
            else pipeline.consume(draft, item.ownerUserId());
        }
    }
}
```

  Note `remind` sends the smallest lead even when the recipient has the type off (the pipeline skips it) and **always** consumes the larger ones — that is what `anAssigneeWhoTurnedTheTypeOffGetsNothingButTheMarkersStillBurn` pins. The smallest lead is retried each sweep while it still matches, which is harmless (`deliver` does nothing for an opted-out user) and correct (an opt-in before the due date gets the current notice).

  `DocumentNotifications.humanise` must be `static` and package-visible (it is, from Task 16).

- [ ] **Step 4: `NotificationSweepJob`.**

```java
@Component
public class NotificationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweepJob.class);
    private final TenantJobRunner runner;
    private final NotificationSweepService sweep;
    private final EmailDispatchJob dispatch;
    // constructor …

    @Scheduled(fixedDelayString = "${app.notifications.sweep-interval:PT1H}", initialDelayString = "PT2M")
    public void scheduled() {
        try { runAll(); } catch (RuntimeException e) { log.error("Notification sweep failed", e); }
    }

    public void runAll() { runner.forEachTenant("notification-sweep", t -> sweep.sweep()); }

    /** Sweeps one tenant, then dispatches its email (dev endpoint, tests). */
    public boolean runOne(UUID tenantId) {
        boolean ran = runner.forTenant("notification-sweep", tenantId, t -> sweep.sweep());
        dispatch.runOne(tenantId);
        return ran;
    }
}
```

- [ ] **Step 5: Run** `DeadlineSweepTest`, `--tests "co.ara.onboarding.notification.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 6: Commit** — `feat(notification): overdue-task and deadline-approaching reminders on tenant horizons` (body: spec §6.2, Review Focus 3, the consumed-marker rule).

---

### Task 24: Expiry and renewal reminders

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{DeadlineCandidates,NotificationSweepService}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/ExpirySweepTest.java`

**Interfaces:**
- Produces:
  ```java
  public List<Dated> liveDocumentsExpiring();      // DeadlineCandidates: status <> 'RETIRED', expires_at not null; owner = case owner; date filled by caller
  public List<Map<String, Object>> liveAgreementsWithDates();   // status <> 'CANCELLED' and (expires_at or renewal_date not null)
  void expiries(LocalDate today, PolicyReader.Policy policy);   // NotificationSweepService
  ```

- [ ] **Step 1: Failing `ExpirySweepTest`.** Set dates with owner SQL. Tests:
  - `aDocumentExpiringWithinThirtyDaysRemindsTheCaseOwnerOnceAtThirty` — expires in 25 days → one `EXPIRY_RENEWAL` row (lead 30; 14 and 7 do not match yet); advance the clock 12 days → a lead-14 row; advance 8 → lead 7.
  - `aRetiredDocumentIsNeverReminded`.
  - `anAgreementExpiryRemindsTheAgreementAndCaseOwners`.
  - `renewalCountsBackFromTheNoticeDeadline` — `renewal_date` in 40 days with `notice_period_days = 15` → the decision deadline is in 25 days → a lead-30 row titled with "Renewal decision due".
  - `aCancelledAgreementIsNeverReminded`.
  - `aTargetedDocumentsExpiryDoesNotReachAnOwnerOutsideItsAudience` — the RecipientAccess gate again, now from the sweep.

  Run — FAIL.

- [ ] **Step 2: Candidates.**

```java
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> liveDocumentsExpiring() {
        return jdbc.queryForList("""
                SELECT d.id, d.case_id, c.owner_user_id, d.expires_at, d.name FROM document d
                  JOIN onboarding_case c ON c.id = d.case_id
                 WHERE d.status <> 'RETIRED' AND d.expires_at IS NOT NULL AND c.owner_user_id IS NOT NULL""");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> liveAgreementsWithDates() {
        return jdbc.queryForList("""
                SELECT id, case_id, owner_user_id, name, expires_at, renewal_date, notice_period_days FROM agreement
                 WHERE status <> 'CANCELLED' AND (expires_at IS NOT NULL OR renewal_date IS NOT NULL)""");
    }
```

  (Documents without a case — if `document.case_id` is nullable — have no case owner to remind and are skipped by the join; say so in the method's javadoc.)

- [ ] **Step 3: `expiries`.** Called from `sweep()` after `deadlines`.

```java
    void expiries(LocalDate today, PolicyReader.Policy policy) {
        for (var d : candidates.liveDocumentsExpiring()) {
            LocalDate date = calendar.localDate(((java.sql.Timestamp) d.get("expires_at")).toInstant());
            UUID id = (UUID) d.get("id");
            expiry(HorizonKind.DOCUMENT_EXPIRY, "document", id, (UUID) d.get("case_id"), List.of((UUID) d.get("owner_user_id")),
                    date, "\"" + Text.clip((String) d.get("name"), 80) + "\" expires", today, policy,
                    new NotificationPipeline.Visibility(PermissionKeys.DOCUMENT_VIEW, Document.class, id));
        }
        for (var a : candidates.liveAgreementsWithDates()) {
            UUID id = (UUID) a.get("id");
            UUID caseId = (UUID) a.get("case_id");
            var owners = new ArrayList<UUID>();
            if (a.get("owner_user_id") != null) owners.add((UUID) a.get("owner_user_id"));
            UUID caseOwner = facts.caseFacts(caseId).ownerUserId();
            if (caseOwner != null) owners.add(caseOwner);
            var visibility = new NotificationPipeline.Visibility(PermissionKeys.AGREEMENT_VIEW, Agreement.class, id);
            String name = Text.clip((String) a.get("name"), 80);
            if (a.get("expires_at") != null) {
                expiry(HorizonKind.AGREEMENT_EXPIRY, "agreement", id, caseId, owners, ((java.sql.Date) a.get("expires_at")).toLocalDate(),
                        name + " expires", today, policy, visibility);
            }
            if (a.get("renewal_date") != null) {
                int notice = a.get("notice_period_days") == null ? 0 : ((Number) a.get("notice_period_days")).intValue();
                LocalDate decision = ((java.sql.Date) a.get("renewal_date")).toLocalDate().minusDays(notice);
                expiry(HorizonKind.AGREEMENT_RENEWAL, "agreement", id, caseId, owners, decision,
                        "Renewal decision due for " + name, today, policy, visibility);
            }
        }
    }

    private void expiry(HorizonKind kind, String subjectType, UUID id, UUID caseId, List<UUID> recipients, LocalDate date,
                        String what, LocalDate today, PolicyReader.Policy policy, NotificationPipeline.Visibility visibility) {
        long away = java.time.temporal.ChronoUnit.DAYS.between(today, date);
        if (away < 0) return;
        List<Integer> matching = policy.horizons().get(kind).stream().filter(lead -> away <= lead).sorted().toList();
        if (matching.isEmpty()) return;
        var kase = facts.caseFacts(caseId);
        for (int i = 0; i < matching.size(); i++) {
            var draft = new NotificationPipeline.Draft(NotificationType.EXPIRY_RENEWAL, subjectType, id, kase.id(),
                    what + (away == 0 ? " today" : " in " + away + " day(s)"),
                    kase.name() + " (" + kase.customerName() + "): " + date + ".",
                    Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.WARN,
                    "EXPIRY:" + kind + ":" + id + ":" + date + ":" + matching.get(i));
            if (i == 0) pipeline.deliver(draft, recipients, null, visibility);
            else for (UUID r : recipients) pipeline.consume(draft, r);
        }
    }
```

  (If the agreement table stores `expires_at`/`renewal_date` as `date`, JDBC returns `java.sql.Date`; adjust casts to whatever `queryForList` actually yields — check one row in the test.)

- [ ] **Step 4: Run** `ExpirySweepTest`, `DeadlineSweepTest`. PASS.
- [ ] **Step 5: Commit** — `feat(notification): expiry and renewal reminders for documents and agreements` (body: PRD §9 renewal reminders; renewal counts back from the notice deadline).

---

### Task 25: Automatic customer reminders

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/authz/SystemPermissions.java`
- Modify: `backend/src/test/java/co/ara/onboarding/security/SystemActorTest.java`
- Modify: `backend/src/main/java/co/ara/onboarding/document/DocumentRequestService.java` (`remindAutomatically`)
- Modify: `backend/src/main/java/co/ara/onboarding/notification/{DeadlineCandidates,NotificationSweepService}.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/AutoReminderTest.java`

**Interfaces:**
- Produces:
  ```java
  // DocumentRequestService
  @RequirePermission(DOCUMENT_REQUEST) @Transactional public boolean remindAutomatically(UUID requestId);
  // DeadlineCandidates
  public List<Map<String, Object>> remindableRequests(int max);   // OPEN, contact ACTIVE, reminders_sent < max, not reminded in 24h
  // NotificationSweepService
  void autoReminders(PolicyReader.Policy policy);
  ```

- [ ] **Step 1: The system permission, test first.** In `SystemActorTest.theSystemActorHoldsExactlyTheJobPermissionsAtAll`, add `PermissionKeys.DOCUMENT_REQUEST` to the `containsExactlyInAnyOrder` set and `assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_REQUEST)).containsExactly(Scope.ALL);`. Run — FAIL. Then `SystemPermissions.forJobs()` returns `Map.of(CASE_VIEW, ALL, TASK_VIEW, ALL, SLA_VIEW, ALL, DOCUMENT_REQUEST, ALL)` and its javadoc gains: *"document.request is the one write permission (6B spec 6.2): the automatic-reminder step calls DocumentRequestService.remindAutomatically and nothing else. It also gates create/fulfil/withdraw; the containment is structural -- NotificationSweepService names no other DocumentRequestService method, which AutoReminderTest asserts."* Run — PASS.

- [ ] **Step 2: Failing `AutoReminderTest`** (`@Import(FlakyEmail.class)`, `FlakyEmail.reset()` in `@AfterEach`). Arrange with `support.openRequestWithContact(slug)` (Task 7). Turn the policy on with owner SQL (`update notification_policy set auto_remind_enabled = true`). Tests:
  - `offByDefaultNothingIsSent`.
  - `afterTheIntervalTheContactIsRemindedAutomatically` — advance the clock past 3 business days; `NotificationSweepJob.runOne(t)` → one `CUSTOMER_REMINDER` outbox row (dispatched), `reminders_sent = 1`, an audit `document_request.reminded` with `timeline_visible = true` and payload `automatic: true`, summary containing "(automatic)".
  - `beforeTheIntervalNothingIsSent`.
  - `itStopsAtTheMaximum` — max 2: three intervals → exactly two reminders.
  - `theManualButtonsTwentyFourHourFloorIsShared` — a manual `remind` just now → the sweep sends nothing even though the interval elapsed from `requested_at`.
  - `aClosedRequestOrRetiredContactIsNeverReminded`.
  - `aStageWriteScopeDoesNotBlockTheAutomaticReminder` — the case's stage is `OWNER_ONLY` → still reminded (spec §6.2).
  - `theSweepCallsNoOtherDocumentRequestServiceMethod` — ArchUnit, in-test: `noClasses().that().resideInAPackage("..notification..").should().callMethodWhere(target owner is DocumentRequestService and name is not "remindAutomatically")`; write it with `JavaCall` predicates (`target(owner(assignableTo(DocumentRequestService.class))).and(not(target(name("remindAutomatically"))))`).

  Run — FAIL.

- [ ] **Step 3: `remindAutomatically`.** In `DocumentRequestService`, refactor the message-building part of `remind` into `private record ReminderMessage(String to, String subject, String body)` + `private ReminderMessage message(Case c, DocumentRequest dr, CustomerContact contact)`, used by both paths. Then:

```java
    /**
     * 6B spec 6.2: the sweep's automatic reminder. Never throws for a not-remindable request --
     * it runs inside the sweep's tenant transaction, where an escaping exception would roll back
     * every other reminder (plan amendment 13). Skips StageWriteScopeGuard: a stage's write scope
     * governs internal collaborators; this is tenant policy acting (the portal-write precedent).
     */
    @RequirePermission(PermissionKeys.DOCUMENT_REQUEST)
    @Transactional
    public boolean remindAutomatically(UUID requestId) {
        var found = authorizedQuery.findAll(requests, DocumentRequest.class, PermissionKeys.DOCUMENT_REQUEST,
                (r, q, cb) -> cb.equal(r.get("id"), requestId), PageRequest.of(0, 1)).getContent();
        if (found.isEmpty()) return false;
        DocumentRequest dr = found.get(0);
        if (dr.getStatus() != DocumentRequestStatus.OPEN || dr.getRequestedOfContactId() == null) return false;
        var contacts = authorizedQuery.findAll(this.contacts, CustomerContact.class, PermissionKeys.DOCUMENT_REQUEST,
                (r, q, cb) -> cb.equal(r.get("id"), dr.getRequestedOfContactId()), PageRequest.of(0, 1)).getContent();
        if (contacts.isEmpty() || contacts.get(0).getStatus() != ContactStatus.ACTIVE) return false;
        var cases = authorizedQuery.findAll(this.cases, Case.class, PermissionKeys.DOCUMENT_REQUEST,
                (r, q, cb) -> cb.equal(r.get("id"), dr.getCaseId()), PageRequest.of(0, 1)).getContent();
        if (cases.isEmpty()) return false;
        Instant now = Instant.now(clock);
        Instant notAfter = now.minus(REMINDER_INTERVAL);
        if (dr.getLastRemindedAt() != null && dr.getLastRemindedAt().isAfter(notAfter)) return false;
        var msg = message(cases.get(0), dr, contacts.get(0));
        int reminder = dr.getRemindersSent() + 1;
        // The guard comes BEFORE the audit row here, unlike the manual path: a lost race returns
        // false instead of throwing, so nothing would roll an earlier audit row back. The counter
        // update is not itself an audited effect, so cause-before-effect still holds.
        if (requests.markReminded(requestId, now, notAfter) == 0) return false;
        audit.record(AuditActions.DOCUMENT_REQUEST_REMINDED, "onboarding_case", dr.getCaseId(),
                "Reminded the customer about document request " + requestId + " (automatic)",
                Map.of("requestId", requestId.toString(), "reminder", reminder, "automatic", true));
        events.publishEvent(new CustomerReminderQueued(requestId, dr.getCaseId(), dr.getRequestedOfContactId(),
                msg.to(), msg.subject(), msg.body(), true));
        return true;
    }
```

  The manual `remind` keeps its existing order (audit, then the guard that throws and rolls the audit row back) and its payload gains nothing; the automatic one carries `"automatic": true`.

- [ ] **Step 4: Candidates and the sweep step.**

```java
    /** OPEN requests with an ACTIVE contact, under the cap, not reminded in the last 24h. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Map<String, Object>> remindableRequests(int max) {
        return jdbc.queryForList("""
                SELECT r.id, r.requested_at, r.last_reminded_at FROM document_request r
                  JOIN customer_contact k ON k.id = r.requested_of_contact_id AND k.status = 'ACTIVE'
                 WHERE r.status = 'OPEN' AND r.reminders_sent < ?
                   AND (r.last_reminded_at IS NULL OR r.last_reminded_at < now() - interval '24 hours')""", max);
    }
```

  `now()` here is the database clock; the test's `MutableClock` moves the application clock only — so filter the 24-hour floor in Java as well (`remindAutomatically` re-checks it against `Instant.now(clock)`, which is what makes the test deterministic). Then in `NotificationSweepService`:

```java
    void autoReminders(PolicyReader.Policy policy) {
        if (!policy.autoRemindEnabled()) return;
        LocalDate today = calendar.today();
        for (var r : candidates.remindableRequests(policy.max())) {
            Instant since = r.get("last_reminded_at") != null
                    ? ((java.sql.Timestamp) r.get("last_reminded_at")).toInstant()
                    : ((java.sql.Timestamp) r.get("requested_at")).toInstant();
            if (calendar.businessDaysBetween(calendar.localDate(since), today) < policy.intervalDays()) continue;
            documentRequests.remindAutomatically((UUID) r.get("id"));
        }
    }
```

  `NotificationSweepService` gains `DocumentRequestService documentRequests`; `sweep()` calls `autoReminders(policy)` last.

- [ ] **Step 5: Run** `AutoReminderTest`, `CustomerReminderOutboxTest`, `--tests "co.ara.onboarding.security.*"`, `--tests "co.ara.onboarding.document.*"`, `--tests "co.ara.onboarding.architecture.*"`. PASS.
- [ ] **Step 6: Commit** — `feat(document): automatic customer reminders on the tenant's cadence` (body: spec §6.2; the system actor's one write permission, pinned by SystemActorTest; plan amendment 13).

---

### Task 26: Daily and weekly digests

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/notification/DigestService.java`
- Create: `backend/src/main/java/co/ara/onboarding/scheduling/DigestJob.java`
- Create: `backend/src/test/java/co/ara/onboarding/notification/DigestScheduleTest.java`

**Interfaces:**
- Produces:
  ```java
  @Service public class DigestService {
      @RequirePermission(SLA_VIEW) @Transactional(propagation = MANDATORY) public int run();   // digests queued
  }
  @Component public class DigestJob { public void runAll(); public int runOne(UUID tenantId); }   // runOne also dispatches
  ```

- [ ] **Step 1: Failing `DigestScheduleTest`** (`@Import(FlakyEmail.class)`; read sent mail from `FlakyEmail.sent`). Helper `advanceTo(ZoneId zone, DayOfWeek day, LocalTime time)` that advances `clock` to the next instant ≥ now that is `day` at `time` in `zone`. Users set `DAILY`/`WEEKLY` via owner SQL on `notification_settings`; pending rows are produced by real events (assign tasks to the user) so they arrive `DIGEST_PENDING`. Tests:
  - `aDailyDigestArrivesAtEightOnAWorkingDayAndCarriesEveryPendingRow` — three pending rows; at Tuesday 07:55 tenant-local → nothing; at 08:05 → one `DIGEST` outbox row, three `email_outbox_item` rows, the three notifications now `DIGESTED`; the email (after dispatch) subject `Your daily digest: 3 notifications`, body lists each title with an absolute link.
  - `aSecondRunTheSameDaySendsNothing`.
  - `noPendingRowsMeansNoEmailButTheDayIsStillMarked`.
  - `aWeeklyDigestArrivesOnTheFirstWorkingDayOfTheWeek` — Monday a holiday (insert into `business_holiday`) → arrives Tuesday 08:00, not Monday.
  - `noDigestOnANonWorkingDay` — Saturday 09:00 → nothing for a daily user.
  - `aDailyDigestFollowsTheTenantZone` (Review Focus 3) — timezone `Pacific/Auckland`; the digest arrives at 08:00 Auckland (i.e. ~19:00 UTC the previous day), not 08:00 UTC.
  - `switchingToImmediateFlushesPendingOnce` (Review Focus 5) — two pending rows; PUT preferences with cadence `IMMEDIATE`; run the digest job at any time → exactly one digest containing both; a second run → nothing; a new event → an immediate `NOTIFICATION` outbox row, not a digest.
  - `anInactiveUsersPendingRowsAreNotEmailed` — deactivate the user → no digest row (rows stay `DIGEST_PENDING`).

  Run — FAIL.

- [ ] **Step 2: `DigestService`.**

```java
/**
 * 6B spec 6.3. Daily at 08:00 tenant-local on working days; weekly at 08:00 on the ISO week's
 * first working day; a user who switched back to IMMEDIATE has pending rows flushed as one digest
 * on the next run. Gated sla.view, the job marker (plan amendment 9).
 */
@Service
public class DigestService {

    static final LocalTime SEND_AT = LocalTime.of(8, 0);

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final OutboxWriter outbox;
    private final PublicBaseUrl baseUrl;
    private final SubjectFacts facts;
    // constructor …

    private record Candidate(UUID userId, EmailCadence cadence, Instant lastDigestAt) {}

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public int run() {
        Instant now = Instant.now(clock);
        LocalDate today = calendar.today();
        Instant sendAt = calendar.startOfDay(today).plus(Duration.between(LocalTime.MIDNIGHT, SEND_AT));
        boolean workingDay = calendar.businessDaysBetween(today, today.plusDays(1)) == 1;
        boolean firstWorkingDayOfWeek = workingDay && calendar.businessDaysBetween(
                today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today) == 0;
        boolean pastSendTime = !now.isBefore(sendAt);
        int queued = 0;
        for (Candidate c : candidates()) {
            boolean due = switch (c.cadence()) {
                case IMMEDIATE -> true;                                     // a switched user's leftovers
                case DAILY -> workingDay && pastSendTime && (c.lastDigestAt() == null || c.lastDigestAt().isBefore(sendAt));
                case WEEKLY -> firstWorkingDayOfWeek && pastSendTime && (c.lastDigestAt() == null || c.lastDigestAt().isBefore(sendAt));
            };
            if (!due) continue;
            if (send(c, now)) queued++;
        }
        return queued;
    }

    /** Users with pending rows, plus daily/weekly users (so last_digest_at advances on an empty day). */
    private List<Candidate> candidates() {
        return jdbc.query("""
                SELECT u.id, COALESCE(s.email_cadence, 'IMMEDIATE'), s.last_digest_at
                  FROM app_user u LEFT JOIN notification_settings s ON s.user_id = u.id
                 WHERE u.id IN (SELECT recipient_user_id FROM notification WHERE email_state = 'DIGEST_PENDING')
                    OR s.email_cadence IN ('DAILY','WEEKLY')""",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), EmailCadence.valueOf(rs.getString(2)),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()));
    }

    private boolean send(Candidate c, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO notification_settings (id, tenant_id, user_id, email_cadence, last_digest_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_settings_one_per_user
                DO UPDATE SET last_digest_at = EXCLUDED.last_digest_at, updated_at = EXCLUDED.updated_at""",
                Uuid7.generate(), TenantContext.getRequired(), c.userId(), c.cadence().name(), at, at, at);
        Optional<String> email = facts.activeInternalEmail(c.userId());
        if (email.isEmpty()) return false;
        var rows = jdbc.queryForList("""
                SELECT id, type, title, link_path FROM notification
                 WHERE recipient_user_id = ? AND email_state = 'DIGEST_PENDING' ORDER BY type, id DESC""", c.userId());
        if (rows.isEmpty()) return false;
        String heading = switch (c.cadence()) {
            case DAILY -> "Your daily digest: ";
            case WEEKLY -> "Your weekly digest: ";
            case IMMEDIATE -> "Your pending notifications: ";   // a user who switched back to immediate
        };
        StringBuilder body = new StringBuilder("Your notifications since your last digest:\n");
        String lastType = null;
        for (var r : rows) {
            String type = (String) r.get("type");
            if (!type.equals(lastType)) {
                body.append("\n").append(NotificationCatalog.of(NotificationType.valueOf(type)).label()).append("\n");
                lastType = type;
            }
            body.append("- ").append(r.get("title")).append("\n  ").append(baseUrl.absolute((String) r.get("link_path"))).append("\n");
        }
        UUID outboxId = outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.DIGEST, email.get(), c.userId(), null, null,
                null, heading + rows.size() + " notification" + (rows.size() == 1 ? "" : "s"),
                body.toString(), null));
        for (var r : rows) {
            jdbc.update("INSERT INTO email_outbox_item (outbox_id, notification_id, tenant_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    outboxId, r.get("id"), TenantContext.getRequired(), at, at);
            jdbc.update("UPDATE notification SET email_state = 'DIGESTED', updated_at = ? WHERE id = ?", at, r.get("id"));
        }
        return true;
    }
}
```

  `businessDaysBetween(monday, today) == 0` means no working day falls in `[monday, today)`, i.e. today is the week's first working day.

- [ ] **Step 3: `DigestJob`** — the `NotificationSweepJob` shape: `@Scheduled(fixedDelayString = "${app.notifications.digest-interval:PT15M}", initialDelayString = "PT3M")`, `runAll()` over `forEachTenant("notification-digest", …)`, `runOne(tenantId)` runs the digest then `dispatch.runOne(tenantId)` and returns the queued count (capture it from inside the lambda).
- [ ] **Step 4: Run** `DigestScheduleTest`, `--tests "co.ara.onboarding.notification.*"`. PASS.
- [ ] **Step 5: Commit** — `feat(notification): daily and weekly email digests in the tenant's zone` (body: spec §6.3, Review Focus 3 and 5).

---

### Task 27: Dev levers and the isolation sweep

**Files:**
- Modify: `backend/src/main/java/co/ara/onboarding/scheduling/{DevToolsService,DevToolsController}.java`
- Modify: `frontend/e2e/support/tenant.ts` (`Api` levers)
- Create: `backend/src/test/java/co/ara/onboarding/notification/NotificationIsolationTest.java`

**Interfaces:**
- Produces:
  ```java
  // DevToolsService (each @Transactional(readOnly = true) @RequirePermission(TENANT_SETTINGS_EDIT)):
  public boolean runNotificationSweep(); public int runDigest(); public int runEmailDispatch();
  // HTTP (dev only): POST /dev/jobs/notification-sweep {"ran":bool} · /dev/jobs/digest {"queued":n} · /dev/jobs/email-dispatch {"sent":n}
  ```
  ```ts
  // e2e Api
  runNotificationSweep(): Promise<boolean>; runDigest(): Promise<number>; runEmailDispatch(): Promise<number>;
  ```

- [ ] **Step 1: Dev endpoints.** `DevToolsService` gains `NotificationSweepJob notificationSweep`, `DigestJob digests`, `EmailDispatchJob dispatch`; each new method calls `runOne(TenantContext.getRequired())`. `DevToolsController` maps the three routes. `DevToolsProfileTest` needs no change (same two classes) — run it to prove the new routes are absent outside `dev`.
- [ ] **Step 2: e2e levers** in `tenant.ts`'s `Api`, next to `runSlaSweep`, each a `this.post<…>("/dev/jobs/…")` returning the field.
- [ ] **Step 3: `NotificationIsolationTest`** (spec §10.1, invariant 10) — one short file in the `task.TaskIsolationTest` shape: tenant A's user, tenant B's ids; PUT/POST/GET against `/notifications/{id}/read`, `/admin/notification-templates/{id}`, and that a tenant-A sweep (`NotificationSweepJob.runOne(a)`) never writes a row for tenant B's overdue task. All 404 or no rows.
- [ ] **Step 4: Run** `--tests "co.ara.onboarding.scheduling.*"`, `NotificationIsolationTest`. PASS.
- [ ] **Step 5: Commit** — `test(notification): dev levers for the new jobs and a cross-tenant isolation sweep`.

---

## Phase 6 — Frontend

**Every task in this phase starts by invoking the `frontend-design` and `ui-ux-pro-max` skills** (CLAUDE.md), then reads `COMPONENTS.md` §1/§16/§17 and opens the prototype's inbox (search "Inbox" in `Onboarding Platform.dc.html`). Every task ends with `npx vitest run`, `npx tsc --noEmit` and `npm run lint`, all clean. Test conventions: replace `global.fetch` with a `vi.fn()` and build a `QueryClient` wrapper per file (no MSW, no shared render helper — see `src/lib/api/calendar.test.tsx`); page tests `vi.mock` the hooks module; every file has `afterEach(cleanup)`.

### Task 28: The API layer

**Files:**
- Regenerate: `frontend/src/lib/api/generated.ts`
- Create: `frontend/src/lib/api/notifications.ts`, `frontend/src/lib/api/notifications.test.tsx`

**Interfaces:**
- Produces:
  ```ts
  export type NotificationItem = components["schemas"]["NotificationView"];
  export type InboxPage = components["schemas"]["InboxPage"];
  export type Preferences = components["schemas"]["PreferencesView"];
  export type TypePreference = components["schemas"]["TypePreferenceView"];
  export type UpdatePreferencesRequest = components["schemas"]["UpdatePreferencesRequest"];
  export type NotificationPolicy = components["schemas"]["PolicyView"];
  export type UpdateNotificationPolicyRequest = components["schemas"]["UpdatePolicyRequest"];
  export type NotificationTemplate = components["schemas"]["TemplateView"];
  export type CreateNotificationTemplateRequest = components["schemas"]["CreateTemplateRequest"];
  export type UpdateNotificationTemplateRequest = components["schemas"]["UpdateTemplateRequest"];
  export type TemplateOption = components["schemas"]["TemplateOption"];
  export const notificationKeys: { all; count(); inbox(); preferences(); policy(); templates(); templateOptions() };
  export function useUnreadCount(enabled: boolean): UseQueryResult<number>;          // polls 60s + on focus
  export function useInbox(enabled: boolean): UseInfiniteQueryResult<InfiniteData<InboxPage>>;
  export function useMarkRead(): UseMutationResult<void, Error, string>;
  export function useMarkAllRead(): UseMutationResult<{ marked: number }, Error, void>;
  export function usePreferences(enabled: boolean): UseQueryResult<Preferences>;
  export function useUpdatePreferences(): UseMutationResult<Preferences, Error, UpdatePreferencesRequest>;  // optimistic
  export function useNotificationPolicy(): …; export function useUpdateNotificationPolicy(): …;
  export function useNotificationTemplates(): …; export function useCreateNotificationTemplate(): …;
  export function useUpdateNotificationTemplate(): UseMutationResult<NotificationTemplate, Error, { id: string; body: UpdateNotificationTemplateRequest }>;
  export function useTemplateOptions(enabled: boolean): UseQueryResult<TemplateOption[]>;
  ```

- [ ] **Step 1: Regenerate.** From `backend/`: `.\gradlew.bat openApiSpec`; from `frontend/`: `npm run generate:api`. Confirm the schema names above exist in `generated.ts` (`rg "NotificationView|InboxPage|PreferencesView|PolicyView|TemplateView" src/lib/api/generated.ts`); if springdoc named any differently, use its name in the aliases. Reordering-only diffs elsewhere are noise.

- [ ] **Step 2: Failing hook tests** (`notifications.test.tsx`, the `calendar.test.tsx` setup): `useUnreadCount` GETs `/api/t/acme/notifications/unread-count` and returns the number; it is disabled when `enabled` is false (no fetch); `useInbox` passes `cursor` from the previous page's `nextCursor` on `fetchNextPage` and stops when it is null; `useMarkRead` POSTs `/notifications/{id}/read` and invalidates `count` and `inbox`; `useMarkAllRead` likewise; `useUpdatePreferences` PUTs the full body, applies it to the cache **before** the response (optimistic), and restores the previous value when the request fails; `useUpdateNotificationPolicy` PUTs `/admin/notification-policy`; `useCreateNotificationTemplate` POSTs and invalidates `templates` and `templateOptions`. Run — FAIL.

- [ ] **Step 3: Implement.**

```ts
"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

export type NotificationItem = components["schemas"]["NotificationView"];
// … the remaining aliases from Interfaces …

export const notificationKeys = {
  all: ["notifications"] as const,
  count: () => [...notificationKeys.all, "count"] as const,
  inbox: () => [...notificationKeys.all, "inbox"] as const,
  preferences: () => [...notificationKeys.all, "preferences"] as const,
  policy: () => [...notificationKeys.all, "policy"] as const,
  templates: () => [...notificationKeys.all, "templates"] as const,
  templateOptions: () => [...notificationKeys.all, "template-options"] as const,
};

/** The badge. Real-time push is sub-project 8; until then it polls once a minute and on focus. */
export function useUnreadCount(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.count(),
    queryFn: async () => (await apiFetch<{ unreadCount: number }>("/notifications/unread-count")).unreadCount,
    enabled,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useInbox(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: notificationKeys.inbox(),
    queryFn: ({ pageParam }) =>
      apiFetch<InboxPage>(`/notifications?limit=30${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? null,
    enabled,
  });
}

function useInboxInvalidation() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: notificationKeys.count() });
    void queryClient.invalidateQueries({ queryKey: notificationKeys.inbox() });
  };
}

export function useMarkRead() {
  const invalidate = useInboxInvalidation();
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/notifications/${id}/read`, { method: "POST" }),
    onSettled: invalidate,
  });
}

export function useMarkAllRead() {
  const invalidate = useInboxInvalidation();
  return useMutation({
    mutationFn: () => apiFetch<{ marked: number }>("/notifications/read-all", { method: "POST" }),
    onSettled: invalidate,
  });
}

export function usePreferences(enabled: boolean) {
  return useQuery({ queryKey: notificationKeys.preferences(), queryFn: () => apiFetch<Preferences>("/notifications/preferences"), enabled });
}

/** Optimistic: the toggle moves at once and snaps back if the server refuses (spec 9.3). */
export function useUpdatePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdatePreferencesRequest) =>
      apiFetch<Preferences>("/notifications/preferences", { method: "PUT", body: JSON.stringify(body) }),
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.preferences() });
      const previous = queryClient.getQueryData<Preferences>(notificationKeys.preferences());
      if (previous) {
        queryClient.setQueryData<Preferences>(notificationKeys.preferences(), {
          emailCadence: body.emailCadence,
          types: (previous.types ?? []).map((tp) => {
            const next = body.types.find((b) => b.type === tp.type);
            return next ? { ...tp, inApp: next.inApp, email: next.email } : tp;
          }),
        });
      }
      return { previous };
    },
    onError: (_err, _body, context) => {
      if (context?.previous) queryClient.setQueryData(notificationKeys.preferences(), context.previous);
    },
    onSuccess: (data) => queryClient.setQueryData(notificationKeys.preferences(), data),
  });
}

// useNotificationPolicy / useUpdateNotificationPolicy: GET/PUT "/admin/notification-policy", invalidate policy().
// useNotificationTemplates: GET "/admin/notification-templates".
// useCreateNotificationTemplate: POST "/admin/notification-templates"; useUpdateNotificationTemplate: PUT
//   `/admin/notification-templates/${id}`; both invalidate templates() and templateOptions().
// useTemplateOptions(enabled): GET "/notification-templates/options".
```

  Write those last five hooks in full in the same shape as `calendar.ts`'s `useUpdateSlaPolicy`.

- [ ] **Step 4: Run** vitest, tsc, lint. **Step 5: Commit** — `feat(frontend): notification API hooks`.

---

### Task 29: The inbox list pane

**Files:**
- Create: `frontend/src/components/inbox/{InboxList,InboxRow,notificationVisuals}.tsx` and tests
- Modify: `frontend/src/lib/format/date.ts` (+ test) — `formatRelative`
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces:
  ```ts
  export function formatRelative(iso: string, now?: Date): string;   // "JUST NOW", "12 MIN AGO", "2 HOURS AGO", "YESTERDAY", "3 DAYS AGO", else formatDate
  export function InboxList({ onNavigate }: { onNavigate: () => void }): JSX.Element;
  export function InboxRow({ item, onOpen }: { item: NotificationItem; onOpen: (item: NotificationItem) => void }): JSX.Element;
  export const TONE_ROLE: Record<"RISK" | "WARN" | "OK" | "INFO", "risk" | "warn" | "ok" | "info">;
  export function iconFor(type: NotificationItem["type"]): (props: IconProps) => JSX.Element;
  ```

- [ ] **Step 1: Failing tests.**
  - `formatRelative` — fixed `now`; each bucket; mono-uppercase strings come from `t()` keys.
  - `InboxRow` — renders title (12.5/600), body, relative time in mono; unread rows have `data-unread="true"` and `surface` background; read rows transparent; the icon tile carries the tone's `--ob-<role>-bg/fg`; the tone is also conveyed in text (`aria-label` on the tile: e.g. "Risk", per "colour is never the only signal").
  - `InboxList` — with a mocked `useInbox` returning two pages: rows render in order; "Load more" appears while `hasNextPage` and calls `fetchNextPage`; clicking a row calls `markRead.mutate(id)`, `router.push(linkPath)` and `onNavigate()`; an already-read row does not call `mutate`; empty → "You're all caught up."; loading → `SkeletonRows rows={3}`; error → `ErrorState` whose Retry calls `refetch`.

  Mock `next/navigation`'s `useRouter` and `@/lib/api/notifications`. Run — FAIL.

- [ ] **Step 2: Visuals.**

```tsx
import type { IconProps } from "@/components/icons";
import { AlertTriangleIcon, CheckCircleIcon, ClockIcon, FileSignatureIcon, FileTextIcon, MessageSquareIcon,
  RefreshCwIcon, UserCheckIcon, CheckIcon } from "@/components/icons";
import type { NotificationItem } from "@/lib/api/notifications";

/** Tone is the row's status (DESIGN_TOKENS semantic pairs); colour is always paired with the icon and a label. */
export const TONE_ROLE = { RISK: "risk", WARN: "warn", OK: "ok", INFO: "info" } as const;

const ICONS: Record<string, (p: IconProps) => JSX.Element> = {
  ESCALATION: AlertTriangleIcon, RISK_CHANGED: AlertTriangleIcon,
  TASK_ASSIGNED: CheckIcon, TASK_OVERDUE: ClockIcon, DEADLINE_APPROACHING: ClockIcon, EXPIRY_RENEWAL: ClockIcon,
  MILESTONE_COMPLETED: CheckCircleIcon, STAGE_CHANGED: RefreshCwIcon, WORKFLOW_PUBLISHED: RefreshCwIcon,
  DOCUMENT_REQUESTED: FileTextIcon, DOCUMENT_UPLOADED: FileTextIcon, DOCUMENT_DECIDED: FileTextIcon,
  AGREEMENT_STATUS: FileSignatureIcon, NEW_COMMENT: MessageSquareIcon, NEW_CUSTOMER: UserCheckIcon,
};

export function iconFor(type: NotificationItem["type"]) {
  return (type && ICONS[type]) || FileTextIcon;
}
```

- [ ] **Step 3: `InboxRow` and `InboxList`.** Row: a `<button type="button">` (full width, left-aligned, `display: grid; grid-template-columns: 26px 1fr; gap: 10px; padding: 11px 16px; border-bottom: 1px solid var(--ob-line-soft)`), background `var(--ob-surface)` when unread else `transparent`; the tile is 26×26, radius `var(--ob-radius-7)`, `background: var(--ob-${role}-bg); color: var(--ob-${role}-fg)`, with the icon at 14px and `aria-label={t(\`inbox.tone.${role}\`)}`; then title (`font: 600 12.5px/1.35 var(--ob-font-family-ui)`), body (`11.5px/1.4`, `text-text-muted`), time (`font: 9.5px/1.3 var(--ob-font-family-data)`, `text-text-faint`, uppercase). An unread dot is not drawn — the surface contrast plus `aria-label` ("Unread: …") carries the state. List: `useInbox(true)`; flatten pages; states as in Step 1; "Load more" is `Button variant="text-link"`. On open: `if (!item.read) markRead.mutate(item.id); router.push(item.linkPath); onNavigate();`.

  `en.json` keys (flat): `inbox.title` "Inbox", `inbox.unread` "{count} UNREAD", `inbox.empty` "You're all caught up.", `inbox.error` "Notifications could not be loaded.", `inbox.loadMore` "Load more", `inbox.tone.risk` "Risk", `inbox.tone.warn` "Warning", `inbox.tone.ok` "Done", `inbox.tone.info` "Information", `inbox.unreadRow` "Unread: {title}", `time.justNow` "JUST NOW", `time.minutesAgo` "{n} MIN AGO", `time.hoursAgo` "{n} HOURS AGO", `time.yesterday` "YESTERDAY", `time.daysAgo` "{n} DAYS AGO".

- [ ] **Step 4: Run** vitest, tsc, lint. **Step 5: Commit** — `feat(frontend): the inbox list pane`.

---

### Task 30: The preferences pane

**Files:**
- Create: `frontend/src/components/inbox/PreferencesPane.tsx` (+ test)
- Modify: `frontend/src/components/ui/Switch.tsx` (+ test) — `disabled` prop and the knob shadow (COMPONENTS §17)
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces:
  ```tsx
  export function PreferencesPane(): JSX.Element;
  // Switch gains: disabled?: boolean (renders aria-disabled, no onChange, reduced opacity) and the §17 knob shadow
  ```

- [ ] **Step 1: Failing tests.**
  - `Switch` — `disabled` blocks `onChange` and sets `aria-disabled="true"`; the knob has `box-shadow: 0 1px 2px rgba(0,0,0,.2)`.
  - `PreferencesPane` (mock `@/lib/api/notifications` and `@/components/ui/Toast`) — renders the intro sentence; the cadence `Tabs` with three options and the current one selected; fifteen rows; the Escalation row's two switches are disabled with "Required by policy"; toggling "Task assigned to me → Email" calls `update.mutate` with the **full** body (every opt-out type, cadence, the one change applied) — never a partial; choosing "Daily digest" sends `emailCadence: "DAILY"`; an error from `mutate`'s `onError` shows a toast `t("notifications.prefs.saveFailed")`.

  Run — FAIL.

- [ ] **Step 2: `Switch`.** Add `disabled?: boolean` (the button gets `aria-disabled="true"`, `onClick` is a no-op, `opacity: .55`) and `ariaLabel?: string` (see Step 3's note); add the §17 knob shadow `0 1px 2px rgba(0,0,0,.2)` unconditionally. Add a `Switch.test.tsx` case for each; the existing cases must still pass.

- [ ] **Step 3: `PreferencesPane`.**

```tsx
"use client";

import { Switch } from "@/components/ui/Switch";
import { Tabs } from "@/components/ui/Tabs";
import { SkeletonRows, ErrorState } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import { usePreferences, useUpdatePreferences } from "@/lib/api/notifications";
import type { Preferences, TypePreference } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";

const CADENCES = ["IMMEDIATE", "DAILY", "WEEKLY"] as const;

/** Every change sends the whole preference set: the PUT is a full replace (plan amendment 11). */
function body(prefs: Preferences, patch: Partial<Pick<Preferences, "emailCadence">> & { type?: TypePreference }) {
  return {
    emailCadence: patch.emailCadence ?? prefs.emailCadence!,
    types: (prefs.types ?? [])
      .filter((tp) => !tp.locked)
      .map((tp) => (patch.type && tp.type === patch.type.type ? patch.type : tp))
      .map((tp) => ({ type: tp.type!, inApp: tp.inApp!, email: tp.email! })),
  };
}

export function PreferencesPane() {
  const prefs = usePreferences(true);
  const update = useUpdatePreferences();
  const toast = useToast();

  if (prefs.isLoading) return <SkeletonRows rows={5} height={36} />;
  if (prefs.isError || !prefs.data) return <ErrorState message={t("common.error")} onRetry={() => void prefs.refetch()} />;
  const data = prefs.data;
  const save = (next: ReturnType<typeof body>) =>
    update.mutate(next, { onError: () => toast.show(t("notifications.prefs.saveFailed")) });

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-14)", padding: "14px 16px" }}>
      <p className="text-text-muted" style={{ font: "11.5px/1.5 var(--ob-font-family-ui)", margin: 0 }}>
        {t("notifications.prefs.intro")}
      </p>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
        <span className="text-text-subtle" style={{ font: "500 11.5px/1.3 var(--ob-font-family-ui)" }}>
          {t("notifications.prefs.delivery")}
        </span>
        <Tabs
          items={CADENCES.map((c) => ({ id: c, label: t(`notifications.cadence.${c}`) }))}
          value={data.emailCadence ?? "IMMEDIATE"}
          onChange={(id) => save(body(data, { emailCadence: id as Preferences["emailCadence"] }))}
        />
        <span className="text-text-faint" style={{ font: "10.5px/1.4 var(--ob-font-family-ui)" }}>
          {t("notifications.prefs.digestCaption")}
        </span>
      </div>
      <ul className="flex flex-col" style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {(data.types ?? []).map((tp) => (
          <li key={tp.type} className="flex flex-col border-b border-line-soft" style={{ padding: "10px 0", gap: "6px" }}>
            <span style={{ font: "600 12.5px/1.35 var(--ob-font-family-ui)" }}>{tp.label}</span>
            {tp.locked && (
              <span className="text-text-faint" style={{ font: "10.5px/1.4 var(--ob-font-family-ui)" }}>
                {t("notifications.prefs.required")}
              </span>
            )}
            <div className="grid grid-cols-2" style={{ gap: "var(--ob-space-12)" }}>
              <Switch checked={tp.inApp!} disabled={tp.locked} label={t("notifications.prefs.inApp")}
                ariaLabel={t("notifications.prefs.inAppFor", { type: tp.label! })}
                onChange={(v) => save(body(data, { type: { ...tp, inApp: v } }))} />
              <Switch checked={tp.email!} disabled={tp.locked} label={t("notifications.prefs.email")}
                ariaLabel={t("notifications.prefs.emailFor", { type: tp.label! })}
                onChange={(v) => save(body(data, { type: { ...tp, email: v } }))} />
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

  `Switch`'s accessible name comes from its visible `label` (`aria-labelledby`), so fifteen rows would produce thirty switches named just "In-app" or "Email". Step 2 therefore also gives `Switch` an optional `ariaLabel?: string`: when present the button gets `aria-label={ariaLabel}` and **no** `aria-labelledby` (which would take precedence). In the pane pass `ariaLabel={t("notifications.prefs.inAppFor", { type: tp.label! })}` / `…emailFor…` beside the short visible `label`. The tests query `getByRole("switch", { name: "Email for Task assigned to me" })`.

  `en.json`: `notifications.prefs.intro` "Every notification type is optional. Escalation to your manager is enforced by policy and cannot be disabled.", `notifications.prefs.delivery` "Email delivery", `notifications.prefs.digestCaption` "Digests arrive at 08:00 your organisation's time.", `notifications.prefs.required` "Required by policy", `notifications.prefs.inApp` "In-app", `notifications.prefs.email` "Email", `notifications.prefs.inAppFor` "In-app for {type}", `notifications.prefs.emailFor` "Email for {type}", `notifications.prefs.saveFailed` "Your change could not be saved", `notifications.cadence.IMMEDIATE` "Immediately", `notifications.cadence.DAILY` "Daily digest", `notifications.cadence.WEEKLY` "Weekly digest".

- [ ] **Step 4: Run** vitest, tsc, lint. **Step 5: Commit** — `feat(frontend): notification preferences pane`.

---

### Task 31: The Inbox button, the drawer, ⌘J — and a TopBar test that proves the control is live

**Files:**
- Modify: `frontend/src/components/ui/Dialog.tsx` — export `FOCUSABLE` and `isVisible`
- Create: `frontend/src/components/inbox/{InboxButton,InboxDrawer,useInboxShortcut}.tsx` (+ tests)
- Modify: `frontend/src/components/shell/TopBar.tsx`, `TopBar.test.tsx`
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces:
  ```tsx
  export const FOCUSABLE: string; export function isVisible(el: HTMLElement): boolean;     // Dialog.tsx
  export function useInboxShortcut(toggle: () => void): void;                               // ⌘/Ctrl-J, ignores typing in inputs
  export function InboxDrawer({ onClose, returnFocusTo }: { onClose: () => void; returnFocusTo: React.RefObject<HTMLElement> }): JSX.Element;
  export function InboxButton(): JSX.Element | null;                                        // null for PORTAL users
  ```

- [ ] **Step 1: Failing tests.**
  - `useInboxShortcut` — `keydown` with `metaKey`+`j` or `ctrlKey`+`j` calls `toggle` and `preventDefault`s; plain `j` does not; it does not fire while focus is in an `input`/`textarea`/`[contenteditable]`.
  - `InboxDrawer` — `role="dialog"`, `aria-modal="true"`, labelled "Inbox"; header shows `{count} UNREAD` from `useUnreadCount`; "Mark all read" hidden at 0, calls `markAllRead.mutate()` otherwise; the Preferences button toggles between `InboxList` and `PreferencesPane` and its label flips to "← Notifications"; ✕, Esc and a scrim click each call `onClose`; Tab cycles inside the drawer; on unmount focus returns to `returnFocusTo`.
  - `InboxButton` — shows "Inbox" and a mono badge with the count (hidden at 0, "99+" above 99); clicking opens the drawer; ⌘J toggles it; renders nothing when `useAuth().user.userType === "PORTAL"`.
  - `TopBar.test.tsx` — rewrite the last test as **`it("ships a live Inbox control and no dead search or account controls")`**: render `TopBar` with `@/lib/auth/useAuth` mocked to an INTERNAL user and `@/lib/api/notifications` mocked (`useUnreadCount → { data: 3 }`, `useInbox`/`usePreferences` minimal, mutations `{ mutate: vi.fn() }`) and `next/navigation` mocked; assert `getByRole("button", { name: /inbox/i })` exists, clicking it renders `getByRole("dialog", { name: "Inbox" })`, and the `searchbox`/`account` assertions stay exactly as they were. The other four TopBar tests keep passing because `InboxButton` is only rendered when the mocks are present — wrap the existing `renderTopBar` with the same mocks (module-level `vi.mock`s apply to the whole file). This replaces the "no notification button" assertion with one that proves the button works — CLAUDE.md's "What 6B inherits" requires exactly this, not deleting the assertion.

  Run — FAIL.

- [ ] **Step 2: `Dialog.tsx`.** Change `const FOCUSABLE` and `function isVisible` to `export`. No behaviour change; `Dialog.test.tsx` stays green.

- [ ] **Step 3: `useInboxShortcut`.**

```tsx
"use client";
import { useEffect } from "react";

/** ⌘J / Ctrl-J toggles the inbox (README "Keyboard"). Never while the user is typing. */
export function useInboxShortcut(toggle: () => void) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "j") return;
      const target = event.target as HTMLElement | null;
      if (target && (target.closest("input, textarea, select, [contenteditable='true']"))) return;
      event.preventDefault();
      toggle();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [toggle]);
}
```

- [ ] **Step 4: `InboxDrawer`.** Structure (COMPONENTS §16): a fragment with a scrim `div` (`fixed inset-0 z-40`, `background: var(--ob-scrim-drawer)`, `onClick={onClose}`, `aria-hidden`) and an `aside`-styled `div` (`role="dialog" aria-modal="true" aria-labelledby={titleId}`, `fixed inset-y-0 right-0 z-50`, `width: min(390px, 100vw)`, `background: var(--ob-canvas)`, `borderLeft: 1px solid var(--ob-line)`, `boxShadow: var(--ob-shadow-drawer)`, `animation: om-slide var(--ob-duration-slide) var(--ob-ease-default)`, flex column). Header: `height: var(--ob-topbar-height)`, `padding: 0 16px`, title `14px/600`, the mono `9.5px` unread count (`font-data`, `text-text-faint`), then right-aligned "Mark all read" (`Button variant="text-link"`, hidden at 0), the Preferences toggle (`Button variant="small-secondary"`, `aria-pressed`), and ✕ (`XIcon` in a `button` with `aria-label={t("common.close")}`). Body: `overflow-y: auto; flex: 1` with `pane === "list" ? <InboxList onNavigate={onClose} /> : <PreferencesPane />`. Focus management copies `Dialog`'s: on mount focus the first focusable inside (using the exported `FOCUSABLE`/`isVisible`), trap Tab, Esc closes, on unmount `returnFocusTo.current?.focus()`.

- [ ] **Step 5: `InboxButton` and `TopBar`.**

```tsx
"use client";

import { useCallback, useRef, useState } from "react";
import { BellIcon } from "@/components/icons";
import { useAuth } from "@/lib/auth/useAuth";
import { useUnreadCount } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";
import { InboxDrawer } from "./InboxDrawer";
import { useInboxShortcut } from "./useInboxShortcut";

export function InboxButton() {
  const { user } = useAuth();
  const internal = user?.userType === "INTERNAL";
  const count = useUnreadCount(internal);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const toggle = useCallback(() => setOpen((o) => !o), []);
  useInboxShortcut(internal ? toggle : () => {});
  if (!internal) return null;   // portal notifications are sub-project 7's
  const n = count.data ?? 0;
  return (
    <>
      <button ref={trigger} type="button" onClick={toggle} aria-expanded={open}
        aria-label={n > 0 ? t("inbox.buttonWithCount", { count: String(n) }) : t("inbox.title")}
        className="flex items-center rounded-9 border border-line bg-surface text-ink"
        style={{ height: "var(--ob-control-height)", padding: "0 10px", gap: "6px", font: "500 12.5px/1 var(--ob-font-family-ui)" }}>
        <BellIcon size={15} />
        <span>{t("inbox.title")}</span>
        {n > 0 && (
          <span className="bg-ink text-surface" style={{ font: "600 9.5px/1 var(--ob-font-family-data)",
            padding: "3px 5px", borderRadius: "var(--ob-radius-full)" }}>{n > 99 ? "99+" : n}</span>
        )}
      </button>
      {open && <InboxDrawer onClose={() => setOpen(false)} returnFocusTo={trigger} />}
    </>
  );
}
```

  In `TopBar.tsx`, render `<InboxButton />` after the `flex-1` spacer, and rewrite the class comment: the inbox now exists (6B); the presence cluster and per-screen primary action still have no counterpart. `en.json`: `inbox.buttonWithCount` "Inbox, {count} unread", `inbox.preferences` "Preferences", `inbox.back` "← Notifications", `inbox.markAllRead` "Mark all read".

  The badge's ink background is a count, not a status — the prototype draws it dark; it carries no colour meaning, so non-negotiable 1 holds.

- [ ] **Step 6: Run** vitest, tsc, lint. Start the dev servers and check the drawer by hand at 1440px and 900px (spec §9.2; 100vw width below 390px). **Step 7: Commit** — `feat(frontend): the Inbox control and drawer, with ⌘J`.

---

### Task 32: Administration → Notifications — deadlines and reminders

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/admin/notifications/page.tsx` (+ test)
- Create: `frontend/src/components/notifications/HorizonsCard.tsx` (+ test)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/layout.tsx` (+ test)
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces: `export function HorizonsCard(): JSX.Element;` and the page at `/t/{slug}/admin/notifications` composing `HorizonsCard` and (Task 33) `TemplatesCard`.

- [ ] **Step 1: Failing tests.**
  - `admin/layout.test.tsx` — a "Notifications" tab appears only with `notification.manage`, linking to `/t/acme/admin/notifications`.
  - `HorizonsCard` (mock the hooks) — one row per kind with its unit caption ("business days before due" for the three `*_DUE` kinds, "days before" for the others); chips show each lead in mono with a remove button labelled "Remove 14 days"; the Add input refuses 0, 91, a duplicate, and a sixth chip (inline message, no request); the auto-remind switch, interval and max inputs; Save sends the **whole** policy (all six kinds) and shows `t("notifications.policy.saved")`; a 422 from the server shows its `detail` inline via `parseProblemDetail`.
  - `page.test.tsx` — the page sets the header "Notifications" and renders both cards.

  Run — FAIL.

- [ ] **Step 2: Implement** following `business-calendar/page.tsx`'s structure (flat `Card`s, `SkeletonRows`/`ErrorState`, a local form state seeded from the query, one Save per card, `useToast`, `problem(err)` for errors). Chips: the existing `Chip` primitive with `mono={lead}` and an `XIcon` button inside; units from `HorizonKind`'s business/calendar split, hard-coded in a `const UNIT: Record<Kind, "business" | "calendar">` that mirrors the backend enum (comment says so). Layout tab: in `admin/layout.tsx` add `const canManageNotifications = useHasPermission("notification.manage");` and push `{ label: t("notifications.admin.title"), href: \`/t/${slug}/admin/notifications\` }` after the calendar tab.

  `en.json`: `notifications.admin.title` "Notifications", `notifications.policy.title` "Deadlines and reminders", `notifications.policy.kind.TASK_DUE` "Task due", `…MILESTONE_DUE` "Milestone due", `…DOCUMENT_REQUEST_DUE` "Document request due", `…DOCUMENT_EXPIRY` "Document expiry", `…AGREEMENT_EXPIRY` "Agreement expiry", `…AGREEMENT_RENEWAL` "Agreement renewal decision", `notifications.policy.unit.business` "business days before due", `notifications.policy.unit.calendar` "days before", `notifications.policy.add` "Add lead time", `notifications.policy.remove` "Remove {n} days", `notifications.policy.range` "Lead times are 1 to 90 days, at most five", `notifications.policy.duplicate` "That lead time is already set", `notifications.policy.autoRemind` "Automatic customer reminders", `notifications.policy.interval` "Every (business days)", `notifications.policy.max` "At most (reminders)", `notifications.policy.saved` "Notification policy saved".

- [ ] **Step 3: Run** vitest, tsc, lint. **Step 4: Commit** — `feat(frontend): Administration > Notifications, deadlines and reminders`.

---

### Task 33: Administration → Notifications — templates

**Files:**
- Create: `frontend/src/components/notifications/{TemplatesCard,TemplateDialog}.tsx` (+ tests)
- Modify: `frontend/src/app/(app)/t/[slug]/admin/notifications/page.tsx`
- Modify: `frontend/src/lib/i18n/messages/en.json`

**Interfaces:**
- Produces: `export function TemplatesCard(): JSX.Element;` `export function TemplateDialog({ template, onClose }: { template?: NotificationTemplate; onClose: () => void }): JSX.Element;`

- [ ] **Step 1: Failing tests.**
  - `TemplatesCard` — a table (key in mono, name, "Entry" / "Entry and exit", active state as a word plus `StatusPill`) and a "New template" button; Deactivate/Activate per row sends a full PUT with `active` flipped (every other field unchanged); empty → `EmptyState` "No templates yet".
  - `TemplateDialog` — create mode has an editable key; edit mode shows it read-only; "Also alert on exit" switch reveals the exited subject/body and hides them when off (then sends `null` for both); the placeholder hint lists `{case} {customer} {stage} {owner}` in mono; a 409 shows "A template with this key already exists"; a 422 shows the server's detail; Save calls create or update with the full body.

  Run — FAIL.

- [ ] **Step 2: Implement** with `Dialog`/`DialogActions`, `Field`/`TextareaField`, `Switch`, the error pattern from the calendar page (`err instanceof ApiError && err.status === 409 ? … : problem(err)`). Add `<TemplatesCard />` below `HorizonsCard` on the page. `en.json` keys under `notifications.templates.*` for every string (title, new, key, name, enteredSubject, enteredBody, alsoOnExit, exitedSubject, exitedBody, placeholders, duplicate, entry, entryAndExit, active, inactive, activate, deactivate, empty, saved).
- [ ] **Step 3: Run** vitest, tsc, lint. **Step 4: Commit** — `feat(frontend): notification template administration`.

---

### Task 34: The builder's template picker

**Files:**
- Modify: `frontend/src/components/workflow/StageInspector.tsx`, `StageInspector.test.tsx`
- Modify: `frontend/src/lib/i18n/messages/en.json`

- [ ] **Step 1: Failing tests.** Replace `renders the notification template field disabled with an explanation` with:
  - `offersNoneAndTheActiveTemplates` — the fetch mock answers `/notification-templates/options` with `[{key:"kickoff",name:"Kickoff"}]`; the `select` labelled "Notification template" has options "None" and "Kickoff"; choosing Kickoff calls `onChange({ notificationTemplateKey: "kickoff" })`; choosing None calls it with `null`.
  - `keepsAKeyThatIsNoLongerOffered` — the stage holds `"retired"`, options do not include it → the select still shows "retired (inactive)" as the selected option, so a save never silently drops it.
  - `isDisabledReadOnly` — with `readOnly` the select is disabled (the existing fieldset does this).
  - The old "arrives with notifications" hint is gone.

  `useTemplateOptions` is gated on `workflow.manage` server-side; the builder is only reachable by workflow managers, so call it with `enabled = true`. Run — FAIL.

- [ ] **Step 2: Implement.** Replace the disabled `input` with a `select` (`selectStyle`) fed by `useTemplateOptions(true)`; value `stage.notificationTemplateKey ?? ""`; `""` maps to `null` in `onChange`; when the current key is not in the options, prepend `<option value={key}>{t("workflow.inspector.notificationTemplate.inactive", { key })}</option>`. Remove the comment about sub-project 6 and the `…notificationTemplate.hint` key; add `workflow.inspector.notificationTemplate.none` "None" and `…inactive` "{key} (inactive)". `toDraft.ts` already round-trips `notificationTemplateKey` (sub-project 6's round-trip guard) — run `toDraft.test.ts` to prove it.
- [ ] **Step 3: Run** vitest, tsc, lint. **Step 4: Commit** — `feat(frontend): pick a notification template for a stage`.

---

## Phase 7 — End to end and close-out

### Task 35: `notifications.spec.ts`

**Files:**
- Create: `frontend/e2e/notifications.spec.ts`
- Modify: `frontend/e2e/support/tenant.ts` (helpers this spec needs, if any beyond Task 27's levers)

- [ ] **Step 1: Write the spec** (serial; one tenant via `provisionTenant(request, "notif")`). Seed with `Api` the way `sla.spec.ts` does: a customer, a published one-stage workflow, a case, and two users via `seedUser` — `ann` (grants `{ "case.view": "ALL", "task.view": "ALL", "task.manage": "ALL", "workflow.view": "ALL" }`) and `ben` (`{ "case.view": "ALL", "task.view": "ALL" }`). Tests:
  1. **"assigning a task raises the assignee's badge; opening the row lands on the case and clears it"** — as ann (API), create a task on the case assigned to ben. Sign in as ben; the top bar's `getByRole("button", { name: /Inbox, 1 unread/ })` is visible (poll with `expect(...).toBeVisible()` — the badge polls every 60 s, so reload the page once rather than wait); click it; the drawer `getByRole("dialog", { name: "Inbox" })` lists "Task assigned to you: …"; click the row → URL is the case page; reopen the drawer → no unread count.
  2. **"⌘J toggles the drawer and Esc closes it"** — `page.keyboard.press("ControlOrMeta+j")` opens, `Escape` closes.
  3. **"turning a type off stops it arriving"** — ben opens Preferences, switches off "In-app for Task assigned to me" and "Email for Task assigned to me"; ann assigns another task; ben's inbox gains nothing (reload, open, assert the count).
  4. **"a keyed stage sends a stage-entered alert"** — as admin: create a template `kickoff` through the Administration → Notifications screen UI (exercises Task 33), author a two-stage workflow through the API with `notificationTemplateKey: "kickoff"` on stage two, open a case owned by ben, satisfy stage one's requirement; ben's inbox shows the rendered subject.
  5. **"a daily-digest user receives one digest email"** — ben sets "Daily digest"; ann assigns a task; `admin.shiftClock(...)` to the next 08:05 tenant-local on a working day (compute from `Date.now()` and the UTC default calendar), `admin.runDigest()` then `admin.runEmailDispatch()`; `readEmail(benEmail, "Your daily digest")` contains the task's title and an `http://localhost:3000/t/…` link.

  The clock-shift caveat from `sla.spec.ts`'s header applies — copy that paragraph into this spec's header comment.

- [ ] **Step 2: Run the whole Playwright suite** against a fresh scratch database (`docker exec onboarding-db createdb -U postgres onboarding_e2e_6b_final`; `$env:DB_URL="jdbc:postgresql://localhost:5434/onboarding_e2e_6b_final"; npx playwright test`). Every existing spec must stay green — in particular `activation.spec.ts`'s "a PORTAL user's rail carries Dashboard and nothing else" (the Inbox button is hidden for portal users) and `sla.spec.ts` (escalation email now arrives through the dispatcher via `runOne`).
- [ ] **Step 3: Commit** — `test(e2e): notifications -- badge, drawer, preferences, stage alert, digest`.

---

### Task 36: Close-out — full verification, CLAUDE.md, security review

**Files:**
- Modify: `CLAUDE.md`
- Create: `.superpowers/sdd/2026-10-04-notifications/close-out-report.md`

- [ ] **Step 1: All suites in one pass.** Backend per package (Task 1's loop, every package), totals from the XML; `npx vitest run`; `npx tsc --noEmit`; `npm run lint`; the full Playwright run from Task 35. Record each summary line verbatim in the report. A failure is fixed (systematic-debugging) before anything else in this task.
- [ ] **Step 2: Invariant cross-check** — verify each of spec §11's ten against the code and record *how* in the report (the sub-project 5 close-out shape):
  1. `ModuleBoundaryTest`'s six producer rules + `noNotificationDependencyOnSla`.
  2. `git log --oneline main..HEAD -- backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java` — every hit adds only `events.publishEvent(...)` lines; `rg "reconcile" backend/src/main/java/co/ara/onboarding/notification` finds nothing.
  3. `NotificationPipelineTest.aRolledBackActionLeavesNoNotificationAndNoOutboxRow`, `CustomerReminderOutboxTest.aRolledBackReminderQueuesNothing`.
  4. `RecipientAccessTest` (all eight), `DocumentNotificationTest.aTargetedUploadDoesNotReachAnOwnerOutsideItsAudience`, `ExpirySweepTest.aTargetedDocumentsExpiryDoesNotReachAnOwnerOutsideItsAudience`.
  5. `NotificationPipelineTest.theActorIsNeverNotified`, `TaskNotificationTest.selfAssignmentNotifiesNobody`.
  6. `PreferencesApiTest.turningEscalationOffIs422`, `PreferenceReaderTest.escalationIsAlwaysOnAndImmediate`.
  7. `NotificationSchemaTest.aDedupeKeyIsUniquePerRecipientButNullsAreNot`, `DeadlineSweepTest`'s repeat-sweep cases.
  8. `rg "email.send\(|EmailSender" backend/src/main/java` — the only notification-class caller is `EmailDispatchJob` (auth's invitation/reset sends remain, by design); `EmailDispatchTest.failuresBackOffThenFailAfterFiveAttemptsAndAreAudited`.
  9. `SystemActorTest.theSystemActorHoldsExactlyTheJobPermissionsAtAll`, `AutoReminderTest.theSweepCallsNoOtherDocumentRequestServiceMethod`.
  10. `NotificationIsolationTest`, `InboxApiTest.anotherUsersNotificationIs404`, the three request/view alignment tests.
- [ ] **Step 3: Whole-branch security review.** Dispatch one fresh reviewer on the most capable model over `main..HEAD` (CLAUDE.md: per-task reviews miss cross-module write paths and cross-actor reads). Named focus: `RecipientAccess` (does any path build a context that widens?), the ungated `InboxService`/`NotificationPreferenceService` (every id predicate includes the caller), plan amendment 4's SQL-fact reads (is any of it returned to a caller anywhere?), the system actor's `document.request`, the outbox's stored PII and bodies, and the dev endpoints. Fix every Important finding with a test proven red first; record the rest in CLAUDE.md's open list.
- [ ] **Step 4: CLAUDE.md.** Add, in the existing shape: **"Sub-project 6B delivered"** (a dense paragraph like sub-project 6's); update the **Sequence** line (`6B Notifications (needs 6; delivered)`); **"Sub-project 6B's own ten"** with the verification sentence from Step 2; **"Open at the close of sub-project 6B"** (at least: real-time badge is sub-project 8's; portal notifications and a customer-comment type are 7's; reviewers of agreements/documents are not a relationship so "submitted for review" reaches no reviewer; digests are English plain text; anything Step 3 parked); move the four sub-project 6 items 6B closed (spec §12.3) out of "Open at the close of sub-project 6"; replace **"What sub-project 6B inherits"** with **"What sub-project 7 inherits"** (portal recipients: `RecipientAccess` refuses non-INTERNAL users and the pipeline's step 2 drops them — 7 widens both deliberately; `notification_template` placeholders; the outbox for portal email); add the new guards to **"Where the guards live"**. Keep it dense — delete what stopped being true.
- [ ] **Step 5: Commit** — `docs: close out sub-project 6B, Notifications` with the report path in the body. Do not push.

---

## Self-review notes (for the plan author; executors may skip)

Spec coverage was checked section by section against `2026-10-04-notifications-design.md`:
§2.1 in-scope items → Tasks 2–34; §3 module/events/listener/jobs → 2, 13–18, 20, 22, 5/23/26; §4.1–4.7 → 3, 9, 19, 21, 22 and each task's audit additions; §5.1 catalogue → 3; §5.2 recipients → 13–18, 20, 22–24; §5.3 pipeline → 10; §5.4 escalation → 6; §5.5 content → 10 and every listener; §6.1 → 22; §6.2 → 23–25; §6.3 → 26; §6.4 → 5; §6.5 → 4; §7 → 8, 11, 12, 19; §8 API → 11, 12, 19, 21, 27; §9 screens → 29–34; §10 testing → every task + 35; §11 → 36; §12.3 closed items → 6, 7, 4.

