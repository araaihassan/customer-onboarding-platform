package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * journey's own seam for the DOCUMENT half of the requirement-instantiation
 * pattern {@link TaskLifecycle} already establishes for TASK -- a concrete
 * journey-side implementation naming a document type would violate
 * ModuleBoundaryTest.noJourneyDependencyOnDocument, so {@code
 * document.DocumentRequestLifecycleAdapter} implements this instead.
 *
 * Deliberately one method, not two: unlike a task, a document request has no
 * reopening concept of its own to mirror {@link TaskLifecycle#reopenForMilestone}
 * -- document's own retirement-reopens-a-requirement mechanism already lives
 * entirely in {@code RequirementService.reopen} (Task 18), unrelated to this port.
 */
public interface DocumentRequestLifecycle {
    void instantiateForCase(UUID caseId);
}
