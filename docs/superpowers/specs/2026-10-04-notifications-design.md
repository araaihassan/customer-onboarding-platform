# Notifications — Design Spec

**Sub-project 6B.** Adds the `notification` module: an in-app inbox with per-user, per-type
preferences (in-app and email, escalation locked on), daily and weekly email digests, fourteen
opt-out-able notification types on top of sub-project 6's mandatory escalation, tenant-configurable
deadline horizons and expiry/renewal reminders, automatic customer document-request reminders,
tenant notification templates that give `stage.notification_template_key` its consumer, a single
`email_outbox` with attempt tracking and a retry cap for every notification-class email, and a
configured public base URL for email links. On the frontend: the top-bar Inbox control and drawer
(COMPONENTS §16), its preferences pane, Administration → Notifications, and the builder's template
picker.

Depends on 1 (identity, authz, audit, `EmailSender`), 2 (journey, `CaseEngine`, workflow), 3 (tasks,
comments), 3A (plan hold), 4 (documents, requests), 5 (agreements), 6 (`notification` table,
`TenantJobRunner`, `SystemPrincipal`, the business calendar, `atRisk`, escalation). Unblocks 7 (portal
notifications reuse the pipeline with PORTAL recipients), 8 (real-time invalidation of the inbox;
dashboard "Recent notifications").

---

## 1. Context

PRD §13 lists the notification catalogue, the channels (in-app, email; SMS optional; Teams/Slack
future) and the delivery policy: per event or as a daily/weekly digest, configurable deadline
horizons, every type opt-out-able except manager escalation. PRD §9 asks for agreement renewal
reminders, §11 lists "notifications sent" on the activity history. QA Q10 (clarified 2026-08-29)
makes escalation mandatory and everything else opt-out-able; Q19 adds stage entered/exited alerts
(the consumer of `stage.notification_template_key`), risk-state alerts to stakeholders, digests, and
configurable horizons replacing a fixed 48 hours.

The design handoff draws the top-bar Inbox button, ⌘J, and a 390px drawer with a list pane and a
preferences pane (COMPONENTS §16, SCREENS overlays L430–443, DOMAIN_RULES §Q10). The prototype's
preferences show nine types with a channels caption and a single toggle; STATE_AND_DATA L268–275
gives `NotificationPreference { type, channels[], enabled }` and `GET /notifications`,
`PATCH /notifications/preferences`. No digest UI, no mark-read and no empty state are drawn.

What exists today, verified against the code at `4bca10f`:

- `notification` (`V31__sla.sql`): one row per recipient, `type CHECK (type IN ('ESCALATION'))`,
  `title`, `body`, `link_path`, `case_id`, `escalation_id`, `read_at`, `emailed_at`; grants
  `SELECT, INSERT, UPDATE`. Owned by `sla` today (`sla.Notification`, `NotificationRepository`,
  `NotificationType { ESCALATION }`). `scoping.NotificationDescriptor` resolves every scope to
  `recipient_user_id = actor`. No HTTP read path.
- Escalation emails go through `sla.EscalationMailer` after commit; `SlaSweepService.retryUnsentEmail`
  resends every `ESCALATION` row with `emailed_at` null, every sweep, no cap.
- `DocumentRequestService.remind` (gated `document.request`) emails the contact after commit and
  advances `reminders_sent`/`last_reminded_at` whether or not the send succeeds.
- `auth.EmailSender` is one method, `send(EmailMessage(to, subject, body))`, plain text, with
  `SmtpEmailSender` and the dev/test `LoggingEmailSender`.
- `stage.notification_template_key` (`V12`) round-trips and is read by nothing; `StageInspector`
  renders it disabled with an "arrives later" hint, and `StageInspector.test.tsx` asserts that.
- `TopBar.tsx` ships no inbox; `TopBar.test.tsx` asserts no button matches `/notification/i`.
- `SlaClockView.atRisk` is computed and alerts nobody. `Document.expiresAt`, `Agreement.expiresAt`,
  `renewalDate`, `noticePeriodDays`, `DocumentRequest.dueAt`, `Task.dueDate`, `Milestone.dueDate`
  exist and are reminded against by nothing.
- Stage transitions call `journey.SlaClockLifecycle.stageEntered/stageExited` from
  `CaseEngine` (L348, L523) and `MilestoneService` (L225–226).

### 1.1 Decisions taken in brainstorming (2026-10-04)

1. **One sub-project, the whole documented scope** — inbox, preferences, every type, reminders and
   horizons, digests, delivery hardening. Not split into 6B/6C.
2. **Catalogue: the union of PRD §13 and the design, minus types with no producer.** The design's
   nine plus New customer, Stage entered/exited, Risk state change, Document uploaded, and Expiry &
   renewal. "Customer commented" becomes **New comment** (internal comments, sub-project 3); a
   customer-authored variant arrives with 7. "Deadline in 48 hours" becomes **Deadline approaching**
   with configurable horizons. Escalation is listed, locked on.
3. **Recipients are a fixed relationship table** (§5.2), never "everyone who holds a permission",
   and never a follow/watch subscription. The actor is never notified of their own action; only
   ACTIVE INTERNAL users receive; a recipient must be able to view the subject at write time.
4. **Preferences: per type an in-app toggle and an email toggle; one per-user email cadence**
   (Immediately / Daily digest / Weekly digest). In-app is always per event. Defaults come from the
   prototype's channel captions.
