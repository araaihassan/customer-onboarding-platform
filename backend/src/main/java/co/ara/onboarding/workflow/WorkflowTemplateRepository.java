package co.ara.onboarding.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * JpaSpecificationExecutor is required, not incidental: AuthorizedQuery turns a
 * grant's scope into a Specification, and a finder method that bypasses
 * Specifications also bypasses scope.
 */
public interface WorkflowTemplateRepository
        extends JpaRepository<WorkflowTemplate, UUID>, JpaSpecificationExecutor<WorkflowTemplate> {}
