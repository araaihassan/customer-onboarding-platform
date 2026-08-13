package co.ara.onboarding.customer;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final AuthorizedQuery authorizedQuery;
    private final ContactInvitationSender invitations;

    public CustomerContactService(CustomerContactRepository repository,
                                  AuthorizedQuery authorizedQuery,
                                  ContactInvitationSender invitations) {
        this.repository = repository;
        this.authorizedQuery = authorizedQuery;
        this.invitations = invitations;
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

    @RequirePermission(PermissionKeys.CONTACT_MANAGE)
    @Transactional
    public ContactView create(UUID customerId, CreateContactRequest request) {
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
        return toView(repository.save(c));
    }

    @RequirePermission(PermissionKeys.CONTACT_MANAGE)
    @Transactional
    public ContactView update(UUID contactId, UpdateContactRequest request) {
        CustomerContact c = authorizedQuery.getById(repository, CustomerContact.class,
                PermissionKeys.CONTACT_MANAGE, contactId);
        c.setFullName(request.fullName());
        c.setEmail(request.email());
        c.setTitle(request.title());
        c.setPhone(request.phone());
        c.setPrimaryContact(request.primaryContact());
        c.setStatus(request.status());
        return toView(repository.save(c));
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
