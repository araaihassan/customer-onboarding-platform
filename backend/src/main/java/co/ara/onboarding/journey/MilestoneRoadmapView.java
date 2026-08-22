package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MilestoneRoadmapView(UUID id, String name, MilestoneStatus status,
                                   UUID ownerUserId, LocalDate dueDate, int progressPercent,
                                   List<RequirementRoadmapView> requirements) {}
