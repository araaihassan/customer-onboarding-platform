package co.ara.onboarding.task;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One checklist line on a {@link Task}. Ordered within a task by {@code ordinal},
 * never by insertion order. Never enters a progress calculation -- progress is
 * derived from requirements alone (spec section 9.3); a checklist is a task's own
 * bookkeeping, not workflow state.
 */
@Entity
@Table(name = "task_checklist_item")
public class TaskChecklistItem extends TenantScopedEntity {

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false)
    private int ordinal;

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
