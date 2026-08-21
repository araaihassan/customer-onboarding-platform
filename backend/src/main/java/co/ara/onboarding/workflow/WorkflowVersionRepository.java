package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowVersionRepository
        extends JpaRepository<WorkflowVersion, UUID>, JpaSpecificationExecutor<WorkflowVersion> {

    Optional<WorkflowVersion> findByTemplateIdAndStatus(UUID templateId, VersionStatus status);

    List<WorkflowVersion> findByTemplateIdOrderByVersionNoDesc(UUID templateId);
}
