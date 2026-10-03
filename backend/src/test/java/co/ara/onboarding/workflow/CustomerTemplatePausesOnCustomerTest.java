package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project 6, Task 22 (carried from the Task 4 review): a stage's {@code pausesOnCustomer=false}
 * must survive both customer-template paths -- clone and refresh-from-source -- which copy a graph
 * through {@code WorkflowService}'s view-to-request round trip rather than a column-by-column copy.
 */
class CustomerTemplatePausesOnCustomerTest extends PostgresTestBase {

    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired WorkflowVersionRepository versions;
    @Autowired TenantFixture fixture;

    private WorkflowDefinitionRequest sourceGraph() {
        var base = WorkflowFixtures.stage("s1", "One", List.of(
                milestone("m1", "M", 1, List.of(), List.of(manual("Do it")))));
        var stage = new WorkflowDefinitionRequest.StageRequest(base.key(), base.name(), null, false,
                true, true, 3, base.writeScope(), null, null, null, base.milestones(),
                base.branchRules(), false);
        return new WorkflowDefinitionRequest(List.of(stage), List.of(), 0L);
    }

    @Test
    void falseSurvivesCloneAndRefresh() {
        UUID tenant = fixture.createTenant("pauses-ct-" + Uuid7.generate());
        var cloneVersion = new AtomicReference<UUID>();
        var refreshVersion = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID customer = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID source = workflows.createTemplate("Standard", "").id();
            UUID draft = workflows.createDraft(source);
            workflows.replaceDraft(draft, sourceGraph());
            publisher.publish(draft);

            var clone = customerTemplates.clone(source, new CloneTemplateRequest(customer, "Acme Onboarding"));
            UUID cloneDraft = versions.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
            cloneVersion.set(cloneDraft);
            assertThat(workflows.getDefinition(cloneDraft).stages().get(0).pausesOnCustomer())
                    .as("after clone").isFalse();

            publisher.publish(cloneDraft);
            refreshVersion.set(customerTemplates.refreshFromSource(clone.id()).versionId());
            assertThat(workflows.getDefinition(refreshVersion.get()).stages().get(0).pausesOnCustomer())
                    .as("after refresh").isFalse();
        });
    }
}
