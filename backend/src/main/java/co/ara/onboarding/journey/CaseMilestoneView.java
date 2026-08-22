package co.ara.onboarding.journey;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Named CaseMilestoneView, not MilestoneView, to avoid colliding with the
 * simple class name of workflow.WorkflowDefinitionView's nested
 * MilestoneView -- springdoc registers OpenAPI schemas by simple name, so
 * the two silently overwrote one another (whichever the scanner reached
 * last) until this rename. That produced a generated.ts type for
 * PUT .../milestones/{mid} shaped like a workflow definition's authored
 * milestone (key/name/description) rather than this runtime one
 * (status/dueDate/progressPercent) -- a documentation and TypeScript-client
 * defect the JSON response itself was never wrong about.
 */
public record CaseMilestoneView(UUID id, UUID caseId, MilestoneStatus status, UUID ownerUserId,
                            LocalDate dueDate, int progressPercent,
                            Instant completedAt, UUID completedBy, String completionReason) {}
