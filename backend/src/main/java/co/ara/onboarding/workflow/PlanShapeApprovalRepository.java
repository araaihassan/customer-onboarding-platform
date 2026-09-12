package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PlanShapeApprovalRepository
        extends JpaRepository<PlanShapeApproval, UUID>, JpaSpecificationExecutor<PlanShapeApproval> {
}
