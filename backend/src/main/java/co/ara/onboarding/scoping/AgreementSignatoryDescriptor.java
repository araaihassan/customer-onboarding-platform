package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementSignatory;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against an "agreement_signatory" resource type --
 * {@code agreement.manage}'s own catalogued resourceType is "agreement", per the
 * design spec's permission table -- but {@code AuthorizedQuery} dispatches by
 * ENTITY type, and {@code AgreementService} reads signatories under
 * agreement.view/agreement.manage, so a signatory read still needs its own
 * registered descriptor: the {@code CaseParticipantDescriptor} lesson (CLAUDE.md
 * "Six descriptors exist ... AuthorizedQuery.findAll/getById dispatch by ENTITY
 * type"), confirmed live here by {@code DescriptorRegistryTest} accepting exactly
 * this shape without requiring a matching permission.
 *
 * {@code agreement_signatory} carries its own agreement_id column directly
 * (V25__agreement.sql), so this is a two-hop resolution -- signatory -> agreement
 * -> case -- the same shape {@link DocumentVersionDescriptor} uses for
 * document_version -> document -> case. Delegating to {@link AgreementDescriptor}'s
 * own scope predicates (rather than duplicating the case-condition logic here) is
 * safe because every predicate ultimately resolves against the SAME top-level
 * {@code CriteriaQuery} passed down through {@code viaAgreement} -- {@code
 * query.subquery(...)} always attaches a new, independent subquery to that one
 * query object, never to an already-open subquery, so nesting a delegate's own
 * internal subquery calls inside this class's subquery causes no conflict; this is
 * the exact multi-hop-subqueries-off-one-top-query pattern
 * {@code DocumentVersionDescriptor} already uses successfully.
 */
@Component
public class AgreementSignatoryDescriptor implements ResourceAuthorizationDescriptor<AgreementSignatory> {

    private final AgreementDescriptor agreements;

    public AgreementSignatoryDescriptor(AgreementDescriptor agreements) {
        this.agreements = agreements;
    }

    @Override public String resourceType() { return "agreement_signatory"; }

    @Override public Class<AgreementSignatory> entityType() { return AgreementSignatory.class; }

    @Override public Set<RelationshipType> assignedRelationships() { return agreements.assignedRelationships(); }

    @Override public Specification<AgreementSignatory> departmentScope(AuthContext ctx) {
        return viaAgreement(agreements.departmentScope(ctx));
    }

    @Override public Specification<AgreementSignatory> teamScope(AuthContext ctx) {
        return viaAgreement(agreements.teamScope(ctx));
    }

    @Override public Specification<AgreementSignatory> assignedScope(AuthContext ctx) {
        return viaAgreement(agreements.assignedScope(ctx));
    }

    private Specification<AgreementSignatory> viaAgreement(Specification<Agreement> onAgreement) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var a = sub.from(Agreement.class);
            sub.select(a.get("id")).where(onAgreement.toPredicate(a, query, cb));
            return root.get("agreementId").in(sub);
        };
    }
}
