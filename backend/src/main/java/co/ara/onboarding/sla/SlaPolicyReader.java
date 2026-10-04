package co.ara.onboarding.sla;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Reads the bound tenant's sla_policy row (RLS-bound); a tenant without one gets the defaults. */
@Component
public class SlaPolicyReader {
    private final JdbcTemplate jdbc;
    public SlaPolicyReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public SlaPolicy current() {
        List<SlaPolicy> rows = jdbc.query(
                "SELECT at_risk_days, escalate_after_overdue_days FROM sla_policy",
                (rs, i) -> new SlaPolicy(rs.getDouble(1), rs.getInt(2)));
        return rows.isEmpty() ? SlaPolicy.defaults() : rows.get(0);
    }
}
