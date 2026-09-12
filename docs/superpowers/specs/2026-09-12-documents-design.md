# Documents — Design Spec

**Sub-project 4.** Adds the `document` module: a blob-storage substrate with two adapters, a
document container with immutable versions, Q9's two-axis visibility enforced server-side for
internal *and* portal actors, and the `RequirementKind.DOCUMENT` seam that has existed in the enum
since sub-project 2 with nothing behind it.

Depends on 1 (tenancy, identity, authz, audit, customer), 2 (workflow, journey), 3 (the `task`
seam precedent). Unblocks 4A (meeting agendas and recordings), 5 (file-backed agreements) and 7
(the portal's documents).

---

## 1. Context

PRD §10 asks for a centralised repository per onboarding project: upload, download, version
history, preview, categories, expiration dates, approval status, secure storage, access control.
QA Q9 and its 2026-08-29 amendment decide *who sees what*. QA Q11 decides retention and erasure.
The design handoff draws two screens — `docs` (operator, SCREENS §7) and `cdocs` (portal,
SCREENS §17).

Taken together that is not one subsystem but five: storage, the record, visibility, requests and
review, and compliance. Sub-project 3 ran 32 tasks and 3A ran 35; all five together is comfortably
past fifty. This spec takes four of the five and states plainly what it leaves.

### 1.1 What makes this sub-project different

Every sub-project so far has added a domain module in the shape CLAUDE.md describes: entity with
`tenant_id`, a migration calling `enable_tenant_rls`, a `ResourceAuthorizationDescriptor`, a gated
service, a thin controller. This one does that **and** two things no previous sub-project has done:

- It **modifies the authorization core**. Q9's visibility is not record scope, and cannot be
  expressed as record scope. §6 is the argument for the change and the bound on its blast radius.
- It **accepts binary input from external users**. Every input the platform takes today is JSON
  through bean validation. §7.6 records that the hardening decision is deliberately deferred and
  must be resolved before the upload path ships.

---

## 2. Scope

### 2.1 In

1. **Storage** — a `BlobStore` port with a local-filesystem adapter and an S3 adapter, one
   contract suite run against both, MinIO as a Testcontainer.
2. **The record** — `document` container, immutable `document_version` rows, the PRD's ten
   categories, per-journey scoping, expiry dates.
3. **Visibility** — Q9 in full: three tiers × department and contact-label targeting, explicit
   principal shares, explicit cross-journey shares. Enforced server-side in the query, for
   internal and portal actors alike.
4. **Portal authority, server-side** — a contact can read what Q9 says they may read, and can
   upload with a visibility choice. API and tests only; no portal UI.
5. **Requests and review** — the `RequirementKind.DOCUMENT` seam, ad-hoc "Request document",
   review with approve/reject, and the satisfaction rule tying the two together.
6. **Frontend** — the operator `docs` screen and the case workspace Documents tab.

### 2.2 Out, and why

| Left out | Why |
|---|---|
| **QA Q11 retention and erasure** | Nothing downstream needs it, and its erasure half touches `audit`, `customer` and `identity` as much as `document`. It is a sibling of 9/10, not a part of this. |
| **In-browser preview** | Rendering untrusted uploaded files is its own security problem. Download works; preview waits for a deliberate decision about sandboxing. |
| **Portal UI** | Sub-project 7 owns the portal's screens. This sub-project makes them possible, which is the part 7 cannot cheaply retrofit. |
| **Expiry notifications** | Sub-project 6 owns notification. This stores `expires_at` and exposes the read; nothing here fires. |
| **Orphaned-blob sweeping** | §7.4's write ordering can leave an unreferenced blob. Harmless and invisible; a sweeper belongs with Q11's retention work. |

### 2.3 The deferred hardening decision

Recorded in CLAUDE.md under *What sub-project 4 inherits* and repeated here because this spec is
where it must be resolved: a size ceiling; an extension/MIME allowlist validated against **sniffed**
content rather than the client's `Content-Type`; filename sanitisation; `Content-Disposition:
attachment` on every download; malware scanning with an undownloadable pre-clean state; a
per-version SHA-256; a per-tenant byte quota.

Two of these are settled by other decisions in this document and are therefore **in** regardless:
opaque generated storage keys (§7.2) mean no user-supplied string reaches a path, and
streaming-through-the-application (§7.3) means every byte transits the gate. The rest are open.
An upload endpoint that ships without an explicit ruling has made the ruling by default, which is
the failure mode this paragraph exists to prevent.

---

## 3. Architecture

### 3.1 The module

`co.ara.onboarding.document`, depending on `platform`, `tenancy`, `authz`, `audit`, `identity`,
`workflow`, `journey` and `customer`. That set follows `programme`, the most recent module, which
imports both `journey` and `customer` directly. The prohibition CLAUDE.md records is specific to
`journey` — `journey` may not import `customer` — not a general rule that a module may depend on
only one other domain.

**Nothing imports `document`.** This gets its own named `ModuleBoundaryTest` rule rather than
relying on the cycle check, for the reason `noJourneyDependencyOnCustomer` and
`noWorkflowDependencyOnJourney` already have their own: a one-way import would still pass a plain
no-cycles test.

- `noJourneyDependencyOnDocument`
- `noWorkflowDependencyOnDocument`
- `noTaskDependencyOnDocument`

Each proven red by a temporary violation before being relied on.

### 3.2 No port back into `journey`

`task` needed `TaskDirectory`/`TaskLifecycle` so `journey` could ask about tasks. `document` gets
no equivalent — but for a plainer reason than the one the ledger might suggest.

CLAUDE.md's sub-project 3 close recorded that `MilestoneRoadmapView.taskSummary` was computed over
every task on a milestone regardless of the reader's scope and was never rendered. **Both halves
were fixed by sub-project 3A** (Task 5): `TaskDirectoryAdapter.summaryFor` now reads through
`AuthorizedQuery` with the scope predicate ANDed in, defaulting every requested id to `(0, 0)` so a
caller never sees a missing key, and `MilestoneRow.tsx` renders the count. Verified in the code on
`main`, not inferred from the plan.

So the argument against a port here is **not** that the pattern is unsafe — 3A demonstrated it can
be done correctly. It is simply that nothing in `journey` needs to ask `document` anything. A port
built anyway would be an unused seam carrying a standing obligation to keep its scope filtering
right. The dependency stays one-way because one way is all that is needed.

Requirement satisfaction flows the proven direction instead: `document` calls `journey`'s already
gated `RequirementService.satisfy(requirementId, ref, refType)` with `ref = documentId` and
`refType = "document"`. Both fields already exist on `CaseRequirementView` and already round-trip;
`SatisfyRequest`'s own doc comment names sub-projects 3–5 as the ones that fill them.

### 3.3 Relationship to `CaseEngine`

**No new caller of `CaseEngine.reconcile`.** Every runtime mutation against a case continues to go
through `RequirementService.satisfy`, which already reconciles under `CaseRepository.lockById`'s
row lock. This is invariant 4 of sub-project 2 and invariant 2 of sub-project 3, and it is the one
most easily broken by a new module that "just needs to update progress".

---

## 4. Data model

Every table carries `tenant_id`, gets its RLS policy and `FORCE ROW LEVEL SECURITY` in the same
migration that creates it, and receives no `GRANT DELETE`.

### 4.1 `document`

| Column | Notes |
|---|---|
| `id` | UUIDv7 |
| `tenant_id` | |
| `case_id` | **NOT NULL.** PRD §10: documents are scoped per journey, not per account. |
| `customer_id` | Denormalised from the case. Safe: a case never changes customer. Serves the `docs` screen's Customer column and the portal audience predicate without a join. |
| `name` | |
| `category` | Enum, the PRD's ten: `CONTRACT`, `AGREEMENT`, `NDA`, `COMPANY_REGISTRATION`, `TAX`, `KYC`, `TECHNICAL`, `CERTIFICATE`, `INVOICE`, `OTHER` |
| `visibility_tier` | `COMPANY_SHARED` / `CONTACT_ONLY` / `SENSITIVE` |
| `target_department_id` | Nullable. Internal-staff targeting (Q9 amendment). |
| `target_contact_label` | Nullable. Customer-side targeting (Q9 amendment). |
| `owner_contact_id` | Nullable. Whose document a `CONTACT_ONLY` one is. |
| `expires_at` | Nullable `timestamptz`. |
| `status` | `ACTIVE` / `RETIRED`. Never deleted. |
| `current_version_id` | |
| `uploaded_by`, `created_at` | |

### 4.2 `document_version` — immutable

`document_id`, `version_no`, `storage_key`, `size_bytes`, `content_type`, `uploaded_by`,
`uploaded_at`, plus the review outcome: `review_status` (`PENDING` / `APPROVED` / `REJECTED`),
`reviewed_by`, `reviewed_at`, `review_note`.

**Unique constraint on `(document_id, version_no)`.** Two clients racing a new version resolve as
a 409 rather than needing a row lock — the constraint is the truth, not a convention. This is
deliberately a different mechanism from `CaseRepository.lockById`, which exists because reconcile
recomputes derived state; appending a version derives nothing.

**Review lives here rather than in a `document_review` table.** History comes from `audit_event`,
which is append-only at the database layer and is already what PRD §11 calls the official history.
A parallel review-history table would be a second, weaker record of the same facts.

### 4.3 `document_share` — authored principal shares

`document_id`, `principal_type` (`CONTACT` / `USER` / `DEPARTMENT`), `principal_id`, `granted_by`,
`granted_at`. This is the "restricted until explicitly shared" mechanism of Q9's sensitive tier,
and it widens past the tier for the principal named.

Authored, not derived — the same shape as `case_participant`. §6.5 explains why that distinction
governs the whole audience model.

### 4.4 `document_case_link` — authored cross-journey shares

`document_id`, `case_id`, `linked_by`, `linked_at`. PRD §10: "Sharing a file across two journeys
of the same account is an explicit action." The document's home stays `document.case_id`; a link
row makes it *additionally* visible in another case.

Kept separate from `document_share` on purpose. One answers *who*, the other answers *where*;
merging them behind a discriminator would produce a table whose rows mean two different things.

### 4.5 `document_request`

`case_id`, `requirement_id` (nullable — null is ad-hoc, non-null is requirement-instantiated,
exactly `task`'s shape), `requested_of_contact_id`, `category`, `description`, `due_at`,
`requires_review` (§5.3), `status` (`OPEN` / `FULFILLED` / `WITHDRAWN`), `fulfilled_document_id`,
`requested_by`, `requested_at`.

### 4.6 `customer_contact.label`

A new nullable column on an existing `customer` table — Q9's amendment ("Finance", "Legal", "IT",
set by internal staff).

### 4.7 `requirement_definition.requires_review`

A new nullable boolean in `workflow`, defaulting `false` when null. See §5.3 for why it exists and
why authoring it in the builder is out of scope.

§4.6 and §4.7 are the only two changes this sub-project makes outside its own module, and both are
additive nullable columns on existing tables.

---

## 5. Lifecycle

### 5.1 Upload

A new document, or a new version of one. Blob first, row second (§7.4). The row's
`visibility_tier` comes from the uploader: internal staff choose freely; a portal contact chooses
from the three options SCREENS §17 names, which map to `CONTACT_ONLY`, `COMPANY_SHARED` and
`SENSITIVE` respectively.

A new version resets `review_status` to `PENDING`. Approving v1 says nothing about v2.

### 5.2 Request

A milestone activating with a `DOCUMENT` requirement instantiates a `document_request` bound to
`requirement_id`. The case header's "Request document" creates one with a null `requirement_id`.

A `WITHDRAWN` request never satisfies its requirement — the same rule as sub-project 3's "a
cancelled task never satisfies or waives its requirement", and for the same reason.

### 5.3 Satisfaction

`document_request.requires_review` decides when the requirement goes green:

- **`false`** — uploading against the request satisfies it immediately.
- **`true`** — the requirement satisfies only when a reviewer approves the version.

A requirement-instantiated request inherits its default from the requirement definition; an ad-hoc
request takes it from the requester. This is one mechanism covering both a KYC document that must
be judged and a casual attachment that must not block a journey on review staffing.

**This needs one column outside the module**: `requirement_definition.requires_review`, nullable
boolean, in `workflow` (§4.7). It is a new column on a definition table, not an edit to a frozen
row — published versions are immutable as *rows*, and a nullable addition leaves every existing
row reading `null`, which resolves to `false`. Authoring it in the builder is **out of scope**;
the column is set through the definition `PUT`, the same route by which workflow attributes and
stage entry conditions are authored today. That is a known builder gap CLAUDE.md already carries
twice, and this spec adds a third field to it rather than pretending otherwise.

Rejecting a version of a document that already satisfied a requirement reopens that requirement,
through the same gated path (§5.5).

### 5.4 Review

A holder of `document.review` approves or rejects a version with a note. Both are audited. The
cross-case pending-review read is an ordinary `AuthorizedQuery` listing filtered to
`review_status = PENDING`; it needs no carve-out.

### 5.5 Retirement — what deactivation revokes

CLAUDE.md makes this a required design question for any new deactivatable entity, asked before the
setter is written rather than after. Retiring a document:

1. **Revokes every `document_share` row** for it. A share is access; ending the record ends the
   access.
2. **Revokes every `document_case_link` row** for it.
3. **Reopens any requirement it satisfied**, through `RequirementService.satisfy`'s gated
   counterpart rather than by writing requirement state directly.
4. **Leaves the bytes.** Business records are never deleted (§7.1).

Point 3 is the mirror image of sub-project 3's task rule, not a contradiction of it. A task is
cancelled *before* it satisfies, so cancellation must be prevented from satisfying. A document is
realistically retired *after* it satisfied — the wrong file was uploaded. Leaving the requirement
green behind a retired document is a silent false positive, and a milestone showing complete on a
document nobody can open is precisely the kind of derived-state lie the engine exists to prevent.

---

## 6. Authorization

This is the substantial part of the sub-project.

### 6.1 The problem

Today's authorization answers *"may this actor touch records of this type, and which ones, by
scope."* Q9 asks *"for this particular record, is this actor in its audience."* Two things break
if the second is answered with the first:

**`ALL` short-circuits the descriptor.** `AuthorizationPredicateBuilder.forPermission` returns
`cb.conjunction()` the moment the actor's scope set contains `ALL`, before a descriptor is ever
consulted. An audience predicate placed in a descriptor is skipped by exactly the actor it most
needs to bind.

**Portal actors hold nothing.** `RoleService.assignRole` refuses `UserType.PORTAL` outright —
"Portal users cannot hold internal roles" — so `scopesFor` is empty, `forPermission` returns
`cb.disjunction()`, and a customer contact who activates and logs in reads zero rows. The link
exists (`customer_contact.user_id` → the PORTAL `app_user`), but there is no authority behind it.

### 6.2 The mechanism: an opt-in `AudienceFilter`

A new interface in `authz`, collected by Spring exactly as descriptors are:

```java
public interface AudienceFilter<T> {
    Class<T> entityType();
    Specification<T> audience(AuthContext ctx, String permissionKey);
}
```

`forPermission` becomes:

```
scopes.isEmpty()  → disjunction()                          (unchanged; returns first)
scopePredicate    = scopes.contains(ALL) ? conjunction() : union(scopes)
return scopePredicate.and(
    audiences.forEntity(type).map(f -> f.audience(ctx, key)).orElse(conjunction()) )
```

**The filter is keyed on the permission, not only the entity type**, and that is load-bearing
rather than incidental. §6.4 lets a `document.manage` holder retarget a document they may not
read; if the filter were keyed on entity alone it would apply to the read that `PATCH` performs to
load the row, and the administrator could never load the document they are permitted to fix. So
`DocumentAudienceFilter` returns the real predicate for `document.view` and `conjunction()` for
`document.manage`. The metadata/payload split of §6.4 is expressed in exactly one place, and it is
visible there rather than implied by which endpoint happens to be called.

A `document.manage` holder can of course retarget a document to a department that includes them
and then read it. That is §6.4's intended path, not a hole: it is an audited write with an
accountable actor, which is precisely what a silent break-glass read would not be.

**Why a separate interface rather than a method on `ResourceAuthorizationDescriptor`.**
`DescriptorRegistry.forEntity` *throws* on a miss, and today's `ALL` branch returns before ever
calling it. A default method on the descriptor would make that lookup unconditional, turning every
currently-working ALL-scoped read of a descriptor-less entity into an `IllegalStateException` —
a regression surface across four modules, for no gain. A separate registry returning
`Optional.empty()` contributes nothing where nothing is declared.

Three further properties follow, and each is worth the separate interface on its own:

- The nineteen existing descriptors are untouched.
- "Unchanged where no filter is declared" becomes provable rather than argued.
- The complete list of audience-governed types is one grep for `implements AudienceFilter`,
  not nineteen default-method overrides to read past. CLAUDE.md's standing complaint about
  name-shaped and enumeration-shaped guards is that the reviewer cannot see what is exempt; an
  opt-in interface inverts that.

**Proven red.** A temporary `AudienceFilter` returning `disjunction()` must make an **ALL-scoped**
read return zero rows. A test that passes without the change proves nothing.

### 6.3 `DocumentAudienceFilter`

Re-reading Q9 closely: the three tiers govern the **customer** side — "each contact sees only
their own", "company level attachments: visible to all in company", "sensitive documents:
restricted even within the company unless explicitly shared". They are not internal-staff
restrictions, and the design handoff confirms it by placing sensitive documents in the operator
table behind a lock glyph rather than hiding them. Internal access is governed by permission and
record scope, as everywhere else in the platform. The amendment's two targeting axes split along
the same line.

**Internal actor:**

```
(target_department_id IS NULL OR target_department_id = ctx.departmentId)
OR EXISTS a document_share row naming this user or their department
```

**Portal actor** (contact C, at customer X) — note the parenthesisation; the explicit-share
disjunct sits outside the whole tier-and-targeting test, not inside it:

```
(
  customer_id = X
  AND (
        visibility_tier = COMPANY_SHARED  AND C is an ACTIVE contact at X
     OR visibility_tier = CONTACT_ONLY    AND owner_contact_id = C
     -- SENSITIVE reaches nobody by tier
  )
  AND (target_contact_label IS NULL OR target_contact_label = C.label)
)
OR EXISTS a document_share row naming C          -- widens past all of the above
```

The explicit-share disjunct sitting outside the tier test *is* Q9's "until explicitly shared".

**Cross-journey visibility is not an audience question** and stays out of this filter. "Which
journey does this appear in" is an ordinary `extra` Specification on the listing —
`case_id = X OR EXISTS a document_case_link row for X`. Keeping the two apart is what stops the
audience filter from becoming a general-purpose where-clause that nobody can reason about.

### 6.4 Targeting binds everyone, including `ALL`

A document targeted at Legal is invisible to a Finance user **and to the tenant Administrator**.
This makes department targeting a real confidentiality boundary — privileged legal advice, salary
data — rather than a convenience filter, and it is the straight reading of "narrows within a tier
and never widens one".

It has a consequence that must be designed for, not discovered: a document targeted at a
department that later empties out would be invisible to every internal actor, permanently, with no
path back.

**Resolution: metadata and payload separate.** A holder of `document.manage` at `ALL` may list and
**retarget** a document — its name, category and current target — without being able to download
it. The audience filter binds the bytes; it does not bind the administrative handle. This concedes
nothing real, because a document's existence is already discoverable by an administrator through
`audit_event`, and it is the difference between a strict rule and an unrecoverable state.

There is **no break-glass reveal**. An administrator can move a document to a department that can
read it, which is an audited act with an accountable actor, rather than silently reading it
themselves.

### 6.5 Derived audience stays live; authored audience becomes rows

A rule that governs the whole model:

- **Derived** — tier, department target, contact-label target, contact status, case-team
  membership. These are evaluated **in the predicate, every request**, against current data. A
  relabelled contact takes effect on the next call. This honours the standing invariant that
  permissions are never cached across requests, and it is why there is no materialised
  `document_audience` table anywhere in §4.
- **Authored** — explicit principal shares and explicit cross-journey links. These are facts
  somebody asserted, exactly like `case_participant` rows, so they are rows.

Materialising the derived half would be faster and would be a staleness bug generator: every
contact added, relabelled or deactivated, and every case-team change, would need to invalidate it
correctly. The predicate is the cheaper correctness.

### 6.6 Portal authority

A portal actor's permissions are **derived from the contact record, in code, and never stored as a
grant**. `AuthorizationService` resolves a fixed set for any `ACTIVE` `PORTAL` user linked to an
`ACTIVE` `customer_contact`. The sponsor gets a small additive set (Q20's programme read, Q22's
plan approval).

Why not portal role templates: opening role assignment to external users reopens the escalation
shape sub-project 1 fought three separate times, and a single mis-seeded template becomes a
cross-company leak. `RoleService`'s `PORTAL` refusal stays exactly as it is — this design requires
no change to it, which is the point.

Why not a fifth `Scope` value: a portal actor resolves at `ALL`, and §6.2's audience filter —
already unbypassable by `ALL` — does every bit of the narrowing. No descriptor learns a new scope,
and the mechanism gets used rather than duplicated.

**The one risk this creates, stated plainly:** `document.view @ ALL` for an external user reads
alarmingly in any review, and would be genuinely dangerous if the audience filter were ever
removed, bypassed, or not applied to a new read path. Mitigations, all required:

1. It is never a `user_role` row. It exists only as a code-resolved set, so no administrator can
   create it and no database edit can widen it.
2. `PortalVisibilityTest` proves a contact reads nothing they should not — including the direct
   negative that contact A cannot read contact B's `CONTACT_ONLY` document at the same customer.
3. A dedicated test asserts that a `PORTAL` actor's resolved permission set is exactly the
   expected constant, so widening it is a deliberate edit with a failing test attached.

### 6.7 Permissions

| Key | Resource type | Scopes | Seeded to |
|---|---|---|---|
| `document.view` | `document` | ALL/DEPT/TEAM/ASSIGNED | Every delivery role |
| `document.upload` | `document` | ALL/DEPT/TEAM/ASSIGNED | Every delivery role |
| `document.manage` | `document` | ALL/DEPT/TEAM | Account Manager, Project Manager, Operations, Administrator |
| `document.review` | `document` | ALL/DEPT/TEAM | **Legal, Finance, Compliance**, Administrator |
| `document.share` | `document` | ALL/DEPT/TEAM | Account Manager, Project Manager, Administrator |
| `document.request` | `document` | ALL/DEPT/TEAM | Account Manager, Project Manager, Operations, Administrator |

**Seeding is a deliberate act here, not a default.** CLAUDE.md records the same finding twice —
`approval.decide` seeded to Administrator only, then `task.manage` seeded to Administrator only,
both flagged as needing a role review before anything builds on them. A third occurrence would be
a pattern rather than an accident. Every row above states its reasoning in the implementation
plan, and `document.review` in particular goes to the three roles whose names appear in Q9's own
department list.

`DescriptorRegistry.validate()` requires a `DocumentDescriptor` in `scoping/` for the record-scoped
keys above. Note the registry validates by *resource type*; `document_version`, `document_request`,
`document_share` and `document_case_link` are each read through `AuthorizedQuery` by entity type
and therefore need their own descriptors even though no permission names them — the same trap
`CaseParticipantDescriptor` and `CaseAttributeValueDescriptor` were added to close, which
`validate()` alone will not catch. Five descriptors, one validated requirement: expect
`validate()` to stay green while a missing one fails at the first request that reads that entity.

### 6.8 The `AuthorizationCoverageTest` finder rule — already rebound

**This sub-project inherits a working rule and must not re-do the rebind.** CLAUDE.md's
sub-project 3 close prescribed binding `servicesDoNotCallRepositoryFindersDirectly` to
repository *injection* rather than a `*Service`/`*Directory` name suffix. **Sub-project 3A Task 2
did it**, and the result is on `main`: `injectsARepository()` selects the covered classes, a
`FINDER_RULE_EXCLUSIONS` list of fully-qualified names carries every exemption visibly, and
`finderRuleBindsToRepositoryInjectionNotClassName` asserts the list's contents so a future
regression to name-shaping fails. Verified in the code, not taken from the plan.

The rebind also went further than the sub-project 3 ledger anticipated: it surfaced four more
classes the name-shaped rule was blind to, and `TaskDirectoryAdapter` was **fixed** rather than
excluded — it no longer calls a finder outside `AuthorizedQuery`, so it carries no exclusion at all.

What this sub-project owes the rule is therefore ordinary compliance, not reform:

- Add `document..` to the rule's covered packages **in the same commit that adds the services**.
- Every `document` class that injects a repository is covered automatically, whatever it is named.
  There is no suffix to fall outside of any more.
- **Add no new exclusion.** If a `document` class seems to need one, the design is wrong — the
  adapter precedent is that the class gets fixed to read through `AuthorizedQuery`.

---

## 7. Storage

### 7.1 The port has no `delete`

```java
public interface BlobStore {
    String put(InputStream content, long sizeBytes, String contentType);
    InputStream open(String storageKey);
    boolean exists(String storageKey);
}
```

The absence of `delete` is the design, not an omission. Nothing in this sub-project needs one, and
its absence states "business records are never deleted" as clearly as the unGRANTed DELETE states
it at the database. Retiring a document keeps its bytes. A delete path belongs with Q11's
retention rules, designed alongside them — not added now as an unused method someone later finds
convenient.

### 7.2 Opaque keys

Storage keys are generated, never derived from user input. No filename, no document name, no
tenant-supplied string reaches a filesystem path or an object name. Keys are prefixed by tenant id
for operability, not for isolation — isolation is the database row, since RLS does not reach blobs.

### 7.3 Delivery streams through the application

**Never a presigned URL.** Three reasons, the first decisive:

1. The local adapter **cannot** presign. Presigning would make the two adapters behaviourally
   different and the port would be leaky from day one.
2. A presigned URL outlives a revoked share. The bytes stay fetchable after the `document_share`
   row is gone — which would make §5.5's revocation a lie.
3. PRD §10's "a user must never receive a document they may not see" is only literally true if
   every byte transits the gate.

The cost is application bandwidth on download. That is a real sub-project 10 concern and is noted
there rather than treated as free.

### 7.4 Write ordering

Blob first, row second. A failed commit leaves an orphaned blob — invisible and harmless. The
reverse leaves a row pointing at nothing, which is a visible, broken document. No sweeper in this
sub-project (§2.2).

### 7.5 Two adapters, one contract suite

`LocalFsBlobStore` (configurable root) and `S3BlobStore` (AWS SDK v2), selected by
`app.storage.kind`. One contract test suite runs against **both**, with **MinIO as a
Testcontainer** for the S3 side — exercised for real, not mocked, the same choice that makes the
existing `postgres:16-alpine` suite trustworthy. An S3-compatible adapter also covers MinIO, R2
and similar without further work.

A port with one implementation is just an interface. Two are what keep it honest.

### 7.6 Hardening

See §2.3. Opaque keys and stream-through-the-app are settled here. The rest is an open decision
this spec's reader must resolve.

---

## 8. API surface

All under `/api/t/{slug}/`.

| Method | Path | Permission |
|---|---|---|
| `GET` | `/documents` | `document.view` — the operator index, scope + audience filtered |
| `GET` | `/cases/{caseId}/documents` | `document.view`, `extra` spec for home-or-linked |
| `POST` | `/cases/{caseId}/documents` | `document.upload` |
| `GET` | `/documents/{id}` | `document.view` |
| `PATCH` | `/documents/{id}` | `document.manage` — rename, recategorise, retarget (§6.4) |
| `POST` | `/documents/{id}/versions` | `document.upload` |
| `GET` | `/documents/{id}/versions/{n}/content` | `document.view` — streams |
| `POST` | `/documents/{id}/versions/{n}/review` | `document.review` |
| `POST` | `/documents/{id}/retire` | `document.manage` |
| `POST` | `/documents/{id}/shares` · `DELETE` …`/{shareId}` | `document.share` |
| `POST` | `/documents/{id}/links` · `DELETE` …`/{caseId}` | `document.share` |
| `POST` | `/cases/{caseId}/document-requests` | `document.request` |
| `POST` | `/document-requests/{id}/withdraw` | `document.request` |
| `GET` | `/portal/documents` · `POST` `/portal/cases/{caseId}/documents` | portal-derived |

Every id taken from a URL or body is resolved through `AuthorizedQuery` **before** it is written —
the write-half rule CLAUDE.md records as the one that keeps escaping, responsible for three
separate sub-project 1 escalations. `PATCH` is used rather than `PUT` for document metadata
specifically to avoid the full-replace trap; any `PUT` added later must carry every field its
request type accepts in the matching view type.

Out-of-scope and cross-tenant ids are 404, never 403.

---

## 9. Screens

`frontend-design` and `ui-ux-pro-max` are invoked before any frontend task, per CLAUDE.md, and the
work implements `docs/uispecs_latest/design_handoff_onboarding_platform/` rather than inventing a
visual language.

**`docs` — the operator index (SCREENS §7).** Five scope filters, the
`34px 1.6fr 1fr .9fr 1fr` table, the visibility cell stacking a Chip over a mono scope label, a
lock glyph before sensitive filenames, and the right aside explaining the three tiers.

**Case workspace Documents tab (SCREENS §6).** Rows with the file-type tile, wired to the
home-or-linked listing.

**The `08 VISIBLE · 61 HIDDEN BY SCOPE` counter is a deliberate, documented exception.** The design
calls this line important — it exists so a scoped view does not read as an empty one. But a count
of records the actor cannot see is an aggregate disclosure, the same shape CLAUDE.md already
records as a problem for `taskSummary`. It ships, **bounded to the current filter context**, and
carries its own written argument in the code — not a copy of the audit-timeline carve-out's
exclusion. CLAUDE.md is explicit that the timeline read is the codebase's only such exception and
that a second one needs its own argument rather than a copied precedent. This is that argument.

No portal UI (§2.2).

---

## 10. Invariant cross-check — sub-project 4's own ten

A change breaking one of these is a change to the design, not an implementation detail. Each is to
be verified against the code at close, not asserted.

1. `journey` never imports a `document` type; the dependency is one-way and there is no port back.
2. Documents add **no new caller of `CaseEngine.reconcile`** — satisfaction goes through the
   existing gated `RequirementService.satisfy`.
3. A retired document satisfies nothing: its shares and links are revoked and any requirement it
   satisfied is reopened.
4. A withdrawn request never satisfies its requirement.
5. **The audience filter binds `ALL`.** An ALL-scoped `document.view` holder is refused a
   targeted document's content. `document.manage` is the one permission the filter deliberately
   does not narrow (§6.2), and it grants metadata only — never bytes.
6. A portal actor's permission set is a code constant, never a `user_role` row.
7. Derived audience is never materialised — no table caches who may see a document.
8. Every byte transits the gate: no presigned URL, no direct blob access, on either adapter.
9. `document_version` rows are immutable and append-only; a new version never edits an old one.
10. Out-of-scope and cross-tenant ids are 404; `PATCH`/view types stay field-for-field aligned.

---

## 11. Inherited state

### 11.1 Carried in, untouched by this sub-project's path

TEAM-scoped user creation (`CreateUserRequest` still has no `teamIds`) and the workflow builder's
missing attribute/entry-condition UI — to which §5.3 adds a third unauthorable field.

**Sub-project 3's task gaps are closed, not carried.** 3A's Phase 1 closed all of them —
`taskSummary` is scope-filtered and rendered, "Do now" is sorted, the task-edit UI and assignee
picker exist, and the missing `task.*` audit actions are recorded. CLAUDE.md's sub-project 3
section still reads as though they are open; a reader of this spec should trust the code over that
section, and anyone editing CLAUDE.md next should prune it.

### 11.2 Touched deliberately

The audit-timeline read is currently the codebase's **only** `AuthorizedQuery` carve-out. §9's
hidden-count is the second exception in the codebase's history and is written as such, with its own
argument, per CLAUDE.md's explicit requirement.

The `AuthorizationCoverageTest` finder-rule rebind (§6.8) closes three `task` evasions and one
no-op `customer` exclusion.

### 11.3 Process note carried forward

3A's close records that `npx vitest run` proves no `tsc` type-check or lint pass, and that a hard
compile error sat undetected across several tasks because of it. Every frontend task in this
sub-project runs `tsc --noEmit` and `next lint` as part of its own verification, not at the
eventual e2e run.

---

## 12. Question mapping

| Source | Where it lands |
|---|---|
| PRD §10 categories, versions, expiry, approval status | §4.1, §4.2, §5.4 |
| PRD §10 per-journey scoping, explicit cross-journey share | §4.1 `case_id`, §4.4 |
| PRD §10 "enforced server-side… must never receive" | §6.2, §7.3 |
| PRD §18 local + S3, configurable | §7.5 |
| QA Q9 three tiers | §6.3 |
| QA Q9 amendment — department and contact-label targeting | §4.1, §6.3, §6.4, §4.6 |
| QA Q9 customer write access | §5.1, §6.6 |
| QA Q11 retention and erasure | **Out** — §2.2 |
| `RequirementKind.DOCUMENT` (sub-project 2) | §3.2, §5.2, §5.3 |
| `task.attachment_ref` seam (sub-project 3) | Populated by callers; no schema change |
| SCREENS §7 `docs`, §6 Documents tab | §9 |
| SCREENS §17 `cdocs` | **Out** — sub-project 7; §6.6 makes it possible |
