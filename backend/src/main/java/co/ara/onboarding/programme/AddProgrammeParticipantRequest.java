package co.ara.onboarding.programme;

import co.ara.onboarding.authz.RelationshipType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code userId} comes from a request body, so {@link ProgrammeMembershipService#addParticipant}
 * resolves it through {@code AuthorizedQuery} under {@code user.view} before writing the
 * {@code programme_participant} row -- the same write-path obligation
 * {@code journey.CaseService#addParticipant} already applies to a userId taken the same way.
 *
 * {@code alsoGrantJourneyAccess}, when true, writes a real {@code CaseParticipant} row on
 * every journey CURRENTLY in the programme, through journey's own gated, audited
 * {@code CaseService#addParticipant} -- never a raw {@code CaseParticipantRepository} write
 * from this module. Authorized by {@code programme.manage} (the permission gating this whole
 * method), never by the target user's own programme participation (design spec §6.3): a
 * programme's participant list must never be a backdoor to journey access, so the grant is an
 * explicit, individually revocable act by someone who can manage the programme AND who
 * independently holds {@code case.edit} on each journey -- CaseService#addParticipant enforces
 * that second gate itself, on every call.
 */
public record AddProgrammeParticipantRequest(
        @NotNull UUID userId, @NotNull RelationshipType relationshipType, boolean alsoGrantJourneyAccess) {}
