package co.ara.onboarding.security;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.document.DocumentShareRepository;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.scoping.DocumentAudienceFilter;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentAudienceFilter}'s portal branch (design spec 6.3/6.4, QA Q9) --
 * the sub-project's own words for this task: "these are this sub-project's most
 * important negatives. Every one is a cross-company or cross-contact leak if it
 * regresses."
 *
 * Every positive test grants nothing explicitly -- a PORTAL actor resolves
 * document.view at ALL scope automatically ({@code PortalPermissions.forContact()},
 * proven by {@code PortalAuthorityTest}), narrowed entirely by this filter. So
 * every test here is exercising the audience predicate itself, never a scope grant.
 */
class PortalVisibilityTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentRepository documents;
    @Autowired DocumentShareRepository documentShares;
    @Autowired CustomerContactRepository contacts;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired DocumentAudienceFilter filter;

    @Test
    void contactACannotReadContactBsContactOnlyDocumentAtTheSameCustomer() {
        UUID tenant = fixture.createTenant("portal-vis-contact-only");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactAUserId = fixture.createPortalUserForContact(tenant, customerId, "a@portal-vis-contact-only.example");
        UUID contactBUserId = fixture.createPortalUserForContact(tenant, customerId, "b@portal-vis-contact-only.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID contactAId = contacts.findByUserId(contactAUserId).orElseThrow().getId();
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, contactAUserId, customerId,
                    VisibilityTier.CONTACT_ONLY, contactAId, null));
        });

        fixture.runAsUser(tenant, contactBUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("contact B must not read contact A's CONTACT_ONLY document, even at the same customer")
                        .isEmpty());

        // Sanity: contact A themself CAN read it -- otherwise "B can't either" is vacuous.
        fixture.runAsUser(tenant, contactAUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("the owning contact themself must still read their own CONTACT_ONLY document")
                        .extracting(Document::getId).containsExactly(docRef.get()));
    }

    @Test
    void aContactCannotReadAnyDocumentOfAnotherCustomer() {
        UUID tenant = fixture.createTenant("portal-vis-cross-customer");
        UUID customerA = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID customerB = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Globex", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerA, "reader@portal-vis-cross-customer.example");

        fixture.runAs(tenant, () -> {
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-cross-customer.example");
            Case c = journey.newCase(tenant);
            // COMPANY_SHARED and untargeted -- the most permissive shape possible,
            // proving the customer gate refuses it regardless.
            newDocument(tenant, c, uploader, customerB, VisibilityTier.COMPANY_SHARED, null, null);
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("a contact of customer A must not read customer B's COMPANY_SHARED document")
                        .isEmpty());
    }

    @Test
    void aContactReadsACompanySharedDocumentAtTheirOwnCustomer() {
        UUID tenant = fixture.createTenant("portal-vis-company-shared");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@portal-vis-company-shared.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-company-shared.example");
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.COMPANY_SHARED, null, null));
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("COMPANY_SHARED at the contact's own customer, untargeted, must be visible")
                        .extracting(Document::getId).containsExactly(docRef.get()));
    }

    @Test
    void aSensitiveDocumentReachesNoContactByTier() {
        UUID tenant = fixture.createTenant("portal-vis-sensitive-tier");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@portal-vis-sensitive-tier.example");

        fixture.runAs(tenant, () -> {
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-sensitive-tier.example");
            Case c = journey.newCase(tenant);
            // Own customer, untargeted (no label) -- every gate but tier is as
            // permissive as possible, so only SENSITIVE's own exclusion from
            // byTier can be responsible for refusing this.
            newDocument(tenant, c, uploader, customerId, VisibilityTier.SENSITIVE, null, null);
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("SENSITIVE reaches nobody by tier, even at the contact's own customer with no label targeting")
                        .isEmpty());
    }

    @Test
    void anExplicitShareMakesASensitiveDocumentVisibleToThatContactOnly() {
        UUID tenant = fixture.createTenant("portal-vis-sensitive-share");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID sharedContactUserId = fixture.createPortalUserForContact(tenant, customerId, "shared@portal-vis-sensitive-share.example");
        UUID otherContactUserId = fixture.createPortalUserForContact(tenant, customerId, "other@portal-vis-sensitive-share.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID sharedContactId = contacts.findByUserId(sharedContactUserId).orElseThrow().getId();
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-sensitive-share.example");
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.SENSITIVE, null, null));

            documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, docRef.get(),
                    SharePrincipalType.CONTACT, sharedContactId, uploader, Instant.now()));
        });

        fixture.runAsUser(tenant, sharedContactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("an explicit CONTACT share overrides SENSITIVE's own tier exclusion, for the shared contact")
                        .extracting(Document::getId).containsExactly(docRef.get()));

        fixture.runAsUser(tenant, otherContactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("...but ONLY that contact -- a different contact at the same customer, not named "
                                + "in the share, must still be refused")
                        .isEmpty());
    }

    @Test
    void aFinanceLabelledContactCannotReadALegalLabelledDocument() {
        UUID tenant = fixture.createTenant("portal-vis-label-mismatch");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "finance@portal-vis-label-mismatch.example");

        fixture.runAs(tenant, () -> {
            var contact = contacts.findByUserId(contactUserId).orElseThrow();
            contact.setLabel("Finance");
            contacts.saveAndFlush(contact);

            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-label-mismatch.example");
            Case c = journey.newCase(tenant);
            // COMPANY_SHARED so byTier passes cleanly -- only the label mismatch
            // is under test here.
            newDocument(tenant, c, uploader, customerId, VisibilityTier.COMPANY_SHARED, null, "Legal");
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("a Finance-labelled contact must not read a Legal-targeted document, "
                                + "even though it is COMPANY_SHARED at their own customer")
                        .isEmpty());
    }

    @Test
    void anUnlabelledTargetIsVisibleToEveryContactAtTheCustomer() {
        UUID tenant = fixture.createTenant("portal-vis-unlabelled-target");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "finance@portal-vis-unlabelled-target.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            // The contact DOES carry a label -- proving the untargeted document's
            // visibility doesn't depend on the contact having none of their own.
            var contact = contacts.findByUserId(contactUserId).orElseThrow();
            contact.setLabel("Finance");
            contacts.saveAndFlush(contact);

            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-unlabelled-target.example");
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.COMPANY_SHARED, null, null));
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("an untargeted (null targetContactLabel) document is visible to every contact at "
                                + "the customer, regardless of that contact's own label")
                        .extracting(Document::getId).containsExactly(docRef.get()));
    }

    @Test
    void aRetiredContactReadsNothing() {
        UUID tenant = fixture.createTenant("portal-vis-retired");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "retired@portal-vis-retired.example");

        var docRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-retired.example");
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.COMPANY_SHARED, null, null));
        });

        // Sanity: visible BEFORE retirement -- otherwise "reads nothing after
        // retirement" could just mean this document was never reachable at all.
        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("sanity check: visible before retirement")
                        .extracting(Document::getId).containsExactly(docRef.get()));

        fixture.retireContactFor(tenant, contactUserId);

        // Through the full stack, permission resolution already fails closed
        // before this filter is ever reached (PortalAuthorityTest.aRetiredContactResolvesNothing:
        // effectivePermissions() resolves zero scopes for a retired contact, and
        // AuthorizationPredicateBuilder.forPermission returns cb.disjunction()
        // BEFORE the audience lookup even runs). Prove the filter is
        // independently fail-closed too -- not merely unreachable -- by invoking
        // it directly against a PORTAL context for the now-retired contact.
        AuthContext ctx = new AuthContext(tenant, contactUserId, UserType.PORTAL, null, Set.of());
        var spec = filter.audience(ctx, PermissionKeys.DOCUMENT_VIEW);

        fixture.runAs(tenant, () ->
                assertThat(documents.findAll(spec))
                        .as("the audience filter itself must fail closed for a retired contact, "
                                + "independent of the permission-resolution layer that already refuses upstream")
                        .isEmpty());

        // And through the full stack too, for completeness.
        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("through the full stack, a retired contact resolves no permission at all")
                        .isEmpty());
    }

    /**
     * The single most important test the eight above were missing (the same gap
     * DocumentAudienceTest's own correlation test closed for the internal branch).
     * sharedWith's EXISTS subquery correlates on {@code share.documentId = root.id};
     * nothing above proves that correlation holds for a CONTACT principal. An
     * uncorrelated subquery -- "any live CONTACT share to this principal, on ANY
     * document" -- would pass every test above just as well, while actually
     * leaking every OTHER SENSITIVE document at the customer to a contact shared
     * on just one of them.
     */
    @Test
    void aShareToOneContactDoesNotLeakAnUnsharedDocumentToThatSameContact() {
        UUID tenant = fixture.createTenant("portal-vis-share-correlation");
        UUID customerId = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenant, customerId, "reader@portal-vis-share-correlation.example");

        var sharedDocRef = new AtomicReference<UUID>();
        var unsharedDocRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID contactId = contacts.findByUserId(contactUserId).orElseThrow().getId();
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-share-correlation.example");
            Case c = journey.newCase(tenant);
            sharedDocRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.SENSITIVE, null, null));
            unsharedDocRef.set(newDocument(tenant, c, uploader, customerId, VisibilityTier.SENSITIVE, null, null));

            // Shares only the FIRST SENSITIVE document -- the second is never
            // shared at all, so it must stay unreachable by tier alone.
            documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, sharedDocRef.get(),
                    SharePrincipalType.CONTACT, contactId, uploader, Instant.now()));
        });

        fixture.runAsUser(tenant, contactUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("a CONTACT share on ONE SENSITIVE document must never widen a different, unshared "
                                + "SENSITIVE document at the same customer -- an uncorrelated EXISTS would leak it")
                        .extracting(Document::getId).containsExactly(sharedDocRef.get()));
    }

    /**
     * Proves the customer gate operates independently of tier, not merely as a
     * side effect of a tier mismatch: two COMPANY_SHARED, untargeted documents --
     * the single most permissive shape -- one per customer, and each contact
     * still sees only their own customer's document.
     */
    @Test
    void twoCompanySharedDocumentsAtDifferentCustomersIsolateByCustomerBeforeTierIsEverConsidered() {
        UUID tenant = fixture.createTenant("portal-vis-customer-isolation");
        UUID customerA = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Acme", null, null, null));
        UUID customerB = fixture.runAsReturning(tenant,
                () -> fixture.createCustomer(tenant, "Globex", null, null, null));
        UUID contactAUserId = fixture.createPortalUserForContact(tenant, customerA, "a@portal-vis-customer-isolation.example");
        UUID contactBUserId = fixture.createPortalUserForContact(tenant, customerB, "b@portal-vis-customer-isolation.example");

        var docARef = new AtomicReference<UUID>();
        var docBRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID uploader = fixture.createUser(tenant, "uploader@portal-vis-customer-isolation.example");
            Case cA = journey.newCase(tenant);
            Case cB = journey.newCase(tenant);
            docARef.set(newDocument(tenant, cA, uploader, customerA, VisibilityTier.COMPANY_SHARED, null, null));
            docBRef.set(newDocument(tenant, cB, uploader, customerB, VisibilityTier.COMPANY_SHARED, null, null));
        });

        fixture.runAsUser(tenant, contactAUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("customer A's contact sees only customer A's document, not customer B's -- "
                                + "same tier, same targeting, different customer")
                        .extracting(Document::getId).containsExactly(docARef.get()));

        fixture.runAsUser(tenant, contactBUserId, () ->
                assertThat(authorizedQuery.findAll(documents, Document.class,
                        PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                        .as("and vice versa")
                        .extracting(Document::getId).containsExactly(docBRef.get()));
    }

    private UUID newDocument(UUID tenant, Case c, UUID uploadedBy, UUID customerId, VisibilityTier tier,
                             UUID ownerContactId, String targetContactLabel) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(customerId);
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(tier);
        d.setOwnerContactId(ownerContactId);
        d.setTargetContactLabel(targetContactLabel);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documents.saveAndFlush(d).getId();
    }
}
