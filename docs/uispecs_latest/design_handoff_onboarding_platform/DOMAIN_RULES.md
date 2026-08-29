# Domain Rules

The sixteen decisions in `source/QA.md`, how each one shows up in the UI, and what will break if
you skip it. **Read this before implementing any screen.** Several of these rules are the reason
the design looks the way it does.

---

## Q1 · A stage contains many milestones (one-to-many)

`Workflow → Stage[] → Milestone[]`. A stage owns the outcome, the SLA and the responsible team.
Milestones are the actual work items and each carries its own owner and estimated duration.

**In the UI.** Case workspace stage accordion (stage header, milestones inside); builder node
(stage) with milestone pills; stage meta reads `2/3 milestones · Operations · 5d estimated`.

**If skipped.** The progress calculation has nothing to compute from, and the builder collapses
into a flat list that can't express the real process.

---

## Q2 · Template edits freeze running cases

Editing or publishing a workflow template **never** touches a running case. Cases stay pinned to
the template version they started on. Moving them is an explicit, separate migration.

**In the UI.**
- `PINNED TO v2.3` chip on the case header; `WF` column in the portfolio grid.
- Warn banner on the builder: publishing affects new cases only, with the counts per version.
- The whole `migrate` screen: per-case opt-in, dry-run preview, "reversible for 14 days".
- Administrator dashboard's spotlight block and the `Cases on old version` KPI.
- Sidebar `Migrations` badge showing 12.

**Version diff shown in the design (v2.3 → v2.4).** Reuse these categories:

| Symbol | Kind | Example |
| --- | --- | --- |
| `+` ok | Added | Conditional branch `segment = ENTERPRISE`; stage `Enhanced due diligence` |
| `⑃` warn | Split | Verification split into KYC verification + Sanctions screening — running cases need manual mapping |
| `~` info | Changed | Document collection SLA 4d → 5d; cases past the stage unaffected |
| `−` risk | Removed | Milestone `Manual credit check` replaced by the automated finance gate |

Per-case migration risk is derived from the diff: `SAFE` (1:1 mapping) / `NEEDS MAPPING` (a split or
removal touches a completed milestone) / `BLOCKED` (case on hold).

---

## Q3 · Multi-tenant SaaS

One installation serves many unrelated provider organisations.

**In the UI.** Tenant switcher at the top of the sidebar (Northwind Services 128 · Halcyon Utilities
54 · Meridian Bank 212), with the plan and case count as its subtitle.

**Implementation.** Tenant id belongs in the auth context and on every query, not in a URL segment
the user can edit. Workflow templates, roles, business calendars, retention policies and branding
are all per tenant.

---

## Q4 · Record-level permissions

Permissions resolve per record, not per screen. A user with team access to documents sees only the
documents attached to their team's cases.

**In the UI.**
- The 8×6 RBAC matrix on `admin`, with five scope levels: Full / Scope / Team / Own / None.
- `08 VISIBLE · 61 HIDDEN BY SCOPE` on the documents screen — the count of hidden records is shown
  deliberately so scoped views don't read as empty.
- Access-exception queue on the Compliance dashboard (requests to view outside scope, with
  time-boxed grants: "Approved for 7 days · expires 30 Aug").

**Implementation.** Enforce server-side. The client must never receive a record it may not see;
the chips are labels on a decision already made. `None` hides the surface from navigation entirely.

---

## Q5 · Force-complete is possible but gated

An authorised user can complete a milestone whose automated conditions are unmet, but:
role **above Project Manager** (Director / VP / Administrator), an **approval flow** rather than a
single click, and a **mandatory reason recorded in the audit trail**.

**In the UI.** The force-complete modal (`COMPONENTS.md` §18). Approver select + reason textarea;
the submit button is disabled until an approver is chosen **and** the reason exceeds 9 characters.
Submitting produces "Force-complete request sent to … · reason recorded in audit trail" — a
request, not a completion. The builder inspector restates the policy.

