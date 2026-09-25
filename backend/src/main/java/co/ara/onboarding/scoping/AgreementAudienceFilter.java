package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.agreement.AgreementStatus;
import co.ara.onboarding.authz.AudienceFilter;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.PortalContactDirectory;
import co.ara.onboarding.platform.UserType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Spec 6.4/6.5. Registered on sub-project 4's AudienceFilter seam, so it binds even
 * at Scope.ALL -- the only scope a portal actor ever resolves (PortalPermissions'
 * forContact()/forSponsor(), both ALL-only). Internal readers pass untouched: case
 * scope (AgreementDescriptor) already governs them, the same split
 * DocumentAudienceFilter draws between its internalAudience() (a no-op-shaped
 * conjunction handled by the scope predicates instead) and its portalAudience().
 *
 * A portal contact sees their own customer's agreements from SENT onward, never a
 * DRAFT, a version UNDER_REVIEW, an APPROVED-but-unsent agreement, or a CANCELLED
 * one -- Q-shaped: the customer should not see internal drafting/review churn, only
 * a document that has actually been sent to them, through signature, and no longer
 * a live agreement once cancelled (cancel-and-replace means the replacement, not
 * the cancelled row, is what the portal should ever show).
 *
 * The child entities (agreement_signatory, agreement_version,
 * agreement_version_review, agreement_signature) have NO audience filter of their
 * own: the portal never reads them through AuthorizedQuery directly --
 * PortalAgreementService (Task 20) loads the agreement through THIS filter first
 * and then reads its children by the resolved agreement's id, the same one-gate
 * shape DocumentAudienceFilter's own javadoc describes for Document's children.
 */
@Component
public class AgreementAudienceFilter implements AudienceFilter<Agreement> {

    static final Set<AgreementStatus> PORTAL_VISIBLE =
            EnumSet.of(AgreementStatus.SENT, AgreementStatus.AWAITING_SIGNATURE, AgreementStatus.SIGNED);

    private final PortalContactDirectory contacts;

    public AgreementAudienceFilter(PortalContactDirectory contacts) {
        this.contacts = contacts;
    }

    @Override public Class<Agreement> entityType() { return Agreement.class; }

    @Override
    public Specification<Agreement> audience(AuthContext ctx, String permissionKey) {
        if (ctx.userType() != UserType.PORTAL) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            var contact = contacts.findActiveContactForUser(ctx.userId()).orElse(null);
            if (contact == null) return cb.disjunction();   // no live contact => no agreements, ever
            return cb.and(
                    cb.equal(root.get("customerId"), contact.customerId()),
                    root.get("status").in(PORTAL_VISIBLE));
        };
    }
}
