package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 19 (design spec 4.3/6.3, Q9's "restricted until explicitly shared"
 * escape hatch): the WRITE half of {@link DocumentShare} -- creating and
 * revoking a share through {@link DocumentSharingService}, gated
 * {@code document.share}. The READ half ({@code scoping.DocumentAudienceFilter}'s
 * own {@code sharedWith} EXISTS subquery) is already built and proven by
 * {@code security.PortalVisibilityTest}, which seeds {@link DocumentShare}
 * rows directly; this file is the first place either method of
 * {@link DocumentSharingService} runs at all.
 */
class DocumentSharingServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentSharingService sharing;
    @Autowired DocumentService documents;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentShareRepository shareRepository;
    @Autowired CustomerContactRepository contactRepository;
    @Autowired RoleService roles;
    @Autowired CaseService cases;
    @Autowired EntityManager entityManager;

    /**
     * The core scenario Task 19's brief names verbatim: a SENSITIVE document,
     * reachable by NEITHER contact through tier (design spec 6.3: "SENSITIVE
     * reaches nobody by tier"), shared to exactly one of them.
     */
    @Test
    void sharingASensitiveDocumentToOneContactMakesItVisibleToThatContactOnly() {
        UUID tenant = fixture.createTenant("doc-share-sensitive-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var sharedContactUserId = new UUID[1];
        var otherContactUserId = new UUID[1];
        var sharedContactId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Sensitive Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "share-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);

            sharedContactUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "shared+" + Uuid7.generate() + "@example.com");
            otherContactUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "other+" + Uuid7.generate() + "@example.com");
            sharedContactId[0] = contactRepository.findByUserId(sharedContactUserId[0]).orElseThrow().getId();
        });

        var view = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                view[0] = sharing.share(documentId[0], SharePrincipalType.CONTACT, sharedContactId[0]));
        assertThat(view[0].revokedAt()).isNull();
        assertThat(view[0].principalType()).isEqualTo(SharePrincipalType.CONTACT);
        assertThat(view[0].principalId()).isEqualTo(sharedContactId[0]);

        fixture.runAsUser(tenant, sharedContactUserId[0], () ->
                assertThat(documents.list(Pageable.unpaged()).getContent())
                        .as("the shared contact must see the SENSITIVE document")
                        .extracting(DocumentView::id).containsExactly(documentId[0]));

        fixture.runAsUser(tenant, otherContactUserId[0], () ->
                assertThat(documents.list(Pageable.unpaged()).getContent())
                        .as("a different contact at the same customer, not named in the share, must not")
                        .isEmpty());
    }

    /** Revoking takes effect on the very next read -- no cache, no token expiry to wait out. */
    @Test
    void revokingAShareMakesTheDocumentInvisibleOnTheVeryNextRequest() {
        UUID tenant = fixture.createTenant("doc-share-revoke-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var contactUserId = new UUID[1];
        var contactId = new UUID[1];
        var shareId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Revoke Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "revoke-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
            contactUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "revoked+" + Uuid7.generate() + "@example.com");
            contactId[0] = contactRepository.findByUserId(contactUserId[0]).orElseThrow().getId();
        });

        var shared = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                shared[0] = sharing.share(documentId[0], SharePrincipalType.CONTACT, contactId[0]));
        shareId[0] = shared[0].id();

        // Sanity: visible BEFORE revocation -- otherwise "invisible after" is vacuous.
        fixture.runAsUser(tenant, contactUserId[0], () ->
                assertThat(documents.list(Pageable.unpaged()).getContent())
                        .as("sanity check: visible before revocation")
                        .extracting(DocumentView::id).containsExactly(documentId[0]));

        var revoked = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () -> revoked[0] = sharing.revokeShare(shareId[0]));
        assertThat(revoked[0].revokedAt()).isNotNull();

        fixture.runAsUser(tenant, contactUserId[0], () ->
                assertThat(documents.list(Pageable.unpaged()).getContent())
                        .as("invisible on the very next request after revocation, no caching")
                        .isEmpty());

        fixture.runAs(tenant, () -> {
            DocumentShare row = shareRepository.findById(shareId[0]).orElseThrow();
            assertThat(row.getRevokedAt()).isNotNull();
        });
    }

    /** Revoking an already-revoked share is a no-op, not a re-stamp -- the same idempotence Task 18's retire cascade proves. */
    @Test
    void revokingAnAlreadyRevokedShareLeavesItsRevokedAtUnchanged() {
        UUID tenant = fixture.createTenant("doc-revoke-idempotent-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var shareId = new UUID[1];
        var firstRevokedAt = new Instant[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Idempotent Revoke Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "idempotent-revoke+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        var shared = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                shared[0] = sharing.share(documentId[0], SharePrincipalType.USER, manager[0]));
        shareId[0] = shared[0].id();

        var firstRevoke = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () -> firstRevoke[0] = sharing.revokeShare(shareId[0]));
        // Re-read from the database rather than trusting the returned view's
        // own in-memory Instant -- Postgres timestamptz is microsecond
        // precision, so the value this method returns (computed in Java,
        // before the round trip) can carry more precision than what a
        // SECOND read of the same row would ever see. Comparing against the
        // persisted value is what the "left unchanged" claim actually means.
        fixture.runAs(tenant, () -> firstRevokedAt[0] = shareRepository.findById(shareId[0]).orElseThrow().getRevokedAt());

        var secondRevoke = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () -> secondRevoke[0] = sharing.revokeShare(shareId[0]));

        assertThat(secondRevoke[0].revokedAt()).isEqualTo(firstRevokedAt[0]);
    }

    /**
     * Task 13's own ruling, carried forward: a CONTACT principal whose
     * customerId differs from the document's own is refused as a 400 -- a
     * cross-reference check between two already-resolved records, not the
     * acting actor's own scope.
     */
    @Test
    void sharingWithAContactAtADifferentCustomerIsRefusedAsABadRequest() {
        UUID tenant = fixture.createTenant("doc-share-cross-customer-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var otherCustomerContactId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID documentCustomerId = fixture.createCustomer(tenant, "Doc Customer " + Uuid7.generate(), null, null, null);
            UUID otherCustomerId = fixture.createCustomer(tenant, "Other Customer " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "cross-customer+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), documentCustomerId, manager[0], VisibilityTier.SENSITIVE);
            otherCustomerContactId[0] = fixture.createContact(
                    tenant, otherCustomerId, "wrong-customer+" + Uuid7.generate() + "@example.com");
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                sharing.share(documentId[0], SharePrincipalType.CONTACT, otherCustomerContactId[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The write-path invariant: a nonexistent contact id is a 404, never a 500 or a silently-created share. */
    @Test
    void sharingWithANonexistentContactIsA404() {
        UUID tenant = fixture.createTenant("doc-share-contact-404-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Missing Contact Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "missing-contact+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        UUID nonexistentContactId = Uuid7.generate();
        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                sharing.share(documentId[0], SharePrincipalType.CONTACT, nonexistentContactId)))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The same write-path invariant for USER: a user in ANOTHER TENANT is a 404, resolved through AuthorizedQuery. */
    @Test
    void sharingWithAUserInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-share-user-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-share-user-tenant-b-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var userInTenantB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            UUID customerId = fixture.createCustomer(tenantA, "Tenant A Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenantA);
            manager[0] = fixture.createUser(tenantA, "tenant-a-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenantA, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        fixture.runAs(tenantB, () ->
                userInTenantB[0] = fixture.createUser(tenantB, "tenant-b-user+" + Uuid7.generate() + "@example.com"));

        assertThatThrownBy(() -> fixture.runAsUser(tenantA, manager[0], () ->
                sharing.share(documentId[0], SharePrincipalType.USER, userInTenantB[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** DEPARTMENT resolution goes through customer.OrgUnitResolver -- a nonexistent department id is a 404. */
    @Test
    void sharingWithANonexistentDepartmentIsA404() {
        UUID tenant = fixture.createTenant("doc-share-department-404-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Missing Dept Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "missing-dept+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        UUID nonexistentDepartmentId = Uuid7.generate();
        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                sharing.share(documentId[0], SharePrincipalType.DEPARTMENT, nonexistentDepartmentId)))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * {@code document_share_live_uq} covers only LIVE rows -- sharing the same
     * already-live principal twice must not surface the partial unique index
     * as a raw constraint violation. This class's own design decision:
     * idempotent, returning the existing live share rather than 409ing.
     */
    @Test
    void sharingTheSamePrincipalTwiceIsIdempotentAndReturnsTheExistingLiveShare() {
        UUID tenant = fixture.createTenant("doc-share-idempotent-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Idempotent Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "idempotent-share+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        var first = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                first[0] = sharing.share(documentId[0], SharePrincipalType.USER, manager[0]));
        var second = new DocumentShareView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                second[0] = sharing.share(documentId[0], SharePrincipalType.USER, manager[0]));

        assertThat(second[0].id()).isEqualTo(first[0].id());
        fixture.runAs(tenant, () ->
                assertThat(shareRepository.findByDocumentId(documentId[0]))
                        .as("no duplicate row was ever inserted")
                        .hasSize(1));
    }

    /** The write-path invariant applied to share() itself: a cross-tenant documentId is a 404, never a 500. */
    @Test
    void shareOfADocumentInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-share-doc-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-share-doc-tenant-b-" + Uuid7.generate());
        var documentId = new UUID[1];
        var managerB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "share-doc-uploader+" + Uuid7.generate() + "@example.com");
            documentId[0] = createDocument(tenantA, c.getId(), c.getCustomerId(), uploader, VisibilityTier.COMPANY_SHARED);
        });

        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "share-doc-manager-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () ->
                sharing.share(documentId[0], SharePrincipalType.USER, managerB[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The same write-path invariant applied to revokeShare(): a cross-tenant shareId is a 404, never a 500. */
    @Test
    void revokeShareOfAShareInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-revoke-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-revoke-tenant-b-" + Uuid7.generate());
        var shareId = new UUID[1];
        var managerB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            UUID customerId = fixture.createCustomer(tenantA, "Revoke Tenant A Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenantA);
            UUID managerA = fixture.createUser(tenantA, "revoke-manager-a+" + Uuid7.generate() + "@example.com");
            grant(managerA, Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            UUID documentId = createDocument(tenantA, c.getId(), customerId, managerA, VisibilityTier.SENSITIVE);

            DocumentShare share = new DocumentShare(Uuid7.generate(), tenantA, documentId,
                    SharePrincipalType.USER, managerA, managerA, Instant.now(clock));
            shareRepository.saveAndFlush(share);
            shareId[0] = share.getId();
        });

        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "revoke-manager-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () -> sharing.revokeShare(shareId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * CLAUDE.md's own convention: "wherever a permission is catalogued at
     * several scopes, at least one write test must run at the narrowest one."
     * {@code document.share} is catalogued ALL/DEPARTMENT/TEAM (no ASSIGNED --
     * {@code PermissionCatalog.ORG_SCOPES}), so TEAM is the narrowest. The
     * case carries no current stage, so {@link StageWriteScopeGuard} has
     * nothing to narrow against and this proves scope resolution alone.
     */
    @Test
    void aTeamScopedDocumentShareHolderCanShareADocumentOwnedByTheirOwnTeam() {
        UUID tenant = fixture.createTenant("doc-share-team-scope-" + Uuid7.generate());
        var teamScopedActor = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopedActor[0] = fixture.createUser(tenant, "team-share+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopedActor[0], team);
            grant(teamScopedActor[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.TEAM,
                    PermissionKeys.USER_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant, null, null, team);
            documentId[0] = createDocument(tenant, c.getId(), c.getCustomerId(), teamScopedActor[0], VisibilityTier.SENSITIVE);
        });

        var view = new DocumentShareView[1];
        fixture.runAsUser(tenant, teamScopedActor[0], () ->
                view[0] = sharing.share(documentId[0], SharePrincipalType.USER, teamScopedActor[0]));

        assertThat(view[0].principalId()).isEqualTo(teamScopedActor[0]);
    }

    /**
     * The {@code write_scope} guard applies to share() exactly as it does to
     * {@code upload}/{@code addVersion}/{@code patch}/{@code retire} -- a
     * TEAM-scoped {@code document.share} holder, matching the case's own
     * team, is still refused inside an {@code OWNER_ONLY} stage when they are
     * not the case's owner, the same construction
     * {@code DocumentServiceTest.aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage}
     * uses for upload.
     */
    @Test
    void aTeamScopedShareHolderIsStillRefusedInsideAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("doc-share-ws-owner-only-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "share-ws-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_SHARE, Scope.TEAM,
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "share-ws-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Share Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            documentId[0] = createDocument(tenant, caseId, customerId, caseOwner, VisibilityTier.SENSITIVE);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () ->
                sharing.share(documentId[0], SharePrincipalType.USER, teamScopeNonOwner[0])))
                .isInstanceOf(WriteScopeException.class);
    }

    /**
     * Task 19 review finding #1: a caller sharing a document to a principal
     * that ALREADY has a live share -- one still uncommitted at the database,
     * so the Java-level idempotency pre-check
     * ({@code DocumentSharingService.liveShareTo}) cannot see it and returns
     * empty -- collides on {@code document_share_live_uq} at the database
     * itself. The loser CANNOT recover inline: an earlier version of
     * {@link DocumentSharingService#share} tried to re-read and return the
     * winner's row from inside the same catch block, but Postgres aborts the
     * whole transaction on a unique violation, so that re-read attempt itself
     * fails with {@code 25P02 current transaction is aborted} (and Hibernate
     * marks the session rollback-only on top) rather than ever returning a
     * row. It must instead be refused with {@link DuplicateDocumentShareException}.
     *
     * <p>Forces the actual DATABASE-level path deterministically, never the
     * Java pre-check, using the same "two real transactions, one thread each"
     * shape {@code journey.ReconcileConcurrencyTest} already uses for its own
     * row-lock race -- but unlike that test's {@code CyclicBarrier} (fine
     * there because the hazard window is the whole width of
     * {@code reconcile()}), the window here is only the gap between one
     * {@code saveAndFlush} and the next, far too narrow to trust to thread
     * scheduling alone. So the FIRST share is inserted directly via the
     * repository (skipping {@code share()}'s own pre-check entirely -- this
     * row is never returned by any call to {@code share}), then that
     * transaction is held open with a real {@code pg_sleep} on the database
     * side -- not a JVM sleep -- so the "still uncommitted" window is exact
     * and independent of scheduling. While it is open, the real
     * {@link DocumentSharingService#share} runs on a second thread: its own
     * pre-check runs against the database and, seeing only committed data,
     * finds nothing (the first row is not yet committed) -- exactly the
     * "cannot see it" precondition that makes this a genuine database race
     * rather than a lost idempotency check. Its insert then either blocks on
     * the first transaction's still-open row and fails once that row commits,
     * or fails immediately if the first transaction has committed by then;
     * either way it collides for real. Simply inserting the first row and
     * committing it BEFORE calling {@code share()} would not exercise this at
     * all: the Java pre-check would find that already-committed row and
     * return it idempotently, never reaching the catch block.
     */
    @Test
    void concurrentSharesToTheSamePrincipalRaceThroughTheUniqueIndexAndTheLoserIsRefused() throws Exception {
        UUID tenant = fixture.createTenant("doc-share-race-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Race Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "share-race+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);
        });

        CountDownLatch firstRowFlushed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A: inserts the FIRST live share directly, bypassing
            // share()'s own pre-check entirely, then holds the transaction
            // open for two real (database-side) seconds before letting it
            // commit -- a window thread B's own pre-check and insert attempt
            // are guaranteed to run well inside.
            Future<?> first = pool.submit(() -> fixture.runUnauthenticated(tenant, () -> {
                DocumentShare share = new DocumentShare(Uuid7.generate(), tenant, documentId[0],
                        SharePrincipalType.USER, manager[0], manager[0], Instant.now(clock));
                shareRepository.saveAndFlush(share);
                firstRowFlushed.countDown();
                entityManager.createNativeQuery("select pg_sleep(2)").getSingleResult();
            }));

            assertThat(firstRowFlushed.await(10, TimeUnit.SECONDS))
                    .as("the first share must actually flush before thread B starts")
                    .isTrue();

            var outcome = new Object[1];
            Future<?> second = pool.submit(() -> {
                try {
                    var view = new DocumentShareView[1];
                    fixture.runAsUser(tenant, manager[0], () ->
                            view[0] = sharing.share(documentId[0], SharePrincipalType.USER, manager[0]));
                    outcome[0] = view[0];
                } catch (RuntimeException e) {
                    outcome[0] = e;
                }
            });

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(outcome[0])
                    .as("racing an uncommitted duplicate live share still in flight at the database is refused"
                            + " with the dedicated conflict exception, never left to recover inline")
                    .isInstanceOf(DuplicateDocumentShareException.class);
        } finally {
            pool.shutdownNow();
        }

        fixture.runAs(tenant, () ->
                assertThat(shareRepository.findByDocumentId(documentId[0]))
                        .as("no duplicate row was ever inserted")
                        .hasSize(1));
    }

    /**
     * Task 19 review finding #3: a RETIRED document must refuse a NEW share
     * -- otherwise this would silently re-grant access that
     * {@link DocumentService#retire}'s own cascade exists specifically to
     * close. {@link IllegalStateException}, mapped to 409 globally by
     * {@code platform.ApiExceptionHandler}, is the same "the record is in
     * the wrong state for this action" shape
     * {@code authz.RoleService.deleteRole} already uses ("Role still has
     * users assigned; disable it instead") -- chosen over a new dedicated
     * exception type because this needs no domain-specific detail and the
     * mapping already exists tenant-wide with no new handler required.
     */
    @Test
    void sharingARetiredDocumentIsRefused() {
        UUID tenant = fixture.createTenant("doc-share-retired-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Retired Co " + Uuid7.generate(), null, null, null);
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "share-retired+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_SHARE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), customerId, manager[0], VisibilityTier.SENSITIVE);

            Document d = documentRepository.findById(documentId[0]).orElseThrow();
            d.setStatus(DocumentStatus.RETIRED);
            documentRepository.saveAndFlush(d);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                sharing.share(documentId[0], SharePrincipalType.USER, manager[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createDocument(UUID tenant, UUID caseId, UUID customerId, UUID uploadedBy, VisibilityTier tier) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(caseId);
        d.setCustomerId(customerId);
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(tier);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
