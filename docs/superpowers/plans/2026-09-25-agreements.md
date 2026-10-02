# Agreements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build sub-project 5 — an `agreement` module with Q13's three record modes, append-only submitted versions carrying a canonical content hash, a mandatory four-eyes review, staff-recorded signatures behind a `SignatureProvider` interface, cancel-and-replace, derived expiry, and the new `RequirementKind.SIGNATURE` wired through case creation and migration.

**Architecture:** One new backend module, `co.ara.onboarding.agreement`, depending one-way on `journey` (for `RequirementService.satisfy`, `StageWriteScopeGuard` and a new `AgreementLifecycle` port it implements) and on `document` (through one narrow `document.AgreementFiles` facade). Nothing imports `agreement`. Authorization reuses sub-project 4's seams unchanged: five new descriptors in `scoping`, and an `AgreementAudienceFilter` that hides pre-`SENT` agreements from portal contacts even at `Scope.ALL`. The frontend adds the case Agreements tab, the operator `agreements` lifecycle screen, the builder's `SIGNATURE` fields and a roadmap chip link.

**Tech Stack:** Java 21, Spring Boot 3.4, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, Hibernate/JPA, Jackson, JUnit 5, Testcontainers, ArchUnit, Next.js 15 (App Router), TypeScript strict, Tailwind, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-25-agreements-design.md` — read it before any task. Section numbers below (§5.6, §6.4 …) refer to it.

**Design system:** `docs/uispecs_latest/design_handoff_onboarding_platform/` — `SCREENS.md` "Other tabs" (Agreements row shape) and §8 `agreements`, `DOMAIN_RULES.md` §Q13/§Q14. Do **not** read `docs/uispecs_legacy/`. **Invoke the `frontend-design` and `ui-ux-pro-max` skills before starting any frontend task** (Phase 5), per CLAUDE.md.

---

## Global Constraints

Every task's requirements implicitly include this section. `CLAUDE.md` is authoritative for everything sub-projects 1–4 established; this carries only what is new or newly binding.

- **Base package** `co.ara.onboarding`. One new module: `co.ara.onboarding.agreement`. All five new descriptors and `AgreementAudienceFilter` go in `co.ara.onboarding.scoping`. The one new `journey` type is the `AgreementLifecycle` port. The one new `document` type is `AgreementFiles` (plus the package-private `DocumentContentWriter` Task 8 extracts).
- **`journey` and `document` must never import `agreement`.** Enforced by two **named** `ModuleBoundaryTest` rules — `noJourneyDependencyOnAgreement`, `noDocumentDependencyOnAgreement` — each **seen red** before it is trusted (Task 4).
- **Migration numbers are decided at dispatch time.** `V23` is highest as this plan is written. Before writing a migration, list `backend/src/main/resources/db/migration/` and use the next unused `V<n>`. Forward-only — never edit a committed migration.
- **Five new tables**, all tenant-owned: `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `SELECT enable_tenant_rls('<table>')` in the same migration. `agreement` and `agreement_signatory` get `GRANT SELECT, INSERT, UPDATE`. **`agreement_version`, `agreement_version_review` and `agreement_signature` get `GRANT SELECT, INSERT` only** — the `plan_revision_item`/`audit_event` shape. No table gets `DELETE`, **with one exception**: `agreement_signatory` gets `GRANT DELETE` with a comment, because `PUT …/signatories` replaces a `DRAFT` agreement's list and a signatory row is not a business record (the frozen copy lives in `agreement_version.structured_snapshot`). `RlsCoverageTest`'s allowlist stays at four entries.
- **UUIDv7 keys** via `Uuid7.generate()`. Timestamps `timestamptz` UTC. `effective_date`, `expires_at`, `renewal_date` and `signed_on` are SQL `date` / Java `LocalDate` (spec §4.2, §4.6) — a contract's expiry is a calendar day, not an instant. "Today" is always `LocalDate.now(clock)` with the injected UTC `Clock`, **never** the zero-arg `LocalDate.now()` (3A Task 1's timezone defect).
- **Every public `*Service` method carries `@RequirePermission`.** `AgreementFiles` methods carry it too even though the rule does not bind on that name.
- **Every read of tenant business data, and every id a write path takes from a URL or body, goes through `AuthorizedQuery` before it writes.** `agreement..` joins `AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly`'s covered packages **in the same commit that adds the first `agreement` service** (Task 11). Exactly **one** new `FINDER_RULE_EXCLUSIONS` entry is permitted: `co.ara.onboarding.agreement.AgreementInstantiation`, for the identical reason `DocumentInstantiation` has one (it runs inside `CaseService.create`/`MigrationService` on a case id the caller just authorized and controls). Any other exclusion means the design is wrong — stop and escalate.
- **No new caller of `CaseEngine.reconcile`.** Satisfaction goes through `journey.RequirementService.satisfy(requirementId, agreementId, "AGREEMENT")`. **`CaseEngine` is not modified.**
- **`RequirementService.satisfy` never runs on a requirement that is not `OPEN`.** `satisfy` is idempotent only for `SATISFIED`; on a `WAIVED` requirement it would overwrite the waiver. The agreement code reads the requirement's status first (spec §5.6). This is load-bearing.
- **`agreement.sign_record` implies `milestone.complete` at an equal-or-broader scope** (spec §6.3 amendment). The final signature calls `satisfy`, gated `milestone.complete`.
- **Out-of-scope and cross-tenant ids are 404, never 403.** An illegal state transition is `IllegalStateException` → 409. A bad argument is `IllegalArgumentException` → 400. Both are already mapped globally.
- **`PATCH` for agreement fields; `PUT` only for the signatory list**, whose detail view carries every field `ReplaceSignatoriesRequest` accepts.
- **Permission keys** declared in `PermissionKeys`, catalogued in `PermissionCatalog`, referenced as constants.
- **Audit: cause before effect.** `agreement.signed` is recorded **before** `satisfy` runs. `journey.CauseBeforeEffectTest` gains the subsequence (Task 18).
- **TDD.** Failing test first; security tests before the mechanism.
- **Never assert an exception inside a `fixture.runAs(...)` lambda** — wrap the helper call: `assertThatThrownBy(() -> fixture.runAsUser(tenant, u, () -> service.x(...)))`.
- **Fixture create-helpers run inside `runAs`.**
- **Backend:** `.\gradlew.bat cleanTest test` (PowerShell) — never a bare `test`. Docker must be running. Run `java -version` **and** `javac -version` at the start of every backend task (see memory: Application Control can block `javac.exe` alone).
- **Every frontend task runs `npx tsc --noEmit` and `npm run lint`** as well as `npx vitest run`.
- **API types are generated, never hand-written** — `.\gradlew.bat openApiSpec` then `npm run generate:api`.
- **OpenSign is not built.** `SignatureProvider` has exactly one implementation, `ManualSignatureProvider`. Do not add an OpenSign class, dependency, config key or stub. The UI eyebrow says `MANUAL SIGNING`.
- **Conventional Commits**, *why* in the body; end every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. When you find a plan defect, fix the code **and** amend this plan, and say so in the commit body.

## Review Focus

Inputs and conditions the spec implies but no happy-path test would meet — each has its pinning test in the owning task:

1. **The final signature on a case that is `ON_HOLD`** (a customer-template case awaiting first schedule approval). `satisfy` throws `CaseOnHoldException`; the whole signature must roll back — no `agreement_signature` row, agreement still `AWAITING_SIGNATURE` — not a half-signed agreement. Pinned in Task 16.
2. **A requirement waived while its agreement is mid-flight, then the agreement is signed.** The waiver must survive; `satisfy` must not be called. Pinned in Task 16.
3. **A hand-built role holding `agreement.sign_record` but not `milestone.complete`** records the last signature: refused, atomically, nothing written. Pinned in Task 16; the template-level implication is Task 5.
4. **Two reviewers approving the same version concurrently / a double-click on Send.** `lock_version` plus the unique `agreement_version_review(agreement_version_id)` make the second one a 409, never a duplicate review or a double transition. Pinned in Tasks 14 and 15.
5. **A draft whose signatory contact is retired between being added and submit**, and **a draft with zero signatories**. Submit refuses both with 400 and names the problem. Pinned in Task 13.

---

## File structure

```
backend/src/main/resources/db/migration/
  V<n>__signature_requirement.sql        Task 2 — requirement_definition columns
  V<n+1>__agreement.sql                  Task 3 — the five tables

backend/src/main/java/co/ara/onboarding/workflow/
  RequirementKind.java                   MODIFIED — + SIGNATURE
  AgreementRecordMode.java               NEW — FILE_BACKED | STRUCTURED_PLUS_FILE | STRUCTURED_ONLY
  RequirementDefinition.java             MODIFIED — agreementRecordMode, agreementName
  WorkflowDefinitionRequest.java         MODIFIED — RequirementRequest + 2 fields
  WorkflowDefinitionView.java            MODIFIED — RequirementView + 2 fields
  WorkflowService.java                   MODIFIED — newRequirement / toRequirementView / toRequirementRequest
  PublishService.java                    MODIFIED — Rule 6

backend/src/main/java/co/ara/onboarding/journey/
  AgreementLifecycle.java                NEW — the port
  CaseService.java                       MODIFIED — one call after documentRequestLifecycle
  MigrationService.java                  MODIFIED — one call after repin

backend/src/main/java/co/ara/onboarding/document/
  DocumentContentWriter.java             NEW (package-private @Component) — extracted from DocumentService
  DocumentService.java                   MODIFIED — delegates to DocumentContentWriter, behaviour unchanged
  AgreementFiles.java                    NEW — the facade

backend/src/main/java/co/ara/onboarding/agreement/
  Agreement.java  AgreementStatus.java  AgreementRepository.java
  AgreementSignatory.java  SignatoryKind.java  AgreementSignatoryRepository.java
  AgreementVersion.java  AgreementVersionRepository.java
  AgreementVersionReview.java  ReviewDecision.java  AgreementVersionReviewRepository.java
  AgreementSignature.java  AgreementSignatureRepository.java
  SignatureProviderKind.java  SignatureProvider.java  ManualSignatureProvider.java
  AgreementContentHasher.java            canonical JSON + SHA-256 (§4.4.1)
  AgreementInstantiation.java  AgreementLifecycleAdapter.java
  AgreementService.java                  reads, summary, draft edits, submit, send, cancel
  AgreementReviewService.java            four-eyes review
  AgreementSignatureService.java         record signature + satisfaction
  PortalAgreementService.java            portal reads
  AgreementController.java  PortalAgreementController.java  AgreementExceptionHandler.java
  SelfReviewException.java
  views/requests: AgreementView, AgreementSummaryView, AgreementDetailView, AgreementVersionView,
                  AgreementSignatoryView, AgreementSignatureView, PortalAgreementView,
                  PatchAgreementRequest, ReplaceSignatoriesRequest, SignatoryRequest,
                  ReviewAgreementRequest, RecordSignatureRequest, CancelAgreementRequest

backend/src/main/java/co/ara/onboarding/scoping/
  AgreementDescriptor.java  AgreementSignatoryDescriptor.java  AgreementVersionDescriptor.java
  AgreementVersionReviewDescriptor.java  AgreementSignatureDescriptor.java
  AgreementAudienceFilter.java

backend/src/main/java/co/ara/onboarding/authz/
  PermissionKeys.java  PermissionCatalog.java  RoleTemplates.java  PortalPermissions.java   MODIFIED
backend/src/main/java/co/ara/onboarding/audit/AuditActions.java                             MODIFIED

frontend/src/lib/api/agreements.ts (+ .test.tsx)
frontend/src/components/agreements/  AgreementRow, AgreementDetailPanel, DraftEditor, SignatoryEditor,
                                     VersionHistory, SignatureList, ReviewDialog, RecordSignatureDialog,
                                     CancelAgreementDialog, LifecycleCard, AgreementTable, statusChip.ts
frontend/src/components/journey/AgreementsTab.tsx                     replaces the EmptyState stub
frontend/src/app/(app)/t/[slug]/agreements/page.tsx                   the lifecycle screen
frontend/src/components/workflow/MilestoneEditor.tsx                  MODIFIED — SIGNATURE fields
frontend/src/components/journey/RequirementList.tsx                   MODIFIED — SIGNATURE chip link
frontend/src/components/shell/Sidebar.tsx                             MODIFIED — nav entry
frontend/e2e/agreements.spec.ts
```

---

## Phase 0 — Baseline

### Task 1: Establish a green baseline across all three suites

**Files:** none modified. Create `.superpowers/sdd/2026-09-25-agreements/task-1-report.md`.

- [ ] **Step 1: Create the worktree** off current `main` using `superpowers:using-git-worktrees` (branch `feat/agreements`). Never reuse `feat/documents`.
- [ ] **Step 2: Check the toolchain**

```powershell
java -version; javac -version; docker info --format '{{.ServerVersion}}'
```

Expected: all three print a version. If `javac` is blocked, stop and tell the user — do not proceed with backend tasks.

- [ ] **Step 3: Run the backend suite**

```powershell
cd backend; .\gradlew.bat cleanTest test
```

Expected: `BUILD SUCCESSFUL`. Record the test count from the summary.

- [ ] **Step 4: Run the frontend unit suite, type-check and lint**

```powershell
cd frontend; npm install; npx vitest run; npx tsc --noEmit; npm run lint
```

Expected: all pass.

- [ ] **Step 5: Run Playwright against a scratch database**

```powershell
cd frontend; $env:DB_URL = "jdbc:postgresql://localhost:5432/onboarding_scratch"; npx playwright test
```

Expected: all specs pass. Kill stray 8080/3000 processes first **only if they belong to this session**.

- [ ] **Step 6: Write the report** — counts, any failure and its ruling (a failure that is not caused by this branch is fixed with its own test before continuing, never skipped). Commit:

```bash
git add .superpowers/sdd/2026-09-25-agreements/task-1-report.md
git commit -m "chore: record the sub-project 5 baseline"
```

---

## Phase 1 — Schema, boundaries, permissions

### Task 2: `RequirementKind.SIGNATURE` and its definition fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V<n>__signature_requirement.sql`
- Create: `backend/src/main/java/co/ara/onboarding/workflow/AgreementRecordMode.java`
- Modify: `workflow/RequirementKind.java`, `RequirementDefinition.java`, `WorkflowDefinitionRequest.java`, `WorkflowDefinitionView.java`, `WorkflowService.java` (lines ~579–595 `newRequirement`, ~760 `toRequirementView`, ~815 `toRequirementRequest`), `PublishService.java` (`validate`)
- Modify (tests): `workflow/WorkflowFixtures.java` (every `new RequirementRequest(` — lines 56, 60, 65, 70, 135), `journey/CaseCreationTest.java:74`, `journey/TransitionTest.java:74`
- Test: `backend/src/test/java/co/ara/onboarding/workflow/SignatureRequirementPublishTest.java`

**Interfaces:**
- Produces: `RequirementKind.SIGNATURE`; `enum AgreementRecordMode { FILE_BACKED, STRUCTURED_PLUS_FILE, STRUCTURED_ONLY; public boolean includesFile() }`; `RequirementDefinition.getAgreementRecordMode(): AgreementRecordMode`, `getAgreementName(): String`; `RequirementRequest`/`RequirementView` gain trailing `AgreementRecordMode agreementRecordMode, String agreementName`; `WorkflowFixtures.signature(String label, AgreementRecordMode mode, String agreementName): RequirementRequest`.

- [ ] **Step 1: Write the failing test**

```java
package co.ara.onboarding.workflow;

import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.1: publish Rule 6 -- a SIGNATURE requirement carries both agreement fields; nothing else carries either. */
class SignatureRequirementPublishTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;

    @Test
    void aSignatureRequirementWithBothFieldsPublishesAndRoundTrips() {
        UUID tenant = fixture.createTenant("sig-pub-ok-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            UUID versionId = draftWith(signature("Signed MSA", AgreementRecordMode.FILE_BACKED, "Master Services Agreement"));
            publishService.publish(versionId);

            var req = workflows.getDefinition(versionId).stages().get(0).milestones().get(0).requirements().get(0);
            assertThat(req.kind()).isEqualTo(RequirementKind.SIGNATURE);
            assertThat(req.agreementRecordMode()).isEqualTo(AgreementRecordMode.FILE_BACKED);
            assertThat(req.agreementName()).isEqualTo("Master Services Agreement");
        });
    }

    @Test
    void aSignatureRequirementMissingItsRecordModeIsRefusedAtPublish() {
        UUID tenant = fixture.createTenant("sig-pub-nomode-" + Uuid7.generate());
        UUID versionId = fixture.runAsReturning(tenant, () -> draftWith(
                new WorkflowDefinitionRequest.RequirementRequest(RequirementKind.SIGNATURE, "Signed MSA", 1, true,
                        null, null, null, null, "MSA")));
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> publishService.publish(versionId)))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("record mode");
    }

    @Test
    void aSignatureRequirementMissingItsAgreementNameIsRefusedAtPublish() {
        UUID tenant = fixture.createTenant("sig-pub-noname-" + Uuid7.generate());
        UUID versionId = fixture.runAsReturning(tenant, () -> draftWith(
                new WorkflowDefinitionRequest.RequirementRequest(RequirementKind.SIGNATURE, "Signed MSA", 1, true,
                        null, null, null, AgreementRecordMode.STRUCTURED_ONLY, "  ")));
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> publishService.publish(versionId)))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("agreement name");
    }

    @Test
    void aNonSignatureRequirementCarryingAgreementFieldsIsRefusedAtPublish() {
        UUID tenant = fixture.createTenant("sig-pub-stray-" + Uuid7.generate());
        UUID versionId = fixture.runAsReturning(tenant, () -> draftWith(
                new WorkflowDefinitionRequest.RequirementRequest(RequirementKind.MANUAL, "Kick-off", 1, true,
                        null, null, null, AgreementRecordMode.FILE_BACKED, "MSA")));
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> publishService.publish(versionId)))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("only a SIGNATURE requirement");
    }

    private UUID draftWith(WorkflowDefinitionRequest.RequirementRequest requirement) {
        UUID templateId = workflows.createTemplate("Sig " + Uuid7.generate(), "").id();
        UUID versionId = workflows.createDraft(templateId);
        workflows.replaceDraft(versionId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Agreement", List.of(milestone("m1", "Contract", 2, List.of(), List.of(requirement))))),
                List.of(), 0L));
        return versionId;
    }
}
```

Check `PublishValidationException`'s message shape before relying on `hasMessageContaining` — if it carries a list of problems in a field rather than the message, assert on that field instead and amend this step.

- [ ] **Step 2: Run to verify it fails**

```powershell
.\gradlew.bat cleanTest test --tests "*SignatureRequirementPublishTest*"
```

