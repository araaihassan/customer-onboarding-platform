package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MilestoneDefinitionRepository
        extends JpaRepository<MilestoneDefinition, UUID>, JpaSpecificationExecutor<MilestoneDefinition> {

    List<MilestoneDefinition> findByVersionIdOrderByOrdinal(UUID versionId);

    /**
     * Parent of requirement_definition and milestone_dependency -- deleted after
     * both. A bulk JPQL delete, executed immediately, not a derived delete-by-query
     * deferred to the next flush -- see StageRepository.deleteByVersionId for why
     * that distinction matters inside replaceDraft.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from MilestoneDefinition m where m.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