5. **Horizons are tenant-wide, per dated kind**, edited by an administrator. Users only opt in or
   out of "Deadline approaching" and "Expiry & renewal".
6. **Stage alerts use tenant notification templates and fire only for keyed stages.** A template
   carries entered/exited subject and body with a fixed placeholder set; the builder field becomes a
   picker. A stage with no key sends no stage alert.
7. **Events are captured in-transaction.** Producers publish event records through Spring's
   `ApplicationEventPublisher`; a synchronous listener in `notification` writes rows in the same
   transaction. Email is delivered by a dispatcher from an outbox; time-based alerts and digests run
   on `TenantJobRunner`.
8. **Automatic customer reminders follow a tenant cadence** (on/off, every N business days, at most M),
   sharing the manual button's counters and 24-hour floor.

### 1.2 Decisions taken while writing this spec

These were not asked in brainstorming; each is the default the spec commits to and is open to
correction at spec review.

1. **Automatic reminders default to off** for every tenant, new and existing (interval 3, max 3 once
   enabled). Turning them on for existing tenants by migration would start emailing customers
   without anyone at the tenant having decided to.
2. **Escalation email is always immediate**, regardless of the recipient's digest cadence — a
   mandatory alert delayed to a weekly digest is not the alert Q10 requires.
3. **The inbox is a drawer only.** The prototype's sidebar "Inbox" item points at a screen it never
   draws; SCREENS.md defines only the drawer.
4. **"Mark all read"** is added to the drawer header (the design has no way to clear the badge but
   one row at a time).
5. **`PUT` replaces the design's `PATCH`** for preferences, keeping request and view field-for-field
   aligned (standing invariant).
6. **Workflow published notifies once per case owner**, summarising how many of their open cases are
   pinned to an older version of that template ("3 of your cases are on v2.3 — v2.4 is live").
7. **Agreement status changes notify the agreement owner and the case owner** on every transition
   into `UNDER_REVIEW`, `APPROVED`, `DRAFT` (a rejection), `SENT`, `SIGNED` and `CANCELLED`
   (`AWAITING_SIGNATURE`, a partially signed agreement, is not announced). Reviewers are not a relationship (any `agreement.review`
   holder may review), so "submitted for review" does not reach Legal; that waits for a review-queue
   feature.

---

## 2. Scope

### 2.1 In

- The `notification` module owning the `notification` table, preferences, settings, templates,
  horizons, policy and the email outbox.
- Fourteen opt-out-able types (§5.1) plus the locked `ESCALATION`.
- `authz.RecipientAccess` — "can this other user view this record?" (§7.2).
- The notification sweep (task overdue, deadline approaching, expiry & renewal, automatic customer
  reminders), risk alerts in the SLA sweep, the digest job, the outbox dispatcher.
- `APP_PUBLIC_BASE_URL` and absolute links in every email.
- Delivery tracking: attempts, backoff, a cap, FAILED and SKIPPED states, for notification, digest,
  escalation and customer-reminder email. Manual remind moves onto the outbox.
- Sub-project 6's sole-administrator escalation fallback (§5.4).
- Frontend: Inbox control and drawer, preferences pane, Administration → Notifications (horizons,
  automatic reminders, templates), the builder template picker, dev-tool buttons for e2e.

### 2.2 Out, and why

- **SMS, Microsoft Teams, Slack** — PRD marks SMS optional, the others future.
- **Portal-user notifications** — sub-project 7 owns every portal surface. The pipeline excludes
  PORTAL recipients now; 7 widens that deliberately.
- **Real-time push** — sub-project 8 (WebSocket invalidation). The badge polls.
- **Q17 new-joiner catch-up** — its own feature; Q17 only says it *may* reuse digests.
- **A notification for every audit action** — only the catalogue in §5.1.
- **Back-filling escalations raised while a tenant had no administrator** — left open by 6, not
  picked up here.
- **Invitation and password-reset email** — stay on sub-project 1's direct send; they are
  transactional auth mail, not notifications.
- **HTML email and localisation of email copy** — plain-text English, as today.
- **A notifications screen, filters, archive or delete** — the drawer lists and pages; rows are never
  deleted (DELETE stays revoked).

---

## 3. Architecture

### 3.1 The module

`co.ara.onboarding.notification` owns: `Notification`, `NotificationType`, `NotificationRepository`
(moved from `sla`), `NotificationPreference`, `NotificationSettings`, `NotificationTemplate`,
`DeadlineHorizon`, `NotificationPolicy`, `EmailOutbox`, the event listener, the recipient resolver,
the per-type renderers, `InboxService`, `NotificationAdminService`, the sweep/digest/dispatch
services, and their controllers.

Dependency arrows (each a `ModuleBoundaryTest` rule, proven red before the module uses it):

- `notification → task, journey, document, agreement, workflow, customer, identity, authz, audit,
  tenancy, platform` — to read the subjects and resolve recipients.
- `sla → notification` — escalation writes go through `notification`'s API (`NotificationWriter`).
- **No producer imports a `notification` type**: `noTaskDependencyOnNotification`,
  `noJourneyDependencyOnNotification`, `noDocumentDependencyOnNotification`,
  `noAgreementDependencyOnNotification`, `noWorkflowDependencyOnNotification`,
  `noCustomerDependencyOnNotification`.
- Jobs live in `scheduling` beside `SlaSweepJob` and call `notification` services.

