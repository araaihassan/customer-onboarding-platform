package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V27 has to move every row that landed in audit_event_default after V5's last
 * partition (2026-09) ran out, because Postgres refuses to create a month's
 * partition while DEFAULT holds rows in its range. The suite's own database is
 * migrated before any test can seed that state, so this runs Flyway against a
 * scratch database in the same container: migrate to V26, write an October row
 * the way production did, then migrate the rest and check where it went.
 */
class AuditPartitionMigrationTest extends PostgresTestBase {

    @Test
    void v27MovesRowsOutOfTheDefaultPartitionIntoTheirMonth() {
        String database = "audit_migration_" + UUID.randomUUID().toString().replace("-", "");
        ownerJdbc().execute("CREATE DATABASE " + database);
        try {
            String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
            var dataSource = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
            var scratch = new JdbcTemplate(dataSource);

            flyway(dataSource).target("26").load().migrate();

            UUID tenantId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            scratch.update("""
                    INSERT INTO tenant (id, slug, name, status, created_at, updated_at)
                    VALUES (?, 'scratch', 'Scratch', 'ACTIVE', now(), now())
                    """, tenantId);
            scratch.update("""
                    INSERT INTO audit_event (id, tenant_id, occurred_at, actor_type, action,
                                             resource_type, summary, timeline_visible, created_at, updated_at)
                    VALUES (?, ?, '2026-10-15T09:30:00Z', 'SYSTEM', 'case.created',
                            'onboarding_case', 'October row', true, now(), now())
                    """, eventId, tenantId);
            assertThat(partitionOf(scratch, eventId)).isEqualTo("audit_event_default");

            flyway(dataSource).load().migrate();

            assertThat(partitionOf(scratch, eventId)).isEqualTo("audit_event_2026_10");
            assertThat(scratch.queryForObject(
                    "SELECT summary FROM audit_event WHERE id = ?", String.class, eventId))
                    .isEqualTo("October row");
            assertThat(scratch.queryForObject(
                    "SELECT count(*) FROM audit_event_default", Integer.class)).isZero();
        } finally {
            ownerJdbc().execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            javax.sql.DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    private static String partitionOf(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM audit_event WHERE id = ?", String.class, eventId);
    }
}
