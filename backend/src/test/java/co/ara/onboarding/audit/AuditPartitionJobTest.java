package co.ara.onboarding.audit;

import co.ara.onboarding.scheduling.AuditPartitionJob;
import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 6.4: the roll-forward runs as onboarding_app, which gets one narrow function and no DDL. */
class AuditPartitionJobTest extends PostgresTestBase {

    @Autowired AuditPartitionJob job;

    @Test
    void theApplicationRoleCanEnsurePartitionsOnlyThroughTheFunction() {
        withAppConnection(jdbc -> assertThat(
                jdbc.queryForObject("SELECT ensure_audit_event_partitions(3)", Integer.class)).isNotNull());
        withAppConnection(jdbc -> assertThatThrownBy(() -> jdbc.execute("CREATE TABLE x_probe (id int)"))
                .hasStackTraceContaining("permission denied for schema public"));
        withAppConnection(jdbc -> assertThatThrownBy(
                () -> jdbc.execute("SELECT create_audit_event_partition('2030-01-01')"))
                .hasStackTraceContaining("permission denied for function"));
    }

    @Test
    void itCreatesOnlyMissingMonthsAndHardensThem() {
        withAppConnection(jdbc -> jdbc.queryForObject("SELECT ensure_audit_event_partitions(30)", Integer.class));
        withAppConnection(jdbc -> assertThat(
                jdbc.queryForObject("SELECT ensure_audit_event_partitions(30)", Integer.class)).isZero());

        String far = ownerJdbc().queryForObject(
                "SELECT 'audit_event_' || to_char(date_trunc('month', now()) + interval '30 months', 'YYYY_MM')",
                String.class);
        List<String> partitions = ownerJdbc().queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'audit_event'::regclass""", String.class);
        assertThat(partitions).contains(far);

        // Hardened exactly like V27's: RLS forced, and onboarding_app holds nothing on it.
        assertThat(ownerJdbc().queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, far))
                .isTrue();
        assertThat(ownerJdbc().queryForObject("""
                SELECT count(*) FROM information_schema.role_table_grants
                 WHERE table_name = ? AND grantee = 'onboarding_app'""", Integer.class, far)).isZero();
        assertThat(ownerJdbc().queryForObject(
                "SELECT has_table_privilege('onboarding_app', ?, 'SELECT,INSERT,UPDATE,DELETE')", Boolean.class, far))
                .isFalse();
        assertThat(ownerJdbc().queryForObject(
                "SELECT count(*) FROM pg_policy WHERE polrelid = ?::regclass", Integer.class, far)).isPositive();
    }

    /**
     * The session TimeZone is whatever the JDBC driver sends (the JVM default), so the function must
     * not depend on it: partition bounds are exactly UTC month boundaries and contiguous whether the
     * session is far east or far west.
     */
    @Test
    void partitionBoundsAreUtcMonthBoundariesWhateverTheSessionTimeZone() {
        for (String zone : new String[] {"Pacific/Auckland", "Pacific/Honolulu"}) {
            // Empty partitions past V27's headroom (V27 made 2026-10..2027-12 under its own session zone; see V34) are dropped so this run creates them afresh.
            ownerJdbc().execute("""
                    DO $$ DECLARE r record; BEGIN
                      FOR r IN SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                                WHERE i.inhparent = 'audit_event'::regclass
                                  AND c.relname >= 'audit_event_2028_01'
                                  AND c.relname <> 'audit_event_default'
                      LOOP EXECUTE format('DROP TABLE %I', r.relname); END LOOP;
                    END $$""");
            withAppConnection(jdbc -> {
                jdbc.execute("SET TimeZone = '" + zone + "'");
                assertThat(jdbc.queryForObject("SELECT ensure_audit_event_partitions(36)", Integer.class))
                        .isPositive();
            });
            withOwnerConnection(jdbc -> {
                jdbc.execute("SET TimeZone = 'UTC'");
                List<String> bad = jdbc.queryForList("""
                        SELECT c.relname || ' ' || pg_get_expr(c.relpartbound, c.oid)
                          FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                         WHERE i.inhparent = 'audit_event'::regclass AND c.relname <> 'audit_event_default'
                           AND c.relname >= 'audit_event_2028_01'
                           AND pg_get_expr(c.relpartbound, c.oid) <> format(
                               'FOR VALUES FROM (''%s 00:00:00+00'') TO (''%s 00:00:00+00'')',
                               to_char(to_date(substr(c.relname, 13), 'YYYY_MM'), 'YYYY-MM-DD'),
                               to_char(to_date(substr(c.relname, 13), 'YYYY_MM') + interval '1 month', 'YYYY-MM-DD'))
                        """, String.class);
                assertThat(bad).as("partitions not on UTC month bounds under " + zone).isEmpty();
            });
        }
    }

    @Test
    void itRejectsAnOutOfRangeArgument() {
        for (int bad : new int[] {-1, 37}) {
            withAppConnection(jdbc -> assertThatThrownBy(
                    () -> jdbc.queryForObject("SELECT ensure_audit_event_partitions(" + bad + ")", Integer.class))
                    .hasStackTraceContaining("months_ahead must be between 0 and 36"));
        }
    }

    @Test
    void theJobRunsTheFunction() {
        assertThat(job.run()).isGreaterThanOrEqualTo(0);
        assertThat(job.run()).isZero();
        String next3 = ownerJdbc().queryForObject(
                "SELECT 'audit_event_' || to_char(date_trunc('month', now()) + interval '3 months', 'YYYY_MM')",
                String.class);
        assertThat(ownerJdbc().queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, next3)).isTrue();
    }
}