### 3.2 Events

Each producer declares its event records in its own package and publishes them with
`ApplicationEventPublisher.publishEvent` **after** recording its own audit action (cause before
effect, `CauseBeforeEffectTest`). Records carry ids only, never entities.

| Producer | Event | Published from |
|---|---|---|
| task | `TaskAssigned(taskId, caseId, assigneeId, actorId)` | `TaskService` create/update when the assignee changes to a non-null user; never on cancel |
| task | `CommentAdded(commentId, resourceType, resourceId, caseId, authorId)` | `CommentService.create` |
| journey | `MilestoneCompleted(milestoneId, caseId, actorId)` | `CaseEngine`, where a milestone becomes `DONE` (natural or forced) |
| journey | `StageEntered(caseId, stageId, actorId)` / `StageExited(caseId, stageId, actorId)` | beside every `SlaClockLifecycle.stageEntered/stageExited` call (`CaseEngine`, `MilestoneService`) |
| document | `DocumentRequested(requestId, caseId, actorId)` | `DocumentRequestService.create` and `DocumentInstantiation` |
| document | `DocumentUploaded(documentId, requestId?, caseId, actorId)` | `DocumentRequestService.fulfil`; `DocumentService.uploadFromPortal` |
| document | `DocumentReviewed(documentId, requestId?, caseId, decision, actorId)` | `DocumentReviewService.review` |
| agreement | `AgreementStatusChanged(agreementId, caseId, from, to, actorId)` | each `AgreementService` transition in §1.2.7 |
| workflow | `WorkflowVersionPublished(templateId, versionId, actorId)` | `WorkflowService.publish` |
| customer | `CustomerCreated(customerId, ownerUserId, actorId)` | `CustomerService.create` |
| sla | `EscalationRaised(...)` | replaced by a direct `NotificationWriter` call (sla already depends on notification) |
| sla | `RiskChanged(clockId, caseId, stageId, state)` | `SlaSweepService` (§6.1) |

`journey` gains no new caller of `CaseEngine.reconcile`; the publishes sit next to existing port
calls. `actorId` is null for system-originated events.

### 3.3 The listener

`NotificationListener` handles every event with a plain synchronous `@EventListener`: it runs inside
the producer's transaction, at the publish point, so a notification commits or rolls back with its
action. It never mutates the subject and never calls `reconcile`. Every event runs the same pipeline
(§5.3). A failure is a bug that rolls the action back, the same as any other in-transaction write —
there is no swallow-and-continue, which would make "the action happened but nobody was told"
silently possible.

### 3.4 Jobs

All on `TenantJobRunner` as the `SystemPrincipal`; every email-sending step is its own run after the
work it reports has committed.

| Job | Cadence | Does |
|---|---|---|
| `SlaSweepJob` (existing) | 5 min | adds risk alerts (§6.1); its email-retry run is removed (the dispatcher replaces it) |
| `NotificationSweepJob` | hourly | task overdue, deadline approaching, expiry & renewal, automatic customer reminders (§6.2) |
| `DigestJob` | 15 min | daily/weekly digests at 08:00 tenant-local (§6.3) |
| `EmailDispatchJob` | 1 min | sends due `email_outbox` rows (§6.4) |

---

## 4. Data model

All tables tenant-owned: non-null `tenant_id`, RLS policy and `FORCE ROW LEVEL SECURITY` in the same
migration (`enable_tenant_rls`), UUIDv7 keys, `timestamptz`, no `DELETE` grant unless stated.
Migrations start at `V35`.

### 4.1 `notification` (widened)

- `type` CHECK widened to the fifteen values of §5.1.
- New: `subject_type varchar(32) NOT NULL` (backfilled `'case'` for escalations), `subject_id uuid
  NOT NULL` (backfilled from `case_id`), `in_app boolean NOT NULL DEFAULT true`, `email_state
  varchar(16) NOT NULL CHECK (email_state IN ('NONE','QUEUED','DIGEST_PENDING','DIGESTED'))`,
  `dedupe_key varchar(200) NULL`.
- `UNIQUE (tenant_id, recipient_user_id, dedupe_key)` — NULLs distinct, so event-driven rows (no key)
  are unconstrained and sweep-driven rows can never repeat.
- `emailed_at` stays, stamped by the dispatcher when the row's own immediate email is SENT (existing
  escalation tests read it). Backfill: rows with `emailed_at` set → `QUEUED`; escalation rows with it
  null → an `email_outbox` row each, `QUEUED`.
- Index `(tenant_id, recipient_user_id, created_at DESC) WHERE in_app`, and
  `(tenant_id, recipient_user_id) WHERE in_app AND read_at IS NULL` for the badge.

### 4.2 Preferences and settings

- `notification_preference (id, tenant_id, user_id → app_user, type, in_app_enabled, email_enabled,
  created_at, updated_at)`, `UNIQUE (tenant_id, user_id, type)`, `type` CHECK excludes `ESCALATION`.
  A row exists only where the user differs from the code default (§5.1). `SELECT, INSERT, UPDATE`.
- `notification_settings (id, tenant_id, user_id UNIQUE, email_cadence CHECK IN ('IMMEDIATE','DAILY',
  'WEEKLY') DEFAULT 'IMMEDIATE', last_digest_at timestamptz NULL, ...)`. Created lazily.

### 4.3 Templates

