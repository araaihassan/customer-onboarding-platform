package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.DescriptorRegistry;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCaseLink;
import co.ara.onboarding.document.DocumentCaseLinkRepository;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentRequest;
import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestStatus;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.document.DocumentShareRepository;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.DocumentVersion;
import co.ara.onboarding.document.DocumentVersionRepository;
import co.ara.onboarding.document.DocumentView;
import co.ara.onboarding.document.ReviewStatus;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Scope resolution for the document module's five descriptors (Task 10). Every
 * one of the five is needed for AuthorizedQuery's entity-type dispatch even
 * though DescriptorRegistry.validate() only requires "document" itself -- see
 * each descriptor's own javadoc. Setup and assertion run inside a single
 * {@link TenantFixture#runAs} block, the same shape JourneyScopingTest and
 * DescriptorRegistryTest already established.
 */
class DocumentScopingTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository documentVersions;
    @Autowired DocumentShareRepository documentShares;
    @Autowired DocumentCaseLinkRepository documentCaseLinks;
    @Autowired DocumentRequestRepository documentRequests;
    @Autowired DocumentService documentService;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;

    @Test
    void allFiveEntityTypesHaveARegisteredDescriptor() {
        assertThatNoException().isThrownBy(() -> registry.forEntity(Document.class));
        assertThatNoException().isThrownBy(() -> registry.forEntity(DocumentVersion.class));
        assertThatNoException().isThrownBy(() -> registry.forEntity(DocumentShare.class));
        assertThatNoException().isThrownBy(() -> registry.forEntity(DocumentCaseLink.class));
        assertThatNoException().isThrownBy(() -> registry.forEntity(DocumentRequest.class));

        assertThat(registry.resourceTypes()).contains(
                "document", "document_version", "document_share",
                "document_case_link", "document_request");
    }

    /**
     * Document's own DEPARTMENT/TEAM resolve through its case_id column directly
     * (one hop). ASSIGNED is the personal uploaded_by relationship, never
     * team-mediated -- a document uploaded by a fellow team member is not
     * visible to a mere teammate under ASSIGNED.
     */
    @Test
    void documentScopeResolvesThroughItsOwnCaseAndUploaderAndFailsClosed() {
        UUID tenant = fixture.createTenant("doc-scope");
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-scope.example");
            UUID otherUploader = fixture.createUser(tenant, "other-uploader@doc-scope.example");
            UUID teammate = fixture.createUser(tenant, "teammate@doc-scope.example");
            fixture.addToTeam(tenant, teammate, team);

            Case inDepartmentAndTeam = journey.newCase(tenant, null, department, team);
            Case elsewhere = journey.newCase(tenant, null, otherDepartment, null);

            Document inDept = newDocument(tenant, inDepartmentAndTeam, uploader);
            newDocument(tenant, elsewhere, otherUploader);

            var descriptor = registry.forEntity(Document.class);

            var deptCtx = new AuthContext(tenant, uploader, UserType.INTERNAL, department, Set.of());
            assertThat(documents.findAll(descriptor.departmentScope(deptCtx)))
                    .extracting(Document::getId).containsExactly(inDept.getId());

            var noDeptCtx = new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of());
            assertThat(documents.findAll(descriptor.departmentScope(noDeptCtx)))
                    .as("no department must match nothing, not everything")
                    .isEmpty();

            var teamCtx = new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of(team));
            assertThat(documents.findAll(descriptor.teamScope(teamCtx)))
                    .extracting(Document::getId).containsExactly(inDept.getId());

            var noTeamCtx = new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of());
            assertThat(documents.findAll(descriptor.teamScope(noTeamCtx)))
                    .as("no teams must match nothing, not everything")
                    .isEmpty();

            assertThat(documents.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("the uploader sees their own document")
                    .extracting(Document::getId).containsExactly(inDept.getId());

            assertThat(documents.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, teammate, UserType.INTERNAL, null, Set.of(team)))))
                    .as("ASSIGNED is personal -- a teammate of the uploader must not match")
                    .isEmpty();
        });
    }

    /**
     * One hop further than DocumentDescriptor: version -> document -> case.
     * ASSIGNED borrows the parent document's own uploaded_by column.
     */
    @Test
    void documentVersionScopeResolvesThroughItsParentDocumentsCaseAndFailsClosed() {
        UUID tenant = fixture.createTenant("doc-version-scope");
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-version-scope.example");
            UUID otherUploader = fixture.createUser(tenant, "other-uploader@doc-version-scope.example");
            UUID teammate = fixture.createUser(tenant, "teammate@doc-version-scope.example");
            fixture.addToTeam(tenant, teammate, team);

            Case c = journey.newCase(tenant, null, department, team);
            Case elsewhere = journey.newCase(tenant);
            Document doc = newDocument(tenant, c, uploader);
            Document otherDoc = newDocument(tenant, elsewhere, otherUploader);

            DocumentVersion version = newVersion(tenant, doc, uploader);
            newVersion(tenant, otherDoc, otherUploader);

            var descriptor = registry.forEntity(DocumentVersion.class);

            assertThat(documentVersions.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, department, Set.of()))))
                    .extracting(DocumentVersion::getId).containsExactly(version.getId());
            assertThat(documentVersions.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("no department must match nothing, not everything").isEmpty();

            assertThat(documentVersions.findAll(descriptor.teamScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of(team)))))
                    .extracting(DocumentVersion::getId).containsExactly(version.getId());
            assertThat(documentVersions.findAll(descriptor.teamScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("no teams must match nothing, not everything").isEmpty();

            assertThat(documentVersions.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("resolves through the parent document's uploaded_by")
                    .extracting(DocumentVersion::getId).containsExactly(version.getId());
            assertThat(documentVersions.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, teammate, UserType.INTERNAL, null, Set.of(team)))))
                    .as("ASSIGNED is personal, borrowed from the parent document's uploader -- "
                            + "a teammate of the uploader must not match")
                    .isEmpty();
        });
    }

    @Test
    void documentShareScopeResolvesThroughItsParentDocumentsCaseAndFailsClosed() {
        UUID tenant = fixture.createTenant("doc-share-scope");
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-share-scope.example");
            UUID otherUploader = fixture.createUser(tenant, "other-uploader@doc-share-scope.example");
            UUID teammate = fixture.createUser(tenant, "teammate@doc-share-scope.example");
            UUID granter = fixture.createUser(tenant, "granter@doc-share-scope.example");
            fixture.addToTeam(tenant, teammate, team);

            Case c = journey.newCase(tenant, null, department, team);
            Case elsewhere = journey.newCase(tenant);
            Document doc = newDocument(tenant, c, uploader);
            Document other = newDocument(tenant, elsewhere, otherUploader);

            DocumentShare share = newShare(tenant, doc, granter);
            newShare(tenant, other, granter);

            var descriptor = registry.forEntity(DocumentShare.class);

            assertThat(documentShares.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, granter, UserType.INTERNAL, department, Set.of()))))
                    .extracting(DocumentShare::getId).containsExactly(share.getId());
            assertThat(documentShares.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, granter, UserType.INTERNAL, null, Set.of()))))
                    .as("no department must match nothing, not everything").isEmpty();

            assertThat(documentShares.findAll(descriptor.teamScope(
                    new AuthContext(tenant, granter, UserType.INTERNAL, null, Set.of(team)))))
                    .extracting(DocumentShare::getId).containsExactly(share.getId());
            assertThat(documentShares.findAll(descriptor.teamScope(
                    new AuthContext(tenant, granter, UserType.INTERNAL, null, Set.of()))))
                    .as("no teams must match nothing, not everything").isEmpty();

            assertThat(documentShares.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("resolves through the parent document's uploaded_by, not granted_by")
                    .extracting(DocumentShare::getId).containsExactly(share.getId());
            assertThat(documentShares.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, teammate, UserType.INTERNAL, null, Set.of(team)))))
                    .as("ASSIGNED is personal, borrowed from the parent document's uploader -- "
                            + "a teammate of the uploader must not match")
                    .isEmpty();
        });
    }

    /**
     * The link's own case_id is the LINKED (target) case, not the document's home
     * case -- proving scope resolves through the document's home case (not the
     * link's own case_id column) requires putting them in different departments
     * and asserting only the home department matches.
     */
    @Test
    void documentCaseLinkScopeResolvesThroughItsParentDocumentsHomeCaseNotTheLinkedCase() {
        UUID tenant = fixture.createTenant("doc-link-scope");
        fixture.runAs(tenant, () -> {
            UUID homeDepartment = fixture.createDepartment(tenant, "Home");
            UUID linkedDepartment = fixture.createDepartment(tenant, "Linked");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-link-scope.example");
            UUID otherUploader = fixture.createUser(tenant, "other-uploader@doc-link-scope.example");
            UUID teammate = fixture.createUser(tenant, "teammate@doc-link-scope.example");
            UUID linker = fixture.createUser(tenant, "linker@doc-link-scope.example");
            fixture.addToTeam(tenant, teammate, team);

            Case home = journey.newCase(tenant, null, homeDepartment, team);
            Case linkedCase = journey.newCase(tenant, null, linkedDepartment, null);
            Case elsewhere = journey.newCase(tenant);
            Document doc = newDocument(tenant, home, uploader);
            Document otherDoc = newDocument(tenant, elsewhere, otherUploader);

            DocumentCaseLink link = newCaseLink(tenant, doc, linkedCase.getId(), linker);
            newCaseLink(tenant, otherDoc, linkedCase.getId(), linker);

            var descriptor = registry.forEntity(DocumentCaseLink.class);

            assertThat(documentCaseLinks.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, homeDepartment, Set.of()))))
                    .as("the document's home department sees the link")
                    .extracting(DocumentCaseLink::getId).containsExactly(link.getId());

            assertThat(documentCaseLinks.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, linkedDepartment, Set.of()))))
                    .as("the LINKED case's own department must not match -- scope is the document's home case")
                    .isEmpty();

            assertThat(documentCaseLinks.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of()))))
                    .as("no department must match nothing, not everything").isEmpty();

            assertThat(documentCaseLinks.findAll(descriptor.teamScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of(team)))))
                    .extracting(DocumentCaseLink::getId).containsExactly(link.getId());
            assertThat(documentCaseLinks.findAll(descriptor.teamScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of()))))
                    .as("no teams must match nothing, not everything").isEmpty();

            assertThat(documentCaseLinks.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, uploader, UserType.INTERNAL, null, Set.of()))))
                    .as("resolves through the parent document's uploaded_by, not linked_by")
                    .extracting(DocumentCaseLink::getId).containsExactly(link.getId());
            assertThat(documentCaseLinks.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, teammate, UserType.INTERNAL, null, Set.of(team)))))
                    .as("ASSIGNED is personal, borrowed from the parent document's uploader -- "
                            + "a teammate of the uploader must not match")
                    .isEmpty();
        });
    }

    /**
     * Task 20 review finding, now closed: DEPARTMENT/TEAM previously resolved
     * ONLY through the document's HOME case ({@code viaCase}), so a
     * DEPARTMENT/TEAM-scoped reader whose department/team owns only the case a
     * document is LINKED into -- not its home case -- could not see it at all,
     * even though {@code DocumentService.forCase}'s own {@code homeOrLinked}
     * filter would otherwise match: {@code AuthorizationPredicateBuilder.forPermission}
     * ANDs the scope predicate with that filter, and the scope predicate alone
     * still rejected it. Widened to OR in a second path through a LIVE
     * {@code document_case_link} row. A REVOKED link must not widen scope --
     * proven here by placing the revoked link's target case in its own
     * department and asserting that department still matches nothing.
     * {@code assignedScope} is deliberately untouched -- it is the document's
     * own {@code uploaded_by} column, a personal relationship with no
     * connection to case linkage (DocumentDescriptor's own class javadoc).
     */
    @Test
    void documentDepartmentAndTeamScopeAlsoMatchThroughALiveLinkedCaseButNotARevokedOne() {
        UUID tenant = fixture.createTenant("doc-link-widen-" + Uuid7.generate());
        fixture.runAs(tenant, () -> {
            UUID homeDepartment = fixture.createDepartment(tenant, "Home");
            UUID linkedDepartment = fixture.createDepartment(tenant, "Linked");
            UUID revokedDepartment = fixture.createDepartment(tenant, "Revoked Target");
            UUID linkedTeam = fixture.createTeam(tenant, "Linked Team");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-link-widen.example");
            UUID linker = fixture.createUser(tenant, "linker@doc-link-widen.example");

            Case home = journey.newCase(tenant, null, homeDepartment, null);
            Case linkedCase = journey.newCase(tenant, null, linkedDepartment, linkedTeam);
            Case revokedTargetCase = journey.newCase(tenant, null, revokedDepartment, null);

            Document doc = newDocument(tenant, home, uploader);
            newCaseLink(tenant, doc, linkedCase.getId(), linker);

            DocumentCaseLink revoked = newCaseLink(tenant, doc, revokedTargetCase.getId(), linker);
            revoked.setRevokedAt(Instant.now());
            documentCaseLinks.saveAndFlush(revoked);

            var descriptor = registry.forEntity(Document.class);

            assertThat(documents.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, linkedDepartment, Set.of()))))
                    .as("a department owning only the LINKED (not home) case still matches via a live link")
                    .extracting(Document::getId).containsExactly(doc.getId());

            assertThat(documents.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, revokedDepartment, Set.of()))))
                    .as("a REVOKED link must not widen scope -- this department's only relationship "
                            + "to the document is through a link that has since been revoked")
                    .isEmpty();

            assertThat(documents.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, homeDepartment, Set.of()))))
                    .as("the home department still matches too -- widening must not have replaced viaCase")
                    .extracting(Document::getId).containsExactly(doc.getId());

            assertThat(documents.findAll(descriptor.teamScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of(linkedTeam)))))
                    .as("a team owning only the LINKED case still matches via a live link")
                    .extracting(Document::getId).containsExactly(doc.getId());

            assertThat(documents.findAll(descriptor.teamScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of()))))
                    .as("no teams must match nothing, not everything").isEmpty();

            assertThat(documents.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, linker, UserType.INTERNAL, null, Set.of()))))
                    .as("ASSIGNED must remain untouched by this widening -- the linker is not the uploader")
                    .isEmpty();
        });
    }

    /**
     * The end-to-end shape the widening above exists for. Before it,
     * {@code DocumentService.forCase}'s own {@code homeOrLinked} filter already
     * matched this document on the second (linked-into) case, but the scope
     * predicate it is ANDed with still rejected it -- so a DEPARTMENT-scoped
     * {@code document.view} holder whose department owns only the SECOND case
     * saw an empty list on a case their own department legitimately owns.
     */
    @Test
    void aDepartmentScopedReaderSeesADocumentOnTheSecondCaseOnlyBecauseOfTheWideningAbove() {
        UUID tenant = fixture.createTenant("doc-link-widen-forcase-" + Uuid7.generate());
        var reader = new UUID[1];
        var secondCaseId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID homeDepartment = fixture.createDepartment(tenant, "Home");
            UUID linkedDepartment = fixture.createDepartment(tenant, "Linked");
            UUID uploader = fixture.createUser(tenant, "uploader@doc-link-widen-forcase.example");
            reader[0] = fixture.createUserInDepartment(
                    tenant, "reader@doc-link-widen-forcase.example", linkedDepartment);
            grant(reader[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.DEPARTMENT));

            Case home = journey.newCase(tenant, null, homeDepartment, null);
            Case linkedCase = journey.newCase(tenant, null, linkedDepartment, null);
            secondCaseId[0] = linkedCase.getId();

            Document doc = newDocument(tenant, home, uploader);
            documentId[0] = doc.getId();
            newCaseLink(tenant, doc, linkedCase.getId(), uploader);
        });

        fixture.runAsUser(tenant, reader[0], () ->
                assertThat(documentService.forCase(secondCaseId[0], Pageable.unpaged()).getContent())
                        .as("a DEPARTMENT-scoped reader whose department owns only the LINKED case, "
                                + "not the document's home case, must still see it")
                        .extracting(DocumentView::id).containsExactly(documentId[0]));
    }

    /**
     * DocumentRequest carries its own case_id column directly (V23), so this is a
     * single-hop viaCase like TaskDescriptor/ApprovalDescriptor. It has no
     * personal column of its own suited to ASSIGNED (requested_of_contact_id is
     * an external contact, requested_by is the creator, which CaseDescriptor's
     * own reasoning excludes), so ASSIGNED falls back to case_participant, the
     * same shape ApprovalDescriptor and CaseAttributeValueDescriptor use.
     */
    @Test
    void documentRequestScopeResolvesThroughItsOwnCaseIdAndFailsClosed() {
        UUID tenant = fixture.createTenant("doc-request-scope");
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");
            UUID requester = fixture.createUser(tenant, "requester@doc-request-scope.example");
            UUID participant = fixture.createUser(tenant, "participant@doc-request-scope.example");

            Case c = journey.newCase(tenant, null, department, team);
            Case elsewhere = journey.newCase(tenant);
            journey.addParticipant(tenant, c.getId(), participant,
                    RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE);

            DocumentRequest request = newRequest(tenant, c, requester);
            newRequest(tenant, elsewhere, requester);

            var descriptor = registry.forEntity(DocumentRequest.class);

            assertThat(documentRequests.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, requester, UserType.INTERNAL, department, Set.of()))))
                    .extracting(DocumentRequest::getId).containsExactly(request.getId());
            assertThat(documentRequests.findAll(descriptor.departmentScope(
                    new AuthContext(tenant, requester, UserType.INTERNAL, null, Set.of()))))
                    .as("no department must match nothing, not everything").isEmpty();

            assertThat(documentRequests.findAll(descriptor.teamScope(
                    new AuthContext(tenant, requester, UserType.INTERNAL, null, Set.of(team)))))
                    .extracting(DocumentRequest::getId).containsExactly(request.getId());
            assertThat(documentRequests.findAll(descriptor.teamScope(
                    new AuthContext(tenant, requester, UserType.INTERNAL, null, Set.of()))))
                    .as("no teams must match nothing, not everything").isEmpty();

            assertThat(documentRequests.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, participant, UserType.INTERNAL, null, Set.of()))))
                    .as("an ACTIVE case_participant resolves ASSIGNED")
                    .extracting(DocumentRequest::getId).containsExactly(request.getId());
            assertThat(documentRequests.findAll(descriptor.assignedScope(
                    new AuthContext(tenant, requester, UserType.INTERNAL, null, Set.of()))))
                    .as("merely having created the request, with no participant row, must not match")
                    .isEmpty();
        });
    }

    private Document newDocument(UUID tenant, Case c, UUID uploadedBy) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documents.saveAndFlush(d);
    }

    private DocumentVersion newVersion(UUID tenant, Document doc, UUID uploadedBy) {
        DocumentVersion v = new DocumentVersion(Uuid7.generate(), tenant, doc.getId(), 1,
                "objects/" + Uuid7.generate(), 100L, "application/pdf",
                "0".repeat(64), ReviewStatus.PENDING, uploadedBy, Instant.now());
        return documentVersions.saveAndFlush(v);
    }

    private DocumentShare newShare(UUID tenant, Document doc, UUID grantedBy) {
        DocumentShare s = new DocumentShare(Uuid7.generate(), tenant, doc.getId(),
                SharePrincipalType.USER, Uuid7.generate(), grantedBy, Instant.now());
        return documentShares.saveAndFlush(s);
    }

    private DocumentCaseLink newCaseLink(UUID tenant, Document doc, UUID caseId, UUID linkedBy) {
        DocumentCaseLink l = new DocumentCaseLink(Uuid7.generate(), tenant, doc.getId(), caseId,
                linkedBy, Instant.now());
        return documentCaseLinks.saveAndFlush(l);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private DocumentRequest newRequest(UUID tenant, Case c, UUID requestedBy) {
        DocumentRequest r = new DocumentRequest();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setCaseId(c.getId());
        r.setCategory(DocumentCategory.OTHER);
        r.setRequiresReview(false);
        r.setStatus(DocumentRequestStatus.OPEN);
        r.setRequestedBy(requestedBy);
        r.setRequestedAt(Instant.now());
        return documentRequests.saveAndFlush(r);
    }
}
