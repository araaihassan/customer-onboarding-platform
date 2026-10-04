package co.ara.onboarding.notification;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** An in-app notification (6B spec §4.1; created by sub-project 6, spec §4.5). */
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
    @Column(name = "subject_type", nullable = false) private String subjectType;
    @Column(name = "subject_id", nullable = false) private UUID subjectId;
    @Column(name = "in_app", nullable = false) private boolean inApp = true;
    @Enumerated(EnumType.STRING) @Column(name = "email_state", nullable = false) private EmailState emailState = EmailState.NONE;
    @Enumerated(EnumType.STRING) @Column(name = "tone", nullable = false) private Tone tone = Tone.INFO;
    @Column(name = "dedupe_key") private String dedupeKey;

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
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public boolean isInApp() { return inApp; }
    public void setInApp(boolean inApp) { this.inApp = inApp; }
    public EmailState getEmailState() { return emailState; }
    public void setEmailState(EmailState emailState) { this.emailState = emailState; }
    public Tone getTone() { return tone; }
    public void setTone(Tone tone) { this.tone = tone; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
}
