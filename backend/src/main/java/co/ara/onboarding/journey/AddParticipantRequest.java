package co.ara.onboarding.journey;

import co.ara.onboarding.authz.RelationshipType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddParticipantRequest(@NotNull UUID userId, @NotNull RelationshipType relationship) {}
