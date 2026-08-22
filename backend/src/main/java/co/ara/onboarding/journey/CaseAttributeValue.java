package co.ara.onboarding.journey;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One custom field's value on a {@link Case}, per {@code attribute_definition}.
 * Typed nullable columns rather than one text column parsed at evaluation time --
 * condition evaluation must never fail on a malformed stored value: a branch that
 * throws mid-transition wedges a case, and one that swallows the error is a silent
 * false. UNIQUE (case_id, attribute_definition_id) at the database layer.
 */
@Entity
@Table(name = "case_attribute_value")
public class CaseAttributeValue extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "attribute_definition_id", nullable = false)
    private UUID attributeDefinitionId;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "value_number")
    private BigDecimal valueNumber;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    @Column(name = "value_date")
    private LocalDate valueDate;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getAttributeDefinitionId() { return attributeDefinitionId; }
    public void setAttributeDefinitionId(UUID attributeDefinitionId) { this.attributeDefinitionId = attributeDefinitionId; }

    public String getValueText() { return valueText; }
    public void setValueText(String valueText) { this.valueText = valueText; }

    public BigDecimal getValueNumber() { return valueNumber; }
    public void setValueNumber(BigDecimal valueNumber) { this.valueNumber = valueNumber; }

    public Boolean getValueBoolean() { return valueBoolean; }
    public void setValueBoolean(Boolean valueBoolean) { this.valueBoolean = valueBoolean; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }
}
