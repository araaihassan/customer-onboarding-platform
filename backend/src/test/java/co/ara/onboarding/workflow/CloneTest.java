package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-project 3A, Task 16 (QA Q21): cloning a catalogue template for one
 * customer. {@link CustomerTemplateService#clone} is the only path that ever
 * writes a non-null {@code workflow_template.customer_id} -- Task 15's
 * {@link CustomerTemplateSchemaTest} already proved the database's own
 * partial unique index; this class proves the service layer built on top of it.
 *
 * Two tenants, not one: {@code admin} holds everything the "happy" and
 * "refused" scenarios need (the catalogue template, both customers), and
 * {@code narrowAdmin} exists purely to give {@code theCustomerIdIsResolved...}
 * a customer id that is genuinely foreign -- belonging to a tenant other than
 * the one bound when {@code clone} runs -- rather than merely an invented one,
 * which is exactly the "cross-tenant id is a 404, never the 500 an invented id
 * gives" distinction CLAUDE.md's own sub-project 2 cross-check names.
 */
class CloneTest extends PostgresTestBase {

    @Autowired CustomerTemplateService customerTemplateService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired TenantFixture fixture;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;

    private UUID admin;
    private UUID narrowAdmin;
    private UUID acme;
    private UUID globex;
    private UUID standardId;
    private UUID draftOnlyTemplateId;

    @BeforeEach
    void seedTenantsCustomersAndTemplates() {
        admin = fixture.createTenant("clone-admin-" + Uuid7.generate());
        narrowAdmin = fixture.createTenant("clone-narrow-" + Uuid7.generate());

        var acmeRef = new AtomicReference<UUID>();
        var globexRef = new AtomicReference<UUID>();
        var standardRef = new AtomicReference<UUID>();
        var draftOnlyRef = new AtomicReference<UUID>();

        fixture.runAs(admin, () -> {
            acmeRef.set(fixture.createCustomer(admin, "Acme", null, null, null));
            globexRef.set(fixture.createCustomer(admin, "Globex", null, null, null));

            var template = workflows.createTemplate("Standard Onboarding", "");
            UUID draftId = workflows.createDraft(template.id());
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(
                    List.of(
                            stage("s1", "Stage One", List.of(
                                    milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up"))))),
                            stage("s2", "Stage Two", List.of(
                                    milestone("m2", "Follow-up", 1, List.of(), List.of(manual("Check in")))))),
                    List.of(), 0L));
            publishService.publish(draftId);
            standardRef.set(template.id());

            draftOnlyRef.set(workflows.createTemplate("Never Published", "").id());
        });

        acme = acmeRef.get();
        globex = globexRef.get();
        standardId = standardRef.get();
        draftOnlyTemplateId = draftOnlyRef.get();
    }

    @Test
    void cloningCopiesTheSourcesCurrentPublishedVersionIntoADraft() {
        var cloneRef = new AtomicReference<WorkflowTemplateView>();
        fixture.runAs(admin, () -> cloneRef.set(
                customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Acme Onboarding"))));
        WorkflowTemplateView clone = cloneRef.get();

        assertThat(clone.customerId()).isEqualTo(acme);
        assertThat(clone.clonedFromTemplateId()).isEqualTo(standardId);
        assertThat(versionsOf(clone.id())).singleElement()
                .extracting(WorkflowVersion::getStatus).isEqualTo(VersionStatus.DRAFT);
        assertThat(stageNamesOf(clone.id())).isEqualTo(stageNamesOf(standardId));
    }

    @Test
    void aSourceWithNoPublishedVersionCannotBeCloned() {
        assertThatThrownBy(() -> fixture.runAs(admin,
                () -> customerTemplateService.clone(draftOnlyTemplateId, new CloneTemplateRequest(acme, "X"))))
                .isInstanceOf(NotCloneableException.class);   // 422 -- never clone a shape that was never frozen
    }

    @Test
    void aSecondCloneOfTheSameSourceForTheSameCustomerIsRefused() {
        fixture.runAs(admin, () ->
                customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "First")));
        assertThatThrownBy(() -> fixture.runAs(admin,
                () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Second"))))
                .isInstanceOf(DuplicateCloneException.class);  // 409
    }

    @Test
    void aCustomerTemplateCannotItselfBeCloned() {
        var cloneIdRef = new AtomicReference<UUID>();
        fixture.runAs(admin, () -> cloneIdRef.set(
                customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "Acme")).id()));
        UUID cloneId = cloneIdRef.get();

        assertThatThrownBy(() -> fixture.runAs(admin,
                () -> customerTemplateService.clone(cloneId, new CloneTemplateRequest(globex, "Globex"))))
                .isInstanceOf(NotCloneableException.class);     // lineage stays one level deep
    }

    /**
     * {@code acme} plays "the foreign customer" here: it belongs to {@code admin},
     * and this attempt runs bound to {@code narrowAdmin} instead -- so under RLS,
     * customerId resolution through AuthorizedQuery finds nothing, exactly the
     * shape a genuinely out-of-scope id must fail with. standardId is ALSO
     * foreign to narrowAdmin, but the customer id is resolved first (the
     * design's own explicit ordering), so this proves that specific obligation
     * rather than merely "some id somewhere was foreign."
     */
    @Test
    void theCustomerIdIsResolvedThroughAuthorizedQueryBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> fixture.runAs(narrowAdmin,
                () -> customerTemplateService.clone(standardId, new CloneTemplateRequest(acme, "X"))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(templatesFor(acme)).isEmpty();    // nothing half-written, in either tenant
    }

    // ---- helpers --------------------------------------------------------------

    private List<WorkflowVersion> versionsOf(UUID templateId) {
        var result = new AtomicReference<List<WorkflowVersion>>();
        fixture.runAs(admin, () -> result.set(versions.findByTemplateIdOrderByVersionNoDesc(templateId)));
        return result.get();
    }

    /**
     * A stage's persisted "key" (WorkflowDefinitionView.StageView.key()) is its
     * own freshly generated id, not the client-local key the authoring request
     * used -- newStage always calls Uuid7.generate(), so a clone's stage ids
     * necessarily differ from its source's. Names, not ids, are what the deep
     * copy actually preserves, so that is what this compares.
     */
    private List<String> stageNamesOf(UUID templateId) {
        var result = new AtomicReference<List<String>>();
        fixture.runAs(admin, () -> {
            WorkflowVersion latest = versions.findByTemplateIdOrderByVersionNoDesc(templateId).get(0);
            result.set(stages.findByVersionIdOrderByOrdinal(latest.getId()).stream()
                    .map(Stage::getName).toList());
        });
        return result.get();
    }

    /** Checked in BOTH tenants: a defect here could write under whichever tenant was actually bound. */
    private List<WorkflowTemplate> templatesFor(UUID customerId) {
        var found = new ArrayList<WorkflowTemplate>();
        fixture.runAs(admin, () -> found.addAll(templates.findAll().stream()
                .filter(t -> customerId.equals(t.getCustomerId())).toList()));
        fixture.runAs(narrowAdmin, () -> found.addAll(templates.findAll().stream()
                .filter(t -> customerId.equals(t.getCustomerId())).toList()));
        return found;
    }
}
