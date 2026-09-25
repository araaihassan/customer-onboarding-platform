package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import org.springframework.data.domain.Page;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read paths, the derived {@link AgreementDisplayStatus} and the lifecycle
 * summary (spec sections 5.7, 8). No write method lives here yet -- Task 12
 * onward add submit/review/send/sign/cancel to this same class.
 *
 * <p>Display names -- a signatory's contact or user name, and an agreement's
 * customer name -- are resolved best-effort through {@link AuthorizedQuery}
 * under {@code contact.view}/{@code user.view}/{@code customer.view}
 * respectively, catching {@link NoSuchElementException} and returning {@code
 * null}. This mirrors CLAUDE.md's own finding that a nested {@code
 * workflow.view} lookup 404'd a case's WHOLE read rather than leaving the
 * stage-name field blank -- a name lookup here must never fail the agreement
 * read itself.
 *
 * <p>{@code today} is always {@link LocalDate#now(Clock)} against the
 * injected {@link Clock} (production: {@code Clock.systemUTC()}; tests: the
 * UTC-backed {@code MutableClock}), never the bare zero-arg overload.
 */
@Service
public class AgreementService {

    private final AgreementRepository agreements;
    private final AgreementSignatoryRepository signatories;
    private final AgreementVersionRepository versions;
    private final AgreementVersionReviewRepository versionReviews;
    private final AgreementSignatureRepository signatures;
    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final AppUserRepository users;
    private final AuthorizedQuery authorizedQuery;
    private final Clock clock;

    public AgreementService(AgreementRepository agreements, AgreementSignatoryRepository signatories,
                            AgreementVersionRepository versions, AgreementVersionReviewRepository versionReviews,
                            AgreementSignatureRepository signatures, CustomerRepository customers,
                            CustomerContactRepository contacts, AppUserRepository users,
                            AuthorizedQuery authorizedQuery, Clock clock) {
        this.agreements = agreements;
        this.signatories = signatories;
        this.versions = versions;
        this.versionReviews = versionReviews;
        this.signatures = signatures;
        this.customers = customers;
        this.contacts = contacts;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.clock = clock;
    }

    /**
     * {@code status} filters on the DISPLAY status, which is why EXPIRED and
     * SIGNED each need their own translation back into a stored-status
     * specification (spec 5.7) rather than a plain {@code status = :status}
     * -- EXPIRED does not exist as a stored value at all.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public Page<AgreementView> list(AgreementDisplayStatus status, Pageable pageable) {
        Specification<Agreement> spec = statusSpec(status);
        return authorizedQuery.findAll(agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, spec, pageable)
                .map(this::toView);
    }

    /** Live agreements (anything not CANCELLED) first, then CANCELLED ones newest-first. */
    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public List<AgreementView> forCase(UUID caseId) {
        Specification<Agreement> byCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        List<Agreement> all = authorizedQuery.findAll(
                        agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, byCase, Pageable.unpaged())
                .getContent();

        List<Agreement> live = all.stream()
                .filter(a -> a.getStatus() != AgreementStatus.CANCELLED)
                .toList();
        List<Agreement> cancelledNewestFirst = all.stream()
                .filter(a -> a.getStatus() == AgreementStatus.CANCELLED)
                .sorted(Comparator.comparing(Agreement::getCreatedAt).reversed())
                .toList();

        return Stream.concat(live.stream(), cancelledNewestFirst.stream()).map(this::toView).toList();
    }

    /**
     * Children of the resolved agreement (signatories, versions, their
     * reviews, signatures) are read through Task 3's own discovery queries,
     * fed only this method's already-authorized {@code id} -- never a raw id
     * from elsewhere -- exactly the "fed only a pre-authorized id" shape
     * {@code AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS} already
     * documents for other modules' own equivalents.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public AgreementDetailView get(UUID id) {
        Agreement a = authorizedQuery.getById(agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, id);

        List<AgreementSignature> signatureRows = signatures.ofAgreement(a.getId());
        Set<UUID> signedSignatoryIds = signatureRows.stream()
                .map(AgreementSignature::getSignatoryId)
                .collect(Collectors.toSet());

        List<AgreementSignatoryView> signatoryViews = signatories.ofAgreement(a.getId()).stream()
                .map(s -> toSignatoryView(s, signedSignatoryIds.contains(s.getId())))
                .toList();

        List<AgreementVersion> versionRows = versions.ofAgreementNewestFirst(a.getId());
        List<UUID> versionIds = versionRows.stream().map(AgreementVersion::getId).toList();
        Map<UUID, AgreementVersionReview> reviewsByVersion = versionIds.isEmpty()
                ? Map.of()
                : versionReviews.ofVersions(versionIds).stream()
                        .collect(Collectors.toMap(AgreementVersionReview::getAgreementVersionId, r -> r));
        List<AgreementVersionView> versionViews = versionRows.stream()
                .map(v -> toVersionView(v, reviewsByVersion.get(v.getId())))
                .toList();

        List<AgreementSignatureView> signatureViews = signatureRows.stream().map(this::toSignatureView).toList();

        return new AgreementDetailView(toView(a), signatoryViews, versionViews, signatureViews);
    }

    /** Spec 8: the lifecycle summary tile. Six independently scope-respecting counts, none of them a page. */
    @RequirePermission(PermissionKeys.AGREEMENT_VIEW)
    @Transactional(readOnly = true)
    public AgreementSummaryView summary() {
        LocalDate today = LocalDate.now(clock);

        long draft = countByStatuses(AgreementStatus.DRAFT);
        long underReview = countByStatuses(AgreementStatus.UNDER_REVIEW, AgreementStatus.APPROVED);
        long sent = countByStatuses(AgreementStatus.SENT);
        long awaitingSignature = countByStatuses(AgreementStatus.AWAITING_SIGNATURE);
        long signed = authorizedQuery.count(
                agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, signedNotExpiredSpec(today));
        long expiringWithin30Days = authorizedQuery.count(
                agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, expiringWithinSpec(today));

        return new AgreementSummaryView(draft, underReview, sent, awaitingSignature, signed, expiringWithin30Days);
    }

    private long countByStatuses(AgreementStatus... statuses) {
        Specification<Agreement> spec = (root, query, cb) -> root.get("status").in((Object[]) statuses);
        return authorizedQuery.count(agreements, Agreement.class, PermissionKeys.AGREEMENT_VIEW, spec);
    }

    private Specification<Agreement> statusSpec(AgreementDisplayStatus status) {
        if (status == null) return null;
        LocalDate today = LocalDate.now(clock);
        return switch (status) {
            case EXPIRED -> (root, query, cb) -> cb.and(
                    cb.equal(root.get("status"), AgreementStatus.SIGNED),
                    cb.lessThan(root.get("expiresAt"), today));
            case SIGNED -> signedNotExpiredSpec(today);
            default -> (root, query, cb) -> cb.equal(root.get("status"), AgreementStatus.valueOf(status.name()));
        };
    }

    /** status = SIGNED and (expires_at is null or expires_at >= today). */
    private Specification<Agreement> signedNotExpiredSpec(LocalDate today) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), AgreementStatus.SIGNED),
                cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThanOrEqualTo(root.get("expiresAt"), today)));
    }

    /** status = SIGNED and expires_at between today and today + 30 days, inclusive on both ends. */
    private Specification<Agreement> expiringWithinSpec(LocalDate today) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), AgreementStatus.SIGNED),
                cb.between(root.get("expiresAt"), today, today.plusDays(30)));
    }

    private AgreementView toView(Agreement a) {
        LocalDate today = LocalDate.now(clock);
        AgreementDisplayStatus displayStatus = AgreementDisplayStatus.of(a.getStatus(), a.getExpiresAt(), today);
        int latestVersionNumber = versions.maxVersionNumber(a.getId());
        return new AgreementView(a.getId(), a.getCaseId(), a.getRequirementId(), a.getCustomerId(),
                resolveCustomerName(a.getCustomerId()), a.getName(), a.getRecordMode(), a.getStatus(), displayStatus,
                a.getEffectiveDate(), a.getExpiresAt(), a.getRenewalDate(), a.getNoticePeriodDays(),
                a.getOwnerUserId(), a.getDocumentId(), a.getLastEditedBy(), a.getReplacesAgreementId(),
                a.getCancelReason(), a.getSignedAt(), a.getSignatureProvider(), latestVersionNumber,
                a.getLockVersion());
    }

    private AgreementSignatoryView toSignatoryView(AgreementSignatory s, boolean signed) {
        String displayName = switch (s.getKind()) {
            case CONTACT -> resolveContactName(s.getContactId());
            case INTERNAL -> resolveUserName(s.getUserId());
        };
        return new AgreementSignatoryView(s.getId(), s.getKind(), s.getContactId(), s.getUserId(), displayName,
                s.getDisplayRole(), s.getSortOrder(), signed);
    }

    private AgreementVersionView toVersionView(AgreementVersion v, AgreementVersionReview review) {
        return new AgreementVersionView(v.getId(), v.getVersionNumber(), v.getSubmittedBy(), v.getSubmittedAt(),
                v.getLastEditedBy(), v.getContentSha256(), v.getDocumentVersionId(), v.getDocumentSha256(),
                review == null ? null : review.getDecision(),
                review == null ? null : review.getReviewerId(),
                review == null ? null : review.getReviewedAt(),
                review == null ? null : review.getReason());
    }

    private AgreementSignatureView toSignatureView(AgreementSignature s) {
        return new AgreementSignatureView(s.getId(), s.getSignatoryId(), s.getAgreementVersionId(),
                s.getSignedContentSha256(), s.getSignedOn(), s.getMethod(), s.getRecordedBy(), s.getRecordedAt(),
                s.getCountersignedDocumentVersionId());
    }

    private String resolveCustomerName(UUID customerId) {
        if (customerId == null) return null;
        try {
            return authorizedQuery.getById(customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, customerId)
                    .getDisplayName();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private String resolveContactName(UUID contactId) {
        if (contactId == null) return null;
        try {
            return authorizedQuery.getById(contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW, contactId)
                    .getFullName();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private String resolveUserName(UUID userId) {
        if (userId == null) return null;
        try {
            return authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, userId).getFullName();
        } catch (NoSuchElementException e) {
            return null;
        }
    }
}
