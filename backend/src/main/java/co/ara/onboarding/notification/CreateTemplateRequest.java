package co.ara.onboarding.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Named explicitly: {@code workflow.CreateTemplateRequest} owns the simple name in the OpenAPI document. */
@Schema(name = "CreateNotificationTemplateRequest")
public record CreateTemplateRequest(@NotBlank String key, @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 200) String enteredSubject, @NotBlank @Size(max = 2000) String enteredBody,
        @Size(max = 200) String exitedSubject, @Size(max = 2000) String exitedBody, @NotNull Boolean active) {}
