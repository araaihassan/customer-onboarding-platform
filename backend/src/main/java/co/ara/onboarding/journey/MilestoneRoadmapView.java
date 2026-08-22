package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * blockedByMilestoneNames is empty except when status is BLOCKED -- CaseEngine
 * computes the status for its own transition logic but discards which
 * dependency was unmet, so this recomputes the same check read-only for
 * display: "blocked" without saying what it is blocked BY is colour carrying
 * the whole signal (review finding 10).
 */
public record MilestoneRoadmapView(UUID id, String name, MilestoneStatus status,
                                   UUID ownerUserId, LocalDate dueDate, int progressPercent,
                                   List<String> blockedByMilestoneNames,
                                   List<RequirementRoadmapView> requirements) {}
