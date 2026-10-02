package co.ara.onboarding.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who an escalation goes to (spec 6.2, 1.2.10). Read with JdbcTemplate under the bound
 * tenant's RLS -- the IdentityActorDirectory precedent: it runs as the system actor on ids the
 * sweep itself derived from tenant rows, never on an id taken from a request, so there is no
 * caller scope to apply. It calls no repository finder, so the finder rule needs no exclusion.
 */
@Component
public class ReportingLineDirectory {

    public record Recipient(UUID userId, String email, String fullName) {}

    private static final String ACTIVE_USER = """
            SELECT id, email, full_name FROM app_user WHERE id = ? AND status = 'ACTIVE' AND user_type = 'INTERNAL'""";

    private final JdbcTemplate jdbc;

    public ReportingLineDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeUser(UUID userId) {
        if (userId == null) return Optional.empty();
        return jdbc.query(ACTIVE_USER, (rs, i) -> map(rs), userId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeManagerOf(UUID userId) {
        return activeUser(single("SELECT manager_id FROM app_user WHERE id = ?", userId));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Recipient> activeDepartmentHeadOf(UUID userId) {
        return activeUser(single("""
                SELECT d.head_user_id FROM app_user u JOIN department d ON d.id = u.department_id
                 WHERE u.id = ?""", userId));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Recipient> activeAdministrators() {
        return jdbc.query("""
                SELECT DISTINCT u.id, u.email, u.full_name
                  FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE r.name = 'Administrator' AND r.enabled AND u.status = 'ACTIVE' AND u.user_type = 'INTERNAL'
                 ORDER BY u.email""", (rs, i) -> map(rs));
    }

    private UUID single(String sql, UUID id) {
        if (id == null) return null;
        List<UUID> rows = jdbc.queryForList(sql, UUID.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Recipient map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Recipient(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"));
    }
}
