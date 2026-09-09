package co.ara.onboarding.programme;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Create, read, update and deactivate a programme (QA Q20) -- a container
 * grouping a customer's parallel journeys, with no lifecycle of its own: no
 * hold, no approval, no engine (see {@link Programme}'s own javadoc).
 *
 * Every read goes through {@link AuthorizedQuery}, resolved by
 * {@code scoping.ProgrammeDescriptor}. Every id taken from a URL or a request
 * body -- here, {@code customerId} on create -- is resolved through
 * {@link AuthorizedQuery} before it is written, the write-path obligation
 * design spec §6.5 names and the shape that bit sub-project 1 three times
 * (contact creation, role assignment, invitation issuance).
 *
 * <b>Why this reads {@code customer.Customer} directly, not
 * {@code journey.CustomerDirectory}:</b> the established idiom for a module
 * reaching another one's data without owning its entities is a facts port
 * declared by the consumer and implemented by the provider --
 * {@code journey.CustomerDirectory}/{@code customer.JourneyCustomerDirectory}
 * is the precedent, and {@code ModuleBoundaryTest.noCustomerDependencyOnProgramme}'s
 * own comment says programme is meant to reach customer "through a facts port,
 * the CustomerDirectory inversion". In practice that means REUSING
 * {@code journey.CustomerDirectory} (a fresh {@code programme.CustomerDirectory}
 * would need {@code customer} to implement an interface declared inside
 * {@code programme}, which {@code noCustomerDependencyOnProgramme} forbids
 * outright) -- but {@code journey.CustomerFacts} deliberately carries no display
 * name ({@code CustomerDirectory}'s own javadoc: "Deliberately no display data
 * ... keeps the port from growing into a second customer API"), and this
 * service's own {@code ProgrammeView} needs {@code customerName}. Extending
 * {@code CustomerFacts} to add one ripples into {@code workflow.CustomerFactKeys}'s
 * condition-key catalog and {@code journey.CustomerDirectoryTest}'s cross-check
 * that the two stay in agreement -- neither owned by this task, and widening a
 * branch-condition key catalog for a field no condition should ever compare
 * against is the wrong fix, not merely an out-of-scope one. Reading
 * {@code Customer} directly through {@link AuthorizedQuery} under
 * {@code customer.view} gives the identical security guarantee -- an
 * out-of-scope or foreign-tenant id collapses to empty, mapped to 404, exactly
 * as {@code JourneyCustomerDirectory.findVisible} does -- without either
 * consequence. No {@code ModuleBoundaryTest} rule forbids a plain
 * {@code programme -> customer} dependency (only the reverse, and only
 * {@code journey -> customer}, are guarded); this is a plan deviation worth a
 * maintainer's attention, not a silent one -- see this task's own report.
 */
@Service
public class ProgrammeService {

    private final ProgrammeRepository programmes;
    private final CustomerRepository customers;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;

    public ProgrammeService(ProgrammeRepository programmes, CustomerRepository customers,
                            AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                            AuditRecorder audit) {
        this.programmes = programmes;
        this.customers = customers;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
    }

    /**
     * {@code programme.create} is ALL-only in the catalog (PermissionCatalog) --
     * safe precisely because the customer is still resolved through
     * {@code customer.view} below, the same reasoning {@code CaseService.create}'s
     * own doc comment gives for {@code case.create}: authority to create is
     * bounded by which customers the caller can see, not by the wide create grant
     * alone.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_CREATE)
    @Transactional
    public ProgrammeView create(CreateProgrammeRequest request) {
        Customer customer = authorizedQuery.getById(
                customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, request.customerId());

        Programme p = new Programme();
        p.setId(Uuid7.generate());
        p.setTenantId(TenantContext.getRequired());
        p.setCustomerId(customer.getId());
        p.setName(request.name());
        p.setDescription(request.description());
        p.setOwnerUserId(request.ownerUserId());
        p.setOwningDepartmentId(request.owningDepartmentId());
        p.setOwningTeamId(request.owningTeamId());
        p.setStatus(ProgrammeStatus.ACTIVE);
        p.setCreatedBy(contextProvider.principal().userId());
        p = programmes.save(p);

        audit.record(AuditActions.PROGRAMME_CREATED, "programme", p.getId(),
                "Created programme " + p.getName(), Map.of("customerId", customer.getId().toString()));

        return toView(p, customer);
    }

    /**
     * Reading a programme's FULL view is gated by more than {@code programme.view}:
     * {@code toView}/{@code customerOf} resolves {@code customerName} through
     * {@code customer.view}, so a caller holding {@code programme.view} but no
     * {@code customer.view} grant reaching this programme's own customer gets a
     * 404 on the WHOLE programme, not a blank name field -- the identical shape
     * CLAUDE.md records for {@code CaseService}'s {@code currentStageName}/
     * {@code workflow.view} dependency ("a role holding case.view but not
     * workflow.view gets a 404 on the whole case read"). Currently latent, not
     * exercised in production: {@code programme.view} is seeded to Administrator
     * only (Task 11's `RoleTemplateCoverageTest` exception, held open for Phase 2
     * of this plan), and Administrator holds {@code customer.view} at ALL too --
     * but the moment a narrower role (a Sponsor-shaped template, say) is granted
     * {@code programme.view} without an accompanying {@code customer.view}, this
     * dependency bites for real. A hand-built test role granting only
     * {@code programme.view} must declare {@code customer.view} explicitly too,
     * same as a real Project-Manager-shaped role bundles {@code case.view} and
     * {@code workflow.view} together deliberately.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_VIEW)
    @Transactional(readOnly = true)
    public ProgrammeView get(UUID programmeId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_VIEW, programmeId);
        return toView(p, customerOf(p));
    }

    /**
     * Fetched with {@code programme.manage}, not {@code programme.view} --
     * fetching under the read permission and then writing is the privilege
     * escalation {@code CustomerService.update}'s own comment already names.
     * customerId is never accepted here: a programme's customer is fixed at
     * creation (UpdateProgrammeRequest carries no such field), so there is
     * nothing to re-resolve through the customer port on this path.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public ProgrammeView update(UUID programmeId, UpdateProgrammeRequest request) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);

        p.setName(request.name());
        p.setDescription(request.description());
        p.setOwnerUserId(request.ownerUserId());
        p.setOwningDepartmentId(request.owningDepartmentId());
        p.setOwningTeamId(request.owningTeamId());
        p = programmes.save(p);

        audit.record(AuditActions.PROGRAMME_UPDATED, "programme", p.getId(), "Updated programme", Map.of());

        return toView(p, customerOf(p));
    }

    /**
     * Sets {@code status = INACTIVE} and does nothing else. The revocation this
     * causes is structural, not a cleanup step here: {@code scoping.
     * ProgrammeDescriptor.assignedScope} requires the programme itself to be
     * ACTIVE (fixed alongside this task -- see that class's own doc comment and
     * this task's report for why it did not, before this change, exclude an
     * inactive programme at all), so a participant's read collapses to nothing
     * on the very next request once this one column changes, the same shape
     * {@code AuthorizationService}'s {@code status = 'ACTIVE'} join gives a
     * deactivated user. DEPARTMENT/TEAM/ALL-scoped holders are deliberately
     * unaffected -- see ProgrammeDescriptor's own comment for why that is a
     * choice, not an oversight.
     */
    @RequirePermission(PermissionKeys.PROGRAMME_MANAGE)
    @Transactional
    public void deactivate(UUID programmeId) {
        Programme p = authorizedQuery.getById(programmes, Programme.class, PermissionKeys.PROGRAMME_MANAGE, programmeId);
        p.setStatus(ProgrammeStatus.INACTIVE);
        programmes.save(p);

        audit.record(AuditActions.PROGRAMME_DEACTIVATED, "programme", p.getId(),
                "Deactivated programme " + p.getName(), Map.of());
    }

    /**
     * Reads the owning customer under {@code customer.view}, exactly as {@link #create}
     * does -- never a raw repository finder, and never the customer id trusted
     * blind just because it is already sitting on the programme row: a programme
     * whose customer later falls outside the caller's own scope must not leak a
     * name through a stale reference either.
     */
    private Customer customerOf(Programme p) {
        return authorizedQuery.getById(customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, p.getCustomerId());
    }

    private ProgrammeView toView(Programme p, Customer customer) {
        return new ProgrammeView(p.getId(), p.getName(), p.getCustomerId(), customer.getDisplayName(),
                p.getDescription(), p.getOwnerUserId(), p.getOwningDepartmentId(), p.getOwningTeamId(),
                p.getStatus());
    }
}
