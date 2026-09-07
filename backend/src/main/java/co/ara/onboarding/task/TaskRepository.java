package co.ara.onboarding.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface TaskRepository
        extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    /** Every task on a case, for the Tasks tab. */
    List<Task> findByCaseId(UUID caseId);

    /** "My work": a cross-case query, which is why case_id is denormalised onto task. */
    List<Task> findByAssigneeId(UUID assigneeId);
}
