package co.ara.onboarding.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single condition, evaluated against either the customer record or an
 * attribute captured on the case. Embedded twice: once in {@link Stage}, onto
 * the {@code entry_*} columns via {@code @AttributeOverrides} -- a stage's own
 * entry condition -- and once in {@link BranchRule}, with the bare column
 * names, where it is the rule's whole reason to exist.
 */
@Embeddable
public class Condition {

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ConditionSource source;

    @Column(length = 64)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private ConditionOperator operator;

    @Column(length = 255)
    private String value;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] values;

    public ConditionSource getSource() { return source; }
    public void setSource(ConditionSource source) { this.source = source; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public ConditionOperator getOperator() { return operator; }
    public void setOperator(ConditionOperator operator) { this.operator = operator; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String[] getValues() { return values; }
    public void setValues(String[] values) { this.values = values; }
}
