package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface RequirementDefinitionRepository
        extends JpaRepository<RequirementDefinition, UUID>, JpaSpecificationExecutor<RequirementDefinition> {

    List<RequirementDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
}
