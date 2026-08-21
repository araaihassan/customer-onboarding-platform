package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface MilestoneDependencyRepository
        extends JpaRepository<MilestoneDependency, UUID>, JpaSpecificationExecutor<MilestoneDependency> {

    List<MilestoneDependency> findByVersionId(UUID versionId);
}
