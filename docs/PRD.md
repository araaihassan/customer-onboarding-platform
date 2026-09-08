# Enterprise Customer Journey & Onboarding Platform
## Product Requirements Document (PRD)

---

# 1. Vision

Develop a modern, enterprise-grade web platform that centralizes and manages the complete customer onboarding journey from initial registration through successful service activation.

The platform should serve as the single source of truth for all onboarding activities, enabling internal teams and customers to collaborate in real time while providing complete visibility into every stage of the onboarding process.

Unlike a traditional CRM, this platform focuses on **workflow orchestration**, **progress tracking**, **document management**, **task collaboration**, and **customer transparency**.

The user experience should be modern, intuitive, highly interactive, and comparable to leading SaaS platforms such as Linear, Notion, GitHub, Stripe Dashboard, and Monday.com.

---

# 2. Core Objectives

The platform should:

- Centralize all onboarding-related information.
- Replace spreadsheets, email chains, and disconnected systems.
- Provide a visual onboarding journey for every customer.
- Enable collaboration between all participating teams.
- Allow customers to monitor their onboarding progress in real time.
- Automate repetitive onboarding processes.
- Maintain complete audit history and document traceability.
- Scale to support different onboarding workflows without code changes.

---

# 3. Product Philosophy

The platform is **not a CRM**.

It is an **Onboarding Workspace** where every stakeholder works from the same customer journey.

Each onboarding case should include:

- Customer Information
- Workflow
- Milestones
- Tasks
- Documents
- Agreements
- Comments
- Activity Timeline
- Notifications
- Progress Analytics

Everything should revolve around the customer's onboarding journey.

---

# 4. User Roles

## Internal Users

- Sales Representatives
- Account Managers
- Project Managers
- Service Providers
- Business Partners
- Operations Team
- Legal Team
- Finance Team
- Technical Team
- Compliance Team
- Support Team
- Administrators

Each role should have configurable permissions.

---

## External Users

Customers should have secure portal access allowing them to:

- View onboarding progress
- Track milestones
- Upload requested documents
- Download agreements
- Review completed tasks
- View upcoming activities
- Receive notifications
- Communicate with assigned personnel where applicable

Customers must only access their own information.

---

# 5. Dashboard

The dashboard should be the operational hub of the platform rather than a collection of reports.

It should provide an immediate overview of:

- Active onboarding projects
- Customers requiring attention
- Pending approvals
- Overdue tasks
- Upcoming deadlines
- Team workload
- Recent activities
- Overall onboarding health

### Dashboard Components

- KPI Cards
- Progress Funnel
- Interactive Workflow Overview
- Activity Feed
- Calendar
- Team Workload
- Performance Charts
- Customer Health Indicators
- Recent Notifications

The dashboard should update in real time without requiring manual refresh.

---

# 6. Customer Journey Workspace

Each customer should have a dedicated workspace representing the complete onboarding lifecycle.

A customer may hold **several concurrent journeys** — separate services, regions or products —
each with its own roadmap, progress, requirements and workspace. Every journey carries a
human-readable name given when it is created. An internal user assigns a new journey to a
customer; a customer contact sees every journey on their account. (QA Q18)

### Programmes

Concurrent journeys are usually parallel plans for one engagement, run by different internal teams:
IT works the IT plan, onboarding works theirs, legal works theirs. A **programme** groups them under
the customer and gives two people the whole picture — the account manager internally, and the
customer's project sponsor in the portal. Each team still works its own journey.

A programme has a name, a customer, and participants. It has **no lifecycle of its own** — no
status, no hold, no approval. Hold and completion happen on the individual journeys. Its progress is
derived by weighting each journey's progress by that journey's total estimated duration, the same
rule §6's milestones already use within a journey.

Programme participation grants **read across the journeys, never write**, and never access a viewer
could not otherwise obtain: an out-of-scope journey stays invisible. (QA Q20)

This workspace should include:

### Customer Summary

- Company Information
- Contacts
- Assigned Teams
- Current Status
- Progress Percentage
- Estimated Completion Date

