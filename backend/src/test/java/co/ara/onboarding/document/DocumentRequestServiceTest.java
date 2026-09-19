package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 23 (design spec 4.5/5.2/5.3): the plan names {@code DocumentRequestService}
 * ("Files") but its own "Interfaces" line only ever specifies {@code create}
 * and {@code withdraw} -- {@code fulfil} is Task 25's own job, not built or
 * tested here.
 *
 * <p>Every id this class receives from a URL or a request body ({@code caseId},
 * {@code requestId}, {@code requestedOfContactId}) is resolved through
 * {@link co.ara.onboarding.authz.AuthorizedQuery} before anything is written --
 * the identical write-path invariant {@link DocumentSharingServiceTest}'s own
 * class javadoc already proves for {@code document.share}, exercised here for
 * {@code document.request} instead.
 */
class DocumentRequestServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentRequestService requests;
    @Autowired DocumentRequestRepository requestRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired RequirementRepository requirementRepository;
    @Autowired RoleService roles;
    @Autowired CaseService cases;

    @Test
    void anAdHocRequestCarriesANullRequirementId() {
        UUID tenant = fixture.createTenant("doc-req-adhoc-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            caseId[0] = journey.newCase(tenant).getId();
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () -> view[0] = requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.CONTRACT, "Please supply the signed MSA",
                        null, false, null)));

        assertThat(view[0].requirementId()).isNull();
        assertThat(view[0].status()).isEqualTo(DocumentRequestStatus.OPEN);
        assertThat(view[0].requestedBy()).isEqualTo(manager[0]);
        assertThat(view[0].requestedOfContactId()).isNull();

        fixture.runAs(tenant, () -> {
            DocumentRequest persisted = requestRepository.findById(view[0].id()).orElseThrow();
            assertThat(persisted.getRequirementId()).isNull();
            assertThat(persisted.getCaseId()).isEqualTo(caseId[0]);
        });
    }

    @Test
    void requestedOfContactIdIsResolvedThroughAuthorizedQueryBeforeItIsWritten() {
        UUID tenant = fixture.createTenant("doc-req-contact-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var contactId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-contact-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            contactId[0] = fixture.createContact(tenant, c.getCustomerId(), "kyc+" + Uuid7.generate() + "@example.com");
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () -> view[0] = requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.KYC, "Please supply proof of ID",
                        Instant.now(), true, contactId[0])));

        assertThat(view[0].requestedOfContactId()).isEqualTo(contactId[0]);
        assertThat(view[0].requiresReview()).isTrue();
    }

    /**
     * A contact id that does not exist at all (never mind another tenant) is
     * refused as a 404 -- {@code AuthorizedQuery.getById} throws
     * {@link NoSuchElementException} for both "does not exist" and "exists but
     * out of scope" alike, the identical shape {@code DocumentSharingService
     * #resolveContact} already relies on.
     */
    @Test
    void aNonexistentContactIdIsRefusedAsNotFound() {
        UUID tenant = fixture.createTenant("doc-req-contact-404-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-404-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            caseId[0] = journey.newCase(tenant).getId();
        });

        UUID bogusContactId = Uuid7.generate();
        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () -> requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, null, null, false, bogusContactId))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * A confused-deputy cross-reference guard, the identical shape
     * {@code DocumentSharingService#resolveContact} already carries: a contact
     * that genuinely exists and is genuinely visible to the actor, but belongs
     * to a DIFFERENT customer than the case being requested against, is a 400 --
     * never the actor's own scope, and never conflated with the 404 an absent
     * or out-of-scope id gets.
     */
    @Test
    void aContactBelongingToADifferentCustomerThanTheCaseIsRefusedAsABadRequest() {
        UUID tenant = fixture.createTenant("doc-req-contact-mismatch-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var otherContactId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-mismatch-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            caseId[0] = journey.newCase(tenant).getId();

            UUID otherCustomerId = fixture.createCustomer(
                    tenant, "Other Customer " + Uuid7.generate(), null, null, null);
            otherContactId[0] = fixture.createContact(
                    tenant, otherCustomerId, "other-cust+" + Uuid7.generate() + "@example.com");
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () -> requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, null, null, false, otherContactId[0]))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * CLAUDE.md's own convention: "wherever a permission is catalogued at
     * several scopes, at least one write test must run at the narrowest one."
     * {@code document.request} is catalogued ALL/DEPARTMENT/TEAM
     * ({@code PermissionCatalog.ORG_SCOPES}, no ASSIGNED), so TEAM is the
     * narrowest. The case carries no current stage, so
     * {@link co.ara.onboarding.journey.StageWriteScopeGuard} has nothing to
     * narrow against and this proves scope resolution alone -- the identical
     * construction {@code DocumentSharingServiceTest
     * .aTeamScopedDocumentShareHolderCanShareADocumentOwnedByTheirOwnTeam} uses.
     */
    @Test
    void aTeamScopedDocumentRequestHolderCanRequestOnACaseOwnedByTheirOwnTeam() {
        UUID tenant = fixture.createTenant("doc-req-team-scope-" + Uuid7.generate());
        var teamScopedActor = new UUID[1];
        var caseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopedActor[0] = fixture.createUser(tenant, "team-req+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopedActor[0], team);
            grant(teamScopedActor[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.TEAM));

            caseId[0] = journey.newCase(tenant, null, null, team).getId();
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, teamScopedActor[0], () -> view[0] = requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, "Team-scoped ad-hoc request",
                        null, false, null)));

        assertThat(view[0].caseId()).isEqualTo(caseId[0]);
    }

    /**
     * The {@code write_scope} guard applies to {@code create} exactly as it
     * does to every other write in this module -- a TEAM-scoped
     * {@code document.request} holder, matching the case's own team, is still
     * refused inside an {@code OWNER_ONLY} stage when they are not the case's
     * owner, the same construction {@code DocumentSharingServiceTest
     * .aTeamScopedShareHolderIsStillRefusedInsideAnOwnerOnlyStage} uses.
     */
    @Test
    void aTeamScopedRequestHolderIsStillRefusedInsideAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("doc-req-ws-owner-only-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var caseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "req-ws-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "req-ws-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Request Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () -> requests.create(caseId[0],
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, null, null, false, null))))
                .isInstanceOf(WriteScopeException.class);
    }

    @Test
    void withdrawSetsStatusToWithdrawnAndIsIdempotentOnASecondCall() {
        UUID tenant = fixture.createTenant("doc-req-withdraw-" + Uuid7.generate());
        var manager = new UUID[1];
        var requestId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-withdraw-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            UUID caseId = journey.newCase(tenant).getId();
            requestId[0] = createRequestRow(tenant, caseId, null, manager[0]);
        });

        var first = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                first[0] = requests.withdraw(requestId[0], "No longer needed"));
        assertThat(first[0].status()).isEqualTo(DocumentRequestStatus.WITHDRAWN);

        // Idempotent -- the identical shape DocumentSharingService.revokeShare
        // already proves for a share: a request already withdrawn is left
        // exactly as it was, never re-processed.
        var second = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                second[0] = requests.withdraw(requestId[0], "Still no longer needed"));
        assertThat(second[0].status()).isEqualTo(DocumentRequestStatus.WITHDRAWN);
    }

    /**
     * Design spec 5.2: "A WITHDRAWN request never satisfies its requirement --
     * the same rule as sub-project 3's 'a cancelled task never satisfies or
     * waives its requirement', and for the same reason." Fulfilment
     * ({@code RequirementService.satisfy}) does not exist yet in this module
     * (Task 25), so this proves the negative the only way currently possible:
     * a request linked to a real, still-OPEN {@link Requirement} is withdrawn,
     * and that requirement's own status is completely untouched -- withdraw
     * calls nothing on {@code RequirementService} and never reaches
     * {@code CaseEngine.reconcile}.
     */
    @Test
    void withdrawingARequestNeverSatisfiesItsLinkedRequirement() {
        UUID tenant = fixture.createTenant("doc-req-withdraw-ns-" + Uuid7.generate());
        var manager = new UUID[1];
        var requestId = new UUID[1];
        var requirementId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-ns-manager+" + Uuid7.generate() + "@example.com");
            // WORKFLOW_VIEW is needed too -- this case has a real currentStageId
            // (pinned to publishedThreeStageWorkflow()), and applyWriteScope
            // resolves that Stage under WORKFLOW_VIEW before withdraw's own body
            // proceeds -- the same shape DocumentServiceTest.retiringReopensARequirementItSatisfied
            // documents for retire.
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Withdraw NS Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();

            // Requirement-instantiated shape (Task 24's own future job to create
            // for real) -- built directly here since only the ad-hoc create()
            // path exists in this task.
            requestId[0] = createRequestRow(tenant, caseId, requirementId[0], manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () -> requests.withdraw(requestId[0], "Requirement satisfied another way"));

        fixture.runAs(tenant, () -> {
            DocumentRequest dr = requestRepository.findById(requestId[0]).orElseThrow();
            assertThat(dr.getStatus()).isEqualTo(DocumentRequestStatus.WITHDRAWN);

            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
        });
    }

    /**
     * A request already {@code FULFILLED} is a terminal state -- the same
     * "any open state may cancel, but a completed one cannot" shape
     * {@code task.TaskService}'s own transition map already enforces for
     * {@code TaskStatus.COMPLETED}. {@code document_request_fulfilled_ck}
     * requires a real {@code fulfilled_document_id} whenever status is
     * FULFILLED, so a real {@link Document} row is seeded to satisfy that FK.
     */
    @Test
    void withdrawingAnAlreadyFulfilledRequestIsRefused() {
        UUID tenant = fixture.createTenant("doc-req-withdraw-fulfilled-" + Uuid7.generate());
        var manager = new UUID[1];
        var requestId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-fulfilled-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Withdraw Fulfilled Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            UUID documentId = createDocument(tenant, c.getId(), c.getCustomerId(), manager[0]);
            requestId[0] = createFulfilledRequestRow(tenant, c.getId(), manager[0], documentId);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                requests.withdraw(requestId[0], "Too late")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withdrawRefusesABlankReason() {
        UUID tenant = fixture.createTenant("doc-req-withdraw-blank-" + Uuid7.generate());
        var manager = new UUID[1];
        var requestId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "req-blank-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            UUID caseId = journey.newCase(tenant).getId();
            requestId[0] = createRequestRow(tenant, caseId, null, manager[0]);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () -> requests.withdraw(requestId[0], "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrossTenantRequestIdAnswers404NeverForbidden() {
        UUID tenantA = fixture.createTenant("doc-req-xt-a-" + Uuid7.generate());
        var managerA = new UUID[1];
        var requestId = new UUID[1];

        fixture.runAs(tenantA, () -> {
            managerA[0] = fixture.createUser(tenantA, "req-xt-a+" + Uuid7.generate() + "@example.com");
            grant(managerA[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
            UUID caseId = journey.newCase(tenantA).getId();
            requestId[0] = createRequestRow(tenantA, caseId, null, managerA[0]);
        });

        UUID tenantB = fixture.createTenant("doc-req-xt-b-" + Uuid7.generate());
        var managerB = new UUID[1];
        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "req-xt-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () ->
                requests.withdraw(requestId[0], "Not mine")))
                .isInstanceOf(NoSuchElementException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createRequestRow(UUID tenant, UUID caseId, UUID requirementId, UUID requestedBy) {
        DocumentRequest dr = new DocumentRequest();
        dr.setId(Uuid7.generate());
        dr.setTenantId(tenant);
        dr.setCaseId(caseId);
        dr.setRequirementId(requirementId);
        dr.setCategory(DocumentCategory.OTHER);
        dr.setRequiresReview(false);
        dr.setStatus(DocumentRequestStatus.OPEN);
        dr.setRequestedBy(requestedBy);
        dr.setRequestedAt(Instant.now());
        return requestRepository.saveAndFlush(dr).getId();
    }

    private UUID createFulfilledRequestRow(UUID tenant, UUID caseId, UUID requestedBy, UUID fulfilledDocumentId) {
        DocumentRequest dr = new DocumentRequest();
        dr.setId(Uuid7.generate());
        dr.setTenantId(tenant);
        dr.setCaseId(caseId);
        dr.setCategory(DocumentCategory.OTHER);
        dr.setRequiresReview(false);
        dr.setStatus(DocumentRequestStatus.FULFILLED);
        dr.setFulfilledDocumentId(fulfilledDocumentId);
        dr.setRequestedBy(requestedBy);
        dr.setRequestedAt(Instant.now());
        return requestRepository.saveAndFlush(dr).getId();
    }

    private UUID createDocument(UUID tenant, UUID caseId, UUID customerId, UUID uploadedBy) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(caseId);
        d.setCustomerId(customerId);
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
