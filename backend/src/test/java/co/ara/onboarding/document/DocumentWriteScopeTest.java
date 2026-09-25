package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 21: the {@code write_scope} negative `task.TaskWriteScopeTest`/
 * `security.WriteScopeTest` establish as its own dedicated, in-package file --
 * a wider-scoped holder is still refused inside an {@code OWNER_ONLY} stage,
 * proving {@link co.ara.onboarding.journey.StageWriteScopeGuard} narrows on
 * top of, never instead of, the record-level scope a permission is held at.
 *
 * <p>All 8 of {@code document}'s write methods call the same private {@code
 * applyWriteScope(Case)} helper unconditionally (confirmed by grep: 4 call
 * sites each in {@link DocumentService} and {@link DocumentSharingService}),
 * so the mechanism itself is uniformly wired. But only 4 of the 8 --
 * {@code upload}, {@code patch}, {@code share}, {@code link} -- had their own
 * dedicated negative test before this task (each in {@code DocumentServiceTest}/
 * {@code DocumentSharingServiceTest}, named {@code aTeamScopedXxxHolderIsStillRefusedInsideAnOwnerOnlyStage}).
 * {@code addVersion}, {@code retire}, {@code revokeShare} and {@code unlink}
 * did not -- confirmed absent by grep, not assumed -- and this file closes
 * that gap, one test per method, each mirroring the exact TEAM-scoped
 * construction the four existing tests already use (CLAUDE.md: "at least one
 * write test must run at the narrowest [catalogued] scope").
 */
class DocumentWriteScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired DocumentService documents;
    @Autowired DocumentSharingService sharing;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentShareRepository shareRepository;
    @Autowired DocumentCaseLinkRepository linkRepository;
    @Autowired RoleService roles;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The same construction as {@code DocumentServiceTest
     * .aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage} (upload's own
     * test), applied to {@code addVersion}: the document already exists
     * (seeded directly, uploaded by the case owner -- the only identity able
     * to legitimately write into the restricted stage), and a TEAM-scoped
     * {@code document.upload} holder matching the case's own team, but not
     * its owner, is still refused.
     */
    @Test
    void aTeamScopedUploadHolderIsStillRefusedInsideAnOwnerOnlyStageWhenAddingAVersion() {
        UUID tenant = fixture.createTenant("doc-ws-addversion-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var documentId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "addversion-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            // WORKFLOW_VIEW is needed too -- applyWriteScope resolves the case's
            // current Stage under it, and with none granted the Stage lookup
            // itself 404s before the write-scope check is even reached.
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "addversion-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc AddVersion Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            documentId[0] = createDocument(tenant, caseId, customerId, caseOwner);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () -> documents.addVersion(
                documentId[0], new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(WriteScopeException.class);
    }

    /**
     * The same construction, applied to {@code retire}: a TEAM-scoped
     * {@code document.manage} holder matching the case's own team, but not
     * its owner, is still refused -- the identical shape
     * {@code DocumentServiceTest.aTeamScopedManageHolderIsStillRefusedInsideAnOwnerOnlyStageWhenPatching}
     * already proves for {@code patch}.
     */
    @Test
    void aTeamScopedManageHolderIsStillRefusedInsideAnOwnerOnlyStageWhenRetiring() {
        UUID tenant = fixture.createTenant("doc-ws-retire-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var documentId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "retire-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "retire-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Retire Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            documentId[0] = createDocument(tenant, caseId, customerId, caseOwner);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () ->
                documents.retire(documentId[0], "No longer needed")))
                .isInstanceOf(WriteScopeException.class);

        fixture.runAs(tenant, () -> {
            Document d = documentRepository.findById(documentId[0]).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(d.getStatus())
                    .as("a refused retire must never persist")
                    .isEqualTo(DocumentStatus.ACTIVE);
        });
    }

    /**
     * The same construction, applied to {@code revokeShare}: the LIVE share
     * pre-exists (seeded directly, granted by the case owner -- {@code
     * share()} itself would already be refused for the same reason under
     * test, so it cannot be used to set this up), and a TEAM-scoped {@code
     * document.share} holder matching the case's own team, but not its
     * owner, is still refused revoking it -- the identical shape
     * {@code DocumentSharingServiceTest.aTeamScopedShareHolderIsStillRefusedInsideAnOwnerOnlyStage}
     * already proves for {@code share} itself.
     */
    @Test
    void aTeamScopedShareHolderIsStillRefusedInsideAnOwnerOnlyStageWhenRevoking() {
        UUID tenant = fixture.createTenant("doc-ws-revokeshare-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var shareId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "revokeshare-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "revokeshare-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc RevokeShare Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            UUID documentId = createDocument(tenant, caseId, customerId, caseOwner);

            DocumentShare live = new DocumentShare(Uuid7.generate(), tenant, documentId,
                    SharePrincipalType.USER, Uuid7.generate(), caseOwner, Instant.now(clock));
            shareRepository.saveAndFlush(live);
            shareId[0] = live.getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () ->
                sharing.revokeShare(shareId[0])))
                .isInstanceOf(WriteScopeException.class);

        fixture.runAs(tenant, () -> {
            DocumentShare share = shareRepository.findById(shareId[0]).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(share.getRevokedAt())
                    .as("a refused revocation must never persist")
                    .isNull();
        });
    }

    /**
     * The same construction, applied to {@code unlink}: the LIVE link
     * pre-exists (seeded directly -- {@code link()} itself would already be
     * refused for the same reason under test), and a TEAM-scoped {@code
     * document.share} holder matching the HOME case's own team, but not its
     * owner, is still refused unlinking it -- the identical shape
     * {@code DocumentSharingServiceTest.aTeamScopedLinkHolderIsStillRefusedInsideAnOwnerOnlyStage}
     * already proves for {@code link}. {@link co.ara.onboarding.scoping.DocumentCaseLinkDescriptor}'s
     * own javadoc confirms scope resolves through the document's HOME case,
     * never the link's own target case -- so the second (target) case need
     * not itself be OWNER_ONLY for this to hold.
     */
    @Test
    void aTeamScopedShareHolderIsStillRefusedInsideAnOwnerOnlyStageWhenUnlinking() {
        UUID tenant = fixture.createTenant("doc-ws-unlink-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var documentId = new UUID[1];
        var secondCaseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "unlink-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "unlink-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Unlink Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID homeCaseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            documentId[0] = createDocument(tenant, homeCaseId, customerId, caseOwner);

            secondCaseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Second Case " + Uuid7.generate(),
                    Map.of())).id();

            DocumentCaseLink live = new DocumentCaseLink(Uuid7.generate(), tenant, documentId[0],
                    secondCaseId[0], caseOwner, Instant.now(clock));
            linkRepository.saveAndFlush(live);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () ->
                sharing.unlink(documentId[0], secondCaseId[0])))
                .isInstanceOf(WriteScopeException.class);

        fixture.runAs(tenant, () ->
                org.assertj.core.api.Assertions.assertThat(linkRepository.liveLinksOf(documentId[0]))
                        .as("a refused unlink must never persist")
                        .hasSize(1));
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
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
