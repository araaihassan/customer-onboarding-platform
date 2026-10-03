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
