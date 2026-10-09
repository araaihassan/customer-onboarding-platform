package co.ara.onboarding.task;

import java.util.UUID;

/**
 * Published, in the writer's transaction, whenever a task gains a (new) assignee: an assigned
 * ad-hoc create, a reassignment, or an instantiated task defaulting to its milestone's owner.
 * Never published on unassignment or on a status change. 6B spec 5.2; plan amendment 1.
 */
public record TaskAssigned(UUID taskId, UUID caseId, UUID assigneeId, UUID actorId) {}
