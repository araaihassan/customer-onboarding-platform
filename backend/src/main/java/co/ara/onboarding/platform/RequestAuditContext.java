package co.ara.onboarding.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped capture of "who did this and from where", read by
 * {@code AuditRecorder} when it writes an event. Nothing populates actorUserId
 * yet: JwtAuthenticationFilter does that starting in Task 15, so every event
 * recorded before then legitimately has actorType SYSTEM and a null
 * actorUserId.
 */
@Component
@RequestScope
public class RequestAuditContext {

    public enum ActorType { USER, SYSTEM, PLATFORM_ADMIN }

    private UUID actorUserId;
    private ActorType actorType = ActorType.SYSTEM;
    private final String ip;
    private final String userAgent;
    private final String requestId = UUID.randomUUID().toString();

    public RequestAuditContext(Optional<HttpServletRequest> request) {
        // Inside a scheduled job (TenantJobRunner) the injected HttpServletRequest is a proxy with no
        // servlet request behind it, and touching it throws; such a context has no ip or agent.
        boolean servlet = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes;
        this.ip = servlet ? request.map(HttpServletRequest::getRemoteAddr).orElse(null) : null;
        this.userAgent = servlet ? request.map(r -> r.getHeader("User-Agent")).orElse(null) : null;
    }

    public void setActor(UUID userId, ActorType type) {
        this.actorUserId = userId;
        this.actorType = type;
    }

    public UUID actorUserId() { return actorUserId; }
    public ActorType actorType() { return actorType; }
    public String ip() { return ip; }
    public String userAgent() { return userAgent; }
    public String requestId() { return requestId; }
}
