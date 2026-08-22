package co.ara.onboarding.journey;

import java.util.List;

/**
 * Every problem found while validating a case's attribute answers against the
 * pinned version's declarations, collected rather than thrown on the first one --
 * an admin fixing a rejected case creation one error per round trip is a bad
 * experience, the same reasoning PublishService.validate already applies to a
 * workflow draft.
 */
public class AttributeValidationException extends RuntimeException {

    private final List<String> problems;

    public AttributeValidationException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }
}
