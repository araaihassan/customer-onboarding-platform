package co.ara.onboarding.agreement;

import co.ara.onboarding.journey.AgreementLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * journey's own {@link AgreementLifecycle} port, implemented here rather than
 * in journey itself -- ModuleBoundaryTest.noJourneyDependencyOnAgreement is
 * what a concrete journey-side implementation naming an agreement type would
 * violate. Mirrors {@code document.DocumentRequestLifecycleAdapter}'s exact
 * shape: one method, pure delegation.
 */
@Component
public class AgreementLifecycleAdapter implements AgreementLifecycle {

    private final AgreementInstantiation instantiation;

    public AgreementLifecycleAdapter(AgreementInstantiation instantiation) {
        this.instantiation = instantiation;
    }

    /**
     * Called from CaseService.create (after case.created is audited, before
     * engine.reconcile) and from MigrationService.migrateOne (after the repin
     * and its own milestone instantiation, before that migration's own
     * reconcile call) -- see both call sites' own comments. Idempotent: see
     * AgreementInstantiation's own javadoc for why this port has two callers
     * and neither can assume the other has not already run.
     */
    @Override
    public void instantiateForCase(UUID caseId) {
        instantiation.instantiateForCase(caseId);
    }
}