`notification_template (id, tenant_id, key varchar(64), name varchar(120), entered_subject
varchar(200), entered_body text, exited_subject varchar(200) NULL, exited_body text NULL, active
boolean, created_by, created_at, updated_at)`, `UNIQUE (tenant_id, key)`; `key` matches
`^[a-z0-9][a-z0-9_.-]{0,63}$` (CHECK and bean validation), immutable after create. Exited fields both
null means the template alerts on entry only.

### 4.4 Horizons and policy

- `deadline_horizon (id, tenant_id, kind CHECK IN ('TASK_DUE','MILESTONE_DUE','DOCUMENT_REQUEST_DUE',
  'DOCUMENT_EXPIRY','AGREEMENT_EXPIRY','AGREEMENT_RENEWAL'), lead_days int CHECK (lead_days BETWEEN 1
  AND 90), ...)`, `UNIQUE (tenant_id, kind, lead_days)`; at most five per kind (service rule). `GRANT
  DELETE` with a comment: a lead time is configuration replaced as a set, not a business record.
  Units are fixed per kind: the three `*_DUE` kinds count **business days** on the tenant calendar,
  the three expiry/renewal kinds **calendar days**.
- Defaults seeded at provisioning and backfilled for existing tenants: `*_DUE` → 2; expiry/renewal →
  30, 14, 7.
- `notification_policy (id, tenant_id UNIQUE, auto_remind_enabled boolean DEFAULT false,
  auto_remind_interval_days int CHECK 1–30 DEFAULT 3, auto_remind_max int CHECK 1–10 DEFAULT 3, ...)`.

### 4.5 Outbox

`email_outbox (id, tenant_id, kind CHECK IN ('NOTIFICATION','DIGEST','CUSTOMER_REMINDER'),
to_address, recipient_user_id NULL, contact_id NULL, notification_id NULL, document_request_id NULL,
subject, body, status CHECK IN ('PENDING','SENDING','SENT','FAILED','SKIPPED'), attempts int,
next_attempt_at, lease_until NULL, last_error text NULL, sent_at NULL, created_at, updated_at)`.
Exactly one of `recipient_user_id`/`contact_id` is set (CHECK). Index on `(status, next_attempt_at)`
for due rows. `SELECT, INSERT, UPDATE`.

