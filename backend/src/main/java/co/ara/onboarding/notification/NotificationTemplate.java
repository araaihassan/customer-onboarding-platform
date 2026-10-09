package co.ara.onboarding.notification;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** A tenant-authored stage alert template (6B spec 4.3). The key is immutable; never deleted. */
@Entity
@Table(name = "notification_template")
public class NotificationTemplate extends TenantScopedEntity {
    @Column(name = "key", nullable = false, updatable = false) private String key;
    @Column(name = "name", nullable = false) private String name;
    @Column(name = "entered_subject", nullable = false) private String enteredSubject;
    @Column(name = "entered_body", nullable = false) private String enteredBody;
    @Column(name = "exited_subject") private String exitedSubject;
    @Column(name = "exited_body") private String exitedBody;
    @Column(name = "active", nullable = false) private boolean active = true;
    @Column(name = "created_by") private UUID createdBy;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEnteredSubject() { return enteredSubject; }
    public void setEnteredSubject(String v) { this.enteredSubject = v; }
    public String getEnteredBody() { return enteredBody; }
    public void setEnteredBody(String v) { this.enteredBody = v; }
    public String getExitedSubject() { return exitedSubject; }
    public void setExitedSubject(String v) { this.exitedSubject = v; }
    public String getExitedBody() { return exitedBody; }
    public void setExitedBody(String v) { this.exitedBody = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
