package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PlanRevisionRepository
        extends JpaRepository<PlanRevision, UUID>, JpaSpecificationExecutor<PlanRevision> {
}