A digest's notifications point at it through a join table `email_outbox_item (outbox_id,
notification_id, tenant_id)`, append-only (`SELECT, INSERT`).

### 4.6 `sla_clock`

Adds `at_risk_alerted_at timestamptz NULL`.

### 4.7 Audit actions

All `timeline_visible = false` (delivery and configuration records, compliance only — Q19 asks for a
deliberate decision per type, and none of these is customer-facing history) **except**
`document_request.reminded`, which stays `true` as today and gains `{"automatic": true}` in its payload
and "(automatic)" in its summary when the job sends it.

`notification.sent` (now for every row, not only escalations — PRD §11's "notifications sent"),
`notification.preferences_changed`, `notification_template.created`, `.updated`, `.deactivated`,
`notification_policy.updated`, `email.failed`.

---

## 5. Notifications

### 5.1 Types and defaults

| Type | Label (preferences pane) | In-app | Email | Tone |
|---|---|---|---|---|
| `ESCALATION` | Escalation to you (required by policy) | locked on | locked on, always immediate | risk |
| `TASK_ASSIGNED` | Task assigned to me | on | on | info |
| `TASK_OVERDUE` | Task overdue | on | on | warn |
| `NEW_CUSTOMER` | New customer | on | off | info |
| `MILESTONE_COMPLETED` | Milestone completed | on | off | ok |
| `STAGE_CHANGED` | Stage entered or exited | on | off | info |
| `DOCUMENT_REQUESTED` | Document requested | on | on | info |
| `DOCUMENT_UPLOADED` | Document uploaded | on | on | info |
| `DOCUMENT_DECIDED` | Document approved or rejected | on | on | ok / risk by decision |
| `AGREEMENT_STATUS` | Agreement status changed | on | on | info |
| `NEW_COMMENT` | New comment | on | on | info |
| `WORKFLOW_PUBLISHED` | Workflow version published | on | off | info |
| `RISK_CHANGED` | Journey at risk or breached | on | on | warn / risk |
| `DEADLINE_APPROACHING` | Deadline approaching | on | off | warn |
| `EXPIRY_RENEWAL` | Expiry and renewal | on | on | warn |

The prototype's "Agreement status changed — Email" default is widened to in-app too, so it appears
in the inbox like every other type.

### 5.2 Recipients

"Case audience" = the case's `owner_user_id` plus every `case_participant` user.

| Type | Candidates |
|---|---|
| `TASK_ASSIGNED` | the new assignee |
| `TASK_OVERDUE` | the assignee |
| `NEW_CUSTOMER` | the customer's owner |
| `MILESTONE_COMPLETED` | case audience + the milestone owner |
| `STAGE_CHANGED` | case audience (keyed stages only, §5.5) |
| `DOCUMENT_REQUESTED` | the case owner |
| `DOCUMENT_UPLOADED` | the request's `requested_by` + the case owner |
| `DOCUMENT_DECIDED` | the request's `requested_by` + the case owner + the uploader if internal |
| `AGREEMENT_STATUS` | the agreement owner + the case owner |
| `NEW_COMMENT` | task thread: the assignee + earlier commenters; journey thread: the case owner + earlier commenters |
| `WORKFLOW_PUBLISHED` | owners of open cases pinned to an older version of that template (one row each) |
| `RISK_CHANGED` | case audience |
| `DEADLINE_APPROACHING` | task → assignee; milestone → milestone owner; document request → `requested_by` |
| `EXPIRY_RENEWAL` | document → the case owner; agreement → the agreement owner + the case owner |
| `ESCALATION` | unchanged — `RecipientResolver` (sub-project 6 §6.2), plus §5.4 |

### 5.3 The pipeline

For each event, in order:

1. **Candidates** from §5.2, de-duplicated.
2. **Drop** the actor; any user not `ACTIVE`; any user whose `user_type` is not `INTERNAL`.
3. **Visibility.** Drop any candidate for whom `RecipientAccess.canView(userId, permission, entity,
   id)` is false (§7.2). The permission is the subject's view permission: `task.view`, `case.view`,
   `customer.view`, `document.view` (audience filter applies), `agreement.view`, `workflow.view`.
4. **Preferences.** Resolve (row ?? code default). Both channels off → skip. Otherwise insert the
   row with `in_app` from the preference and `email_state`:
   - email off → `NONE`;
   - cadence `IMMEDIATE` → `QUEUED`, plus an `email_outbox` row in the same transaction;
   - cadence `DAILY`/`WEEKLY` → `DIGEST_PENDING`.
5. **`ESCALATION` skips step 4**: `in_app = true`, `QUEUED`, outbox row, always.
6. Record `notification.sent` per row.

Sweep-generated rows carry a `dedupe_key` and insert with `ON CONFLICT DO NOTHING`.

### 5.4 Escalation changes

`sla` writes escalation notifications through `NotificationWriter.escalation(...)`, which applies
steps 5–6 — no preference, no visibility drop (the recipient resolver already decided, and an
escalation recipient is a manager, head or administrator by construction). `EscalationMailer` and the
retry run are deleted; the outbox delivers. **Sole-administrator fallback:** when the
`ADMINISTRATORS` step would be empty only because the late person is the sole active administrator,
the escalation goes to that administrator; the "no active administrator" ERROR log is reserved for
the case where none exists. Spec 6 §6.2's amendment is closed.

### 5.5 Content

- Plain-text English, one renderer per type in code: `title` (≤ 120 chars), `body` (≤ 500), and
  `link_path` to the record (case workspace, with a tab/anchor where one exists).
- **Stage alerts** render the stage's template: `{case}`, `{customer}`, `{stage}`, `{owner}` are
  substituted; any other `{…}` token is refused at template save (422) and never reaches render. A
  stage whose key names no template, or an inactive one, or an exited event on an entry-only
  template, sends nothing.
- **Email** = subject (the title, or the template subject) + body + `"\n\nOpen: " + baseUrl +
  link_path`. A digest lists its rows grouped by type, newest first, each with its link.
- Staff-written strings (case names, comment text) are inserted verbatim — the channel is plain text
  and the inbox renders text, never HTML.

---

## 6. Scheduled work

### 6.1 Risk alerts (SLA sweep)

After its breach step, per running clock: if `atRisk` and `at_risk_alerted_at` is null, stamp it and
publish `RiskChanged(AT_RISK)`. When the sweep stamps `breached_at`, it also publishes
`RiskChanged(BREACHED)`. Dedupe keys `RISK:{clockId}:AT_RISK` / `:BREACHED`. A new stage visit is a
new clock, so it re-arms.

### 6.2 Notification sweep

Each candidate is evaluated in the tenant's calendar and timezone; "today" is tenant-local.

- **Task overdue** — open (not `COMPLETED`/`CANCELLED`) tasks with `due_date < today`: key
  `TASK_OVERDUE:{taskId}:{dueDate}`.
- **Deadline approaching** — for each `*_DUE` horizon lead L, open items whose due date is in the
  future and `businessDaysBetween(today, due) ≤ L`: key `DEADLINE:{kind}:{id}:{due}:{L}`. A moved
  due date is a new key, so it re-arms. When several leads match on one run (a newly created item),
  only the smallest matching L is sent and the larger ones are recorded as consumed so they never
  fire late. A consumed marker is a `notification` row with `in_app = false`, `email_state = NONE`
  and its dedupe key — it never appears in the inbox, is never emailed, records no
  `notification.sent`, and is written even for a recipient who has the type switched off (so opting
  back in never releases a backlog of stale lead times).
- **Expiry & renewal** — the same shape in calendar days: document `expires_at` (status not
  `RETIRED`), agreement `expires_at` and `renewal_date − notice_period_days` (status not `CANCELLED`;
  renewal only when `renewal_date` is set). Key `EXPIRY:{kind}:{id}:{date}:{L}`.
- **Automatic customer reminders** — when `auto_remind_enabled`: `OPEN` requests with a resolvable,
  active contact, `reminders_sent < auto_remind_max`, and `businessDaysBetween(last_reminded_at ??
  requested_at, now) ≥ interval`, and not reminded in the last 24 hours (the manual floor). Calls
  `DocumentRequestService.remindAutomatically(requestId)` (gated `document.request`), which shares
  the manual path's message and counters but resolves the request, contact and case under
  `document.request` itself (the manual path resolves the contact under `contact.view`, which the
  system actor does not hold — amended at close-out), refuses any non-`SYSTEM` caller, **skips `StageWriteScopeGuard`** (a stage's
  write scope governs internal collaborators; this is tenant policy acting, the portal-write
  precedent's reasoning), queues a `CUSTOMER_REMINDER` outbox row, and records
  `document_request.reminded` with `automatic: true`.

**System actor.** `authz.SystemPermissions.forJobs()` gains exactly `document.request` at `ALL` —
the permission the manual remind is gated by — and nothing else; an exact-set test pins it. This is
broader than reminding: `document.request` also gates create, fulfil and withdraw. The mitigation is
structural, not by permission: the job calls only `remindAutomatically`, and
`ModuleBoundaryTest.onlyRemindAutomaticallyIsCalledOutsideDocument` forbids every class outside
`document` from calling any other `DocumentRequestService` method (amended at close-out: the
containment moved from a job-level test to this ArchUnit rule in Task 25's fix round). A narrower system-only permission
was considered and rejected: a catalogued key no role template holds would be a permission with no
UI and its own coverage-test carve-out.

### 6.3 Digests

Every 15 minutes, per tenant: users with cadence `DAILY` whose tenant-local time is ≥ 08:00 on a
working day and whose `last_digest_at` is before today's 08:00; `WEEKLY` likewise on the first working
day of the ISO week. Collect their `DIGEST_PENDING` rows; none → stamp `last_digest_at`, send nothing;
otherwise one `DIGEST` outbox row, `email_outbox_item` rows, and the notifications move to
`DIGESTED`. A user who switches to `IMMEDIATE` has pending rows flushed as one digest on the next run.

### 6.4 Dispatcher

Three steps, so no email is ever sent inside a transaction that can roll back:

1. **Claim** (tenant run): up to 50 rows `status IN ('PENDING','SENDING') AND next_attempt_at <= now
   AND (lease_until IS NULL OR lease_until < now)` `FOR UPDATE SKIP LOCKED`; set `SENDING`,
   `lease_until = now + 5 min`, `attempts + 1`.
2. **Send** outside any transaction through `EmailSender`. Before sending, a user recipient no longer
   `ACTIVE`, or a contact no longer active, → `SKIPPED`.
3. **Stamp** (tenant run): `SENT` + `sent_at` (+ the notification's `emailed_at` for `NOTIFICATION`
   kind); on failure `PENDING` with `next_attempt_at` after 1 min, 5 min, 30 min, 2 h, and `last_error`;
   after the fifth failed attempt `FAILED` and `email.failed` audited.

A crash between send and stamp leaves the lease to expire and the row is sent again: at-least-once,
documented, one duplicate at most per crash.

### 6.5 Public base URL

`app.public-base-url` (`APP_PUBLIC_BASE_URL`), an absolute `http(s)` URL without a trailing slash.
Required outside the `dev` and `test` profiles: a startup guard refuses a blank or malformed value
and names the variable (`PublicBaseUrlGuardTest`). Under `dev`/`test` it defaults to
`http://localhost:3000`. The e2e harness sets it explicitly.

