package co.ara.onboarding.audit;

/**
 * Thrown by {@link AuditRecorder#record} when Jackson cannot serialize the
 * payload a caller handed it. This is deliberately NOT an
 * {@code IllegalArgumentException}: that type is caught platform-wide by
 * {@code platform.ApiExceptionHandler} and mapped to a client-facing 400,
 * which would be wrong here. A non-serializable audit payload means some
 * OTHER service in the codebase built a bad payload object -- a programming
 * error in the caller, not bad input from the end user, who did nothing
 * wrong and whose otherwise-valid request (create a case, complete a task,
 * whatever triggered this audit write) would be misreported to them as a 400
 * if this extended IllegalArgumentException instead.
 *
 * Left deliberately unmapped: it falls through to Spring's default
 * unmapped-exception handling (a 500), which is the correct signal -- an
 * internal bug worth alerting on, not ordinary client-input noise.
 */
public class AuditSerializationException extends RuntimeException {

    public AuditSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
