package co.ara.onboarding.task;

/**
 * The two resource kinds a {@link Comment} can attach to. Each constant carries its
 * own wire string, matching {@code comment.comment_resource_type_ck} in
 * {@code V16__task.sql} exactly, so the CHECK constraint and this enum cannot
 * disagree by a typo -- a third value needs a migration AND a compile error here.
 * Persisted via {@link CommentResourceTypeConverter} rather than
 * {@code @Enumerated(STRING)}, because the wire strings ("task", "onboarding_case")
 * are not the enum constants' own names.
 */
public enum CommentResourceType {
    TASK("task"),
    CASE("onboarding_case");

    private final String wireValue;

    CommentResourceType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static CommentResourceType fromWireValue(String wireValue) {
        for (CommentResourceType type : values()) {
            if (type.wireValue.equals(wireValue)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown comment resource type: " + wireValue);
    }
}
