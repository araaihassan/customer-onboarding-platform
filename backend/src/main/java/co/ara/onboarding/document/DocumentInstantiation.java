package co.ara.onboarding.document;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Creates a {@link DocumentRequest} row for every requirement of kind DOCUMENT
 * on a freshly opened case -- the DOCUMENT half of the same requirement seam
 * {@code task.TaskInstantiation} already fills for TASK, modelled on that
 * class exactly. Named for what it does, not to dodge a naming rule: unlike
 * {@code TaskInstantiation}'s own admitted naming dodge (which fell outside
 * the OLD *Service/*Directory suffix rule), that rule no longer exists --
 * sub-project 3A Task 2 rebound {@code AuthorizationCoverageTest}'s finder
 * rule to bind on repository injection instead, so this class is covered
 * under its real name, via a named entry in {@code FINDER_RULE_EXCLUSIONS},
 * the same as {@code TaskInstantiation}'s own (now-real, not naming-based)
 * entry there: called inside {@code CaseService.create}'s own transaction, on
 * a {@code caseId} that method just created and fully controls -- there is no
 * request-supplied id here for {@code AuthorizedQuery} to protect against.
 *
 * Nothing is copied from the requirement definition beyond its label,
 * category and requiresReview flag -- same reasoning as {@code
 * TaskInstantiation}'s own javadoc: instances join their definitions, copying
 * drifts.
 *
 * No audit action is recorded here, for the identical reason {@code
 * TaskInstantiation} records none of its own: this class runs strictly
 * between {@code CaseService.create}'s own {@code CASE_CREATED} record and
 * {@code engine.reconcile} (see that call site's own comment), so any future
 * {@code document.requested} event (Task 29's job, not this one) recorded
 * here would already land after the case's own creation by construction.
 *
 * <b>Calling {@link #instantiateForCase} twice on the same case is left to
 * the database, not guarded here.</b> {@code document_request_requirement_uq}
 * (a unique index on {@code requirement_id} where non-null, {@code
 * V23__document.sql}) refuses a second row for the same requirement -- the
 * identical, already-established precedent {@code task_requirement_uq}
 * enforces for {@code TaskInstantiation}, which carries no existence check of
 * its own either. This class's own single caller ({@code
 * DocumentRequestLifecycleAdapter}, from {@code CaseService.create}) only
 * ever invokes it once per case, so an application-level idempotency check
 * would be defending against a call shape that cannot happen today; the
 * unique index is the truth, and a genuine double call fails loudly (a
 * constraint violation) rather than silently duplicating a request.
 */
@Component
public class DocumentInstantiation {

    private final RequirementRepository requirements;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final MilestoneRepository milestones;
    private final DocumentRequestRepository documentRequests;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    public DocumentInstantiation(RequirementRepository requirements,
                                  RequirementDefinitionRepository requirementDefinitions,
                                  MilestoneRepository milestones,
                                  DocumentRequestRepository documentRequests,
                                  AuthContextProvider contextProvider, Clock clock) {
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.milestones = milestones;
        this.documentRequests = documentRequests;
        this.contextProvider = contextProvider;
        this.clock = clock;
    }

    /**
     * Every requirement on the case is inspected; only ones whose definition
     * is kind DOCUMENT produce a row. Q15's own default -- requestedBy is the
     * milestone's own ownerUserId, already set to the case's default owner by
     * {@code CaseService.instantiate}, which runs before this is ever called --
     * with one departure from {@code TaskInstantiation}'s identical use of that
     * default for {@code Task.assigneeId}: {@code document_request.requested_by}
     * is {@code NOT NULL}, unlike {@code assigneeId}, which tolerates a null
     * (an unassigned task). A customer with no owner set (a real, reachable
     * state -- {@code CaseService.create} only writes an OWNER participant
     * "if (customer.ownerUserId() != null)") would otherwise attempt a
     * NOT NULL violation here, so a null milestone owner falls back to the
     * acting principal -- the same actor {@code CaseService.create} itself
     * already attributes {@code createdBy} to -- rather than leaving the
     * request attributed to nobody.
     */
    public void instantiateForCase(UUID caseId) {
        for (Requirement r : requirements.findByCaseId(caseId)) {
            RequirementDefinition definition = requirementDefinitions.findById(r.getRequirementDefinitionId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            if (definition.getKind() != RequirementKind.DOCUMENT) {
                continue;
            }

            Milestone m = milestones.findById(r.getMilestoneId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));

            DocumentRequest dr = new DocumentRequest();
            dr.setId(Uuid7.generate());
            dr.setTenantId(r.getTenantId());
            dr.setCaseId(r.getCaseId());
            dr.setRequirementId(r.getId());
            // Nothing supplies a contact at instantiation time -- an ad-hoc
            // request (DocumentRequestService.create) is the only path that
            // ever names one.
            dr.setRequestedOfContactId(null);
            dr.setCategory(resolveCategory(definition));
            dr.setDescription(definition.getLabel());
            // Nothing computes one at this level -- a later task's concern, if ever.
            dr.setDueAt(null);
            dr.setRequiresReview(Boolean.TRUE.equals(definition.getRequiresReview()));
            dr.setStatus(DocumentRequestStatus.OPEN);
            dr.setFulfilledDocumentId(null);
            UUID owner = m.getOwnerUserId();
            dr.setRequestedBy(owner != null ? owner : contextProvider.principal().userId());
            dr.setRequestedAt(Instant.now(clock));

            documentRequests.save(dr);
        }
    }

    /**
     * Defends against a null or genuinely-invalid {@code documentCategory} --
     * whether an invalid non-enum string can even reach here is a
     * {@code workflow}-module validation question outside this class's own
     * scope; a hard failure ({@link IllegalArgumentException} from {@link
     * DocumentCategory#valueOf}) on a genuinely bad value is acceptable here,
     * silent corruption is not.
     */
    private static DocumentCategory resolveCategory(RequirementDefinition definition) {
        String category = definition.getDocumentCategory();
        return category == null ? DocumentCategory.OTHER : DocumentCategory.valueOf(category);
    }
}
