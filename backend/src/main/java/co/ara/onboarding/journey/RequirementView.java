package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.UUID;

/** What satisfy/waive return: the record's terminal state plus who and what settled it. */
public record RequirementView(UUID id, RequirementStatus status, Instant satisfiedAt, UUID satisfiedBy,
                               UUID satisfiedRef, String satisfiedRefType, String waiverReason) {}
