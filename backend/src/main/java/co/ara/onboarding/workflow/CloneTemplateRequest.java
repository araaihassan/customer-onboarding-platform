package co.ara.onboarding.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * WorkflowController.clone's request body (sub-project 3A, Task 16 / QA Q21):
 * which customer to tailor the catalogue template for, and what to name the
 * resulting clone. customerId is resolved through {@code customer.view} before
 * anything is written -- see {@link CustomerTemplateService#clone} -- so an
 * out-of-scope or foreign-tenant value 404s rather than silently creating a
 * clone against a customer the caller could never otherwise see.
 *
 * A blank name is a 400, the same shape {@link CreateTemplateRequest} already
 * gives a catalogue template's own name.
 */
public record CloneTemplateRequest(
        @NotNull UUID customerId,
        @NotBlank String name) {}
