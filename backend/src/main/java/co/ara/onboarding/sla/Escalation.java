package co.ara.onboarding.sla;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One escalation of a late subject; unique per subject and due date (spec 4.5). */
@Entity
@Table(name = "escalation")
public class Escalation extends TenantScopedEntity {
    @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false) private EscalationSubject subjectType;
    @Column(name = "subject_id", nullable = false) private UUID subjectId;
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "late_user_id") private UUID lateUserId;
    @Enumerated(EnumType.STRING) @Column(name = "route", nullable = false) private EscalationRoute route;
    @Column(name = "escalated_to_user_id") private UUID escalatedToUserId;
    @Column(name = "due_date_at_escalation", nullable = false) private LocalDate dueDateAtEscalation;
    @Column(name = "overdue_days", nullable = false) private int overdueDays;
    @Column(name = "escalated_at", nullable = false) private Instant escalatedAt;

    public EscalationSubject getSubjectType() { return subjectType; }
    public void setSubjectType(EscalationSubject subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }
    public UUID getLateUserId() { return lateUserId; }
    public void setLateUserId(UUID lateUserId) { this.lateUserId = lateUserId; }
    public EscalationRoute getRoute() { return route; }
    public void setRoute(EscalationRoute route) { this.route = route; }
    public UUID getEscalatedToUserId() { return escalatedToUserId; }
    public void setEscalatedToUserId(UUID escalatedToUserId) { this.escalatedToUserId = escalatedToUserId; }
    public LocalDate getDueDateAtEscalation() { return dueDateAtEscalation; }
    public void setDueDateAtEscalation(LocalDate dueDateAtEscalation) { this.dueDateAtEscalation = dueDateAtEscalation; }
    public int getOverdueDays() { return overdueDays; }
    public void setOverdueDays(int overdueDays) { this.overdueDays = overdueDays; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant escalatedAt) { this.escalatedAt = escalatedAt; }
}
