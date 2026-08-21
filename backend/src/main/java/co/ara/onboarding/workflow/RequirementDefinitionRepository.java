package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RequirementDefinitionRepository
        extends JpaRepository<RequirementDefinition, UUID>, JpaSpecificationExecutor<RequirementDefinition> {

    List<RequirementDefinition> findByVersionIdOrderByOrdinal(UUID versionId);

    /**
     * Deleted before milestone_definition, which it references. A bulk JPQL delete,
     * executed immediately -- see StageRepository.deleteByVersionId for why.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RequirementDefinition r where r.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
