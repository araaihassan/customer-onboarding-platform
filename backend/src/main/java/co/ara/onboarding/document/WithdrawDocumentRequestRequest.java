package co.ara.onboarding.document;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /document-requests/{id}/withdraw} -- mirrors {@link
 * RetireDocumentRequest}'s own shape and reasoning exactly. {@link
 * DocumentRequestService#withdraw} already refuses a blank reason itself
 * ({@link IllegalArgumentException}, mapped to 400 by {@code
 * platform.ApiExceptionHandler}); {@code @NotBlank} here just moves that same
 * refusal earlier, into ordinary bean validation.
 *
 * <p>{@code reason} is deliberately NOT persisted anywhere -- {@code
 * document_request} has no reason column, the identical precedent {@link
 * DocumentService#retire}'s own javadoc already establishes for its own
 * {@code reason} parameter: {@code document.request_withdrawn} is one of Task
 * 29's own ten future {@code document.*} audit actions that will carry it in
 * the audit payload once that action exists, so validating-but-not-storing it
 * now is not a gap, it is the known shape of a value waiting on its own audit
 * action to land.
 */
public record WithdrawDocumentRequestRequest(@NotBlank String reason) {}
