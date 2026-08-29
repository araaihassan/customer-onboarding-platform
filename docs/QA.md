
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
