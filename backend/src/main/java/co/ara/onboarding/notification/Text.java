package co.ara.onboarding.notification;

/** Display-text helpers for notification titles and bodies. */
public final class Text {

    private Text() {}

    /** Null-safe; collapses whitespace, and clips to {@code max} characters ending in an ellipsis. */
    public static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
