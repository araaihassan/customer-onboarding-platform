package co.ara.onboarding.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's deadline horizons and automatic-reminder policy (6B spec 4.4). RLS-bound: it reads the
 * bound tenant's rows. A tenant with no notification_policy row (one created outside provisioning) reads
 * the code defaults, so a missing row never turns every horizon off.
 */
@Component
public class PolicyReader {

    public record Policy(boolean autoRemindEnabled, int intervalDays, int max, Map<HorizonKind, List<Integer>> horizons) {}

    private final JdbcTemplate jdbc;

    PolicyReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Policy current() {
        var rows = jdbc.queryForList(
                "SELECT auto_remind_enabled, auto_remind_interval_days, auto_remind_max FROM notification_policy");
        Map<HorizonKind, List<Integer>> horizons = new EnumMap<>(HorizonKind.class);
        for (HorizonKind k : HorizonKind.values()) horizons.put(k, new ArrayList<>());
        if (rows.isEmpty()) {
            for (HorizonKind k : HorizonKind.values()) horizons.get(k).addAll(k.businessDays ? List.of(2) : List.of(7, 14, 30));
            return new Policy(false, 3, 3, horizons);
        }
        var p = rows.get(0);
        jdbc.query("SELECT kind, lead_days FROM deadline_horizon ORDER BY kind, lead_days",
                rs -> { horizons.get(HorizonKind.valueOf(rs.getString(1))).add(rs.getInt(2)); });
        return new Policy((Boolean) p.get("auto_remind_enabled"), ((Number) p.get("auto_remind_interval_days")).intValue(),
                ((Number) p.get("auto_remind_max")).intValue(), horizons);
    }
}
