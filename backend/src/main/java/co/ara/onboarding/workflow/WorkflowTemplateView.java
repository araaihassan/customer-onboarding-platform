package co.ara.onboarding.workflow;

import java.util.UUID;

/**
 * The read side of a {@link WorkflowTemplate}. currentVersionNo is resolved from
 * currentVersionId for display -- a caller listing templates wants "v4", not a raw
 * id -- and is null until Task 7 publishes a first version.
 *
 * customerId and clonedFromTemplateId (sub-project 3A, Task 15 / QA Q21) round-trip
 * a clone's lineage: null customerId is the tenant catalogue, non-null is a template
 * tailored for exactly one customer, cloned from clonedFromTemplateId. There is no
 * update request for a template's own fields (only PUT .../versions/{vid}, a full
 * replace of the DEFINITION graph, not the template row) so no request type needs
 * these two added for full-replace correctness -- they exist here purely so a client
 * reading a template back sees its own clone lineage.
 */
public record WorkflowTemplateView(
        UUID id,
        String name,
        String description,
        TemplateStatus status,
        UUID currentVersionId,
        Integer currentVersionNo,
        UUID customerId,
        UUID clonedFromTemplateId) {}
