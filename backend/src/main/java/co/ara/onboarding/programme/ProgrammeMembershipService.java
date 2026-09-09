package co.ara.onboarding.programme;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Journey membership ({@code programme_case}) and participants
 * ({@code programme_participant}) for a programme -- this sub-project's most
 * security-critical class (design spec §6.3, Q20's non-negotiable).
 *
 * <b>A programme's participant list must never be a backdoor to journey
 * access.</b> Programme participation ({@link #addParticipant}) grants read of
 * the programme container alone; nothing here writes a {@code CaseParticipant}
 * row as a SIDE EFFECT of participation. The one path that DOES grant journey
 * access -- {@code alsoGrantJourneyAccess} on {@link #addParticipant} -- is an
 * explicit, separately authorized act that calls through journey's own real,
 * gated, audited {@link CaseService#addParticipant} rather than writing
 * {@code CaseParticipantRepository} directly from this module (which would
 * bypass journey's own {@code case.edit} gate and its own audit trail).
 * {@link ProgrammeScopeTest} is the test proving this holds.
 *
 * Every id taken from a URL or a request body -- the case id on
 * {@link #addJourney}, the user id on {@link #addParticipant} -- is resolved
 * through {@link AuthorizedQuery} before it is written, same as everywhere else
 * in the codebase (design spec §6.5).
 */
@Service
public class ProgrammeMembershipService {

    private final ProgrammeRepository programmes;
    private final ProgrammeCaseRepository programmeCases;
    private final ProgrammeParticipantRepository programmeParticipants;
    private final CaseRepository cases;
    private final AppUserRepository users;
    private final CaseService caseService;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public ProgrammeMembershipService(ProgrammeRepository programmes, ProgrammeCaseRepository programmeCases,
                                      ProgrammeParticipantRepository programmeParticipants, CaseRepository cases,
                                      AppUserRepository users, CaseService caseService,
                                      AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                                      AuditRecorder audit) {
        this.programmes = programmes;
        this.programmeCases = programmeCases;
        this.programmeParticipants = programmeParticipants;
        this.cases = cases;
        this.users = users;
        this.caseService = caseService;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    /**
     * Resolves the case id through {@code AuthorizedQuery} under {@code case.view}
     * BEFORE writing the {@code programme_case} link row -- the id comes from a
     * request body, so the write-path obligation applies regardless of the caller
     * already holding {@code programme.manage}. A foreign-tenant or out-of-scope
     * case id 404s here, never a half-written link ({@code ProgrammeIsolationTest}
     * proves this for a foreign-tenant case specifically).
     *
     * Deliberately no {@code ProgrammeStatus.ACTIVE} guard here (unlike
     * {@code ProgrammeService.update}): no test in this task exercises membership
     * mutation against a deactivated programme, and adding an unverified guard
     * would be exactly the kind of untested behaviour CLAUDE.md's TDD convention
     * warns against. Worth a design decision before Task 14, not a silent
     * assumption made here.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void addJourney(UUID programmeId, AddJourneyRequest request) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, request.caseId());

        ProgrammeCase link = new ProgrammeCase();
        link.setId(Uuid7.generate());
        link.setTenantId(TenantContext.getRequired());
        link.setProgrammeId(p.getId());
        link.setCaseId(c.getId());
        link.setAddedAt(Instant.now());
        link.setAddedBy(contextProvider.principal().userId());
        programmeCases.save(link);

        audit.record(AuditActions.PROGRAMME_JOURNEY_ADDED, "programme", p.getId(),
                "Added journey to programme " + p.getName(), Map.of("caseId", c.getId().toString()));
    }

    /**
     * Sets {@code removedAt}, never deletes -- {@code programme_case_active_uq}'s
     * own partial unique index exists precisely so a case can leave one programme
     * and join another without the old link row ever disappearing (DELETE is
     * denied at the database layer for every business table in this codebase).
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void removeJourney(UUID programmeId, UUID caseId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);

        Specification<ProgrammeCase> activeLink = (root, query, cb) -> cb.and(
                cb.equal(root.get("programmeId"), p.getId()),
                cb.equal(root.get("caseId"), caseId),
                cb.isNull(root.get("removedAt")));
        ProgrammeCase link = authorizedQuery.findAll(programmeCases, ProgrammeCase.class,
                        PermissionKeys.PROGRAMME_MANAGE, activeLink, Pageable.unpaged())
                .getContent().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        link.setRemovedAt(Instant.now());
        programmeCases.save(link);

        audit.record(AuditActions.PROGRAMME_JOURNEY_REMOVED, "programme", p.getId(),
                "Removed journey from programme " + p.getName(), Map.of("caseId", caseId.toString()));
    }

    /**
     * Writes a {@code programme_participant} row -- read-only over the programme
     * itself, exactly design spec §6.3's resolution: participation never grants
     * journey access on its own.
     *
     * When {@code request.alsoGrantJourneyAccess()} is true, this method ALSO
     * calls {@link CaseService#addParticipant} for every journey currently linked
     * to the programme, so a real, audited {@code CaseParticipant} row is written
     * on each one -- an explicit act authorized by THIS method's own
     * {@code programme.manage} gate, not by the target user's participation.
     * {@code CaseService#addParticipant} independently enforces {@code case.edit}
     * on every one of those calls; a caller holding {@code programme.manage} but
     * not {@code case.edit} on a given journey still cannot grant access to it,
     * exactly as it cannot through any other path in the codebase.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void addParticipant(UUID programmeId, AddProgrammeParticipantRequest request) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        AppUser user = authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, request.userId());

        ProgrammeParticipant participant = new ProgrammeParticipant();
        participant.setId(Uuid7.generate());
        participant.setTenantId(TenantContext.getRequired());
        participant.setProgrammeId(p.getId());
        participant.setUserId(user.getId());
        participant.setRelationshipType(request.relationshipType());
        participant.setStatus(ProgrammeParticipantStatus.ACTIVE);
        programmeParticipants.save(participant);

        audit.record(AuditActions.PROGRAMME_PARTICIPANT_ADDED, "programme", p.getId(),
                "Added participant to programme " + p.getName(),
                Map.of("userId", user.getId().toString(), "relationshipType", request.relationshipType().name()));

        if (request.alsoGrantJourneyAccess()) {
            for (UUID caseId : activeCaseIdsFor(p.getId())) {
                // journey's own gate (case.edit) and its own audit trail
                // (case.participant_added) -- not this module's write.
                caseService.addParticipant(caseId, user.getId(), request.relationshipType());
            }
        }
    }

    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void removeParticipant(UUID programmeId, UUID userId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);

        Specification<ProgrammeParticipant> activeParticipant = (root, query, cb) -> cb.and(
                cb.equal(root.get("programmeId"), p.getId()),
                cb.equal(root.get("userId"), userId),
                cb.equal(root.get("status"), ProgrammeParticipantStatus.ACTIVE));
        ProgrammeParticipant participant = authorizedQuery.findAll(programmeParticipants, ProgrammeParticipant.class,
                        PermissionKeys.PROGRAMME_MANAGE, activeParticipant, Pageable.unpaged())
                .getContent().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        participant.setStatus(ProgrammeParticipantStatus.REMOVED);
        programmeParticipants.save(participant);

        audit.record(AuditActions.PROGRAMME_PARTICIPANT_REMOVED, "programme", p.getId(),
                "Removed participant from programme " + p.getName(), Map.of("userId", userId.toString()));
    }

    /** Every case currently (not previously) linked to this programme, through AuthorizedQuery. */
    private List<UUID> activeCaseIdsFor(UUID programmeId) {
        Specification<ProgrammeCase> activeLinksForProgramme = (root, query, cb) -> cb.and(
                cb.equal(root.get("programmeId"), programmeId),
                cb.isNull(root.get("removedAt")));
        return authorizedQuery.findAll(programmeCases, ProgrammeCase.class,
                        PermissionKeys.PROGRAMME_MANAGE, activeLinksForProgramme, Pageable.unpaged())
                .getContent().stream().map(ProgrammeCase::getCaseId).toList();
    }
}