Expected: compilation failure — `SIGNATURE`, `AgreementRecordMode`, `signature(...)` and the 9-arg `RequirementRequest` do not exist.

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 5, Task 2 (spec section 4.1): a SIGNATURE requirement fixes the record
-- mode (QA Q13's "template setting") and the default name of the agreement that
-- case creation instantiates for it. Typed nullable columns per kind, the
-- document_category / requires_review precedent -- never a params jsonb.
ALTER TABLE requirement_definition ADD COLUMN agreement_record_mode varchar(24);
ALTER TABLE requirement_definition ADD COLUMN agreement_name        varchar(200);
ALTER TABLE requirement_definition ADD CONSTRAINT requirement_definition_agreement_mode_ck
    CHECK (agreement_record_mode IS NULL
           OR agreement_record_mode IN ('FILE_BACKED','STRUCTURED_PLUS_FILE','STRUCTURED_ONLY'));
```

`requirement_definition.kind` is `varchar(16)` with no CHECK constraint (verified against `V12__workflow.sql:120`), so `SIGNATURE` (9 chars) needs no constraint change. Verify this is still true before writing the migration; if a later migration added a kind CHECK, extend it here.

- [ ] **Step 4: Implement**

`AgreementRecordMode.java`:

```java
package co.ara.onboarding.workflow;

/** QA Q13, spec section 4.1. Fixed per SIGNATURE requirement; an instantiated agreement copies it and never changes it. */
public enum AgreementRecordMode {
    FILE_BACKED, STRUCTURED_PLUS_FILE, STRUCTURED_ONLY;

    /** Whether an agreement in this mode must carry a file at submit, and a countersigned copy at its last signature. */
    public boolean includesFile() { return this != STRUCTURED_ONLY; }
}
```

`RequirementKind.java`: `public enum RequirementKind { TASK, DOCUMENT, APPROVAL, MANUAL, SIGNATURE }`.

`RequirementDefinition.java` — beside `requiresReview`:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_record_mode")
    private AgreementRecordMode agreementRecordMode;

    @Column(name = "agreement_name")
    private String agreementName;

    public AgreementRecordMode getAgreementRecordMode() { return agreementRecordMode; }
    public void setAgreementRecordMode(AgreementRecordMode m) { this.agreementRecordMode = m; }
    public String getAgreementName() { return agreementName; }
    public void setAgreementName(String agreementName) { this.agreementName = agreementName; }
```

`WorkflowDefinitionRequest.RequirementRequest` and `WorkflowDefinitionView.RequirementView` — append `AgreementRecordMode agreementRecordMode, String agreementName` as the last two components (PUT alignment: the view carries every field the request accepts). `WorkflowService.newRequirement` adds `requirement.setAgreementRecordMode(r.agreementRecordMode()); requirement.setAgreementName(r.agreementName());`; `toRequirementView` and `toRequirementRequest` pass both through.

`WorkflowFixtures` — append `, null, null` to every existing `new RequirementRequest(...)` (and `r.agreementRecordMode(), r.agreementName()` in `toRequirementRequest`), then add:

```java
    /** A requirement of kind SIGNATURE -- sub-project 5's instantiation seam. */
    public static RequirementRequest signature(String label, AgreementRecordMode mode, String agreementName) {
        return new RequirementRequest(RequirementKind.SIGNATURE, label, 1, true, null, null, null, mode, agreementName);
    }
```

Fix `CaseCreationTest.java:74` and `TransitionTest.java:74` the same way (`, null, null`).

`PublishService.validate` — read the version's requirement definitions with the existing `readByVersion(requirementDefinitions, RequirementDefinition.class, versionId, "ordinal")` helper (inject `RequirementDefinitionRepository` if the class does not already hold it) and add, before `return problems;`:

```java
        // Rule 6 (sub-project 5, spec section 4.1): a SIGNATURE requirement fixes its
        // agreement's record mode and name; no other kind may carry either, or a stray
        // value would sit frozen in a published version meaning nothing.
        for (RequirementDefinition r : requirements) {
            boolean agreementFields = r.getAgreementRecordMode() != null || r.getAgreementName() != null;
            if (r.getKind() == RequirementKind.SIGNATURE) {
                if (r.getAgreementRecordMode() == null) {
                    problems.add("SIGNATURE requirement '" + r.getLabel() + "' needs an agreement record mode");
                }
                if (r.getAgreementName() == null || r.getAgreementName().isBlank()) {
                    problems.add("SIGNATURE requirement '" + r.getLabel() + "' needs an agreement name");
                }
            } else if (agreementFields) {
                problems.add("Requirement '" + r.getLabel() + "' carries agreement fields, which only a SIGNATURE requirement may");
            }
        }
```

- [ ] **Step 5: Run the test and the whole workflow + journey packages**

```powershell
.\gradlew.bat cleanTest test --tests "co.ara.onboarding.workflow.*" --tests "co.ara.onboarding.journey.*"
```

Expected: PASS, including every pre-existing test (the fixture arity change touches many).

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat(workflow): add the SIGNATURE requirement kind and its agreement fields"
```

### Task 3: The agreement tables, entities and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V<n+1>__agreement.sql`
- Create: `agreement/Agreement.java`, `AgreementStatus.java`, `AgreementRepository.java`, `AgreementSignatory.java`, `SignatoryKind.java`, `AgreementSignatoryRepository.java`, `AgreementVersion.java`, `AgreementVersionRepository.java`, `AgreementVersionReview.java`, `ReviewDecision.java`, `AgreementVersionReviewRepository.java`, `AgreementSignature.java`, `AgreementSignatureRepository.java`, `SignatureProviderKind.java`
- Test: `backend/src/test/java/co/ara/onboarding/agreement/AgreementSchemaTest.java`

**Interfaces:**
- Consumes: `AgreementRecordMode` (Task 2), `Uuid7`.
- Produces: the five entities with the getters/setters shown; every repository extends `JpaRepository<T, UUID>, JpaSpecificationExecutor<T>` (`AuthorizedQuery` needs the latter — a missing one fails at the first scoped read, not at compile time). Discovery queries (Step 5) are deliberately **not** named `findBy*`, so the finder rule does not bind on them — the `RequirementRepository.satisfiedBy` precedent — and every caller feeds them only ids already resolved through `AuthorizedQuery`.

- [ ] **Step 1: Write the failing schema test**

`RlsCoverageTest` goes red on its own once the tables exist without policies; this test pins what it does not cover.

```java
package co.ara.onboarding.agreement;

/**
 * RlsCoverageTest proves tenant_id + RLS + FORCE on every new table. This covers the
 * domain rules the migration encodes: one live agreement per requirement, the CHECKs,
 * and -- run as onboarding_app, the role the application really connects as -- that the
 * three append-only tables refuse UPDATE and DELETE.
 */
class AgreementSchemaTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;     // created in this task, see Step 4
    @Autowired AgreementRepository agreements;
    @Autowired JdbcTemplate jdbc;               // connects as onboarding_app in tests (verify in PostgresTestBase)

    @Test
    void aSecondLiveAgreementForTheSameRequirementIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-live-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement first = support.draftAgreementRow(tenant);
            support.draftAgreementRowFor(tenant, first.getCaseId(), first.getRequirementId());
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_live_per_requirement_uq");
    }

    @Test
    void aCancelledAgreementDoesNotBlockItsReplacement() {
        UUID tenant = fixture.createTenant("agr-schema-repl-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            Agreement first = support.draftAgreementRow(tenant);
            first.setStatus(AgreementStatus.CANCELLED);
            first.setCancelReason("terms changed");
            agreements.saveAndFlush(first);
            Agreement second = support.draftAgreementRowFor(tenant, first.getCaseId(), first.getRequirementId());
            assertThat(second.getId()).isNotEqualTo(first.getId());
        });
    }

    @Test
    void aCancelledAgreementWithoutAReasonIsRejected() {
        UUID tenant = fixture.createTenant("agr-schema-reason-" + Uuid7.generate());
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            a.setStatus(AgreementStatus.CANCELLED);
            agreements.saveAndFlush(a);
        })).isInstanceOf(DataIntegrityViolationException.class)
           .hasMessageContaining("agreement_cancel_reason_ck");
    }

    @Test
    void aSignatoryWithBothAContactAndAUserIsRejected() { /* agreement_signatory_party_ck */ }

    @Test
    void aStructuredOnlyVersionCarryingAFileIsRejected() { /* agreement_version_file_ck */ }

    @Test
    void aFileIncludingVersionWithoutAFileIsRejected() { /* agreement_version_file_ck */ }

    @Test
    void agreementVersionRefusesUpdateAndDeleteAsTheApplicationRole() {
        // Insert one version via the repository inside runAs, then, still inside the
        // tenant binding, issue raw "UPDATE agreement_version SET content_sha256 = ..."
        // and "DELETE FROM agreement_version WHERE id = ?" through jdbc and assert each
        // fails with a permission-denied SQLException (SQLState 42501).
    }

    @Test
    void agreementVersionReviewRefusesUpdateAndDeleteAsTheApplicationRole() { /* same shape */ }

    @Test
    void agreementSignatureRefusesUpdateAndDeleteAsTheApplicationRole() { /* same shape */ }

    @Test
    void deletingAnAgreementIsDeniedAtTheDatabase() { /* no GRANT DELETE on agreement */ }
}
```

Fill in the four `/* … */` bodies in the same shape as the fully written tests above before running — each one builds the offending row inside `runAs` and asserts `DataIntegrityViolationException` naming the constraint, or SQLState `42501` for the grant cases. Check how `PostgresTestBase`/`V*` tests already assert a revoked grant (e.g. the audit-append-only test) and copy that mechanism rather than inventing one.

- [ ] **Step 2: Run to verify it fails**

```powershell
.\gradlew.bat cleanTest test --tests "*AgreementSchemaTest*"
```

Expected: compilation failure / missing tables.

- [ ] **Step 3: Write the migration**

```sql
-- Sub-project 5, Task 3 (spec section 4): the agreement module's five tables.
-- One live agreement per SIGNATURE requirement (cancel-and-replace keeps it so);
-- versions, reviews and signatures are append-only evidence.

CREATE TABLE agreement (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    case_id               uuid NOT NULL REFERENCES onboarding_case(id),
    requirement_id        uuid NOT NULL REFERENCES requirement(id),
    -- Denormalised from the case (a case never changes customer) so the portal
    -- audience predicate needs no join -- the document.customer_id precedent.
    customer_id           uuid NOT NULL REFERENCES customer(id),
    name                  varchar(200) NOT NULL,
    record_mode           varchar(24) NOT NULL,
    status                varchar(24) NOT NULL,
    effective_date        date,
    expires_at            date,
    renewal_date          date,
    notice_period_days    int,
    owner_user_id         uuid NOT NULL REFERENCES app_user(id),
    document_id           uuid REFERENCES document(id),
    last_edited_by        uuid NOT NULL REFERENCES app_user(id),
    replaces_agreement_id uuid REFERENCES agreement(id),
    cancel_reason         text,
    signed_at             timestamptz,
    signature_provider    varchar(16) NOT NULL,
    provider_envelope_id  text,
    lock_version          bigint NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT agreement_mode_ck CHECK (record_mode IN ('FILE_BACKED','STRUCTURED_PLUS_FILE','STRUCTURED_ONLY')),
    -- EXPIRED is deliberately absent: it is derived on read (spec section 5.7).
    CONSTRAINT agreement_status_ck CHECK (status IN
        ('DRAFT','UNDER_REVIEW','APPROVED','SENT','AWAITING_SIGNATURE','SIGNED','CANCELLED')),
    CONSTRAINT agreement_cancel_reason_ck CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CONSTRAINT agreement_signed_at_ck CHECK ((status = 'SIGNED') = (signed_at IS NOT NULL)),
    CONSTRAINT agreement_notice_ck CHECK (notice_period_days IS NULL OR notice_period_days >= 0),
    CONSTRAINT agreement_provider_ck CHECK (signature_provider IN ('MANUAL'))
);
CREATE UNIQUE INDEX agreement_live_per_requirement_uq ON agreement (requirement_id) WHERE status <> 'CANCELLED';
CREATE INDEX agreement_tenant_case_idx     ON agreement (tenant_id, case_id);
CREATE INDEX agreement_tenant_customer_idx ON agreement (tenant_id, customer_id, status);
CREATE INDEX agreement_tenant_expiry_idx   ON agreement (tenant_id, expires_at) WHERE status = 'SIGNED' AND expires_at IS NOT NULL;

CREATE TABLE agreement_signatory (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    agreement_id uuid NOT NULL REFERENCES agreement(id),
    kind         varchar(16) NOT NULL,
    contact_id   uuid REFERENCES customer_contact(id),
    user_id      uuid REFERENCES app_user(id),
    display_role varchar(120) NOT NULL,
    sort_order   int NOT NULL,
    CONSTRAINT agreement_signatory_kind_ck CHECK (kind IN ('CONTACT','INTERNAL')),
    CONSTRAINT agreement_signatory_party_ck CHECK (
        (kind = 'CONTACT'  AND contact_id IS NOT NULL AND user_id IS NULL) OR
        (kind = 'INTERNAL' AND user_id IS NOT NULL AND contact_id IS NULL))
);
CREATE UNIQUE INDEX agreement_signatory_contact_uq ON agreement_signatory (agreement_id, contact_id) WHERE contact_id IS NOT NULL;
CREATE UNIQUE INDEX agreement_signatory_user_uq    ON agreement_signatory (agreement_id, user_id)    WHERE user_id IS NOT NULL;

CREATE TABLE agreement_version (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    agreement_id        uuid NOT NULL REFERENCES agreement(id),
    version_number      int  NOT NULL,
    -- Copied so the file CHECK below needs no join and the row never needs an UPDATE.
    record_mode         varchar(24) NOT NULL,
    submitted_by        uuid NOT NULL REFERENCES app_user(id),
    submitted_at        timestamptz NOT NULL,
    last_edited_by      uuid NOT NULL REFERENCES app_user(id),
    structured_snapshot jsonb NOT NULL,
    document_version_id uuid REFERENCES document_version(id),
    document_sha256     char(64),
    content_sha256      char(64) NOT NULL,
    UNIQUE (agreement_id, version_number),
    CONSTRAINT agreement_version_file_ck CHECK (
        (record_mode = 'STRUCTURED_ONLY' AND document_version_id IS NULL AND document_sha256 IS NULL) OR
        (record_mode <> 'STRUCTURED_ONLY' AND document_version_id IS NOT NULL AND document_sha256 IS NOT NULL))
);

CREATE TABLE agreement_version_review (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    agreement_version_id uuid NOT NULL UNIQUE REFERENCES agreement_version(id),
    decision             varchar(8) NOT NULL,
    reviewer_id          uuid NOT NULL REFERENCES app_user(id),
    reviewed_at          timestamptz NOT NULL,
    reason               text,
    CONSTRAINT agreement_review_decision_ck CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT agreement_review_reason_ck CHECK (decision <> 'REJECT' OR (reason IS NOT NULL AND btrim(reason) <> ''))
);

CREATE TABLE agreement_signature (
    id                                uuid PRIMARY KEY,
    tenant_id                         uuid NOT NULL REFERENCES tenant(id),
    agreement_id                      uuid NOT NULL REFERENCES agreement(id),
    signatory_id                      uuid NOT NULL REFERENCES agreement_signatory(id),
    agreement_version_id              uuid NOT NULL REFERENCES agreement_version(id),
    signed_content_sha256             char(64) NOT NULL,
    signed_on                         date NOT NULL,
    method                            varchar(200) NOT NULL,
    recorded_by                       uuid NOT NULL REFERENCES app_user(id),
    recorded_at                       timestamptz NOT NULL,
    countersigned_document_version_id uuid REFERENCES document_version(id),
    UNIQUE (agreement_id, signatory_id)
);

SELECT enable_tenant_rls('agreement');
SELECT enable_tenant_rls('agreement_signatory');
SELECT enable_tenant_rls('agreement_version');
SELECT enable_tenant_rls('agreement_version_review');
SELECT enable_tenant_rls('agreement_signature');

GRANT SELECT, INSERT, UPDATE ON agreement           TO onboarding_app;
-- DELETE granted deliberately: PUT .../signatories replaces a DRAFT agreement's
-- list, and a signatory row is not a business record -- the frozen copy a
-- signature is proven against lives in agreement_version.structured_snapshot.
-- AgreementService only deletes signatories of an agreement in DRAFT.
GRANT SELECT, INSERT, UPDATE, DELETE ON agreement_signatory TO onboarding_app;
-- Append-only evidence: the plan_revision_item / audit_event shape.
GRANT SELECT, INSERT ON agreement_version        TO onboarding_app;
GRANT SELECT, INSERT ON agreement_version_review TO onboarding_app;
GRANT SELECT, INSERT ON agreement_signature      TO onboarding_app;
```

Before relying on the `REFERENCES requirement(id)` / `onboarding_case(id)` table names, confirm them in `V13__journey.sql`.

- [ ] **Step 4: Write the entities, enums, repositories and the test-support component**

Enums:

```java
public enum AgreementStatus { DRAFT, UNDER_REVIEW, APPROVED, SENT, AWAITING_SIGNATURE, SIGNED, CANCELLED;
    /** Spec section 5.8: any status before SIGNED may be cancelled. */
    public boolean isCancellable() { return this != SIGNED && this != CANCELLED; }
}
public enum SignatoryKind { CONTACT, INTERNAL }
public enum ReviewDecision { APPROVE, REJECT }
public enum SignatureProviderKind { MANUAL }
```

`Agreement.java` — mutable, ordinary setters, the `DocumentRequest` shape (not `TenantScopedEntity`; set `createdAt`/`updatedAt` explicitly in the services). Fields map 1:1 to the columns above: `UUID id, tenantId, caseId, requirementId, customerId; String name; @Enumerated(STRING) AgreementRecordMode recordMode; @Enumerated(STRING) AgreementStatus status; LocalDate effectiveDate, expiresAt, renewalDate; Integer noticePeriodDays; UUID ownerUserId, documentId, lastEditedBy, replacesAgreementId; String cancelReason; Instant signedAt; @Enumerated(STRING) SignatureProviderKind signatureProvider; String providerEnvelopeId; @Version long lockVersion; Instant createdAt, updatedAt`. Getters and setters for each.

`AgreementSignatory.java` — mutable: `id, tenantId, agreementId, @Enumerated(STRING) SignatoryKind kind, contactId, userId, String displayRole, int sortOrder`.

`AgreementVersion.java`, `AgreementVersionReview.java`, `AgreementSignature.java` — **immutable**: one all-args constructor, a protected no-arg constructor for JPA, getters only, no setters (the `DocumentVersion` shape). `AgreementVersion.structuredSnapshot` is a `String` column mapped with `@JdbcTypeCode(SqlTypes.JSON)` — confirm the Hibernate 6 JSON mapping used elsewhere in the codebase (`grep -rn "SqlTypes.JSON" backend/src/main`) and copy it; if none exists, use `@JdbcTypeCode(SqlTypes.JSON) private String structuredSnapshot;`.

Repositories, each `extends JpaRepository<T, UUID>, JpaSpecificationExecutor<T>`, with these discovery queries:

```java
public interface AgreementRepository extends JpaRepository<Agreement, UUID>, JpaSpecificationExecutor<Agreement> {
    /** Instantiation's existence check: the requirement's live agreement, if any. Fed only requirement ids the caller controls. */
    @Query("select a from Agreement a where a.requirementId = :requirementId and a.status <> co.ara.onboarding.agreement.AgreementStatus.CANCELLED")
    Optional<Agreement> liveFor(@Param("requirementId") UUID requirementId);
}
public interface AgreementSignatoryRepository extends JpaRepository<AgreementSignatory, UUID>, JpaSpecificationExecutor<AgreementSignatory> {
    @Query("select s from AgreementSignatory s where s.agreementId = :agreementId order by s.sortOrder, s.id")
    List<AgreementSignatory> ofAgreement(@Param("agreementId") UUID agreementId);
    @Modifying @Query("delete from AgreementSignatory s where s.agreementId = :agreementId")
    void clearFor(@Param("agreementId") UUID agreementId);
}
public interface AgreementVersionRepository extends JpaRepository<AgreementVersion, UUID>, JpaSpecificationExecutor<AgreementVersion> {
    @Query("select coalesce(max(v.versionNumber), 0) from AgreementVersion v where v.agreementId = :agreementId")
    int maxVersionNumber(@Param("agreementId") UUID agreementId);
    @Query("select v from AgreementVersion v where v.agreementId = :agreementId order by v.versionNumber desc")
    List<AgreementVersion> ofAgreementNewestFirst(@Param("agreementId") UUID agreementId);
}
public interface AgreementVersionReviewRepository extends JpaRepository<AgreementVersionReview, UUID>, JpaSpecificationExecutor<AgreementVersionReview> {
    @Query("select r from AgreementVersionReview r where r.agreementVersionId in :versionIds")
    List<AgreementVersionReview> ofVersions(@Param("versionIds") Collection<UUID> versionIds);
}
public interface AgreementSignatureRepository extends JpaRepository<AgreementSignature, UUID>, JpaSpecificationExecutor<AgreementSignature> {
    @Query("select s from AgreementSignature s where s.agreementId = :agreementId order by s.recordedAt")
    List<AgreementSignature> ofAgreement(@Param("agreementId") UUID agreementId);
}
```

Test support — `backend/src/test/java/co/ara/onboarding/agreement/AgreementTestSupport.java`, a `@Component` in the test tree (the `JourneyFixtures` shape), with `Agreement draftAgreementRow(UUID tenant)` (creates a case via `JourneyFixtures.newCase`, a milestone and requirement via `newMilestone`/`newRequirement`, then saves a `DRAFT` `FILE_BACKED` agreement owned by the tenant administrator) and `Agreement draftAgreementRowFor(UUID tenant, UUID caseId, UUID requirementId)`. Later tasks extend it; keep every helper usable only inside `runAs`.

- [ ] **Step 5: Run the schema test and `RlsCoverageTest`**

```powershell
.\gradlew.bat cleanTest test --tests "*AgreementSchemaTest*" --tests "*RlsCoverageTest*"
```

Expected: PASS. `RlsCoverageTest` passes with its allowlist unchanged at four.

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat(agreement): add the agreement tables, entities and repositories"
```

### Task 4: The two module-boundary rules, proven red

**Files:**
- Modify: `backend/src/test/java/co/ara/onboarding/architecture/ModuleBoundaryTest.java`

**Interfaces:** Produces `noJourneyDependencyOnAgreement`, `noDocumentDependencyOnAgreement`.

- [ ] **Step 1: Add both rules** beside `noJourneyDependencyOnDocument` (line ~119), in its exact shape:

```java
    @ArchTest
    static final ArchRule noJourneyDependencyOnAgreement =
            noClasses().that().resideInAPackage("..journey..")
                .should().dependOnClassesThat().resideInAPackage("..agreement..")
                .because("agreement depends on journey, never the reverse -- journey reaches it only "
                       + "through the AgreementLifecycle port it declares (spec 3.2). A one-way import "
                       + "would still pass the plain no-cycles rule.");

    @ArchTest
    static final ArchRule noDocumentDependencyOnAgreement =
            noClasses().that().resideInAPackage("..document..")
                .should().dependOnClassesThat().resideInAPackage("..agreement..")
                .because("agreement consumes document through AgreementFiles; document names no agreement "
                       + "type (spec 3.1). AgreementFiles uses only authz permission keys.");
```

Match whatever annotation the neighbouring rules actually use (`@ArchTest` field vs. a test method) — read the file first.

- [ ] **Step 2: Prove each red.** Temporarily add `import co.ara.onboarding.agreement.Agreement;` plus an unused field of that type to `journey/AgreementLifecycle`'s eventual neighbour — any existing `journey` class, e.g. `journey/DocumentRequestLifecycle.java` — run:

```powershell
.\gradlew.bat cleanTest test --tests "*ModuleBoundaryTest*"
```

Expected: FAIL naming `noJourneyDependencyOnAgreement`. Revert, repeat with a `document` class for `noDocumentDependencyOnAgreement`. Revert.

- [ ] **Step 3: Run green** — same command, expected PASS.
- [ ] **Step 4: Commit** — `test(architecture): forbid journey and document from importing agreement`. Record both red runs in the commit body.

### Task 5: Permissions, role templates and the `sign_record ⇒ milestone.complete` guard

**Files:**
- Modify: `authz/PermissionKeys.java`, `authz/PermissionCatalog.java` (after the `DOCUMENT_*` block, line ~138), `authz/RoleTemplates.java`
- Test: `backend/src/test/java/co/ara/onboarding/authz/SignRecordImpliesMilestoneCompleteTest.java`; existing `RoleTemplateCoverageTest`, `RoleTemplateValidityTest`, `PermissionCatalogTest`

**Interfaces:** Produces `PermissionKeys.AGREEMENT_VIEW = "agreement.view"`, `AGREEMENT_MANAGE = "agreement.manage"`, `AGREEMENT_REVIEW = "agreement.review"`, `AGREEMENT_SIGN_RECORD = "agreement.sign_record"`.

- [ ] **Step 1: Write the failing guard** — derived, not a name list, so it covers every template now and later:

```java
package co.ara.onboarding.authz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 6.3 amendment. The last signature calls journey.RequirementService.satisfy,
 * gated milestone.complete; a template holding agreement.sign_record without
 * milestone.complete at an equal-or-broader scope would have every final signature
 * refused and rolled back. Derived over RoleTemplates.ALL, never a list of names.
 */
class SignRecordImpliesMilestoneCompleteTest {

    @Test
    void everyTemplateHoldingSignRecordHoldsMilestoneCompleteAtLeastAsBroadly() {
        for (RoleTemplate template : RoleTemplates.ALL) {
            Scope signRecord = template.grants().get(PermissionKeys.AGREEMENT_SIGN_RECORD);
            if (signRecord == null) continue;
            Scope complete = template.grants().get(PermissionKeys.MILESTONE_COMPLETE);
            assertThat(complete)
                    .as(template.name() + " holds agreement.sign_record at " + signRecord
                            + " but milestone.complete at " + complete)
                    .isNotNull();
            assertThat(Scopes.atLeastAsBroad(complete, signRecord))
                    .as(template.name() + ": milestone.complete " + complete + " is narrower than sign_record " + signRecord)
                    .isTrue();
        }
    }

    @Test
    void theGuardIsNotVacuous() {
        assertThat(RoleTemplates.ALL).anyMatch(t -> t.grants().containsKey(PermissionKeys.AGREEMENT_SIGN_RECORD));
    }
}
```

`RoleTemplates.ALL`, `RoleTemplate.grants()`/`name()` and a scope-breadth comparison: check what `RoleTemplateCoverageTest` and `RoleService.refuseEscalation` already use and call **that** (it must exist — delegation checks "equal or broader scope"). If the comparison lives as a private method, expose it rather than duplicating it; rename `Scopes.atLeastAsBroad` in this step to the real name. Breadth order: `ALL > DEPARTMENT > TEAM > ASSIGNED`.

- [ ] **Step 2: Run to verify it fails** — `--tests "*SignRecordImpliesMilestoneCompleteTest*"`; expected: compile failure (`AGREEMENT_SIGN_RECORD` missing).
- [ ] **Step 3: Implement.** `PermissionKeys`:

```java
    public static final String AGREEMENT_VIEW        = "agreement.view";
    public static final String AGREEMENT_MANAGE      = "agreement.manage";
    public static final String AGREEMENT_REVIEW      = "agreement.review";
    public static final String AGREEMENT_SIGN_RECORD = "agreement.sign_record";
```

`PermissionCatalog`:

```java
        add(AGREEMENT_VIEW,        "agreement", "agreement", "View agreements",                                RECORD);
        add(AGREEMENT_MANAGE,      "agreement", "agreement", "Draft, submit, send or cancel an agreement",     ORG_SCOPES);
        add(AGREEMENT_REVIEW,      "agreement", "agreement", "Approve or reject a submitted agreement version", ORG_SCOPES);
        add(AGREEMENT_SIGN_RECORD, "agreement", "agreement", "Record that a signatory has signed",             ORG_SCOPES);
```

`RoleTemplates` — add, with a one-line comment each citing spec §6.3:

| Template | Entries |
|---|---|
| Sales Representative | `entry(AGREEMENT_VIEW, ASSIGNED)` |
| Account Manager | `entry(AGREEMENT_VIEW, TEAM), entry(AGREEMENT_MANAGE, TEAM)` — comment: no `sign_record`, it holds no `milestone.complete` |
| Project Manager | `entry(AGREEMENT_VIEW, TEAM), entry(AGREEMENT_MANAGE, TEAM), entry(AGREEMENT_SIGN_RECORD, TEAM)` |
| Service Provider, Business Partner | `AGREEMENT_VIEW, ASSIGNED` (these use `Map.of` — **`Map.of` caps at 10 pairs**; if adding one exceeds it, convert that template to `Map.ofEntries`) |
| Operations | `entry(AGREEMENT_VIEW, DEPARTMENT), entry(AGREEMENT_MANAGE, DEPARTMENT), entry(AGREEMENT_SIGN_RECORD, DEPARTMENT)` |
| Legal | `entry(AGREEMENT_VIEW, ALL), entry(AGREEMENT_REVIEW, ALL)` |
| Finance, Compliance | `AGREEMENT_VIEW, ALL` |
| Technical, Support | `AGREEMENT_VIEW, TEAM` |
| Administrator | all four at `ALL` |

`DescriptorRegistry.validate()` will now refuse to start the application — the `agreement` resource type has no descriptor until Task 6. **Tasks 5 and 6 therefore land in one commit**: do not commit at the end of this task; continue to Task 6 and commit both together.

- [ ] **Step 4: Continue to Task 6.**

### Task 6: The five descriptors and `AgreementAudienceFilter`

**Files:**
- Create: `scoping/AgreementDescriptor.java`, `AgreementSignatoryDescriptor.java`, `AgreementVersionDescriptor.java`, `AgreementVersionReviewDescriptor.java`, `AgreementSignatureDescriptor.java`, `AgreementAudienceFilter.java`
- Modify: `authz/PortalPermissions.java`
- Test: `backend/src/test/java/co/ara/onboarding/scoping/AgreementScopingTest.java`, `AgreementAudienceFilterTest.java`

**Interfaces:**
- Consumes: `PortalContactDirectory.findActiveContactForUser(UUID): Optional<PortalContactFacts>` (`customerId()`, `id()`).
- Produces: `AgreementDescriptor` (`resourceType() = "agreement"`), four child descriptors (`resourceType()` = `"agreement_signatory"`, `"agreement_version"`, `"agreement_version_review"`, `"agreement_signature"`), `AgreementAudienceFilter implements AudienceFilter<Agreement>`; `PortalPermissions.forContact()/forSponsor()` gain `AGREEMENT_VIEW, Scope.ALL`.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementScopingTest extends PostgresTestBase {
    @Test void aTeamScopedViewerSeesAgreementsOnTheirTeamsCasesOnly() { }
    @Test void aDepartmentScopedViewerSeesAgreementsOnTheirDepartmentsCasesOnly() { }
    @Test void anAssignedViewerSeesOnlyCasesTheyPersonallyParticipateIn() { }
    @Test void aViewerWithNoDepartmentAndNoTeamSeesNothingAtThoseScopes() { }   // fail closed
    @Test void childRowsFollowTheirAgreementsCase() { }   // a signatory/version on an out-of-scope case is 404
}

class AgreementAudienceFilterTest extends PostgresTestBase {
    @Test void aPortalContactSeesNothingInDraftUnderReviewOrApproved() { }
    @Test void aPortalContactSeesTheirCustomersAgreementFromSentOnward() { }   // SENT, AWAITING_SIGNATURE, SIGNED
    @Test void aPortalContactNeverSeesAnotherCustomersAgreement() { }
    @Test void aPortalUserWithNoActiveContactSeesNothing() { }                  // retired contact
    @Test void aCancelledAgreementIsInvisibleToThePortal() { }
    @Test void internalAllScopedReadersAreNotNarrowedByTheFilter() { }
}
```

Each builds its rows with `AgreementTestSupport` (extend it with `agreementRowInStatus(tenant, caseId, status)` and a way to put a case in a given department/team — use `JourneyFixtures.newCase(tenant, owner, departmentId, teamId)`), reads through `AuthorizedQuery.findAll(agreements, Agreement.class, AGREEMENT_VIEW, null, Pageable.unpaged())` as the relevant user (`fixture.runAsUser`, portal users from `fixture.createPortalUserForContact`), and asserts the visible id set. Write every body before running; the scoping test mirrors `scoping.JourneyScopingTest`, the audience test mirrors the portal half of `DocumentAudienceFilterTest` — read both first.

- [ ] **Step 2: Run to verify failure** — expected: application context fails to start (`DescriptorRegistry.validate()` — no descriptor for `agreement`).
- [ ] **Step 3: Implement the descriptors.** `AgreementDescriptor` is `DocumentRequestDescriptor` with the entity type swapped (same `viaCase` subquery on `caseId`, same `assignedRelationships()` — `OWNER, ASSIGNEE, PARTICIPANT, APPROVER`, same fail-closed `cb.disjunction()`). The four child descriptors resolve through their agreement's case:

```java
@Component
public class AgreementSignatoryDescriptor implements ResourceAuthorizationDescriptor<AgreementSignatory> {
    // Not catalogued against any permission: exists because AuthorizedQuery dispatches by
    // ENTITY type, and AgreementService reads signatories under agreement.view/manage --
    // the CaseParticipantDescriptor lesson (CLAUDE.md "Six descriptors exist").
    private final AgreementDescriptor agreements;
    public AgreementSignatoryDescriptor(AgreementDescriptor agreements) { this.agreements = agreements; }

    @Override public String resourceType() { return "agreement_signatory"; }
    @Override public Class<AgreementSignatory> entityType() { return AgreementSignatory.class; }
    @Override public Set<RelationshipType> assignedRelationships() { return agreements.assignedRelationships(); }
    @Override public Specification<AgreementSignatory> departmentScope(AuthContext ctx) { return viaAgreement(agreements.departmentScope(ctx)); }
    @Override public Specification<AgreementSignatory> teamScope(AuthContext ctx)       { return viaAgreement(agreements.teamScope(ctx)); }
    @Override public Specification<AgreementSignatory> assignedScope(AuthContext ctx)   { return viaAgreement(agreements.assignedScope(ctx)); }

    private Specification<AgreementSignatory> viaAgreement(Specification<Agreement> onAgreement) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var a = sub.from(Agreement.class);
            sub.select(a.get("id")).where(onAgreement.toPredicate(a, query, cb));
            return root.get("agreementId").in(sub);
        };
    }
}
```

`toPredicate(a, query, cb)` needs a `Root<Agreement>`; `sub.from(Agreement.class)` returns one. If the compiler rejects passing the outer `query` for a subquery root, build the agreement predicate against `a` by duplicating the `viaCase` subquery logic here instead — read how `CaseParticipantDescriptor` does its equivalent and follow it. `AgreementVersionDescriptor`, `AgreementVersionReviewDescriptor` (joins through `agreementVersionId` → version → agreement) and `AgreementSignatureDescriptor` follow the same shape. Confirm how `DescriptorRegistry` treats a descriptor whose `resourceType()` is catalogued by no permission (`CaseParticipantDescriptor` is the precedent).

- [ ] **Step 4: Implement the audience filter**

```java
package co.ara.onboarding.scoping;

/**
 * Spec 6.4. Registered on sub-project 4's AudienceFilter seam, so it binds even at
 * Scope.ALL -- which is the only scope a portal actor ever resolves (PortalPermissions).
 * Internal readers pass untouched: case scope already governs them. A portal contact
 * sees their own customer's agreements from SENT onward, never a draft, a version under
 * review, an approved-but-unsent agreement, or a cancelled one.
 */
@Component
public class AgreementAudienceFilter implements AudienceFilter<Agreement> {

    static final Set<AgreementStatus> PORTAL_VISIBLE =
            EnumSet.of(AgreementStatus.SENT, AgreementStatus.AWAITING_SIGNATURE, AgreementStatus.SIGNED);

    private final PortalContactDirectory contacts;

    public AgreementAudienceFilter(PortalContactDirectory contacts) { this.contacts = contacts; }

    @Override public Class<Agreement> entityType() { return Agreement.class; }

    @Override
    public Specification<Agreement> audience(AuthContext ctx, String permissionKey) {
        if (ctx.userType() != UserType.PORTAL) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            var contact = contacts.findActiveContactForUser(ctx.userId()).orElse(null);
            if (contact == null) return cb.disjunction();   // no live contact => no agreements, ever
            return cb.and(
                    cb.equal(root.get("customerId"), contact.customerId()),
                    root.get("status").in(PORTAL_VISIBLE));
        };
    }
}
```

The child entities have **no** audience filter: the portal never reads them through `AuthorizedQuery` — `PortalAgreementService` (Task 20) loads the agreement through this filter and then reads its children by the resolved agreement's id.

`PortalPermissions.forContact()`/`forSponsor()` — add `PermissionKeys.AGREEMENT_VIEW, Scope.ALL`.

- [ ] **Step 5: Run Tasks 5 and 6 tests plus the authz and architecture suites**

```powershell
.\gradlew.bat cleanTest test --tests "co.ara.onboarding.authz.*" --tests "co.ara.onboarding.scoping.*" --tests "co.ara.onboarding.architecture.*" --tests "co.ara.onboarding.security.*"
```

Expected: PASS — including `RoleTemplateCoverageTest` (each multi-scope agreement permission held by a non-Administrator template), `DescriptorRegistryTest`, and `SignRecordImpliesMilestoneCompleteTest`.

- [ ] **Step 6: Commit Tasks 5 and 6 together**

```bash
git add backend/
git commit -m "feat(authz): add agreement permissions, role grants, descriptors and the portal audience filter"
```

Body: why the two tasks share a commit (`DescriptorRegistry.validate()` refuses to start between them), and the Account Manager / `sign_record` ruling.

---

## Phase 2 — The document facade

### Task 7: Extract `DocumentContentWriter` from `DocumentService`

A pure refactor. `AgreementFiles` (Task 8) needs the hardened capture-and-persist logic, which is private to `DocumentService`. Forking it would let the two drift; the Task 7 hardening ruling from sub-project 4 must stay implemented exactly once.

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/document/DocumentContentWriter.java` (package-private class, `@Component`)
- Modify: `document/DocumentService.java` (`captureContent` ~1017, `enforceSizeCeiling` ~1036, `readPrefix` ~1041, `sha256` ~1055, `StoredContent` ~1079, `persistNewDocument` ~609, and the version-append block in `addVersion` ~692–720)

**Interfaces:**
- Produces (package-private):
  - `record StoredContent(String storageKey, String contentType, String sha256)`
  - `StoredContent capture(DocumentCategory category, InputStream content, long sizeBytes)` — size ceiling, content sniff against the category allowlist, streaming SHA-256, blob write. Throws the existing `UploadTooLargeException` / `UnacceptableContentTypeException`.
  - `Document createDocument(Case c, UUID uploadedBy, String name, DocumentCategory category, VisibilityTier tier, UUID targetDepartmentId, String targetContactLabel, UUID ownerContactId, Instant expiresAt, StoredContent stored, long sizeBytes)` — inserts `document` + version 1, sets `current_version_id`, returns the saved `Document`.
  - `DocumentVersion appendVersion(Document d, UUID uploadedBy, StoredContent stored, long sizeBytes)` — the `maxVersionNo + 1` insert with the existing `DocumentVersionConflictException` mapping, updates `current_version_id`.

- [ ] **Step 1: Confirm the safety net is green** — `.\gradlew.bat cleanTest test --tests "co.ara.onboarding.document.*"`. Expected: PASS. These tests (upload hardening, versions, portal upload, size limits) are this refactor's specification; no new test is written.
- [ ] **Step 2: Move the code.** Cut the listed private members into `DocumentContentWriter` verbatim, give it the constructor dependencies they use (`BlobStore`, `StorageProperties`, `ContentSniffGuard`, `DocumentRepository`, `DocumentVersionRepository`, `Clock`), and have `DocumentService` call it. Do not change a single behaviour, message or exception type. The writer takes no `AuthorizedQuery` and does **no** authorization — every caller has already resolved the `Case`/`Document` through `AuthorizedQuery` and applied write scope. Say so in its class javadoc, and note it injects repositories, so `AuthorizationCoverageTest`'s finder rule covers it: it must call only `save`/`saveAndFlush` and the non-`findBy*` discovery method `versions.maxVersionNo`, never a finder.
- [ ] **Step 3: Run the document package and the architecture suite** — expected: PASS, identical counts to Step 1.
- [ ] **Step 4: Commit** — `refactor(document): extract DocumentContentWriter so agreements reuse the hardened upload path`.

### Task 8: `document.AgreementFiles`

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/document/AgreementFiles.java`
- Test: `backend/src/test/java/co/ara/onboarding/document/AgreementFilesTest.java`

**Interfaces:**
- Consumes: `DocumentContentWriter` (Task 7), `AuthorizedQuery`, `StageWriteScopeGuard`, `AuthContextProvider`, `AuditRecorder`.
- Produces:

```java
public record OwnedFile(UUID documentId, UUID documentVersionId, int versionNumber, String sha256) {}

@RequirePermission(AGREEMENT_MANAGE)  
    OwnedFile createOwnedDocument(UUID caseId, String name, InputStream content, long sizeBytes);
@RequirePermission(AGREEMENT_MANAGE)
    OwnedFile addDraftVersion(UUID documentId, InputStream content, long sizeBytes);
@RequirePermission(AGREEMENT_SIGN_RECORD)
    OwnedFile addCountersignedVersion(UUID documentId, InputStream content, long sizeBytes);
@RequirePermission(AGREEMENT_MANAGE)
    void retier(UUID documentId, VisibilityTier tier);   // SENSITIVE or COMPANY_SHARED only
```

`OwnedFile` lives in `document` as a public record.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementFilesTest extends PostgresTestBase {
    @Test void createOwnedDocumentMakesASensitiveUntargetedAgreementCategoryDocumentOnTheCase() { }
    @Test void anAgreementManagerWithoutAnyDocumentPermissionCanCreateAndVersion() { }  // only agreement.manage granted
    @Test void theUploadIsHardenedExactlyLikeADocumentUpload() { }   // oversize -> UploadTooLargeException; sniff mismatch -> UnacceptableContentTypeException; sha256 stored
    @Test void aPortalContactCannotDownloadTheSensitiveFileThroughTheDocumentContentEndpoint() { }
    @Test void afterRetierToCompanySharedThePortalContactCanDownloadIt() { }
    @Test void retierRefusesContactOnly() { }                      // IllegalArgumentException
    @Test void theFacadeRefusesADocumentThatIsNotAgreementCategory() { }   // 404, not 400: it is not an agreement file
    @Test void anOutOfScopeCaseIsNotFound() { }
    @Test void addCountersignedVersionNeedsSignRecordNotManage() { }
}
```

For the two portal-download tests, call `DocumentContentService`'s download method (the one behind `GET /documents/{id}/versions/{n}/content`) as a portal user from `fixture.createPortalUserForContact` for the case's customer, and assert `NoSuchElementException` before retier / success after. Write every body before running.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
package co.ara.onboarding.document;

/**
 * Sub-project 5, spec section 7: every document write an agreement needs, through one
 * narrow facade gated on agreement permissions -- so an agreement author needs no
 * document.* permission, and an agreement's file is mutable only via its agreement.
 * Names no agreement type (ModuleBoundaryTest.noDocumentDependencyOnAgreement): the
 * gates are authz permission keys, and the category check below is how it recognises
 * "an agreement's document" without knowing what an agreement is.
 *
 * Documents are created SENSITIVE: that hides them from every portal contact absent an
 * explicit share (DocumentAudienceFilter.portalAudience) while leaving internal case
 * readers untouched, so a DRAFT agreement's file cannot leak through the existing portal
 * document endpoints. Sending the agreement retiers it COMPANY_SHARED; cancelling a sent
 * agreement retiers it back.
 */
@Component
public class AgreementFiles {

    private final DocumentRepository documents;
    private final CaseRepository cases;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;
    private final AuthContextProvider contextProvider;
    private final DocumentContentWriter writer;

    // constructor assigning all seven

    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public OwnedFile createOwnedDocument(UUID caseId, String name, InputStream content, long sizeBytes) {
        refusePortal();
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.AGREEMENT_MANAGE, caseId);
        applyWriteScope(c);
        UUID actor = contextProvider.current().userId();
        var stored = writer.capture(DocumentCategory.AGREEMENT, content, sizeBytes);
        Document d = writer.createDocument(c, actor, name, DocumentCategory.AGREEMENT, VisibilityTier.SENSITIVE,
                null, null, null, null, stored, sizeBytes);
        return new OwnedFile(d.getId(), d.getCurrentVersionId(), 1, stored.sha256());
    }

    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public OwnedFile addDraftVersion(UUID documentId, InputStream content, long sizeBytes) {
        return append(documentId, PermissionKeys.AGREEMENT_MANAGE, content, sizeBytes);
    }

    @RequirePermission(PermissionKeys.AGREEMENT_SIGN_RECORD)
    @Transactional
    public OwnedFile addCountersignedVersion(UUID documentId, InputStream content, long sizeBytes) {
        return append(documentId, PermissionKeys.AGREEMENT_SIGN_RECORD, content, sizeBytes);
    }

    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public void retier(UUID documentId, VisibilityTier tier) {
        if (tier != VisibilityTier.SENSITIVE && tier != VisibilityTier.COMPANY_SHARED) {
            throw new IllegalArgumentException("An agreement's document is SENSITIVE or COMPANY_SHARED, never " + tier);
        }
        Document d = ownedDocument(documentId, PermissionKeys.AGREEMENT_MANAGE);
        d.setVisibilityTier(tier);
        d.setUpdatedAt(Instant.now(/* inject Clock */));
        documents.save(d);
    }

    private OwnedFile append(UUID documentId, String permission, InputStream content, long sizeBytes) {
        refusePortal();
        Document d = ownedDocument(documentId, permission);
        Case c = authorizedQuery.getById(cases, Case.class, permission, d.getCaseId());
        applyWriteScope(c);
        var stored = writer.capture(DocumentCategory.AGREEMENT, content, sizeBytes);
        DocumentVersion v = writer.appendVersion(d, contextProvider.current().userId(), stored, sizeBytes);
        return new OwnedFile(d.getId(), v.getId(), v.getVersionNo(), stored.sha256());
    }

    /** Resolved through AuthorizedQuery like any id; a non-AGREEMENT document is simply not an agreement's file -> 404. */
    private Document ownedDocument(UUID documentId, String permission) {
        Document d = authorizedQuery.getById(documents, Document.class, permission, documentId);
        if (d.getCategory() != DocumentCategory.AGREEMENT) throw new NoSuchElementException("Not found");
        return d;
    }

    private void refusePortal() {
        if (contextProvider.current().userType() == UserType.PORTAL) throw new NoSuchElementException("Not found");
    }

    private void applyWriteScope(Case c) {
        if (c.getCurrentStageId() == null) return;
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, c.getCurrentStageId());
        writeScope.check(c, stage);
    }
}
```

Inject a `Clock` for `setUpdatedAt`. Use the real setter/getter names on `Document`/`DocumentVersion` (`setVisibilityTier`, `getVersionNo` — confirm them). **Audit:** record nothing here — the agreement action recorded by the caller (`agreement.submitted` / `.signature_recorded` / `.sent`) is the business event; a `document.uploaded` for a SENSITIVE agreement draft would put a customer-invisible file on the customer-visible timeline.

- [ ] **Step 4: Run the tests and the architecture suite** — expected PASS, `noDocumentDependencyOnAgreement` still green.
- [ ] **Step 5: Commit** — `feat(document): add the AgreementFiles facade`.

---

## Phase 3 — The agreement core

### Task 9: `AgreementContentHasher`

**Files:**
- Create: `backend/src/main/java/co/ara/onboarding/agreement/AgreementContentHasher.java`, `AgreementSnapshot.java`
- Test: `backend/src/test/java/co/ara/onboarding/agreement/AgreementContentHasherTest.java` (plain JUnit, no Spring)

**Interfaces:**
- Produces:

```java
public record AgreementSnapshot(String name, AgreementRecordMode recordMode, LocalDate effectiveDate,
                                LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays,
                                List<SignatorySnapshot> signatories) {
    public record SignatorySnapshot(UUID id, SignatoryKind kind, UUID contactId, UUID userId,
                                    String displayRole, int sortOrder) {}
}

public final class AgreementContentHasher {
    public static String canonicalJson(AgreementSnapshot s);                                   // spec 4.4.1
    public static String contentSha256(AgreementSnapshot s, String documentSha256OrNull);     // 64 lowercase hex
}
```

- [ ] **Step 1: Write the failing tests**

```java
package co.ara.onboarding.agreement;

class AgreementContentHasherTest {

    private static final UUID S1 = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID S2 = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID C1 = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
    private static final UUID U1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

    private static final AgreementSnapshot.SignatorySnapshot A =
            new AgreementSnapshot.SignatorySnapshot(S1, SignatoryKind.CONTACT, C1, null, "Customer signatory", 0);
    private static final AgreementSnapshot.SignatorySnapshot B =
            new AgreementSnapshot.SignatorySnapshot(S2, SignatoryKind.INTERNAL, null, U1, "Provider signatory", 1);

    private static AgreementSnapshot snapshot(List<AgreementSnapshot.SignatorySnapshot> signatories) {
        return new AgreementSnapshot("Master Services Agreement", AgreementRecordMode.STRUCTURED_PLUS_FILE,
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30), null, 30, signatories);
    }

    @Test
    void canonicalJsonSortsKeysUsesIsoDatesAndSerialisesAbsentOptionalsAsNull() {
        assertThat(AgreementContentHasher.canonicalJson(snapshot(List.of(A))))
                .isEqualTo("{\"effectiveDate\":\"2026-10-01\",\"expiresAt\":\"2027-09-30\",\"name\":\"Master Services Agreement\","
                        + "\"noticePeriodDays\":30,\"recordMode\":\"STRUCTURED_PLUS_FILE\",\"renewalDate\":null,"
                        + "\"signatories\":[{\"contactId\":\"" + C1 + "\",\"displayRole\":\"Customer signatory\",\"id\":\"" + S1
                        + "\",\"kind\":\"CONTACT\",\"sortOrder\":0,\"userId\":null}]}");
    }

    @Test
    void signatoryInputOrderDoesNotChangeTheHash() {
        assertThat(AgreementContentHasher.contentSha256(snapshot(List.of(B, A)), null))
                .isEqualTo(AgreementContentHasher.contentSha256(snapshot(List.of(A, B)), null));
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        var base = snapshot(List.of(A));
        var renamed = new AgreementSnapshot("MSA v2", base.recordMode(), base.effectiveDate(), base.expiresAt(),
                base.renewalDate(), base.noticePeriodDays(), base.signatories());
        assertThat(AgreementContentHasher.contentSha256(renamed, null))
                .isNotEqualTo(AgreementContentHasher.contentSha256(base, null));
    }

    @Test
    void theFileDigestIsPartOfTheIdentity() {
        var s = snapshot(List.of(A));
        String withFile = AgreementContentHasher.contentSha256(s, "a".repeat(64));
        assertThat(withFile).isNotEqualTo(AgreementContentHasher.contentSha256(s, null));
        assertThat(withFile).isNotEqualTo(AgreementContentHasher.contentSha256(s, "b".repeat(64)));
        assertThat(withFile).matches("[0-9a-f]{64}");
    }

    @Test
    void aQuoteOrNewlineInANameIsEscapedNotInjected() {
        var tricky = new AgreementSnapshot("A \"quoted\"\nname", AgreementRecordMode.STRUCTURED_ONLY,
                LocalDate.of(2026, 1, 1), null, null, null, List.of());
        assertThat(AgreementContentHasher.canonicalJson(tricky)).contains("\"name\":\"A \\\"quoted\\\"\\nname\"");
    }

    @Test
    void knownVectorPinsTheAlgorithm() {
        // Produced in Step 4 by an EXTERNAL sha256sum over canonicalJson(snapshot(List.of(A)))
        // followed by one NUL byte -- never by the class under test. A refactor that changes
        // key order, date format or the separator then fails here instead of silently re-hashing.
        assertThat(AgreementContentHasher.contentSha256(snapshot(List.of(A)), null))
                .isEqualTo(KNOWN_DIGEST);
    }

    private static final String KNOWN_DIGEST = "REPLACE_IN_STEP_4";
}
```

`KNOWN_DIGEST` is filled in at Step 4 from an independent tool; committing the literal `REPLACE_IN_STEP_4` is a task failure (the test will be red).

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat cleanTest test --tests "*AgreementContentHasherTest*"`; compile failure.
- [ ] **Step 3: Implement**

```java
package co.ara.onboarding.agreement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Spec 4.4.1 -- the ONE place content_sha256 is computed. Hand-built canonical JSON
 * rather than an ObjectMapper configuration: the bytes must stay stable across Jackson
 * upgrades and config drift, and a known-answer test pins them.
 *
 * content_sha256 = SHA-256( canonical_json || 0x00 || (document_sha256 ?? "") ), lowercase hex.
 */
public final class AgreementContentHasher {

    private AgreementContentHasher() {}

    public static String contentSha256(AgreementSnapshot s, String documentSha256OrNull) {
        MessageDigest digest = sha256();
        digest.update(canonicalJson(s).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update((documentSha256OrNull == null ? "" : documentSha256OrNull).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String canonicalJson(AgreementSnapshot s) {
        List<AgreementSnapshot.SignatorySnapshot> ordered = s.signatories().stream()
                .sorted(Comparator.comparingInt(AgreementSnapshot.SignatorySnapshot::sortOrder)
                        .thenComparing(AgreementSnapshot.SignatorySnapshot::id))
                .toList();
        // Keys are written in lexicographic order by hand -- that IS the canonicalisation.
        StringBuilder b = new StringBuilder("{");
        field(b, "effectiveDate", date(s.effectiveDate())).append(',');
        field(b, "expiresAt", date(s.expiresAt())).append(',');
        field(b, "name", str(s.name())).append(',');
        field(b, "noticePeriodDays", s.noticePeriodDays() == null ? "null" : s.noticePeriodDays().toString()).append(',');
        field(b, "recordMode", str(s.recordMode().name())).append(',');
        field(b, "renewalDate", date(s.renewalDate())).append(',');
        b.append("\"signatories\":[");
        for (int i = 0; i < ordered.size(); i++) {
            var sig = ordered.get(i);
            if (i > 0) b.append(',');
            b.append('{');
            field(b, "contactId", uuid(sig.contactId())).append(',');
            field(b, "displayRole", str(sig.displayRole())).append(',');
            field(b, "id", uuid(sig.id())).append(',');
            field(b, "kind", str(sig.kind().name())).append(',');
            field(b, "sortOrder", Integer.toString(sig.sortOrder())).append(',');
            field(b, "userId", uuid(sig.userId()));
            b.append('}');
        }
        return b.append("]}").toString();
    }

    private static StringBuilder field(StringBuilder b, String key, String jsonValue) {
        return b.append('"').append(key).append("\":").append(jsonValue);
    }

    private static String date(LocalDate d) { return d == null ? "null" : "\"" + d + "\""; }   // ISO yyyy-MM-dd
    private static String uuid(UUID u) { return u == null ? "null" : "\"" + u + "\""; }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Produce the known-answer vector.** The canonical JSON for `snapshot(List.of(A))` is exactly the string asserted in `canonicalJsonSortsKeysUsesIsoDatesAndSerialisesAbsentOptionalsAsNull`. In Git Bash:

```bash
printf '%s\0' '{"effectiveDate":"2026-10-01","expiresAt":"2027-09-30","name":"Master Services Agreement","noticePeriodDays":30,"recordMode":"STRUCTURED_PLUS_FILE","renewalDate":null,"signatories":[{"contactId":"01900000-0000-7000-8000-0000000000c1","displayRole":"Customer signatory","id":"01900000-0000-7000-8000-000000000001","kind":"CONTACT","sortOrder":0,"userId":null}]}' | sha256sum
```

Paste the digest into `KNOWN_DIGEST`.

- [ ] **Step 5: Run to verify pass.**
- [ ] **Step 6: Commit** — `feat(agreement): add the canonical content hasher`.

### Task 10: `AgreementInstantiation`, the `AgreementLifecycle` port, and its two call sites

**Files:**
- Create: `journey/AgreementLifecycle.java`, `agreement/AgreementInstantiation.java`, `agreement/AgreementLifecycleAdapter.java`
- Modify: `journey/CaseService.java` (after `documentRequestLifecycle.instantiateForCase(c.getId());`, line ~244), `journey/MigrationService.java` (after the case is repinned and its new milestones instantiated), `audit/AuditActions.java`, `architecture/AuthorizationCoverageTest.java` (`FINDER_RULE_EXCLUSIONS` ~line 343, and the assertion list ~line 543)
- Test: `agreement/AgreementInstantiationTest.java`; extend `AgreementTestSupport` with `UUID openCaseWithSignatureRequirement(UUID tenant, AgreementRecordMode mode)`

**Interfaces:**
- Produces: `journey.AgreementLifecycle { void instantiateForCase(UUID caseId); }`; `AgreementInstantiation.instantiateForCase(UUID caseId)` — idempotent; `AuditActions.AGREEMENT_CREATED`.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementInstantiationTest extends PostgresTestBase {
    @Test void openingACaseCreatesOneDraftAgreementPerSignatureRequirement() { }
    @Test void theAgreementCopiesNameAndRecordModeFromTheDefinitionAndCustomerFromTheCase() { }
    @Test void theOwnerIsTheMilestoneOwnerFallingBackToTheActingPrincipal() { }   // DocumentInstantiation's same fallback
    @Test void requirementsOfOtherKindsProduceNoAgreement() { }
    @Test void callingItTwiceIsANoOpNotADuplicateOrAnError() { }      // unlike DocumentInstantiation: MigrationService re-calls it
    @Test void migratingToAVersionThatAddsASignatureRequirementCreatesItsAgreement() { }
    @Test void migratingLeavesAnExistingLiveAgreementAlone() { }
    @Test void aCancelledAgreementDoesNotCountAsLiveForInstantiation() { }
    @Test void creationIsAuditedAfterCaseCreated() { }
}
```

Build workflows the way `DocumentInstantiationTest.openCaseWhoseFirstRequirementIsKindDocument` does (`workflows.createTemplate` → `createDraft` → `replaceDraft` with `WorkflowFixtures.signature(...)` → `publishService.publish` → `cases.create`); put that in `AgreementTestSupport.openCaseWithSignatureRequirement`. For migration, publish a second version with `JourneyFixtures.publishNewVersion(templateId, request)` and migrate through `MigrationService`'s public entry point — read `journey.MigrationTest` for the call shape. Write every body before running.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * journey's seam for the SIGNATURE half of the requirement-instantiation pattern
 * (TaskLifecycle, DocumentRequestLifecycle). agreement.AgreementLifecycleAdapter
 * implements it -- a journey-side implementation naming an agreement type would violate
 * ModuleBoundaryTest.noJourneyDependencyOnAgreement.
 *
 * Unlike DocumentRequestLifecycle it has TWO callers -- CaseService.create and
 * MigrationService -- so implementations must be idempotent (spec 3.2).
 */
public interface AgreementLifecycle {
    void instantiateForCase(UUID caseId);
}
```

```java
package co.ara.onboarding.agreement;

/**
 * Creates one DRAFT agreement per SIGNATURE requirement on a case that has no live
 * (non-CANCELLED) agreement yet -- spec 3.2. Modelled on document.DocumentInstantiation,
 * with one deliberate difference: it checks for an existing live agreement first, because
 * MigrationService calls it again after every repin. agreement_live_per_requirement_uq
 * stays the truth under a race.
 *
 * Carries a FINDER_RULE_EXCLUSIONS entry for DocumentInstantiation's exact reason: both
 * callers run it inside their own transaction on a case id they just created or already
 * resolved through AuthorizedQuery -- there is no request-supplied id here to protect.
 *
 * Records agreement.created per row. CaseService.create calls this after case.created is
 * recorded and before engine.reconcile, so cause precedes effect by construction.
 */
@Component
public class AgreementInstantiation {

    private final RequirementRepository requirements;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final AgreementRepository agreements;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final Clock clock;

    public AgreementInstantiation(RequirementRepository requirements,
                                  RequirementDefinitionRepository requirementDefinitions,
                                  MilestoneRepository milestones, CaseRepository cases,
                                  AgreementRepository agreements, AuthContextProvider contextProvider,
                                  AuditRecorder audit, Clock clock) {
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.milestones = milestones;
        this.cases = cases;
        this.agreements = agreements;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.clock = clock;
    }

    public void instantiateForCase(UUID caseId) {
        Case c = cases.findById(caseId).orElseThrow(() -> new NoSuchElementException("Not found"));
        for (Requirement r : requirements.findByCaseId(caseId)) {
            RequirementDefinition definition = requirementDefinitions.findById(r.getRequirementDefinitionId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            if (definition.getKind() != RequirementKind.SIGNATURE) continue;
            if (agreements.liveFor(r.getId()).isPresent()) continue;

            Milestone m = milestones.findById(r.getMilestoneId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            UUID actor = contextProvider.principal().userId();
            Instant now = Instant.now(clock);

            Agreement a = new Agreement();
            a.setId(Uuid7.generate());
            a.setTenantId(r.getTenantId());
            a.setCaseId(caseId);
            a.setRequirementId(r.getId());
            a.setCustomerId(c.getCustomerId());
            a.setName(definition.getAgreementName());
            a.setRecordMode(definition.getAgreementRecordMode());
            a.setStatus(AgreementStatus.DRAFT);
            a.setOwnerUserId(m.getOwnerUserId() != null ? m.getOwnerUserId() : actor);
            a.setLastEditedBy(actor);
            a.setSignatureProvider(SignatureProviderKind.MANUAL);
            a.setCreatedAt(now);
            a.setUpdatedAt(now);
            agreements.save(a);

            audit.record(AuditActions.AGREEMENT_CREATED, "onboarding_case", caseId,
                    "Created agreement " + a.getName(), Map.of("agreementId", a.getId().toString()));
        }
    }
}
```

`AgreementLifecycleAdapter` is `DocumentRequestLifecycleAdapter` with the types swapped (`@Component`, implements `journey.AgreementLifecycle`, delegates).

`AuditActions`:

```java
    // Sub-project 5 (spec 5.9). Business records, timeline-visible -- the customer.* /
    // contact.* side. timelineVisible is the internal Activity tab's flag; a future
    // portal timeline must still hide pre-SENT agreement events (spec 11.3).
    public static final AuditAction AGREEMENT_CREATED = of("agreement.created", true);
```

`CaseService.create` — directly after the document call, and inject `AgreementLifecycle agreementLifecycle` in the constructor:

```java
        // SIGNATURE half of the same seam (sub-project 5, spec 3.2). Idempotent, because
        // MigrationService calls it too.
        agreementLifecycle.instantiateForCase(c.getId());
```

`MigrationService` — inject it too; call `agreementLifecycle.instantiateForCase(c.getId())` **after** the repin and its milestone instantiation and **before** the migration's own reconcile, with a comment citing spec §3.2. **Do not** add the matching `DocumentRequestLifecycle`/`TaskLifecycle` calls — spec §11.1 records that gap as out of scope.

`AuthorizationCoverageTest` — add `"co.ara.onboarding.agreement.AgreementInstantiation"` to `FINDER_RULE_EXCLUSIONS` with a comment in the `DocumentInstantiation` entry's style, and to the list `finderRuleBindsToRepositoryInjectionNotClassName` asserts.

Any test constructing `CaseService`/`MigrationService` by hand now fails to compile: `grep -rn "new CaseService(\|new MigrationService(" backend/src/test` and pass the new dependency.

- [ ] **Step 4: Run** the test plus `co.ara.onboarding.journey.*`, `co.ara.onboarding.document.*` and `co.ara.onboarding.architecture.*`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(agreement): instantiate an agreement per SIGNATURE requirement at case creation and migration`.

### Task 11: Read paths, views, derived status and the lifecycle summary

**Files:**
- Create: `agreement/AgreementService.java` (reads only in this task), `AgreementWrites.java` (package-private, see Step 3), `AgreementDisplayStatus.java`, `AgreementView.java`, `AgreementDetailView.java`, `AgreementSignatoryView.java`, `AgreementVersionView.java`, `AgreementSignatureView.java`, `AgreementSummaryView.java`
- Modify: `audit/AuditActions.java`, `architecture/AuthorizationCoverageTest.java` (covered packages, ~line 382)
- Test: `agreement/AgreementReadTest.java`

**Interfaces:**
- Produces:

```java
public enum AgreementDisplayStatus {
    DRAFT, UNDER_REVIEW, APPROVED, SENT, AWAITING_SIGNATURE, SIGNED, EXPIRED, CANCELLED;

    /** Spec 5.7: EXPIRED is derived -- stored SIGNED with expires_at strictly before today (UTC). */
    public static AgreementDisplayStatus of(AgreementStatus stored, LocalDate expiresAt, LocalDate today) {
        if (stored == AgreementStatus.SIGNED && expiresAt != null && expiresAt.isBefore(today)) return EXPIRED;
        return valueOf(stored.name());
    }
}

public record AgreementView(UUID id, UUID caseId, UUID requirementId, UUID customerId, String customerName,
        String name, AgreementRecordMode recordMode, AgreementStatus status, AgreementDisplayStatus displayStatus,
        LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays,
        UUID ownerUserId, UUID documentId, UUID lastEditedBy, UUID replacesAgreementId, String cancelReason,
        Instant signedAt, SignatureProviderKind signatureProvider, int latestVersionNumber, long lockVersion) {}

public record AgreementSignatoryView(UUID id, SignatoryKind kind, UUID contactId, UUID userId,
        String displayName, String displayRole, int sortOrder, boolean signed) {}

public record AgreementVersionView(UUID id, int versionNumber, UUID submittedBy, Instant submittedAt,
        UUID lastEditedBy, String contentSha256, UUID documentVersionId, String documentSha256,
        ReviewDecision reviewDecision, UUID reviewerId, Instant reviewedAt, String reviewReason) {}

public record AgreementSignatureView(UUID id, UUID signatoryId, UUID agreementVersionId, String signedContentSha256,
        LocalDate signedOn, String method, UUID recordedBy, Instant recordedAt, UUID countersignedDocumentVersionId) {}

public record AgreementDetailView(AgreementView agreement, List<AgreementSignatoryView> signatories,
        List<AgreementVersionView> versions, List<AgreementSignatureView> signatures) {}

public record AgreementSummaryView(long draft, long underReview, long sent, long awaitingSignature,
        long signed, long expiringWithin30Days) {}

AgreementService:
  @RequirePermission(AGREEMENT_VIEW) Page<AgreementView> list(AgreementDisplayStatus status /* nullable */, Pageable p);
  @RequirePermission(AGREEMENT_VIEW) List<AgreementView> forCase(UUID caseId);   // live first, then CANCELLED newest-first
  @RequirePermission(AGREEMENT_VIEW) AgreementDetailView get(UUID id);
  @RequirePermission(AGREEMENT_VIEW) AgreementSummaryView summary();
```

`AgreementView.status` (stored) and `displayStatus` (derived) are both carried: the UI decides actions from `status` and renders the chip from `displayStatus`.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementReadTest extends PostgresTestBase {
    @Test void getReturnsTheAgreementWithSignatoriesVersionsAndSignatures() { }
    @Test void aSignedAgreementWhoseExpiryIsBeforeTodayReadsAsExpired() { }        // MutableClock
    @Test void aSignedAgreementExpiringTodayIsStillSigned() { }                    // expires_at == today -> SIGNED
    @Test void theStoredStatusStaysSignedWhenItReadsExpired() { }
    @Test void listFilteredByExpiredReturnsOnlyDerivedExpiredRows() { }
    @Test void listFilteredBySignedExcludesExpiredRows() { }
    @Test void forCaseListsLiveAgreementsBeforeCancelledOnes() { }
    @Test void summaryCountsApprovedUnderUnderReviewAndIsScopeFiltered() { }       // a TEAM viewer's counts exclude other teams
    @Test void summaryExpiringWindowIsTodayThroughTodayPlus30Inclusive() { }
    @Test void aViewerWithoutContactViewStillGetsTheDetailWithNullSignatoryNames() { }
    @Test void anOutOfScopeAgreementIsNotFound() { }
}
```

`aViewerWithoutContactView…` exists because of CLAUDE.md's finding that a nested `workflow.view` lookup 404'd the **whole** case read. Display names (signatory contact/user, customer) are resolved **best-effort**: through `AuthorizedQuery` under `CONTACT_VIEW` / `USER_VIEW` / `CUSTOMER_VIEW`, catching `NoSuchElementException` and returning `null`. A name lookup never fails the read.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Add the remaining audit constants so later tasks only use them:

```java
    public static final AuditAction AGREEMENT_SUBMITTED          = of("agreement.submitted", true);
    public static final AuditAction AGREEMENT_APPROVED           = of("agreement.approved", true);
    public static final AuditAction AGREEMENT_REJECTED           = of("agreement.rejected", true);
    public static final AuditAction AGREEMENT_SENT               = of("agreement.sent", true);
    public static final AuditAction AGREEMENT_SIGNATURE_RECORDED = of("agreement.signature_recorded", true);
    public static final AuditAction AGREEMENT_SIGNED             = of("agreement.signed", true);
    public static final AuditAction AGREEMENT_CANCELLED          = of("agreement.cancelled", true);
```

Status filter → specification: `EXPIRED` ⇒ `status = SIGNED AND expires_at < today`; `SIGNED` ⇒ `status = SIGNED AND (expires_at IS NULL OR expires_at >= today)`; anything else ⇒ `status = <same name>`. Every read goes through `authorizedQuery.findAll(agreements, Agreement.class, AGREEMENT_VIEW, spec, pageable)` / `getById`. Children of a resolved agreement use Task 3's discovery queries, fed only the resolved agreement's id.

`summary()`: add `public <T> long count(JpaSpecificationExecutor<T> repository, Class<T> entityType, String permissionKey, Specification<T> extra)` to `AuthorizedQuery`, composing `predicates.forPermission` exactly like `findAll` — **not** `countIgnoringScope` (spec §8 forbids it here). Give it a unit test in `authz/PredicateBuilderTest`'s neighbour proving it applies scope. Six counts: draft; under review = `UNDER_REVIEW` + `APPROVED`; sent; awaiting signature; signed-not-expired; expiring = `status = SIGNED AND expires_at BETWEEN today AND today.plusDays(30)`.

`today` is always `LocalDate.now(clock)`.

Create `AgreementWrites` now, empty but for its javadoc and the two helpers Task 12 fills in — every later write service shares it (no module-wide base class). It is package-private and `@Component`.

`AuthorizationCoverageTest` — add `"co.ara.onboarding.agreement.."` to the covered packages **in this commit**.

- [ ] **Step 4: Run** the test and `co.ara.onboarding.architecture.*`. Expected PASS.
- [ ] **Step 5: Commit** — `feat(agreement): agreement reads, derived expiry and the lifecycle summary`.

### Task 12: Draft editing — fields, signatories, file

**Files:**
- Modify: `agreement/AgreementService.java`, `agreement/AgreementWrites.java`
- Create: `PatchAgreementRequest.java`, `ReplaceSignatoriesRequest.java`, `SignatoryRequest.java`, `ClearableAgreementField.java`
- Test: `agreement/AgreementDraftTest.java`

**Interfaces:**
- Produces:

```java
public enum ClearableAgreementField { EFFECTIVE_DATE, EXPIRES_AT, RENEWAL_DATE, NOTICE_PERIOD_DAYS }

/**
 * PATCH: a null field is left unchanged (PatchDocumentRequest's convention). Unlike that
 * request, a draft's dates must be removable -- a wrongly entered expiry cannot be left
 * stuck -- so `clear` names fields to set back to null. A field both supplied and cleared
 * is a 400.
 */
public record PatchAgreementRequest(
        @Size(max = 200) @Pattern(regexp = "^[^\\x00-\\x1F\\x7F\"]*$") String name,
        LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate,
        @PositiveOrZero Integer noticePeriodDays,
        Set<ClearableAgreementField> clear,
        long lockVersion) {}

public record SignatoryRequest(@NotNull SignatoryKind kind, UUID contactId, UUID userId,
                               @NotBlank @Size(max = 120) String displayRole) {}

public record ReplaceSignatoriesRequest(@NotNull @Size(max = 20) List<@Valid SignatoryRequest> signatories,
                                        long lockVersion) {}

AgreementWrites (package-private):
  Agreement loadForWrite(UUID id, String permission, long lockVersion, Set<AgreementStatus> allowed);

AgreementService:
  @RequirePermission(AGREEMENT_MANAGE) AgreementDetailView patch(UUID id, PatchAgreementRequest r);
  @RequirePermission(AGREEMENT_MANAGE) AgreementDetailView replaceSignatories(UUID id, ReplaceSignatoriesRequest r);
  @RequirePermission(AGREEMENT_MANAGE) AgreementDetailView uploadDraftFile(UUID id, long lockVersion, InputStream content, long sizeBytes);
```

- [ ] **Step 1: Write the failing tests**

```java
class AgreementDraftTest extends PostgresTestBase {
    @Test void patchChangesOnlySuppliedFieldsAndSetsLastEditedBy() { }
    @Test void patchClearsANamedField() { }
    @Test void aFieldBothSuppliedAndClearedIsRefused() { }
    @Test void patchWithAStaleLockVersionIsAConflict() { }
    @Test void everyDraftWriteIsRefusedOutsideDraft() { }      // UNDER_REVIEW/APPROVED/SENT/SIGNED/CANCELLED -> IllegalStateException
    @Test void replaceSignatoriesSwapsTheWholeListInOrder() { }
    @Test void aContactOfAnotherCustomerIsNotFound() { }       // 404: a party of another customer is not a valid party here
    @Test void aRetiredContactIsRefusedAsASignatory() { }      // IllegalArgumentException
    @Test void anInternalSignatoryOutsideUserViewScopeIsNotFound() { }
    @Test void aSignatoryWhoseKindDisagreesWithItsIdsIsRefused() { }   // CONTACT carrying a userId -> 400
    @Test void theSameContactTwiceIsRefusedBeforeTheDatabaseSeesIt() { }   // 400, not a raw constraint 500
    @Test void uploadingTheFirstDraftFileCreatesTheOwnedSensitiveDocument() { }
    @Test void uploadingAgainAddsAVersionToTheSameDocument() { }
    @Test void uploadingAFileToAStructuredOnlyAgreementIsRefused() { }
    @Test void aTeamScopedManagerCanEditAnAgreementOnTheirTeamsCase() { }     // narrowest scope (CLAUDE.md)
    @Test void aWiderScopedManagerIsRefusedInsideAnOwnerOnlyStage() { }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement `AgreementWrites`** — the prologue every agreement write shares:

```java
package co.ara.onboarding.agreement;

/**
 * The shared prologue of every agreement write (AgreementService, AgreementReviewService,
 * AgreementSignatureService): resolve through AuthorizedQuery, check lock_version, require
 * a status, apply the stage write scope. Injects repositories, so AuthorizationCoverageTest's
 * finder rule covers it -- it reads only through AuthorizedQuery.
 */
@Component
class AgreementWrites {

    private final AgreementRepository agreements;
    private final CaseRepository cases;
    private final RequirementRepository requirements;
    private final MilestoneRepository milestones;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;

    // constructor assigning all seven

    Agreement loadForWrite(UUID id, String permission, long lockVersion, Set<AgreementStatus> allowed) {
        Agreement a = authorizedQuery.getById(agreements, Agreement.class, permission, id);
        if (a.getLockVersion() != lockVersion) {
            throw new ObjectOptimisticLockingFailureException(Agreement.class, id);
        }
        if (!allowed.contains(a.getStatus())) {
            throw new IllegalStateException("Agreement " + id + " is " + a.getStatus() + "; this action needs " + allowed);
        }
        applyWriteScope(a, permission);
        return a;
    }

    /** The SIGNATURE requirement's own stage governs who may write its agreement -- not the case's current stage. */
    private void applyWriteScope(Agreement a, String permission) {
        Case c = authorizedQuery.getById(cases, Case.class, permission, a.getCaseId());
        Requirement r = authorizedQuery.getById(requirements, Requirement.class, permission, a.getRequirementId());
        Milestone m = authorizedQuery.getById(milestones, Milestone.class, permission, r.getMilestoneId());
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, m.getStageId());
        writeScope.check(c, m, stage);
    }
}
```

Before relying on them: confirm `ObjectOptimisticLockingFailureException` already maps to 409 globally (`grep -rn "OptimisticLocking" backend/src/main`) — if not, Task 21's `AgreementExceptionHandler` maps it; confirm `Milestone.getStageId()` exists — if a milestone reaches its stage through its definition, follow `RequirementService.stageOf` instead.

**Implement the three service methods.**

`patch`: `loadForWrite(id, AGREEMENT_MANAGE, r.lockVersion(), EnumSet.of(DRAFT))`; reject a field both supplied and in `clear` (`IllegalArgumentException`); apply supplied fields, then clears; set `lastEditedBy` = actor, `updatedAt`; `saveAndFlush`; return `get(id)`.

`replaceSignatories`: `loadForWrite(..., EnumSet.of(DRAFT))`; reject duplicate `contactId`/`userId` and kind/id mismatches in the request with `IllegalArgumentException` **before** touching the database; then resolve **every** id before writing anything — `CONTACT` ⇒ `authorizedQuery.getById(contacts, CustomerContact.class, CONTACT_VIEW, contactId)`, require `getCustomerId().equals(a.getCustomerId())` (else `NoSuchElementException`) and `getStatus() == ACTIVE` (else `IllegalArgumentException`); `INTERNAL` ⇒ `authorizedQuery.getById(users, AppUser.class, USER_VIEW, userId)`, require active. Then `signatories.clearFor(a.getId())`, insert new rows with `sortOrder` = list index, set `lastEditedBy`, save.

`uploadDraftFile`: `loadForWrite(..., EnumSet.of(DRAFT))`; `STRUCTURED_ONLY` ⇒ `IllegalStateException`; if `a.getDocumentId() == null` call `agreementFiles.createOwnedDocument(a.getCaseId(), a.getName(), content, sizeBytes)` and store `documentId`, else `agreementFiles.addDraftVersion(a.getDocumentId(), content, sizeBytes)`; set `lastEditedBy`. Submit (Task 13) freezes whichever version is then current.

No audit action for draft edits — `agreement.submitted` records the frozen result; say so in the javadoc.

- [ ] **Step 4: Run** the test and `co.ara.onboarding.architecture.*`.
- [ ] **Step 5: Commit** — `feat(agreement): edit a draft agreement's fields, signatories and file`.

### Task 13: Submit

**Files:** Modify `agreement/AgreementService.java`, `agreement/AgreementSignatoryRepository.java`, `document/AgreementFiles.java`; test `agreement/AgreementSubmitTest.java`, `document/AgreementFilesTest.java`

**Interfaces:**
- Consumes: `AgreementContentHasher`, `AgreementSnapshot` (Task 9).
- Produces: `@RequirePermission(AGREEMENT_MANAGE) OwnedFile AgreementFiles.currentVersion(UUID documentId)`; `@RequirePermission(AGREEMENT_MANAGE) AgreementDetailView AgreementService.submit(UUID id, long lockVersion)`; discovery queries `AgreementSignatoryRepository.contactStatusOf(UUID contactId): ContactStatus` and `userStatusOf(UUID userId): UserStatus` (JPQL `select c.status from CustomerContact c where c.id = :contactId` etc.).

The status queries are discovery-only, non-`findBy*`, fed only ids already frozen onto a signatory of an agreement resolved through `AuthorizedQuery` — the `RequirementRepository.satisfiedBy` precedent. A submitter may legitimately lack `contact.view`/`user.view`, so re-resolving through `AuthorizedQuery` here would wrongly 404 a valid submit. Say so in the javadoc.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementSubmitTest extends PostgresTestBase {
    @Test void submitFreezesVersionOneWithSnapshotAndHashAndMovesToUnderReview() { }
    @Test void theVersionRecordsSubmitterAndLastEditorSeparately() { }   // edited by A, submitted by B
    @Test void aSecondSubmissionAfterRejectionIsVersionTwo() { }
    @Test void zeroSignatoriesIsRefusedWithAMessageNamingTheProblem() { }       // Review Focus 5
    @Test void aSignatoryContactRetiredSinceBeingAddedIsRefusedAtSubmit() { }   // Review Focus 5
    @Test void aFileIncludingModeWithoutAFileIsRefused() { }
    @Test void structuredModesNeedAnEffectiveDate() { }
    @Test void fileBackedDoesNotNeedAnEffectiveDate() { }
    @Test void expiryNotAfterEffectiveDateIsRefused() { }
    @Test void theVersionsContentHashEqualsTheHasherOverTheSnapshotAndFileDigest() { }
    @Test void submitIsRefusedOutsideDraft() { }
    @Test void submitRecordsAgreementSubmitted() { }
}
```

Add to `AgreementFilesTest`: `currentVersionReturnsTheLatestVersionAndItsSha256()`.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView submit(UUID id, long lockVersion) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, lockVersion, EnumSet.of(AgreementStatus.DRAFT));
        List<AgreementSignatory> parties = signatories.ofAgreement(a.getId());

        List<String> problems = new ArrayList<>();
        if (parties.isEmpty()) problems.add("an agreement needs at least one signatory");
        for (AgreementSignatory s : parties) {
            if (s.getKind() == SignatoryKind.CONTACT && signatories.contactStatusOf(s.getContactId()) != ContactStatus.ACTIVE) {
                problems.add("signatory '" + s.getDisplayRole() + "' is a contact who is no longer active");
            }
            if (s.getKind() == SignatoryKind.INTERNAL && signatories.userStatusOf(s.getUserId()) != UserStatus.ACTIVE) {
                problems.add("signatory '" + s.getDisplayRole() + "' is a user who is no longer active");
            }
        }
        if (a.getRecordMode().includesFile() && a.getDocumentId() == null) problems.add("this record mode needs a file");
        if (a.getRecordMode() != AgreementRecordMode.FILE_BACKED && a.getEffectiveDate() == null) {
            problems.add("an effective date is required");
        }
        if (a.getEffectiveDate() != null && a.getExpiresAt() != null && !a.getExpiresAt().isAfter(a.getEffectiveDate())) {
            problems.add("the expiry date must be after the effective date");
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException("Cannot submit: " + String.join("; ", problems));

        OwnedFile file = a.getRecordMode().includesFile() ? agreementFiles.currentVersion(a.getDocumentId()) : null;
        AgreementSnapshot snapshot = snapshotOf(a, parties);
        String contentSha = AgreementContentHasher.contentSha256(snapshot, file == null ? null : file.sha256());
        UUID actor = contextProvider.current().userId();
        Instant now = Instant.now(clock);

        AgreementVersion v = new AgreementVersion(Uuid7.generate(), a.getTenantId(), a.getId(),
                versions.maxVersionNumber(a.getId()) + 1, a.getRecordMode(), actor, now, a.getLastEditedBy(),
                AgreementContentHasher.canonicalJson(snapshot),
                file == null ? null : file.documentVersionId(), file == null ? null : file.sha256(), contentSha);
        versions.saveAndFlush(v);

        a.setStatus(AgreementStatus.UNDER_REVIEW);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        audit.record(AuditActions.AGREEMENT_SUBMITTED, "onboarding_case", a.getCaseId(),
                "Submitted " + a.getName() + " v" + v.getVersionNumber() + " for review",
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(v.getVersionNumber()),
                       "contentSha256", contentSha));
        return get(a.getId());
    }

    private static AgreementSnapshot snapshotOf(Agreement a, List<AgreementSignatory> parties) {
        return new AgreementSnapshot(a.getName(), a.getRecordMode(), a.getEffectiveDate(), a.getExpiresAt(),
                a.getRenewalDate(), a.getNoticePeriodDays(),
                parties.stream().map(s -> new AgreementSnapshot.SignatorySnapshot(s.getId(), s.getKind(),
                        s.getContactId(), s.getUserId(), s.getDisplayRole(), s.getSortOrder())).toList());
    }
```

Use the real enum names for contact and user status (`ContactStatus`, `UserStatus` — confirm). `get(a.getId())` re-reads under `AGREEMENT_VIEW`; every template granting `manage` grants `view`.

- [ ] **Step 4: Run** both tests.
- [ ] **Step 5: Commit** — `feat(agreement): submit a draft as an immutable, hashed version`.

### Task 14: Four-eyes review

**Files:** Create `agreement/AgreementReviewService.java`, `ReviewAgreementRequest.java`, `SelfReviewException.java`; test `agreement/AgreementReviewTest.java`

**Interfaces:**
- Produces: `public record ReviewAgreementRequest(@NotNull ReviewDecision decision, @Size(max = 2000) String reason, long lockVersion) {}`; `@RequirePermission(AGREEMENT_REVIEW) AgreementDetailView review(UUID agreementId, int versionNumber, ReviewAgreementRequest r)`; `public class SelfReviewException extends IllegalStateException { public SelfReviewException(UUID agreementId, int versionNumber) }` (409 through the global `IllegalStateException` mapping; Task 21 gives it a clearer `detail`).

- [ ] **Step 1: Write the failing tests**

```java
class AgreementReviewTest extends PostgresTestBase {
    @Test void theSubmitterCannotApproveTheirOwnVersion() { }            // SelfReviewException; still UNDER_REVIEW; no review row
    @Test void theLastEditorCannotApproveEvenIfSomeoneElseSubmitted() { }
    @Test void aThirdPersonApprovesAndTheAgreementIsApproved() { }
    @Test void rejectionNeedsAReasonAndReturnsToDraft() { }
    @Test void aRejectedVersionKeepsItsReviewAndTheNextSubmitIsANewVersion() { }
    @Test void reviewingAnythingButTheLatestVersionIsRefused() { }
    @Test void reviewIsRefusedOutsideUnderReview() { }
    @Test void aSecondDecisionOnTheSameVersionIsAConflictNotADuplicate() { }   // Review Focus 4
    @Test void theDatabaseRefusesASecondReviewRowForOneVersion() { }           // unique agreement_version_id
    @Test void aTeamScopedReviewerAtTheNarrowestScopeCanReview() { }           // hand-built role, AGREEMENT_REVIEW at TEAM
    @Test void aHolderOfManageButNotReviewIsRefused() { }
    @Test void approveAndRejectAreEachAudited() { }
}
```

For Review Focus 4, run two `fixture.runAsUser` calls in sequence with the **same** `lockVersion`: the second throws `ObjectOptimisticLockingFailureException` and exactly one review row exists. A hand-built reviewer role must grant `AGREEMENT_VIEW` and `WORKFLOW_VIEW` alongside `AGREEMENT_REVIEW` (`applyWriteScope` reads the stage under `WORKFLOW_VIEW`; `get` reads under `AGREEMENT_VIEW` — CLAUDE.md's `case.view`-needs-`workflow.view` lesson).

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
    @RequirePermission(PermissionKeys.AGREEMENT_REVIEW)
    @Transactional
    public AgreementDetailView review(UUID agreementId, int versionNumber, ReviewAgreementRequest r) {
        Agreement a = writes.loadForWrite(agreementId, PermissionKeys.AGREEMENT_REVIEW, r.lockVersion(),
                EnumSet.of(AgreementStatus.UNDER_REVIEW));
        AgreementVersion latest = versions.ofAgreementNewestFirst(a.getId()).get(0);
        if (latest.getVersionNumber() != versionNumber) {
            throw new IllegalStateException("Only the latest version (v" + latest.getVersionNumber() + ") can be reviewed");
        }
        UUID reviewer = contextProvider.current().userId();
        // Spec 5.3 / invariant 7: enforced HERE, never only in the UI.
        if (reviewer.equals(latest.getSubmittedBy()) || reviewer.equals(latest.getLastEditedBy())) {
            throw new SelfReviewException(a.getId(), latest.getVersionNumber());
        }
        if (r.decision() == ReviewDecision.REJECT && (r.reason() == null || r.reason().isBlank())) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        Instant now = Instant.now(clock);
        reviews.saveAndFlush(new AgreementVersionReview(Uuid7.generate(), a.getTenantId(), latest.getId(),
                r.decision(), reviewer, now, r.reason()));

        boolean approved = r.decision() == ReviewDecision.APPROVE;
        a.setStatus(approved ? AgreementStatus.APPROVED : AgreementStatus.DRAFT);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        audit.record(approved ? AuditActions.AGREEMENT_APPROVED : AuditActions.AGREEMENT_REJECTED,
                "onboarding_case", a.getCaseId(),
                (approved ? "Approved " : "Rejected ") + a.getName() + " v" + versionNumber,
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(versionNumber)));
        return agreementService.get(a.getId());
    }
```

- [ ] **Step 4: Run.**
- [ ] **Step 5: Commit** — `feat(agreement): four-eyes review of a submitted version`.

### Task 15: Send, and the `SignatureProvider` interface

**Files:** Create `agreement/SignatureProvider.java`, `ManualSignatureProvider.java`, `LockVersionRequest.java`; modify `AgreementService.java`; test `agreement/AgreementSendTest.java`

**Interfaces:**
- Produces:

```java
public interface SignatureProvider {
    SignatureProviderKind kind();
    /** Spec 3.4. Returns the provider's envelope id, if it creates one. MANUAL creates none. */
    Optional<String> send(Agreement agreement, AgreementVersion sentVersion);
}
public record LockVersionRequest(long lockVersion) {}

@RequirePermission(AGREEMENT_MANAGE) AgreementDetailView AgreementService.send(UUID id, long lockVersion);
```

- [ ] **Step 1: Write the failing tests**

```java
class AgreementSendTest extends PostgresTestBase {
    @Test void sendMovesAnApprovedAgreementToSentAndRecordsIt() { }
    @Test void sendRetiersTheOwnedDocumentToCompanyShared() { }
    @Test void aStructuredOnlyAgreementSendsWithNoDocumentToRetier() { }
    @Test void theManualProviderStoresNoEnvelopeId() { }
    @Test void sendIsRefusedOutsideApproved() { }
    @Test void aDoubleSendIsAConflictNotASecondTransition() { }             // Review Focus 4
    @Test void exactlyOneSignatureProviderBeanExistsAndItIsManual() { }     // no half-added OpenSign stub
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**

```java
/**
 * The only SignatureProvider (spec 3.4). send() does nothing: signatures arrive through
 * AgreementSignatureService.record, entered by staff. The OpenSign adapter is NOT
 * implemented -- deliberately deferred; see CLAUDE.md "What sub-project 5 inherits".
 */
@Component
public class ManualSignatureProvider implements SignatureProvider {
    @Override public SignatureProviderKind kind() { return SignatureProviderKind.MANUAL; }
    @Override public Optional<String> send(Agreement agreement, AgreementVersion sentVersion) { return Optional.empty(); }
}
```

`AgreementService` injects `List<SignatureProvider>` and selects by `a.getSignatureProvider()`, throwing `IllegalStateException` if none matches. Add no tenant configuration for provider choice: the column selects it and only `MANUAL` passes its CHECK.

```java
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView send(UUID id, long lockVersion) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, lockVersion, EnumSet.of(AgreementStatus.APPROVED));
        AgreementVersion sent = versions.ofAgreementNewestFirst(a.getId()).get(0);
        providerFor(a).send(a, sent).ifPresent(a::setProviderEnvelopeId);
        if (a.getDocumentId() != null) agreementFiles.retier(a.getDocumentId(), VisibilityTier.COMPANY_SHARED);
        a.setStatus(AgreementStatus.SENT);
        a.setUpdatedAt(Instant.now(clock));
        agreements.saveAndFlush(a);
        audit.record(AuditActions.AGREEMENT_SENT, "onboarding_case", a.getCaseId(),
                "Sent " + a.getName() + " v" + sent.getVersionNumber() + " for signature",
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(sent.getVersionNumber())));
        return get(a.getId());
    }
```

From here the "sent version" is always the latest: no version can be created past `DRAFT`, and `SENT` never returns to `DRAFT` (cancel makes a new agreement).

- [ ] **Step 4: Run.**
- [ ] **Step 5: Commit** — `feat(agreement): send an approved agreement through the SignatureProvider interface`.

### Task 16: Record a signature, and satisfy the requirement

**Files:** Create `agreement/AgreementSignatureService.java`, `RecordSignatureRequest.java`; test `agreement/AgreementSignatureTest.java`

**Interfaces:**
- Consumes: `journey.RequirementService.satisfy(UUID, UUID, String)`, `journey.RequirementRepository`, `AgreementFiles.addCountersignedVersion`.
- Produces: `public record RecordSignatureRequest(@NotNull UUID signatoryId, @NotNull LocalDate signedOn, @NotBlank @Size(max = 200) String method, long lockVersion) {}`; `@RequirePermission(AGREEMENT_SIGN_RECORD) AgreementDetailView record(UUID agreementId, RecordSignatureRequest r, InputStream countersigned /* nullable */, long countersignedSize)`; `public static final String SATISFIED_REF_TYPE = "AGREEMENT";`.

"Not in the future" is checked in the service against `LocalDate.now(clock)`, not with `@PastOrPresent` (which uses the JVM default zone — 3A Task 1's defect).

- [ ] **Step 1: Write the failing tests**

```java
class AgreementSignatureTest extends PostgresTestBase {
    @Test void theFirstOfTwoSignaturesMovesToAwaitingSignature() { }
    @Test void theLastSignatureMovesToSignedSetsSignedAtAndSatisfiesTheRequirement() { }
    @Test void theSatisfiedRefPointsAtTheAgreementWithRefTypeAgreement() { }
    @Test void aOneSignatoryAgreementGoesStraightFromSentToSigned() { }
    @Test void eachSignatureCitesTheSentVersionAndCopiesItsContentHash() { }
    @Test void signingTheSameSignatoryTwiceIsRefused() { }
    @Test void aSignatoryOfAnotherAgreementIsNotFound() { }
    @Test void theLastSignatureOfAFileIncludingAgreementRequiresTheCountersignedFile() { }   // 400, nothing written
    @Test void theCountersignedFileBecomesANewVersionOfTheAgreementsOwnDocument() { }
    @Test void aCountersignedFileOnANonFinalSignatureIsRefused() { }
    @Test void aStructuredOnlyAgreementRefusesAnyFile() { }
    @Test void aFutureSignedOnDateIsRefused() { }                        // MutableClock
    @Test void recordingIsRefusedBeforeSent() { }
    @Test void aWaivedRequirementIsNotResatisfiedWhenTheAgreementIsSigned() { }        // Review Focus 2
    @Test void theFinalSignatureOnAHeldCaseRollsBackCompletely() { }                   // Review Focus 1
    @Test void aSignRecordHolderWithoutMilestoneCompleteIsRefusedAtomically() { }      // Review Focus 3
    @Test void aContactRetiredAfterSendingCanStillHaveTheirSignatureRecorded() { }     // spec 5.5
}
```

Review Focus 1: after sending, put the case `ON_HOLD` through the existing hold path (as a user holding `case.hold`), record the last signature, and assert: it throws `CaseOnHoldException`; **no** `agreement_signature` row for that signatory; the agreement's status is unchanged; the requirement is still `OPEN`. Review Focus 2: waive the requirement through `RequirementService.waive` after sending, record the last signature, assert the agreement is `SIGNED` and the requirement is still `WAIVED` with its reason intact. Review Focus 3: a hand-built role with `AGREEMENT_SIGN_RECORD`, `AGREEMENT_VIEW`, `WORKFLOW_VIEW` at ALL and **no** `MILESTONE_COMPLETE` records the only signature; assert refusal and the same three nothing-changed facts. Write each fully.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
    @RequirePermission(PermissionKeys.AGREEMENT_SIGN_RECORD)
    @Transactional
    public AgreementDetailView record(UUID agreementId, RecordSignatureRequest r,
                                      InputStream countersigned, long countersignedSize) {
        Agreement a = writes.loadForWrite(agreementId, PermissionKeys.AGREEMENT_SIGN_RECORD, r.lockVersion(),
                EnumSet.of(AgreementStatus.SENT, AgreementStatus.AWAITING_SIGNATURE));
        if (r.signedOn().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("A signature cannot be dated in the future");
        }

        // Resolved as a signatory OF THIS AGREEMENT; anything else is simply not found.
        AgreementSignatory party = authorizedQuery.getById(signatories, AgreementSignatory.class,
                PermissionKeys.AGREEMENT_SIGN_RECORD, r.signatoryId());
        if (!party.getAgreementId().equals(a.getId())) throw new NoSuchElementException("Not found");

        List<AgreementSignature> existing = signatures.ofAgreement(a.getId());
        if (existing.stream().anyMatch(s -> s.getSignatoryId().equals(party.getId()))) {
            throw new IllegalStateException("That signatory's signature is already recorded");
        }
        boolean last = existing.size() + 1 == signatories.ofAgreement(a.getId()).size();

        if (countersigned != null && (!last || !a.getRecordMode().includesFile())) {
            throw new IllegalArgumentException(
                    "A countersigned file is accepted only with the last signature of an agreement that includes a file");
        }
        if (last && a.getRecordMode().includesFile() && countersigned == null) {
            throw new IllegalArgumentException("The last signature of this agreement needs the countersigned file");
        }

        AgreementVersion sent = versions.ofAgreementNewestFirst(a.getId()).get(0);
        UUID countersignedVersionId = countersigned == null ? null
                : agreementFiles.addCountersignedVersion(a.getDocumentId(), countersigned, countersignedSize).documentVersionId();

        UUID actor = contextProvider.current().userId();
        Instant now = Instant.now(clock);
        signatures.saveAndFlush(new AgreementSignature(Uuid7.generate(), a.getTenantId(), a.getId(), party.getId(),
                sent.getId(), sent.getContentSha256(), r.signedOn(), r.method(), actor, now, countersignedVersionId));
        audit.record(AuditActions.AGREEMENT_SIGNATURE_RECORDED, "onboarding_case", a.getCaseId(),
                "Recorded " + party.getDisplayRole() + "'s signature on " + a.getName(),
                Map.of("agreementId", a.getId().toString(), "signatoryId", party.getId().toString(),
                       "signedContentSha256", sent.getContentSha256()));

        a.setStatus(last ? AgreementStatus.SIGNED : AgreementStatus.AWAITING_SIGNATURE);
        if (last) a.setSignedAt(now);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        if (last) {
            // Cause before effect: agreement.signed precedes requirement.satisfied and
            // milestone.completed, which satisfy() records.
            audit.record(AuditActions.AGREEMENT_SIGNED, "onboarding_case", a.getCaseId(),
                    "Signed " + a.getName(), Map.of("agreementId", a.getId().toString()));
            satisfyIfStillOpen(a);
        }
        return agreementService.get(a.getId());
    }

    /**
     * Spec 5.6. satisfy() is idempotent only for SATISFIED -- on a WAIVED requirement it
     * would overwrite the waiver -- so the status is read first. Only a SIGNED agreement,
     * which the partial unique index makes the requirement's single live one, satisfies it.
     * Any failure inside satisfy (CaseOnHoldException, a missing milestone.complete) rolls
     * this whole method back; the countersigned blob already written is orphaned, which is
     * accepted -- BlobStore has no delete by design (sub-project 4 spec 7.1).
     */
    private void satisfyIfStillOpen(Agreement a) {
        Requirement r = authorizedQuery.getById(requirements, Requirement.class,
                PermissionKeys.AGREEMENT_SIGN_RECORD, a.getRequirementId());
        if (r.getStatus() != RequirementStatus.OPEN) return;
        requirementService.satisfy(a.getRequirementId(), a.getId(), SATISFIED_REF_TYPE);
    }
```

Confirm `RequirementStatus.OPEN` exists (`RequirementService.reopen` uses it).

- [ ] **Step 4: Run** the test, `co.ara.onboarding.journey.*` and `co.ara.onboarding.architecture.*`.
- [ ] **Step 5: Commit** — `feat(agreement): record signatures and satisfy the SIGNATURE requirement`.

### Task 17: Cancel and replace

**Files:** Modify `AgreementService.java`; create `CancelAgreementRequest.java`; test `agreement/AgreementCancelTest.java`

**Interfaces:**
- Produces: `public record CancelAgreementRequest(@NotBlank @Size(max = 2000) String reason, long lockVersion) {}`; `@RequirePermission(AGREEMENT_MANAGE) AgreementDetailView cancel(UUID id, CancelAgreementRequest r)` — returns the **successor's** detail.

- [ ] **Step 1: Write the failing tests**

```java
class AgreementCancelTest extends PostgresTestBase {
    @Test void cancellingCreatesADraftSuccessorForTheSameRequirementInOneTransaction() { }
    @Test void theSuccessorCopiesNameModeDatesAndSignatoriesButNoDocument() { }
    @Test void theSuccessorPointsBackAtWhatItReplaced() { }
    @Test void cancelNeverSatisfiesOrWaivesTheRequirement() { }        // still OPEN, satisfiedRef null
    @Test void aSignedAgreementCannotBeCancelled() { }
    @Test void aCancelledAgreementCannotBeCancelledAgain() { }
    @Test void cancellingASentAgreementRetiersItsDocumentBackToSensitive() { }
    @Test void cancellingADraftNeverSentLeavesItsDocumentSensitive() { }
    @Test void exactlyOneLiveAgreementRemainsForTheRequirement() { }
    @Test void cancelRecordsCancelledThenCreatedInThatOrder() { }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

```java
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView cancel(UUID id, CancelAgreementRequest r) {
        Agreement old = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, r.lockVersion(),
                EnumSet.of(AgreementStatus.DRAFT, AgreementStatus.UNDER_REVIEW, AgreementStatus.APPROVED,
                           AgreementStatus.SENT, AgreementStatus.AWAITING_SIGNATURE));
        boolean wasSent = old.getStatus() == AgreementStatus.SENT || old.getStatus() == AgreementStatus.AWAITING_SIGNATURE;
        UUID actor = contextProvider.current().userId();
        Instant now = Instant.now(clock);

        old.setStatus(AgreementStatus.CANCELLED);
        old.setCancelReason(r.reason());
        old.setUpdatedAt(now);
        agreements.saveAndFlush(old);   // flush BEFORE the successor insert: agreement_live_per_requirement_uq
        if (wasSent && old.getDocumentId() != null) agreementFiles.retier(old.getDocumentId(), VisibilityTier.SENSITIVE);
        audit.record(AuditActions.AGREEMENT_CANCELLED, "onboarding_case", old.getCaseId(),
                "Cancelled " + old.getName() + ": " + r.reason(), Map.of("agreementId", old.getId().toString()));

        Agreement next = new Agreement();
        next.setId(Uuid7.generate());
        next.setTenantId(old.getTenantId());
        next.setCaseId(old.getCaseId());
        next.setRequirementId(old.getRequirementId());
        next.setCustomerId(old.getCustomerId());
        next.setName(old.getName());
        next.setRecordMode(old.getRecordMode());
        next.setStatus(AgreementStatus.DRAFT);
        next.setEffectiveDate(old.getEffectiveDate());
        next.setExpiresAt(old.getExpiresAt());
        next.setRenewalDate(old.getRenewalDate());
        next.setNoticePeriodDays(old.getNoticePeriodDays());
        next.setOwnerUserId(actor);
        next.setLastEditedBy(actor);
        next.setReplacesAgreementId(old.getId());
        next.setSignatureProvider(old.getSignatureProvider());
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        agreements.saveAndFlush(next);
        for (AgreementSignatory s : signatories.ofAgreement(old.getId())) {
            AgreementSignatory copy = new AgreementSignatory();
            copy.setId(Uuid7.generate());
            copy.setTenantId(next.getTenantId());
            copy.setAgreementId(next.getId());
            copy.setKind(s.getKind());
            copy.setContactId(s.getContactId());
            copy.setUserId(s.getUserId());
            copy.setDisplayRole(s.getDisplayRole());
            copy.setSortOrder(s.getSortOrder());
            signatories.save(copy);
        }
        audit.record(AuditActions.AGREEMENT_CREATED, "onboarding_case", next.getCaseId(),
                "Created " + next.getName() + " to replace a cancelled agreement",
                Map.of("agreementId", next.getId().toString(), "replacesAgreementId", old.getId().toString()));
        // Deliberately NOTHING on RequirementService: cancel never satisfies or waives (invariant 3).
        return get(next.getId());
    }
```

- [ ] **Step 4: Run.**
- [ ] **Step 5: Commit** — `feat(agreement): cancel an agreement and replace it with a fresh draft`.

### Task 18: Cause before effect

**Files:** Modify `backend/src/test/java/co/ara/onboarding/journey/CauseBeforeEffectTest.java`

- [ ] **Step 1: Add the subsequence test** — open a case with one `STRUCTURED_ONLY` `SIGNATURE` requirement (single internal signatory, so no file) as the only requirement of its milestone; drive patch (effective date) → signatories → submit → review (a second user) → send → record; read the case's audit events oldest-first and assert, in the file's existing helper style, the subsequence `agreement.signed` → `requirement.satisfied` → `milestone.completed`, and that `case.created` precedes `agreement.created`.
- [ ] **Step 2: Run** — expected PASS given Tasks 10 and 16. Prove it can fail: temporarily move the `AGREEMENT_SIGNED` record below `satisfyIfStillOpen(a)`, see red, revert.
- [ ] **Step 3: Commit** — `test(journey): agreement.signed precedes requirement.satisfied`. Record the red run in the body.

### Task 19: The negative suite

**Files:** Create `agreement/AgreementIsolationTest.java`, `AgreementScopeTest.java`, `AgreementWriteScopeTest.java`, `AgreementIdEscalationTest.java`

- [ ] **Step 1: Write them**

```java
class AgreementIsolationTest extends PostgresTestBase {
    // An agreement in tenant A; each call made as tenant B's administrator with A's ids
    // must throw NoSuchElementException -- never succeed, never 403, never 500.
    @Test void getListForCaseAndSummaryDoNotLeakAcrossTenants() { }
    @Test void everyWriteRefusesACrossTenantAgreementId() { }   // patch, replaceSignatories, uploadDraftFile, submit, review, send, record, cancel
    @Test void aCrossTenantSignatoryContactOrUserIdIsNotFound() { }
}
class AgreementScopeTest extends PostgresTestBase {
    @Test void aTeamHolderCannotReadOrWriteAnotherTeamsAgreement() { }
    @Test void anAssignedViewerSeesOnlyAgreementsOnCasesTheyParticipateIn() { }
    @Test void aDepartmentHolderIsBoundToTheirDepartment() { }
}
class AgreementWriteScopeTest extends PostgresTestBase {
    // security.WriteScopeTest's shape: the SIGNATURE requirement's stage is OWNER_ONLY; an
    // ALL-scoped holder who is not the case owner is refused; the owner succeeds.
    @Test void manageIsRefusedInsideAnOwnerOnlyStage() { }
    @Test void reviewIsRefusedInsideAnOwnerOnlyStage() { }
    @Test void signRecordIsRefusedInsideAnOwnerOnlyStage() { }
    @Test void theCaseOwnerSucceedsInsideTheSameStage() { }
}
class AgreementIdEscalationTest extends PostgresTestBase {
    @Test void aContactOfAnotherCustomerInTheSameTenantIsNotFound() { }
    @Test void anInternalUserOutsideTheActorsUserViewScopeIsNotFound() { }
    @Test void aSignatoryIdFromAnotherAgreementIsNotFoundWhenRecording() { }
    @Test void aVersionNumberOfAnotherAgreementCannotBeReviewedHere() { }
}
```

Write every body. For `OWNER_ONLY`, rebuild the `StageRequest` from `WorkflowFixtures.stage(...)` with `WriteScope.OWNER_ONLY` (read `security.WriteScopeTest` for the precedent).

- [ ] **Step 2: Run** — expected PASS. A failure is a real escalation: fix the service, never the test, and record it in the commit body.
- [ ] **Step 3: Commit** — `test(agreement): isolation, scope, write-scope and id-escalation negatives`.

### Task 20: Portal reads

**Files:** Create `agreement/PortalAgreementService.java`, `PortalAgreementView.java`; test `agreement/AgreementPortalTest.java`

**Interfaces:**
- Produces:

```java
public record PortalAgreementView(UUID id, UUID caseId, String name, AgreementRecordMode recordMode,
        AgreementDisplayStatus displayStatus, LocalDate effectiveDate, LocalDate expiresAt, LocalDate renewalDate,
        Integer noticePeriodDays, int sentVersionNumber, String sentContentSha256, UUID documentId,
        List<PortalSignatory> signatories, Instant signedAt) {
    public record PortalSignatory(String displayRole, boolean signed, LocalDate signedOn) {}
}

PortalAgreementService:
  @RequirePermission(AGREEMENT_VIEW) List<PortalAgreementView> mine();
  @RequirePermission(AGREEMENT_VIEW) PortalAgreementView get(UUID id);
```

- [ ] **Step 1: Write the failing tests**

```java
class AgreementPortalTest extends PostgresTestBase {
    @Test void nothingIsVisibleInDraftUnderReviewOrApproved() { }
    @Test void theAgreementIsVisibleFromSentOnward() { }
    @Test void anotherCustomersAgreementIsNeverVisible() { }
    @Test void aCancelledAgreementIsNotVisible() { }
    @Test void theViewCarriesNoInternalFields() { }       // reflect over record components: no reviewer, reason, recordedBy, cancelReason, contactId, userId
    @Test void theAgreementFileIsNotDownloadableBeforeSend() { }        // DocumentContentService as the portal user -> NoSuchElementException
    @Test void theAgreementFileIsDownloadableAfterSend() { }
    @Test void theAgreementFileStopsBeingDownloadableWhenASentAgreementIsCancelled() { }
    @Test void anInternalUserCallingThePortalServiceIsNotFound() { }    // self-defending
    @Test void aRetiredContactSeesNothing() { }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Both methods first require `contextProvider.current().userType() == UserType.PORTAL`, else `NoSuchElementException` — the gated method defends itself rather than trusting its controller (CLAUDE.md "What sub-project 5 inherits", the portal-write precedent's self-defending rule). Reads use `authorizedQuery.findAll/getById(agreements, Agreement.class, AGREEMENT_VIEW, ...)`, so `AgreementAudienceFilter` narrows. Children are read by the resolved agreement's id. `sentVersionNumber`/`sentContentSha256` come from the latest version. Signatories expose display role and signed state only.
- [ ] **Step 4: Run.**
- [ ] **Step 5: Commit** — `feat(agreement): portal read endpoints behind the audience filter`.

### Task 21: Controllers, exception handler and the OpenAPI document

**Files:** Create `agreement/AgreementController.java`, `PortalAgreementController.java`, `AgreementExceptionHandler.java`; test `agreement/AgreementControllerTest.java`

**Interfaces:** Spec §8's endpoints:

| Method | Path under `/api/t/{tenantSlug}` | Body | Service call |
|---|---|---|---|
| `GET` | `/agreements?status=&page=&size=` | — | `list` |
| `GET` | `/agreements/summary` | — | `summary` |
| `GET` | `/cases/{caseId}/agreements` | — | `forCase` |
| `GET` | `/agreements/{id}` | — | `get` |
| `PATCH` | `/agreements/{id}` | `PatchAgreementRequest` | `patch` |
| `PUT` | `/agreements/{id}/signatories` | `ReplaceSignatoriesRequest` | `replaceSignatories` |
| `POST` | `/agreements/{id}/file?lockVersion=` | multipart `file` | `uploadDraftFile` |
| `POST` | `/agreements/{id}/submit` | `LockVersionRequest` | `submit` |
| `POST` | `/agreements/{id}/versions/{n}/review` | `ReviewAgreementRequest` | `review` |
| `POST` | `/agreements/{id}/send` | `LockVersionRequest` | `send` |
| `POST` | `/agreements/{id}/signatures` | multipart: `signature` (JSON `RecordSignatureRequest`) + optional `file` | `record` |
| `POST` | `/agreements/{id}/cancel` | `CancelAgreementRequest` | `cancel` |
| `GET` | `/portal/agreements`, `/portal/agreements/{id}` | — | `PortalAgreementService` |

- [ ] **Step 1: Write the failing controller tests** — MockMvc in `DocumentControllerTest`'s shape: each endpoint's success status for an authorised user; 404 for an out-of-scope id; 409 for `SelfReviewException` whose `detail` says someone else must review; 409 for a stale `lockVersion`; 400 for a validation failure; the multipart signature endpoint accepts a JSON part plus a file and rejects an oversize file with 413 (read `MultipartUploadSizeTest`). Confirm `security.DirectApiAccessTest.everyTenantScopedEndpointRejectsAnonymousAccess` picks up the new endpoints by derivation — no hand-typed list edit should be needed.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Thin controllers; `@Operation`/`@ApiResponse` documenting 404/409/400 with `ProblemDetail` exactly as `DocumentController` does, so `generated.ts` carries them. `AgreementExceptionHandler` (`@RestControllerAdvice` in `agreement`, never `platform`) maps `SelfReviewException` → 409 with `detail` "You submitted or last edited this version, so someone else must review it." and, if Task 12 found it unmapped, `ObjectOptimisticLockingFailureException` → 409 "This agreement changed since you loaded it — reload and try again."
- [ ] **Step 4: Regenerate the OpenAPI document** — `cd backend; .\gradlew.bat openApiSpec`. Expected: `build/openapi.json` lists every path above.
- [ ] **Step 5: Run the full backend suite** — `.\gradlew.bat cleanTest test`. Expected: `BUILD SUCCESSFUL`, nothing skipped.
- [ ] **Step 6: Commit** — `feat(agreement): REST and portal controllers`.

---

## Phase 4 — Frontend

**Before every task in this phase:** invoke the `frontend-design` and `ui-ux-pro-max` skills; open `Onboarding Platform.dc.html` at the relevant screen. Reuse the existing primitives in `frontend/src/components/ui/` (`Button`, `Chip`, `StatusPill`, `DataTable`, `Dialog`, `Field`, `Tabs`, `States` — `EmptyState`/`SkeletonRows`/error, `Pagination`, `Toast`). Do not add a parallel primitive. Every string through `t()` (`frontend/src/lib/i18n/messages/en.json`); ids, hashes, dates and counts in the mono data font (`var(--ob-font-family-data)`); every status colour paired with a word; cards flat.

Every frontend task's verification is **all three** of:

```powershell
cd frontend; npx vitest run; npx tsc --noEmit; npm run lint
```

Keep the dev servers running and current after each task (the user checks features in the live app between steps — memory: user workflow preferences); restart the backend after Task 21's backend changes land in the worktree.

### Task 22: Generated types and `lib/api/agreements.ts`

**Files:**
- Modify: `frontend/src/lib/api/generated.ts` (regenerated, never hand-edited)
- Create: `frontend/src/lib/api/agreements.ts`, `frontend/src/lib/api/agreements.test.tsx`

**Interfaces:** Produces the types and hooks below; every later frontend task imports from here.

- [ ] **Step 1: Regenerate types**

```powershell
cd backend; .\gradlew.bat openApiSpec; cd ..\frontend; npm run generate:api
```

Expected: `generated.ts` gains `AgreementView`, `AgreementDetailView`, `AgreementSummaryView`, `PortalAgreementView` and the request schemas. Reordering-only noise elsewhere in the diff is expected (springdoc's property order is nondeterministic).

- [ ] **Step 2: Write the failing hook tests** in `documents.test.tsx`'s shape (`fetchMock`, `reply`, `makeWrapper`, `lastUrl`):

```tsx
describe("agreements api", () => {
  it("useCaseAgreements reads /cases/{caseId}/agreements", async () => { /* assert lastUrl() ends with /cases/c1/agreements */ });
  it("useAgreement reads /agreements/{id}", async () => {});
  it("useAgreements passes status and page", async () => { /* ?status=EXPIRED&page=0&size=25 */ });
  it("useAgreementSummary reads /agreements/summary", async () => {});
  it("usePatchAgreement PATCHes and invalidates detail, case list, index and summary", async () => {});
  it("useReplaceSignatories PUTs the whole list", async () => {});
  it("useUploadAgreementFile posts multipart with lockVersion in the query", async () => {});
  it("useSubmitAgreement / useSendAgreement post {lockVersion}", async () => {});
  it("useReviewAgreement posts to /versions/{n}/review", async () => {});
  it("useRecordSignature posts a multipart with a JSON signature part and an optional file", async () => {});
  it("useCancelAgreement posts the reason and invalidates the case list", async () => {});
});
```

Write each body — assert method, URL, body shape and which query keys `invalidateQueries` was called with (spy on `client.invalidateQueries`).

- [ ] **Step 3: Run to verify failure** — `npx vitest run src/lib/api/agreements.test.tsx`.
- [ ] **Step 4: Implement**

```ts
"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/** Generated types only -- never hand-write an API type (lib/api/documents.ts' discipline). */
export type Agreement = components["schemas"]["AgreementView"];
export type AgreementDetail = components["schemas"]["AgreementDetailView"];
export type AgreementSignatory = components["schemas"]["AgreementSignatoryView"];
export type AgreementVersion = components["schemas"]["AgreementVersionView"];
export type AgreementSignature = components["schemas"]["AgreementSignatureView"];
export type AgreementSummary = components["schemas"]["AgreementSummaryView"];
export type AgreementPage = components["schemas"]["PageAgreementView"];
export type AgreementStatus = NonNullable<Agreement["status"]>;
export type AgreementDisplayStatus = NonNullable<Agreement["displayStatus"]>;
export type AgreementRecordMode = NonNullable<Agreement["recordMode"]>;
export type PatchAgreementRequest = components["schemas"]["PatchAgreementRequest"];
export type SignatoryRequest = components["schemas"]["SignatoryRequest"];
export type ReviewAgreementRequest = components["schemas"]["ReviewAgreementRequest"];
export type RecordSignatureRequest = components["schemas"]["RecordSignatureRequest"];

/** Mirrors agreement_mode_ck / AgreementRecordMode.java. Shared by the builder and the tab so the two never drift. */
export const AGREEMENT_RECORD_MODES: AgreementRecordMode[] = ["FILE_BACKED", "STRUCTURED_PLUS_FILE", "STRUCTURED_ONLY"];
export const recordModeIncludesFile = (m: AgreementRecordMode) => m !== "STRUCTURED_ONLY";

export const AGREEMENTS_PAGE_SIZE = 25;

export const agreementKeys = {
  all: ["agreements"] as const,
  index: () => [...agreementKeys.all, "index"] as const,
  summary: () => [...agreementKeys.all, "summary"] as const,
  forCase: (caseId: string) => [...agreementKeys.all, "case", caseId] as const,
  detail: (id: string) => [...agreementKeys.all, "detail", id] as const,
};

export function useAgreements(status?: AgreementDisplayStatus, page = 0) {
  return useQuery({
    queryKey: [...agreementKeys.index(), status ?? "all", page],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(AGREEMENTS_PAGE_SIZE) });
      if (status) params.set("status", status);
      return apiFetch<AgreementPage>(`/agreements?${params.toString()}`);
    },
  });
}

export function useAgreementSummary() {
  return useQuery({ queryKey: agreementKeys.summary(), queryFn: () => apiFetch<AgreementSummary>("/agreements/summary") });
}

export function useCaseAgreements(caseId: string) {
  return useQuery({
    queryKey: agreementKeys.forCase(caseId),
    queryFn: () => apiFetch<Agreement[]>(`/cases/${caseId}/agreements`),
    enabled: Boolean(caseId),
  });
}

export function useAgreement(id: string) {
  return useQuery({
    queryKey: agreementKeys.detail(id),
    queryFn: () => apiFetch<AgreementDetail>(`/agreements/${id}`),
    enabled: Boolean(id),
  });
}

/**
 * Every agreement write changes the detail, the case's list, the index and the summary
 * counts. A signature that satisfies a requirement also moves the roadmap and progress,
 * so the case itself is invalidated too.
 */
function useAgreementMutation<TVars>(
  fn: (vars: TVars) => Promise<AgreementDetail>,
  opts: { touchesCase?: boolean } = {},
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (detail) => {
      const a = detail.agreement!;
      void queryClient.invalidateQueries({ queryKey: agreementKeys.detail(a.id!) });
      void queryClient.invalidateQueries({ queryKey: agreementKeys.forCase(a.caseId!) });
      void queryClient.invalidateQueries({ queryKey: agreementKeys.index() });
      void queryClient.invalidateQueries({ queryKey: agreementKeys.summary() });
      if (opts.touchesCase) void queryClient.invalidateQueries({ queryKey: caseKeys.detail(a.caseId!) });
    },
  });
}

const json = (body: unknown) => ({ body: JSON.stringify(body), headers: { "Content-Type": "application/json" } });

export const usePatchAgreement = () =>
  useAgreementMutation(({ id, body }: { id: string; body: PatchAgreementRequest }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}`, { method: "PATCH", ...json(body) }));

export const useReplaceSignatories = () =>
  useAgreementMutation(({ id, signatories, lockVersion }: { id: string; signatories: SignatoryRequest[]; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/signatories`, { method: "PUT", ...json({ signatories, lockVersion }) }));

export const useUploadAgreementFile = () =>
  useAgreementMutation(({ id, file, lockVersion }: { id: string; file: File; lockVersion: number }) => {
    const body = new FormData();
    body.append("file", file);
    return apiFetch<AgreementDetail>(`/agreements/${id}/file?lockVersion=${lockVersion}`, { method: "POST", body });
  });

export const useSubmitAgreement = () =>
  useAgreementMutation(({ id, lockVersion }: { id: string; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/submit`, { method: "POST", ...json({ lockVersion }) }));

export const useReviewAgreement = () =>
  useAgreementMutation(({ id, versionNumber, body }: { id: string; versionNumber: number; body: ReviewAgreementRequest }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/versions/${versionNumber}/review`, { method: "POST", ...json(body) }));

export const useSendAgreement = () =>
  useAgreementMutation(({ id, lockVersion }: { id: string; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/send`, { method: "POST", ...json({ lockVersion }) }));

export const useRecordSignature = () =>
  useAgreementMutation(({ id, signature, file }: { id: string; signature: RecordSignatureRequest; file?: File }) => {
    const body = new FormData();
    body.append("signature", new Blob([JSON.stringify(signature)], { type: "application/json" }));
    if (file) body.append("file", file);
    return apiFetch<AgreementDetail>(`/agreements/${id}/signatures`, { method: "POST", body });
  }, { touchesCase: true });

export const useCancelAgreement = () =>
  useAgreementMutation(({ id, reason, lockVersion }: { id: string; reason: string; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/cancel`, { method: "POST", ...json({ reason, lockVersion }) }));
```

Check `apiFetch`'s option handling in `client.ts` (does it set `Content-Type` itself for a string body? how does `useCreateDocumentRequest` send JSON?) and match it exactly — drop the local `json` helper if `apiFetch` already handles it. Confirm `caseKeys.detail` is the real key name. Adjust the generated schema names if springdoc named a page type differently.

- [ ] **Step 5: Run the three verifications.**
- [ ] **Step 6: Commit** — `feat(frontend): agreement API hooks over the generated types`.

### Task 23: Builder `SIGNATURE` fields and the roadmap chip

**Files:**
- Modify: `frontend/src/components/workflow/MilestoneEditor.tsx` (line 13 `REQUIREMENT_KINDS`; add a `SignatureRequirementFields` beside `DocumentRequirementFields` ~line 181), `frontend/src/components/journey/RequirementList.tsx` (~line 75 kind branch), `lib/i18n/messages/en.json`
- Test: `MilestoneEditor.test.tsx`, `RequirementList.test.tsx`

**Interfaces:** Consumes `AGREEMENT_RECORD_MODES` (Task 22). The roadmap chip links to `/t/{slug}/customers/{customerId}/cases/{caseId}?tab=agreements&agreement={id}` — the id is `requirement.satisfiedRef` when `satisfiedRefType === "AGREEMENT"`; otherwise the chip links to the tab with no `agreement` param (the live agreement is found there).

- [ ] **Step 1: Write the failing tests**

```tsx
// MilestoneEditor.test.tsx
it("offers SIGNATURE as a requirement kind", () => {});
it("shows record-mode and agreement-name fields only for a SIGNATURE requirement", () => {});
it("writes agreementRecordMode and agreementName into the requirement patch", () => {});
it("clears the agreement fields when the kind changes away from SIGNATURE", () => {});   // or publish Rule 6 refuses a stray value

// RequirementList.test.tsx
it("renders a SIGNATURE requirement as a chip linking to the Agreements tab", () => {});
it("links a satisfied SIGNATURE requirement to the agreement that satisfied it", () => {});
it("never renders a checkbox for a SIGNATURE requirement", () => {});   // it is satisfied only by signing
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** `REQUIREMENT_KINDS` gains `"SIGNATURE"`. `SignatureRequirementFields` mirrors `DocumentRequirementFields`: a `<select>` over `AGREEMENT_RECORD_MODES` labelled with `t("agreement.recordMode." + mode)` and a text input for the name (max 200). When `update` changes `kind` away from `SIGNATURE`, set both fields to `undefined` in the same patch — a stray value would fail publish Rule 6 with a message the author cannot see the cause of. Check `draftState.ts` for where the requirement shape is defaulted and add the two fields there.

`RequirementList`: a `SIGNATURE` requirement renders a `SignatureChip` (model it on the existing `DocumentChip` in the same file — read it) showing the label, the requirement's status word, and a link as described above. No checkbox, no waive button change (waiving stays available through the existing control where it already appears).

i18n keys: `workflow.requirement.kind.SIGNATURE`, `workflow.requirement.agreementRecordMode`, `workflow.requirement.agreementName`, `agreement.recordMode.FILE_BACKED` ("File-backed"), `agreement.recordMode.STRUCTURED_PLUS_FILE` ("Structured + file"), `agreement.recordMode.STRUCTURED_ONLY` ("Structured record only"), `requirement.signature.open` ("Open agreement").

- [ ] **Step 4: Run the three verifications.**
- [ ] **Step 5: Commit** — `feat(frontend): SIGNATURE requirement fields in the builder and a roadmap chip`.

### Task 24: The case Agreements tab — list and rows

**Files:**
- Create: `frontend/src/components/journey/AgreementsTab.tsx` (+ `.test.tsx`), `frontend/src/components/agreements/AgreementRow.tsx` (+ test), `frontend/src/components/agreements/statusChip.ts`
- Modify: `frontend/src/app/(app)/t/[slug]/customers/[id]/cases/[caseId]/page.tsx:148` (replace the `EmptyState` stub with `<AgreementsTab caseId={caseId} />`), `en.json` (replace `case.tabs.agreements.empty`)

**Interfaces:**
- Produces: `statusTone(s: AgreementDisplayStatus): "neutral" | "info" | "warn" | "success" | "danger"` and `statusLabelKey(s)`; `AgreementsTab({ caseId })` reads `?agreement=` from the URL to open a detail panel (Task 25 builds the panel; this task renders a placeholder slot that Task 25 fills).

Status → tone (colour means status, never decoration): `DRAFT` neutral, `UNDER_REVIEW`/`APPROVED` info, `SENT`/`AWAITING_SIGNATURE` warn, `SIGNED` success, `EXPIRED` danger, `CANCELLED` neutral. Check which tone names `Chip`/`StatusPill` actually accept and map to those.

- [ ] **Step 1: Write the failing tests**

```tsx
it("renders one row per live agreement with its name, meta line and status chip", () => {});
it("the meta line states version, record mode and signing", () => {});   // "v3 · file-backed · signed 14 Aug 2026 via manual record"
it("a structured-only agreement's meta says no file is required", () => {});
it("collapses cancelled agreements under 'Replaced (n)'", () => {});
it("shows 'Expires in 12d' when a signed agreement expires within 30 days", () => {});
it("shows the EXPIRED chip for a derived-expired agreement", () => {});
it("renders the empty state when the case has no agreements", () => {});
it("renders a skeleton while loading and an error state on failure", () => {});
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Row shape per SCREENS "Other tabs": 30×34 radius-5 tile showing `§` on `var(--ob-automation-bg)` (confirm the token name in `DESIGN_TOKENS.md`/the codebase's token layer), name in UI font, meta line in mono, a status chip (word + tone), an `Open` button that sets `?agreement={id}`. Meta pieces: `v{latestVersionNumber}` (omit while 0), `t("agreement.recordMode." + recordMode)` lower-cased as the design shows, and for `SIGNED`/`EXPIRED` "signed {signedAt, formatted d MMM yyyy} via manual record". Empty state copy: "No agreements on this journey — a SIGNATURE requirement in the workflow creates one automatically."
- [ ] **Step 4: Run the three verifications.**
- [ ] **Step 5: Commit** — `feat(frontend): the case Agreements tab`.

### Task 25: The agreement detail panel — draft editing

**Files:**
- Create: `frontend/src/components/agreements/AgreementDetailPanel.tsx`, `DraftEditor.tsx`, `SignatoryEditor.tsx`, `VersionHistory.tsx`, `SignatureList.tsx` (each + `.test.tsx`)
- Modify: `AgreementsTab.tsx` (mount the panel for `?agreement=`)

**Interfaces:** Consumes `useAgreement`, `usePatchAgreement`, `useReplaceSignatories`, `useUploadAgreementFile`; contacts of the case's customer from the existing contacts hook in `lib/api/customers.ts`; users from `lib/api/admin.ts`'s user list hook (shown only when the viewer holds `user.view` — otherwise the internal-signatory option is hidden, matching the backend 404).

- [ ] **Step 1: Write the failing tests**

```tsx
it("shows editable name, dates and notice period only while DRAFT and the viewer holds agreement.manage", () => {});
it("saving sends only changed fields plus lockVersion, and clearing a date sends it in `clear`", () => {});
it("the signatory editor adds, reorders and removes, then PUTs the whole list", () => {});
it("offers only this customer's active contacts as contact signatories", () => {});
it("hides the internal-signatory option without user.view", () => {});
it("the file control appears only for record modes that include a file", () => {});
it("version history shows each version's short hash in mono and its review outcome", () => {});
it("the signature list shows signed and pending signatories with date and method", () => {});
it("a 409 from a stale lockVersion shows the reload message and refetches", () => {});
it("everything is read-only outside DRAFT", () => {});
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** The panel is a side panel/drawer consistent with the existing detail patterns (read how `DocumentsTab` or `TaskDetail` presents detail and follow it). Short hash = first 12 hex characters of `contentSha256`, full value in a `title` attribute. Reuse `UploadDialog`'s file-picking internals if they are separable; otherwise a plain `<input type="file">` inside a `Field`. Permission checks through `useHasPermission("agreement.manage")`. Every date input is a `<input type="date">` bound to an ISO `yyyy-MM-dd` string — no timezone conversion (dates, not instants).
- [ ] **Step 4: Run the three verifications.**
- [ ] **Step 5: Commit** — `feat(frontend): agreement detail panel with draft editing`.

### Task 26: Lifecycle actions — submit, review, send, record signature, cancel

**Files:**
- Create: `frontend/src/components/agreements/AgreementActions.tsx`, `ReviewAgreementDialog.tsx`, `RecordSignatureDialog.tsx`, `CancelAgreementDialog.tsx` (each + `.test.tsx`)
- Modify: `AgreementDetailPanel.tsx`

**Interfaces:** Consumes `useSubmitAgreement`, `useReviewAgreement`, `useSendAgreement`, `useRecordSignature`, `useCancelAgreement`, `useAuth().user.id`.

- [ ] **Step 1: Write the failing tests**

```tsx
it("DRAFT shows Submit for review to a manage holder", () => {});
it("UNDER_REVIEW shows Approve and Reject to a review holder", () => {});
it("Approve and Reject are disabled with an explanation for the submitter", () => {});   // "You submitted this version, so someone else must review it"
it("Approve and Reject are disabled with an explanation for the last editor", () => {});
it("Reject requires a reason before it can be sent", () => {});
it("APPROVED shows Send", () => {});
it("SENT and AWAITING_SIGNATURE show Record signature, listing only unsigned signatories", () => {});
it("the last signatory of a file-including agreement requires the countersigned file", () => {});
it("signedOn cannot be set in the future", () => {});
it("Cancel and replace appears before SIGNED, requires a reason, and opens the successor afterwards", () => {});
it("SIGNED and EXPIRED show no lifecycle actions", () => {});
it("a server 409 SelfReview detail is shown verbatim", () => {});
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** The four-eyes disabled state is computed from the latest version's `submittedBy`/`lastEditedBy` against `useAuth().user.id` — shown, not hidden, so the rule is visible — but the server is the guard (Task 14); the UI never assumes success. `RecordSignatureDialog`: signatory `<select>` of unsigned signatories, `signedOn` date input with `max` = today in local date, `method` text input with suggestions ("Wet ink", "Signed PDF returned by email"), and a required file input when this is the last signatory and `recordModeIncludesFile(recordMode)`. After cancel, set `?agreement={successorId}` from the mutation's returned detail. Dialogs use the existing `Dialog` primitive.
- [ ] **Step 4: Run the three verifications.**
- [ ] **Step 5: Commit** — `feat(frontend): agreement lifecycle actions with the four-eyes rule visible`.

### Task 27: The operator `agreements` screen and its nav entry

**Files:**
- Create: `frontend/src/app/(app)/t/[slug]/agreements/page.tsx` (+ `page.test.tsx`), `frontend/src/components/agreements/LifecycleCard.tsx`, `AgreementTable.tsx` (each + test)
- Modify: `frontend/src/components/shell/Sidebar.tsx` (~line 100 permissions, ~line 142 items) and `Sidebar.test.tsx`, `en.json`

**Interfaces:** Consumes `useAgreements`, `useAgreementSummary`.

- [ ] **Step 1: Write the failing tests**

```tsx
// Sidebar.test.tsx
it("shows Agreements to an agreement.view holder", () => {});
it("hides Agreements from a portal user even holding agreement.view", () => {});   // portal actors resolve agreement.view at ALL
it("hides Agreements without agreement.view", () => {});

// page.test.tsx / components
it("the lifecycle card shows six counts with their labels", () => {});   // Draft · Under review · Sent · Awaiting signature · Signed · Expiring ≤30d
it("each count's bar width is min(100, n × 1.9)%", () => {});
it("the eyebrow says AGREEMENT LIFECYCLE · MANUAL SIGNING", () => {});
it("the table has Agreement / Customer / Record mode / Owner / Status columns", () => {});
it("filtering by status refetches with ?status=", () => {});
it("a row links to the case Agreements tab with the agreement open", () => {});
it("falls back to a card list below 900px", () => {});
it("renders empty, loading and error states", () => {});
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** per SCREENS §8. Table grid `1.5fr 1fr 1.2fr .8fr 1.5fr`; record-mode cell phrasing `File-backed · v3` / `Structured + file · v2` / `Structured record only`; status cell = chip + an 11px `text-subtle` date phrase ("signed 14 Aug", "expires in 12d", "sent 3 Sep"). Customer name from `customerName` (may be null when the viewer lacks `customer.view` — render "—"). Owner — resolve through the existing user lookup if the viewer holds `user.view`, else show nothing rather than an id. Sidebar: `const canViewAgreements = useHasPermission("agreement.view") && user?.userType !== "PORTAL";` placed after Documents, icon from the existing icon set (pick a neutral document-like icon already imported elsewhere; do not add an icon library). The row link needs the customer id: `AgreementView.customerId` + `caseId` give `/t/{slug}/customers/{customerId}/cases/{caseId}?tab=agreements&agreement={id}`.
- [ ] **Step 4: Run the three verifications.**
- [ ] **Step 5: Commit** — `feat(frontend): the agreements lifecycle screen`.

---

## Phase 5 — Verification and close-out

### Task 28: `agreements.spec.ts`

**Files:** Create `frontend/e2e/agreements.spec.ts`

- [ ] **Step 1: Write the spec** in the shape of `customer-plan.spec.ts` and `documents`' e2e specs (provision a tenant per file, read the activation token from `e2e/.artifacts/backend.log`, seed through the API with a bearer token). Seed payloads must spell out every field that NPEs or misbinds when omitted (CLAUDE.md "Live-running the three new specs"): `attributes: []`, `autoAdvance: true`, `branchRules: []`, `dependsOnMilestoneKeys: []`, `estimatedDurationDays`, and now `agreementRecordMode` + `agreementName` on the `SIGNATURE` requirement. Capture `lockVersion` from each response rather than assuming 0.

Scenarios:
1. **The full arc.** Workflow: stage "Agreement" → milestone "Contract" with one `SIGNATURE` requirement, `FILE_BACKED`, name "Master Services Agreement". Open a case. As the project manager: open the Agreements tab, open the agreement, upload a PDF, add a contact signatory and an internal signatory, submit. Assert Approve is **disabled** with the explanation. Log in as a Legal user (create via API, role template "Legal"), approve. As the manager: send; record the contact's signature; record the internal signatory's signature with the countersigned PDF. Assert the chip reads `SIGNED`, the roadmap milestone is complete, and the progress bar reached 100%.
2. **Cancel and replace.** On a second case, submit then cancel with a reason; assert a new `DRAFT` row appears, the old one sits under "Replaced (1)", and the requirement is still open on the roadmap.
3. **Lifecycle screen.** Visit `/t/{slug}/agreements`; assert the Signed count ≥ 1 and the Draft count ≥ 1, and the eyebrow text.

Use `.click()` followed by a retrying `expect(...)` for any control whose state waits on a round trip — never `.check()`/`.fill()`-and-assume (CLAUDE.md).

- [ ] **Step 2: Run** against a scratch database: `$env:DB_URL = "jdbc:postgresql://localhost:5432/onboarding_scratch"; npx playwright test e2e/agreements.spec.ts`. Expected: PASS. A failure is diagnosed with `superpowers:systematic-debugging`; a product bug gets its own unit test first. Never weaken an assertion.
- [ ] **Step 3: Run the whole Playwright suite** — expected all specs pass.
- [ ] **Step 4: Commit** — `test(e2e): agreement lifecycle arc`.

### Task 29: Whole-branch verification and close-out

**Files:** Modify `CLAUDE.md`; create `.superpowers/sdd/2026-09-25-agreements/task-29-report.md`

- [ ] **Step 1: Run all three suites in one pass** — `.\gradlew.bat cleanTest test`; `npx vitest run; npx tsc --noEmit; npm run lint`; `npx playwright test`. Record each summary line (not a pinned count).
- [ ] **Step 2: Verify the ten invariants against the code**, one line of evidence each, in the report:
  1. `ModuleBoundaryTest.noJourneyDependencyOnAgreement` / `noDocumentDependencyOnAgreement` green (Task 4 red runs cited).
  2. `grep -rn "CaseEngine\|reconcile" backend/src/main/java/co/ara/onboarding/agreement` returns nothing; `CaseEngine.java` has no commit on this branch (`git log --oneline main.. -- backend/src/main/java/co/ara/onboarding/journey/CaseEngine.java`).
  3. `AgreementCancelTest.cancelNeverSatisfiesOrWaivesTheRequirement`.
  4. `AgreementSchemaTest.aSecondLiveAgreementForTheSameRequirementIsRejected`.
  5. The `GRANT SELECT, INSERT` lines in the agreement migration + `AgreementSchemaTest`'s three grant tests.
  6. `AgreementSignatureTest.eachSignatureCitesTheSentVersionAndCopiesItsContentHash`.
  7. `AgreementReviewTest`'s two refusal tests.
  8. `AgreementAudienceFilterTest` + `AgreementPortalTest`'s file-download tests.
  9. `grep -n "EXPIRED" backend/src/main/resources/db/migration/*agreement*.sql` finds only the comment; `AgreementReadTest.theStoredStatusStaysSignedWhenItReadsExpired`.
  10. `AgreementIsolationTest`, and a reflection test that `ReplaceSignatoriesRequest`'s `SignatoryRequest` fields all appear on `AgreementSignatoryView`.
- [ ] **Step 3: Request the whole-branch review** with `superpowers:requesting-code-review` (security focus: every id from a request resolved through `AuthorizedQuery`; the portal never sees a pre-`SENT` agreement or file; four-eyes enforced server-side; no path satisfies a `WAIVED` requirement). Fix findings with their own tests.
- [ ] **Step 4: Update CLAUDE.md** — add "**Sub-project 5 delivered:**" after 3A's paragraph; add "**Sub-project 5's own ten**" to the invariants section and the new negatives to "Where the guards live"; **keep the "OpenSign is NOT implemented" bullet** under "What sub-project 5 inherits" (it stays true); add "Open at the close of sub-project 5" with spec §11.1's `MigrationService` observation for `DOCUMENT`/`TASK` requirements and anything the review deferred. Keep it dense.
- [ ] **Step 5: Commit** — `docs: record sub-project 5's close`.
- [ ] **Step 6: Stop.** Pushing, opening a PR and merging are each the user's own separate instruction (memory: user workflow preferences). Report what is ready and wait.

---

## Plan self-review

**Spec coverage.** §2.1 items → Tasks: `SIGNATURE` kind 2; tables 3; lifecycle 12–17; provider 15; permissions/descriptors/filter/roles 5–6; `AgreementFiles` 7–8, 13; portal 20; frontend 22–27. §3.2 port and migration call → 10. §4.4.1 hash → 9. §5.6 guards → 16 (waived, held, missing `milestone.complete`), 17 (cancel). §5.7 derived expiry → 11. §5.8 cancel revokes portal file access → 17, 20. §5.9 audit → 10, 11, 18. §6.3 amendment → 5. §8 API → 21. §9 screens → 23–27. §10 invariants → 29. §11.3 OpenSign note → kept in CLAUDE.md (Task 29 Step 4).

**Deliberate deviations from the spec, recorded there too:** two migrations instead of one (spec §4 amended); `agreement_signatory` gets `GRANT DELETE` for `DRAFT` list replacement (Global Constraints — not previously stated in the spec; amend §4.3 in Task 3's commit); `PatchAgreementRequest.clear` (spec §8 says `PATCH`; the clear set is how a date is removed); `AuthorizedQuery.count` added (Task 11) because `countIgnoringScope` is forbidden for the summary.

**Type consistency checked:** `AgreementWrites.loadForWrite(UUID, String, long, Set<AgreementStatus>)` used identically in Tasks 12–17; `OwnedFile(documentId, documentVersionId, versionNumber, sha256)` in Tasks 8, 13, 16; `SATISFIED_REF_TYPE = "AGREEMENT"` in Task 16 and the roadmap link in Task 23; `AgreementDisplayStatus` in Tasks 11, 20, 22, 24; `RequirementRequest` 9-arg order `(kind, label, weight, mandatory, documentCategory, approverRelationship, requiresReview, agreementRecordMode, agreementName)` in Tasks 2 and 10.
