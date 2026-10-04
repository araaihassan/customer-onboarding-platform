package co.ara.onboarding.scheduling;

import co.ara.onboarding.platform.OffsetClock;
import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Clock;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** The dev tools move the application clock and run a system-actor job: they must not exist outside dev. */
class DevToolsProfileTest extends PostgresTestBase {

    @Autowired RequestMappingHandlerMapping mapping;
    @Autowired ApplicationContext ctx;
    @Autowired Environment env;
    @Autowired Clock clock;

    @Test
    void noDevMappingIsRegisteredOutsideTheDevProfile() {
        assertThat(env.getActiveProfiles()).doesNotContain("dev");
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            assertThat(info.getPatternValues()).noneMatch(p -> p.contains("/dev/"));
        }
    }

    @Test
    void noDevBeansExist() {
        assertThat(ctx.getBeanNamesForType(DevToolsController.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DevToolsService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(OffsetClock.class)).isEmpty();
        assertThat(clock).isNotInstanceOf(OffsetClock.class);
    }

    @Test
    void theDevClassesAreProfileGatedToDevAlone() {
        for (Class<?> c : new Class<?>[]{DevToolsController.class, DevToolsService.class}) {
            var p = c.getAnnotation(org.springframework.context.annotation.Profile.class);
            assertThat(p).as(c.getSimpleName()).isNotNull();
            assertThat(Arrays.asList(p.value())).containsExactly("dev");
        }
    }
}
