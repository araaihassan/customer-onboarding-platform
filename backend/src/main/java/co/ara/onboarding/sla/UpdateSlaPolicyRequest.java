package co.ara.onboarding.sla;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.io.IOException;

/**
 * Full replace; field-for-field the same as {@link SlaPolicyView}. {@code escalateAfterOverdueDays}
 * is capped at {@value #MAX_ESCALATE_AFTER_OVERDUE_DAYS} business days: escalation is mandatory
 * (spec 1, QA Q10), and an unbounded value would let an administrator opt out of it. It is also
 * read strictly as an integer -- Jackson would otherwise truncate 1.5 to 1 without complaint.
 */
public record UpdateSlaPolicyRequest(
        @NotNull @PositiveOrZero Double atRiskDays,
        @NotNull @Min(1) @Max(UpdateSlaPolicyRequest.MAX_ESCALATE_AFTER_OVERDUE_DAYS)
        @JsonDeserialize(using = UpdateSlaPolicyRequest.WholeNumber.class) Integer escalateAfterOverdueDays) {

    public static final int MAX_ESCALATE_AFTER_OVERDUE_DAYS = 30;

    /** Accepts only a JSON integer token; a fraction is a 400 (HttpMessageNotReadable), not a truncation. */
    static final class WholeNumber extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) ctx.handleUnexpectedToken(Integer.class, p);
            }
            return p.getIntValue();   // overflow beyond int range throws InputCoercionException -> 400
        }
    }
}
