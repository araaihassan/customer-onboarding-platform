package co.ara.onboarding.journey;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Q15's manual override. Either field may be null: a null ownerUserId leaves the
 * current owner untouched (this is a targeted reassignment/reschedule, not the
 * case-level full-replace PUT CaseView/UpdateCaseRequest carry), and a null
 * dueDate leaves the current date untouched.
 */
public record UpdateMilestoneRequest(UUID ownerUserId, LocalDate dueDate) {}
