package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A concrete task, document, approval or manual step required by a milestone.
 * documentCategory and approverRelationship are typed nullable columns per kind,
 * not a params jsonb -- a JSON bag invites later sub-projects to write whatever
 * they like into a column nobody validates; a typed column makes each of them add
 * a forward-only migration deliberately.
 */
@Entity
@Table(name = "requirement_definition")
public class RequirementDefinition extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "milestone_definition_id", nullable = false)
    private UUID milestoneDefinitionId;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementKind kind;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int weight = 1;

    @Column(nullable = false)
    private boolean mandatory = true;

    @Column(name = "document_category")
    private String documentCategory;

    @Column(name = "approver_relationship")
    private String approverRelationship;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getMilestoneDefinitionId() { return milestoneDefinitionId; }
    public void setMilestoneDefinitionId(UUID milestoneDefinitionId) { this.milestoneDefinitionId = milestoneDefinitionId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public RequirementKind getKind() { return kind; }
    public void setKind(RequirementKind kind) { this.kind = kind; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }

    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }

    public String getDocumentCategory() { return documentCategory; }
    public void setDocumentCategory(String documentCategory) { this.documentCategory = documentCategory; }

    public String getApproverRelationship() { return approverRelationship; }
    public void setApproverRelationship(String approverRelationship) { this.approverRelationship = approverRelationship; }
}
