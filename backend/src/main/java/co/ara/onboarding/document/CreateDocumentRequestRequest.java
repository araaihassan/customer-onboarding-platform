package co.ara.onboarding.document;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /cases/{caseId}/document-requests} (Task 23; design
 * spec 4.5/5.2). {@code caseId} is deliberately NOT a field here -- it is
 * {@link DocumentRequestService#create}'s own URL-nesting parameter, the same
 * "the URL, not the body, names the parent" shape {@link CreateDocumentRequest}
 * already follows for a document's own {@code caseId}.
 *
 * <p>This request type only ever produces an AD-HOC request:
 * {@code requirementId} is never accepted here at all -- {@link
 * DocumentRequestService#create} always writes it {@code null}. A
 * requirement-instantiated request is Task 24's own job
 * ({@code DocumentInstantiation}, modelled on {@code task.TaskInstantiation}),
 * which sets {@code requirementId} directly rather than accepting one from a
 * caller.
 *
 * <p>{@code requiresReview} is a real field here, not a default or an
 * inherited value -- design spec 5.3's own words: "an ad-hoc request takes it
 * from the requester." Contrast the requirement-instantiated path Task 24
 * builds, which reads {@code requirement_definition.requires_review} instead
 * (defaulting {@code false} when that column is null) and never asks the
 * caller. There is deliberately no default here either: a primitive
 * {@code boolean} binds an omitted JSON key to {@code false}, which is
 * exactly CLAUDE.md's own documented trap for a boolean field with no
 * explicit default (see {@code StageRequest.autoAdvance}'s own history) --
 * but here {@code false} ("uploading satisfies it immediately," design spec
 * 5.3) is a legitimate, deliberately chosen value for an ad-hoc request that
 * omits the field, not a silent misconfiguration, so no {@code @NotNull}
 * wrapper is warranted the way {@link CreateDocumentRequest#category} carries
 * one.
 *
 * <p>{@code requestedOfContactId} is nullable (design spec 4.5) -- a request
 * need not name a specific contact. When supplied, {@link
 * DocumentRequestService#create} resolves it through {@code AuthorizedQuery}
 * under {@code contact.view} before writing anything, composing that READ
 * permission with this method's own {@code document.request} WRITE gate --
 * the identical shape {@code task.TaskService#resolveAssigneeId} uses
 * composing {@code user.view} with {@code task.manage}, and {@code
 * DocumentSharingService#resolveContact} already uses composing {@code
 * contact.view} with {@code document.share}.
 */
public record CreateDocumentRequestRequest(@NotNull DocumentCategory category, String description,
                                           Instant dueAt, boolean requiresReview,
                                           UUID requestedOfContactId) {}
