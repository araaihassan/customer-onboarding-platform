package co.ara.onboarding.journey;

import java.util.List;
import java.util.UUID;

public record StageRoadmapView(UUID id, String name, int ordinal,
                               List<MilestoneRoadmapView> milestones) {}
