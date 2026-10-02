package co.ara.onboarding.agreement;

import co.ara.onboarding.workflow.AgreementRecordMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AgreementSnapshot(String name, AgreementRecordMode recordMode, LocalDate effectiveDate,
                                LocalDate expiresAt, LocalDate renewalDate, Integer noticePeriodDays,
                                List<SignatorySnapshot> signatories) {
    public record SignatorySnapshot(UUID id, SignatoryKind kind, UUID contactId, UUID userId,
                                    String displayRole, int sortOrder) {}
}
