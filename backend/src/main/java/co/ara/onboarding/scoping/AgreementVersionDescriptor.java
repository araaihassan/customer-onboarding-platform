package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementVersion;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against an "agreement_version" resource type either
 * -- same reasoning as {@link AgreementSignatoryDescriptor}'s own javadoc, and the
 * same {@code CaseParticipantDescriptor} precedent {@code DescriptorRegistryTest}
 * confirms is fine. {@code agreement_version} carries its own agreement_id column
 * directly (V25__agreement.sql), so this is the identical two-hop shape as
 * {@link AgreementSignatoryDescriptor}: version -> agreement -> case.
 */
@Component
public class AgreementVersionDescriptor implements ResourceAuthorizationDescriptor<AgreementVersion> {

    private final AgreementDescriptor agreements;

    public AgreementVersionDescriptor(AgreementDescriptor agreements) {
        this.agreements = agreements;
    }

    @Override public String resourceType() { return "agreement_version"; }

    @Override public Class<AgreementVersion> entityType() { return AgreementVersion.class; }

    @Override public Set<RelationshipType> assignedRelationships() { return agreements.assignedRelationships(); }

    @Override public Specification<AgreementVersion> departmentScope(AuthContext ctx) {
        return viaAgreement(agreements.departmentScope(ctx));
    }

    @Override public Specification<AgreementVersion> teamScope(AuthContext ctx) {
        return viaAgreement(agreements.teamScope(ctx));
    }

    @Override public Specification<AgreementVersion> assignedScope(AuthContext ctx) {
        return viaAgreement(agreements.assignedScope(ctx));
    }

    private Specification<AgreementVersion> viaAgreement(Specification<Agreement> onAgreement) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var a = sub.from(Agreement.class);
            sub.select(a.get("id")).where(onAgreement.toPredicate(a, query, cb));
            return root.get("agreementId").in(sub);
        };
    }
}
