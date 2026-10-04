package co.ara.onboarding.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EscalationRepository extends JpaRepository<Escalation, UUID>, JpaSpecificationExecutor<Escalation> {
    List<Escalation> findByCaseIdInOrderByEscalatedAtDesc(Collection<UUID> caseIds);
}
