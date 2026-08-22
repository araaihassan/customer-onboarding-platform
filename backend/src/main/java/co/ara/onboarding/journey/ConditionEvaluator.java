package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.Condition;
import co.ara.onboarding.workflow.ConditionOperator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

/**
 * Evaluates one condition. Fails closed in every direction: an unknown key, an unset
 * value, an unparseable number and a null customer field all evaluate FALSE. A missing
 * input must never open a path -- the same rule every descriptor follows, for the same
 * reason.
 *
 * A {@link CaseAttributeValue} carries no key of its own -- {@code attributes} is
 * already keyed by the attribute's declared {@code key} string (CaseEngine resolves
 * that through AttributeDefinition before calling in). Which typed column is
 * populated on the value decides the comparison, not a separately-passed data type:
 * a NUMBER attribute is always stored in valueNumber, so dispatching on that column
 * is what keeps "9" > "100000" from being compared as text.
 */
@Component
class ConditionEvaluator {

    boolean matches(Condition condition, CustomerFacts customer, Map<String, CaseAttributeValue> attributes) {
        if (condition == null || condition.getSource() == null) return true;   // no condition = always
        return switch (condition.getSource()) {
            case CUSTOMER  -> matchesText(condition, customerField(customer, condition.getKey()));
            case ATTRIBUTE -> matchesAttribute(condition, attributes.get(condition.getKey()));
        };
    }

    private String customerField(CustomerFacts customer, String key) {
        if (customer == null) return null;
        return switch (key) {
            case "status"   -> customer.status();
            case "industry" -> customer.industry();
            case "country"  -> customer.country();
            // Unknown keys cannot reach here -- publish validation rejects them against
            // CustomerFactKeys.ALL -- but a null return is the fail-closed answer for a
            // version published before a key was removed.
            default -> null;
        };
    }

    /** Text/lexical comparison, for CUSTOMER-sourced conditions. Null is absent, never empty-string-equal. */
    private boolean matchesText(Condition condition, String actual) {
        if (actual == null || actual.isBlank()) return false;   // absent -- false for every operator
        return switch (condition.getOperator()) {
            case IS_SET -> true;
            case EQ  -> actual.equals(condition.getValue());
            case NEQ -> !actual.equals(condition.getValue());
            case IN  -> condition.getValues() != null && Arrays.asList(condition.getValues()).contains(actual);
            case GT  -> compareText(actual, condition.getValue()) > 0;
            case GTE -> compareText(actual, condition.getValue()) >= 0;
            case LT  -> compareText(actual, condition.getValue()) < 0;
            case LTE -> compareText(actual, condition.getValue()) <= 0;
        };
    }

    private int compareText(String actual, String value) {
        return value == null ? 0 : actual.compareTo(value);
    }

    /** Dispatches on which typed column is actually populated. */
    private boolean matchesAttribute(Condition condition, CaseAttributeValue value) {
        if (value == null) return false;   // unset -- false for every operator, including IS_SET

        if (value.getValueNumber() != null) return matchesNumber(condition, value.getValueNumber());
        if (value.getValueDate() != null) return matchesDate(condition, value.getValueDate());
        if (value.getValueBoolean() != null) return matchesBoolean(condition, value.getValueBoolean());
        if (value.getValueText() != null) return matchesTextAttribute(condition, value.getValueText());
        return false;   // every typed column null -- treat as unset
    }

    private boolean matchesNumber(Condition condition, BigDecimal actual) {
        if (condition.getOperator() == ConditionOperator.IS_SET) return true;
        if (condition.getOperator() == ConditionOperator.IN) {
            if (condition.getValues() == null) return false;
            for (String v : condition.getValues()) {
                BigDecimal parsed = parseNumber(v);
                if (parsed != null && actual.compareTo(parsed) == 0) return true;
            }
            return false;
        }
        BigDecimal value = parseNumber(condition.getValue());
        if (value == null) return false;   // unparseable condition value -- fail closed
        int cmp = actual.compareTo(value);
        return switch (condition.getOperator()) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case GT  -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT  -> cmp < 0;
            case LTE -> cmp <= 0;
            case IS_SET, IN -> false;   // handled above; unreachable here
        };
    }

    private BigDecimal parseNumber(String v) {
        if (v == null) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean matchesDate(Condition condition, LocalDate actual) {
        if (condition.getOperator() == ConditionOperator.IS_SET) return true;
        if (condition.getOperator() == ConditionOperator.IN) {
            if (condition.getValues() == null) return false;
            for (String v : condition.getValues()) {
                LocalDate parsed = parseDate(v);
                if (parsed != null && actual.isEqual(parsed)) return true;
            }
            return false;
        }
        LocalDate value = parseDate(condition.getValue());
        if (value == null) return false;
        int cmp = actual.compareTo(value);
        return switch (condition.getOperator()) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case GT  -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT  -> cmp < 0;
            case LTE -> cmp <= 0;
            case IS_SET, IN -> false;
        };
    }

    private LocalDate parseDate(String v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** GT/GTE/LT/LTE/IN are meaningless for a boolean column -- fail closed rather than guess an ordering. */
    private boolean matchesBoolean(Condition condition, boolean actual) {
        return switch (condition.getOperator()) {
            case IS_SET -> true;
            case EQ  -> condition.getValue() != null && Boolean.parseBoolean(condition.getValue()) == actual;
            case NEQ -> condition.getValue() != null && Boolean.parseBoolean(condition.getValue()) != actual;
            default -> false;
        };
    }

    private boolean matchesTextAttribute(Condition condition, String actual) {
        return switch (condition.getOperator()) {
            case IS_SET -> true;
            case EQ  -> actual.equals(condition.getValue());
            case NEQ -> !actual.equals(condition.getValue());
            case IN  -> condition.getValues() != null && Arrays.asList(condition.getValues()).contains(actual);
            case GT  -> compareText(actual, condition.getValue()) > 0;
            case GTE -> compareText(actual, condition.getValue()) >= 0;
            case LT  -> compareText(actual, condition.getValue()) < 0;
            case LTE -> compareText(actual, condition.getValue()) <= 0;
        };
    }
}
