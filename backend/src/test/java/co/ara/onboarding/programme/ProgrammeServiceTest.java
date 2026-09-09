package co.ara.onboarding.programme;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 12: ProgrammeService create/read/update/deactivate, and the write-path
 * guard on customerId -- the escalation shape that bit sub-project 1 three
 * times (contact creation, role assignment, invitation issuance).
 */
class ProgrammeServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired ProgrammeService programmeService;
    @Autowired ProgrammeRepository programmeRepository;
    @Autowired ProgrammeParticipantRepository participantRepository;
    @Autowired RoleService roles;

    @Test
    void createResolvesTheCustomerThroughAuthorizedQueryBeforeWriting() {
        UUID tenant = fixture.createTenant("programme-escalation");
        var narrowActor = new UUID[1];
        var foreignCustomer = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID actorsDepartment = fixture.createDepartment(tenant, "Actor's Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Another Department");
            narrowActor[0] = fixture.createUserInDepartment(tenant, "narrow-pm@example.com", actorsDepartment);
            // programme.create is ALL-only in the catalog -- there is no narrower
            // grant to test it at -- but customer.view is RECORD-scoped, and this
            // actor holds it only at DEPARTMENT, over their OWN department. A
            // passing programme.create gate proves only that the actor may create
            // A programme, never that they may see this particular customer.
            grant(narrowActor[0], Map.of(
                    PermissionKeys.PROGRAMME_CREATE, Scope.ALL,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));

            // Owned by the OTHER department -- outside the narrow actor's own
            // customer.view scope.
            foreignCustomer[0] = fixture.createCustomer(
                    tenant, "Foreign Co " + Uuid7.generate(), null, otherDepartment, null);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor[0], () -> programmeService.create(
                new CreateProgrammeRequest("P", foreignCustomer[0], null, null, null, null))))
                .isInstanceOf(NoSuchElementException.class);

        // Nothing half-written: the customer resolution failure must have
        // happened before the programme row (or any audit record of it) was
        // ever saved.
        fixture.runAs(tenant, () -> assertThat(programmesFor(foreignCustomer[0])).isEmpty());
    }

    @Test
    void updateIsAFullReplaceAndTheViewCarriesEveryFieldTheRequestAccepts() {
        // Field-for-field alignment: a field on UpdateProgrammeRequest with no twin on
        // ProgrammeView makes every client silently erase it on the next PUT.
        Set<String> requestFields = componentNames(UpdateProgrammeRequest.class);
        Set<String> viewFields = componentNames(ProgrammeView.class);
        assertThat(viewFields).containsAll(requestFields);
    }

    /**
     * The "what does deactivation revoke?" question, answered in code rather
     * than convention (CLAUDE.md's required design question for any
     * deactivatable entity). Traced against the actual predicate-combination
     * code (AuthorizationPredicateBuilder.forPermission,
     * ProgrammeDescriptor.assignedScope) before writing this: assignedScope
     * previously read only programme_participant and never Programme.status,
     * so this test failed red for a real reason, not a hypothetical one --
     * fixed alongside this task, see ProgrammeDescriptor's own doc comment.
     */
    @Test
    void deactivationRevokesTheCrossJourneyReadStructurally() {
        UUID tenant = fixture.createTenant("programme-deactivation");
        var sponsor = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            sponsor[0] = fixture.createUser(tenant, "sponsor@example.com");
            // ASSIGNED is the only scope granted for programme.view -- resolved
            // through programme_participant (ProgrammeDescriptor), no DEPARTMENT/
            // TEAM/ALL grant to fall back on, so this proves the
            // participation-mediated read specifically, not a wider one masking it.
            // customer.view ALL is granted alongside it: ProgrammeService.get's own
            // doc comment names this cross-permission dependency (resolving
            // customerName needs customer.view over the programme's own customer,
            // the same shape CLAUDE.md documents for case.view/workflow.view) --
            // this test is about the ASSIGNED/deactivation interaction, not that
            // dependency, so it is granted widely rather than narrowly here.
            grant(sponsor[0], Map.of(PermissionKeys.PROGRAMME_VIEW, Scope.ASSIGNED,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));

            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Sponsor's Programme", customerId, null, null, null, null)).id();
            addParticipant(tenant, programmeId[0], sponsor[0]);
        });

        // Before deactivation: the sponsor's participation genuinely grants the read.
        fixture.runAsUser(tenant, sponsor[0], () -> assertThat(programmeService.get(programmeId[0]).id())
                .isEqualTo(programmeId[0]));

        fixture.runAs(tenant, () -> programmeService.deactivate(programmeId[0]));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, sponsor[0], () -> programmeService.get(programmeId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The other half of the descriptor investigation: a DEPARTMENT-scoped
     * holder is deliberately UNAFFECTED by deactivation -- ProgrammeDescriptor's
     * departmentScope/teamScope were not given the same ACTIVE-only treatment
     * as assignedScope, on purpose (governance/reporting access to a retired
     * programme is not the same grant as participation-mediated access). This
     * pins that choice down as a test, not just a comment, so a future change
     * to departmentScope has to break a named assertion rather than an
     * unwritten expectation.
     */
    @Test
    void deactivationDoesNotAffectADepartmentScopedReader() {
        UUID tenant = fixture.createTenant("programme-deactivation-department");
        var deptReader = new UUID[1];
        var programmeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Owning Department");
            deptReader[0] = fixture.createUserInDepartment(tenant, "dept-reader@example.com", department);
            // customer.view ALL alongside programme.view DEPARTMENT -- see the
            // sponsor test above for why this second grant is needed at all.
            grant(deptReader[0], Map.of(PermissionKeys.PROGRAMME_VIEW, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            programmeId[0] = programmeService.create(new CreateProgrammeRequest(
                    "Department Programme", customerId, null, null, department, null)).id();
            programmeService.deactivate(programmeId[0]);
        });

        fixture.runAsUser(tenant, deptReader[0], () -> assertThat(programmeService.get(programmeId[0]).status())
                .isEqualTo(ProgrammeStatus.INACTIVE));
    }

    private List<Programme> programmesFor(UUID customerId) {
        return programmeRepository.findAll().stream()
                .filter(p -> customerId.equals(p.getCustomerId()))
                .toList();
    }

    private void addParticipant(UUID tenant, UUID programmeId, UUID userId) {
        ProgrammeParticipant p = new ProgrammeParticipant();
        p.setId(Uuid7.generate());
        p.setTenantId(tenant);
        p.setProgrammeId(programmeId);
        p.setUserId(userId);
        p.setRelationshipType(RelationshipType.PARTICIPANT);
        p.setStatus(ProgrammeParticipantStatus.ACTIVE);
        participantRepository.saveAndFlush(p);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private Set<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(toSet());
    }
}
