package co.ara.onboarding.journey;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MilestoneView(UUID id, UUID caseId, MilestoneStatus status, UUID ownerUserId,
                            LocalDate dueDate, int progressPercent,
                            Instant completedAt, UUID completedBy, String completionReason) {}