**Implementation.** Model this as an approval request entity with its own state, not a boolean
override. The audit entry records requester, approver, reason, and the conditions bypassed.

---

## Q6 · Progress is weighted by estimated duration ★

```
stageWeight_i   = stage_i.estimatedDurationDays / Σ estimatedDurationDays
stageOwnPct_i   = weighted completion of that stage's milestones
                  (Σ milestone.days × milestone.pct) / Σ milestone.days
caseProgress    = Σ (stageWeight_i × stageOwnPct_i)
```

No manual override. Not equal-weight per milestone. Not count-of-tasks.

**In the UI.** The `Why 44%?` explain panel on the case workspace exposes the whole calculation:
per stage, its estimate, its weight, its own percentage, a proportional contribution bar and the
points it adds — footed by the sum, which must equal the headline.

**Implementation.** One pure function, shared by the operator app and the customer portal, so the
two can never disagree. Recompute on read; do not persist a display percentage. The customer's
`44%` and the operator's `44%` are the same call.

Worked example from the design: 27 total estimated days; Registration 2d/100%, Sales approval
1d/100%, Agreement 8d/100%, Document collection 5d/20% (1 of 5 weighted days done), everything else
0% → 7.4 + 3.7 + 29.6 + 3.7 = **44.4% → 44%**.

---

## Q7 · Conditional branching

Workflows branch on case attributes — e.g. `segment = ENTERPRISE` routes through Enhanced due
diligence; SMB skips it.

**In the UI.** A distinct branch node in the builder: `#faf7ff` fill, `automation` border, `⑃`
glyph instead of a number, and its two outcomes shown as the milestone pills
(`True → Enhanced due diligence`, `False → skip to Document collection`). Stages that are only
entered on a branch carry a `CONDITIONAL` chip.

**Implementation.** A branch is a node in the ordered graph, not a property of a stage. Progress
weighting must exclude un-entered branches for a given case — otherwise an SMB case can never reach
100%.

---

## Q8 · SLA scoping ★

- **Business days**, using the tenant's configured business calendar. Never calendar days.
- **The clock pauses** while the case waits on the customer — case hold, or any open customer
  document request. It resumes when the request is satisfied or withdrawn.

**In the UI.**
- SLA chips distinguish states by wording, not just colour: `PAUSED 3.1d` · `BREACHED 2.0d` ·
  `AGED 4.0d` · `1.2d LEFT` · `0.4d LEFT`.
