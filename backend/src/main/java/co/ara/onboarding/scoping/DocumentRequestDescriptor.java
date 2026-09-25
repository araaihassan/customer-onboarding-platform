package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.document.DocumentRequest;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against a "document_request" resource type either
 * -- {@code document.request}'s own catalogued resourceType is "document", per
 * the design spec's permission table -- but {@code AuthorizedQuery} dispatches
 * by ENTITY TYPE, so a DocumentRequest read still needs its own registered
 * descriptor, the same reason {@link DocumentVersionDescriptor},
 * {@link DocumentShareDescriptor} and {@link DocumentCaseLinkDescriptor} exist.
 *
 * Unlike those three, {@code document_request} carries its own case_id column
 * directly (V23__document.sql), so this is a single-hop viaCase, the same shape
 * as {@code TaskDescriptor} and {@code ApprovalDescriptor}. It has no personal
 * relationship column of its own that stands for ASSIGNED the way a task's
 * assignee_id or a document's uploaded_by does: requested_of_contact_id is an
 * external customer_contact, not an app_user, and requested_by is the creator,
 * which {@code CaseDescriptor}'s own reasoning excludes from ASSIGNED (having
 * once created a record is not an ongoing relationship to it). ASSIGNED
 * therefore resolves through case_participant instead, the same fallback
 * {@code ApprovalDescriptor} and {@code CaseAttributeValueDescriptor} use for an
 * entity with no personal column of its own.
 *
 * <p><b>This makes ASSIGNED here deliberately broader than {@link DocumentDescriptor}'s
 * own ASSIGNED</b>, and that asymmetry is real, not an oversight: an ASSIGNED-scoped
 * holder of a permission gating {@code DocumentRequest} sees every request on any
 * case they participate in (case-participant-mediated, the {@code ApprovalDescriptor}
 * precedent), while the same actor sees only the documents they personally
 * uploaded under {@code DocumentDescriptor}'s own ASSIGNED. It is also live, not
 * theoretical: {@code document.upload} IS catalogued at ASSIGNED (the design
 * spec's own permission table), and the fulfilment write path the write-scope
 * invariant requires (CLAUDE.md: every id a write path takes from a URL or body
 * must resolve through {@code AuthorizedQuery} before the write) will resolve a
 * {@code DocumentRequest} id under that very permission -- so whoever builds the
 * service layer on top (Tasks 14+) should read this asymmetry as a real design
 * constraint on what an ASSIGNED-scoped uploader can see and fulfil, not assume
 * it mirrors {@code DocumentDescriptor}'s narrower, personal ASSIGNED.
 */
@Component
public class DocumentRequestDescriptor implements ResourceAuthorizationDescriptor<DocumentRequest> {

    @Override public String resourceType() { return "document_request"; }

    @Override public Class<DocumentRequest> entityType() { return DocumentRequest.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<DocumentRequest> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<DocumentRequest> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<DocumentRequest> assignedScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return c.get("id").in(sub);
        });
    }

    /**
     * The subquery selects case ids matching the condition and tests caseId
     * against them. RLS still applies to the subquery's own table, so a request
     * cannot be reached through a case in another tenant.
     */
    private Specification<DocumentRequest> viaCase(CaseCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var c = subquery.from(Case.class);
            subquery.select(c.get("id"))
                    .where(condition.build(root, query, cb, c));
            return root.get("caseId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface CaseCondition {
        Predicate build(Root<DocumentRequest> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
