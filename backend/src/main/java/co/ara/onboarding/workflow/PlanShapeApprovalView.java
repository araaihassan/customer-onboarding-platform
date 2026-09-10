package co.ara.onboarding.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * The current state of a {@link WorkflowVersion}'s shape approval -- "current"
 * meaning the LATEST {@link PlanShapeApproval} row for that version, by
 * {@code submittedAt}, never an aggregate over every submission: resubmission
 * after a rejection creates a NEW row rather than mutating the old one (see
 * that entity's own javadoc for why).
 *
 * {@link PlanShapeService#currentApproval} is the one place this is built --
 * sub-project 3A Task 24 (a later task, in the {@code journey} module) calls it
 * directly, cross-module, to enforce gate 2's ordering rule against
 * {@link #status()}.
 */
public record PlanShapeApprovalView(
        UUID id,
        UUID versionId,
        UUID templateId,
        UUID customerId,
        PlanShapeApprovalStatus status,
        Instant submittedAt,
        UUID submittedBy,
        Instant decidedAt,
        UUID decidedBy,
        UUID decidedOnBehalfOf,
        String decisionNote) {}
