package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.Condition;
import co.ara.onboarding.workflow.ConditionOperator;
import co.ara.onboarding.workflow.ConditionSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test -- ConditionEvaluator has no dependencies, so no Spring context and
 * no Postgres are needed to exercise its fail-closed semantics.
 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private Condition condition(ConditionSource source, String key, ConditionOperator op, String value) {
        Condition c = new Condition();
        c.setSource(source);
        c.setKey(key);
        c.setOperator(op);
        c.setValue(value);
        return c;
    }

    private CustomerFacts facts() {
        return new CustomerFacts(UUID.randomUUID(), "ACTIVE", null, "SE", null, null, null);
    }

    private CaseAttributeValue textValue(String text) {
        CaseAttributeValue v = new CaseAttributeValue();
        v.setValueText(text);
        return v;
    }

    private CaseAttributeValue numberValue(String number) {
        CaseAttributeValue v = new CaseAttributeValue();
        v.setValueNumber(new BigDecimal(number));
        return v;
    }

    /** An unset attribute is FALSE, never true: a missing input must not open a path. */
    @Test
    void anUnsetAttributeEvaluatesFalseForEveryOperator() {
        for (ConditionOperator op : ConditionOperator.values()) {
            assertThat(evaluator.matches(condition(ConditionSource.ATTRIBUTE, "segment", op, "SMB"),
                    facts(), Map.of())).isFalse();
        }
    }

    @Test
    void isSetIsTrueOnlyWhenAValueIsPresent() {
        Condition isSet = condition(ConditionSource.ATTRIBUTE, "segment", ConditionOperator.IS_SET, null);

        assertThat(evaluator.matches(isSet, facts(), Map.of("segment", textValue("ENTERPRISE")))).isTrue();
        assertThat(evaluator.matches(isSet, facts(), Map.of())).isFalse();
    }

    @Test
    void numericComparisonsUseTheNumericColumn() {
        Condition contractValueGreaterThan = condition(
                ConditionSource.ATTRIBUTE, "contractValue", ConditionOperator.GT, "100000");

        assertThat(evaluator.matches(contractValueGreaterThan, facts(),
                Map.of("contractValue", numberValue("150000")))).isTrue();
        assertThat(evaluator.matches(contractValueGreaterThan, facts(),
                Map.of("contractValue", numberValue("50000")))).isFalse();
    }

    /** "9" > "100000" as strings; a NUMBER attribute must not compare lexically. */
    @Test
    void aNumericAttributeIsNotComparedAsText() {
        Condition employeeCountGreaterThan = condition(
                ConditionSource.ATTRIBUTE, "employeeCount", ConditionOperator.GT, "100000");

        assertThat("9".compareTo("100000")).isPositive();   // the lexical trap this test guards against
        assertThat(evaluator.matches(employeeCountGreaterThan, facts(),
                Map.of("employeeCount", numberValue("9")))).isFalse();
    }

    @Test
    void customerFieldsResolveFromTheFactsRecord() {
        assertThat(evaluator.matches(condition(ConditionSource.CUSTOMER, "country", ConditionOperator.EQ, "SE"),
                facts(), Map.of())).isTrue();
        assertThat(evaluator.matches(condition(ConditionSource.CUSTOMER, "country", ConditionOperator.EQ, "NO"),
                facts(), Map.of())).isFalse();
    }

    @Test
    void inMatchesAnyOfTheListedValues() {
        Condition inCondition = condition(ConditionSource.ATTRIBUTE, "segment", ConditionOperator.IN, null);
        inCondition.setValues(new String[] {"SMB", "ENTERPRISE"});

        assertThat(evaluator.matches(inCondition, facts(), Map.of("segment", textValue("SMB")))).isTrue();
        assertThat(evaluator.matches(inCondition, facts(), Map.of("segment", textValue("MIDMARKET")))).isFalse();
    }

    /** A NULL customer field is absent, not empty-string-equal. */
    @Test
    void aNullCustomerFieldEvaluatesFalse() {
        // facts().industry() is null.
        assertThat(evaluator.matches(condition(ConditionSource.CUSTOMER, "industry", ConditionOperator.EQ, ""),
                facts(), Map.of())).isFalse();
    }

    @Test
    void aConditionWithNoSourceAlwaysMatches() {
        assertThat(evaluator.matches(new Condition(), facts(), Map.of())).isTrue();
        assertThat(evaluator.matches(null, facts(), Map.of())).isTrue();
    }
}
