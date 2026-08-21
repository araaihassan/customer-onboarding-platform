package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * The read side of a {@link WorkflowTemplate}. currentVersionNo is resolved from
 * currentVersionId for display -- a caller listing templates wants "v4", not a raw
 * id -- and is null until Task 7 publishes a first version.
 */
public record WorkflowTemplateView(
        UUID id,
        String name,
        String description,
        TemplateStatus status,
        UUID currentVersionId,
        Integer currentVersionNo) {}
