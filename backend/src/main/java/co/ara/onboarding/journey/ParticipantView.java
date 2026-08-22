package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;

import java.util.UUID;

/**
 * fullName is resolved through the same USER_VIEW-gated lookup addParticipant uses
 * to validate the id before writing. A viewer who can see the case but holds no
 * user.view grant at all sees a null name rather than the whole list failing --
 * losing a display label is not the same failure mode as a 404 an id-guessing
 * caller could learn from.
 */
public record ParticipantView(UUID userId, String fullName, RelationshipType relationship,
                              ParticipantStatus status) {}
