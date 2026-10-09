package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RecipientAccess;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.workflow.WorkflowVersionPublished;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** WORKFLOW_PUBLISHED (spec 1.2.6): one row per case owner, counting only cases they can view. */
@Component
public class WorkflowNotifications {

    private final NotificationPipeline pipeline;
    private final SubjectFacts facts;
    private final RecipientAccess access;

    WorkflowNotifications(NotificationPipeline pipeline, SubjectFacts facts, RecipientAccess access) {
        this.pipeline = pipeline;
        this.facts = facts;
        this.access = access;
    }

    @EventListener
    public void on(WorkflowVersionPublished e) {
        String slug = facts.tenantSlug();
        String template = facts.templateName(e.templateId());
        Map<UUID, List<SubjectFacts.OutdatedCase>> byOwner = facts.openCasesOnEarlierVersions(e.templateId(), e.versionId())
                .stream().collect(Collectors.groupingBy(SubjectFacts.OutdatedCase::ownerUserId, LinkedHashMap::new, Collectors.toList()));
        byOwner.forEach((owner, cases) -> {
            var visible = cases.stream()
                    .filter(c -> access.canView(owner, PermissionKeys.CASE_VIEW, Case.class, c.caseId())).toList();
            if (visible.isEmpty()) return;
            var first = visible.get(0);
            var draft = new NotificationPipeline.Draft(NotificationType.WORKFLOW_PUBLISHED, "workflow_version",
                    e.versionId(), null, Text.clip(template, 80) + " v" + e.versionNo() + " is live",
                    visible.size() + " of your open cases " + (visible.size() == 1 ? "is" : "are") + " on an earlier version.",
                    Links.caseLink(slug, first.customerId(), first.caseId()), Tone.INFO, null);
            pipeline.deliver(draft, List.of(owner), e.actorId(),
                    new NotificationPipeline.Visibility(PermissionKeys.CASE_VIEW, Case.class, first.caseId()));
        });
    }
}
