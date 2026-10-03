# SLA & Escalation — Design Spec

**Sub-project 6.** Adds the `sla` module: a per-tenant business calendar with holidays and a
timezone, a business-day SLA clock per stage visit that pauses while the case is on hold or waiting
on the customer, breach and at-risk detection, a manager relationship to escalate to, mandatory
automatic escalation of overdue tasks, milestones and breached clocks, the platform's first
scheduler (tenant-iterating, RLS-bound), the audit partition roll-forward job, and on the frontend
the SLA war room, the SLA chips, the business-calendar administration screen and the builder's
"Pause on customer" toggle.

Depends on 1 (tenancy, identity, authz, audit, `EmailSender`), 2 (workflow, journey, `CaseEngine`,
the hold mechanism), 3 (tasks), 3A (the plan hold), 4 (document requests). Unblocks **6B
Notifications** (inbox, preferences, digests, deadline horizons, stage-entered/exited alerts via
`stage.notification_template_key`, risk alerts, expiry and renewal reminders — all built on this
sub-project's `notification` table and scheduler), 8 (dashboard SLA KPIs, real-time clock ticks)
and 9 (SLA compliance reporting, the "time lost to waiting" split bar).

---

## 1. Context

PRD §13 lists overdue-task and upcoming-deadline notifications and §14 an SLA compliance report.
QA Q8 fixes the SLA rules: business days on a configured business calendar, paused while waiting on
the customer — case hold and open customer document requests. QA Q10 (clarified 2026-08-29) makes
escalation to the assignee's manager on overdue work **mandatory and impossible to opt out of**,
while every other notification type is opt-out-able. QA Q23 maps the 3A plan hold onto the existing
`Case.held_at`, so "the SLA accounting is correct for free".

The design handoff draws the SLA war room (SCREENS §4), the cases-list SLA column and saved filter
(§2), the case header chip and right-rail SLA CLOCK callout (§3), and the builder's SLA days and
"Pause on customer" fields (§9). STATE_AND_DATA.md gives the `SlaClock` shape (L124–135) and the
rule that a clock is never computed in the browser as `now - startedAt` (L138).

What exists today, verified against the code at `e70b83e`:

- `stage.sla_days` is stored and round-tripped (`V12`, `Stage.java:45`) and read by nothing.
- `Case.held_at` / `total_hold_days` work; `CaseService.doResume` shifts open milestones' due dates
  by the held business days.
- `platform.BusinessCalendar` (`plusBusinessDays`, `businessDaysBetween`) has one implementation,
  `WeekdayBusinessCalendar` — Monday to Friday, UTC, no holidays. Its own javadoc names this
  sub-project as its replacement.
- No scheduler exists anywhere (`@Scheduled`, `@EnableScheduling`, ShedLock: zero hits).
- No manager concept exists: `app_user` has `department_id` only; `department` and `team` have no
  head.
- `EmailSender` is one plain-text method; its javadoc defers templates to "the notification system".
- `document_request.status` is written in exactly four places: `DocumentRequestService.create`
  (OPEN), `.withdraw` (WITHDRAWN), `.fulfil` (FULFILLED), and `DocumentInstantiation` (OPEN).
- Audit partitions: fixed separately and first, as PR #20 (`V27`, partitions through 2027-12,
  `create_audit_event_partition(month)`). The roll-forward job is this sub-project's.

### 1.1 Decisions taken in brainstorming (2026-10-02)

1. **Split.** The original sub-project 6 is split into **6, SLA & Escalation** (this spec) and
   **6B, Notifications**, sequential. Escalation is the one mandatory notification and SLA serves the
   most screens, so it goes first; escalation needs only a stored notification row and an email.
2. **Who is the manager.** `app_user.manager_id` if set, else the assignee's department's
   `head_user_id`, else the tenant's administrators. Escalation can never resolve to nobody.
