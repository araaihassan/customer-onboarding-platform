package co.ara.onboarding.workflow;

import jakarta.validation.constraints.NotBlank;

/**
 * WorkflowController.create's request body. A blank name is a 400, not a template a
 * builder can never usefully find in the list -- sub-project 1 shipped
 * PlatformTenantController with no validation on its own creation request and created
 * tenants no slug could ever reach; this is that same shape, caught here instead.
 */
public record CreateTemplateRequest(
        @NotBlank String name,
        String description) {}
