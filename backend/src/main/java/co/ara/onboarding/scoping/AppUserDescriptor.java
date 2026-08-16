package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.identity.AppUser;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AppUserDescriptor implements ResourceAuthorizationDescriptor<AppUser> {

    @Override public String resourceType() { return "app_user"; }

    @Override public Class<AppUser> entityType() { return AppUser.class; }

    /** For a user record, the "owner" is the user themselves. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER);
    }

    @Override public Specification<AppUser> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("departmentId"), ctx.departmentId());
    }

    /**
     * teamIds is an @ElementCollection over team_member, so TEAM scope is "shares
     * at least one team with the actor" and needs a join rather than a column
     * comparison. The join multiplies rows for a user in several matching teams,
     * hence the distinct — without it a list query returns duplicates.
     */
    @Override public Specification<AppUser> teamScope(AuthContext ctx) {
        return (root, query, cb) -> {
            if (ctx.teamIds().isEmpty()) return cb.disjunction();
            if (query != null) query.distinct(true);
            return root.join("teamIds").in(ctx.teamIds());
        };
    }

    @Override public Specification<AppUser> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.equal(root.get("id"), ctx.userId());
    }
}
