package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One step of a {@link WorkflowVersion}'s definition. Child of the version, and
 * frozen along with it once PUBLISHED (stage_frozen trigger).
 */
@Entity
@Table(name = "stage")
public class Stage extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String name;

    @Column(name = "responsible_department_id")
    private UUID responsibleDepartmentId;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "auto_advance", nullable = false)
    private boolean autoAdvance = true;

    @Column(name = "portal_visible", nullable = false)
    private boolean portalVisible = true;

    /** The tenant's promise; distinct from the schedule driven by milestone effort. */
    @Column(name = "sla_days")
    private Integer slaDays;

    /**
     * Subtractive only -- narrows who may write inside this stage after the
     * permission gate has already said yes. No branch here grants (spec §6.3).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "write_scope", nullable = false)
    private WriteScope writeScope = WriteScope.ANY;

    /** Authored here, acted on in sub-project 6. The builder renders it disabled. */
    @Column(name = "notification_template_key")
    private String notificationTemplateKey;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "source", column = @Column(name = "entry_source")),
            @AttributeOverride(name = "key", column = @Column(name = "entry_key")),
            @AttributeOverride(name = "operator", column = @Column(name = "entry_operator")),
            @AttributeOverride(name = "value", column = @Column(name = "entry_value")),
            @AttributeOverride(name = "values", column = @Column(name = "entry_values"))
    })
    private Condition entryCondition = new Condition();

    @Column(name = "fallback_next_stage_id")
    private UUID fallbackNextStageId;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getResponsibleDepartmentId() { return responsibleDepartmentId; }
    public void setResponsibleDepartmentId(UUID responsibleDepartmentId) { this.responsibleDepartmentId = responsibleDepartmentId; }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }

    public boolean isAutoAdvance() { return autoAdvance; }
    public void setAutoAdvance(boolean autoAdvance) { this.autoAdvance = autoAdvance; }

    public boolean isPortalVisible() { return portalVisible; }
    public void setPortalVisible(boolean portalVisible) { this.portalVisible = portalVisible; }

    public Integer getSlaDays() { return slaDays; }
    public void setSlaDays(Integer slaDays) { this.slaDays = slaDays; }

    public WriteScope getWriteScope() { return writeScope; }
    public void setWriteScope(WriteScope writeScope) { this.writeScope = writeScope; }

    public String getNotificationTemplateKey() { return notificationTemplateKey; }
    public void setNotificationTemplateKey(String notificationTemplateKey) { this.notificationTemplateKey = notificationTemplateKey; }

    public Condition getEntryCondition() { return entryCondition; }
    public void setEntryCondition(Condition entryCondition) { this.entryCondition = entryCondition; }

    public UUID getFallbackNextStageId() { return fallbackNextStageId; }
    public void setFallbackNextStageId(UUID fallbackNextStageId) { this.fallbackNextStageId = fallbackNextStageId; }
}
