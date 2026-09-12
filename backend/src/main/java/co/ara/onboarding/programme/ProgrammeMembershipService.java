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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
     * Refuses a non-ACTIVE programme (fix round 1 -- see {@link
     * ProgrammeNotActiveException}'s own doc comment for the shape). {@code
     * scoping.ProgrammeDescriptor.departmentScope}/{@code teamScope} deliberately
     * still resolve a deactivated programme for governance/reporting reads, so
     * without this independent guard a DEPARTMENT- or TEAM-scoped {@code
     * programme.manage} holder could still successfully link a fresh journey --
     * or, on {@link #addParticipant} below, grant real journey access -- onto a
     * programme everyone agrees is retired. An earlier version of this class
     * left this check out deliberately, reasoning that no test exercised it; a
     * task reviewer determined, with the full precedent of {@code
     * ProgrammeService.update}'s own identical guard in view, that the omission
     * was itself the gap, not a safe default -- see {@code ProgrammeMembershipServiceTest}.
     *
     * {@code programme_case_active_uq} is a PARTIAL unique index on {@code
     * case_id} (only where {@code removed_at IS NULL}) -- scoped to the case
     * being in ANY currently-active link tenant-wide, not to this programme
     * specifically. A second {@code addJourney} against a case that is still
     * actively linked -- to this programme or a different one -- collides with
     * it; {@link #saveLink} catches that and translates it to {@link
     * ProgrammeJourneyAlreadyLinkedException} (409) rather than letting it
     * surface as a raw {@code DataIntegrityViolationException} (500). A case
     * previously REMOVED from a programme carries no such collision (its old
     * row's {@code removed_at} is set, so it falls outside the partial index),
     * so re-linking it -- to this programme or another -- is always a clean
     * INSERT.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void addJourney(UUID programmeId, AddJourneyRequest request) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        if (p.getStatus() != ProgrammeStatus.ACTIVE) {
            throw new ProgrammeNotActiveException(programmeId);
        }
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, request.caseId());

        ProgrammeCase link = new ProgrammeCase();
        link.setId(Uuid7.generate());
        link.setTenantId(TenantContext.getRequired());
        link.setProgrammeId(p.getId());
        link.setCaseId(c.getId());
        link.setAddedAt(Instant.now());
        link.setAddedBy(contextProvider.principal().userId());
        saveLink(link);

        audit.record(AuditActions.PROGRAMME_JOURNEY_ADDED, "programme", p.getId(),
                "Added journey to programme " + p.getName(), Map.of("caseId", c.getId().toString()));
    }

    /**
     * Sets {@code removedAt}, never deletes -- {@code programme_case_active_uq}'s
     * own partial unique index exists precisely so a case can leave one programme
     * and join another without the old link row ever disappearing (DELETE is
     * denied at the database layer for every business table in this codebase).
     *
     * Refuses a non-ACTIVE programme, same as {@link #addJourney} -- see that
     * method's own doc comment.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void removeJourney(UUID programmeId, UUID caseId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        if (p.getStatus() != ProgrammeStatus.ACTIVE) {
            throw new ProgrammeNotActiveException(programmeId);
        }

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
     * Refuses a non-ACTIVE programme, same shape as {@link #addJourney} -- see
     * that method's own doc comment. Consequential here specifically: without
     * this guard, {@code alsoGrantJourneyAccess=true} against an already-
     * deactivated programme would still write real, live {@code CaseParticipant}
     * rows through {@link CaseService#addParticipant} below.
     *
     * {@code programme_participant_uq} is a PLAIN unique index on {@code
     * (programme_id, user_id)} -- unlike {@code programme_case_active_uq}, NOT
     * partial on status -- so at most one row can ever exist for this pair,
     * ACTIVE or REMOVED, and a naive second INSERT always collides once either
     * exists. {@link #existingParticipantRow} is checked first, before any
     * write, to tell the two cases apart: an existing ACTIVE row is a genuine
     * conflict ({@link ProgrammeParticipantAlreadyActiveException}, 409); an
     * existing REMOVED row is reactivated in place (status flipped back to
     * ACTIVE, relationship type updated to the request's) rather than a
     * duplicate row ever being inserted.
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
        if (p.getStatus() != ProgrammeStatus.ACTIVE) {
            throw new ProgrammeNotActiveException(programmeId);
        }
        AppUser user = authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, request.userId());

        ProgrammeParticipant existing = existingParticipantRow(p.getId(), user.getId());
        if (existing != null && existing.getStatus() == ProgrammeParticipantStatus.ACTIVE) {
            throw new ProgrammeParticipantAlreadyActiveException(p.getId(), user.getId());
        }
        if (existing != null) {
            existing.setStatus(ProgrammeParticipantStatus.ACTIVE);
            existing.setRelationshipType(request.relationshipType());
            programmeParticipants.save(existing);
        } else {
            ProgrammeParticipant participant = new ProgrammeParticipant();
            participant.setId(Uuid7.generate());
            participant.setTenantId(TenantContext.getRequired());
            participant.setProgrammeId(p.getId());
            participant.setUserId(user.getId());
            participant.setRelationshipType(request.relationshipType());
            participant.setStatus(ProgrammeParticipantStatus.ACTIVE);
            programmeParticipants.save(participant);
        }

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

    /**
     * Refuses a non-ACTIVE programme, same shape as {@link #addJourney} -- see
     * that method's own doc comment.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void removeParticipant(UUID programmeId, UUID userId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        if (p.getStatus() != ProgrammeStatus.ACTIVE) {
            throw new ProgrammeNotActiveException(programmeId);
        }

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

    /**
     * The one row {@code programme_participant_uq} allows for this pair, in
     * whatever status it currently holds -- ACTIVE, REMOVED, or absent
     * (returns null). Deliberately not restricted to ACTIVE, unlike {@link
     * #removeParticipant}'s own lookup: {@link #addParticipant} needs to see a
     * REMOVED row too, to reactivate it instead of colliding with it.
     */
    private ProgrammeParticipant existingParticipantRow(UUID programmeId, UUID userId) {
        Specification<ProgrammeParticipant> forPair = (root, query, cb) -> cb.and(
                cb.equal(root.get("programmeId"), programmeId),
                cb.equal(root.get("userId"), userId));
        return authorizedQuery.findAll(programmeParticipants, ProgrammeParticipant.class,
                        PermissionKeys.PROGRAMME_MANAGE, forPair, Pageable.unpaged())
                .getContent().stream().findFirst().orElse(null);
    }

    /**
     * Forces the flush so a unique-index collision surfaces here, inside this
     * try block, rather than at commit time outside it -- with an assigned
     * (not database-generated) id, Hibernate otherwise defers the INSERT to
     * flush, exactly {@code customer.CustomerContactService.save}'s own doc
     * comment explains for the identical shape.
     */
    private ProgrammeCase saveLink(ProgrammeCase link) {
        try {
            return programmeCases.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, PROGRAMME_CASE_ACTIVE_UNIQUE)) {
                throw new ProgrammeJourneyAlreadyLinkedException(link.getCaseId(), e);
            }
            // Every other constraint is rethrown untouched -- reporting an
            // unrelated violation as "already linked" would send the caller
            // hunting for a conflict that does not exist.
            throw e;
        }
    }

    private static final String PROGRAMME_CASE_ACTIVE_UNIQUE = "programme_case_active_uq";

    /**
     * Matched on the constraint name Hibernate reports, not on message text,
     * which is Postgres's to reword -- the same idiom {@code
     * customer.CustomerContactService.violates} uses for {@code
     * customer_contact_customer_id_lower_email_idx}.
     */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
