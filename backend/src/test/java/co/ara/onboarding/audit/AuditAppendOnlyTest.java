package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAppendOnlyTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationRoleCannotUpdateOrDeleteAuditEvents() {
        // hasStackTraceContaining, not hasMessageContaining: JdbcTemplate wraps the
        // driver error in a BadSqlGrammarException whose own message is generic,
        // so the Postgres text only appears on the cause.
        assertThatThrownBy(() -> jdbc.execute("UPDATE audit_event SET summary = 'tampered'"))
                .hasStackTraceContaining("permission denied for table audit_event");

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM audit_event"))
                .hasStackTraceContaining("permission denied for table audit_event");
    }

    // Partitions are independent relations that inherit V2's default-privilege
    // GRANT SELECT, INSERT, UPDATE at CREATE TABLE time; naming one directly
    // bypasses both the append-only grant AND RLS applied only to the parent
    // (V5's enable_tenant_rls('audit_event') does not reach partitions accessed
    // by their own name). V5_1 closes this by revoking the schema-wide default
    // grant, stripping what the partitions already inherited, and enabling RLS
    // on each partition as defence in depth.
    //
    // The partition list is derived from pg_inherits rather than named, so a
    // partition created later (V27's forward months, or sub-project 6's job)
    // is covered the moment it exists instead of when someone remembers to
    // add it here.
    @Test
    void applicationRoleCannotAccessPartitionsDirectly() {
        List<String> partitions = auditPartitions();
        assertThat(partitions).contains("audit_event_2026_08", "audit_event_default");

        for (String partition : partitions) {
            assertThatThrownBy(() -> jdbc.execute("SELECT * FROM " + partition))
                    .hasStackTraceContaining("permission denied for table " + partition);

            assertThatThrownBy(() -> jdbc.execute("UPDATE " + partition + " SET summary = 'tampered'"))
                    .hasStackTraceContaining("permission denied for table " + partition);
        }
    }

    @Test
    void everyPartitionForcesRowLevelSecurity() {
        List<String> unforced = ownerJdbc().queryForList("""
                SELECT c.relname
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'audit_event'::regclass
                   AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """, String.class);

        assertThat(unforced).isEmpty();
    }

    private static List<String> auditPartitions() {
        return ownerJdbc().queryForList("""
                SELECT c.relname
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'audit_event'::regclass
                 ORDER BY c.relname
                """, String.class);
    }
}
