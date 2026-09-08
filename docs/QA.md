
### Q1 · Stage vs. Milestone

**Question:** Is a "Stage" in a workflow template the exact same concept as a "Milestone" in a running case? Or can a stage contain multiple milestones?

**Decision:** **One-to-Many.** A stage contains multiple milestones.

---

### Q2 · Template Edit Semantics

**Question:** What happens to in-flight (running) cases when an administrator edits a workflow template?

**Decision:** **Freeze by Default.** Running cases stay on their pinned version. Admins can explicitly "migrate" cases to the new version via a separate tool.

---

### Q3 · Tenancy

**Question:** Does a single installation serve ONE provider organization or MULTIPLE unrelated provider organizations (true SaaS)?

**Decision:** **Multi-tenant SaaS platform.**


---

### Q4 · Permission Granularity

**Question:** How granular are the configurable permissions for the 12 internal roles?

**Decision:** **Record-Level.** A user can only see documents and resources assigned to their team (or within their scope).


---

## DOMAIN RULES — Engine Design

### Q5 · Force-Complete Authority

**Question:** Can an authorized user force a milestone to "Complete" even when automated conditions are not satisfied?

**Decision:** **Yes, but with controls.**
- Requires a role **above Project Manager** (e.g., Director, VP, Administrator)
- Requires a **specific approval flow** (not a single click)
- Mandatory reason recorded in audit trail


---

### Q6 · Progress Calculation

**Question:** How is the `progressPercent` for a case calculated?

**Decision:** **Weighted by estimated duration.** Milestones are weighted by their `estimatedDurationDays`. A milestone's own completion percent is derived from its constituent tasks/documents/approvals.
we can combine or consider the following options
Equal weight per milestone — too simplistic for variable-length stages
Weight by number of tasks — tasks are not uniform
Manual override — introduces inconsistency and disputes



---

### Q7 · Conditional Branching

**Question:** Can workflows branch conditionally (e.g., "If customer is ENTERPRISE, go to Legal Review; if SMB, skip it")?

**Decision:** **Yes, support Conditional Branching.**



---

### Q8 · SLA Scoping

**Question:** How are SLAs defined and scoped? Does the SLA clock pause while waiting on the customer? Business days or calendar days?

**Decision:**
- **SLA pauses while waiting for the customer** (including case hold and open customer document requests)
- **Business days** (not calendar days), using the configured business calendar


---

## CUSTOMER & PORTAL BEHAVIOR

### Q9 · Customer Write Access

**Question:** What can customers do, not just see? Can they comment? Does one contact see all uploads from other contacts at the same company?

**Decision:**
- Customers can **upload documents** AND **post comments** on their case with attachments
- **Each contact sees only their own documents 
- ** company level attachments: visible to all in company
- **contact / user documents: visible only to that user
- sensitive documents: ristricted even within the company unless explicitly shared
- admins/managers: border access based on their role.

**Amended 2026-08-29 — department targeting.** The tiers above are unchanged and answer *how
broadly* a document is shared. A second, independent axis answers *which group*. A document may
additionally be targeted at:

- **internal staff departments** — Legal, Finance, Operations, Compliance (reuses the existing
  `app_user.department_id`), and/or
- **customer-side contact labels** — a named label carried on each `customer_contact`
  ("Finance", "Legal", "IT"), set by internal staff when managing contacts.

Targeting **narrows within a tier and never widens one**. A company-shared document targeted at
Legal is visible to Legal, not to everyone. A document with no targeting behaves exactly as this
answer originally decided, so nothing already built changes meaning.

Company-level visibility keeps its original meaning: all approved contacts at the customer **plus
the case team**. The team needs company-shared documents to do the work.


---

### Q10 · Escalation & Notification Preferences

**Question:** What is the escalation policy for overdue work? Can users opt out of notification types?

