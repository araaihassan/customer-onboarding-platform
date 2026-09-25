# Agreements — Design Spec

**Sub-project 5.** Adds the `agreement` module: an agreement record in Q13's three record modes,
immutable submitted versions carrying a provable content hash, a mandatory four-eyes internal
review, signatories and staff-recorded signatures behind a `SignatureProvider` interface (Q14),
derived expiry, and the new `RequirementKind.SIGNATURE` that Q25 assigned to this sub-project.

Depends on 1 (tenancy, identity, authz, audit, customer), 2 (workflow, journey), 3 (the
requirement-instantiation seam precedent), 4 (document storage, `document_version.sha256`, the
`AudienceFilter` seam, the portal-read precedent). Unblocks 7 (the portal `cagree` screen) and 9
(agreement reporting).

---

## 1. Context

PRD §9 asks for the complete agreement lifecycle: templates, generation, version control, an
approval workflow, digital signatures (marked *Future*), expiration tracking, renewal reminders and
secure storage, across seven statuses — Draft, Under review, Sent, Awaiting signature, Signed,
Expired, Cancelled. QA Q13 makes "does an agreement need a file?" a template setting, drawn in the
design as three record modes. QA Q14 names OpenSign as the provider, and `DOMAIN_RULES.md` §Q14
says to wrap it behind a provider interface. QA Q25 assigns a `SIGNATURE` requirement kind to this
sub-project; Q27 makes a signed agreement a journey output automatically, through `satisfiedRef`.

