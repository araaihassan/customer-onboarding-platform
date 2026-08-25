
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


---

### Q10 · Escalation & Notification Preferences

**Question:** What is the escalation policy for overdue work? Can users opt out of notification types?

**Decision:**
- **Automatic escalation is required** (e.g., notify the assignee's manager after X days overdue)
- **All notifications are configurable** — none are mandatory. Users can opt out of any notification type.

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
