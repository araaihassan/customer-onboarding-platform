package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.RelationshipType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

/**
 * A draft is edited as ONE document. Reordering stages, deleting one a branch rule
 * targets, renaming a milestone another depends on -- these are graph edits, and
 * per-element endpoints leave dangling references between calls that publish then has
 * to reject. One request validates the graph once and writes it atomically.
 *
 * Cross-references use client-supplied `key` strings, not ids. A newly added stage has
 * no id yet, so a branch rule targeting it cannot name one; asking the client to
 * round-trip through the server for each insert would make reordering three stages a
 * three-request transaction. The server assigns UUIDv7s and resolves keys once.
 */
public record WorkflowDefinitionRequest(
        @Valid List<StageRequest> stages,
        List<AttributeRequest> attributes,
        long lockVersion) {

    public record StageRequest(
            String key,                        // client-local, unique within the request
            String name,
            UUID responsibleDepartmentId,
            boolean requiresApproval,
            boolean autoAdvance,
            boolean portalVisible,
            Integer slaDays,
            WriteScope writeScope,
            String notificationTemplateKey,
            ConditionRequest entryCondition,   // null = always enterable
            String fallbackNextStageKey,       // null = next by ordinal
            @Valid List<MilestoneRequest> milestones,
            List<BranchRuleRequest> branchRules) {}

    public record MilestoneRequest(
            String key,
            String name,
            String description,
            @Positive int estimatedDurationDays,
            List<String> dependsOnMilestoneKeys,
            List<RequirementRequest> requirements) {}

    public record RequirementRequest(
            RequirementKind kind,
            String label,
            int weight,
            boolean mandatory,
            String documentCategory,
            RelationshipType approverRelationship) {}

    public record BranchRuleRequest(
            ConditionRequest condition,
            String targetStageKey) {}       // a key from this same request, resolved server-side

    public record AttributeRequest(
            String key,
            String label,
            AttributeType dataType,
            boolean required,
            List<String> allowedValues) {}

    public record ConditionRequest(
            ConditionSource source,
            String key,
            ConditionOperator operator,
            String value,
            List<String> values) {}
}
