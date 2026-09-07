package co.ara.onboarding.journey;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What journey may know about tasks. task implements this; journey never names
 * a task type, and ModuleBoundaryTest.noJourneyDependencyOnTask enforces it.
 *
 * Takes a COLLECTION and returns a map deliberately. A roadmap renders every
 * milestone in a case, so a per-id port would make that N queries -- the shape
 * of the port is what prevents it.
 */
public interface TaskDirectory {
    Map<UUID, TaskSummary> summaryFor(Collection<UUID> milestoneIds);
}