### Interactive Roadmap

A visual roadmap displaying onboarding stages.

Example:

Registration

↓

Sales Approval

↓

Agreement

↓

Document Collection

↓

Verification

↓

Technical Setup

↓

Testing

↓

Training

↓

Go Live

Each milestone should display:

- Completion Status
- Assigned Owner
- Due Date
- Dependencies
- Documents
- Comments
- Activity History
- Completion Percentage
- Outputs produced
- Whether it is internal-only or shared with the customer

Users should be able to expand milestones to view details without leaving the page.

### Milestone Kinds

A milestone is completed by satisfying its requirements, and a requirement has a kind. The kinds are
**task**, **document**, **approval**, **signature**, **meeting**, and a plain manual check-off. So a
"Kickoff" milestone is not a special kind of milestone — it is a milestone holding a *meeting*
requirement.

A **meeting** requirement carries a proposed time the customer accepts or rejects, an agenda, and
afterwards the recording, any documents produced, and the notes taken. A meeting may **recur** — a
weekly status call is one milestone holding one recurring meeting requirement that spawns a dated
occurrence per period, each with its own agenda, notes and recording. Recurrence never multiplies
milestones, so a journey's progress denominator stays fixed and a journey can still reach 100%.
(QA Q25, Q26)

### Internal and Shared Milestones

A milestone may be **internal-only** — work the provider's team does that the customer has no need
to see — or **shared**, appearing on the customer's roadmap. Stages carry the same flag.

**Progress remains a single number for every audience.** It is computed over every milestone,
internal ones included, so the customer's roadmap may show seven rows while the bar reflects ten.
This is deliberate: one truth about how far along a journey is, with status reports, dashboards,
rollups and SLA figures that all agree. (QA Q24)

### Outputs

Every stage and milestone shows the **outputs** it produced: the documents, agreements, signed
records, meeting notes and completed tasks that actually satisfied its requirements. Outputs are not
a separately maintained list of promises — they are what the journey has genuinely delivered so far,
and they feed both the customer portal and the status report. (QA Q27)

---

# 7. Configurable Workflow Engine

The onboarding workflow should be fully configurable.

Administrators should be able to:

- Create workflows
- Add stages
- Reorder stages
- Add approvals
- Define dependencies
- Configure automatic transitions
- Assign responsible departments
- Create reusable workflow templates
- Clone a template for one customer and tailor it

No software development should be required to modify business workflows.

### Customer-Specific Templates

Templates live in a tenant-wide catalogue, and any of them may be **cloned for one customer** and
then edited for that customer alone. The clone is a snapshot: editing the customer's copy never
reaches the original, and equally the original's later improvements do not flow down to the customer
— catching up is the deliberate, per-journey act of migrating a journey to a newer version (QA Q2).

One clone serves that customer: all of their journeys draw from it, so a change made once is
available to every future journey for them. (QA Q21)

### The Project Plan, and Approving It

A plan is authored internally and sent to the customer for approval, and it is **approved twice**,
because a plan's two halves live at different levels:

- **The shape** — stages, milestones, requirements, estimated durations — is approved once per
  version of the customer's template. Every journey pinned to that version inherits the approval.
- **The schedule** — calendar dates and named owners — is approved per journey, as a dated snapshot
  revision. Two journeys built on the same approved shape still approve their own dates.

Every subsequent edit must declare which gate it reopens: a shape change reopens the first and
ordinarily the second with it, a date or owner change reopens only the second.

**A journey waits until its first schedule is approved.** It sits on hold — no requirement can be
satisfied and no stage exits — and the SLA clock is paused for exactly the days the customer took,
which is the treatment §Q8 already gives every other wait on the customer. Later revisions do not
block: the team keeps working and the approval is recorded when it arrives, because an internal date
correction must not be able to freeze a live project.

The plan a customer approves is the **customer-visible** plan, filtered by the internal/shared flag
above. The internal plan and the approved plan are two renderings of one journey. (QA Q22, Q23)

