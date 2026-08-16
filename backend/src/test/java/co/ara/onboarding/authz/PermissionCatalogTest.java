package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void catalogIsMirroredToDatabaseAtStartup() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM permission", Integer.class);
        assertThat(rows).isEqualTo(PermissionCatalog.all().size());
    }

    /**
     * The count assertion above would pass over sixteen rows of garbage. This
     * checks what the sync actually wrote: a null resource_type for an ALL-only
     * permission, a populated one for a record-scoped permission, and the
     * comma-joined scope encoding in the sorted order PermissionSyncRunner
     * promises -- unsorted output would still satisfy a count check while
     * rewriting the column on every restart.
     */
    @Test
    void syncWritesEachColumnNotJustTheRowCount() {
        var roleManage = jdbc.queryForMap(
                "SELECT category, resource_type, allowed_scopes FROM permission WHERE key = ?",
                PermissionKeys.ROLE_MANAGE);
        assertThat(roleManage).containsEntry("category", "authz")
                              .containsEntry("resource_type", null)
                              .containsEntry("allowed_scopes", "ALL");

        var customerView = jdbc.queryForMap(
                "SELECT category, resource_type, allowed_scopes FROM permission WHERE key = ?",
                PermissionKeys.CUSTOMER_VIEW);
        assertThat(customerView).containsEntry("category", "customer")
                                .containsEntry("resource_type", "customer")
                                .containsEntry("allowed_scopes", "ALL,ASSIGNED,DEPARTMENT,TEAM");
    }

    @Test
    void allOnlyPermissionsRejectNarrowerScopes() {
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.ALL)).isTrue();
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.TEAM)).isFalse();
        assertThat(PermissionCatalog.allows(PermissionKeys.ROLE_MANAGE, Scope.ASSIGNED)).isFalse();
    }

    @Test
    void recordScopedPermissionsAcceptAllFourScopes() {
        for (Scope s : Scope.values()) {
            assertThat(PermissionCatalog.allows(PermissionKeys.CUSTOMER_VIEW, s))
                    .as("customer.view should allow %s", s).isTrue();
        }
    }

    @Test
    void unknownPermissionKeyIsRejected() {
        assertThat(PermissionCatalog.byKey("made.up.permission")).isEmpty();
        assertThat(PermissionCatalog.allows("made.up.permission", Scope.ALL)).isFalse();
    }
}
