package co.ara.onboarding.sla;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The war room feed (spec 8, 9.2). Every number is computed over the viewer's own sla.view
 * scope; stageName/customerName are null when the viewer may not read those records.
 */
public record ExceptionsView(Summary summary, List<Card> breached, List<Card> dueToday, List<Card> watch,
                             String calendarName) {

    public record Summary(int breached, int dueToday, int clocksPaused, int autoEscalated) {}

    public record Card(UUID caseId, String caseName, UUID customerId, String customerName, String stageName,
                       UUID ownerUserId, String ownerName, SlaClockView clock, boolean hasOpenRequests,
                       List<EscalationNote> escalations) {}

    public record EscalationNote(EscalationSubject subjectType, UUID subjectId, EscalationRoute route,
                                 UUID escalatedToUserId, String escalatedToName, String latePersonName,
                                 LocalDate dueDate, int overdueDays, Instant escalatedAt) {}
}
