package co.ara.onboarding.document;

import co.ara.onboarding.journey.DocumentRequestLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * journey's own {@link DocumentRequestLifecycle} port, implemented here rather
 * than in journey itself -- ModuleBoundaryTest.noJourneyDependencyOnDocument
 * is what a concrete journey-side implementation naming a document type would
 * violate. Mirrors {@code task.TaskLifecycleAdapter}'s exact shape, minus
 * {@code reopenForMilestone}: the port it implements declares only one method,
 * since document has no reopening concept of its own to mirror.
 */
@Component
public class DocumentRequestLifecycleAdapter implements DocumentRequestLifecycle {

    private final DocumentInstantiation instantiation;

    public DocumentRequestLifecycleAdapter(DocumentInstantiation instantiation) {
        this.instantiation = instantiation;
    }

    /**
     * Called from CaseService.create, immediately after taskLifecycle's own
     * instantiateForCase call -- after CASE_CREATED is audited and before
     * engine.reconcile runs, inside that method's own transaction, on a
     * caseId it just created and fully controls -- see DocumentInstantiation's
     * own javadoc for why this deliberately bypasses AuthorizedQuery rather
     * than by oversight.
     */
    @Override
    public void instantiateForCase(UUID caseId) {
        instantiation.instantiateForCase(caseId);
    }
}
