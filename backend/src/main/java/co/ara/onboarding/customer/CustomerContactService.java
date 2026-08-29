package co.ara.onboarding.customer;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class CustomerContactService {

    public record CreateContactRequest(String fullName, String email, String title,
                                       String phone, boolean primaryContact) {}

    public record UpdateContactRequest(String fullName, String email, String title,
                                       String phone, boolean primaryContact,
                                       ContactStatus status) {}

    public record ContactView(UUID id, UUID customerId, UUID userId, String fullName,
                              String email, String title, String phone,
                              boolean primaryContact, ContactStatus status) {}

    private final CustomerContactRepository repository;
    private final CustomerRepository customers;
    private final AuthorizedQuery authorizedQuery;
    private final ContactInvitationSender invitations;
    private final AppUserRepository users;
    private final AuditRecorder audit;

    public CustomerContactService(CustomerContactRepository repository,
                                  CustomerRepository customers,
                                  AuthorizedQuery authorizedQuery,
                                  ContactInvitationSender invitations,
                                  AppUserRepository users,
                                  AuditRecorder audit) {
        this.repository = repository;
        this.customers = customers;
        this.authorizedQuery = authorizedQuery;
        this.invitations = invitations;
        this.users = users;
        this.audit = audit;
    }

    /**
     * A customerId Specification through AuthorizedQuery, NOT
     * repository.findByCustomerId. A derived finder carries no scope predicate, so
     * it would return every contact of that customer regardless of whether the
     * caller may see it — a silent, total bypass. This is the exact case
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly names.
     *
     * Contacts carry no owner of their own; CustomerContactDescriptor resolves their
     * scope through the parent customer.
     */
    @RequirePermission(PermissionKeys.CONTACT_VIEW)
    @Transactional(readOnly = true)
    public List<ContactView> list(UUID customerId) {
        Specification<CustomerContact> ofCustomer =
                (root, query, cb) -> cb.equal(root.get("customerId"), customerId);

        return authorizedQuery.findAll(repository, CustomerContact.class,
                        PermissionKeys.CONTACT_VIEW, ofCustomer, Pageable.unpaged())
                .map(this::toView)
                .getContent();
    }

    /**
     * The parent is resolved through AuthorizedQuery BEFORE the write, for the
     * same reason list() applies a scope predicate rather than a derived finder.
     *
     * The permission gate is coarse by design — PermissionGateAspect answers
     * only "does this user hold the permission at ANY scope" — and a create has
     * no record of its own to scope, so the customerId arriving from the URL is
     * the only thing left to check. Without this a contact.manage holder scoped to
     * DEPARTMENT, TEAM or ASSIGNED could attach a contact to any customer in the
     * tenant, including ones invisible to them. That was quiet rather than
     * harmless: every read path resolves a contact's scope through its parent, so
     * they could never see or invite what they had written, but the row was
     * there.
     *
     * CONTACT_MANAGE against Customer.class is the coherent pairing, not a
     * mismatch: AuthorizationPredicateBuilder takes the SCOPES from the
     * permission key and the predicate SHAPE from the entity's descriptor, so
     * this asks exactly "which customers does this actor's contact.manage
     * reach". It needs no new permission, and it keeps create and the read paths
     * agreeing — you can only create under a parent you would then be able to see
     * the result under.
     *
     * The returned customer is deliberately unused: this is a reachability
     * check, and getById throws NoSuchElementException — 404, never 403 — when
     * the parent is out of scope or absent, which are the same answer by design.
     */
    @RequirePermission(PermissionKeys.CONTACT_MANAGE)
    @Transactional
    public ContactView create(UUID customerId, CreateContactRequest request) {
        authorizedQuery.getById(customers, Customer.class, PermissionKeys.CONTACT_MANAGE, customerId);

        CustomerContact c = new CustomerContact();
        c.setId(Uuid7.generate());
        c.setTenantId(TenantContext.getRequired());
        c.setCustomerId(customerId);
        c.setFullName(request.fullName());
        c.setEmail(request.email());
        c.setTitle(request.title());
        c.setPhone(request.phone());
        c.setPrimaryContact(request.primaryContact());
        // A contact starts ACTIVE with no linked user; userId is set only when the
        // portal invitation is accepted (spec 9.1, QA Q12).
        c.setStatus(ContactStatus.ACTIVE);
        CustomerContact saved = save(c);

        // After save(), not before: save() is where the duplicate-email constraint
        // surfaces, and an audit event for a create that then threw would be a
        // record of something that never happened. The recorder is MANDATORY on
        // this transaction, so the two commit together or not at all.
        audit.record(AuditActions.CONTACT_CREATED, "contact", saved.getId(),
                "Added contact " + saved.getFullName(),
                Map.of("customerId", customerId.toString(), "email", saved.getEmail()));
        return toView(saved);
    }

    /**
     * No parent SCOPE lookup here, deliberately, and NOT an omission of the one
     * create carries. (The equality check below is a different thing: it verifies
     * the path is self-consistent, not that the actor may reach the parent.)
     *
     * update reads the contact itself through AuthorizedQuery, and
     * CustomerContactDescriptor resolves every contact scope through a subquery
     * over its parent customer — so an unreachable parent already makes the
     * contact unreachable, and getById already answers 404. UpdateContactRequest
     * carries no customerId either, so a contact cannot be moved under a
     * different parent. Adding a second lookup would issue an extra query per
     * edit and imply the contact-level check were insufficient.
     *
     * Proven rather than asserted:
     * CustomerContactServiceTest.updatingAContactUnderAnOutOfScopeCustomerIsNotFound.
     */
    @RequirePermission(PermissionKeys.CONTACT_MANAGE)
    @Transactional
    public ContactView update(UUID customerId, UUID contactId, UpdateContactRequest request) {
        CustomerContact c = authorizedQuery.getById(repository, CustomerContact.class,
                PermissionKeys.CONTACT_MANAGE, contactId);

        // The customerId in the URL means what it says. Without this any customer
        // id at all addressed the same contact, which is not a scope bypass —
        // getById above binds regardless of what the path claims — but a path
        // variable nothing verifies is one a later caller will assume means
        // something, and the frontend already keys its cache invalidation off
        // exactly this segment. NoSuchElementException, so a contact that is not
        // under the customer named is simply absent from that URL.
        if (!customerId.equals(c.getCustomerId())) {
            throw new NoSuchElementException("Not found");
        }

        // Captured BEFORE the setters: the entity is managed, so after the setters
        // run the previous values are gone and both transitions are unrecoverable.
        ContactStatus previousStatus = c.getStatus();
        String previousEmail = c.getEmail();

        c.setFullName(request.fullName());
        c.setEmail(request.email());
        c.setTitle(request.title());
        c.setPhone(request.phone());
        c.setPrimaryContact(request.primaryContact());
        c.setStatus(request.status());
        CustomerContact saved = save(c);

        // A retirement is recorded as its own action, not as an ordinary edit.
        // There is no separate deactivate endpoint for a contact the way there is
        // for a customer — retirement arrives through this PUT — but the reason
        // customer.deactivated exists applies unchanged: business records are never
        // deleted, so INACTIVE is the only retirement a contact has and this event
        // is the only record that it happened.
        //
        // A TRANSITION, not the resulting status: editing an already-retired
        // contact is an ordinary update, and logging a second retirement would
        // claim an event that did not occur. Exactly one event either way, so a
        // single click never appears twice on the timeline.
        boolean retired = previousStatus != ContactStatus.INACTIVE
                && saved.getStatus() == ContactStatus.INACTIVE;

        // The portal login is app_user, not customer_contact -- LoginService and
        // ActivationService's duplicate-address check both read app_user.email, so
        // a corrected address that never reaches it leaves the account reachable
        // only under the address the contact no longer uses. LinkedPortalUserEmailSync
        // is the finder-and-update helper, not AppUserRepository directly here,
        // because CustomerContactService ends in "Service" and
        // AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly
        // covers customer.. — the same reason OrgUnitResolver exists. The id fed to
        // it, saved.getUserId(), was never a fresh caller-supplied value: it comes
        // off a CustomerContact already resolved through AuthorizedQuery under
        // CONTACT_MANAGE above, so no further scope predicate applies.
        if (!previousEmail.equals(saved.getEmail())) {
            new LinkedPortalUserEmailSync(users).syncEmail(saved.getUserId(), saved.getEmail());
        }

        if (retired) {
            // Ends the linked portal account so a retired contact cannot still sign
            // in (LoginService admits only ACTIVE), same helper and same reasoning
            // as the email sync above.
            new LinkedPortalUserEmailSync(users).deactivate(saved.getUserId());

            // And a retired contact must not still be ACTIVATABLE either: an
            // outstanding invitation is a live credential nobody has redeemed yet.
            // Delegated through the ContactInvitationSender port rather than
            // touching InvitationRepository here, for the same module-cycle reason
            // sendInvitation already delegates below — auth already depends on
            // customer, so the reverse import would close a cycle. Not re-gated
            // here either: the implementation carries its own @RequirePermission
            // and re-resolves the contact through AuthorizedQuery, exactly as
            // sendInvitation's own comment already states for issue().
            invitations.revokePendingInvitations(saved.getId());
        }

        audit.record(retired ? AuditActions.CONTACT_DEACTIVATED : AuditActions.CONTACT_UPDATED,
                "contact", saved.getId(),
                (retired ? "Retired contact " : "Updated contact ") + saved.getFullName(),
                Map.of("customerId", saved.getCustomerId().toString()));
        return toView(saved);
    }

    /**
     * saveAndFlush, NOT save, and the difference is the whole point.
     *
     * With an assigned identifier Hibernate defers the INSERT to flush, and flush
     * happens at commit -- outside this method, outside any try block here, and
     * outside the transaction proxy that could translate the failure. The
     * constraint violation would then escape as a raw 500 no matter what was
     * caught here. Forcing the write makes the database's answer available at the
     * point where it can still be turned into something the caller can act on.
     */
    private CustomerContact save(CustomerContact c) {
        try {
            return repository.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, CONTACT_EMAIL_UNIQUE)) throw new DuplicateContactEmailException(e);
            // Every other constraint is rethrown untouched. Reporting a foreign
            // key violation as "that address is taken" would send the caller
            // hunting for a duplicate that does not exist.
            throw e;
        }
    }

    /**
     * The explicit name V14 gives {@code CREATE UNIQUE INDEX ... (customer_id,
     * lower(email))} — a functional index has no auto-derived name the way V8's
     * plain {@code UNIQUE (customer_id, email)} constraint did, so this must match
     * whatever the migration actually calls it.
     */
    private static final String CONTACT_EMAIL_UNIQUE = "customer_contact_customer_id_lower_email_idx";

    /**
     * Matched on the constraint name Hibernate reports rather than on the message
     * text, which is Postgres's to reword. CustomerContactServiceTest pins both
     * halves against a real database: the duplicate is translated, and an
     * unrelated violation is not.
     */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Delegates to the ContactInvitationSender port, implemented in auth. Not
     * re-gated here: the implementation carries @RequirePermission(INVITATION_SEND)
     * and reads the contact through AuthorizedQuery, so both the coarse check and
     * the record-level scope are applied there. Annotating this method as well would
     * imply the port's own gate were optional.
     */
    @RequirePermission(PermissionKeys.INVITATION_SEND)
    @Transactional
    public String sendInvitation(UUID contactId) {
        return invitations.issue(contactId);
    }

    private ContactView toView(CustomerContact c) {
        return new ContactView(c.getId(), c.getCustomerId(), c.getUserId(), c.getFullName(),
                c.getEmail(), c.getTitle(), c.getPhone(), c.isPrimaryContact(), c.getStatus());
    }
}
