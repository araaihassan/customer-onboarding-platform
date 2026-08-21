package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface AttributeDefinitionRepository
        extends JpaRepository<AttributeDefinition, UUID>, JpaSpecificationExecutor<AttributeDefinition> {

    List<AttributeDefinition> findByVersionIdOrderByOrdinal(UUID versionId);
}
