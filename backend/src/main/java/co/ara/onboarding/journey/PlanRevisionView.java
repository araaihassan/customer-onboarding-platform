package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The read side of a {@link PlanRevision}, its frozen {@code items} included. */
public record PlanRevisionView(
        UUID id,
        UUID caseId,
        int revisionNumber,
        PlanRevisionStatus status,
        Instant issuedAt,
        UUID issuedBy,
        String issueNote,
        Instant decidedAt,
        UUID decidedBy,
        UUID decidedOnBehalfOf,
        String decisionNote,
        List<PlanRevisionItemView> items) {}