3. **What escalates.** All three: an overdue task (to the assignee's manager), an overdue milestone
   and a breached stage SLA clock (both to the case owner's manager). Each fires once per subject and
   due date, after a tenant-configurable number of overdue business days (default 1 — the design's
   "automatic, day 1 overdue").
4. **Calendar.** One calendar per tenant: timezone, working weekdays, dated holidays. It replaces
   `WeekdayBusinessCalendar` everywhere, so due dates, hold days and SLA agree. Changing it affects
   only dates computed afterwards — stored due dates are never recomputed, as publishing never
   touches a running case.
5. **Clock rules** (§5): one clock per stage visit; business days only, fractional, a business day
   counting as 24 hours; paused by a hold or by any open document request; pause eligibility per
   stage via a new `pausesOnCustomer` toggle; re-entry starts a fresh clock.
6. **At risk.** A tenant setting, "at risk when this many business days or fewer remain", default
   1.0. "Due today" is the day the target falls on and needs no setting.
7. **Delivery.** Each escalation writes a `notification` row and emails the recipient. 6B builds the
   inbox on that same table.
8. **War room actions.** All three ship: Reassign and Force-complete reuse existing endpoints;
   Remind customer is a new, manual, rate-limited email to the request's contact.
9. **Architecture.** Stored clocks and pause intervals written synchronously through ports, elapsed
   time derived on read, a scheduled sweep for breach and escalation (Approach 1). Rejected: deriving
   pauses from `audit_event` (rows before 2026-08-29 have causes stamped after effects, and a
   tenant-wide war room would be slow), and storing elapsed time per tick (stale and write-heavy).
10. **Test time travel.** A dev/test-only clock-offset endpoint, absent from every other profile, so
    Playwright can cross a business day. Added while writing this spec, under the same profile gate:
    a dev/test-only endpoint that runs the sweep on demand, since waiting five minutes per assertion
    is not a usable e2e loop (§10.3).

### 1.2 Amendments from plan research (2026-10-03)

Found while mapping exact signatures for the implementation plan. Where this section and a later one
disagree, **this section wins**; the later sections are left as approved so the reasoning stays
readable.

1. **A fifth stage-change path.** `MilestoneService.reopen` rewinds `currentStageId` directly, without
   `enterStage` (and un-completes a `COMPLETED` case). It makes the same `stageExited`/`stageEntered`
   port calls when the stage actually changes or the case was completed. Invariant 2 becomes: *stage
   clock calls are made only where `currentStageId` changes — `CaseEngine.enterStage`/
   `advanceIfExitable` and `MilestoneService.reopen` — and nothing adds a caller of `reconcile`.*
   `MigrationService` also sets `currentStageId` directly, but by stage *name* within the same visit,
   so it keeps the open clock (§3.2's ruling holds).
2. **The scheduler is its own slice, `co.ara.onboarding.scheduling`**, not `platform.scheduling`: it
   orchestrates `tenancy` (`TenantContext`, `TenantConnectionCustomizer`) and `authz` (the system
   principal), and `platform` may name neither — the reason `provisioning` exists.
3. **The system actor needs a request scope and a principal.** `AuthorizationService` and
   `RequestAuditContext` are `@RequestScope`, and `AuthContextProvider.current()` resolves an
   `app_user` row. So: a new `authz.SystemPrincipal(UUID tenantId)` — a distinct principal type no JWT
   can produce; `platform.UserType` gains `SYSTEM` (never persisted); `AuthContextProvider.current()`
   returns `AuthContext(tenantId, SystemPrincipal.SYSTEM_USER_ID, SYSTEM, null, Set.of())` for it;
   `AuthorizationService.effectivePermissions()` returns `SystemPermissions.forJobs()` for it; and
   `TenantJobRunner` installs a fresh in-memory `RequestAttributes` per tenant so request-scoped beans
   resolve. `RequestAuditContext` already defaults to `ActorType.SYSTEM`.
4. **The job lock is transaction-level and per tenant**: `pg_try_advisory_xact_lock(hashtext(job),
   hashtext(tenantId))` inside each tenant's transaction, not a session lock — a session lock on a
   pooled connection cannot be reliably released on the connection that took it.
5. **Dev endpoints are `@Profile("dev")` only.** The backend suite runs under `test`, Playwright's
   backend under `dev`; the "absent outside dev" test therefore runs in the ordinary suite. Under
   `dev`, `PlatformBeansConfig` registers an `OffsetClock` as the `clock` bean.
6. **There is no cases list.** No `GET /cases` endpoint and no screen 2 exist; cases are reached per
   customer. The cases-list SLA column, the `sla=` filter and the "SLA at risk · N" saved filter are
   **dropped from this sub-project** and carried forward to whichever sub-project builds screen 2.
7. **Departments have no update endpoint.** This sub-project adds `PUT /admin/departments/{id}`
   (`name`, `description`, `headUserId`), gated `department.manage`; `DepartmentView` gains
   `headUserId`. The head picker lives on the existing `admin/org` page.
8. **Owners are columns, not participants.** `Case.ownerUserId` and `Milestone.ownerUserId` exist.
   The late person for a milestone is `milestone.ownerUserId`, else `case.ownerUserId`; for a clock,
   `case.ownerUserId`.
9. **`escalate_after_overdue_days` is `CHECK (>= 1)`**, and *overdue days* is precisely
   `calendar.businessDaysBetween(dueDate, today)` (working days in `[dueDate, today)`) with
   `today > dueDate`. With the default 1, a task due Thursday escalates on Friday.
10. **Recipients come from `identity.ReportingLineDirectory`**, which reads `app_user`,
    `department` and `user_role`/`role` with `JdbcTemplate` under the bound tenant's RLS — the
    `IdentityActorDirectory` precedent — returning `Recipient(userId, email, fullName)`.
11. **Positional constructors stay compiling.** `StageRequest`, `CreateUserRequest`,
    `UpdateUserRequest` and `DepartmentRequest` gain their new component plus a secondary
    constructor at the old arity, so the ~31 positional test call sites need no edit.
12. **Reassign a case owner** uses the existing full-replace `PUT /cases/{id}`; the frontend gains
    `useUpdateCase` and sends the current `CaseView`'s fields with only `ownerUserId` changed.
13. **The calendar maths is a pure class**, `platform.CalendarRules(ZoneId, Set<DayOfWeek>,
    Set<LocalDate>)`, unit-tested directly; `tenancy.TenantBusinessCalendar` loads a tenant's rules
    (cached per transaction through `TransactionSynchronizationManager`) and delegates to it.

## 2. Scope

### 2.1 In

- `sla` module: `sla_clock`, `sla_pause`, `escalation`, `notification`; the clock reader; the sweep.
- `tenancy.TenantBusinessCalendar`, `business_calendar`, `business_holiday`, `sla_policy`.
- `app_user.manager_id`, `department.head_user_id`, `stage.pauses_on_customer`,
  `document_request.reminders_sent` / `last_reminded_at`.
- `platform.scheduling.TenantJobRunner`, `authz.SystemPermissions`, the advisory-lock job guard.
- `ensure_audit_event_partitions(months_ahead)` and the daily partition job.
- Permissions `sla.view` and `calendar.manage`; role template grants.
- Remind customer (`POST /document-requests/{id}/remind`).
- Frontend: war room, `SlaChip`, `SlaClockCallout`, the cases-list column and filter, the case header
  chip, the builder toggle, Administration → Business calendar, the manager and department-head
  pickers.
- A dev/test-only clock-offset endpoint and the `sla.spec.ts` end-to-end spec.

### 2.2 Out, and why

- **The inbox drawer, preferences, opt-outs, digests, deadline horizons** — 6B.
- **Stage entered/exited alerts and `stage.notification_template_key`** — 6B. The builder field
  stays inert.
- **Risk-state alerts to stakeholders** (Q19) — 6B. This sub-project computes `atRisk`; 6B notifies
  on it.
- **Automatic customer reminders, document and agreement expiry/renewal reminders** — 6B, on this
  sub-project's scheduler.
- **`stage.portal_visible`.** CLAUDE.md says 6 gives it a consumer; QA Q24 names sub-project 7. Q24
  is the more specific document and wins: this sub-project does not touch it. CLAUDE.md is corrected
  in the plan's close-out task.
- **Dashboard SLA KPIs, real-time clock ticks** — 8. The war room polls.
- **Analytics split bar, SLA compliance report** — 9.
- **Working hours.** Nothing in the PRD or Q8 asks for them; a business day is a whole day.
- **Multiple calendars per tenant.** Nothing asks for them; `business_calendar` is one row per
  tenant, and the design's `Tenant.businessCalendarId` is realised as that one-to-one.
- **SMS, Teams, Slack** — PRD marks them optional or future.

## 3. Architecture

### 3.1 The module

`co.ara.onboarding.sla` is its own slice: it reads `journey`, `task`, `document` and `identity`
and implements ports two of them declare, so it cannot live inside any of them (the reason
`provisioning` and `scoping` exist). It owns `SlaClock`, `SlaPause`, `Escalation`, `Notification`,
their repositories, `SlaClockLifecycleAdapter`, `CustomerWaitLifecycleAdapter`, `SlaClockReader`,
`SlaSweep`, `EscalationService`, `RecipientResolver`, `SlaExceptionsService`, and controllers.

`ModuleBoundaryTest` gains `noJourneyDependencyOnSla` and `noDocumentDependencyOnSla`, each proven
red with a temporary violation before it is relied on.

### 3.2 Ports

**`journey.SlaClockLifecycle`** — declared in `journey`, implemented by `sla`:

```java
void stageEntered(UUID caseId, UUID stageId, Instant at);
void stageExited(UUID caseId, Instant at);
void held(UUID caseId, Instant at);
void resumed(UUID caseId, Instant at);
```

- `stageEntered`/`stageExited` are called only from inside `CaseEngine.reconcile` (where
  `enterStage` and the stage-exit/completion transitions already run), under `lockById`. This adds no
  new caller of `reconcile` and no second lock path. Case completion calls `stageExited` for the
  final stage. A skipped stage (entry condition false) is never entered, so never gets a clock.
  Force-complete exits a stage through the same transition as any other exit.
- `held`/`resumed` are called from `CaseService.hold` and `CaseService.doResume`, in their
  existing transactions. A customer-template case created on hold (Q23) calls `stageEntered` then
  `held` in the creation transaction, so its first clock starts paused.
- Migration (`MigrationService`) changes a case's pinned version through the same engine path; if the
  case's current stage changes as a result, `reconcile` makes the same exit/enter calls. If it does
  not, the open clock continues untouched — its `target_days` and `pause_eligible` were copied at
  stage entry and describe the visit, not the version.

**`document.CustomerWaitLifecycle`** — declared in `document`, implemented by `sla`:

```java
void requestOpened(UUID caseId, Instant at);
void requestClosed(UUID caseId, Instant at);
```

Called at each of the four `document_request.status` writes: `create` and `DocumentInstantiation`
(opened), `fulfil` and `withdraw` (closed). A request moves to FULFILLED the moment the customer
uploads, even when internal review is still pending — so the clock resumes on upload: review is
internal work.

Every port call runs in the caller's transaction. A business change that rolls back takes its clock
change with it. Invariant 1.

### 3.3 Business calendar

`platform.BusinessCalendar` stays the interface — `platform` still names no domain type — and gains:

```java
double businessDuration(Instant from, Instant to); // fractional business days
LocalDate today();                                  // in the tenant's timezone
```

`tenancy.TenantBusinessCalendar` replaces `WeekdayBusinessCalendar` as the only bean. It resolves
the current tenant's `business_calendar` and holidays from the bound tenant context, cached for the
duration of one request or job transaction and never across them. Existing callers
(`CaseEngine.enterStage`, `MigrationService`, `CaseService.doResume`) are unchanged in code; their
results start honouring holidays and the timezone for dates computed after this ships.

`businessDuration` walks the interval day by day in the tenant's timezone: a working, non-holiday
day contributes the fraction of its 24 hours inside the interval; any other day contributes zero.

### 3.4 Scheduler

`platform.scheduling.TenantJobRunner` runs a job body once per `ACTIVE` tenant: it reads the
`tenant` registry (not RLS-protected, on `RlsCoverageTest`'s allowlist already), then opens one
transaction per tenant with that tenant bound through the same binder the request path uses. A
tenant's failure is logged with the tenant id and does not stop the others.

Each job takes a Postgres session advisory lock keyed by job name (`pg_try_advisory_lock`); a second
application instance that fails to take it skips the tick. No new dependency.

Jobs act as a **system actor** whose permission set is a code constant —
`authz.SystemPermissions`: `case.view`, `task.view`, `sla.view` at `ALL` — never a `user_role` row,
mirroring `PortalPermissions`. Sweep methods still carry `@RequirePermission` and still read through
`AuthorizedQuery`; `AuthorizationCoverageTest` gains no exclusion. The audit actor for job-written
events is `SYSTEM`.

Cadences are properties: `app.sla.sweep-interval` (default `PT5M`), the partition job daily.
`@EnableScheduling` is off under the `test` profile; tests invoke job bodies directly.

## 4. Data model

One migration per area, starting at `V28`. Every tenant-owned table has a non-null `tenant_id` and
calls `enable_tenant_rls` in the migration that creates it; no `RlsCoverageTest` allowlist entry.

### 4.1 Calendar and policy

```sql
business_calendar (
  id uuid PK, tenant_id uuid NOT NULL UNIQUE,
  timezone text NOT NULL DEFAULT 'UTC',          -- IANA zone id, validated in Java
  working_days smallint[] NOT NULL DEFAULT '{1,2,3,4,5}',  -- ISO day-of-week
  created_at, updated_at timestamptz NOT NULL)

business_holiday (
  id uuid PK, tenant_id uuid NOT NULL,
  holiday_date date NOT NULL, name text NOT NULL,
  UNIQUE (tenant_id, holiday_date))

sla_policy (
  id uuid PK, tenant_id uuid NOT NULL UNIQUE,
  at_risk_days numeric(4,1) NOT NULL DEFAULT 1.0 CHECK (at_risk_days >= 0),
  escalate_after_overdue_days int NOT NULL DEFAULT 1 CHECK (escalate_after_overdue_days >= 0),
  created_at, updated_at timestamptz NOT NULL)
```

`business_holiday` carries an explicit, commented `GRANT DELETE`: a holiday is configuration, not a
business record, and removing a mistaken one must remove it. Provisioning seeds a calendar and a
policy for every new tenant in its existing transaction; the migration backfills both for existing
tenants.

### 4.2 People

`app_user.manager_id uuid NULL REFERENCES app_user(id)` and
`department.head_user_id uuid NULL REFERENCES app_user(id)`, with a `CHECK (manager_id <> id)`.

**What deactivation revokes.** Nothing new: a deactivated user who is someone's manager or a
department head keeps the link, and `RecipientResolver` skips anyone not `ACTIVE` and falls through
to the next step. Deactivating a user can therefore never break the escalation chain, and
reactivating restores it with no extra step.

### 4.3 Stage

`stage.pauses_on_customer boolean NOT NULL DEFAULT true`. Existing published stages read `true`; the
column add fires no row trigger, so the published-version freeze is unaffected. Changing it on a
published workflow means publishing a new version, like every stage field.

### 4.4 Clocks

```sql
sla_clock (
  id uuid PK, tenant_id uuid NOT NULL,
  case_id uuid NOT NULL REFERENCES onboarding_case(id),
  stage_id uuid NOT NULL REFERENCES stage(id),
  target_days int NOT NULL CHECK (target_days > 0),   -- copied from stage.sla_days
  pause_eligible boolean NOT NULL,                     -- copied from stage.pauses_on_customer
  started_at timestamptz NOT NULL,
  stopped_at timestamptz NULL,
  outcome text NULL CHECK (outcome IN ('MET','BREACHED')),
  breached_at timestamptz NULL,
  CHECK ((stopped_at IS NULL) = (outcome IS NULL)))
CREATE UNIQUE INDEX sla_clock_one_open_per_case ON sla_clock (case_id) WHERE stopped_at IS NULL;

sla_pause (
  id uuid PK, tenant_id uuid NOT NULL,
  clock_id uuid NOT NULL REFERENCES sla_clock(id),
  reason text NOT NULL CHECK (reason IN ('CASE_HOLD','OPEN_DOCUMENT_REQUEST')),
  started_at timestamptz NOT NULL, ended_at timestamptz NULL)
CREATE UNIQUE INDEX sla_pause_one_open_per_reason ON sla_pause (clock_id, reason) WHERE ended_at IS NULL;
```

A stage with no `sla_days` gets no clock. `outcome` is set at stop: `BREACHED` if `breached_at` is
set or elapsed ≥ target at the stop instant, else `MET`.

### 4.5 Escalations and notifications

```sql
escalation (
  id uuid PK, tenant_id uuid NOT NULL,
  subject_type text NOT NULL CHECK (subject_type IN ('TASK','MILESTONE','SLA_CLOCK')),
  subject_id uuid NOT NULL,
  case_id uuid NOT NULL REFERENCES onboarding_case(id),
  late_user_id uuid NULL REFERENCES app_user(id),   -- assignee or case owner; NULL if none
  route text NOT NULL CHECK (route IN ('MANAGER','DEPARTMENT_HEAD','ADMINISTRATORS')),
  escalated_to_user_id uuid NULL REFERENCES app_user(id),  -- NULL for ADMINISTRATORS
  due_date_at_escalation date NOT NULL,   -- for SLA_CLOCK, the business day the target fell on
  overdue_days int NOT NULL,
  escalated_at timestamptz NOT NULL,
  UNIQUE (subject_type, subject_id, due_date_at_escalation))

notification (
  id uuid PK, tenant_id uuid NOT NULL,
  recipient_user_id uuid NOT NULL REFERENCES app_user(id),
  type text NOT NULL CHECK (type IN ('ESCALATION')),   -- 6B widens this by migration
  title text NOT NULL, body text NOT NULL, link_path text NOT NULL,
  case_id uuid NULL REFERENCES onboarding_case(id),
  escalation_id uuid NULL REFERENCES escalation(id),
  created_at timestamptz NOT NULL, read_at timestamptz NULL, emailed_at timestamptz NULL)
```

Neither table grants `DELETE`. The unique key on `escalation` is what makes escalation idempotent
(invariant 5): a task re-dated after escalating can escalate again for its new date; the same date
never twice.

### 4.6 Document request

`document_request.reminders_sent int NOT NULL DEFAULT 0` and `last_reminded_at timestamptz NULL`.
A reminder goes to a contact who may have no portal account, so it is recorded here and on the audit
trail rather than as a `notification` row (those are internal users only).

### 4.7 Audit actions

| Action | `timeline_visible` | Why |
|---|---|---|
| `sla.breached` | false | SLA mechanics are hidden from the customer (SCREENS L344) |
| `escalation.raised` | false | Internal performance management |
| `notification.sent` | false | Delivery record, compliance only |
| `document_request.reminded` | true | The customer received it; a business event on their journey |
| `calendar.updated`, `calendar.holiday_added`, `calendar.holiday_removed`, `sla_policy.updated` | false | Tenant configuration |
| `user.manager_changed`, `department.head_changed` | false | Identity, as `user.*` already is |

Clock start/stop/pause are **not** audited separately: they are consequences of `case.stage_entered`,
`case.held`, `document.requested` etc., which are already recorded, and the clock tables are their
own record. Each new action is recorded before the calls that record its consequences
(`CauseBeforeEffectTest`).

## 5. Clock rules

1. **One clock per stage visit.** Started at stage entry with `target_days = stage.sla_days`;
   stopped at exit as MET or BREACHED. Re-entering a stage starts a fresh clock; the earlier one stays.
2. **Elapsed** = `businessDuration(started_at, stopped_at ?? now)` minus the business duration of the
   **union** of the clock's pause intervals (overlapping hold and request pauses count once).
3. **Paused** while the case is on hold, or — only when `pause_eligible` — while at least one
   document request on the case is OPEN. Several open requests share one `OPEN_DOCUMENT_REQUEST`
   interval, opened by the first and closed when the count reaches zero (the adapter counts OPEN
   rows inside the same transaction). A hold pauses even an ineligible stage, consistent with today's
   hold shifting milestone due dates. A request opened while the stage is ineligible opens no
   interval.
4. **State**: `MET`/`BREACHED` once stopped; otherwise `BREACHED` if elapsed ≥ target, else `PAUSED`
   if a pause interval is open, else `RUNNING`. `atRisk` = running or paused, not breached, and
   `target − elapsed ≤ sla_policy.at_risk_days`. **Due today** = not breached and the business day
   on which remaining time reaches zero (assuming no further pause) is `today()`.
5. **Pause reason shown** is the open interval that started first.

## 6. The sweep and escalation

### 6.1 Per tenant, in order

1. **Breach.** Open clocks with `breached_at` null and elapsed ≥ target: stamp `breached_at`, record
   `sla.breached`.
2. **Find overdue work**, against `today()` minus `escalate_after_overdue_days` business days:
   - tasks with status `PENDING`, `IN_PROGRESS` or `WAITING` and a `due_date` (never `COMPLETED` or
     `CANCELLED` — sub-project 3's rule that a cancelled task fires nothing);
   - milestones with status `PENDING`, `ACTIVE` or `BLOCKED` and a `due_date`;
   - open clocks breached that many business days ago.
   Items on a case that is `ON_HOLD`, `COMPLETED` or `CANCELLED` are skipped — resuming a hold
   shifts due dates anyway.
3. **Escalate.** `INSERT … ON CONFLICT DO NOTHING` the `escalation` row. Only when a row was
   inserted: record `escalation.raised`, resolve recipients, write one `notification` per recipient,
   record `notification.sent` for each. A re-run or an overlapping sweep inserts nothing and does
   nothing more.
4. **Retry email.** Send every `ESCALATION` notification with `emailed_at` null.

### 6.2 Recipients — `RecipientResolver`

Start from the late person: a task's assignee; for a milestone or clock, the case's `OWNER`
participant. Then the first that applies:

1. their `manager_id`, if `ACTIVE` and not themselves → `MANAGER`;
2. their department's `head_user_id`, if `ACTIVE` and not themselves → `DEPARTMENT_HEAD`;
3. every `ACTIVE` user holding the Administrator template → `ADMINISTRATORS`.

A task with no assignee, or a case with no owner, starts at step 3. Provisioning creates an
administrator, so step 3 normally has at least one recipient. The one way it can be empty is a tenant
that has deactivated every administrator; then the sweep still writes the `escalation` row (so the
war room shows it, routed `ADMINISTRATORS` with no one to deliver to) and logs an error naming the
tenant on every sweep until an administrator exists. Invariant 6 is therefore "every overdue item is
escalated and recorded", not "every escalation is delivered".

**Amendment (Task 31 e2e, 2026-10-03):** there is a second way step 3 comes up empty. Step 3 excludes
the late person, so a tenant's *sole* active administrator who owns a late milestone or clock yields
no recipient although an active administrator exists, and the "no active administrator" error log is
then misleading. Open for 6B or a follow-up: fall back to the late administrator themselves, or word
the log for both cases.

### 6.3 Email

Sent after commit, never inside the transaction, through the existing `EmailSender`, plain text,
with a link to the case. `notification.emailed_at` is stamped on success. A failed send is logged and
retried by step 4 of the next sweep, so an SMTP outage delays a mandatory escalation but never loses
it. No retry cap in this sub-project; 6B adds delivery tracking.

### 6.4 Partition job

A migration adds `ensure_audit_event_partitions(months_ahead int)`: `SECURITY DEFINER`, owned by
the migration owner, `SET search_path = public, pg_temp`, looping `create_audit_event_partition`
over the current month through `months_ahead` ahead; `EXECUTE` granted to `onboarding_app` and
revoked from `PUBLIC`. It takes no table name and builds none from input, so it can create nothing
but an audit partition. The daily job calls it with 3, globally rather than per tenant, under the
same advisory-lock guard. The application role still holds no DDL privilege (invariant 8).

## 7. Authorization

### 7.1 Permissions

| Key | Scopes | Gates |
|---|---|---|
| `sla.view` | ALL, DEPARTMENT, TEAM, ASSIGNED | the war room feed |
| `calendar.manage` | ALL only | calendar, holidays, SLA policy |

The clock on a single case is read under `case.view` — anyone who can open the case sees its clock.
Editing `managerId` reuses `user.manage`; editing `headUserId` reuses `department.manage`. Both ids
are resolved through `AuthorizedQuery` before being written.

### 7.2 Descriptors

`scoping.SlaClockDescriptor` and `scoping.EscalationDescriptor` scope by the row's `case_id`,
delegating to the case's own predicates — the shape `CaseParticipantDescriptor` already has. Both
exist for `AuthorizedQuery`'s entity-type dispatch, not only `DescriptorRegistry.validate()`.
`Notification` is read by recipient only (`recipient_user_id = actor`) and has no HTTP read path in
this sub-project.

### 7.3 Role templates

Administrator: both. Operations: `sla.view` at DEPARTMENT. Project Manager: `sla.view` at TEAM.
`RoleTemplateCoverageTest` passes with no new exclusion (`calendar.manage` is ALL-only by catalog).

## 8. API surface

| Method & path | Gate | Notes |
|---|---|---|
| `GET /cases/{id}/sla-clock` | `case.view` | current clock, else the last stopped one; 404 if none |
| `GET /cases` | `case.view` | rows gain `slaClock`; new `sla=AT_RISK\|BREACHED\|PAUSED` filter |
| `GET /sla/exceptions` | `sla.view` | summary counts + three columns of clock cards with escalation history |
| `GET`/`PUT /admin/business-calendar` | `calendar.manage` | timezone, working days |
| `POST /admin/business-calendar/holidays` | `calendar.manage` | 409 on a duplicate date |
| `POST /admin/business-calendar/holidays/{id}/remove` | `calendar.manage` | |
| `GET`/`PUT /admin/sla-policy` | `calendar.manage` | |
| user create/update, `UserView` | `user.manage` | gain `managerId`; 422 for self |
| department request/view | `department.manage` | gain `headUserId` |
| `StageRequest` / stage view | existing | gain `pausesOnCustomer` |
| `POST /document-requests/{id}/remind` | `document.request` + `StageWriteScopeGuard` | 422 no contact or not OPEN; 409 if reminded < 24h ago |
| `POST /dev/clock/offset` | dev/test profiles only | §10.3 |

`SlaClockView`: `targetDays`, `elapsedDays`, `pausedDays`, `remainingDays`, `state`, `atRisk`,
`dueToday`, `pauseReason`, `pauseEligible`, `escalatedAt`, `escalatedTo` (`{userId, name}` or
`ADMINISTRATORS`), `calendarName`. Computed once, by `SlaClockReader`; the browser only formats it.

**The `pausesOnCustomer` trap, avoided deliberately.** `StageRequest.autoAdvance` is a primitive
`boolean`, so an omitted key binds to `false` (CLAUDE.md's Tests section). `pausesOnCustomer` is a
`Boolean` normalised to `true` when null, at the one place `WorkflowService` builds a stage, with a
test that omits it.

Every `PUT` is a full replace and its view carries every field its request accepts. Out-of-scope and
cross-tenant ids are 404, never 403.

## 9. Screens

Every frontend task invokes `frontend-design` and `ui-ux-pro-max` first (CLAUDE.md). Mono for every
number, ID and date; status colour always paired with a word; cards flat.

### 9.1 Shared components

- **`SlaChip`** + `formatSlaClock(view)` — the one formatter: `PAUSED 3.1d`, `BREACHED 2.0d`,
  `1.2d LEFT`, `MET`, `DUE TODAY`.
- **`SlaClockCallout`** — the case right rail's SLA CLOCK block: state, reason, `NOT ELIGIBLE FOR
  PAUSE — INTERNAL REVIEW` when ineligible, calendar name.

### 9.2 War room (SCREENS §4) — `/sla`

Nav item gated by `sla.view`. Eyebrow `SLA WAR ROOM · BUSINESS DAYS · <CALENDAR>`; summary strip
`BREACHED · DUE TODAY · CLOCKS PAUSED · AUTO-ESCALATED` (escalations raised in the last 7 days);
columns Breached / Due today / Watch (at risk). Each card: case and customer, elapsed / target, the
chip, owner, and a note with the case's escalation history ("Escalated to Priya's manager on 21 Aug
(automatic, day 1 overdue)") or the pause reason. Actions: **Reassign** (case owner, or a listed
task's assignee, through existing endpoints), **Force-complete** (existing dialog), **Remind
customer** (when the case has open requests: each listed with its reminder count and last-sent time
and its own Remind button). The policy panel states that escalation cannot be opted out of. Polls
every 60 seconds.

### 9.3 Elsewhere

- **Cases list (§2):** SLA clock column; saved filter `SLA at risk · N`.
- **Case workspace (§3):** header chip; right-rail callout.
- **Builder (§9):** stage inspector's "Pause on customer" toggle, live, beside SLA days.
- **Administration → Business calendar** (no design; built in the bundle's language): timezone
  select, working-day toggles, holiday list with add/remove, the two policy numbers.
- **Users / Departments:** manager picker on the user edit dialog; head picker on departments.

### 9.4 Gaps the design does not cover

Loading skeletons, empty states (a war room with nothing breached says so plainly) and error states
for every new surface. Below-1440px layouts: the war room's three columns stack below 900px, matching
the cases list's card fallback breakpoint.

## 10. Testing

### 10.1 Backend

- **Clock maths (unit, fixed calendar):** weekends, holidays, a timezone far from UTC across a day
  boundary, overlapping pauses counted once, fractional days, ineligible stages pausing for hold only.
- **Lifecycle (real engine):** entry starts a clock, exit stops MET/BREACHED, a skipped stage gets
  none, force-complete stops it, a customer-template case starts paused, re-entry gives a fresh
  clock, a request opened then fulfilled pauses and resumes, two open requests close one interval
  only when both close, every port call rolls back with its business change.
- **Sweep:** breach stamped once; two concurrent sweeps on two real connections produce one
  escalation (the `ReconcileConcurrencyTest` shape); held, cancelled and done never escalate; a
  re-dated task escalates again; a failed email is retried next sweep.
- **Recipients:** manager → head → administrators; inactive skipped; a head is never sent their own
  escalation.
- **Calendar:** `TenantBusinessCalendar` caches within, never across, transactions; due dates
  computed after a holiday is added skip it, stored ones do not move.
- **Security negatives:** `sla.SlaIsolationTest` (cross-tenant 404 on every new endpoint);
  `sla.SlaScopeTest` (a TEAM-scoped `sla.view` holder sees only their teams' cases in the war room —
  the narrowest-scope write/read test CLAUDE.md requires); `security.SystemActorTest` (exact-set
  assertion on `SystemPermissions`); the `SECURITY DEFINER` function rejects anything but an
  integer and creates only `audit_event_*`.
- **Architecture:** the two new `ModuleBoundaryTest` rules proven red first; `sla..` added to
  `AuthorizationCoverageTest`'s finder rule in the same commit as the first service; `RlsCoverageTest`
  green with no allowlist change; `AuditPartitionCoverageTest` stays green.

### 10.2 Frontend

Vitest for `formatSlaClock`, `SlaChip`, `SlaClockCallout`, the war room, the calendar screen and the
pickers. `tsc --noEmit` and `npm run lint` on every frontend task (sub-project 3A's recorded blind
spot), not only at the end.

### 10.3 End-to-end and the clock offset

`POST /dev/clock/offset {"seconds": n}` shifts the application `Clock` bean (a `MutableClock`-shaped
wrapper in main code, registered only under `dev` and `test`). The endpoint and its controller are
`@Profile({"dev","test"})`; a test asserts the mapping is absent under the default profile, and the
bean is a plain `Clock.systemUTC()` there. It requires an authenticated administrator even where it
exists.

`sla.spec.ts`: seed a stage with a 1-day SLA, assign a manager to the case owner, advance the clock
past a business day, run the sweep (a dev/test-only `POST /dev/jobs/sla-sweep`, same profile gate),
and see the case breach, the escalation email to the manager in `backend.log`, the war room card in
Breached with its note, and Remind customer increment a request's count.

## 11. Invariant cross-check — sub-project 6's own ten

1. Every clock change happens in the same transaction as the business change that caused it.
2. Stage clock calls are made only where `currentStageId` changes (`CaseEngine`, `MilestoneService.reopen`
   — §1.2.1); no new caller of `reconcile`.
3. `journey` and `document` never import an `sla` type.
4. Elapsed time is always derived, never stored.
5. An escalation fires at most once per subject and due date, enforced by the database.
6. Every overdue item past the threshold is escalated and recorded; escalation has no off switch.
7. The system actor's permission set is a code constant, never a `user_role` row.
8. The application role never runs DDL; partitions come only through the hardened function.
9. SLA mechanics never reach a `timeline_visible` event.
10. Out-of-scope and cross-tenant ids are 404; `PUT` request/view types stay field-for-field aligned.

## 12. Inherited state

### 12.1 Carried in, untouched by this sub-project's path

TEAM-scoped user creation (`CreateUserRequest` has no `teamIds`); the builder's missing
attribute/entry-condition UI; the audit-timeline read's `AuthorizedQuery` carve-out; sub-project 4's
and 5's own open lists. None is on this path.

### 12.2 Touched deliberately

- `WeekdayBusinessCalendar` is retired; `BusinessCalendar` gains two methods.
- `CaseEngine` gains port calls at stage entry/exit — the first change to it since sub-project 3A's
  javadoc fix. No change to `reconcile`'s mutation logic or its lock.
- `TopBar.test.tsx`'s "ships no dead notification controls" assertion stays true: no inbox ships here.
- CLAUDE.md: correct the `portal_visible` attribution (§2.2); add "What sub-project 6B inherits".

### 12.3 Carried forward to 6B

The `notification` table and its `type` check to widen; the scheduler and `TenantJobRunner`;
`atRisk` to alert on; `reminders_sent` to drive automatic reminders; `document.expiresAt` and the
agreement expiry fields to remind against.

## 13. Question mapping

| Q | Where |
|---|---|
| Q8 (business days, pause on customer) | §3.3, §5 |
| Q10 (mandatory escalation) | §6, invariant 6 |
| Q19 (alert types, horizons) | 6B; `atRisk` computed here (§5) |
| Q23 (plan hold pauses SLA) | §3.2 (`held` at creation) |
| Q24 (`portal_visible` is sub-project 7's) | §2.2 |
