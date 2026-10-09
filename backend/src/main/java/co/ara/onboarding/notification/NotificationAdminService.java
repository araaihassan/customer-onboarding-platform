package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Administration of the tenant's notification templates (6B spec 4.3). notification.manage and
 * workflow.manage are ALL-only with no resource type, so the scope predicate is a conjunction; reads
 * still go through AuthorizedQuery so the rule "every read is gated" holds structurally.
 */
@Service
public class NotificationAdminService {

    private static final String KEY_PATTERN = "^[a-z0-9][a-z0-9_.-]{0,63}$";
    private static final Pageable ALL_BY_KEY = Pageable.unpaged(Sort.by("key"));

    private final NotificationTemplateRepository templates;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contexts;
    private final AuditRecorder audit;
    private final PolicyReader policyReader;
    private final JdbcTemplate jdbc;

    public NotificationAdminService(NotificationTemplateRepository templates, AuthorizedQuery authorizedQuery,
                                    AuthContextProvider contexts, AuditRecorder audit,
                                    PolicyReader policyReader, JdbcTemplate jdbc) {
        this.policyReader = policyReader;
        this.jdbc = jdbc;
        this.templates = templates;
        this.authorizedQuery = authorizedQuery;
        this.contexts = contexts;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        return authorizedQuery.findAll(templates, NotificationTemplate.class, PermissionKeys.NOTIFICATION_MANAGE,
                null, ALL_BY_KEY).stream().map(NotificationAdminService::view).toList();
    }

    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional(readOnly = true)
    public List<TemplateOption> templateOptions() {
        return authorizedQuery.findAll(templates, NotificationTemplate.class, PermissionKeys.WORKFLOW_MANAGE,
                        (root, q, cb) -> cb.isTrue(root.get("active")), ALL_BY_KEY).stream()
                .map(t -> new TemplateOption(t.getKey(), t.getName())).toList();
    }

    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional
    public TemplateView createTemplate(CreateTemplateRequest r) {
        validate(r.key(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody());
        boolean taken = authorizedQuery.count(templates, NotificationTemplate.class, PermissionKeys.NOTIFICATION_MANAGE,
                (root, q, cb) -> cb.equal(root.get("key"), r.key())) > 0;
        if (taken) throw new IllegalStateException("A template with key '" + r.key() + "' already exists");
        NotificationTemplate t = new NotificationTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(TenantContext.getRequired());
        t.setKey(r.key());
        apply(t, r.name(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody(), r.active());
        t.setCreatedBy(contexts.principal().userId());
        try {
            templates.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("A template with key '" + r.key() + "' already exists");
        }
        audit.record(AuditActions.NOTIFICATION_TEMPLATE_CREATED, "notification_template", t.getId(),
                "Created notification template " + t.getKey(), Map.of("key", t.getKey()));
        return view(t);
    }

    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional
    public TemplateView updateTemplate(UUID id, UpdateTemplateRequest r) {
        NotificationTemplate t = authorizedQuery.getById(templates, NotificationTemplate.class,
                PermissionKeys.NOTIFICATION_MANAGE, id);
        if (!t.getKey().equals(r.key())) throw new NotificationRuleException("A template's key cannot change; stages refer to it");
        validate(r.key(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody());
        boolean deactivating = t.isActive() && !r.active();
        apply(t, r.name(), r.enteredSubject(), r.enteredBody(), r.exitedSubject(), r.exitedBody(), r.active());
        templates.saveAndFlush(t);
        audit.record(deactivating ? AuditActions.NOTIFICATION_TEMPLATE_DEACTIVATED : AuditActions.NOTIFICATION_TEMPLATE_UPDATED,
                "notification_template", t.getId(), (deactivating ? "Deactivated" : "Updated") + " notification template " + t.getKey(),
                Map.of("key", t.getKey()));
        return view(t);
    }

    /** notification.manage is ALL-only with no resource type; the policy rows are tenant-wide and RLS-bound. */
    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional(readOnly = true)
    public PolicyView getPolicy() {
        PolicyReader.Policy p = policyReader.current();
        return new PolicyView(new AutoRemindView(p.autoRemindEnabled(), p.intervalDays(), p.max()), p.horizons());
    }

    @RequirePermission(PermissionKeys.NOTIFICATION_MANAGE)
    @Transactional
    public PolicyView replacePolicy(UpdatePolicyRequest r) {
        if (!r.horizons().keySet().equals(EnumSet.allOf(HorizonKind.class))) {
            throw new IllegalArgumentException("Every deadline kind needs a list of lead times, empty to turn it off");
        }
        Map<String, List<Integer>> audited = new TreeMap<>();
        for (HorizonKind k : HorizonKind.values()) {
            List<Integer> leads = r.horizons().get(k);
            if (leads == null) throw new IllegalArgumentException(k + " needs a list of lead times, empty to turn it off");
            if (leads.size() > 5) throw new NotificationRuleException(k + " takes at most five lead times");
            if (new HashSet<>(leads).size() != leads.size()) throw new NotificationRuleException(k + " has a repeated lead time");
            for (Integer d : leads) {
                if (d == null || d < 1 || d > 90) throw new NotificationRuleException("A lead time is 1 to 90 days");
            }
            List<Integer> sorted = new ArrayList<>(leads);
            Collections.sort(sorted);
            audited.put(k.name(), sorted);
        }
        UUID tenant = TenantContext.getRequired();
        jdbc.update("DELETE FROM deadline_horizon");
        audited.forEach((kind, leads) -> leads.forEach(d -> jdbc.update(
                "INSERT INTO deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, now(), now())", Uuid7.generate(), tenant, kind, d)));
        AutoRemindView a = r.autoRemind();
        jdbc.update("""
                INSERT INTO notification_policy (id, tenant_id, auto_remind_enabled, auto_remind_interval_days,
                                                 auto_remind_max, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id) DO UPDATE SET auto_remind_enabled = EXCLUDED.auto_remind_enabled,
                    auto_remind_interval_days = EXCLUDED.auto_remind_interval_days,
                    auto_remind_max = EXCLUDED.auto_remind_max, updated_at = now()""",
                Uuid7.generate(), tenant, a.enabled(), a.intervalDays(), a.max());
        audit.record(AuditActions.NOTIFICATION_POLICY_UPDATED, "tenant", tenant, "Updated the notification policy",
                Map.of("autoRemind", Map.of("enabled", a.enabled(), "intervalDays", a.intervalDays(), "max", a.max()),
                        "horizons", audited));
        return getPolicy();
    }

    private static void validate(String key, String enteredSubject, String enteredBody, String exitedSubject, String exitedBody) {
        if (!key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("A key is lowercase letters, digits, '.', '_' or '-', up to 64 characters");
        }
        if ((exitedSubject == null || exitedSubject.isBlank()) != (exitedBody == null || exitedBody.isBlank())) {
            throw new NotificationRuleException("An exit alert needs both a subject and a body, or neither");
        }
        TemplatePlaceholders.validate("the entered subject", enteredSubject);
        TemplatePlaceholders.validate("the entered body", enteredBody);
        TemplatePlaceholders.validate("the exited subject", exitedSubject);
        TemplatePlaceholders.validate("the exited body", exitedBody);
    }

    private static void apply(NotificationTemplate t, String name, String es, String eb, String xs, String xb, boolean active) {
        t.setName(name);
        t.setEnteredSubject(es);
        t.setEnteredBody(eb);
        t.setExitedSubject(xs == null || xs.isBlank() ? null : xs);
        t.setExitedBody(xb == null || xb.isBlank() ? null : xb);
        t.setActive(active);
    }

    private static TemplateView view(NotificationTemplate t) {
        return new TemplateView(t.getId(), t.getKey(), t.getName(), t.getEnteredSubject(), t.getEnteredBody(),
                t.getExitedSubject(), t.getExitedBody(), t.isActive());
    }
}
