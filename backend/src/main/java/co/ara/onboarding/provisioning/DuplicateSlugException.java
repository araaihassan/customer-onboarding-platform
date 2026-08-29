package co.ara.onboarding.provisioning;

/**
 * Two tenants cannot share a slug -- {@code UNIQUE} on {@code tenant.slug} (V2,
 * case-sensitive, Postgres-generated name {@code tenant_slug_key}).
 *
 * This exists because that collision is a foreseeable USER error rather than a
 * programming one: an operator retrying a provisioning call, or two people
 * choosing the same short name for two different tenants. Left untranslated it
 * surfaced as a bare 500 -- {@link TenantProvisioningService#provision} calls
 * {@code tenants.save(tenant)} as its very first statement, but {@code BaseEntity}
 * assigns its own id with no {@code @Version} and no {@code Persistable}, so
 * Spring Data's {@code isNew()} check routes an already-ID-assigned entity
 * through {@code entityManager.merge()} rather than {@code persist()} --
 * Hibernate does not flush a merge immediately, so the constraint violation
 * actually surfaces later, at the next flush point inside the same transaction,
 * not at the {@code tenants.save} call site itself.
 *
 * Mapped to 409 by ProvisioningExceptionHandler -- in this module, never in
 * platform, which must not name a domain type (ModuleBoundaryTest).
 *
 * The detail deliberately does NOT echo the slug back. The caller supplied it,
 * so repeating it adds nothing, and a message assembled from tenant data is the
 * habit that eventually leaks some.
 */
public class DuplicateSlugException extends RuntimeException {

    public DuplicateSlugException(Throwable cause) {
        super("A tenant with this slug already exists", cause);
    }
}
