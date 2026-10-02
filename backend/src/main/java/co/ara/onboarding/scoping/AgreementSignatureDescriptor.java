package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementSignature;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against an "agreement_signature" resource type
 * either -- same reasoning as {@link AgreementSignatoryDescriptor}'s own javadoc.
 * {@code agreement_signature} carries its own agreement_id column directly,
 * denormalised from signatory_id/agreement_version_id the same way
 * {@code agreement.customer_id} is denormalised from case_id (V25__agreement.sql's
 * own comment on the table), so this is the identical two-hop shape as
 * {@link AgreementSignatoryDescriptor} and {@link AgreementVersionDescriptor}:
 * signature -> agreement -> case -- not a three-hop through agreement_version.
 */
@Component
public class AgreementSignatureDescriptor implements ResourceAuthorizationDescriptor<AgreementSignature> {

    private final AgreementDescriptor agreements;

    public AgreementSignatureDescriptor(AgreementDescriptor agreements) {
        this.agreements = agreements;
    }

    @Override public String resourceType() { return "agreement_signature"; }

    @Override public Class<AgreementSignature> entityType() { return AgreementSignature.class; }

    @Override public Set<RelationshipType> assignedRelationships() { return agreements.assignedRelationships(); }

    @Override public Specification<AgreementSignature> departmentScope(AuthContext ctx) {
        return viaAgreement(agreements.departmentScope(ctx));
    }

    @Override public Specification<AgreementSignature> teamScope(AuthContext ctx) {
        return viaAgreement(agreements.teamScope(ctx));
    }

    @Override public Specification<AgreementSignature> assignedScope(AuthContext ctx) {
        return viaAgreement(agreements.assignedScope(ctx));
    }

    private Specification<AgreementSignature> viaAgreement(Specification<Agreement> onAgreement) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var a = sub.from(Agreement.class);
            sub.select(a.get("id")).where(onAgreement.toPredicate(a, query, cb));
            return root.get("agreementId").in(sub);
        };
    }
}
