package co.ara.onboarding.journey;

import java.util.List;

/**
 * Every stage of the pinned version, not just the current one -- the prototype
 * draws the whole spine with future stages pending, and eager instantiation
 * (CaseService.instantiate) is what makes that possible from the first moment.
 */
public record RoadmapView(List<StageRoadmapView> stages) {}
