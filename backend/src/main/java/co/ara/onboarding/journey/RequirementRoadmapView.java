package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.RequirementKind;

import java.util.UUID;

public record RequirementRoadmapView(UUID id, String label, RequirementKind kind,
                                     boolean mandatory, RequirementStatus status) {}
