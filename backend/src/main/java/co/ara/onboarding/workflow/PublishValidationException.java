package co.ara.onboarding.workflow;

import java.util.List;

/**
 * Every rule {@link PublishService#publish} found broken, collected rather than
 * thrown on the first -- see the javadoc on {@code validate} for why. The message is
 * every problem joined, so a caller inspecting only {@code getMessage()} (a log line,
 * a test assertion) still sees each one; {@link #problems()} is the structured form
 * Task 8 maps to a 422 body.
 */
public class PublishValidationException extends RuntimeException {

    private final List<String> problems;

    public PublishValidationException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
