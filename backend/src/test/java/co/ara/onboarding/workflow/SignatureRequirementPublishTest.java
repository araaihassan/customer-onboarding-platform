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
