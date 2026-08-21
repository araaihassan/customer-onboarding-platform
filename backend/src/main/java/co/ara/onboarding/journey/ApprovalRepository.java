package co.ara.onboarding.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ApprovalRepository
        extends JpaRepository<Approval, UUID>, JpaSpecificationExecutor<Approval> {

    List<Approval> findByCaseIdAndStatus(UUID caseId, ApprovalStatus status);
}
