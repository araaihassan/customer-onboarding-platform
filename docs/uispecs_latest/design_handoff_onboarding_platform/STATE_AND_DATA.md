# State & Data

What the UI needs to hold, and the shapes it reads. Types are illustrative TypeScript — adapt to
the codebase's conventions and generate them from the real API contract where one exists.

---

## 1. Client state

The prototype keeps everything in one component. **Do not reproduce that.** Split as follows:

### Server state (TanStack Query or equivalent)

Everything with a server origin: cases, milestones, tasks, documents, agreements, notifications,
workflow templates, users, permissions, analytics. Cache-keyed by tenant + scope + filters.

Real-time: the PRD asks for a dashboard that updates without manual refresh. Use the WebSocket
channel to invalidate query keys rather than to push view models — presence, case updates,
notification arrivals and SLA clock ticks all become invalidations.

### URL state

Route, selected case, active case tab, portfolio view mode (`table` | `board`), timeline grouping,
document scope filter, selected builder node, selected customer journey. These must survive reload
and be shareable — "look at this case, Documents tab" is a normal thing to send a colleague.

### Ephemeral UI state (local)

```ts
{
  paletteOpen: boolean; paletteQuery: string; paletteIndex: number;
  inboxOpen: boolean; inboxPane: 'list' | 'preferences';
  tenantMenuOpen: boolean; roleMenuOpen: boolean; journeyMenuOpen: boolean;
  expandedStageIds: string[];        // case accordion, multi-open
  explainOpen: boolean;              // "Why 44%?" panel
  forceComplete: { caseId, milestoneId, approverId, reason } | null;
  migrationSelection: Set<CaseId>;
  draggingNodeId: string | null;
  toast: { message: string } | null; // auto-clears after 2600ms
  composerDraft: string;
}
```

### Session state

