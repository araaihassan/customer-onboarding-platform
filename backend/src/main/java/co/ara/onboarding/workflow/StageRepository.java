package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StageRepository
        extends JpaRepository<Stage, UUID>, JpaSpecificationExecutor<Stage> {

    List<Stage> findByVersionIdOrderByOrdinal(UUID versionId);

    /**
     * Parent of milestone_definition and branch_rule -- deleted last, after both. A
     * bulk JPQL delete, not a derived delete-by-query: the plain derived form loads
     * matching entities and schedules their removal for the next flush rather than
     * executing immediately, so a replaceDraft that deletes then re-inserts at the
     * same ordinals within one transaction can race its own pending delete and trip
     * the unique (version_id, ordinal) constraint. flushAutomatically pushes any
     * earlier pending writes first; clearAutomatically is deliberately NOT set,
     * because it would detach the WorkflowVersion instance replaceDraft still holds.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Stage s where s.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);
}
