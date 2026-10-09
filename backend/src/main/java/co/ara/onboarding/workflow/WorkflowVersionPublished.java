package co.ara.onboarding.workflow;

import java.util.UUID;

/** Published, in the publishing transaction, after the {@code workflow.published} audit record. */
public record WorkflowVersionPublished(UUID templateId, UUID versionId, int versionNo, UUID actorId) {}
