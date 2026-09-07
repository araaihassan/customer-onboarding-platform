package co.ara.onboarding.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface TaskChecklistItemRepository
        extends JpaRepository<TaskChecklistItem, UUID>, JpaSpecificationExecutor<TaskChecklistItem> {

    List<TaskChecklistItem> findByTaskIdOrderByOrdinal(UUID taskId);
}
