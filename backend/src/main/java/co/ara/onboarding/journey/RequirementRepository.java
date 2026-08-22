package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface RequirementRepository
        extends JpaRepository<Requirement, UUID>, JpaSpecificationExecutor<Requirement> {

    List<Requirement> findByMilestoneId(UUID milestoneId);

    /** Every requirement across a case's milestones, for CaseEngine.reconcile. */
    List<Requirement> findByCaseId(UUID caseId);
}
