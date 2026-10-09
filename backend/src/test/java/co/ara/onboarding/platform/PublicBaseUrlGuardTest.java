package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicBaseUrlGuardTest {

    private static MockEnvironment profiles(String... active) {
        var env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void blankIsRefusedOutsideDevAndTestAndNamesTheVariable() {
        assertThatThrownBy(() -> new PublicBaseUrl("", profiles("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_PUBLIC_BASE_URL");
        assertThatThrownBy(() -> new PublicBaseUrl("", profiles()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void devAndTestDefaultToTheLocalFrontend() {
        assertThat(new PublicBaseUrl("", profiles("dev")).value()).isEqualTo("http://localhost:3000");
        assertThat(new PublicBaseUrl(null, profiles("test")).value()).isEqualTo("http://localhost:3000");
    }

    @Test
    void anExplicitValueWinsOnEveryProfile() {
        assertThat(new PublicBaseUrl("https://app.example.com", profiles("dev")).value())
                .isEqualTo("https://app.example.com");
    }

    @Test
    void malformedValuesAreRefused() {
        for (String bad : new String[]{"app.example.com", "ftp://app.example.com", "https://app.example.com/",
                "https://app.example.com?x=1", "https://"}) {
            assertThatThrownBy(() -> PublicBaseUrl.validate(bad)).as(bad).isInstanceOf(IllegalStateException.class);
        }
        assertThat(PublicBaseUrl.validate("https://example.com/onboarding")).isEqualTo("https://example.com/onboarding");
    }

    @Test
    void absoluteJoinsAPath() {
        assertThat(new PublicBaseUrl("https://app.example.com", profiles("prod")).absolute("/t/acme/x"))
                .isEqualTo("https://app.example.com/t/acme/x");
    }
}
