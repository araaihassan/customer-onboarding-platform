package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseEditTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RoleService roles;

    /** A full replace, so the view must carry everything the request accepts. */
    @Test
    void updatingReplacesTheOwnershipTripleAndAttributes() {
        UUID tenant = fixture.createTenant("case-edit-replace");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplateWithSegmentAttribute();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var created = cases.create(new CreateCaseRequest(customerId, templateId,
                    Map.of("segment", "ENTERPRISE")));

            UUID newOwner = fixture.createUser(tenant, "new-owner@example.com");
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Onboarding Team");

            var updated = cases.update(created.id(), new UpdateCaseRequest(newOwner, department, team,
                    Map.of("segment", "SMB")));

            assertThat(updated.ownerUserId()).isEqualTo(newOwner);
            assertThat(updated.owningDepartmentId()).isEqualTo(department);
            assertThat(updated.owningTeamId()).isEqualTo(team);
            assertThat(updated.attributes()).containsEntry("segment", "SMB");
        });
    }

    /**
     * The field that would silently erase: attributes omitted from the body must not
     * blank the case's answers. CLAUDE.md's full-replace invariant says a PUT means
     * replace, so the request carries them and the view returns them -- there is no
     * "just don't send it" mitigation.
     */
    @Test
    void omittingAnAttributeFromAnUpdateIsRejectedRatherThanBlanking() {
        UUID tenant = fixture.createTenant("case-edit-omit");
        var caseId = new AtomicReference<UUID>();
        var owner = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplateWithSegmentAttribute();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            caseId.set(cases.create(new CreateCaseRequest(customerId, templateId,
                    Map.of("segment", "ENTERPRISE"))).id());
            owner.set(fixture.createUser(tenant, "owner-omit@example.com"));
        });

        // Never assert inside the runAs lambda -- see aMissingRequiredAttributeIsRejected
        // in CaseCreationTest for why: the whole runAs call is wrapped instead.
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.update(caseId.get(),
                new UpdateCaseRequest(owner.get(), null, null, Map.of()))))
                .isInstanceOf(AttributeValidationException.class);
    }

    /**
     * Changing the owner re-points DEPARTMENT/TEAM/ASSIGNED scope for the whole case, so
     * it is resolved under CASE_EDIT and the new owner becomes an OWNER participant --
     * otherwise the case has an owner who cannot open it.
     */
    @Test
    void changingTheOwnerAddsAnOwnerParticipant() {
        UUID tenant = fixture.createTenant("case-edit-owner-participant");
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var created = cases.create(new CreateCaseRequest(customerId, templateId, Map.of()));

            UUID newOwner = fixture.createUser(tenant, "owner-added@example.com");
            cases.update(created.id(), new UpdateCaseRequest(newOwner, null, null, Map.of()));

            assertThat(cases.participants(created.id())).anySatisfy(p -> {
                assertThat(p.userId()).isEqualTo(newOwner);
                assertThat(p.relationship()).isEqualTo(RelationshipType.OWNER);
            });
        });
    }

    @Test
    void aRemovedParticipantLosesAssignedAccessOnTheNextRequest() {
        UUID tenant = fixture.createTenant("case-edit-remove-participant");
        var caseId = new AtomicReference<UUID>();
        var participant = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var created = cases.create(new CreateCaseRequest(customerId, templateId, Map.of()));
            caseId.set(created.id());

            UUID userId = fixture.createUser(tenant, "participant@example.com");
            participant.set(userId);
            cases.addParticipant(created.id(), userId, RelationshipType.PARTICIPANT);
            // WORKFLOW_VIEW ALL joins every real operational role template
            // (RoleTemplates) alongside CASE_VIEW: rendering a case's own view
            // reads its pinned WorkflowVersion for versionNo, which is gated on
            // WORKFLOW_VIEW, not CASE_VIEW.
            grantRole(tenant, userId, Map.of(
                    PermissionKeys.CASE_VIEW, Scope.ASSIGNED, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        });

        fixture.runAsUser(tenant, participant.get(), () ->
                assertThat(cases.get(caseId.get())).isNotNull());

        fixture.runAs(tenant, () -> cases.removeParticipant(caseId.get(), participant.get()));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, participant.get(),
                () -> cases.get(caseId.get())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void addingAParticipantRequiresCaseEdit() {
        UUID tenant = fixture.createTenant("case-edit-requires-edit");
        var caseId = new AtomicReference<UUID>();
        var viewer = new AtomicReference<UUID>();
        var someoneElse = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            caseId.set(cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id());

            UUID userId = fixture.createUser(tenant, "viewer-only@example.com");
            viewer.set(userId);
            grantRole(tenant, userId, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
            someoneElse.set(fixture.createUser(tenant, "someone-else@example.com"));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, viewer.get(), () ->
                cases.addParticipant(caseId.get(), someoneElse.get(), RelationshipType.PARTICIPANT)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aUserFromAnotherTenantCannotBeAddedAsAParticipant() {
        UUID tenantA = fixture.createTenant("case-edit-cross-a");
        UUID tenantB = fixture.createTenant("case-edit-cross-b");
        var tenantBUserId = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenantB, () -> tenantBUserId.set(fixture.createUser(tenantB, "cross@example.com")));
        fixture.runAs(tenantA, () -> {
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenantA, "Acme", null, null, null);
            caseId.set(cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id());
        });

        // the user id comes from a request body, so it resolves through AuthorizedQuery
        // under USER_VIEW before the row is written -- 404, never a cross-tenant row
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                cases.addParticipant(caseId.get(), tenantBUserId.get(), RelationshipType.PARTICIPANT)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private void grantRole(UUID tenantId, UUID userId, Map<String, Scope> grants) {
        UUID roleId = roles.createRole("Grant-" + Uuid7.generate(), "", grants);
        roles.assignRole(userId, roleId);
    }
}
