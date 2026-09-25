package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementVersion;
import co.ara.onboarding.agreement.AgreementVersionReview;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against an "agreement_version_review" resource type
 * either -- same reasoning as {@link AgreementSignatoryDescriptor}'s own javadoc.
 * Unlike its three siblings, {@code agreement_version_review} carries only
 * agreement_version_id (V25__agreement.sql) -- no denormalised agreement_id of its
 * own -- so this is a genuine three-hop resolution: review -> version -> agreement
 * -> case. Each hop is one more sibling subquery off the same top-level
 * {@code CriteriaQuery}, the same pattern {@link AgreementSignatoryDescriptor}
 * uses for its own two hops.
 */
@Component
public class AgreementVersionReviewDescriptor implements ResourceAuthorizationDescriptor<AgreementVersionReview> {

    private final AgreementDescriptor agreements;

    public AgreementVersionReviewDescriptor(AgreementDescriptor agreements) {
        this.agreements = agreements;
    }

    @Override public String resourceType() { return "agreement_version_review"; }

    @Override public Class<AgreementVersionReview> entityType() { return AgreementVersionReview.class; }

    @Override public Set<RelationshipType> assignedRelationships() { return agreements.assignedRelationships(); }

    @Override public Specification<AgreementVersionReview> departmentScope(AuthContext ctx) {
        return viaAgreementVersion(agreements.departmentScope(ctx));
    }

    @Override public Specification<AgreementVersionReview> teamScope(AuthContext ctx) {
        return viaAgreementVersion(agreements.teamScope(ctx));
    }

    @Override public Specification<AgreementVersionReview> assignedScope(AuthContext ctx) {
        return viaAgreementVersion(agreements.assignedScope(ctx));
    }

    private Specification<AgreementVersionReview> viaAgreementVersion(Specification<Agreement> onAgreement) {
        return (root, query, cb) -> {
            var agreementSub = query.subquery(UUID.class);
            var a = agreementSub.from(Agreement.class);
            agreementSub.select(a.get("id")).where(onAgreement.toPredicate(a, query, cb));

            var versionSub = query.subquery(UUID.class);
            var v = versionSub.from(AgreementVersion.class);
            versionSub.select(v.get("id")).where(v.get("agreementId").in(agreementSub));

            return root.get("agreementVersionId").in(versionSub);
        };
    }
}
