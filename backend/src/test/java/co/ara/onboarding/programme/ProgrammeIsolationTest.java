package co.ara.onboarding.programme;

import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13: a cross-tenant id is consistently a 404, never the 200 a bypassed-RLS
 * FK check would produce, nor the 500 an invented id does (CLAUDE.md's own
 * cross-project invariant) -- proven here for programme's two id-taking surfaces:
 * reading a programme itself, and the case id {@link ProgrammeMembershipService
 * #addJourney} takes from a request body.
 */
class ProgrammeIsolationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired ProgrammeService programmeService;
    @Autowired ProgrammeMembershipService membershipService;

    @Test
    void aProgrammeFromAnotherTenantIs404() {
        UUID tenantA = fixture.createTenant("prog-iso-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("prog-iso-b-" + Uuid7.generate());
        var foreign = new UUID[1];

        fixture.runAs(tenantB, () -> {
            UUID customerId = fixture.createCustomer(tenantB, "Foreign Co " + Uuid7.generate(), null, null, null);
            foreign[0] = programmeService.create(new CreateProgrammeRequest(
                    "Foreign Programme", customerId, null, null, null, null)).id();
        });

        // 404, never 200 (a bypassed-RLS FK check) and never 500 (an invented id).
        assertThatThrownBy(() -> fixture.runAs(tenantA, () -> programmeService.get(foreign[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aCaseFromAnotherTenantCannotBeAddedToAProgramme() {
        UUID tenantA = fixture.createTenant("prog-iso-case-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("prog-iso-case-b-" + Uuid7.generate());
        var programmeA = new UUID[1];
        var foreignCaseId = new UUID[1];

        fixture.runAs(tenantA, () -> {
            UUID customerId = fixture.createCustomer(tenantA, "Acme " + Uuid7.generate(), null, null, null);
            programmeA[0] = programmeService.create(new CreateProgrammeRequest(
                    "Programme A", customerId, null, null, null, null)).id();
        });
        fixture.runAs(tenantB, () -> foreignCaseId[0] = journey.newCase(tenantB).getId());

        // The case id comes from a request body, so it must resolve through
        // AuthorizedQuery under case.view BEFORE the programme_case link is
        // written -- a foreign-tenant id 404s here, exactly like every other
        // write-path id in the codebase.
        assertThatThrownBy(() -> fixture.runAs(tenantA, () -> membershipService.addJourney(
                programmeA[0], new AddJourneyRequest(foreignCaseId[0]))))
                .isInstanceOf(NoSuchElementException.class);
    }
}
