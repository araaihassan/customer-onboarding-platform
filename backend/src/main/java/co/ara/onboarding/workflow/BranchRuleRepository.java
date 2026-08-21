package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BranchRuleRepository
        extends JpaRepository<BranchRule, UUID>, JpaSpecificationExecutor<BranchRule> {

    List<BranchRule> findByVersionIdOrderByOrdinal(UUID versionId);

    /**
     * Deleted first, before stage: references stage twice (stage_id,
     * target_stage_id). A bulk JPQL delete, executed immediately -- see
     * StageRepository.deleteByVersionId for why.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from BranchRule b where b.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
