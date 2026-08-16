package co.ara.onboarding.audit;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit trail row. See V5__audit.sql for the table definition.
 *
 * PRIMARY KEY mismatch is deliberate: the table declares
 * PRIMARY KEY (id, occurred_at) because PostgreSQL requires the partition key
 * in any primary key on a partitioned table, but this entity inherits a
 * single @Id UUID id from BaseEntity. id is a UUIDv7 and unique on its own,
 * so a single-column @Id is a correct entity identity; Hibernate's validate
 * mode checks columns and types, not primary-key composition, so this does
 * not fail startup. Do not introduce an @IdClass or @EmbeddedId of
 * (id, occurredAt) -- that would push occurredAt into every lookup for no
 * benefit. The one consequence is that findById scans all partitions, which
 * is acceptable because audit rows are read through time-bounded,
 * tenant-scoped queries, never by bare id in a hot path.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent extends TenantScopedEntity {

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(nullable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "timeline_visible", nullable = false)
    private boolean timelineVisible;

    @Column
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "request_id")
    private String requestId;

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public boolean isTimelineVisible() { return timelineVisible; }
    public void setTimelineVisible(boolean timelineVisible) { this.timelineVisible = timelineVisible; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
