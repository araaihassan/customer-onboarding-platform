package co.ara.onboarding.journey;

import java.util.UUID;

/** Published after a case enters a stage, once its audit record is written (6B spec 5.5). */
public record StageEntered(UUID caseId, UUID stageId, UUID actorId) {}