---

# 8. Task Management

Every onboarding stage may generate one or more tasks.

Each task should contain:

- Title
- Description
- Priority
- Assigned User
- Due Date
- Status
- Related Milestone
- Comments
- Attachments
- Checklist
- Time Tracking (optional)

Task statuses include:

- Pending
- In Progress
- Waiting
- Completed
- Cancelled

---

# 9. Agreement Management

The system should manage the complete agreement lifecycle.

Features include:

- Agreement Templates
- Agreement Generation
- Version Control
- Approval Workflow
- Digital Signatures (Future)
- Expiration Tracking
- Renewal Reminders
- Secure Storage

Agreement statuses:

- Draft
- Under Review
- Sent
- Awaiting Signature
- Signed
- Expired
- Cancelled

---

# 10. Document Management

Every onboarding project should maintain a centralized document repository.

Supported document categories include:

- Contracts
- Agreements
- NDAs
- Company Registration
- Tax Documents
- KYC
- Technical Documents
- Certificates
- Invoices
- Custom Attachments

Capabilities:

- Upload
- Download
- Version History
- Preview
- Categories
- Expiration Dates
- Approval Status
- Secure Storage
- Access Control

### Visibility

Every document carries a visibility tier, and optionally a department target. (QA Q9)

Tiers — *how broadly* a document is shared:

- **Company-shared** — all approved contacts at the customer, plus the case team
- **Contact-only** — the uploading contact and permitted reviewers; other contacts at the same
  company cannot see it
- **Sensitive** — restricted even within the customer's own company until explicitly shared

Department targeting — *which group*, narrowing within a tier and never widening one:

- **Internal staff departments** — Legal, Finance, Operations, Compliance
- **Customer-side contact labels** — a named label on each contact, set by internal staff

Documents are scoped per journey, not per account. Sharing a file across two journeys of the same
account is an explicit action. Visibility is enforced server-side: a user must never receive a
document they may not see.

---

# 11. Activity Timeline

Every onboarding project should automatically generate a chronological activity timeline.

Events include:

- Customer Created
- Status Changed
- Milestone Completed
- Task Assigned
- Task Completed
- Document Uploaded
- Agreement Signed
- Comments Added
- Notifications Sent
- Workflow Changes

This timeline serves as the official audit history.

---

# 12. Customer Portal

The customer experience should be simple, transparent, and informative.

Customers should see:

- Overall Progress
- Current Stage
- Completed Milestones
- Pending Requirements
- Requested Documents
- Upcoming Activities
- Agreements
- Notifications
- Estimated Completion
- Every journey on their account, and the programme rolling them up
- Outputs delivered so far
- Status reports issued to them
- Plans awaiting their approval

Internal notes and restricted information must remain hidden.

### What the Sponsor Sees

A customer's project sponsor sees the **whole programme** — every parallel journey the provider is
running for them, and the rolled-up progress across all of it. This is the customer-side answer to
the same need the account manager has internally: complete visibility of every service being
delivered, in one place, rather than one journey at a time. (QA Q20)

The sponsor is also the person who **approves plans** — the shape of the customer's template, and
each journey's schedule (§7). Until the portal exists, that approval is recorded internally by the
account manager on the sponsor's behalf; the decision, its date and its approver are captured
identically either way. (QA Q22)

### New-Joiner Catch-Up

People join a customer's team part-way through an onboarding, and today they arrive with no idea
what has already happened. A contact granted access mid-journey should see a catch-up: what the
journey is, where it stands, what happened before they arrived, and what is now waiting on them.

It is assembled from the activity timeline rather than written by hand, and it is filtered through
the same visibility rules as every other read — it must never reveal a document or note the joiner
could not otherwise open. (QA Q17)

---

# 13. Notifications

Automatic notifications should support:

- New Customer
- Task Assignment
- Overdue Task
- Milestone Completion
- Document Request
- Document Approval
- Agreement Status
- Customer Comment
- Upcoming Deadline
- Workflow Changes
- Stage Entered / Exited
- Risk State Change

