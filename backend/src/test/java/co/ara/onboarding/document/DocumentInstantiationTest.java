package co.ara.onboarding.document;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.document;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 24: a workflow requirement of kind DOCUMENT now produces a real
 * {@code document_request} row the moment its case opens -- the DOCUMENT half
 * of the same requirement seam Task 19's {@code TaskInstantiationTest} proved
 * for TASK. Modelled on that test's own three cases verbatim, plus two more
 * the brief calls for by name: requires_review copied from the requirement
 * definition (null reading as false), and a second instantiation call refused
 * by the database rather than silently duplicating a row.
 */
class DocumentInstantiationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired DocumentInstantiation instantiation;
    @Autowired DocumentRequestRepository documentRequests;
    @Autowired RequirementDefinitionRepository requirementDefinitions;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;

    @Test
    void aRequirementOfKindDocumentProducesAnOpenDocumentRequestWhenTheCaseOpens() {
        UUID tenant = fixture.createTenant("doc-inst-basic");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWhoseFirstRequirementIsKindDocument(tenant, null, null);

            assertThat(documentRequests.findByCaseId(caseId)).singleElement()
                    .satisfies(dr -> {
                        assertThat(dr.getRequirementId()).isNotNull();
                        assertThat(dr.getCategory()).isEqualTo(DocumentCategory.NDA);
                        assertThat(dr.getDescription()).isEqualTo("Provide NDA");
                        assertThat(dr.getStatus()).isEqualTo(DocumentRequestStatus.OPEN);
                        assertThat(dr.getRequestedOfContactId()).isNull();
                        assertThat(dr.getFulfilledDocumentId()).isNull();
                        // null on the definition reads as false -- no requiresReview
                        // was ever set on this scenario's definition.
                        assertThat(dr.isRequiresReview()).isFalse();
                    });
        });
    }

    /** Q15's own default, the identical shape TaskInstantiation already uses for assigneeId. */
    @Test
    void anInstantiatedRequestDefaultsRequestedByToTheMilestoneOwner() {
        UUID tenant = fixture.createTenant("doc-inst-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID caseId = openCaseWhoseFirstRequirementIsKindDocument(tenant, owner, null);

            assertThat(documentRequests.findByCaseId(caseId).get(0).getRequestedBy()).isEqualTo(owner);
        });
    }

    @Test
    void requirementsOfOtherKindsProduceNoDocumentRequest() {
        UUID tenant = fixture.createTenant("doc-inst-manual-only");
        fixture.runAs(tenant, () -> {
            // publishedTemplate() is a single stage/milestone/MANUAL requirement,
            // no DOCUMENT requirement anywhere in the graph.
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();

            assertThat(documentRequests.findByCaseId(caseId)).isEmpty();
        });
    }

    @Test
    void requiresReviewIsCopiedFromTheRequirementDefinitionWhenSet() {
        UUID tenant = fixture.createTenant("doc-inst-review");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWhoseFirstRequirementIsKindDocument(tenant, null, true);

            assertThat(documentRequests.findByCaseId(caseId).get(0).isRequiresReview()).isTrue();
        });
    }

    /**
     * document_request_requirement_uq (V23__document.sql) is the truth here, the
     * identical unguarded precedent task_requirement_uq already establishes for
     * TaskInstantiation -- see DocumentInstantiation's own javadoc for why no
     * application-level existence check was added on top of it. The first call
     * (inside case creation) leaves exactly one row; the second, direct call
     * fails against that row's unique index rather than silently duplicating it.
     */
    @Test
    void callingInstantiateForCaseTwiceProducesOnlyOneDocumentRequest() {
        UUID tenant = fixture.createTenant("doc-inst-twice");
        UUID caseId = fixture.runAsReturning(tenant,
                () -> openCaseWhoseFirstRequirementIsKindDocument(tenant, null, null));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> instantiation.instantiateForCase(caseId)))
                .as("document_request_requirement_uq must be what refuses the second row, " +
                        "not some other failure reached first")
                .isInstanceOf(DataIntegrityViolationException.class);

        fixture.runAs(tenant, () -> assertThat(documentRequests.findByCaseId(caseId)).hasSize(1));
    }

    private UUID openCaseWhoseFirstRequirementIsKindDocument(UUID tenant, UUID ownerUserId, Boolean requiresReview) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(document("Provide NDA", "NDA")))))),
                List.of(), 0L);

        // Built through WorkflowService/PublishService directly, rather than
        // JourneyFixtures.publish, because requiresReview has to be written onto
        // the definition WHILE its version is still DRAFT -- refuse_published_
        // child_write refuses every write to a requirement_definition row once
        // its owning version is PUBLISHED, requiresReview included, the same
        // freeze CLAUDE.md's own "a published workflow version never mutates"
        // invariant already covers for the version row itself.
        UUID templateId = workflows.createTemplate("Fixture Document " + Uuid7.generate(), "").id();
        UUID versionId = workflows.createDraft(templateId);
        workflows.replaceDraft(versionId, request);

        if (requiresReview != null) {
            RequirementDefinition definition = requirementDefinitions.findByVersionIdOrderByOrdinal(versionId)
                    .stream().filter(d -> d.getKind() == RequirementKind.DOCUMENT).findFirst().orElseThrow();
            definition.setRequiresReview(requiresReview);
            // saveAndFlush, not save: the UPDATE must actually hit the row while
            // the version is still DRAFT -- refuse_published_child_write's own
            // check reads the version's CURRENT database status, so a deferred
            // flush ordered after publish's own status flip would trip the same
            // trigger this test means to get past.
            requirementDefinitions.saveAndFlush(definition);
        }

        publishService.publish(versionId);

        UUID customerId = ownerUserId == null
                ? fixture.createCustomer(tenant, "Acme", null, null, null)
                : fixture.createCustomerOwnedBy(tenant, "Acme", ownerUserId);

        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }
}
