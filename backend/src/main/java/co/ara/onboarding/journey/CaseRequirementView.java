package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.UUID;

/**
 * Named CaseRequirementView, not RequirementView, to avoid colliding with
 * the simple class name of workflow.WorkflowDefinitionView's nested
 * RequirementView -- see CaseMilestoneView's javadoc for why that collision
 * mattered (the wrong shape ended up in generated.ts for satisfy/waive).
 *
 * What satisfy/waive return: the record's terminal state plus who and what settled it.
 */
public record CaseRequirementView(UUID id, RequirementStatus status, Instant satisfiedAt, UUID satisfiedBy,
                               UUID satisfiedRef, String satisfiedRefType, String waiverReason) {}
