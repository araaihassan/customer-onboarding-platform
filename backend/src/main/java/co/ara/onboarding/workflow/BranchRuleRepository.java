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
     * Called directly from journey.CaseEngine (Task 15) while walking a stage's
     * branch rules in ordinal order -- first match wins. CaseEngine is listed by
     * fully-qualified name in AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS
     * (sub-project 3A Task 2 rebound that rule to bind on repository injection
     * rather than class name, so this is a reviewable line in that list, not a
     * *Service/*Directory naming dodge), and workflow-definition rows are ALL-only
     * (WORKFLOW_VIEW) with no descriptor, the same reasoning CaseService's
     * readDefinition already relies on.
     */
    List<BranchRule> findByStageIdOrderByOrdinal(UUID stageId);

    /**
     * Deleted first, before stage: references stage twice (stage_id,
     * target_stage_id). A bulk JPQL delete, executed immediately -- see
     * StageRepository.deleteByVersionId for why.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from BranchRule b where b.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