- Case rail `SLA CLOCK` callout names the pause reason and the calendar.
- War room: a `CLOCKS PAUSED 23` summary card, a per-card `Clock` row, and a policy panel; each
  exception states whether it is even *eligible* for pause ("Not eligible for pause — internal
  review").
- Insights: the Document collection bar is split into running vs. paused time, and
  `TIME LOST TO WAITING 41%` is a headline metric.

**Implementation.** Model the clock as an accumulation of running intervals against a business
calendar, not as `now - startedAt`. Every pause and resume is an audit event. Getting this wrong
makes the entire war room lie.

---

## Q9 · Customer write access and document visibility ★

Customers can **upload documents** and **post comments with attachments**. Visibility has three
tiers:

| Tier | Visible to |
| --- | --- |
| Company-shared | All approved contacts at the customer + the case team |
| Contact-only | The uploading contact + permitted reviewers. **Other contacts at the same company cannot see it.** |
| Sensitive | Restricted even within the customer's own company until explicitly shared |

Admins and managers get broader access according to their role scope.

**In the UI.** The visibility select in the customer upload zone (three plainly-worded options);
the scope filter and per-row `VISIBILITY` column on the operator documents screen; the explainer
aside; `ONLY YOU` / `PERSONAL` / `SENSITIVE` chips; the lock glyph on sensitive filenames; the
privacy note on the requirements screen.

---

## Q10 · Escalation and notification preferences

Automatic escalation to the assignee's manager after X days overdue is **required and cannot be
disabled**. Every other notification type is opt-out-able.

**In the UI.** `AUTO-ESCALATED 4` in the war-room summary; per-card escalation history ("Escalated
to Priya's manager on 21 Aug (automatic, day 1 overdue)"); the preferences pane in the inbox drawer
with nine toggles and an intro sentence that states the one exception.

---

## Q11 · Retention and erasure

- Audit logs: **7 years**, configurable per organisation.
- Documents after case closure: configurable per category or organisation.
- Right to erasure: request by email; the application **pseudonymises and hides** — it does not
  hard-delete.

**In the UI.** Activity-tab footer `IMMUTABLE AUDIT LOG · 7-YEAR RETENTION`; the retention note on
the documents aside; the Governance card on `admin`; an audit-stream entry showing an erasure
request logged and pseudonymisation scheduled.

---

## Q12 · Invitation-only provisioning

Customers cannot self-register. Internal staff generate an invitation; the customer activates via a
one-time link.

**In the UI.** The three-step `auth` flow, explicitly stating "Self-registration is disabled";
the Sales dashboard's `Portal invitations` block with unsent/pending/active states and link expiry
("Invited 20 Aug · link expires in 3 days").

---

## Q13 · Agreements may be structured-only (configurable)

Whether an agreement requires a file is a **template setting**. An agreement can exist as a
structured record while it is being drafted.

**In the UI.** The `Record mode` column on the agreements table —
`File-backed · v3` / `Structured + file · v2` / `Structured record only` — and the same phrasing in
the case Agreements tab.

**Statuses.** Draft · Under review · Sent · Awaiting signature · Signed · Expired · Cancelled.

---

## Q14 · E-signature: OpenSign

**In the UI.** `AGREEMENT LIFECYCLE · OPENSIGN CONNECTED` eyebrow; "signed 14 Aug via OpenSign" in
the case Agreements tab.

**Implementation.** Wrap it behind a provider interface — the PRD lists digital signatures as a
future item and the provider may change.

---

## Q15 · Assignment resolution

A stage is assigned to a department or team. The default individual owner is the case's `OWNER`
participant (the Project Manager). Any authorised user can reassign a milestone to a specific
person.

**In the UI.** Milestone owner lines; "owner inherited from case" phrasing; the war room's
`Reassign` action; the case rail Participants list with the `Owner · Project Manager` role label.

---

## Q16 · Per-role dashboards ★

Roles need *genuinely different layouts*, not one layout with different data — while some blocks
stay common so everyone shares a view of overall status.

**In the UI.** Eight compositions (see `SCREENS.md` §1). Shared blocks recur across roles — the
journey-map funnel appears for Operations and Executive; the activations chart for Operations and
Executive; the handoff queue for PM and Sales — but each role's *set and order* differs, and the
KPI quartet is role-specific.

**Implementation.** One block registry + one role config map. Blocks receive their data by id.
Adding a role, or letting a user pin/reorder their own blocks later, must be a config change.

---

# Reference domain vocabulary

Use these names; they run through the whole design.

```
Tenant            a provider organisation on the platform
Account           a customer company (Acme Foods Ltd.)
Journey / Case    one onboarding for one service, e.g. ENT-04281. An account may hold several.
Workflow template versioned definition; cases pin to a version
Stage             an outcome with a team, an SLA and milestones
Milestone         a unit of work with an owner and an estimated duration
Task              work inside a milestone
Requirement       a customer-facing document request
Participant       a person on a case, with a role (OWNER, reviewer, customer contact)
SLA clock         business-day accumulation, pausable
Escalation        automatic manager notification after breach
```

Nine internal stages map to five customer-facing steps:

| Customer step | Internal stages |
| --- | --- |
| Register | Registration, Sales approval |
| Agreement | Agreement |
| Documents | Document collection |
| Verification | Verification (+ Enhanced due diligence when branched) |
| Go live | Technical setup, Testing, Training, Go live |

Keep this mapping in one place. The customer must never see nine stages, and the operator must
never see only five.