**Decision:**
- **Automatic escalation is required** (e.g., notify the assignee's manager after X days overdue)
- **All notifications are configurable** — none are mandatory. Users can opt out of any notification type.

*Clarification (2026-08-29): the two bullets above read as contradictory. They resolve the way
`DOMAIN_RULES.md` §Q10 already states — escalation to the assignee's manager is required and
cannot be disabled; **every other** notification type is opt-out-able. Q19's additions all land on
the opt-out-able side.*

---

## COMPLIANCE & INTEGRATION

### Q11 · Retention & Erasure (GDPR/CCPA)

**Question:** How long to retain audit logs? How long to retain documents after a case is closed? How to handle right-to-erasure requests?

**Decision:**
- **Audit log retention: 7 years** (configurable per organization)
- **Document retention after case closure: configurable** (per category or organization)
- **Right-to-erasure:** User sends an email request. The application must comply with GDPR rules and **hide the data** (pseudonymization, not hard deletion)

---

### Q12 · Customer Provisioning

**Question:** How are customers provisioned? Self-registration or invitation?

**Decision:** **Invitation link.** Internal staff generates an invitation; the customer receives a link to activate their portal access.

---

### Q13 · Agreement File Requirement

**Question:** Is an `Agreement` always backed by a file, or can it exist as a structured record while still being drafted?

**Decision:** **if it can be configurable it will be better.**
---

### Q14 · E-Signature Provider

**Question:** Is an e-signature provider (DocuSign, Adobe Sign) already assumed?

**Decision:** **Consider OpenSign for now.** 
---

## OPERATIONAL SCOPE

### Q15 · Assignment Resolution

**Question:** A workflow stage is assigned to a *Department* or *Team*. How does that become a specific *Individual* assigned to the milestone?

**Decision:**
- **Default: Project Manager** — the `CaseParticipant` of role `OWNER` is the default owner
- **Manual override supported** — an authorized user can reassign a milestone to any user

---


### Q16 · Per-Role Dashboards

**Question:** Does each of the 12 internal roles see the same dashboard with different data, or genuinely different dashboards?

**Decision:** **Different roles require different dashboard layouts** that benefit them and give them insights to help them make decisions or take actions based on their role. Some dashboard layouts are common to all as they help giving insights about the overall project/engagement status.

---

## MULTI-JOURNEY, CONTINUITY & ALERTING

*Added 2026-08-29. Q17 is a new decision; Q18 and Q19 pin down points the PRD and the design
bundle assumed but never resolved.*

### Q17 · New-Joiner Catch-Up

**Question:** A contact is given portal access part-way through an onboarding. How do they get up
to speed on what has already happened and what is now owed?

**Decision:** **A catch-up view, built on the existing activity timeline.**

- **Customer-side contacts only.** An internal staff member inheriting a case is a different
  problem and is out of scope for this answer.
- Reads the existing `audit_event` timeline (`timeline_visible`) — not a second event store, and
  not a hand-written handover document somebody has to remember to write.
- Shows what the journey is, where it stands, what happened before they arrived, and what is
  currently waiting on them.
- **The catch-up is filtered, never privileged.** It passes through the same Q9 tiers and Q4
  record scope as every other read. It must never surface a contact-only or sensitive document the
  joiner is not entitled to, and never internal-only notes (PRD §12). A summary that leaks is
  worse than no summary.
- Delivery may reuse Q19's digest mechanism rather than inventing its own.

---

### Q18 · Multiple Journeys Per Customer

**Question:** May one customer hold several concurrent onboarding journeys? If so, how are they
told apart, and which of them does a contact see?

**Decision:** **Yes — an account may hold several concurrent journeys**, one per service, region
or product, each with its own roadmap, progress and requirements.

- Already true structurally: `onboarding_case.customer_id` carries no unique constraint, and
  `GET /customers/{id}/cases` already lists them.
- **A journey carries a human-readable name**, set when it is created — "Enterprise onboarding",
  "EU expansion". Today the UI derives a label from the current stage name plus a short id, which
  is not a name and stops being right the moment the stage advances.
- An internal user holding `case.create` assigns a new journey to a customer.
- **A contact sees every journey on their account.** Restricting individual files is Q9's job,
  not the journey list's.

---

### Q19 · Notification Catalogue Extensions

**Question:** Beyond the catalogue in PRD §13, what else must raise an alert?

**Decision:** Four additions, all **opt-out-able** under Q10:

- **Stage entered / exited.** Gives `stage.notification_template_key` — authored in the workflow
  builder today, acted on by nothing — a defined trigger.
- **Risk state changes.** When a stage or journey becomes at-risk or breaches. Distinct from
  Q10's escalation: escalation is mandatory and goes to the assignee's manager, a risk alert is
  optional and goes to the stakeholders.
- **Digest roll-ups.** A daily or weekly summary instead of per-event sends. Every type in PRD §13
  is a single-event send and nothing summarises. Also the natural delivery vehicle for Q17.
- **Configurable deadline horizons.** Replaces a single fixed 48-hour warning — a signature and a
  document request do not deserve the same lead time.

**Q10 is unchanged.** Escalation to the assignee's manager stays mandatory; everything added here
is opt-out-able. Each new alert type needs its own deliberate `timeline_visible` decision when it
is built — customer-visible or compliance-only is a choice, not a default.

---

## PROJECT-BASED DELIVERY — PROGRAMMES, CUSTOMER PLANS, MEETINGS

*Added 2026-09-08. Nine decisions taken together, reframing the platform for project-based
delivery: a customer holds a programme of parallel journeys, runs them against a plan cloned and
tailored for them, and approves that plan twice — once as a shape, once as a schedule.*

**None of these weakens an existing invariant.** Q2's freeze-by-default, "a published version never
mutates", "a case has exactly one pinned version" and "progress is derived by the engine, never
accepted from a request" all hold unchanged — see each answer's own cross-check.

**Where they are built:** Q20–Q24 in sub-project **3A** (Programmes & Customer-Scoped Plans, new,
after 3). Q25–Q26 in **4A** (Meetings, new, after 4 — the agenda and the recording are documents).
Q27 grows across 4 and 5 as new satisfying record types land. Q28 in **8**. The customer-facing half
of Q22's approval lands in **7**; see Q22.

---

### Q20 · Programmes above journeys

**Question:** Several internal teams each run their own plan for one customer engagement — IT sees
the IT plan, onboarding sees theirs, legal theirs. The account manager and the customer's sponsor
need to see the whole thing. What is "the whole thing", structurally?

**Decision:** **A `programme` record grouping several journeys under one customer.** Each team's
plan stays a `Case` with its own roadmap, pinned version, progress and completion date — Q18's
multi-journey answer, now with a container above it.

- **The programme has no lifecycle of its own** — no status, no hold, no approval, no engine. Hold
  and completion happen on the individual journeys. This is deliberate: a second orchestration
  layer above `CaseEngine` would need cascade rules ("programme held but case active"?) that nobody
  has a use for yet.
- **Programme progress is derived, never stored** — weighted across its cases by their total
  estimated duration, consistent with Q6's rule for milestones within a case. Sub-project 2's
  invariant "progress is derived and stored by the engine; no request type accepts one" is
  unaffected: the engine still owns per-case progress, and the rollup is a read.
- **A programme carries participants** — the account manager (internal, full view) and the customer's
  project sponsor (portal, full view). This is the reason it is a record and not a label: a label
  has nowhere to hang the sponsor, so "the sponsor sees the whole project" would have to be granted
  journey by journey.
- **Participants grant read, never write.** A programme read must still go through `AuthorizedQuery`
  and an out-of-scope journey must still 404 (invariant: "authorization narrows at a stage's
  `write_scope`; there is no branch that widens it"). A container that returned journeys its viewer
  could not otherwise open would be a scope-widening backdoor, which is precisely the shape three
  sub-project 1 escalations took.
- **Amended 2026-09-08, sub-project 3A design §6.3.** As originally written the two bullets above
  contradict each other: a sponsor who is *only* a programme participant can read the programme and
  none of its journeys, so the container shows an empty list and does nothing for the person it
  exists for. The resolution keeps the invariant rather than the convenience: **programme
  participation grants read of the programme itself and nothing more**, and adding a participant
  **offers to add them as a `CaseParticipant` on the programme's journeys** — an explicit, audited,
  individually revocable write authorized by `programme.manage`, never by participation. Journey
  access therefore still comes only from a real per-journey grant; the programme merely stops that
  grant from being N separate manual acts.
- **The rollup is computed over the journeys the reader can see**, and the view states the count it
  covered. Computing it over every journey would be an aggregate over rows the viewer cannot open —
  the same shape as the `taskSummary` leak recorded against sub-project 3. This does not contradict
  Q24: that answer is about internal-versus-portal *rendering*, not authorization scope.

**Rejected:** one journey whose stages are partitioned by responsible department — "the IT plan"
then has no independent roadmap, completion date or progress, and one number must be sliced N ways.
**Rejected:** a nullable programme name on `onboarding_case` — no home for participants, and a typo
silently splits a programme in two.

---

### Q21 · Customer-scoped workflow templates

**Question:** May a general template be cloned for one customer and then edited for that customer
only, without the edit reaching the original?

**Decision:** **Yes — `workflow_template` gains a nullable `customer_id`.** Null means the tenant
catalogue (every template today); set means a lineage owned by one customer.

- **Cloning is a snapshot, in both directions.** Editing "Acme Onboarding" cannot reach `template1`;
  equally, `template1`'s later improvements do **not** flow down to Acme. That second half is Q2's
  freeze-by-default.
- **Amended 2026-09-08, sub-project 3A design §5.2 and §11.2.** The original wording — "no link back
  exists to follow", and "the existing migration tool is the deliberate, per-journey way to pull an
  upstream improvement down later" — cannot both be true, and the second is false as the code
  stands: `MigrationService.casesOnAnOldVersionOf` filters by `templateId`, so migration only moves
  a case between versions of the **same** template. With no link back there is no path from a
  customer clone to its catalogue source at all, and an upstream improvement can never be pulled
  down. Corrected: `workflow_template` also gains a nullable `cloned_from_template_id`, and a
  **refresh** action deep-copies the source's current published version into a new DRAFT version of
  the *customer's own* template; ordinary migration then moves that customer's journeys forward
  within it. The pointer is provenance, not a propagation path, so "edits never flow automatically,
  in either direction" holds unchanged. **Refresh replaces rather than merges** — the customer's
  tailoring is re-applied in the new draft before publishing. A three-way merge would need per-node
  identity across two lineages that Q2's freeze deliberately severs, and is out of scope.
- **The machinery already exists.** `WorkflowService.createDraftVersion` already deep-copies a
  template's current published version into a new editable draft, and `V12`'s freeze triggers
  already refuse every write to a `PUBLISHED` row. Cloning for a customer is that same deep copy
  with a different owner on the resulting template.
- **One clone per customer, reused across that customer's journeys.** An edit made once is available
  to every future Acme journey. A change wanted on one journey only is not supported by this answer
  — see the note under Q22.

**Rejected:** one clone per journey (a private lineage per case) — an Acme-wide change would have to
be made once per journey. **Rejected:** both tiers with promotion between them — three
freeze-and-approval interactions instead of one, and "which tier does the customer approve?" gains
no good answer.

---

### Q22 · The plan is approved twice — shape, then schedule

**Question:** An internal user builds a project plan and sends it to the customer for approval. When
the plan changes, the version must be captured. What exactly is the customer approving?

**Decision:** **Two gates, because the two halves of a plan live at different levels.**

- **Gate 1 — shape, at the customer template version.** Stages, milestones, requirements and
  estimated durations. Approved once per version; every journey pinned to that version inherits the
  approval.
- **Gate 2 — schedule, per journey.** Calendar due dates and named owners, captured as a dated
  snapshot revision of the journey's instantiated plan and approved per journey.

The split is forced by where the data lives: `MilestoneDefinition` carries only
`estimatedDurationDays`, while `Milestone.due_date` and `Milestone.owner_user_id` are runtime
columns on the running case. A customer approving a template version is therefore approving a shape
and a duration, not a schedule — and in project-based delivery dates are precisely what a sponsor
argues about. Two journeys on the same approved shape still approve their schedules separately.

- **Every edit must declare which gate it reopens.** A change to the customer template's shape
  reopens gate 1 and, because dates shift with it, ordinarily gate 2 as well. A change to one
  journey's dates or owners reopens gate 2 only. This is the cost of two gates and it is paid
  explicitly, per edit, not inferred.
- **A journey-only change to the *shape*** — as opposed to the schedule — is out of scope for Q21's
  one-clone-per-customer answer. If it becomes necessary, the honest fix is a per-case lineage
  (Q21's rejected option), not a second editing path bolted onto the customer tier.
- **The approver is internal until sub-project 7.** Both gates need a customer to press approve, and
  the portal is four sub-projects later. 3A builds the model with the decision **recorded**
  internally — "the sponsor approved by email, logged by the account manager" — and 7 wires the
  sponsor's own button to the same endpoint. The gate is real from the day it ships; only who
  presses it changes.
- **The approved artifact is the filtered view.** Because Q24 lets a milestone be internal-only, the
  plan a sponsor approves is the portal-visible plan, not the internal one. The internal plan and
  the approved plan are two renderings of one journey, and the approval records which one was sent.

---

### Q23 · A pending first schedule approval holds the journey

**Question:** A journey is open and its schedule is sitting unapproved with the customer's sponsor.
May the internal team work the first milestone?

**Decision:** **No — the journey waits on hold until its first schedule approval lands. Later
revisions are advisory and do not block.**

- **Mapped onto the existing hold**, not a new pause: `Case.held_at` / `total_hold_days`,
  `CaseOnHoldException` already refuses every satisfy while held, and Q8 already decided the SLA
  clock pauses while waiting on the customer. Waiting for a sponsor to approve a plan *is* waiting
  on the customer, so the SLA accounting is correct for free rather than invented twice.
- **Revisions do not re-block.** When dates shift mid-flight a new revision is issued and the team
  keeps working; the approval is recorded when it arrives. The alternative — re-holding on every
  revision — means an internal typo correction freezes a live project until the customer replies.
- **Rejected:** never blocking. Approval would be a decoration, and work could proceed to completion
  against a plan the customer had explicitly refused.

---

### Q24 · Internal-only milestones, and one progress number

**Question:** Some milestones are internal team work the customer should not see. If three of ten
are hidden, what progress does the customer's portal show?

**Decision:** **Milestones gain portal visibility, and progress stays one number for everyone.**

- `stage.portal_visible` already exists in the schema but **is read by nothing** — authored in the
  builder and inert, like `notification_template_key`. Sub-project 3A adds the milestone-level flag;
  sub-project 7 becomes the first consumer of both.
- **Progress is computed once, over every milestone**, exactly as `CaseEngine` does today, and every
  audience sees the same figure. The customer's roadmap shows seven rows while the bar reflects ten,
  so the arithmetic is not reconstructable from their screen. That is the accepted cost: one truth
  about "how far along are we", and status reports, dashboards, rollups and SLA figures that all
  agree. No engine change.
- **Rejected:** recomputing over visible milestones only — the same journey then reads 40% internally
  and 29% externally, forever, and every report needs an audience flag. **Rejected:** giving
  internal milestones zero weight — a week of internal staging would move the bar 0%, and marking
  work internal would silently change Q6's denominator.

---

### Q25 · Meetings are a requirement kind

**Question:** A kickoff meeting needs a proposed time the customer accepts or rejects, an agenda, and
afterwards a recording, documents and notes. How is it modelled?

**Decision:** **`RequirementKind.MEETING`, backed by a `meeting` record.** It carries the proposed
time, the customer's decision on that time, an agenda reference, `held_at`, notes and attachment
references.

- **Follows the `TASK` precedent exactly.** `RequirementKind.TASK` was added in sub-project 2 as an
  empty seam and sub-project 3 fills it with a real module; `SatisfyRequest.satisfiedRef` /
  `satisfiedRefType` exist for precisely this. A meeting held and its notes captured satisfies its
  requirement through the same gated `CaseEngine.reconcile` under the same row lock — **no second
  write path into a case**, which is what invariant 4 and `ReconcileConcurrencyTest` protect.
- **A "Kickoff" milestone is a milestone with a MEETING requirement**, not a new kind of milestone.
  The other kinds in the original brief map onto what already exists or is already scheduled: task
  → `TASK` (sub-project 3), file → `DOCUMENT` (4), approval → `APPROVAL`, signature → a `SIGNATURE`
  kind added by 5, agenda → a document belonging to the meeting.
- **Needs sub-project 4.** The agenda and the recording are documents, so `meeting` carries nullable
  attachment references until 4 lands — the same seam sub-project 3's tasks and comments carry.
- **Rejected:** a first-class `meeting` hanging off a milestone independently of any requirement.
  More expressive (a series, an ad-hoc meeting that gates nothing), but it becomes a second thing
  that mutates a case, "does a meeting completing advance the milestone?" needs an answer, and it
  must route through `reconcile` regardless. **Rejected:** composing a kickoff from an `APPROVAL`
  plus three `DOCUMENT` requirements — nothing then holds an actual date and time, so there is
  nothing to put on a calendar, remind against, or reschedule.

---

### Q26 · Recurrence lives on the meeting series, never the graph

**Question:** A weekly status meeting runs for the life of the project. How is "every week"
represented?

**Decision:** **A recurrence rule on the meeting, spawning occurrences.** One milestone, one MEETING
requirement, many occurrences — each with its own proposed time, agenda, notes and recording. The
milestone completes when the series ends.

The frozen graph never changes and the progress denominator never moves. **A recurring *milestone*
was rejected for a structural reason:** a workflow version is a frozen graph at publish and Q6
weights progress by `estimated_duration_days`, so a milestone spawning a new instance every week
grows the denominator without bound — progress would drift *downward* week over week and the journey
could never reach 100% while the series ran. Excluding such milestones from progress weight fixes
the arithmetic but makes them contribute nothing, at which point they need not be milestones at all.

**Rejected:** a recurrence rule on sub-project 3's ad-hoc `task` — simplest, but a task has no
scheduled time, agenda, attendees, recording or customer-visible occurrence, so it does not model a
weekly status call.

---

### Q27 · Outputs are derived, not declared

**Question:** Stages and milestones have outputs — the deliverables produced. Are these a new
tracked concept?

**Decision:** **No new schema. An Outputs view rolls up `satisfiedRef`.**

Satisfying a requirement already records `satisfiedRef` / `satisfiedRefType` — a pointer to the
record that satisfied it: the task, the document, the agreement, the meeting. **That pointer is the
output.** A stage's or milestone's outputs are a query over its requirements' satisfied references,
surfaced on the portal as "what we have delivered" and in Q28's status report. It grows on its own
as sub-projects 4 and 5 add satisfying record types — no list to extend.

**Rejected:** declaring expected outputs in the template alongside requirements — a `DOCUMENT`
requirement labelled "Signed MSA" already declares exactly that deliverable, so this is a second
list to keep consistent with the first and a second editor in the builder. **Rejected:** declaring
and then matching them ("3 of 4 promised deliverables produced") — a third tracking axis beside
requirement status and progress, for reporting nobody has asked for yet.

---

### Q28 · Status reports are issued snapshots

**Question:** Is a status report a document you issue, or a screen the customer can always look at?

**Decision:** **An issued, dated, immutable snapshot**, generated on demand or on a schedule.

Each report captures progress and its change since the previous report, milestones closed in the
interval, outputs produced (Q27), what is open with the customer and what is open with the provider,
and current risk state. Published to the portal as a numbered artifact and emailable.

- **Reuses Q22's snapshot mechanism** rather than inventing a second one.
- The deciding argument is history: *"what did we report on 15 October?"* has to have an answer, for
  governance packs and for disputes. A live screen cannot answer it.
- **Depends on 4, 5 and 6** for outputs to be rich and for risk state to exist, so it is built in
  sub-project 8, not 3A.
- **Rejected:** a live composed screen only — nothing to generate or store, always current, but no
  record of what the customer was told last month. **Rejected:** both — workable, but the live view
  and the generator must then share one computation or they drift into disagreeing about the same
  journey.

---

### What these nine did *not* change

Three items from the same discussion needed no decision, and one needed no model:

- **Journeys for prospects** already work. `CustomerStatus.PROSPECT` exists and nothing gates case
  creation on `ACTIVE`. This needs a test proving it, not a feature.
- **Customer–provider messaging** is already specced — design screen 19 `cmsg`, and Q9 already grants
  customers commenting with attachments. Sub-project 3's spec defers it to the portal deliberately.
- **Customer dashboard insights** are Q16 (per-role dashboards) plus design screens 1 and 12,
  sub-projects 8–9.
- **"Legal sees every legal engagement across customers"** is a read, not a model. Q4's record-level
  scope and `stage.responsible_department_id` already hold the data; it is a department filter on the
  portfolio screens (6, 7, 12), in sub-project 8.
