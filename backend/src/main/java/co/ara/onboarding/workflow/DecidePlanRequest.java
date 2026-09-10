package co.ara.onboarding.workflow;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code WorkflowController}'s request body for deciding a plan's shape
 * approval (sub-project 3A, Task 20 / QA Q22 gate 1). {@code outcome} is the
 * only required field.
 *
 * {@code decidedOnBehalfOfContactId} is genuinely optional at the schema level
 * (nullable on {@code plan_shape_approval}) -- it names the {@code
 * customer_contact} the internal actor calling {@link PlanShapeService#decide}
 * is recording this decision ON BEHALF OF (e.g. "the sponsor approved by
 * email, an account manager logged it"). It is NOT the same id as {@code
 * decidedBy}, which is always the actor who actually pressed the button --
 * today always internal staff, since sub-project 7 is what eventually lets a
 * real customer sponsor press it themselves.
 */
public record DecidePlanRequest(
        @NotNull PlanDecision outcome,
        String note,
        UUID decidedOnBehalfOfContactId) {}
