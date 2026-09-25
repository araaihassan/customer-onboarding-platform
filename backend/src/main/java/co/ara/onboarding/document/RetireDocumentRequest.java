package co.ara.onboarding.document;

import jakarta.validation.constraints.NotBlank;

/**
 * Task 22's own minimal request body for {@code POST /documents/{id}/retire} --
 * {@link DocumentService#retire} already takes a plain {@code (UUID, String)}
 * pair rather than an existing record, so this is the one new request type
 * this task adds beyond the ones already listed in the plan
 * ({@link CreateDocumentRequest}, {@link PatchDocumentRequest}), following the
 * same one-field-record shape {@code task.TaskStatusRequest}'s own
 * {@code reason} field uses. {@link DocumentService#retire} itself already
 * refuses a blank reason with an {@link IllegalArgumentException} (mapped to
 * 400 by {@code platform.ApiExceptionHandler}); {@code @NotBlank} here just
 * moves that same refusal earlier, into ordinary bean validation, the same
 * way every other unconditionally-required reason field in this codebase
 * already does.
 */
public record RetireDocumentRequest(@NotBlank String reason) {}