---

## 7. Authorization

### 7.1 Permissions

- New `notification.manage` — ALL-only, Administrator. Gates the policy, horizons and templates.
- `GET /notification-templates/options` is gated `workflow.manage` so a workflow author can pick a
  template without `notification.manage`.
- Inbox and preferences need no permission (§7.3).

### 7.2 `RecipientAccess`

`authz.RecipientAccess.canView(UUID userId, String permission, Class<?> entity, UUID id)`. It builds
the recipient's `AuthContext` from scratch — grants resolved through the same `AuthorizationService`
path a request uses, including the `app_user.status = 'ACTIVE'` join — and runs the same
`AuthorizationPredicateBuilder.forPermission` predicate (record scope **and** any registered
`AudienceFilter`) as an existence query. It never reads a cached context and never returns the
record. It is infrastructure the notification pipeline depends on, excluded from the finder rule with
a written argument, and its negative tests are part of this sub-project's ten (§11): out-of-scope,
audience-filtered (a targeted document), deactivated, PORTAL, cross-tenant.

### 7.3 The inbox and preferences are self-service

`InboxService` and the preferences half of `NotificationPreferenceService` follow `MeService`'s
precedent: ungated, because they only ever touch rows whose `recipient_user_id` / `user_id` is the
caller, and a permission every role must hold is no permission. `AuthorizationCoverageTest` gains a
commented exclusion for each saying exactly that. Every id they accept (`POST
/notifications/{id}/read`) is looked up with `recipient_user_id = actor` in the predicate, so another
user's id and a cross-tenant id are both 404. `NotificationDescriptor` stays recipient-only.

### 7.4 Descriptors

`NotificationTemplate`, `DeadlineHorizon`, `NotificationPolicy` are tenant configuration gated by an
ALL-only permission, read through the gated admin service; `EmailOutbox` and `EmailOutboxItem` are
read only by jobs. None is read through `AuthorizedQuery`, so none needs a descriptor; if one later
is, it gets one.

---

## 8. API surface

Under `/api/t/{slug}`:

