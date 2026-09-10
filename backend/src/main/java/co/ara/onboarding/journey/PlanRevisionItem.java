package co.ara.onboarding.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One frozen milestone row captured into a {@link PlanRevision} snapshot -- the
 * second append-only table in the codebase, after {@code audit_event}, and for the
 * same reason (V21's own comment): a snapshot the application can rewrite is not
 * evidence of what was actually sent. "What did we send on 15 October?" has to have
 * an answer, for governance packs and for disputes.
 *
 * No setters beyond construction, so the Java layer says the same thing the GRANT
 * says: V21 grants {@code onboarding_app} only SELECT and INSERT on
 * {@code plan_revision_item}, with no UPDATE and no DELETE. A field that can never
 * legally change after the row is written has no business exposing a method that
 * changes it.
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass, the same reasoning
 * {@code authz.UserRole}'s javadoc gives: that superclass chain supplies
 * {@code updated_at}, and this table has none -- a column that can never change has
 * no business carrying a timestamp claiming it did. The consequence is the same one
 * UserRole names too: this entity carries no Hibernate {@code tenantFilter}, so
 * tenant isolation for plan_revision_item rests entirely on the database (V21's
 * {@code enable_tenant_rls('plan_revision_item')}), enforced once rather than
 * twice.
 *
 * caseId is denormalised (also reachable via planRevisionId -> plan_revision.case_id)
 * so a future {@code PlanRevisionItemDescriptor} is one subquery hop to
 * {@code onboarding_case} instead of a chain -- the same reason {@code milestone}
 * and {@code comment} both carry case_id too.
 */
@Entity
@Table(name = "plan_revision_item")
public class PlanRevisionItem {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plan_revision_id", nullable = false)
    private UUID planRevisionId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    @Column(name = "milestone_definition_id", nullable = false)
    private UUID milestoneDefinitionId;

    @Column(name = "stage_name", nullable = false)
    private String stageName;

    @Column(name = "milestone_name", nullable = false)
    private String milestoneName;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "estimated_duration_days", nullable = false)
    private int estimatedDurationDays;

    @Column(name = "portal_visible", nullable = false)
    private boolean portalVisible;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanRevisionItem() {} // required by JPA

    public PlanRevisionItem(UUID id, UUID tenantId, UUID planRevisionId, UUID caseId,
                             UUID milestoneId, UUID milestoneDefinitionId, String stageName,
                             String milestoneName, LocalDate dueDate, UUID ownerUserId,
                             int estimatedDurationDays, boolean portalVisible, int sortOrder,
                             Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.planRevisionId = planRevisionId;
        this.caseId = caseId;
        this.milestoneId = milestoneId;
        this.milestoneDefinitionId = milestoneDefinitionId;
        this.stageName = stageName;
        this.milestoneName = milestoneName;
        this.dueDate = dueDate;
        this.ownerUserId = ownerUserId;
        this.estimatedDurationDays = estimatedDurationDays;
        this.portalVisible = portalVisible;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPlanRevisionId() { return planRevisionId; }
    public UUID getCaseId() { return caseId; }
    public UUID getMilestoneId() { return milestoneId; }
    public UUID getMilestoneDefinitionId() { return milestoneDefinitionId; }
    public String getStageName() { return stageName; }
    public String getMilestoneName() { return milestoneName; }
    public LocalDate getDueDate() { return dueDate; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public int getEstimatedDurationDays() { return estimatedDurationDays; }
    public boolean isPortalVisible() { return portalVisible; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
