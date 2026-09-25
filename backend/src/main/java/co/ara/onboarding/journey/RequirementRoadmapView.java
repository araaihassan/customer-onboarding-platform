package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.RequirementKind;

import java.util.UUID;

/**
 * {@code satisfiedRef}/{@code satisfiedRefType} are the same fields already
 * on {@link Requirement} and {@link CaseRequirementView} -- added here too so
 * the roadmap can link a DOCUMENT-kind requirement to the record that
 * satisfied it (a document, a task, ...) without a second fetch per
 * requirement. Both are null until the requirement is satisfied, exactly as
 * on the entity itself.
 */
public record RequirementRoadmapView(UUID id, String label, RequirementKind kind,
                                     boolean mandatory, RequirementStatus status,
                                     UUID satisfiedRef, String satisfiedRefType) {}
