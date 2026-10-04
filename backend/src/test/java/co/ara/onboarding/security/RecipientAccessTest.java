package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RecipientAccess;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B spec 7.2: {@link RecipientAccess} answers whether ANOTHER user -- a notification
 * recipient -- can view a record. Every test evaluates while authenticated as somebody
 * other than the recipient, so a mechanism that consulted the caller's grants (the
 * request-scoped AuthorizationService) instead of the recipient's would fail here.
 *
 * Negative cases dominate on purpose: a "can view" that is wrong in the permissive
 * direction sends a record's existence (and title) to somebody who must not see it.
 */
class RecipientAccessTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired RoleService roles;
    @Autowired RecipientAccess access;
    @Autowired DocumentRepository documents;
    @Autowired EntityManager em;

    @Test
    void anInScopeTeamViewerCanView() {
        UUID t = fixture.createTenant("ra-team-in");
        var viewer = new AtomicReference<UUID>();
        var caller = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            UUID team = fixture.createTeam(t, "Team T");
            viewer.set(fixture.createUser(t, "viewer@ra-team-in.test"));
            fixture.addToTeam(t, viewer.get(), team);
            grant(viewer.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
            caller.set(fixture.createUser(t, "nobody@ra-team-in.test"));
            caseId.set(journey.newCase(t, null, null, team).getId());
        });

        // The caller holds nothing at all: the positive answer must come from the
        // recipient's grants, not from the caller's.
        fixture.runAsUser(t, caller.get(), () ->
                assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("a TEAM-scoped case.view holder in the owning team can view")
                        .isTrue());
    }

    @Test
    void anOutOfScopeUserCannot() {
        UUID t = fixture.createTenant("ra-team-out");
        var viewer = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            UUID mine = fixture.createTeam(t, "Team T");
            UUID other = fixture.createTeam(t, "Team U");
            viewer.set(fixture.createUser(t, "viewer@ra-team-out.test"));
            fixture.addToTeam(t, viewer.get(), mine);
            grant(viewer.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
            caseId.set(journey.newCase(t, null, null, other).getId());
        });

        fixture.runAs(t, () ->
                assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("a case owned by another team is out of TEAM scope")
                        .isFalse());
    }

    @Test
    void theAnswerIsTheRecipientsNotTheCallers() {
        UUID t = fixture.createTenant("ra-not-caller");
        var nobody = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            nobody.set(fixture.createUser(t, "nobody@ra-not-caller.test"));
            caseId.set(journey.newCase(t).getId());
        });

        // The caller is the tenant administrator (CASE_VIEW at ALL); the recipient holds nothing.
        fixture.runAs(t, () ->
                assertThat(access.canView(nobody.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("the administrator's own ALL scope must never answer for a recipient holding nothing")
                        .isFalse());
    }

    @Test
    void theAnswerIsComputedFreshNotCachedWithinARequest() {
        UUID t = fixture.createTenant("ra-fresh");
        var viewer = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            viewer.set(fixture.createUser(t, "viewer@ra-fresh.test"));
            caseId.set(journey.newCase(t).getId());
        });

        // One request, one transaction: a first "no", then a grant, then the same question.
        fixture.runAs(t, () -> {
            assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                    .as("before the grant").isFalse();
            grant(viewer.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
            // GrantLookup reads user_role through plain JDBC, which never triggers a Hibernate
            // auto-flush; flush so the grant is in the database, as a committed one would be.
            em.flush();
            assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                    .as("the grant is visible on the very next call -- no memoised answer")
                    .isTrue();
        });
    }

    @Test
    void aDeactivatedUserCannot() {
        UUID t = fixture.createTenant("ra-deactivated");
        var viewer = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            UUID team = fixture.createTeam(t, "Team T");
            viewer.set(fixture.createUser(t, "viewer@ra-deactivated.test"));
            fixture.addToTeam(t, viewer.get(), team);
            grant(viewer.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
            caseId.set(journey.newCase(t, null, null, team).getId());
        });

        fixture.runAs(t, () ->
                assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("precondition: in scope while ACTIVE").isTrue());

        ownerJdbc().update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", viewer.get());

        fixture.runAs(t, () ->
                assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("a deactivated user holds nothing, whatever roles remain assigned")
                        .isFalse());
    }

    @Test
    void aPortalUserCannotEvenForTheirOwnCustomersCase() {
        UUID t = fixture.createTenant("ra-portal");
        var customerId = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            customerId.set(fixture.createCustomer(t, "Portal Co", null, null, null));
            caseId.set(journey.newCaseForCustomer(t, customerId.get()).getId());
        });
        UUID portalUser = fixture.createPortalUserForContact(t, customerId.get(), "contact@ra-portal.test");

        fixture.runAs(t, () -> {
            assertThat(access.canView(portalUser, PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                    .as("portal recipients are out of 6B's scope: always false, even for their own case")
                    .isFalse();
            assertThat(access.canView(portalUser, PermissionKeys.DOCUMENT_VIEW, Document.class, Uuid7.generate()))
                    .isFalse();
        });
    }

    @Test
    void aTargetedDocumentIsInvisibleToAnAllScopedReaderOutsideItsAudience() {
        UUID t = fixture.createTenant("ra-doc-audience");
        var outsider = new AtomicReference<UUID>();
        var insider = new AtomicReference<UUID>();
        var docId = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            UUID deptA = fixture.createDepartment(t, "Department A");
            UUID deptB = fixture.createDepartment(t, "Department B");
            outsider.set(fixture.createUserInDepartment(t, "a@ra-doc-audience.test", deptA));
            insider.set(fixture.createUserInDepartment(t, "b@ra-doc-audience.test", deptB));
            grant(outsider.get(), Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            grant(insider.get(), Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
            Case c = journey.newCase(t);
            docId.set(newDocument(t, c, insider.get(), deptB));
        });

        // The caller (administrator, document.view at ALL, in no department) would itself be
        // refused this document too; the outsider/insider split proves the recipient's own
        // department is what the audience filter reads.
        fixture.runAs(t, () -> {
            assertThat(access.canView(outsider.get(), PermissionKeys.DOCUMENT_VIEW, Document.class, docId.get()))
                    .as("DocumentAudienceFilter narrows even ALL scope: department A cannot see a B-targeted document")
                    .isFalse();
            assertThat(access.canView(insider.get(), PermissionKeys.DOCUMENT_VIEW, Document.class, docId.get()))
                    .as("a department-B reader is inside the audience")
                    .isTrue();
        });
    }

    @Test
    void aUserFromAnotherTenantCannot() {
        UUID tenantA = fixture.createTenant("ra-tenant-a");
        UUID tenantB = fixture.createTenant("ra-tenant-b");
        var strangerRef = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenantA, () -> {
            strangerRef.set(fixture.createUser(tenantA, "stranger@ra-tenant-a.test"));
            grant(strangerRef.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        });
        fixture.runAs(tenantB, () -> caseId.set(journey.newCase(tenantB).getId()));

        fixture.runAs(tenantB, () ->
                assertThat(access.canView(strangerRef.get(), PermissionKeys.CASE_VIEW, Case.class, caseId.get()))
                        .as("an ALL-scoped user of tenant A is nobody in tenant B")
                        .isFalse());
    }

    @Test
    void anUnknownIdIsFalseNotAnException() {
        UUID t = fixture.createTenant("ra-unknown");
        var viewer = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            viewer.set(fixture.createUser(t, "viewer@ra-unknown.test"));
            grant(viewer.get(), Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        });

        fixture.runAs(t, () -> {
            assertThat(access.canView(viewer.get(), PermissionKeys.CASE_VIEW, Case.class, Uuid7.generate()))
                    .as("an unknown record id").isFalse();
            assertThat(access.canView(Uuid7.generate(), PermissionKeys.CASE_VIEW, Case.class, Uuid7.generate()))
                    .as("an unknown user id").isFalse();
            assertThat(access.canView(null, PermissionKeys.CASE_VIEW, Case.class, null))
                    .as("null ids").isFalse();
        });
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    /** Copied from scoping.DocumentScopingTest's newDocument, with a target department set. */
    private UUID newDocument(UUID tenant, Case c, UUID uploadedBy, UUID targetDepartmentId) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setTargetDepartmentId(targetDepartmentId);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documents.saveAndFlush(d).getId();
    }
}
