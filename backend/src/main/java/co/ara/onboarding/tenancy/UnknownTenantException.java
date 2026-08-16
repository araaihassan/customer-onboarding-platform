package co.ara.onboarding.tenancy;

public class UnknownTenantException extends RuntimeException {
    public UnknownTenantException(String slug) {
        super("Unknown or inactive tenant: " + slug);
    }
}
