package co.ara.onboarding.journey;

import java.util.UUID;

/** Published after a case leaves a stage (advance, completion or reopen), after its cause (6B spec 5.5). */
public record StageExited(UUID caseId, UUID stageId, UUID actorId) {}
