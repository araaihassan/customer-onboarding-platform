package co.ara.onboarding.authz;

/**
 * A grant named a permission the catalog does not define, or a scope that
 * permission does not allow. Mapped to 400 by AuthzExceptionHandler: this is a
 * malformed request, not an authorization failure, so it must not be confused
 * with 403.
 *
 * The message names the offending key and scope because the catalog is public
 * knowledge -- it leaks nothing about the tenant's data.
 */
public class InvalidGrantException extends RuntimeException {

    public InvalidGrantException(String permissionKey, Scope scope) {
        super("Permission '" + permissionKey + "' does not allow scope " + scope);
    }

    public InvalidGrantException(String permissionKey) {
        super("Unknown permission '" + permissionKey + "'");
    }
}
