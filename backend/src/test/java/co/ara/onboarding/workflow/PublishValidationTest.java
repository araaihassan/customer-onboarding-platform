package co.ara.onboarding.workflow;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowFixtures.PublishScenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.anEmptyStage;
import static co.ara.onboarding.workflow.WorkflowFixtures.branchFromStage;
import static co.ara.onboarding.workflow.WorkflowFixtures.conditionOnAttribute;
import static co.ara.onboarding.workflow.WorkflowFixtures.firstMilestoneDependingOnTheSecond;
import static co.ara.onboarding.workflow.WorkflowFixtures.lastStageConditionalOn;
import static co.ara.onboarding.workflow.WorkflowFixtures.threeValidStages;
import static co.ara.onboarding.workflow.WorkflowFixtures.twoStages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishValidationTest extends PostgresTestBase {

    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired TenantFixture fixture;

    @Test
    void aValidWorkflowPublishesAndBecomesCurrent() {
        UUID tenant = fixture.createTenant("pub-ok");
        fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(threeValidStages());
            var published = publisher.publish(draftId);

            assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(published.publishedAt()).isNotNull();
            assertThat(workflows.getTemplate(published.templateId()).currentVersionId())
                    .isEqualTo(draftId);
        });
    }

    /**
     * Rule 1. Without it the skip loop can walk off the end of the stage list.
     *
     * assertThatThrownBy wraps the whole runAs call, not a nested call inside it, for
     * the reason WorkflowAuthoringTest.aStaleLockVersionIsRejected documents: catching
     * publish's exception inside the lambda leaves the (shared, still-open) runAs
     * transaction rollback-only, and the block's own commit then surfaces
     * UnexpectedRollbackException instead of the exception under test. Confirmed by
     * running this exact shape from the brief and hitting that failure, not merely
     * cited from CLAUDE.md.
     */
    @Test
    void aFinalStageWithAnEntryConditionIsRefused() {
        UUID tenant = fixture.createTenant("pub-final");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(lastStageConditionalOn("segment", "SMB"));
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("final stage");
    }

    /** Rule 2. Forward-only targets are what make the graph a DAG by construction. */
    @Test
    void aBranchTargetingAnEarlierStageIsRefused() {
        UUID tenant = fixture.createTenant("pub-backward");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(branchFromStage(3).toStage(1));
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("forward");
    }

    /** Rule 3. A forward dependency describes a plan the engine will never follow. */
    @Test
    void aDependencyPointingForwardIsRefused() {
        UUID tenant = fixture.createTenant("pub-dep");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(firstMilestoneDependingOnTheSecond());
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("earlier");
    }

    /** Rule 4. An undeclared key would evaluate false forever and skip a stage silently. */
    @Test
    void aConditionNamingAnUndeclaredAttributeIsRefused() {
        UUID tenant = fixture.createTenant("pub-attr");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(conditionOnAttribute("tier"));   // never declared
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("tier");
    }

    /** Rule 5. A stage with no milestones is exitable the instant it is entered. */
    @Test
    void aStageWithNoMilestonesIsRefused() {
        UUID tenant = fixture.createTenant("pub-empty");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(anEmptyStage());
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .hasMessageContaining("milestone");
    }

    /**
     * Every problem, not the first. An admin fixing a nine-stage workflow one error per
     * round trip is the experience this avoids, and it is also how you find out the
     * validator only implements one rule.
     */
    @Test
    void allProblemsAreReportedTogether() {
        UUID tenant = fixture.createTenant("pub-all");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(anEmptyStage().and(branchFromStage(2).toStage(1)));
            publisher.publish(draftId);
        }))
                .isInstanceOf(PublishValidationException.class)
                .satisfies(e -> assertThat(((PublishValidationException) e).problems())
                        .hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void publishingTwiceIsRefused() {
        UUID tenant = fixture.createTenant("pub-twice");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> {
            UUID draftId = draftWith(threeValidStages());
            publisher.publish(draftId);
            publisher.publish(draftId);
        })).isInstanceOf(VersionNotEditableException.class);
    }

    /**
     * Editing a published workflow makes a new draft; the old version keeps its stages
     * exactly as running cases pinned to it expect. This is Q2's freeze-by-default seen
     * from the authoring side.
     *
     * v2's lock version is 1, not 0, by the time this test can see it: createDraft's
     * deep-copy path writes the copied graph through replaceDraft before returning,
     * which bumps it once. twoStages() alone assumes "a fresh (lockVersion 0) draft"
     * per its own javadoc, which v2 is not, so the request is built from v2's own
     * current definition's lockVersion rather than the fixture's hardcoded 0 --
     * confirmed necessary by running the brief's original shape and hitting
     * OptimisticLockingFailureException here.
     */
    @Test
    void editingAfterPublishCreatesV2AndLeavesV1Intact() {
        UUID tenant = fixture.createTenant("pub-v2");
        fixture.runAs(tenant, () -> {
            UUID v1 = draftWith(threeValidStages());
            publisher.publish(v1);
            var templateId = workflows.getDefinition(v1).templateId();

            UUID v2 = workflows.createDraft(templateId);
            var v2Definition = workflows.getDefinition(v2);
            assertThat(v2Definition.versionNo()).isEqualTo(2);
            assertThat(v2Definition.stages()).hasSize(3);   // deep-copied

            workflows.replaceDraft(v2, new WorkflowDefinitionRequest(
                    twoStages().stages(), twoStages().attributes(), v2Definition.lockVersion()));
            assertThat(workflows.getDefinition(v1).stages()).hasSize(3);   // untouched
        });
    }

    /** The one call in this DSL that needs a live WorkflowService rather than just request records. */
    private UUID draftWith(PublishScenario scenario) {
        return WorkflowFixtures.draftWith(workflows, scenario);
    }
}
