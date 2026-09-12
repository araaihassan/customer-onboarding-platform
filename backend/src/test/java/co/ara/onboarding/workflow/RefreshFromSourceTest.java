package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-project 3A, Task 17 (QA Q21, spec 5.2): refreshing a customer's own
 * clone from its catalogue source. {@link CustomerTemplateService#refreshFromSource}
 * REPLACES the customer template's graph with a fresh deep copy of the
 * source's current published version -- it never merges the customer's own
 * prior tailoring in, because a three-way merge would need per-node identity
 * across two lineages that Q2's freeze deliberately severs.
 *
 * {@code acmeCloneId} starts every test already published with no open draft
 * (this class's own BeforeEach publishes the clone's initial version) --
 * that is the precondition refreshFromSource's own "no open draft" refusal
 * assumes, the same starting shape a real customer template sits in between
 * refreshes.
 */
class RefreshFromSourceTest extends PostgresTestBase {

    @Autowired CustomerTemplateService customerTemplateService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired TenantFixture fixture;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;

    private UUID admin;
    private UUID acme;
    private UUID standardId;
    private UUID acmeCloneId;

    @BeforeEach
    void seedCatalogueAndPublishedCustomerClone() {
        admin = fixture.createTenant("refresh-admin-" + Uuid7.generate());

        var acmeRef = new AtomicReference<UUID>();
        var standardRef = new AtomicReference<UUID>();
        var cloneRef = new AtomicReference<UUID>();

        fixture.runAs(admin, () -> {
            acmeRef.set(fixture.createCustomer(admin, "Acme", null, null, null));

            var template = workflows.createTemplate("Standard Onboarding", "");
            UUID draftId = workflows.createDraft(template.id());
            workflows.replaceDraft(draftId, baseGraph());
            publishService.publish(draftId);
            standardRef.set(template.id());

            WorkflowTemplateView clone = customerTemplateService.clone(
                    standardRef.get(), new CloneTemplateRequest(acmeRef.get(), "Acme Onboarding"));

            // clone() only ever leaves a fresh DRAFT (Task 16) -- published here so
            // this customer template starts with a real baseline and no open draft.
            UUID cloneDraftId = versions.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
            publishService.publish(cloneDraftId);
            cloneRef.set(clone.id());
        });

        acme = acmeRef.get();
        standardId = standardRef.get();
        acmeCloneId = cloneRef.get();
    }

    @Test
    void refreshDeepCopiesTheSourcesPublishedVersionIntoANewDraftOfTheCustomerTemplate() {
        publishANewVersionOf(standardId, "extra-stage");

        var draftRef = new AtomicReference<WorkflowDefinitionView>();
        fixture.runAs(admin, () -> draftRef.set(customerTemplateService.refreshFromSource(acmeCloneId)));
        WorkflowDefinitionView draft = draftRef.get();

        assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(draft.templateId()).isEqualTo(acmeCloneId);       // the CUSTOMER's template
        assertThat(stageKeysOf(draft.versionId())).contains("extra-stage");
    }

    @Test
    void refreshReplacesRatherThanMerging() {
        // The customer's tailoring is NOT carried across. This is the stated cost of
        // Q21's answer (spec 5.2): a three-way merge would need per-node identity across
        // two lineages that Q2's freeze deliberately severs.
        tailorAndPublish(acmeCloneId, "acme-only-stage");

        var draftRef = new AtomicReference<WorkflowDefinitionView>();
        fixture.runAs(admin, () -> draftRef.set(customerTemplateService.refreshFromSource(acmeCloneId)));
        WorkflowDefinitionView draft = draftRef.get();

        assertThat(stageKeysOf(draft.versionId())).doesNotContain("acme-only-stage");
    }

    @Test
    void refreshIsRefusedWhileTheCustomerTemplateAlreadyHasADraft() {
        fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId));
        assertThatThrownBy(() -> fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(acmeCloneId)))
                .isInstanceOf(DraftAlreadyExistsException.class);
    }

    @Test
    void aCatalogueTemplateHasNoSourceToRefreshFrom() {
        assertThatThrownBy(() -> fixture.runAs(admin, () -> customerTemplateService.refreshFromSource(standardId)))
                .isInstanceOf(NotCloneableException.class);
    }

    // ---- helpers --------------------------------------------------------------

    private WorkflowDefinitionRequest baseGraph() {
        return new WorkflowDefinitionRequest(
                List.of(
                        stage("s1", "Stage One", List.of(
                                milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up"))))),
                        stage("s2", "Stage Two", List.of(
                                milestone("m2", "Follow-up", 1, List.of(), List.of(manual("Check in")))))),
                List.of(), 0L);
    }

    private WorkflowDefinitionRequest baseGraphPlus(String extraStageKey, long lockVersion) {
        return new WorkflowDefinitionRequest(
                List.of(
                        stage("s1", "Stage One", List.of(
                                milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up"))))),
                        stage("s2", "Stage Two", List.of(
                                milestone("m2", "Follow-up", 1, List.of(), List.of(manual("Check in"))))),
                        stage(extraStageKey, extraStageKey, List.of(
                                milestone(extraStageKey + "-m", extraStageKey, 1, List.of(), List.of(manual("Extra")))))),
                List.of(), lockVersion);
    }

    /**
     * Publishes a NEW version of the catalogue source, adding one stage to its
     * graph. createDraft's own copy branch already seeds the new draft's
     * lockVersion past 0 (CLAUDE.md's own "a freshly created draft's lockVersion
     * is not reliably 0" note) -- read back the real one rather than assuming it.
     */
    private void publishANewVersionOf(UUID templateId, String extraStageKey) {
        fixture.runAs(admin, () -> {
            UUID draftId = workflows.createDraft(templateId);
            long lockVersion = workflows.getDefinition(draftId).lockVersion();
            workflows.replaceDraft(draftId, baseGraphPlus(extraStageKey, lockVersion));
            publishService.publish(draftId);
        });
    }

    /** Publishes a NEW version of the CUSTOMER'S OWN template, tailoring in one stage. */
    private void tailorAndPublish(UUID customerTemplateId, String extraStageKey) {
        fixture.runAs(admin, () -> {
            UUID draftId = workflows.createDraft(customerTemplateId);
            long lockVersion = workflows.getDefinition(draftId).lockVersion();
            workflows.replaceDraft(draftId, baseGraphPlus(extraStageKey, lockVersion));
            publishService.publish(draftId);
        });
    }

    /**
     * A stage's persisted key (WorkflowDefinitionView.StageView.key()) is its own
     * freshly generated id, not the client-local key an authoring request used
     * (see CloneTest's own stageNamesOf) -- so this reads back NAMES, which is
     * exactly what every stage() call above sets equal to its own client key,
     * making the two interchangeable for this test's purposes.
     */
    private List<String> stageKeysOf(UUID versionId) {
        var result = new AtomicReference<List<String>>();
        fixture.runAs(admin, () -> result.set(
                stages.findByVersionIdOrderByOrdinal(versionId).stream().map(Stage::getName).toList()));
        return result.get();
    }
}
