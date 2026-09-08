package co.ara.onboarding.customer;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerService {

    public record CreateCustomerRequest(String legalName, String displayName, String industry,
                                        String country, String externalRef,
                                        UUID owningDepartmentId, UUID owningTeamId) {}

    public record UpdateCustomerRequest(String legalName, String displayName, String industry,
                                        String country, String externalRef, UUID ownerUserId,
                                        UUID owningDepartmentId, UUID owningTeamId) {}

    /**
     * Carries externalRef, and must keep carrying it. update() below is a full
     * replace, and a full replace is only honest if the read returns everything the
     * write accepts — otherwise any client that loads a record, edits one field and
     * saves erases whatever it was never given. Adding a field to
     * UpdateCustomerRequest without adding it here reintroduces exactly that.
     */
    public record CustomerView(UUID id, String legalName, String displayName,
                               CustomerStatus status, String industry, String country,
                               String externalRef, UUID ownerUserId,
                               UUID owningDepartmentId, UUID owningTeamId) {}

    private final CustomerRepository repository;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final OrgUnitResolver orgUnitResolver;
    private final AppUserRepository users;

    public CustomerService(CustomerRepository repository, AuthorizedQuery authorizedQuery,
                           AuthContextProvider contextProvider, AuditRecorder audit,
                           OrgUnitResolver orgUnitResolver, AppUserRepository users) {
        this.repository = repository;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.orgUnitResolver = orgUnitResolver;
        this.users = users;
    }

    @RequirePermission(PermissionKeys.CUSTOMER_CREATE)
    @Transactional
    public CustomerView create(CreateCustomerRequest request) {
        UUID actor = contextProvider.principal().userId();

        Customer c = new Customer();
        c.setId(Uuid7.generate());
        c.setTenantId(TenantContext.getRequired());
        c.setLegalName(request.legalName());
        c.setDisplayName(request.displayName());
        c.setStatus(CustomerStatus.PROSPECT);
        c.setIndustry(request.industry());
        c.setCountry(request.country());
        c.setExternalRef(request.externalRef());
        // The creator becomes the owner by default, which is what makes ASSIGNED
        // scope useful immediately after creation — otherwise a user holding
        // customer.create plus customer.view at ASSIGNED would create records they
        // instantly cannot see.
        c.setOwnerUserId(actor);
        c.setCreatedBy(actor);
        c.setOwningDepartmentId(orgUnitResolver.resolveDepartment(request.owningDepartmentId()));
        c.setOwningTeamId(orgUnitResolver.resolveTeam(request.owningTeamId()));
        repository.save(c);

        audit.record(AuditActions.CUSTOMER_CREATED, "customer", c.getId(),
                "Created customer " + c.getDisplayName(),
                Map.of("legalName", c.getLegalName()));
        return toView(c);
    }

    /**
     * The caller's filters are passed to AuthorizedQuery as an extra Specification
     * and ANDed with the scope predicate — they narrow the result, never widen it.
     */
    @RequirePermission(PermissionKeys.CUSTOMER_VIEW)
    @Transactional(readOnly = true)
    public Page<CustomerView> list(String search, CustomerStatus status, Pageable pageable) {
        Specification<Customer> filters = (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("displayName")), pattern),
                        cb.like(cb.lower(root.get("legalName")), pattern)));
            }
            if (status != null) predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            return predicate;
        };
        return authorizedQuery.findAll(repository, Customer.class,
                PermissionKeys.CUSTOMER_VIEW, filters, pageable).map(this::toView);
    }

    @RequirePermission(PermissionKeys.CUSTOMER_VIEW)
    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return toView(authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_VIEW, id));
    }

    /**
     * Fetched with CUSTOMER_EDIT, not CUSTOMER_VIEW. Fetching with the read
     * permission and then writing is a privilege escalation: a user who may see a
     * record at ALL scope but edit only what they own would be able to edit anything
     * they can see. Same for deactivate below.
     */
    @RequirePermission(PermissionKeys.CUSTOMER_EDIT)
    @Transactional
    public CustomerView update(UUID id, UpdateCustomerRequest request) {
        Customer c = authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_EDIT, id);
        c.setLegalName(request.legalName());
        c.setDisplayName(request.displayName());
        c.setIndustry(request.industry());
        c.setCountry(request.country());
        c.setExternalRef(request.externalRef());
        // Only resolve owner if the value actually changed. Clients round-trip the
        // read value on every save (load, modify one field, save), and ownership
        // resolution checks permissions that narrow-scoped editors may not hold
        // (USER_VIEW at ASSIGNED scope has no grant at all in Sales Rep, for example).
        // A no-op round-trip must succeed, but a real ownership handoff (including
        // to a cross-tenant id) must go through the security check.
        if (!Objects.equals(request.ownerUserId(), c.getOwnerUserId())) {
            c.setOwnerUserId(resolveOwner(request.ownerUserId()));
        }
        c.setOwningDepartmentId(orgUnitResolver.resolveDepartment(request.owningDepartmentId()));
        c.setOwningTeamId(orgUnitResolver.resolveTeam(request.owningTeamId()));
        repository.save(c);

        audit.record(AuditActions.CUSTOMER_UPDATED, "customer", c.getId(),
                "Updated customer " + c.getDisplayName(), Map.of());
        return toView(c);
    }

    @RequirePermission(PermissionKeys.CUSTOMER_DEACTIVATE)
    @Transactional
    public void deactivate(UUID id, String reason) {
        Customer c = authorizedQuery.getById(repository, Customer.class,
                PermissionKeys.CUSTOMER_DEACTIVATE, id);
        c.setStatus(CustomerStatus.INACTIVE);
        repository.save(c);
        // Never delete: erasure is satisfied by pseudonymization (spec 9.4, QA Q11).
        // This audit event is the only record that the deactivation happened.
        audit.record(AuditActions.CUSTOMER_DEACTIVATED, "customer", c.getId(),
                "Deactivated customer " + c.getDisplayName(),
                Map.of("reason", reason == null ? "" : reason));
    }

    private UUID resolveOwner(UUID ownerUserId) {
        if (ownerUserId == null) return null;
        return authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, ownerUserId).getId();
    }

    private CustomerView toView(Customer c) {
        return new CustomerView(c.getId(), c.getLegalName(), c.getDisplayName(), c.getStatus(),
                c.getIndustry(), c.getCountry(), c.getExternalRef(), c.getOwnerUserId(),
                c.getOwningDepartmentId(), c.getOwningTeamId());
    }
}