`currentUser`, `tenantId`, `activeRole` (the "Viewing as" switcher — in production this reflects
the user's real role; a role *simulator* should exist for admins only and be visibly labelled),
notification preferences, table density preference.

---

## 2. Core types

```ts
type ISODate = string;   // '2026-09-18'
type Money   = { amount: number; currency: 'GBP' | 'USD' | 'EUR' };

interface Tenant { id: string; name: string; plan: string; activeCaseCount: number;
                   businessCalendarId: string; }

interface Account {              // the customer company
  id: string; legalName: string; displayName: string;
  segment: 'ENTERPRISE' | 'SMB' | 'MID_MARKET';
  contacts: Contact[]; journeys: CaseSummary[];   // an account holds MANY journeys
}

interface Contact { id: string; accountId: string; name: string; email: string;
                    isPrimary: boolean; portalStatus: 'NOT_INVITED' | 'INVITED' | 'ACTIVE';
                    invitationExpiresAt?: ISODate; }

interface Case {                 // one journey
  id: string;                    // 'ENT-04281'
  accountId: string;
  name: string;                  // 'Enterprise onboarding'
  subtitle: string;              // 'Head office · core platform'
  workflowTemplateId: string;
  workflowVersion: string;       // 'v2.3'  ← pinned; see DOMAIN_RULES Q2
  status: 'ACTIVE' | 'ON_HOLD' | 'LIVE' | 'CANCELLED';
  ownerId: string;
  participants: CaseParticipant[];
  stages: Stage[];
  value: Money;
  committedGoLive: ISODate;
  forecastGoLive: ISODate;       // diverges from committed → surface in warn
  sla: SlaClock;
  createdAt: ISODate;
}

interface CaseParticipant { userId: string; role: 'OWNER' | 'REVIEWER' | 'CONTRIBUTOR' | 'CUSTOMER';
                            team?: string; }

interface Stage {
  id: string; ordinal: number; name: string; team: string;
  estimatedDurationDays: number;                 // the weight — see Q6
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE' | 'SKIPPED';
  entryCondition?: BranchCondition;              // set when reached via a branch
  milestones: Milestone[];
  slaTargetDays: number;
  pausesOnCustomer: boolean;
}

interface Milestone {
  id: string; stageId: string; name: string;
  ownerId?: string; ownerLabel: string;          // 'Alex Smith (customer)' | 'Automated'
  estimatedDurationDays: number;
  completionPercent: number;                     // derived from tasks/docs/approvals
  state: 'NOT_STARTED' | 'IN_PROGRESS' | 'WAITING_CUSTOMER' | 'BLOCKED' | 'COMPLETE';
  isAutomated: boolean;
  blockedBy?: string[];                          // milestone ids
  note: string;                                  // 'Requested 22 Aug · SLA clock paused'
  tasks: Task[];
}

interface Task { id: string; milestoneId: string; title: string; description?: string;
                 priority: 'LOW' | 'MEDIUM' | 'HIGH';
                 status: 'PENDING' | 'IN_PROGRESS' | 'WAITING' | 'COMPLETED' | 'CANCELLED';
                 assigneeId?: string; dueDate?: ISODate; checklist?: ChecklistItem[];
                 attachments: DocumentRef[]; comments: Comment[]; }
```

### SLA clock — see `DOMAIN_RULES.md` §Q8

```ts
interface SlaClock {
  targetBusinessDays: number;
  elapsedBusinessDays: number;      // accumulated running intervals only
  state: 'RUNNING' | 'PAUSED' | 'BREACHED' | 'MET';
  pausedSince?: ISODate;
  pausedBusinessDays: number;
  pauseReason?: 'OPEN_DOCUMENT_REQUEST' | 'CASE_HOLD';
  pauseEligible: boolean;           // false for internal-only stages
  escalatedAt?: ISODate;
  escalatedToUserId?: string;
  businessCalendarId: string;
}
```

Never compute a clock as `now - startedAt`. Accumulate running intervals against the tenant
calendar. Expose one formatter that turns a `SlaClock` into the chip label
(`PAUSED 3.1d` / `BREACHED 2.0d` / `1.2d LEFT` / `AGED 4.0d`).

### Progress — see `DOMAIN_RULES.md` §Q6

```ts
interface ProgressBreakdown {
  totalEstimatedDays: number;
  percent: number;                                     // rounded for display only
  stages: Array<{ stageId: string; name: string; estimatedDays: number;
                  weight: number;        // 0..1
                  ownPercent: number;    // 0..100
                  contribution: number;  // percentage points added
                }>;
}

function computeProgress(case_: Case): ProgressBreakdown
```

One implementation, called by the operator case screen, the portfolio grid, the portal and any
report. `Σ contribution` must equal `percent` before rounding. Exclude stages skipped by an
unfollowed branch from `totalEstimatedDays`.

### Documents — see `DOMAIN_RULES.md` §Q9

```ts
type Visibility = 'COMPANY_SHARED' | 'CONTACT_ONLY' | 'SENSITIVE';

interface Document {
  id: string; caseId: string; accountId: string;
  name: string; category: 'CONTRACT' | 'AGREEMENT' | 'NDA' | 'COMPANY_REGISTRATION'
    | 'TAX' | 'KYC' | 'TECHNICAL' | 'CERTIFICATE' | 'INVOICE' | 'OTHER';
  version: number; versions: DocumentVersion[];
  visibility: Visibility;
  explicitlySharedWith: string[];        // user ids, for SENSITIVE
  uploadedByUserId: string; uploadedAt: ISODate;
  approvalStatus: 'PENDING' | 'APPROVED' | 'REJECTED';
  expiresAt?: ISODate;                   // drives renewal reminders at 30/14/7 days
  storage: 'LOCAL' | 'S3' | 'AZURE_BLOB';
}

interface DocumentRequest {              // an open request pauses the SLA clock
  id: string; caseId: string; milestoneId: string;
  requestedFromContactId: string; requestedAt: ISODate; dueDate: ISODate;
  status: 'OPEN' | 'FULFILLED' | 'WITHDRAWN'; remindersSent: number;
}
```

Documents are scoped **per journey**, not per account. Sharing a file across two journeys of the
same account is an explicit action.

### Agreements — see `DOMAIN_RULES.md` §Q13

```ts
interface Agreement {
  id: string; caseId: string; name: string; templateId?: string;
  recordMode: 'FILE_BACKED' | 'STRUCTURED_ONLY' | 'STRUCTURED_PLUS_FILE';
  requiresFile: boolean;                 // from the template — configurable
  version: number;
  status: 'DRAFT' | 'UNDER_REVIEW' | 'SENT' | 'AWAITING_SIGNATURE'
        | 'SIGNED' | 'EXPIRED' | 'CANCELLED';
  ownerId: string; signatories: Signatory[];
  signedAt?: ISODate; expiresAt?: ISODate; renewalDate?: ISODate; noticePeriodDays?: number;
  eSignProvider?: 'OPENSIGN'; eSignEnvelopeId?: string;
}
```

### Workflow templates — see `DOMAIN_RULES.md` §Q2, Q7

```ts
interface WorkflowTemplate { id: string; tenantId: string; name: string; versions: WorkflowVersion[]; }

interface WorkflowVersion {
  version: string;                       // 'v2.4'
  status: 'DRAFT' | 'PUBLISHED' | 'FROZEN' | 'ARCHIVED';
  publishedAt?: ISODate; publishedByUserId?: string;
  runningCaseCount: number;
  nodes: WorkflowNode[];                 // ordered graph
}

type WorkflowNode =
  | { kind: 'STAGE';  id: string; name: string; team: string; slaDays: number;
      pausesOnCustomer: boolean; transitionRule: string; milestones: MilestoneTemplate[];
      conditional?: boolean; }
  | { kind: 'BRANCH'; id: string; name: string;            // 'Segment is ENTERPRISE?'
      condition: BranchCondition; trueTargetId: string; falseTargetId: string; };

interface BranchCondition { field: 'segment' | string; op: 'eq' | 'neq' | 'in'; value: unknown; }

interface MigrationPlan {
  fromVersion: string; toVersion: string;
  changes: Array<{ kind: 'ADD' | 'SPLIT' | 'CHANGE' | 'REMOVE'; label: string; detail: string }>;
  cases: Array<{ caseId: string; risk: 'SAFE' | 'NEEDS_MAPPING' | 'BLOCKED'; reason: string;
                 milestoneMapping?: Record<string, string> }>;
  reversibleUntil: ISODate;              // +14 days
}
```

### Permissions — see `DOMAIN_RULES.md` §Q4

```ts
type ScopeLevel = 'NONE' | 'OWN' | 'TEAM' | 'SCOPE' | 'FULL';
type Resource = 'CASES' | 'DOCUMENTS' | 'AGREEMENTS' | 'WORKFLOWS' | 'USERS' | 'REPORTS';

interface RoleDefinition { id: string; name: string; tenantId: string;
                           permissions: Record<Resource, ScopeLevel>;
                           canForceComplete: boolean;      // Director and above
                           mfaRequired: boolean; }

interface AccessRequest { id: string; requesterId: string; resourceType: Resource;
                          resourceId: string; reason: string;
                          status: 'PENDING' | 'GRANTED' | 'DENIED';
                          grantedUntil?: ISODate; }        // time-boxed grants
```

### Audit & notifications

```ts
interface AuditEvent {
  id: string; tenantId: string; caseId?: string; at: ISODate;
  actorId: string; actorLabel: string;                     // 'System' for automation
  action: 'CASE_CREATED' | 'STATUS_CHANGED' | 'MILESTONE_COMPLETED' | 'FORCE_COMPLETE_APPROVED'
        | 'TASK_ASSIGNED' | 'DOCUMENT_UPLOADED' | 'DOCUMENT_REQUESTED' | 'AGREEMENT_SIGNED'
        | 'COMMENT_ADDED' | 'NOTIFICATION_SENT' | 'WORKFLOW_PUBLISHED' | 'PERMISSION_CHANGED'
        | 'SLA_PAUSED' | 'SLA_RESUMED' | 'ESCALATED' | 'ERASURE_REQUESTED';
  detail: string; reason?: string;                         // mandatory for force-complete
  retainUntil: ISODate;                                    // +7 years, configurable
}

type NotificationType =
  | 'TASK_ASSIGNED' | 'TASK_OVERDUE' | 'MILESTONE_COMPLETED' | 'DOCUMENT_REQUESTED'
  | 'DOCUMENT_DECIDED' | 'AGREEMENT_STATUS' | 'CUSTOMER_COMMENT' | 'DEADLINE_48H'
  | 'WORKFLOW_PUBLISHED';

interface NotificationPreference { type: NotificationType;
                                   channels: Array<'IN_APP' | 'EMAIL' | 'SMS'>;
                                   enabled: boolean; }      // all opt-out-able except escalation
```

### Dashboard configuration — see `DOMAIN_RULES.md` §Q16

```ts
type BlockType = 'rows' | 'funnel' | 'chart' | 'bars' | 'spotlight';

interface DashboardBlock { id: string; type: BlockType; span: 1|2|3|4;
                           title: string; subtitle: string; tag?: Chip;
                           dataQueryKey: string; }

interface RoleDashboard { roleId: string; eyebrow: string; headline: string; sub: string;
                          kpis: KpiSpec[];        // exactly 4
                          blockIds: string[]; }   // ordered
```

The headline is generated from live data ("Two cases need your decision before 5pm") — treat it as
a small server-side or client-side rule set, not a static string.

---

## 3. API surface implied by the design

Illustrative, REST-shaped; adapt to the real backend.

```
GET  /accounts/:id                          account + its journeys (portal & operator)
GET  /cases?scope&stage&sla&version&q       portfolio grid, board, timeline
GET  /cases/:id                             full case with stages, milestones, participants
GET  /cases/:id/progress                    ProgressBreakdown (or compute client-side from /cases/:id)
GET  /cases/:id/activity?cursor             audit timeline, paginated
POST /cases/:id/document-requests           creates request → pauses SLA
POST /cases/:id/milestones/:mid/force-complete-request   { approverId, reason }
POST /cases/:id/milestones/:mid/reassign    { userId }

GET  /dashboards/:roleId                    kpis + block payloads for the active role
GET  /exceptions                            war-room feed, grouped by severity
GET  /documents?scope&category&visibility   scoped repository (+ hiddenCount)
POST /documents                             multipart, with { visibility, sharedWith[] }
GET  /agreements
GET  /workflows/:id/versions
POST /workflows/:id/versions/:v/publish
GET  /workflows/:id/migrations/:from/:to    MigrationPlan (dry run)
POST /workflows/:id/migrations              { caseIds[], mappings }
GET  /roles                                 RBAC matrix
GET  /notifications  ·  PATCH /notifications/preferences
POST /invitations                           { contactId } → one-time activation link
GET  /search?q                              command palette (cases, docs, people, actions)

WS   /realtime                              presence, case updates, notifications, clock ticks
```

Portal endpoints are the same domain behind a customer principal, always journey-scoped:
`/portal/journeys`, `/portal/journeys/:id`, `/portal/journeys/:id/requirements`,
`/portal/journeys/:id/documents`, `/portal/journeys/:id/messages`.

---

## 4. Reference fixture data

The prototype's mock data is coherent and worth reusing for tests and Storybook. In particular:

- **Acme Foods Ltd.** — three journeys: `ENT-04281` Enterprise onboarding (44%, doc collection,
  SLA paused 3.1d, pinned v2.3, £740k), `ENT-04355` Manchester distribution centre (12%,
  registration, £180k), `ENT-04120` Payments module add-on (live since 4 July).
- **Orbit Finance** `ENT-04272` — legal review, SLA **breached 2.0d**, escalated, £1.2M. The
  worked example for escalation.
- **Rowan Health** `ENT-04244` — verification, 0.4d left, KYC expiring in 6 days.
- **Delta Works** `ENT-04231` — technical setup, paused on customer VPN credentials.
- Nine-stage Acme journey with the durations in `SCREENS.md` §3 — the fixture that must produce
  exactly 44%.

Use these in tests: if `computeProgress` on the Acme fixture doesn't return 44, the implementation
has drifted from the spec.