Delivery channels:

- In-App
- Email
- SMS (Optional)
- Microsoft Teams (Future)
- Slack (Future)

Delivery shape and policy (QA Q10, Q19):

- Alerts may be sent **per event** or rolled up into a **daily or weekly digest**
- **Deadline warning horizons are configurable** — a signature and a document request do not
  deserve the same lead time
- Every notification type is **opt-out-able**, with one exception: escalation to the assignee's
  manager on overdue work is mandatory and cannot be disabled

---

# 14. Reporting & Analytics

The platform should provide operational and executive reporting.

Examples include:

- Average Onboarding Time
- Customer Pipeline
- Stage Bottlenecks
- Department Performance
- SLA Compliance
- Team Productivity
- Overdue Tasks
- Completion Rates
- Customer Satisfaction (Future)

Reports should support export to PDF, Excel, and CSV.

### Status Reports

Distinct from the analytics above, a **status report** is an artifact issued to a customer rather
than a screen queried by staff. Generated on demand or on a schedule, each one captures progress and
how it moved since the last report, milestones closed in the interval, outputs produced, what is
open with the customer and what is open with the provider, and current risk state.

A status report is **dated and immutable once issued**, and past reports remain readable. The reason
is history, not convenience: *"what did we report on 15 October?"* must have an answer, for
governance packs and for disputes. A live screen cannot answer it. (QA Q28)

---

# 15. Security

Enterprise-grade security should include:

- Role-Based Access Control (RBAC)
- Multi-Factor Authentication (Optional)
- Single Sign-On (Future)
- Encrypted Document Storage
- Audit Logging
- Secure API Access
- Session Management
- Backup & Disaster Recovery

---

# 16. Non-Functional Requirements

The platform should be:

- Fast and responsive
- Mobile-friendly
- Highly available
- Scalable for thousands of onboarding projects
- Cloud-ready
- API-first
- Accessible (WCAG compliant)
- Multi-language ready
- **Light theme only**

Dark mode was dropped deliberately on 2026-08-25, not deferred. The design system at
`docs/uispecs_latest/design_handoff_onboarding_platform/` defines no dark palette anywhere, and the
frontend refactor for sub-projects 1–2 removed the theming mechanism, `next-themes`, the
`ThemeProvider` and the theme toggle from the application rather than leaving them half-wired
against tokens that no longer exist. Reintroducing a dark palette needs a design decision first.

---

# 17. Future Roadmap

Planned enhancements include:

- AI-powered onboarding assistant
- Intelligent workflow recommendations
- OCR for document processing
- Digital signatures
- CRM integrations
- ERP integrations
- Payment tracking
- Customer support ticketing
- Workflow automation rules
- Approval matrices
- Mobile applications
- Calendar synchronization
- Webhooks and public APIs
- Business intelligence dashboards

---

# 18. Suggested Technology Stack

### Frontend
- Next.js
- React
- TypeScript
- Tailwind CSS
- shadcn/ui
- Framer Motion
- TanStack Query

### Backend
- Java 21
- Spring Boot
- Spring Security
- REST & WebSocket APIs
- Hibernate / JPA

### Database
- PostgreSQL

### Caching
- Redis

### File Storage
- must support local file storage and 
- Amazon S3 or Azure Blob Storage
- configurable
### Authentication
- JWT with Refresh Tokens
- OAuth2 / OpenID Connect
- Multi-Factor Authentication

### Infrastructure
- Docker
- Kubernetes
- GitHub Actions
- NGINX
- Cloud Deployment (AWS, Azure, or GCP)

---

# 19. Success Criteria

The platform will be successful when it:

- Becomes the single source of truth for all onboarding projects.
- Reduces manual coordination across departments.
- Provides customers with real-time visibility into their onboarding journey.
- Enables configurable workflows without software changes.
- Improves collaboration and accountability through shared workspaces.
- Delivers a modern, enterprise-quality user experience suitable for organizations of any size.