package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskAssigned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;

/** TASK_ASSIGNED (spec 5.2) and, from Task 14, NEW_COMMENT. */
@Component
public class TaskNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;

    TaskNotifications(NotificationPipeline pipeline, SubjectFacts facts) {
        this.pipeline = pipeline;
        this.facts = facts;
    }

    @EventListener
    public void on(TaskAssigned e) {
        var task = facts.task(e.taskId());
        var kase = facts.caseFacts(e.caseId());
        String due = task.dueDate() == null ? "" : ", due " + task.dueDate();
        var draft = new NotificationPipeline.Draft(NotificationType.TASK_ASSIGNED, "task", task.id(), kase.id(),
                "Task assigned to you: " + Text.clip(task.title(), 80),
                "\"" + Text.clip(task.title(), 120) + "\" on " + kase.name() + " (" + kase.customerName() + ")" + due + ".",
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, List.of(e.assigneeId()), e.actorId(),
                new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, task.id()));
    }
}
