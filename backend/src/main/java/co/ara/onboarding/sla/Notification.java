package co.ara.onboarding.sla;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An in-app notification (spec 4.6). */
@Entity
@Table(name = "notification")
public class Notification extends TenantScopedEntity {
    @Column(name = "recipient_user_id", nullable = false) private UUID recipientUserId;
    @Enumerated(EnumType.STRING) @Column(name = "type", nullable = false) private NotificationType type;
    @Column(name = "title", nullable = false) private String title;
    @Column(name = "body", nullable = false) private String body;
    @Column(name = "link_path", nullable = false) private String linkPath;
    @Column(name = "case_id") private UUID caseId;
    @Column(name = "escalation_id") private UUID escalationId;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "emailed_at") private Instant emailedAt;

    public UUID getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(UUID recipientUserId) { this.recipientUserId = recipientUserId; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getLinkPath() { return linkPath; }
    public void setLinkPath(String linkPath) { this.linkPath = linkPath; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    public UUID getEscalationId() { return escalationId; }
    public void setEscalationId(UUID escalationId) { this.escalationId = escalationId; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public Instant getEmailedAt() { return emailedAt; }
    public void setEmailedAt(Instant emailedAt) { this.emailedAt = emailedAt; }
}
