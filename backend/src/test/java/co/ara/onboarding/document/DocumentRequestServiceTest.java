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
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

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
 * Task 23 (design spec 4.5/5.2/5.3): {@code create} and {@code withdraw}.
 * Task 25 adds {@code fulfil} below -- resolving both {@code requestId}
 * (under {@code document.request}) and {@code documentId} (under {@code
 * document.view}, composed with the {@code document.request} write gate,
 * the same shape {@link DocumentSharingService#resolveContact} already
 * establishes composing {@code contact.view} with {@code document.share})
 * before anything is written, satisfying the linked requirement -- through
 * the existing gated {@code journey.RequirementService#satisfy} -- only
 * when {@code requiresReview} is false.
 *
 * <p>Every id this class receives from a URL or a request body ({@code caseId},
 * {@code requestId}, {@code requestedOfContactId}, {@code documentId}) is
 * resolved through {@link co.ara.onboarding.authz.AuthorizedQuery} before
 * anything is written -- the identical write-path invariant {@link
 * DocumentSharingServiceTest}'s own class javadoc already proves for {@code
 * document.share}, exercised here for {@code document.request} instead.
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

    /**
     * Design spec 5.3: "the requirement satisfies only when a reviewer
     * approves the version" is the {@code requiresReview == true} branch
     * (below) -- this is the OTHER branch, where {@code requiresReview} is
     * false and uploading (here, fulfilling with an existing document)
     * satisfies the linked requirement immediately, through the existing
     * gated {@code RequirementService.satisfy}, proven the same way {@link
     * DocumentServiceTest#retiringReopensARequirementItSatisfied} proves a
     * composed-permission call: the manager holds both {@code
     * document.request} (fulfil's own gate) and {@code milestone.complete}
     * (satisfy's own gate).
     */
    @Test
    void fulfillingARequestWithRequiresReviewFalseSatisfiesTheRequirementImmediately() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-satisfy-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-satisfy-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Fulfil Satisfy Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            requestId[0] = createRequestRow(tenant, caseId[0], requirementId[0], manager[0], false);
            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0]);
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () -> view[0] = requests.fulfil(requestId[0], documentId[0]));

        assertThat(view[0].status()).isEqualTo(DocumentRequestStatus.FULFILLED);
        assertThat(view[0].fulfilledDocumentId()).isEqualTo(documentId[0]);

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.SATISFIED);
            assertThat(r.getSatisfiedRef()).isEqualTo(documentId[0]);
            assertThat(r.getSatisfiedRefType()).isEqualTo(DocumentService.SATISFIED_REF_TYPE);
        });
    }

    /**
     * The last test's own doc comment names the seam this one proves: {@code
     * satisfiedRef}/{@code satisfiedRefType} round-trip the fulfilling
     * document's own id and {@code DocumentService.SATISFIED_REF_TYPE}
     * exactly -- the seam {@code SatisfyRequest}'s own doc comment promised
     * sub-projects 3-5 would use.
     */
    @Test
    void theSatisfiedRefAndRefTypePointAtTheDocument() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-ref-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-ref-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Fulfil Ref Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            requestId[0] = createRequestRow(tenant, caseId[0], requirementId[0], manager[0], false);
            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () -> requests.fulfil(requestId[0], documentId[0]));

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getSatisfiedRef()).isEqualTo(documentId[0]);
            assertThat(r.getSatisfiedRefType()).isEqualTo("document");
        });
    }

    /**
     * Design spec 5.3's other branch: when the linked request's own {@code
     * requiresReview} is true, {@code fulfil} still moves the request itself
     * to FULFILLED, but the requirement it is linked to stays exactly as it
     * was -- untouched by {@code RequirementService.satisfy} entirely, which
     * a future review-approval task (not this one) will call once a reviewer
     * actually approves the version.
     */
    @Test
    void fulfillingARequestWithRequiresReviewTrueDoesNotSatisfyYet() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-review-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-review-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Fulfil Review Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            requestId[0] = createRequestRow(tenant, caseId[0], requirementId[0], manager[0], true);
            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0]);
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () -> view[0] = requests.fulfil(requestId[0], documentId[0]));

        assertThat(view[0].status()).isEqualTo(DocumentRequestStatus.FULFILLED);
        assertThat(view[0].fulfilledDocumentId()).isEqualTo(documentId[0]);

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
            assertThat(r.getSatisfiedRefType()).isNull();
        });
    }

    /**
     * An ad-hoc request ({@code requirementId == null}) has nothing to
     * satisfy regardless of {@code requiresReview} -- {@code fulfil} only
     * ever changes the request's own status in this case. Not one of this
     * task's own four named tests, but a real branch its own logic needs to
     * cover: {@code requirementId == null} must never reach {@code
     * RequirementService.satisfy} at all.
     */
    @Test
    void fulfillingAnAdHocRequestWithNoLinkedRequirementNeverCallsSatisfy() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-adhoc-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-adhoc-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Fulfil Adhoc Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            requestId[0] = createRequestRow(tenant, caseId[0], null, manager[0], false);
            documentId[0] = createDocument(tenant, caseId[0], c.getCustomerId(), manager[0]);
        });

        var view = new DocumentRequestView[1];
        fixture.runAsUser(tenant, manager[0], () -> view[0] = requests.fulfil(requestId[0], documentId[0]));

        assertThat(view[0].status()).isEqualTo(DocumentRequestStatus.FULFILLED);
        assertThat(view[0].fulfilledDocumentId()).isEqualTo(documentId[0]);
        assertThat(view[0].requirementId()).isNull();
    }

    /**
     * The mirror of {@code withdrawingAnAlreadyFulfilledRequestIsRefused}:
     * a request already {@code WITHDRAWN} is likewise a terminal state, and
     * fulfilling it is refused the same way -- {@link IllegalStateException}
     * (409), never a silent no-op.
     */
    @Test
    void fulfillingAWithdrawnRequestIsRefused() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-withdrawn-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-withdrawn-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            requestId[0] = createRequestRow(tenant, caseId[0], null, manager[0], false);
            documentId[0] = createDocument(tenant, caseId[0], c.getCustomerId(), manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () -> requests.withdraw(requestId[0], "No longer needed"));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                requests.fulfil(requestId[0], documentId[0])))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The confused-deputy cross-reference guard for {@code fulfil}, the same
     * shape {@link #aContactBelongingToADifferentCustomerThanTheCaseIsRefusedAsABadRequest}
     * already proves for {@code create}: a document that genuinely exists
     * and is genuinely visible to the actor, but whose own {@code caseId}
     * does not match the request's, is refused as a 400 -- never conflated
     * with the 404 an out-of-scope or nonexistent document id already gets.
     * An exact {@code caseId} match only -- whether a document merely LINKED
     * into the request's case should also count is a real, unresolved edge
     * case, deliberately not solved here.
     */
    @Test
    void fulfillingWithADocumentFromADifferentCaseIsRefusedAsABadRequest() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-mismatch-" + Uuid7.generate());
        var manager = new UUID[1];
        var requestId = new UUID[1];
        var otherDocumentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-mismatch-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant);
            requestId[0] = createRequestRow(tenant, c.getId(), null, manager[0], false);

            Case otherCase = journey.newCase(tenant);
            otherDocumentId[0] = createDocument(tenant, otherCase.getId(), otherCase.getCustomerId(), manager[0]);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                requests.fulfil(requestId[0], otherDocumentId[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A RETIRED document is kept reachable by id on purpose ({@link
     * DocumentService#get}'s own javadoc: retirement is a status change, not
     * an existence change), so {@code AuthorizedQuery} resolves it fine here
     * -- but {@code fulfil} must refuse it anyway (400, the same shape as the
     * cross-case check just above), or a requirement could be satisfied
     * against a document nobody can ever open again, with nothing left to
     * ever reopen it ({@code journey.RequirementService#reopen}'s own
     * javadoc names exactly this as retirement's reason for reopening
     * requirements in the first place). Also proves no partial state is left
     * behind: the request stays {@code OPEN} and the linked requirement is
     * never satisfied.
     */
    @Test
    void fulfillingWithARetiredDocumentIsRefusedAndLeavesNoPartialState() {
        UUID tenant = fixture.createTenant("doc-req-fulfil-retired-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "fulfil-retired-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Fulfil Retired Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            requestId[0] = createRequestRow(tenant, caseId[0], requirementId[0], manager[0], false);
            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0]);

            Document d = documentRepository.findById(documentId[0]).orElseThrow();
            d.setStatus(DocumentStatus.RETIRED);
            documentRepository.saveAndFlush(d);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                requests.fulfil(requestId[0], documentId[0])))
                .isInstanceOf(IllegalArgumentException.class);

        fixture.runAs(tenant, () -> {
            DocumentRequest dr = requestRepository.findById(requestId[0]).orElseThrow();
            assertThat(dr.getStatus()).isEqualTo(DocumentRequestStatus.OPEN);
            assertThat(dr.getFulfilledDocumentId()).isNull();

            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
        });
    }

    /**
     * A cross-tenant (or otherwise nonexistent) {@code requestId} is a 404,
     * the identical shape {@link #aCrossTenantRequestIdAnswers404NeverForbidden}
     * already proves for {@code withdraw}.
     */
    @Test
    void aCrossTenantRequestIdOnFulfilAnswers404NeverForbidden() {
        UUID tenantA = fixture.createTenant("doc-req-fulfil-xt-a-" + Uuid7.generate());
        var managerA = new UUID[1];
        var requestId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenantA, () -> {
            managerA[0] = fixture.createUser(tenantA, "fulfil-xt-a+" + Uuid7.generate() + "@example.com");
            grant(managerA[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            Case c = journey.newCase(tenantA);
            requestId[0] = createRequestRow(tenantA, c.getId(), null, managerA[0], false);
            documentId[0] = createDocument(tenantA, c.getId(), c.getCustomerId(), managerA[0]);
        });

        UUID tenantB = fixture.createTenant("doc-req-fulfil-xt-b-" + Uuid7.generate());
        var managerB = new UUID[1];
        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "fulfil-xt-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () ->
                requests.fulfil(requestId[0], documentId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The identical 404 shape, for a cross-tenant (or otherwise nonexistent)
     * {@code documentId} instead -- {@code requestId} resolves fine, but the
     * document does not exist in this tenant at all, so {@code
     * AuthorizedQuery.getById} refuses it before the cross-reference check
     * (or anything else) ever runs.
     */
    @Test
    void aCrossTenantDocumentIdOnFulfilAnswers404NeverForbidden() {
        UUID tenantA = fixture.createTenant("doc-req-fulfil-xtd-a-" + Uuid7.generate());
        var managerA = new UUID[1];
        var requestId = new UUID[1];

        fixture.runAs(tenantA, () -> {
            managerA[0] = fixture.createUser(tenantA, "fulfil-xtd-a+" + Uuid7.generate() + "@example.com");
            grant(managerA[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            Case c = journey.newCase(tenantA);
            requestId[0] = createRequestRow(tenantA, c.getId(), null, managerA[0], false);
        });

        UUID tenantB = fixture.createTenant("doc-req-fulfil-xtd-b-" + Uuid7.generate());
        var managerB = new UUID[1];
        var documentIdInB = new UUID[1];
        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "fulfil-xtd-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(
                    PermissionKeys.DOCUMENT_REQUEST, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            Case c = journey.newCase(tenantB);
            documentIdInB[0] = createDocument(tenantB, c.getId(), c.getCustomerId(), managerB[0]);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantA, managerA[0], () ->
                requests.fulfil(requestId[0], documentIdInB[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Task 36 review: {@code forCase} had zero dedicated tests before this --
     * its only exercise was the e2e spec's single admin-happy-path call. These
     * three mirror {@code DocumentServiceTest.forCase}'s own three
     * (cross-tenant, out-of-scope-department, portal), the exact precedent
     * {@code DocumentRequestService.forCase}'s own javadoc already cites.
     *
     * A cross-tenant {@code caseId} is a 404, never a 500 or a leak across the
     * tenant boundary -- the same shape {@code aCrossTenantRequestIdAnswers404NeverForbidden}
     * already proves for {@code withdraw}, now proven for the {@code caseId}
     * {@code forCase} itself resolves.
     */
    @Test
    void forCaseWithACrossTenantCaseIdIsA404() {
        UUID tenantA = fixture.createTenant("doc-req-forcase-xt-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-req-forcase-xt-b-" + Uuid7.generate());
        var caseInA = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> caseInA[0] = journey.newCase(tenantA).getId());

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "forcase-actor-b+" + Uuid7.generate() + "@example.com");
            grant(actorB[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0],
                () -> requests.forCase(caseInA[0], Pageable.unpaged())))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * A SAME-tenant {@code caseId} outside the caller's own {@code
     * document.request} scope -- DEPARTMENT here, the same narrowest
     * real-ownership scope {@code DocumentServiceTest
     * .forCaseWithACaseOutsideTheCallersDepartmentScopeIsA404} exercises.
     * Proves {@code forCase}'s resolution genuinely narrows by scope, not
     * merely by tenant.
     */
    @Test
    void forCaseWithACaseOutsideTheCallersDepartmentScopeIsA404() {
        UUID tenant = fixture.createTenant("doc-req-forcase-scope-" + Uuid7.generate());
        var actor = new UUID[1];
        var otherDeptCaseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID ownDepartment = fixture.createDepartment(tenant, "Reader's Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Department");
            actor[0] = fixture.createUserInDepartment(
                    tenant, "forcase-dept-reader+" + Uuid7.generate() + "@example.com", ownDepartment);
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_REQUEST, Scope.DEPARTMENT));

            otherDeptCaseId[0] = journey.newCase(tenant, null, otherDepartment, null).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0],
                () -> requests.forCase(otherDeptCaseId[0], Pageable.unpaged())))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Deliberately NOT the same shape as {@code DocumentServiceTest
     * .forCaseRefusesAPortalActorEvenForTheirOwnCustomersCase}, and this is
     * the point worth pinning down: that test needs {@code DocumentService
     * .forCase}'s own explicit {@code UserType.PORTAL} guard because {@code
     * PortalPermissions} grants {@code document.view} at {@code Scope.ALL}
     * with no {@code AudienceFilter} on {@code Case} to narrow it. {@code
     * document.request} is NOT one of the two keys {@code
     * PortalPermissions.forContact}/{@code forSponsor} grant (only {@code
     * document.view}/{@code document.upload}) -- so a portal actor calling
     * {@code DocumentRequestService.forCase} never reaches the {@code caseId}
     * resolution at all; {@code @RequirePermission}'s own gate refuses them
     * first, as {@link AccessDeniedException} (403), because they hold no
     * {@code document.request} grant whatsoever.
     * {@code forCase} needs no equivalent portal special-case of its own --
     * proven here rather than left an untested assumption.
     */
    @Test
    void forCaseRefusesAPortalActorOutrightWithNoDocumentRequestGrantAtAll() {
        UUID tenant = fixture.createTenant("doc-req-forcase-portal-" + Uuid7.generate());
        var portalUserId = new UUID[1];
        var ownCustomersCaseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Portal Co " + Uuid7.generate(), null, null, null);
            portalUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "portal-contact+" + Uuid7.generate() + "@example.com");
            ownCustomersCaseId[0] = journey.newCase(tenant).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, portalUserId[0],
                () -> requests.forCase(ownCustomersCaseId[0], Pageable.unpaged())))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createRequestRow(UUID tenant, UUID caseId, UUID requirementId, UUID requestedBy) {
        return createRequestRow(tenant, caseId, requirementId, requestedBy, false);
    }

    private UUID createRequestRow(UUID tenant, UUID caseId, UUID requirementId, UUID requestedBy, boolean requiresReview) {
        DocumentRequest dr = new DocumentRequest();
        dr.setId(Uuid7.generate());
        dr.setTenantId(tenant);
        dr.setCaseId(caseId);
        dr.setRequirementId(requirementId);
        dr.setCategory(DocumentCategory.OTHER);
        dr.setRequiresReview(requiresReview);
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
