package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface MilestoneDefinitionRepository
        extends JpaRepository<MilestoneDefinition, UUID>, JpaSpecificationExecutor<MilestoneDefinition> {

    List<MilestoneDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
}
