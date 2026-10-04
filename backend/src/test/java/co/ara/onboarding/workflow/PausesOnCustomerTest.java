package co.ara.onboarding.workflow;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;

/** Sub-project 6, Task 4: stage.pauses_on_customer, boxed so an omitted key means true (spec 8). */
class PausesOnCustomerTest extends PostgresTestBase {

    @Autowired ObjectMapper json;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired TenantFixture fixture;

    @Test
    void anOmittedKeyDeserialisesToTrueNotFalse() throws Exception {
        var request = json.readValue("""
                {"key":"s1","name":"S","autoAdvance":true,"milestones":[],"branchRules":[]}""",
                WorkflowDefinitionRequest.StageRequest.class);
        assertThat(request.pausesOnCustomer()).isNull();
        assertThat(WorkflowService.pausesOnCustomer(request)).isTrue();
    }

    @Test
    void anExplicitFalseIsKept() throws Exception {
        var request = json.readValue("""
                {"key":"s1","name":"S","autoAdvance":true,"pausesOnCustomer":false,"milestones":[],"branchRules":[]}""",
                WorkflowDefinitionRequest.StageRequest.class);
        assertThat(WorkflowService.pausesOnCustomer(request)).isFalse();
    }

    @Test
    void theOldArityConstructorDefaultsToNull() {
        var s = new WorkflowDefinitionRequest.StageRequest("k", "n", null, false, true, true, null,
                null, null, null, null, List.of(), List.of());
        assertThat(s.pausesOnCustomer()).isNull();
    }

    @Test
    void aSavedDraftRoundTripsTheFlag() {
        UUID tenant = fixture.createTenant("pauses-roundtrip");
        fixture.runAs(tenant, () -> {
            UUID templateId = workflows.createTemplate("Pauses", "").id();
            UUID draftId = workflows.createDraft(templateId);
            var base = WorkflowFixtures.stage("s1", "One", List.of(
                    milestone("m1", "M", 1, List.of(), List.of(manual("Do it")))));
            var stage = new WorkflowDefinitionRequest.StageRequest(base.key(), base.name(), null, false,
                    true, true, null, base.writeScope(), null, null, null, base.milestones(),
                    base.branchRules(), false);
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(List.of(stage), List.of(), 0L));
            assertThat(workflows.getDefinition(draftId).stages().get(0).pausesOnCustomer()).isFalse();

            publisher.publish(draftId);
            UUID copy = workflows.createDraft(templateId);
            assertThat(workflows.getDefinition(copy).stages().get(0).pausesOnCustomer()).isFalse();
        });
    }
}