| Method | Path | Gate | Notes |
|---|---|---|---|
| GET | `/notifications?cursor=&limit=30` | self | `{items: NotificationView[], unreadCount, nextCursor}`; in-app rows, newest first |
| GET | `/notifications/unread-count` | self | `{unreadCount}` |
| POST | `/notifications/{id}/read` | self | idempotent; 404 if not yours |
| POST | `/notifications/read-all` | self | idempotent |
| GET | `/notifications/preferences` | self | `{emailCadence, types: [{type, inApp, email, locked}]}` |
| PUT | `/notifications/preferences` | self | full replace; `ESCALATION` with either channel off → 422; unknown type → 400 |
| GET/PUT | `/admin/notification-policy` | `notification.manage` | `{autoRemind: {enabled, intervalDays, max}, horizons: {KIND: [lead…]}}`; full replace |
| GET | `/admin/notification-templates` | `notification.manage` | all, active and inactive |
| POST | `/admin/notification-templates` | `notification.manage` | 409 on duplicate key; 422 on unknown placeholder |
| PUT | `/admin/notification-templates/{id}` | `notification.manage` | full replace except `key` (immutable; a differing key → 422) |
| GET | `/notification-templates/options` | `workflow.manage` | `[{key, name}]`, active only |

`NotificationView = {id, type, title, body, linkPath, tone, read, createdAt}`. Dev profile adds
`POST /dev/jobs/notification-sweep`, `/dev/jobs/digest`, `/dev/jobs/email-dispatch` beside the
existing sweep endpoint.

**Publish validation** gains a rule: a stage's `notificationTemplateKey`, when set, names an existing
template (active or not) in the tenant — 422 at publish otherwise.

---

## 9. Screens

Every frontend task invokes `frontend-design` and `ui-ux-pro-max` first, and runs `tsc --noEmit` and
lint as part of its own verification.

### 9.1 Top bar

An **Inbox** button on the right (COMPONENTS §1), `BellIcon` + "Inbox" + a mono unread badge (hidden
at 0, "99+" above 99). Click or ⌘/Ctrl-J toggles the drawer; Esc closes. The badge query polls every
60 s and refetches on focus. Not rendered for PORTAL users. `TopBar.test.tsx`'s "no dead controls"
test is rewritten to prove the Inbox control is live (opens the drawer, shows the count); its search
and account assertions stay.

### 9.2 Inbox drawer (COMPONENTS §16)

390 px, fixed right, `drawer` shadow, `om-slide .2s`, scrim `rgba(28,27,24,.18)` closing on click,
focus trapped while open and returned to the Inbox button on close. Header 52 px: "Inbox", mono
"N UNREAD", **Mark all read** (text button, hidden at 0), the Preferences toggle ("Preferences" /
"← Notifications"), ✕.

List rows: 26 px radius-7 icon tile in the type's semantic pair (§5.1 tone), title 12.5/600, body
11.5 `text-muted`, mono 9.5 relative time `text-faint`; unread `surface`, read transparent. Clicking
marks read (optimistic), navigates to `linkPath`, closes. "Load more" pages by cursor. Empty: "You're
all caught up." Loading: three skeleton rows. Error: inline message and Retry.

### 9.3 Preferences pane

The prototype's intro ("Every notification type is optional. Escalation to your manager is enforced
by policy and cannot be disabled."). An **Email delivery** segmented control (Immediately / Daily
digest / Weekly digest) with a one-line caption ("Digests arrive at 08:00 your organisation's time").
Then one row per type: label, **In-app** and **Email** toggles (COMPONENTS §17); Escalation shows both
locked on with "Required by policy". Each change saves immediately with an optimistic update and
rolls back with a toast on error.

### 9.4 Administration → Notifications

New admin nav item, gated `notification.manage`.

- **Deadlines & reminders.** One row per kind: label, unit caption ("business days before due" /
  "days before"), lead-time chips with ✕ and an Add input (1–90, max 5). An **Automatic customer
  reminders** block: switch, "every N business days", "at most M reminders". One Save for the
  section (full replace).
- **Templates.** Table: key (mono), name, entry/exit, active. Create/Edit dialog: key (create only),
  name, entered subject/body, an "Also alert on exit" switch revealing exited subject/body, the
  placeholder hint, inline 409/422 errors. Deactivate/Activate from the row.

### 9.5 Builder

`StageInspector`'s disabled field becomes a select: "None" plus the active templates from
`/notification-templates/options`. A stage whose saved key names an inactive template shows it with
"(inactive)". The "arrives later" hint and its test are replaced.

### 9.6 Gaps the design does not cover

