package co.ara.onboarding.sla;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 10: both PUTs are full replaces, so every field a request accepts must come back on
 * its view. The calendar's holidays are the one deliberate asymmetry (own routes), pinned here.
 */
class CalendarRequestViewAlignmentTest {

    @Test
    void everyCalendarRequestFieldAppearsOnTheView() {
        assertThat(names(BusinessCalendarView.class)).containsAll(names(UpdateBusinessCalendarRequest.class));
        assertThat(names(BusinessCalendarView.class)).containsExactlyInAnyOrder("name", "timezone", "workingDays", "holidays");
    }

    @Test
    void everyPolicyRequestFieldAppearsOnTheViewAndNothingElse() {
        assertThat(names(SlaPolicyView.class)).isEqualTo(names(UpdateSlaPolicyRequest.class));
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
