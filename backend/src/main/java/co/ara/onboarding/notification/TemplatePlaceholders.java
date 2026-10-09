package co.ara.onboarding.notification;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The closed placeholder vocabulary of a notification template (6B spec 5.5). */
public final class TemplatePlaceholders {

    public static final Set<String> ALLOWED = Set.of("case", "customer", "stage", "owner");
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");

    private TemplatePlaceholders() {}

    /** Refused at save, never at send (spec 5.5). */
    public static void validate(String field, String text) {
        if (text == null) return;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!ALLOWED.contains(m.group(1))) {
                throw new NotificationRuleException("Unknown placeholder {" + m.group(1) + "} in " + field
                        + "; use {case}, {customer}, {stage} or {owner}");
            }
        }
    }

    public static String render(String text, Map<String, String> values) {
        String out = text;
        for (var e : values.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return out;
    }
}