The design handoff draws three surfaces: the case workspace Agreements tab (SCREENS "Other tabs"),
the operator `agreements` lifecycle screen (SCREENS §8), and the portal `cagree` screen (SCREENS
§18, sub-project 7's).

### 1.1 Decisions taken in brainstorming (2026-09-25)

In the order they were made — none is to be re-asked:

1. **Scope: core lifecycle, provider as an interface.** Record + versions + review + signatories +
   manual signature recording + derived expiry + the `SIGNATURE` kind. The real OpenSign adapter,
   template-driven document generation and renewal reminders are out (§2.2).
2. **Agreements are instantiated at case creation**, one per `SIGNATURE` requirement, exactly as
   `DOCUMENT` instantiates a `document_request`. No manual linking of an agreement to a requirement.
3. **Review is mandatory and four-eyes**: the reviewer may be neither the submitter nor the draft's
   last editor.
4. **Staff record signatures** (`ManualSignatureProvider`); each signature cites the content hash of
   the exact version sent. No portal click-to-sign.
5. **Visibility follows the case**; a portal contact sees their own customer's agreements only from
   `SENT` onward.
6. **Cancel-and-replace**: cancelling creates a fresh `DRAFT` for the same requirement in the same
   transaction, so a requirement is never stranded.
7. **Approach 1**: a new `agreement` module that consumes `document` for files, one-way.
8. **`EXPIRED` is derived on read, never stored** (§5.7) — decided after finding that the codebase
   has no scheduled job at all.

---

## 2. Scope

### 2.1 In

- `RequirementKind.SIGNATURE`, its two definition fields, publish validation and builder fields.
- `agreement`, `agreement_signatory`, `agreement_version`, `agreement_version_review`,
  `agreement_signature` (§4).
- The lifecycle and state machine of §5, satisfaction through the existing gated
  `RequirementService.satisfy`, and cancel-and-replace.
- `SignatureProvider` with one implementation, `ManualSignatureProvider`.
- Four permissions, one descriptor per read entity, an `AgreementAudienceFilter`, role-template
  grants (§6).
- A narrow `document.AgreementFiles` facade (§7).
- Server-side portal read endpoints (§6.5). No portal UI.
- Frontend: case Agreements tab, operator lifecycle screen, builder fields, roadmap chip link (§9).

### 2.2 Out, and why

| Item | Why |
|---|---|
| **Real OpenSign integration** | PRD §9 marks digital signatures *Future*. The interface lands now; the adapter (envelope creation, webhooks, pulling the signed PDF back) is a later drop-in. **Recorded in CLAUDE.md as not implemented**, at the user's request, so it is picked up deliberately rather than assumed. |
| Agreement generation from templates / merge fields | A text-templating and rendering subsystem of its own; nothing depends on it yet. "Template" here means only the workflow requirement definition that fixes the record mode. |
| Renewal reminders, expiry notifications | Sub-project 6 owns notifications and the first scheduled job. This sub-project stores the dates and derives expiring/expired state. |
| An `agreement.expired` audit event | Needs a job to notice the moment; sub-project 6 (§5.7). |
| Standalone agreements (not tied to a requirement), renewals as new agreements | Not needed by the requirement path; addable later without changing the model. |
| Portal signing | Waits for OpenSign (decision 4). |
| Portal `cagree` UI | Sub-project 7. |

---

## 3. Architecture

### 3.1 The module

`co.ara.onboarding.agreement` owns its entities, repositories, services and controllers, in the
shape of sub-project 1's Tasks 20–21. Dependencies:

```
agreement ──▶ journey    (RequirementService.satisfy, StageWriteScopeGuard, AgreementLifecycle port)
agreement ──▶ document   (AgreementFiles facade only)
agreement ──▶ authz, audit, platform, identity/customer via existing directories
journey   ─╳─▶ agreement
document  ─╳─▶ agreement
```

`ModuleBoundaryTest` gains `noJourneyDependencyOnAgreement` and `noDocumentDependencyOnAgreement`,
each its own named method (a one-way import would pass the plain cycle check), each proven red by a
temporary violation before it is trusted.

### 3.2 The instantiation port

`journey` declares `AgreementLifecycle { void instantiateForCase(UUID caseId); }`, implemented by
`agreement.AgreementLifecycleAdapter` — the same seam as `DocumentRequestLifecycle`/`TaskLifecycle`.

- `CaseService.create` calls it after `documentRequestLifecycle.instantiateForCase`.
- **`MigrationService` also calls it after repinning.** A migration can introduce a `SIGNATURE`
  requirement the case never had; without an agreement it would be unsatisfiable, the stranded
  shape §5.6 exists to prevent. The call is idempotent: it creates an agreement only for a
  `SIGNATURE` requirement with no live (non-`CANCELLED`) agreement. (The same gap exists for
  `DOCUMENT` requirements introduced by migration today — noted in §11.1, not fixed here.)

### 3.3 Relationship to `CaseEngine`

None directly. Satisfaction goes through `journey.RequirementService.satisfy`, which already takes
`CaseRepository.lockById`'s row lock and calls `reconcile`. **No new caller of `reconcile`.**

### 3.4 `SignatureProvider`

```java
public interface SignatureProvider {
    SignatureProviderKind kind();                         // MANUAL, later OPENSIGN
    Optional<String> send(Agreement a, AgreementVersion sent);   // returns an envelope id, if any
}
```

`ManualSignatureProvider.send` does nothing and returns empty; signatures then arrive through the
staff-facing record endpoint (§5.5). The OpenSign adapter will create an envelope in `send` and
deliver signatures through a webhook that calls the same internal recording path. The provider is
selected per tenant by configuration, defaulting to `MANUAL`; only `MANUAL` exists.

---

## 4. Data model

Two migrations, numbers decided at dispatch time (`V23` is highest as this is written): one adding
§4.1's requirement-definition columns, one creating §4.2–4.6's tables. Every new table has a non-null `tenant_id`, calls
`enable_tenant_rls` in the same migration, uses UUIDv7 keys, and has DELETE denied. All timestamps
`timestamptz`.

### 4.1 `requirement_definition` — two new nullable columns

| Column | Notes |
|---|---|
| `agreement_record_mode` | `FILE_BACKED` \| `STRUCTURED_PLUS_FILE` \| `STRUCTURED_ONLY`; CHECK constraint |
| `agreement_name` | The default name of the instantiated agreement |

Typed nullable columns per kind, the `documentCategory`/`requiresReview` precedent. Publish
validation: every `SIGNATURE` requirement has both; no non-`SIGNATURE` requirement has either.
`RequirementKind` becomes `{ TASK, DOCUMENT, APPROVAL, MANUAL, SIGNATURE }`.
`WorkflowDefinitionRequest`'s requirement record and its view gain both fields (PUT alignment).

### 4.2 `agreement`

| Column | Notes |
|---|---|
| `case_id`, `requirement_id` | NOT NULL, FK |
| `customer_id` | NOT NULL; denormalised from the case for the portal audience filter |
| `name` | NOT NULL; editable in `DRAFT` |
| `record_mode` | NOT NULL; copied from the definition, never editable |
| `status` | `DRAFT`, `UNDER_REVIEW`, `APPROVED`, `SENT`, `AWAITING_SIGNATURE`, `SIGNED`, `CANCELLED` |
| `effective_date`, `expires_at`, `renewal_date` | nullable `date`; editable in `DRAFT` |
| `notice_period_days` | nullable, `>= 0` |
| `owner_user_id` | NOT NULL; the case's `OWNER` participant at instantiation (Q15) |
| `document_id` | nullable; the agreement's owned document (§7), created on first file upload |
| `last_edited_by` | NOT NULL; the actor of the most recent draft write (fields, signatories, file) |
| `replaces_agreement_id` | nullable self-FK; set on a cancel-and-replace successor |
| `cancel_reason` | required when `CANCELLED` (CHECK) |
| `signed_at` | set on `SIGNED` |
| `signature_provider` | NOT NULL, `MANUAL` |
| `provider_envelope_id` | nullable; always null until OpenSign |
| `lock_version` | optimistic lock |

**Partial unique index** on `(requirement_id) WHERE status <> 'CANCELLED'` — at most one live
agreement per requirement, enforced by the database.

`APPROVED` is not one of PRD §9's seven. It is the "reviewed, ready to send" rest state the
four-eyes rule needs between approval and sending; the lifecycle screen counts it under "Under
review". `EXPIRED` is deliberately absent (§5.7).

### 4.3 `agreement_signatory`

| Column | Notes |
|---|---|
| `agreement_id` | NOT NULL |
| `kind` | `CONTACT` \| `INTERNAL` |
| `contact_id`, `user_id` | exactly one non-null, matching `kind` (CHECK) |
| `display_role` | e.g. "Customer signatory", "Provider signatory" |
| `sort_order` | int |

Editable only in `DRAFT` (replace-the-list semantics, §8). **The one table in this sub-project with
`GRANT DELETE`** (amended while planning): replacing a draft's list deletes its signatory rows, and
a signatory row is not a business record — the frozen copy a signature is proven against lives in
`agreement_version.structured_snapshot`. The service deletes only signatories of a `DRAFT`
agreement; the migration carries a comment saying why. A `CONTACT` signatory must be an
`ACTIVE` contact of the agreement's own customer; an `INTERNAL` signatory must be an `ACTIVE` user
resolvable through `AuthorizedQuery` under `user.view`. Unique `(agreement_id, contact_id)` and
`(agreement_id, user_id)`.

Signatory rows are *not* history: the version snapshot (§4.4) is what a signature is proven
against. A signatory can therefore be the target of a signature only while the agreement is past
`DRAFT`, when the list is frozen.

### 4.4 `agreement_version` — append-only

`GRANT SELECT, INSERT` only; `UPDATE`/`DELETE` revoked (the `plan_revision_item`/`audit_event`
shape).

| Column | Notes |
|---|---|
| `agreement_id`, `version_number` | unique together; `version_number` from 1 |
| `submitted_by`, `submitted_at` | |
| `last_edited_by` | copied from `agreement.last_edited_by` at submit — the four-eyes input |
| `structured_snapshot` | jsonb: name, dates, notice period, record mode, signatories |
| `document_version_id`, `document_sha256` | required when the record mode includes a file; null for `STRUCTURED_ONLY` (CHECK against a copied `record_mode` column) |
| `content_sha256` | NOT NULL, §4.4.1 |

#### 4.4.1 The content hash

`content_sha256 = SHA-256( canonical_json(structured_snapshot) || 0x00 || (document_sha256 ?? "") )`,
hex-encoded. Canonical JSON: UTF-8, keys sorted lexicographically, no insignificant whitespace,
dates ISO-8601 (`yyyy-MM-dd`), signatories ordered by `sort_order` then id, absent optionals
serialised as `null` rather than omitted. One `AgreementContentHasher` owns this; nothing else
computes it. Every record mode therefore has a provable identity, not just file-backed ones.

### 4.5 `agreement_version_review` — append-only

Same `GRANT` shape. `agreement_version_id` (unique — one decision per version), `decision`
(`APPROVE` \| `REJECT`), `reviewer_id`, `reviewed_at`, `reason` (required on `REJECT`, CHECK).
Kept separate from `agreement_version` precisely so that table stays strictly INSERT-only.

### 4.6 `agreement_signature` — append-only

Same `GRANT` shape.

| Column | Notes |
|---|---|
| `agreement_id`, `signatory_id` | unique `(agreement_id, signatory_id)` — never twice |
| `agreement_version_id` | the version that was sent |
| `signed_content_sha256` | copied from that version, so the proof sits on the row itself |
| `signed_on` | `date`, supplied by the recorder; not in the future |
| `method` | free text, required ("wet ink", "signed PDF returned by email") |
| `recorded_by`, `recorded_at` | |
| `countersigned_document_version_id` | set only on the final signature of a file-including agreement |

---

## 5. Lifecycle

```
DRAFT ──submit──▶ UNDER_REVIEW ──approve (reviewer ∉ {submitter, last editor})──▶ APPROVED ──send──▶ SENT
  ▲                    │                                                                          │
  └───reject(reason)───┘                                                        record 1st of N signatures
                                                                                                  ▼
   any pre-SIGNED ──cancel(reason)──▶ CANCELLED (+ new DRAFT successor)   AWAITING_SIGNATURE ──last──▶ SIGNED
```

Every transition is gated (§6.2), loads the agreement through `AuthorizedQuery`, calls
`StageWriteScopeGuard` against the requirement's stage, checks `lock_version`, and refuses an
illegal source status with 409.

### 5.1 Draft editing

`PATCH` fields, replace signatories, upload a file (§7) — `agreement.manage`, `DRAFT` only. Each sets
`last_edited_by` to the actor. Uploading a file on a `STRUCTURED_ONLY` agreement is refused (409).

### 5.2 Submit

`agreement.manage`, `DRAFT → UNDER_REVIEW`. Validates: at least one signatory; a file present iff
the record mode includes one; `effective_date` present unless `FILE_BACKED`; `expires_at` after
`effective_date` when both present. Writes the next `agreement_version` with its snapshot and
`content_sha256`. Records `agreement.submitted`.

### 5.3 Review

`agreement.review`, `UNDER_REVIEW` only, against the latest version.

- **Four-eyes, enforced in the service**: refused with 409 (`SelfReviewException`) when the actor is
  the version's `submitted_by` or its `last_edited_by`. The UI shows this (§9.1), but the UI is not
  the guard.
- `APPROVE` → `APPROVED`, records `agreement.approved`.
- `REJECT` (reason required) → `DRAFT`, records `agreement.rejected`. The next draft write and submit
  produce a new version; the rejected version and its review remain.

### 5.4 Send

`agreement.manage`, `APPROVED → SENT`. Calls `SignatureProvider.send` with the approved version,
stores any returned envelope id, and retiers the owned document to `COMPANY_SHARED` (§7). Records
`agreement.sent`. The **sent version** is the latest version; it cannot change afterwards, because no
draft write is legal past `DRAFT`.

### 5.5 Record a signature

`agreement.sign_record`, from `SENT` or `AWAITING_SIGNATURE`.

- The signatory id is resolved as a signatory **of this agreement**; any other id is 404.
- Refused (409) if that signatory already has a signature.
- Writes `agreement_signature` citing the sent version and copying its `content_sha256`.
- If this is the **last** unsigned signatory and the record mode includes a file, the request must
  carry the countersigned file (multipart). It is stored as a new version of the agreement's own
  document through `AgreementFiles`, and that version's id is written to
  `countersigned_document_version_id` — **server-produced, never an id taken from the request**, so
  there is no foreign-document escalation path to guard.
- First signature: `SENT → AWAITING_SIGNATURE`. Last signature: `→ SIGNED`, `signed_at = now`, then
  §5.6's satisfaction. (A one-signatory agreement goes `SENT → SIGNED` directly.)
- Records `agreement.signature_recorded`, and `agreement.signed` on the last.

A contact retired after being frozen into the signatory list can still have a signature recorded:
the recorder is logging a real-world act that has already happened.

### 5.6 Satisfaction, and the stranded-requirement guards

On `SIGNED`, **as the very last step**, the service calls
`RequirementService.satisfy(requirementId, satisfiedRef = agreementId, satisfiedRefType = "AGREEMENT")`.
Audit order is therefore `agreement.signed` → `requirement.satisfied` → `milestone.completed`
(cause before effect).

Guards, present from the first draft of each path (CLAUDE.md "What sub-project 5 inherits"):

- **Satisfy only a live, signed agreement**: the call is made only when this agreement is the
  requirement's single non-`CANCELLED` agreement and its status is exactly `SIGNED`.
- **Cancel never satisfies or waives.** It is a status write, a retier, and the successor insert —
  it calls nothing on `RequirementService`.
- **A requirement already satisfied or waived is not re-satisfied.** If the requirement was waived
  through the existing path, the agreement carries on untouched (it is not auto-cancelled); reaching
  `SIGNED` later skips the `satisfy` call when the requirement is no longer open.

### 5.7 Expiry — derived

`EXPIRED` is never stored. Every view computes
`displayStatus = (status == SIGNED && expires_at != null && expires_at < today(UTC)) ? EXPIRED : status`.
The "Expiring ≤30d" figure is a filter: `status = SIGNED AND expires_at BETWEEN today AND today+30`.

**Expiry never un-satisfies.** The agreement was signed and the milestone delivered; progress never
regresses. Why derived: a stored `EXPIRED` needs a daily job, the codebase has none, and the first
one must iterate tenants while connected as the RLS-bound `onboarding_app` — infrastructure
sub-project 6 is specified to build. The cost is recorded in §2.2: no `agreement.expired` event
fires at the moment of expiry.

### 5.8 Cancel and replace — what cancellation revokes

`agreement.manage`, from any status before `SIGNED` (a `SIGNED` agreement cannot be cancelled), reason
required. In one transaction:

1. `status = CANCELLED`, `cancel_reason` set. Records `agreement.cancelled`.
2. If the agreement had been sent, the owned document is retiered back to `SENSITIVE`, so a withdrawn contract stops being downloadable by the portal.
3. A successor is inserted: same case, requirement, name, record mode, dates and signatory list,
   `status = DRAFT`, `replaces_agreement_id` = the cancelled one, no document (a fresh draft starts
   without the old file), `owner_user_id` and `last_edited_by` = the actor. Records
   `agreement.created`.

The partial unique index (§4.2) makes step 3 legal only after step 1, inside the same transaction.

### 5.9 Audit actions

`agreement.created`, `.submitted`, `.approved`, `.rejected`, `.sent`, `.signature_recorded`,
`.signed`, `.cancelled` — new `AuditActions` constants, all `timeline_visible = true` (business
records, the `customer.*`/`contact.*` split). The internal Activity tab shows the full sequence.
Sub-project 7, when it builds a portal timeline, must apply the same pre-`SENT` rule as §6.4 to these
events rather than rendering every visible row — recorded in §11.3.

---

## 6. Authorization

### 6.1 Permissions

| Key | Scopes | Meaning |
|---|---|---|
| `agreement.view` | RECORD (ALL, DEPARTMENT, TEAM, ASSIGNED) | See agreements on cases in scope |
| `agreement.manage` | ORG (ALL, DEPARTMENT, TEAM) | Edit draft, signatories, file; submit, send, cancel |
| `agreement.review` | ORG | Approve or reject a submitted version |
| `agreement.sign_record` | ORG | Record a signatory's signature |

### 6.2 Descriptors

`scoping/AgreementDescriptor` resolves DEPARTMENT/TEAM/ASSIGNED through the agreement's case, the
`TaskDescriptor`/`DocumentRequestDescriptor` shape, failing closed (`cb.disjunction()`) with no
department or teams. `AgreementSignatory`, `AgreementVersion`, `AgreementVersionReview` and
`AgreementSignature` are read through `AuthorizedQuery` too, so each gets its own descriptor resolving
through its agreement's case — the `CaseParticipantDescriptor` lesson: `DescriptorRegistry.validate()`
checks only catalogued resource types and will not remind anyone.

Every public `*Service` method carries `@RequirePermission`. Every id taken from a URL or body —
case, agreement, signatory, signatory contact, signatory user — is resolved through `AuthorizedQuery`
before any write. The `agreement` package is added to
`AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly` in the same commit that adds
the first service.

### 6.3 Role templates

| Template | Grants |
|---|---|
| Project Manager | `view`, `manage`, `sign_record` at TEAM |
| Account Manager | `view`, `manage` at TEAM — **not** `sign_record`, see below |
| Legal | `view`, `review` at ALL — "Reviews agreements", and the natural second pair of eyes |
| Operations | `view`, `manage`, `sign_record` at DEPARTMENT |
| Finance, Compliance | `view` at ALL |
| Sales Representative, Service Provider, Business Partner | `view` at ASSIGNED |
| Technical, Support | `view` at TEAM |
| Administrator | all four at ALL |

**`sign_record` implies `milestone.complete`** (amended while planning, 2026-09-25). The last
signature calls `RequirementService.satisfy`, which is itself gated `milestone.complete`. A template
holding `sign_record` without `milestone.complete` at an equal-or-broader scope would have its final
signature refused and rolled back — the whole agreement stuck one signature short. Account Manager
holds no `milestone.complete`, so it does not get `sign_record`; Project Manager (TEAM), Operations
(DEPARTMENT) and Administrator (ALL) all already hold `milestone.complete` at the matching scope. A
derived guard in `RoleTemplateCoverageTest`'s neighbour enforces the implication for every template,
and a hand-built role breaking it is refused atomically (no signature row written).

`RoleTemplateCoverageTest` passes by construction — each multi-scope permission is held by a template
other than Administrator. Narrowest-scope write tests run `manage` and `sign_record` at TEAM, and
`review` at TEAM via a hand-built role.

### 6.4 `AgreementAudienceFilter`

Registered on the sub-project 4 `AudienceFilter` seam, so it binds even at `Scope.ALL`.

- **Internal actors**: `cb.conjunction()` — case scope already governs them.
- **Portal actors**: `customer_id = <acting contact's customer>` AND
  `status IN (SENT, AWAITING_SIGNATURE, SIGNED)`, resolving the contact through
  `PortalContactDirectory` (one definition of "active contact for this user"). No contact ⇒
  `cb.disjunction()`.

### 6.5 Portal

`PortalPermissions.forContact()`/`forSponsor()` gain `agreement.view` at ALL; the audience filter
does all the narrowing. Endpoints:

- `GET /api/t/{slug}/portal/agreements` — the contact's customer's agreements from `SENT` onward.
- `GET /api/t/{slug}/portal/agreements/{id}` — `PortalAgreementView`: name, record mode, display
  status, the **sent** version's snapshot and `content_sha256`, signatories with signed/pending and
  `signed_on`, dates, and `documentId` for download through the existing portal document endpoints.
  **No** reviewer identities, rejection reasons, cancel reasons, internal version history, or
  `recorded_by`.

No portal write path. The read path follows the precedent in CLAUDE.md: the gated method re-verifies
the acting contact and customer itself, not only its controller.

---

## 7. Files — `document.AgreementFiles`

Every document write an agreement needs goes through one narrow facade in the `document` module:

| Method | Gate | Does |
|---|---|---|
| `createOwnedDocument(caseId, name, stream)` | `agreement.manage` | Creates a `document` (category `AGREEMENT`, tier **`SENSITIVE`**, untargeted, home case = the agreement's case) plus its first version, through the existing hardened upload path |
| `addVersion(documentId, stream)` | `agreement.manage` or `agreement.sign_record` | Adds a version to that document |
| `retier(documentId, tier)` | `agreement.manage` | `SENSITIVE` ↔ `COMPANY_SHARED` |

Uploads reuse everything sub-project 4 hardened: sniffed-content MIME allowlist, size ceiling, opaque
storage keys, per-version SHA-256, `Content-Disposition: attachment`. The facade uses only `authz`
permission keys — `document` still names no `agreement` type.

**Why `SENSITIVE` first.** A company-shared draft file would be downloadable through the existing
portal *document* endpoints while the agreement is still a `DRAFT`, breaking decision 5. `SENSITIVE`
does not narrow internal case readers (tier binds only the portal audience) but hides the document
from portal contacts absent an explicit share. `send` moves it to `COMPANY_SHARED`; cancelling a sent
agreement moves it back.

**Why a facade.** An agreement author should not also need `document.manage`/`document.upload`, and
an agreement's document should be mutable only via its agreement. The facade is a Java API with no
controller of its own; it refuses any document whose category is not `AGREEMENT`, and `agreement`
only ever passes a `document_id` it read from its own row — never one taken from a request.

---

## 8. API surface

All under `/api/t/{slug}/`.

| Method | Path | Permission |
|---|---|---|
| `GET` | `/agreements?status=` | `agreement.view` — operator index, paginated |
| `GET` | `/agreements/summary` | `agreement.view` — six counts, scope-filtered through `AuthorizedQuery` |
| `GET` | `/cases/{caseId}/agreements` | `agreement.view` |
| `GET` | `/agreements/{id}` | `agreement.view` — detail: fields, signatories, versions + reviews, signatures |
| `PATCH` | `/agreements/{id}` | `agreement.manage` — name, dates, notice period (`DRAFT`) |
| `PUT` | `/agreements/{id}/signatories` | `agreement.manage` — full replace of the list (`DRAFT`) |
| `POST` | `/agreements/{id}/file` | `agreement.manage` — multipart, new draft file version (`DRAFT`) |
| `POST` | `/agreements/{id}/submit` | `agreement.manage` |
| `POST` | `/agreements/{id}/versions/{n}/review` | `agreement.review` |
| `POST` | `/agreements/{id}/send` | `agreement.manage` |
| `POST` | `/agreements/{id}/signatures` | `agreement.sign_record` — multipart when countersigned file required |
| `POST` | `/agreements/{id}/cancel` | `agreement.manage` |
| `GET` | `/portal/agreements`, `/portal/agreements/{id}` | portal-derived |

`PATCH` is used for fields to avoid the full-replace trap. `PUT …/signatories` is a genuine full
replace; the detail view carries the same signatory fields its request accepts. The summary endpoint
**does not** use `countIgnoringScope` — the lifecycle screen counts only what the reader could open.
It uses a new `AuthorizedQuery.count`, composing `forPermission` exactly as `findAll` does
(amended while planning). A draft's dates are cleared through `PatchAgreementRequest.clear`, a set
of field names, because a `PATCH` null means "unchanged".
Out-of-scope and cross-tenant ids are 404.

---

## 9. Screens

Built against `docs/uispecs_latest/design_handoff_onboarding_platform/`. Every frontend task invokes
`frontend-design` and `ui-ux-pro-max`, and verifies with `tsc --noEmit` and `next lint` as well as
vitest (3A's process note).

### 9.1 Case workspace — Agreements tab

- Rows: `§` tile on `automation.bg`, name, mono meta line ("v3 · file-backed · signed 14 Aug via
  manual record" / "structured record only · no file required"), status chip, `Open`. Cancelled
  agreements collapse under "Replaced (n)".
- `Open` shows a detail panel: editable fields and signatory picker (contacts of this customer,
  internal users) while `DRAFT`; file upload reusing `UploadDialog`; version history with short mono
  hash and review outcome; signatures with signed/pending, date, method.
- Actions per status and permission: `Submit for review`; `Approve`/`Reject` — **disabled with an
  explanation** when the viewer submitted or last edited ("You submitted this version, so someone else
  must review it"), not hidden; `Send`; `Record signature` (with the countersigned upload on the last
  signatory of a file-including agreement); `Cancel and replace`.
- Derived `EXPIRED` has its own chip; "Expires in 12d" when within 30 days.

### 9.2 Operator `agreements` screen (SCREENS §8)

New sidebar entry for `agreement.view` holders. Lifecycle card: Draft · Under review (incl.
`APPROVED`) · Sent · Awaiting signature · Signed · Expiring ≤30d, from `/agreements/summary`. Table
Agreement / Customer / Record mode / Owner / Status (chip + date phrase), paginated, status filter,
rows linking to the case Agreements tab with the agreement open. Card-list fallback below 900px.
Eyebrow `AGREEMENT LIFECYCLE · MANUAL SIGNING` — not "OPENSIGN CONNECTED", which would claim a
connection that does not exist.

### 9.3 Builder

A `SIGNATURE` requirement gets record-mode and agreement-name fields in the inspector, the PR #17
treatment of `DOCUMENT`'s category/`requiresReview`. Missing values surface as publish errors.

### 9.4 Roadmap

A `SIGNATURE` requirement's chip links to its live agreement, and to the satisfying agreement once
satisfied, through the `satisfiedRef`/`satisfiedRefType` PR #17 already surfaces.

### 9.5 Gaps the design does not cover

Empty states ("No agreements on this journey — a SIGNATURE requirement in the workflow creates one
automatically"), loading skeletons, error states, layouts below 1440px. Every string through `t()`;
ids, hashes, dates and counts in mono; every status colour paired with a word.

---

## 10. Invariant cross-check — sub-project 5's own ten

A change breaking one of these is a change to the design. Each is to be verified against the code at
close, not asserted.

1. `journey` and `document` never import an `agreement` type.
2. Signing adds **no new caller of `CaseEngine.reconcile`** — only `RequirementService.satisfy`.
3. A cancelled agreement never satisfies or waives anything.
4. At most one live agreement per `SIGNATURE` requirement, enforced by the database.
5. `agreement_version`, `agreement_version_review` and `agreement_signature` are append-only at the
   database layer.
6. Every signature cites the content hash of the exact version sent.
7. The reviewer is neither the submitter nor the last editor — enforced in the service.
8. A portal contact never sees an agreement, or its file, before `SENT`.
9. `EXPIRED` is derived, never stored, and never un-satisfies a requirement.
10. Out-of-scope and cross-tenant ids are 404; `PUT`/view types stay field-for-field aligned.

### 10.1 Tests that prove them

Security tests written before the mechanism they verify:

- `agreement.AgreementIsolationTest` — cross-tenant ids 404 on every endpoint.
- `agreement.AgreementScopeTest` — TEAM and ASSIGNED readers see only their cases' agreements.
- `agreement.AgreementReviewTest` — submitter refused, last editor refused, a third party succeeds;
  a TEAM-scoped review holder at the narrowest scope.
- `agreement.AgreementWriteScopeTest` — a wider-scoped holder refused inside an `OWNER_ONLY` stage.
- `agreement.AgreementIdEscalationTest` — a signatory contact from another customer, an out-of-scope
  internal user, another agreement's signatory id: each 404.
- `agreement.AgreementPortalTest` — nothing visible in `DRAFT`/`UNDER_REVIEW`/`APPROVED`; visible from
  `SENT`; another customer's never; no internal fields. **The file is not downloadable through the
  existing portal document endpoints before send, and stops being downloadable when a sent agreement
  is cancelled.**
- `agreement.AgreementImmutabilityTest` — `UPDATE`/`DELETE` refused as `onboarding_app` on the three
  append-only tables.

Lifecycle and engine: every legal transition, every illegal one 409; instantiation at create and
idempotently after migration; the last signature satisfies through `satisfy`, with
`CauseBeforeEffectTest` gaining `agreement.signed → requirement.satisfied → milestone.completed`;
cancel never satisfies; cancel-and-replace keeps exactly one live agreement and the index refuses a
second; a waived requirement is not re-satisfied; identical snapshots hash identically and each
signature row carries the sent version's hash; derived `EXPIRED` still satisfied; summary counts are
scope-filtered; `ModuleBoundaryTest`'s two new rules proven red then green; PUT/view alignment by
reflection.

Frontend: vitest per component (four-eyes disabled-with-reason, actions per status, empty/loading/
error), plus `tsc --noEmit` and `next lint`. Playwright `agreements.spec.ts`: seed a workflow with a
`SIGNATURE` requirement, open a case, draft, submit, be refused as own reviewer, approve as a Legal
user, send, record two signatures with the countersigned upload, see the milestone complete,
cancel-and-replace a second agreement, check the lifecycle counts.

---

## 11. Inherited state

### 11.1 Carried in, untouched by this sub-project's path

TEAM-scoped user creation; the builder's missing attribute/entry-condition UI; the audit-timeline read
carve-out. **New observation, not fixed:** `MigrationService` does not call
`DocumentRequestLifecycle`/`TaskLifecycle` after repinning, so a `DOCUMENT` or `TASK` requirement
introduced by migration gets no request/task. This sub-project closes the equivalent for `SIGNATURE`
only (§3.2).

### 11.2 Touched deliberately

`CaseService.create` and `MigrationService` (one port call each), `RequirementKind`,
`requirement_definition`, `WorkflowDefinitionRequest`, `PermissionCatalog`, `RoleTemplates`,
`PortalPermissions`, and `document` (the `AgreementFiles` facade only).

### 11.3 Carried forward

- **OpenSign is not implemented.** `SignatureProvider` has only `ManualSignatureProvider`;
  `provider_envelope_id` stays null. Recorded in CLAUDE.md.
- Sub-project 6: an `agreement.expired` event and renewal/expiry reminders, driven by the first
  scheduled job; `expires_at`/`renewal_date`/`notice_period_days` are already stored.
- Sub-project 7: the `cagree` screen over §6.5's endpoints, and a portal timeline that applies §6.4's
  pre-`SENT` rule to `agreement.*` events.

---

## 12. Question mapping

| Source | Where it lands |
|---|---|
| PRD §9 lifecycle and seven statuses | §4.2, §5 (`APPROVED` added, `EXPIRED` derived) |
| PRD §9 version control | §4.4 |
| PRD §9 approval workflow | §5.3 |
| PRD §9 expiration tracking | §5.7 |
| PRD §9 secure storage | §7 (sub-project 4's hardened path) |
| PRD §9 templates / generation | Templates = the requirement definition (§4.1); generation **out** (§2.2) |
| PRD §9 digital signatures (*Future*) | Interface only (§3.4); OpenSign **out** (§2.2, §11.3) |
| PRD §9 renewal reminders | **Out** — sub-project 6 |
| QA Q13 configurable file requirement | §4.1 `agreement_record_mode`, §5.2 |
| QA Q14 OpenSign behind an interface | §3.4 |
| QA Q25 `SIGNATURE` kind | §3.2, §4.1, §5.6 |
| QA Q27 outputs | Automatic via `satisfiedRef` (§5.6) |
| SCREENS "Other tabs" — Agreements | §9.1 |
| SCREENS §8 `agreements` | §9.2 |
| SCREENS §18 `cagree` | **Out** — sub-project 7; §6.5 makes it possible |
