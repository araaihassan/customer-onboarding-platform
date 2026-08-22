package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** A custom field captured on a case, whose shape a workflow version defines. */
@Entity
@Table(name = "attribute_definition")
public class AttributeDefinition extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false)
    private AttributeType dataType;

    @Column(nullable = false)
    private boolean required;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_values")
    private String[] allowedValues;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public AttributeType getDataType() { return dataType; }
    public void setDataType(AttributeType dataType) { this.dataType = dataType; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public String[] getAllowedValues() { return allowedValues; }
    public void setAllowedValues(String[] allowedValues) { this.allowedValues = allowedValues; }
}
