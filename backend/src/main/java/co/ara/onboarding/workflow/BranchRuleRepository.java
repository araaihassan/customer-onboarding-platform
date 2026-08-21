package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface BranchRuleRepository
        extends JpaRepository<BranchRule, UUID>, JpaSpecificationExecutor<BranchRule> {

    List<BranchRule> findByVersionIdOrderByOrdinal(UUID versionId);
}
