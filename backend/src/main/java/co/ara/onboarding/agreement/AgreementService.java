package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.document.AgreementFiles;
import co.ara.onboarding.document.OwnedFile;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read paths, the derived {@link AgreementDisplayStatus} and the lifecycle
 * summary (spec sections 5.7, 8), plus the draft-editing writes Task 12 adds:
 * {@link #patch}, {@link #replaceSignatories}, {@link #uploadDraftFile}. Later
 * tasks add submit/review/send/sign/cancel to this same class.
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
    private final AgreementWrites writes;
    private final AuthContextProvider contextProvider;
    private final AgreementFiles agreementFiles;
    private final List<SignatureProvider> signatureProviders;
    private final Clock clock;
    private final AuditRecorder audit;

    public AgreementService(AgreementRepository agreements, AgreementSignatoryRepository signatories,
                            AgreementVersionRepository versions, AgreementVersionReviewRepository versionReviews,
                            AgreementSignatureRepository signatures, CustomerRepository customers,
                            CustomerContactRepository contacts, AppUserRepository users,
                            AuthorizedQuery authorizedQuery, AgreementWrites writes,
                            AuthContextProvider contextProvider, AgreementFiles agreementFiles,
                            List<SignatureProvider> signatureProviders, Clock clock,
                            AuditRecorder audit) {
        this.agreements = agreements;
        this.signatories = signatories;
        this.versions = versions;
        this.versionReviews = versionReviews;
        this.signatures = signatures;
        this.customers = customers;
        this.contacts = contacts;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.writes = writes;
        this.contextProvider = contextProvider;
        this.agreementFiles = agreementFiles;
        this.signatureProviders = signatureProviders;
        this.clock = clock;
        this.audit = audit;
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

    /**
     * A DRAFT-only partial update of an agreement's own fields (Task 12; spec sections
     * 5.2/5.4). Every field is optional and, when supplied, overwrites the current value;
     * a field named in {@code clear} is set back to {@code null} instead -- {@code
     * PatchAgreementRequest}'s own javadoc names why dates specifically need this, unlike
     * {@code document.PatchDocumentRequest}'s plain "absent means unchanged" contract. A
     * field both supplied (non-null) and named in {@code clear} is refused as a 400
     * ({@link IllegalArgumentException}) before anything is written.
     *
     * <p>{@link AgreementWrites#loadForWrite} does the rest of the prologue: resolves
     * {@code id} through {@code AuthorizedQuery} under {@code agreement.manage}, refuses a
     * stale {@code lockVersion} as a 409, refuses any status but DRAFT as a 409, and
     * narrows on top with the SIGNATURE requirement's own stage write scope.
     *
     * <p>No audit action is recorded here -- {@code agreement.submitted} records the
     * frozen result of however many drafts edits led up to it; a draft edit itself is not
     * itself a business event worth its own row (the same reasoning {@code
     * document.DocumentContentWriter}'s own draft-version path gives for uploads before
     * submission).
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView patch(UUID id, PatchAgreementRequest request) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, request.lockVersion(),
                EnumSet.of(AgreementStatus.DRAFT));

        Set<ClearableAgreementField> clear = request.clear() == null ? Set.of() : request.clear();
        if (request.effectiveDate() != null && clear.contains(ClearableAgreementField.EFFECTIVE_DATE)) {
            throw new IllegalArgumentException("effectiveDate cannot be both supplied and cleared");
        }
        if (request.expiresAt() != null && clear.contains(ClearableAgreementField.EXPIRES_AT)) {
            throw new IllegalArgumentException("expiresAt cannot be both supplied and cleared");
        }
        if (request.renewalDate() != null && clear.contains(ClearableAgreementField.RENEWAL_DATE)) {
            throw new IllegalArgumentException("renewalDate cannot be both supplied and cleared");
        }
        if (request.noticePeriodDays() != null && clear.contains(ClearableAgreementField.NOTICE_PERIOD_DAYS)) {
            throw new IllegalArgumentException("noticePeriodDays cannot be both supplied and cleared");
        }

        if (request.name() != null) a.setName(request.name());
        if (request.effectiveDate() != null) a.setEffectiveDate(request.effectiveDate());
        if (request.expiresAt() != null) a.setExpiresAt(request.expiresAt());
        if (request.renewalDate() != null) a.setRenewalDate(request.renewalDate());
        if (request.noticePeriodDays() != null) a.setNoticePeriodDays(request.noticePeriodDays());

        if (clear.contains(ClearableAgreementField.EFFECTIVE_DATE)) a.setEffectiveDate(null);
        if (clear.contains(ClearableAgreementField.EXPIRES_AT)) a.setExpiresAt(null);
        if (clear.contains(ClearableAgreementField.RENEWAL_DATE)) a.setRenewalDate(null);
        if (clear.contains(ClearableAgreementField.NOTICE_PERIOD_DAYS)) a.setNoticePeriodDays(null);

        a.setLastEditedBy(contextProvider.current().userId());
        a.setUpdatedAt(Instant.now(clock));
        agreements.saveAndFlush(a);
        return get(id);
    }

    /**
     * Replaces a DRAFT agreement's whole signatory list, in order (Task 12; spec section
     * 4.3/5.2). Every id in the request -- {@code contactId}/{@code userId} on each row
     * -- is resolved before anything is written: duplicate {@code contactId}s or {@code
     * userId}s across the list, and a row whose {@code kind} disagrees with which id it
     * carries, are refused as a 400 up front, never surfaced as a raw database constraint
     * violation. Only once every row resolves does this clear the agreement's existing
     * signatories and insert the new list, {@code sortOrder} taken from list position.
     *
     * <p>A {@code CONTACT} row must resolve through {@code contact.view} to a contact
     * belonging to the SAME customer as the agreement -- a contact that exists and is
     * visible but belongs to a different customer is refused as a 404 ({@link
     * NoSuchElementException}), deliberately NOT the 400 {@code
     * document.DocumentRequestService#resolveContact} gives an equivalent cross-customer
     * mismatch: a party of another customer is simply not a valid party here, the same
     * "not found" a genuinely out-of-scope or nonexistent contact already gets. Only once
     * the customer match is confirmed is the contact's own {@code status} checked -- a
     * retired (INACTIVE) contact of the RIGHT customer is refused as a 400 ({@link
     * IllegalArgumentException}), a state check on a record already known to belong here,
     * not a cross-reference between two records.
     *
     * <p>An {@code INTERNAL} row must resolve through {@code user.view} to an ACTIVE
     * {@code AppUser} -- an out-of-scope or nonexistent user is the usual 404; a resolved
     * but non-ACTIVE one is a 400.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView replaceSignatories(UUID id, ReplaceSignatoriesRequest request) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, request.lockVersion(),
                EnumSet.of(AgreementStatus.DRAFT));

        Set<UUID> seenContactIds = new HashSet<>();
        Set<UUID> seenUserIds = new HashSet<>();
        for (SignatoryRequest s : request.signatories()) {
            switch (s.kind()) {
                case CONTACT -> {
                    if (s.contactId() == null || s.userId() != null) {
                        throw new IllegalArgumentException(
                                "A CONTACT signatory must carry a contactId and no userId");
                    }
                    if (!seenContactIds.add(s.contactId())) {
                        throw new IllegalArgumentException(
                                "Contact " + s.contactId() + " is named as a signatory more than once");
                    }
                }
                case INTERNAL -> {
                    if (s.userId() == null || s.contactId() != null) {
                        throw new IllegalArgumentException(
                                "An INTERNAL signatory must carry a userId and no contactId");
                    }
                    if (!seenUserIds.add(s.userId())) {
                        throw new IllegalArgumentException(
                                "User " + s.userId() + " is named as a signatory more than once");
                    }
                }
            }
        }

        List<AgreementSignatory> resolved = request.signatories().stream()
                .map(s -> resolveSignatory(s, a))
                .toList();

        signatories.clearFor(a.getId());
        int sortOrder = 0;
        for (AgreementSignatory s : resolved) {
            s.setSortOrder(sortOrder++);
            signatories.saveAndFlush(s);
        }

        a.setLastEditedBy(contextProvider.current().userId());
        a.setUpdatedAt(Instant.now(clock));
        agreements.saveAndFlush(a);
        return get(id);
    }

    private AgreementSignatory resolveSignatory(SignatoryRequest s, Agreement a) {
        AgreementSignatory row = new AgreementSignatory();
        row.setId(Uuid7.generate());
        row.setTenantId(a.getTenantId());
        row.setAgreementId(a.getId());
        row.setKind(s.kind());
        row.setDisplayRole(s.displayRole());

        switch (s.kind()) {
            case CONTACT -> {
                CustomerContact contact = authorizedQuery.getById(
                        contacts, CustomerContact.class, PermissionKeys.CONTACT_VIEW, s.contactId());
                if (!contact.getCustomerId().equals(a.getCustomerId())) {
                    throw new NoSuchElementException("Not found");
                }
                if (contact.getStatus() != ContactStatus.ACTIVE) {
                    throw new IllegalArgumentException(
                            "Contact " + contact.getId() + " is retired and cannot be a signatory");
                }
                row.setContactId(contact.getId());
            }
            case INTERNAL -> {
                AppUser user = authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, s.userId());
                if (user.getStatus() != UserStatus.ACTIVE) {
                    throw new IllegalArgumentException(
                            "User " + user.getId() + " is not active and cannot be a signatory");
                }
                row.setUserId(user.getId());
            }
        }
        return row;
    }

    /**
     * Adds the agreement's first draft file, or a further revision of it (Task 12; spec
     * sections 4.4/5.2). The first upload creates the owned SENSITIVE document ({@link
     * AgreementFiles#createOwnedDocument}) and stores its id; every later upload appends a
     * version to that same document ({@link AgreementFiles#addDraftVersion}). Whichever
     * version is current when the agreement is later submitted is the one Task 13's submit
     * freezes -- this method itself does no freezing.
     *
     * <p>Refused as a 409 ({@link IllegalStateException}) on a {@code STRUCTURED_ONLY}
     * agreement -- that record mode has no file at all (spec 4.1); {@code
     * AgreementRecordMode#includesFile} names the same distinction.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView uploadDraftFile(UUID id, long lockVersion, InputStream content, long sizeBytes) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, lockVersion,
                EnumSet.of(AgreementStatus.DRAFT));

        if (a.getRecordMode() == AgreementRecordMode.STRUCTURED_ONLY) {
            throw new IllegalStateException(
                    "Agreement " + id + " is STRUCTURED_ONLY and cannot take a file");
        }

        if (a.getDocumentId() == null) {
            OwnedFile f = agreementFiles.createOwnedDocument(a.getCaseId(), a.getName(), content, sizeBytes);
            a.setDocumentId(f.documentId());
        } else {
            agreementFiles.addDraftVersion(a.getDocumentId(), content, sizeBytes);
        }

        a.setLastEditedBy(contextProvider.current().userId());
        a.setUpdatedAt(Instant.now(clock));
        agreements.saveAndFlush(a);
        return get(id);
    }

    /**
     * Freezes the current draft into an immutable, hashed {@link AgreementVersion} and
     * moves the agreement to UNDER_REVIEW (Task 13; spec sections 4.4.1/5.3). Every
     * submission-readiness rule is collected into one list before anything is written,
     * so a caller sees every problem at once rather than fixing them one 400 at a time:
     *
     * <ul>
     *   <li>at least one signatory (Review Focus 5);</li>
     *   <li>every CONTACT signatory's contact still ACTIVE, every INTERNAL signatory's
     *       user still ACTIVE -- a signatory can be added and then retired/deactivated
     *       without ever touching this agreement again, so this is re-checked at submit,
     *       not just at {@link #replaceSignatories} time (Review Focus 5);</li>
     *   <li>a record mode that {@link co.ara.onboarding.workflow.AgreementRecordMode#includesFile()}
     *       has a file;</li>
     *   <li>a record mode other than FILE_BACKED has an effective date (FILE_BACKED's own
     *       file carries its own dates; the other two modes need one to derive expiry/renewal
     *       against);</li>
     *   <li>an expiry date, when present, is after the effective date.</li>
     * </ul>
     *
     * <p>The frozen snapshot and its {@code contentSha256} are computed the same way for
     * every later version too -- {@link AgreementContentHasher} is the one place that
     * happens, over an {@link AgreementSnapshot} of the agreement's own fields and its
     * current signatory list, plus the current file's own digest when the mode carries one.
     * {@code submittedBy} is the actor who ran THIS call; {@code lastEditedBy} is copied
     * from the agreement's own field, which may well be a different person -- the two are
     * deliberately never conflated.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView submit(UUID id, long lockVersion) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, lockVersion,
                EnumSet.of(AgreementStatus.DRAFT));
        List<AgreementSignatory> parties = signatories.ofAgreement(a.getId());

        List<String> problems = new ArrayList<>();
        if (parties.isEmpty()) problems.add("an agreement needs at least one signatory");
        for (AgreementSignatory s : parties) {
            if (s.getKind() == SignatoryKind.CONTACT
                    && signatories.contactStatusOf(s.getContactId()) != ContactStatus.ACTIVE) {
                problems.add("signatory '" + s.getDisplayRole() + "' is a contact who is no longer active");
            }
            if (s.getKind() == SignatoryKind.INTERNAL
                    && signatories.userStatusOf(s.getUserId()) != UserStatus.ACTIVE) {
                problems.add("signatory '" + s.getDisplayRole() + "' is a user who is no longer active");
            }
        }
        if (a.getRecordMode().includesFile() && a.getDocumentId() == null) problems.add("this record mode needs a file");
        if (a.getRecordMode() != AgreementRecordMode.FILE_BACKED && a.getEffectiveDate() == null) {
            problems.add("an effective date is required");
        }
        if (a.getEffectiveDate() != null && a.getExpiresAt() != null && !a.getExpiresAt().isAfter(a.getEffectiveDate())) {
            problems.add("the expiry date must be after the effective date");
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException("Cannot submit: " + String.join("; ", problems));

        OwnedFile file = a.getRecordMode().includesFile() ? agreementFiles.currentVersion(a.getDocumentId()) : null;
        AgreementSnapshot snapshot = snapshotOf(a, parties);
        String contentSha = AgreementContentHasher.contentSha256(snapshot, file == null ? null : file.sha256());
        UUID actor = contextProvider.current().userId();
        Instant now = Instant.now(clock);

        AgreementVersion v = new AgreementVersion(Uuid7.generate(), a.getTenantId(), a.getId(),
                versions.maxVersionNumber(a.getId()) + 1, a.getRecordMode(), actor, now, a.getLastEditedBy(),
                AgreementContentHasher.canonicalJson(snapshot),
                file == null ? null : file.documentVersionId(), file == null ? null : file.sha256(), contentSha);
        versions.saveAndFlush(v);

        a.setStatus(AgreementStatus.UNDER_REVIEW);
        a.setUpdatedAt(now);
        agreements.saveAndFlush(a);

        audit.record(AuditActions.AGREEMENT_SUBMITTED, "onboarding_case", a.getCaseId(),
                "Submitted " + a.getName() + " v" + v.getVersionNumber() + " for review",
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(v.getVersionNumber()),
                       "contentSha256", contentSha));
        return get(a.getId());
    }

    /**
     * Moves an APPROVED agreement to SENT (Task 15; spec 3.4/4.6/5.3). The "sent version"
     * is always the latest one -- no version can be created past DRAFT, and SENT never
     * returns to DRAFT (cancel makes a new agreement) -- so {@code
     * versions.ofAgreementNewestFirst(...).get(0)} needs no version-number argument the
     * way {@code review} does.
     *
     * <p>{@link #providerFor} selects the one {@link SignatureProvider} bean whose {@code
     * kind()} matches the agreement's own {@code signatureProvider} column; no bean
     * matching is an {@link IllegalStateException}, not a silent no-op, since the column's
     * own CHECK constraint is the only thing standing between this and a provider nobody
     * wrote. {@code ManualSignatureProvider} never returns an envelope id, so {@code
     * providerEnvelopeId} stays null for every agreement this sub-project sends.
     *
     * <p>A FILE_BACKED/MIXED agreement's owned document is retiered COMPANY_SHARED here --
     * the one moment {@code AgreementFiles}'s own javadoc names as the SENSITIVE-to-shared
     * transition. STRUCTURED_ONLY has no document at all ({@code documentId == null}), so
     * this step is skipped, not a no-op retier call.
     */
    @RequirePermission(PermissionKeys.AGREEMENT_MANAGE)
    @Transactional
    public AgreementDetailView send(UUID id, long lockVersion) {
        Agreement a = writes.loadForWrite(id, PermissionKeys.AGREEMENT_MANAGE, lockVersion,
                EnumSet.of(AgreementStatus.APPROVED));
        AgreementVersion sent = versions.ofAgreementNewestFirst(a.getId()).get(0);
        providerFor(a).send(a, sent).ifPresent(a::setProviderEnvelopeId);
        if (a.getDocumentId() != null) agreementFiles.retier(a.getDocumentId(), VisibilityTier.COMPANY_SHARED);
        a.setStatus(AgreementStatus.SENT);
        a.setUpdatedAt(Instant.now(clock));
        agreements.saveAndFlush(a);
        audit.record(AuditActions.AGREEMENT_SENT, "onboarding_case", a.getCaseId(),
                "Sent " + a.getName() + " v" + sent.getVersionNumber() + " for signature",
                Map.of("agreementId", a.getId().toString(), "versionNumber", Integer.toString(sent.getVersionNumber())));
        return get(a.getId());
    }

    private SignatureProvider providerFor(Agreement a) {
        return signatureProviders.stream()
                .filter(p -> p.kind() == a.getSignatureProvider())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No SignatureProvider registered for " + a.getSignatureProvider()));
    }

    private static AgreementSnapshot snapshotOf(Agreement a, List<AgreementSignatory> parties) {
        return new AgreementSnapshot(a.getName(), a.getRecordMode(), a.getEffectiveDate(), a.getExpiresAt(),
                a.getRenewalDate(), a.getNoticePeriodDays(),
                parties.stream().map(s -> new AgreementSnapshot.SignatorySnapshot(s.getId(), s.getKind(),
                        s.getContactId(), s.getUserId(), s.getDisplayRole(), s.getSortOrder())).toList());
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
