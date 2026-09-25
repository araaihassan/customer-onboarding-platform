package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Creates one DRAFT {@link Agreement} per requirement of kind SIGNATURE on a
 * case that has no live (non-CANCELLED) agreement for that requirement yet --
 * spec 3.2, the SIGNATURE half of the same requirement-instantiation seam
 * {@code task.TaskInstantiation}/{@code document.DocumentInstantiation} already
 * fill for TASK/DOCUMENT, modelled on {@code DocumentInstantiation} exactly.
 *
 * One deliberate departure from that model: {@code DocumentInstantiation} has
 * exactly one caller (CaseService.create) and leaves idempotency entirely to a
 * unique index, with no existence check of its own. This class has TWO callers
 * -- CaseService.create AND MigrationService, which re-calls it after every
 * repin to instantiate agreements for any SIGNATURE requirement the new version
 * adds -- so a plain "insert and let the index refuse the second one" shape
 * would make the second, expected call throw on every requirement the first
 * call already handled. instantiateForCase therefore checks
 * {@code agreements.liveFor(requirementId)} first and skips a requirement that
 * already has a live agreement, making the whole method idempotent by
 * construction. {@code agreement_live_per_requirement_uq} stays the truth under
 * a race between the two callers -- this check narrows the common case, it does
 * not replace the constraint.
 *
 * Carries a FINDER_RULE_EXCLUSIONS entry for the identical reason
 * {@code DocumentInstantiation}'s own entry gives: both callers run this inside
 * their own transaction, on a case id they either just created
 * (CaseService.create) or already resolved through AuthorizedQuery
 * (MigrationService, before ever reaching migrateOne) -- there is no
 * request-supplied id here for AuthorizedQuery to protect.
 *
 * Records {@code agreement.created} per row, after the caller's own
 * case.created (CaseService.create) or case.migrated (MigrationService) is
 * already recorded -- see both call sites' own comments for why cause
 * precedes effect by construction here.
 */
@Component
public class AgreementInstantiation {

    private final RequirementRepository requirements;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final AgreementRepository agreements;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final Clock clock;

    public AgreementInstantiation(RequirementRepository requirements,
                                  RequirementDefinitionRepository requirementDefinitions,
                                  MilestoneRepository milestones, CaseRepository cases,
                                  AgreementRepository agreements, AuthContextProvider contextProvider,
                                  AuditRecorder audit, Clock clock) {
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.milestones = milestones;
        this.cases = cases;
        this.agreements = agreements;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Every requirement on the case is inspected; only ones whose definition is
     * kind SIGNATURE, and that have no live agreement yet, produce a row. The
     * owner defaults to the milestone's own ownerUserId, falling back to the
     * acting principal -- the identical Q15 default
     * {@code DocumentInstantiation}/{@code TaskInstantiation} already use for a
     * customer with no owner set.
     */
    public void instantiateForCase(UUID caseId) {
        Case c = cases.findById(caseId).orElseThrow(() -> new NoSuchElementException("Not found"));
        for (Requirement r : requirements.findByCaseId(caseId)) {
            RequirementDefinition definition = requirementDefinitions.findById(r.getRequirementDefinitionId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            if (definition.getKind() != RequirementKind.SIGNATURE) {
                continue;
            }
            if (agreements.liveFor(r.getId()).isPresent()) {
                continue;
            }

            Milestone m = milestones.findById(r.getMilestoneId())
                    .orElseThrow(() -> new NoSuchElementException("Not found"));
            UUID actor = contextProvider.principal().userId();
            Instant now = Instant.now(clock);

            Agreement a = new Agreement();
            a.setId(Uuid7.generate());
            a.setTenantId(r.getTenantId());
            a.setCaseId(caseId);
            a.setRequirementId(r.getId());
            a.setCustomerId(c.getCustomerId());
            a.setName(definition.getAgreementName());
            a.setRecordMode(definition.getAgreementRecordMode());
            a.setStatus(AgreementStatus.DRAFT);
            UUID owner = m.getOwnerUserId();
            a.setOwnerUserId(owner != null ? owner : actor);
            a.setLastEditedBy(actor);
            a.setSignatureProvider(SignatureProviderKind.MANUAL);
            a.setCreatedAt(now);
            a.setUpdatedAt(now);
            agreements.save(a);

            audit.record(AuditActions.AGREEMENT_CREATED, "onboarding_case", caseId,
                    "Created agreement " + a.getName(), Map.of("agreementId", a.getId().toString()));
        }
    }
}
