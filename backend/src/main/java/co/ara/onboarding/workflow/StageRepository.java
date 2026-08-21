package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface StageRepository
        extends JpaRepository<Stage, UUID>, JpaSpecificationExecutor<Stage> {

    List<Stage> findByVersionIdOrderByOrdinal(UUID versionId);
}
