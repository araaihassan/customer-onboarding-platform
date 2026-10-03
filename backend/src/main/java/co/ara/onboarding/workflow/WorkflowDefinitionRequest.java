package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.RelationshipType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
            List<BranchRuleRequest> branchRules,
            /** Boxed on purpose: an omitted key must mean true, not Jackson's false (spec 8). */
            Boolean pausesOnCustomer) {

        /** Spec 1.2.11: the pre-sub-project-6 arity, so positional call sites compile unchanged. */
        public StageRequest(String key, String name, UUID responsibleDepartmentId, boolean requiresApproval,
                boolean autoAdvance, boolean portalVisible, Integer slaDays, WriteScope writeScope,
                String notificationTemplateKey, ConditionRequest entryCondition, String fallbackNextStageKey,
                List<MilestoneRequest> milestones, List<BranchRuleRequest> branchRules) {
            this(key, name, responsibleDepartmentId, requiresApproval, autoAdvance, portalVisible, slaDays,
                    writeScope, notificationTemplateKey, entryCondition, fallbackNextStageKey, milestones,
                    branchRules, null);
        }
    }

    public record MilestoneRequest(
            String key,
            String name,
            String description,
            @Positive int estimatedDurationDays,
            List<String> dependsOnMilestoneKeys,
            @Valid List<RequirementRequest> requirements,
            // Boxed, not primitive boolean: a client that omits this key must default
            // to visible, never to hidden. StageRequest.portalVisible is a primitive
            // and so already has the "missing key silently binds to false" defect
            // sub-project 2's live run found on autoAdvance -- this field is
            // deliberately not built the same way. Null is coalesced to true in
            // WorkflowService.newMilestone.
            Boolean portalVisible) {}

    public record RequirementRequest(
            RequirementKind kind,
            String label,
            int weight,
            boolean mandatory,
            String documentCategory,
            RelationshipType approverRelationship,
            // Boxed, not primitive boolean, matching MilestoneRequest.portalVisible's own
            // reasoning: a missing key must not silently bind to a wrong default the way
            // StageRequest.autoAdvance once did. Null is read as false wherever this is
            // consumed (DocumentInstantiation.instantiateForCase's own
            // Boolean.TRUE.equals(...) check), matching RequirementDefinition.requiresReview's
            // own documented null-is-false column semantics.
            Boolean requiresReview,
            // Sub-project 5, Task 2: only a SIGNATURE requirement may carry either --
            // PublishService's Rule 6 refuses both a SIGNATURE missing one and a
            // non-SIGNATURE requirement carrying either.
            AgreementRecordMode agreementRecordMode,
            // Becomes the instantiated agreement's own name (varchar(200)) and, through it,
            // the agreement file's download filename -- so bounded here with exactly
            // PatchAgreementRequest.name's constraints: a 400 at the request boundary,
            // never a database failure (final whole-branch review, minor (a)).
            @Size(max = 200) @Pattern(regexp = "^[^\\x00-\\x1F\\x7F\"]*$") String agreementName) {}

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
