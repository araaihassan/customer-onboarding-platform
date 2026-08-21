package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AttributeDefinitionRepository
        extends JpaRepository<AttributeDefinition, UUID>, JpaSpecificationExecutor<AttributeDefinition> {

    List<AttributeDefinition> findByVersionIdOrderByOrdinal(UUID versionId);

    /**
     * No child references an attribute, so deletion order relative to it never
     * matters. Still a bulk JPQL delete, executed immediately, for consistency with
     * its five siblings -- see StageRepository.deleteByVersionId for why that
     * distinction matters inside replaceDraft.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from AttributeDefinition a where a.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
