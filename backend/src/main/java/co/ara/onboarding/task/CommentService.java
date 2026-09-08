package co.ara.onboarding.task;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Internal comments on a task or a journey, polymorphic over
 * {@link CommentResourceType} (design spec 3.3, 4.3). Every comment is
 * resolved through the case it is attached to, never directly: {@code
 * case_id} is denormalised onto {@link Comment} for exactly this reason (a
 * cross-case "My work"-shaped query would otherwise need a join before the
 * scope predicate could apply), and it is always taken from the RESOLVED
 * {@link Case}, never from the request -- {@link CreateCommentRequest}
 * carries no caseId field at all to trust in the first place.
 *
 * <p><b>Reading is gated {@code case.view}, not a bespoke {@code
 * comment.view}.</b> Design spec 6.1 is explicit that a separate {@code
 * comment.view} would be a permission nobody would ever grant differently:
 * if an actor can see the journey, they can see its discussion. {@link #get}
 * and {@link #forResource} are therefore gated {@link PermissionKeys#CASE_VIEW},
 * and every finder here (including the read path) still goes through
 * {@link AuthorizedQuery} with {@code scoping.CommentDescriptor} -- no
 * carve-out, which is the whole reason {@code case_id} is denormalised in
 * the first place (CLAUDE.md is explicit a second {@code AuthorizedQuery}
 * exclusion needs its own argument, not a copy of the audit-timeline one;
 * this design needs none at all).
 *
 * <p><b>Posting is gated {@code comment.create}</b> (ORG_SCOPES: ALL /
 * DEPARTMENT / TEAM -- deliberately no ASSIGNED, a comment has no personal
 * "assignee" of its own). <b>Editing is gated {@code comment.create} too,
 * PLUS an independent, hand-written author-only check that no scope
 * widens</b>: there is deliberately no permission that grants editing
 * another author's comment (spec 6.1) -- the absence is the denial, so it
 * cannot be expressed as a catalogued scope and is enforced here instead,
 * by manually throwing {@link AccessDeniedException} exactly the way
 * {@link AuthContextProvider} and {@code PermissionGateAspect} already do
 * elsewhere in this codebase. An actor holding {@code comment.create} at
 * ALL scope passes both the method-level gate and {@link AuthorizedQuery}'s
 * scope predicate (ALL sees everything) and is STILL refused here if they
 * are not the comment's own author -- the refusal is independent of, and
 * layered on top of, both.
 *
 * <p><b>{@code resourceId} is independently confirmed to belong to the
 * resolved case before a comment is ever filed against it.</b>
 * {@code resource_type}/{@code resource_id} form a polymorphic pair with no
 * literal database foreign key -- a discriminator column cannot point at two
 * different target tables -- so there is no FK backstop the way an ordinary
 * id column gets one. An invented {@code resourceId}, or one belonging to a
 * DIFFERENT case (same tenant or not), is refused as {@link
 * NoSuchElementException} (404) here, the same "does the id actually belong
 * to what it claims" class of check {@code TaskService#create}'s own
 * case/milestone mismatch guard performs.
 *
 * <p>{@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}
 * already covers {@code co.ara.onboarding.task} (added in Task 16) -- every
 * repository finder here is reached through {@link AuthorizedQuery}.
 */
@Service
public class CommentService {

    private final CommentRepository comments;
    private final CaseRepository cases;
    private final TaskRepository tasks;
    private final AuthorizedQuery authorizedQuery;
    private final AuditRecorder audit;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    public CommentService(CommentRepository comments, CaseRepository cases, TaskRepository tasks,
                          AuthorizedQuery authorizedQuery, AuditRecorder audit,
                          AuthContextProvider contextProvider, Clock clock) {
        this.comments = comments;
        this.cases = cases;
        this.tasks = tasks;
        this.authorizedQuery = authorizedQuery;
        this.audit = audit;
        this.contextProvider = contextProvider;
        this.clock = clock;
    }

    /**
     * caseId is the URL's nesting id, resolved first so an out-of-scope case
     * is a 404 before anything about the request body -- including whether
     * resourceId even makes sense -- is considered. authorId is always the
     * calling principal, never a request field: there is nothing in {@link
     * CreateCommentRequest} to disagree with it.
     */
    @RequirePermission(PermissionKeys.COMMENT_CREATE)
    @Transactional
    public CommentView create(UUID caseId, CreateCommentRequest request) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.COMMENT_CREATE, caseId);
        checkResourceBelongsToCase(c.getId(), request.resourceType(), request.resourceId());

        Comment comment = new Comment();
        comment.setId(Uuid7.generate());
        comment.setTenantId(c.getTenantId());
        comment.setCaseId(c.getId());
        comment.setResourceType(request.resourceType());
        comment.setResourceId(request.resourceId());
        comment.setAuthorId(contextProvider.principal().userId());
        comment.setBody(request.body());
        comments.save(comment);

        audit.record(AuditActions.COMMENT_ADDED, "onboarding_case", c.getId(),
                "Comment added on " + request.resourceType().wireValue(),
                Map.of("commentId", comment.getId().toString(), "resourceId", request.resourceId().toString()));

        return toView(comment);
    }

    /** Gated case.view, matching {@link #forResource} -- see the class javadoc. */
    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public CommentView get(UUID commentId) {
        Comment comment = authorizedQuery.getById(comments, Comment.class, PermissionKeys.CASE_VIEW, commentId);
        return toView(comment);
    }

    /**
     * Confirms the case itself is visible FIRST, under case.view, before any
     * comment is ever queried -- a cross-tenant or otherwise out-of-scope
     * caseId is therefore a 404 by construction: {@link AuthorizedQuery#getById}
     * on the {@link Case} never returns a row RLS hides, so this throws
     * before the comment query below has any chance to run.
     */
    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public List<CommentView> forResource(UUID caseId, CommentResourceType resourceType, UUID resourceId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);

        Specification<Comment> byResource = (root, query, cb) -> cb.and(
                cb.equal(root.get("caseId"), caseId),
                cb.equal(root.get("resourceType"), resourceType),
                cb.equal(root.get("resourceId"), resourceId));
        return authorizedQuery.findAll(
                comments, Comment.class, PermissionKeys.CASE_VIEW, byResource, Pageable.unpaged())
                .getContent().stream().map(this::toView).toList();
    }

    /**
     * Gated comment.create -- an actor holding none of it cannot edit
     * anything, their own comments included. The author-only check below is
     * independent of, and layered on top of, both that gate and whatever
     * scope {@link AuthorizedQuery} resolves for it: see the class javadoc.
     */
    @RequirePermission(PermissionKeys.COMMENT_CREATE)
    @Transactional
    public CommentView update(UUID commentId, UpdateCommentRequest request) {
        Comment comment = authorizedQuery.getById(comments, Comment.class, PermissionKeys.COMMENT_CREATE, commentId);

        UUID actorId = contextProvider.principal().userId();
        if (!comment.getAuthorId().equals(actorId)) {
            throw new AccessDeniedException("Only the author may edit this comment");
        }

        comment.setBody(request.body());
        comment.setEditedAt(Instant.now(clock));
        comments.save(comment);

        audit.record(AuditActions.COMMENT_EDITED, "onboarding_case", comment.getCaseId(),
                "Comment edited", Map.of("commentId", comment.getId().toString()));

        return toView(comment);
    }

    /**
     * CASE comments must name the same case they are nested under -- there is
     * no other case they could sensibly attach to. TASK comments must resolve
     * to a real {@link Task} that actually belongs to THIS case, resolved
     * through {@link AuthorizedQuery} under comment.create (dispatching to
     * {@code scoping.TaskDescriptor} by entity type, the same pattern {@code
     * TaskService#resolveAssigneeId} already establishes for a differently-typed
     * id) so an out-of-scope or invented task id is refused the same way an
     * out-of-scope case is -- never a raw existence check that would bypass
     * scope entirely.
     */
    private void checkResourceBelongsToCase(UUID caseId, CommentResourceType resourceType, UUID resourceId) {
        switch (resourceType) {
            case CASE -> {
                if (!resourceId.equals(caseId)) {
                    throw new NoSuchElementException("Not found");
                }
            }
            case TASK -> {
                Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.COMMENT_CREATE, resourceId);
                if (!t.getCaseId().equals(caseId)) {
                    throw new NoSuchElementException("Not found");
                }
            }
        }
    }

    private CommentView toView(Comment c) {
        return new CommentView(c.getId(), c.getCaseId(), c.getResourceType(), c.getResourceId(),
                c.getAuthorId(), c.getBody(), c.getCreatedAt(), c.getEditedAt());
    }
}
