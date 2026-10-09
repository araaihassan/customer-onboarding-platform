package co.ara.onboarding.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Arrays;

/**
 * The absolute URL people open the application at (6B spec §6.5), used to turn a notification's
 * link path into a link an email can carry. Required outside the dev and test profiles: an email
 * with a relative link is a broken email. Unlike JWT_SECRET this is not a secret, so a dev/test
 * default is safe.
 */
@Component
public class PublicBaseUrl {

    static final String LOCAL_DEFAULT = "http://localhost:3000";
    private static final String REMEDY = " Set APP_PUBLIC_BASE_URL to the absolute URL users open the"
            + " application at, without a trailing slash (for example https://onboarding.example.com).";

    private final String value;

    public PublicBaseUrl(@Value("${app.public-base-url:}") String configured, Environment env) {
        boolean local = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("dev") || p.equals("test"));
        String candidate = (configured == null || configured.isBlank()) && local ? LOCAL_DEFAULT : configured;
        this.value = validate(candidate);
    }

    static String validate(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("app.public-base-url is not set." + REMEDY);
        }
        URI uri;
        try {
            uri = URI.create(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.public-base-url is not a URL." + REMEDY);
        }
        boolean httpish = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!httpish || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
                || configured.endsWith("/")) {
            throw new IllegalStateException("app.public-base-url must be an absolute http(s) URL with no query,"
                    + " fragment or trailing slash." + REMEDY);
        }
        return configured;
    }

    public String value() { return value; }

    public String absolute(String path) { return value + path; }
}
