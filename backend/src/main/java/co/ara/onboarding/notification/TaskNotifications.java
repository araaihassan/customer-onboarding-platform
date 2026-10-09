package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.task.CommentAdded;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskAssigned;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** TASK_ASSIGNED and NEW_COMMENT (spec 5.2). */
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

    /**
     * A task comment reaches the task's assignee, a journey comment the case owner; both reach
     * every earlier commenter on the thread. The author is excluded by the pipeline, and each
     * candidate must still be able to see the thread's subject (task.view or case.view).
     */
    @EventListener
    public void on(CommentAdded e) {
        var kase = facts.caseFacts(e.caseId());
        Set<UUID> candidates = new LinkedHashSet<>();
        NotificationPipeline.Visibility visibility;
        String about;
        if (e.resourceType() == CommentResourceType.TASK) {
            var task = facts.task(e.resourceId());
            if (task.assigneeId() != null) candidates.add(task.assigneeId());
            visibility = new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, task.id());
            about = "\"" + Text.clip(task.title(), 80) + "\"";
        } else {
            if (kase.ownerUserId() != null) candidates.add(kase.ownerUserId());
            visibility = new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, kase.id());
            about = kase.name();
        }
        candidates.addAll(facts.earlierCommenters(e.commentId()));
        var draft = new NotificationPipeline.Draft(NotificationType.NEW_COMMENT, "comment", e.commentId(), kase.id(),
                "New comment on " + Text.clip(about, 90),
                facts.userName(e.authorId()) + ": " + Text.clip(facts.commentBody(e.commentId()), 300),
                Links.caseLink(facts.tenantSlug(), kase.customerId(), kase.id()), Tone.INFO, null);
        pipeline.deliver(draft, candidates, e.authorId(), visibility);
    }
}