Empty, loading and error states for the drawer; the admin screen entirely (built from the existing
admin screens' primitives); the email cadence control; Mark all read.

---

## 10. Testing

TDD throughout; negative and security tests before the mechanism they verify; every new structural
guard proven red.

### 10.1 Backend

- `NotificationIsolationTest` — cross-tenant inbox, read, preferences, templates, policy: 404.
- `RecipientAccessTest` — out-of-scope, audience-filtered document, deactivated, PORTAL,
  cross-tenant: `false`; in-scope: `true`; never reads a cached context.
- `InboxOwnershipTest` — another user's notification id → 404 on read; list/count never include
  another user's rows.
- `NotificationPipelineTest` — actor excluded; inactive/PORTAL excluded; invisible excluded;
  preferences honoured per channel; cadence routes to `QUEUED`/`DIGEST_PENDING`; rollback of the
  action leaves no notification and no outbox row.
- One producer test per event: publishes after its own audit action (`CauseBeforeEffectTest`
  additions), cancelled task publishes nothing.
- `EscalationLockTest` — preferences `PUT` disabling escalation → 422; escalation bypasses cadence.
- `SoleAdministratorEscalationTest`.
- `NotificationDedupeTest` — a second sweep sends nothing; a moved due date re-arms; multiple
  matching leads send only the smallest.
- `DigestScheduleTest` — tenant timezone, working days, weekly start, empty digest, switch to
  immediate, on `MutableClock`.
- `AutoReminderTest` — off by default; interval in business days; max; shared 24 h floor with manual;
  OPEN with contact only; skips write scope; `automatic: true` audit.
- `OutboxDispatcherTest` — backoff schedule, cap → FAILED + `email.failed`, lease expiry resend,
  two concurrent dispatchers never double-claim, inactive recipient → SKIPPED, manual remind failure
  no longer loses the reminder.
- `TemplateTest` — placeholder whitelist, immutable key, duplicate key 409, publish rule.
- `SystemPermissionsTest` — exact set.
- `PublicBaseUrlGuardTest`.
- Request/view alignment by reflection for preferences, policy and templates.
- `ModuleBoundaryTest` — the six producer rules and `sla → notification` one-way.

Run per package on this machine (`--max-workers=1 --tests "co.ara.onboarding.<pkg>.*"`).

### 10.2 Frontend

Vitest for the Inbox button (badge, ⌘J, hidden for PORTAL), the drawer (rows, tones, mark read,
mark all, paging, empty/loading/error, focus), the preferences pane (locked row, optimistic rollback,
cadence), the admin screen (chips, limits, full-replace save, template dialog errors), the builder
picker. `tsc --noEmit` and lint per task.

### 10.3 End-to-end — `notifications.spec.ts`

Assigning a task to a second user raises their badge; clicking the row lands on the case and clears
it. Turning a type off stops it arriving. A keyed stage sends a stage-entered alert. A daily-digest
user receives one digest email (read from the backend log) after the dev clock offset and the digest
job. Existing specs stay green, including `activation.spec.ts`'s portal rail assertion.

A whole-branch security review runs before close-out, with `RecipientAccess`, the ungated inbox, the
system actor's new permission and the outbox's PII as its named focus.

---

## 11. Invariant cross-check — sub-project 6B's own ten

1. No producer module imports a `notification` type; each arrow is its own `ModuleBoundaryTest` rule.
2. Notifications add no caller of `CaseEngine.reconcile` and never mutate the record they report on.
3. A notification commits with its action; an action that rolls back leaves no notification and no
   outbox row.
4. Nobody is notified about a record they cannot view at write time — scope, audience filters,
   deactivation and PORTAL all respected (`RecipientAccess`).
5. The actor is never notified of their own action.
6. Escalation cannot be disabled and is always emailed immediately.
7. A sweep never sends the same reminder twice — a database unique key on recipient + `dedupe_key`.
8. Every notification, digest, escalation and customer-reminder email goes through `email_outbox`;
   none is sent inside a transaction that can roll back; attempts are capped.
9. `SystemPermissions` gains exactly `document.request` at `ALL`, pinned by an exact-set test.
10. Another recipient's and cross-tenant ids are 404; `PUT` request and view types stay field-for-field
    aligned for preferences, policy and templates.

---

## 12. Inherited state

### 12.1 Carried in, untouched

TEAM-scoped user creation; the builder's attribute/entry-condition UI; the audit-timeline read
carve-out; sub-project 5's open list; sub-project 6's sweep-robustness items, the `V27` timezone
seam, the 25-user pickers, `resolveHead`/`resolveManager`.

### 12.2 Touched deliberately

- `sla`: `Notification*` move to `notification`; `EscalationMailer` and the retry run are deleted;
  `RecipientResolver` gains the sole-administrator fallback; the sweep publishes `RiskChanged`.
- `journey.CaseEngine` and `MilestoneService`: event publishes beside existing `SlaClockLifecycle`
  calls and where a milestone becomes `DONE` — no change to `reconcile`'s logic.
- `document.DocumentRequestService.remind`: queues an outbox row instead of sending after commit;
  `remindAutomatically` added.
- `authz.SystemPermissions`: + `document.request` at `ALL`.
- `TopBar.test.tsx`, `StageInspector` and its test.

### 12.3 Closed from sub-project 6's open list

Failed escalation email retried forever with no tracking; failed customer reminder silently lost;
no public base URL; the sole-administrator escalation with no recipient.

### 12.4 Carried forward

Portal-user notifications and a customer-authored comment type (7); real-time invalidation of the
badge and dashboard "Recent notifications" (8); a review queue that could notify agreement and
document reviewers; SMS/Teams/Slack; Q17 catch-up.

---

## 13. Question mapping

| Source | Where |
|---|---|
| PRD §13 catalogue | §5.1 (all but "Customer comment", deferred to 7) |
| PRD §13 channels | in-app, email; SMS/Teams/Slack out (§2.2) |
| PRD §13 / Q19 digests | §6.3, §9.3 |
| PRD §13 / Q19 horizons | §4.4, §6.2, §9.4 |
| Q10 escalation mandatory, rest opt-out | §5.3 step 5, §8 (422), §9.3 |
| Q19 stage entered/exited | §4.3, §5.5, §9.4–9.5 |
| Q19 risk state change | §6.1 |
| Q19 per-type `timeline_visible` | §4.7 |
| PRD §9 renewal reminders | §6.2 |
| PRD §11 notifications sent | `notification.sent` (compliance-only, §4.7) |
| Q17 catch-up | out (§2.2) |
