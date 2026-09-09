package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Clones a catalogue template for exactly one customer (sub-project 3A, Task 16
 * / QA Q21): {@code workflow_template.customer_id} is null for every template
 * today (the tenant catalogue); this service is the only path that ever writes
 * a non-null one.
 *
 * <b>Why this resolves the customer through {@link CustomerDirectory}, not
 * {@code customer.Customer}/{@code CustomerRepository} directly:</b> a direct
 * dependency was tried first, following {@code programme.ProgrammeService}'s
 * own precedent -- but it closes a module cycle {@code programme} does not:
 * {@code customer} already depends on {@code journey}
 * ({@code JourneyCustomerDirectory} implements {@code journey.CustomerDirectory}),
 * and {@code journey} already depends on {@code workflow} ({@code CaseEngine}
 * reads {@code Stage}/{@code MilestoneDefinition}/etc.), so a direct
 * {@code workflow -> customer} edge closes {@code customer -> journey ->
 * workflow -> customer}. No single NAMED {@code ModuleBoundaryTest} rule
 * catches this (neither {@code noWorkflowDependencyOnJourney} nor
 * {@code noJourneyDependencyOnCustomer} names {@code customer} as a target of a
 * workflow dependency) -- only the general {@code noCyclesBetweenModules}
 * slices check does, and it failed for real the first time this task's suite
 * actually ran, not merely as a hypothetical. See {@link CustomerDirectory}'s
 * own javadoc for the full explanation, including why
 * {@code programme.ProgrammeService}'s direct dependency is a different,
 * unaffected shape (nothing depends on {@code programme}, so it can never close
 * a loop back through it).
 *
 * Every id this class takes from a request body -- {@code customerId} here, and
 * {@code sourceTemplateId} from the URL -- is resolved before any write, in
 * that order: the customer first, exactly as the design brief requires, so a
 * foreign customer id fails closed before the source template (which may
 * itself be perfectly valid) is ever touched.
 */
@Service
public class CustomerTemplateService {

    private final WorkflowTemplateRepository templates;
    private final CustomerDirectory customerDirectory;
    private final WorkflowService workflows;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public CustomerTemplateService(WorkflowTemplateRepository templates,
                                   CustomerDirectory customerDirectory,
                                   WorkflowService workflows,
                                   AuthorizedQuery authorizedQuery,
                                   AuthContextProvider contextProvider,
                                   AuditRecorder audit) {
        this.templates = templates;
        this.customerDirectory = customerDirectory;
        this.workflows = workflows;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    /**
     * Refuses three ways, each a different shape (see {@link NotCloneableException}
     * and {@link DuplicateCloneException} for why each is its own type, and
     * {@link WorkflowExceptionHandler} for the status each maps to):
     * <ul>
     *   <li>the source has never been published ({@code currentVersionId} null);</li>
     *   <li>the source is already itself a customer clone ({@code customerId}
     *       non-null) -- lineage stays one level deep;</li>
     *   <li>this customer already holds a clone of this exact source -- checked
     *       here, before the write, anticipating the same constraint V18's
     *       partial unique index ({@code workflow_template_customer_clone_uq})
     *       enforces as the last line of defense against a concurrent race.</li>
     * </ul>
     */
    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public WorkflowTemplateView clone(UUID sourceTemplateId, CloneTemplateRequest request) {
        CustomerSummary customer = customerDirectory.findVisible(request.customerId())
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        WorkflowTemplate source = authorizedQuery.getById(
                templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_MANAGE, sourceTemplateId);

        if (source.getCurrentVersionId() == null) {
            throw new NotCloneableException(sourceTemplateId, "it has never been published");
        }
        if (source.getCustomerId() != null) {
            throw new NotCloneableException(sourceTemplateId,
                    "it is already a customer clone -- lineage stays one level deep");
        }
        if (alreadyClonedFor(sourceTemplateId, customer.id())) {
            throw new DuplicateCloneException(sourceTemplateId, customer.id());
        }

        WorkflowTemplate clone = new WorkflowTemplate();
        clone.setId(Uuid7.generate());
        clone.setTenantId(TenantContext.getRequired());
        clone.setName(request.name());
        clone.setDescription(source.getDescription());
        clone.setStatus(TemplateStatus.ACTIVE);
        clone.setCreatedBy(contextProvider.principal().userId());
        clone.setCustomerId(customer.id());
        clone.setClonedFromTemplateId(source.getId());
        clone = templates.save(clone);

        // Reuses WorkflowService's own deep-copy machinery -- see that class's
        // copyVersionInto/createDraftCopyingVersion -- rather than re-walking the
        // graph here.
        workflows.createDraftCopyingVersion(clone.getId(), clone.getName(), source.getCurrentVersionId());

        audit.record(AuditActions.WORKFLOW_CLONED_FOR_CUSTOMER, "workflow_template", clone.getId(),
                "Cloned workflow template " + source.getName() + " for customer " + customer.displayName(),
                Map.of("sourceTemplateId", source.getId().toString(), "customerId", customer.id().toString()));

        return new WorkflowTemplateView(clone.getId(), clone.getName(), clone.getDescription(),
                clone.getStatus(), null, null, clone.getCustomerId(), clone.getClonedFromTemplateId());
    }

    /**
     * A real service-level check anticipating V18's own partial unique index
     * ({@code workflow_template_customer_clone_uq} on
     * {@code (cloned_from_template_id, customer_id) WHERE customer_id IS NOT NULL}),
     * not a reliance on the database exception surfacing instead. Read through
     * {@link AuthorizedQuery} under {@code workflow.manage} like every other
     * finder in this package -- workflow.manage is ALL-only today, but
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly binds
     * to every *Service in this package regardless.
     */
    private boolean alreadyClonedFor(UUID sourceTemplateId, UUID customerId) {
        return !authorizedQuery.findAll(templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_MANAGE,
                        (root, query, cb) -> cb.and(
                                cb.equal(root.get("clonedFromTemplateId"), sourceTemplateId),
                                cb.equal(root.get("customerId"), customerId)),
                        Pageable.ofSize(1))
                .isEmpty();
    }
}
