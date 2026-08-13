package co.ara.onboarding.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors the code catalog into the permission table at startup.
 *
 * Runs with no tenant bound, which is correct: permission is not tenant-scoped
 * and carries no RLS policy, so there is nothing for a tenant GUC to filter.
 * TenantTransactionBinder no-ops when TenantContext is empty, so nothing here
 * needs to pretend to be a tenant.
 *
 * The upsert is idempotent, which matters more in tests than in production: each
 * Spring context that boots against the shared Testcontainers database runs this
 * again over rows a previous context already wrote.
 */
@Component
public class PermissionSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionSyncRunner.class);

    private final JdbcTemplate jdbc;

    public PermissionSyncRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        for (Permission p : PermissionCatalog.all()) {
            jdbc.update("""
                INSERT INTO permission (key, category, resource_type, description, allowed_scopes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (key) DO UPDATE
                SET category = EXCLUDED.category,
                    resource_type = EXCLUDED.resource_type,
                    description = EXCLUDED.description,
                    allowed_scopes = EXCLUDED.allowed_scopes
                """, p.key(), p.category(), p.resourceType(), p.description(), encodeScopes(p));
        }

        reportOrphans();
    }

    /**
     * Sorted so the stored value is stable across restarts. Without the sort the
     * order would follow whatever the Set happens to iterate in, and every
     * startup could rewrite the column with a different permutation of the same
     * scopes -- churn that looks like a real change in an audit of the table.
     */
    private static String encodeScopes(Permission p) {
        return p.allowedScopes().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /**
     * Orphans are logged and ignored, never auto-deleted (spec 6.2), so a
     * mistaken catalog removal is revertible without data loss. An orphan grants
     * nothing in the meantime: authority is resolved against PermissionCatalog,
     * not this table.
     */
    private void reportOrphans() {
        Set<String> known = PermissionCatalog.all().stream()
                .map(Permission::key)
                .collect(Collectors.toUnmodifiableSet());

        jdbc.queryForList("SELECT key FROM permission", String.class).stream()
                .filter(key -> !known.contains(key))
                .forEach(key -> log.warn(
                        "Orphaned permission '{}' remains in the database; "
                      + "it grants nothing until restored to the code catalog.", key));
    }
}
