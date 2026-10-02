package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.UserType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The portal (customer-facing) agreement reads. Self-defending: each gated method refuses a
 * non-PORTAL caller with the same {@link NoSuchElementException} an out-of-scope id gets, rather
 * than trusting its controller. Visibility itself -- own customer, SENT onward, never CANCELLED --
 * is {@code scoping.AgreementAudienceFilter}'s, applied by {@link AuthorizedQuery} even at
 * {@code Scope.ALL}. Children (signatories, versions, signatures) are read by the already-resolved
 * agreement's id only.
 */
@Service
public class PortalAgreementService {

    private final AgreementRepository agreements;
    private final AgreementSignatoryRepository signatories;
    private final AgreementVersionRepository versions;
    private final AgreementSignatureRepository signatures;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    public PortalAgreementService(AgreementRepository agreements, AgreementSignatoryRepository signatories,
                                  AgreementVersionRepository versions, AgreementSignatureRepository signatures,
                                  AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider, Clock clock) {
        this.agreements = agreements;
        this.signatories = signatories;
        this.versions = versions;
        this.signatures = signatures;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.clock = clock;
    }

    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public List<PortalAgreementView> mine() {
        requirePortal();
        Specification<Agreement> all = (root, query, cb) -> cb.conjunction();
        return authorizedQuery.findAll(agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, all, Pageable.unpaged())
                .getContent().stream()
                .sorted(Comparator.comparing(Agreement::getCreatedAt).reversed())
                .map(this::toView)
                .toList();
    }

    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public PortalAgreementView get(UUID id) {
        requirePortal();
        return toView(authorizedQuery.getById(agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, id));
    }

    private void requirePortal() {
        if (contextProvider.current().userType() != UserType.PORTAL) {
            throw new NoSuchElementException("Not found");
        }
    }

    private PortalAgreementView toView(Agreement a) {
        List<AgreementVersion> versionRows = versions.ofAgreementNewestFirst(a.getId());
        AgreementVersion sent = versionRows.isEmpty() ? null : versionRows.get(0);

        Map<UUID, AgreementSignature> signatureBySignatory = signatures.ofAgreement(a.getId()).stream()
                .collect(Collectors.toMap(AgreementSignature::getSignatoryId, Function.identity(), (x, y) -> x));
        List<PortalAgreementView.PortalSignatory> parties = signatories.ofAgreement(a.getId()).stream()
                .map(s -> {
                    AgreementSignature sig = signatureBySignatory.get(s.getId());
                    return new PortalAgreementView.PortalSignatory(
                            s.getDisplayRole(), sig != null, sig == null ? null : sig.getSignedOn());
                })
                .toList();

        return new PortalAgreementView(a.getId(), a.getCaseId(), a.getName(), a.getRecordMode(),
                AgreementDisplayStatus.of(a.getStatus(), a.getExpiresAt(), LocalDate.now(clock)),
                a.getEffectiveDate(), a.getExpiresAt(), a.getRenewalDate(), a.getNoticePeriodDays(),
                sent == null ? 0 : sent.getVersionNumber(), sent == null ? null : sent.getContentSha256(),
                a.getDocumentId(), parties, a.getSignedAt());
    }
}
