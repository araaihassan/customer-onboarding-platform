package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.RelationshipType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read side of the same graph. It returns every field the request accepts, plus
 * server-assigned ids and the lock version -- CLAUDE.md's full-replace invariant: a
 * PUT whose view omits a field makes every client that loads, edits one thing and
 * saves erase whatever it was never given.
 *
 * `key` is echoed back as the stage's or milestone's own id in string form, so a
 * client that GETs then PUTs unchanged is a no-op rather than a rebuild. The same is
 * true of every key-shaped cross-reference field (fallbackNextStageKey,
 * targetStageKey, dependsOnMilestoneKeys): each is echoed as the referenced row's id
 * in string form, alongside the resolved id itself for callers that just want the
 * UUID. AttributeView.key is different -- an attribute's key is a real, persisted
 * business identifier (the column itself), not a synthesized graph-resolution key,
 * so it is echoed unchanged.
 */
public record WorkflowDefinitionView(
        UUID versionId, UUID templateId, int versionNo, VersionStatus status,
        long lockVersion, Instant publishedAt,
        List<StageView> stages, List<AttributeView> attributes) {

    public record StageView(
            UUID id,
            String key,
            String name,
            UUID responsibleDepartmentId,
            boolean requiresApproval,
            boolean autoAdvance,
            boolean portalVisible,
            Integer slaDays,
            WriteScope writeScope,
            String notificationTemplateKey,
            ConditionView entryCondition,
            String fallbackNextStageKey,
            UUID fallbackNextStageId,
            List<MilestoneView> milestones,
            List<BranchRuleView> branchRules) {}

    public record MilestoneView(
            UUID id,
            String key,
            String name,
            String description,
            int estimatedDurationDays,
            List<String> dependsOnMilestoneKeys,
            List<UUID> dependsOnMilestoneIds,
            List<RequirementView> requirements) {}

    public record RequirementView(
            UUID id,
            RequirementKind kind,
            String label,
            int weight,
            boolean mandatory,
            String documentCategory,
            RelationshipType approverRelationship) {}

    public record BranchRuleView(
            UUID id,
            ConditionView condition,
            String targetStageKey,
            UUID targetStageId) {}

    public record AttributeView(
            UUID id,
            String key,
            String label,
            AttributeType dataType,
            boolean required,
            List<String> allowedValues) {}

    public record ConditionView(
            ConditionSource source,
            String key,
            ConditionOperator operator,
            String value,
            List<String> values) {}
}
