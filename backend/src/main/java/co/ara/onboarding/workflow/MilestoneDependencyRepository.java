package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MilestoneDependencyRepository
        extends JpaRepository<MilestoneDependency, UUID>, JpaSpecificationExecutor<MilestoneDependency> {

    List<MilestoneDependency> findByVersionId(UUID versionId);

    /**
     * Deleted first: references milestone_definition twice. A bulk JPQL delete,
     * executed immediately -- see StageRepository.deleteByVersionId for why.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from MilestoneDependency d where d.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
