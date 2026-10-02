package co.ara.onboarding.agreement;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant 10 ("PUT request and view types stay field-for-field aligned"): {@code PUT
 * /agreements/{id}/signatories} is a full replace, so every field a {@link SignatoryRequest}
 * row accepts must come back on {@link AgreementSignatoryView} -- otherwise a client
 * round-tripping the list it read would silently blank the missing field. Derived by
 * reflection, so a field added to the request alone fails here rather than in production.
 */
class SignatoryRequestViewAlignmentTest {

    @Test
    void everySignatoryRequestFieldAppearsOnTheSignatoryView() {
        Set<String> viewFields = names(AgreementSignatoryView.class);
        assertThat(viewFields).containsAll(names(SignatoryRequest.class));
    }

    @Test
    void replaceSignatoriesCarriesNothingButTheRowsAndTheLockVersion() {
        // The list's own rows are covered above; lockVersion comes back on the agreement itself.
        assertThat(names(ReplaceSignatoriesRequest.class)).containsExactlyInAnyOrder("signatories", "lockVersion");
        assertThat(names(AgreementView.class)).contains("lockVersion");
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
