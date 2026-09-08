package co.ara.onboarding.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test -- the converter has no dependencies, so no Spring context and no
 * Postgres are needed. Proves the two gates (comment_resource_type_ck in
 * V16__task.sql and this enum) actually agree on the wire strings, not just that
 * each looks right on its own.
 */
class CommentResourceTypeConverterTest {

    private final CommentResourceTypeConverter converter = new CommentResourceTypeConverter();

    @Test
    void convertsEachConstantToTheStringTheCheckConstraintAllows() {
        assertThat(converter.convertToDatabaseColumn(CommentResourceType.TASK)).isEqualTo("task");
        assertThat(converter.convertToDatabaseColumn(CommentResourceType.CASE)).isEqualTo("onboarding_case");
    }

    @Test
    void roundTripsEveryConstantThroughItsWireValue() {
        for (CommentResourceType type : CommentResourceType.values()) {
            String wire = converter.convertToDatabaseColumn(type);
            assertThat(converter.convertToEntityAttribute(wire)).isEqualTo(type);
        }
    }

    @Test
    void convertsNullBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void failsClosedOnAValueTheCheckConstraintWouldAlsoRefuse() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("document"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
