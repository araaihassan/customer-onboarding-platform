"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { PlusIcon, WorkflowIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import {
  parseProblems,
  useDefinition,
  usePublish,
  useSaveDraft,
  type Attribute,
  type BranchRule,
  type Milestone,
  type Requirement,
  type Stage,
  type WorkflowDefinition,
} from "@/lib/api/workflows";
import { StageInspector } from "@/components/workflow/StageInspector";
import { StageRow } from "@/components/workflow/StageRow";
import { newDraftKey, useDraftState, type AttributeDraft, type StageDraft } from "@/components/workflow/draftState";
import { t } from "@/lib/i18n";

/**
 * The workflow builder (component-specs §12, uispecs/README §6): stage list
 * plus a sticky inspector, `1fr 320px`. Publish stays a separate call from
 * Save -- the atomic whole-draft PUT is what the server validates against,
 * never an in-memory draft the publish click never persisted.
 */
export default function VersionPage() {
  const { id: templateId, vid: versionId } = useParams<{ id: string; vid: string }>();
  const definition = useDefinition(templateId, versionId);

  useSetPageHeader(t("workflow.builder.title"));

  if (definition.isLoading) return <SkeletonRows rows={5} height={64} />;
  if (definition.isError || !definition.data) {
    return (
      <EmptyState
        icon={<WorkflowIcon size={28} />}
        title={t("common.error")}
        action={
          <Button type="button" variant="secondary" onClick={() => void definition.refetch()}>
            {t("common.retry")}
          </Button>
        }
      />
    );
  }

  // Keyed on the version: navigating to a different version remounts this
  // subtree, so useDraftState's initial value is always the fresh one it was
  // seeded with, not a stale first-render snapshot React would otherwise keep.
  return <Builder key={versionId} templateId={templateId} initial={definition.data} />;
}

function toStageDraft(stage: Stage): StageDraft {
  return {
    key: stage.key ?? newDraftKey("stage"),
    name: stage.name,
    responsibleDepartmentId: stage.responsibleDepartmentId,
    requiresApproval: stage.requiresApproval,
    autoAdvance: stage.autoAdvance,
    portalVisible: stage.portalVisible,
    slaDays: stage.slaDays,
    writeScope: stage.writeScope,
    notificationTemplateKey: stage.notificationTemplateKey,
    entryCondition: stage.entryCondition,
    fallbackNextStageKey: stage.fallbackNextStageKey,
    milestones: (stage.milestones ?? []).map(toMilestoneRequest),
    branchRules: (stage.branchRules ?? []).map(toBranchRuleRequest),
  };
}

function toMilestoneRequest(milestone: Milestone) {
  return {
    key: milestone.key ?? newDraftKey("milestone"),
    name: milestone.name,
    description: milestone.description,
    estimatedDurationDays: milestone.estimatedDurationDays,
    dependsOnMilestoneKeys: milestone.dependsOnMilestoneKeys ?? [],
    requirements: (milestone.requirements ?? []).map(toRequirementRequest),
  };
}

function toRequirementRequest(requirement: Requirement) {
  return {
    kind: requirement.kind,
    label: requirement.label,
    weight: requirement.weight,
    mandatory: requirement.mandatory,
    documentCategory: requirement.documentCategory,
    approverRelationship: requirement.approverRelationship,
  };
}

function toBranchRuleRequest(rule: BranchRule) {
  return { condition: rule.condition, targetStageKey: rule.targetStageKey };
}

function toAttributeDraft(attribute: Attribute): AttributeDraft {
  return {
    key: attribute.key ?? newDraftKey("attribute"),
    label: attribute.label,
    dataType: attribute.dataType,
    required: attribute.required,
    allowedValues: attribute.allowedValues,
  };
}

function Builder({ templateId, initial }: { templateId: string; initial: WorkflowDefinition }) {
  const versionId = initial.versionId!;
  const isPublished = initial.status === "PUBLISHED";

  const [lockVersion, setLockVersion] = useState(initial.lockVersion ?? 0);
  const [announcement, setAnnouncement] = useState("");
  const [publishProblems, setPublishProblems] = useState<string[]>();

  const draft = useDraftState(
    (initial.stages ?? []).map(toStageDraft),
    (initial.attributes ?? []).map(toAttributeDraft),
  );
  const saveDraft = useSaveDraft();
  const publish = usePublish();

  // A half-edited graph must never be what publish validates against a
  // reload wiping it out silently is a smaller harm than that, so this warns
  // rather than blocking navigation outright (Next's App Router has no
  // first-class in-app navigation guard to hook into).
  useEffect(() => {
    if (!draft.dirty) return;
    function onBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault();
      e.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [draft.dirty]);

  const selectedStage = draft.stages.find((s) => s.key === draft.selectedKey);
  const selectedIndex = draft.stages.findIndex((s) => s.key === draft.selectedKey);

  function save() {
    setPublishProblems(undefined);
    saveDraft.mutate(
      { templateId, versionId, body: { stages: draft.stages, attributes: draft.attributes, lockVersion } },
      {
        onSuccess: (saved) => {
          setLockVersion(saved.lockVersion ?? lockVersion);
          draft.reset(
            (saved.stages ?? []).map(toStageDraft),
            (saved.attributes ?? []).map(toAttributeDraft),
          );
          setAnnouncement(t("workflow.builder.saved"));
        },
      },
    );
  }

  function doPublish() {
    setPublishProblems(undefined);
    publish.mutate(
      { templateId, versionId },
      {
        onError: (error) => {
          if (error instanceof ApiError && error.status === 422) {
            setPublishProblems(parseProblems(error.message));
          }
        },
      },
    );
  }

  function addStage() {
    draft.addStage({
      key: newDraftKey("stage"),
      name: "",
      requiresApproval: false,
      autoAdvance: true,
      portalVisible: true,
      writeScope: "ANY",
      milestones: [],
      branchRules: [],
    });
  }

  return (
    <section>
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <span
          className="text-text-muted"
          style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)" }}
        >
          {isPublished
            ? t("workflow.version.published", { version: String(initial.versionNo ?? "") })
            : t("workflow.version.draft")}
        </span>

        <div className="flex-1" />

        {!isPublished && (
          <>
            <Button
              type="button"
              variant="secondary"
              onClick={save}
              disabled={!draft.dirty || saveDraft.isPending}
              style={{ gap: "var(--ob-space-6)" }}
            >
              {t("workflow.builder.save")}
            </Button>
            <Button
              type="button"
              onClick={doPublish}
              disabled={draft.dirty || publish.isPending}
              title={draft.dirty ? t("workflow.publish.disabledHint") : undefined}
            >
              {t("workflow.publish.submit")}
            </Button>
          </>
        )}
      </div>

      {saveDraft.isError && (
        <p role="alert" style={alertStyle}>
          {t("workflow.builder.saveFailed")}
        </p>
      )}

      {draft.problems.length > 0 && (
        <ProblemBanner title={t("workflow.builder.problems")} problems={draft.problems} />
      )}
      {publishProblems && (
        <ProblemBanner title={t("workflow.publish.problems")} problems={publishProblems} />
      )}

      <div className="grid items-start xl:grid-cols-[minmax(0,1fr)_320px]" style={{ gap: "var(--ob-space-20)" }}>
        <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          {draft.stages.map((stage, index) => (
            <StageRow
              key={stage.key}
              stage={stage}
              stages={draft.stages}
              index={index}
              isFirst={index === 0}
              isLast={index === draft.stages.length - 1}
              selected={stage.key === draft.selectedKey}
              onSelect={() => draft.select(stage.key)}
              onMoveUp={() => draft.moveUp(stage.key)}
              onMoveDown={() => draft.moveDown(stage.key)}
              onDelete={() => draft.removeStage(stage.key)}
              readOnly={isPublished}
            />
          ))}

          {!isPublished && (
            <button
              type="button"
              onClick={addStage}
              className="inline-flex items-center self-start"
              style={{
                gap: "var(--ob-space-6)",
                padding: "var(--ob-space-10) var(--ob-space-13)",
                borderRadius: "var(--ob-radius-chip)",
                border: "1px dashed var(--ob-border-dashed)",
                color: "var(--ob-text-secondary)",
                font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
              }}
            >
              <PlusIcon size={14} />
              {t("workflow.builder.addStage")}
            </button>
          )}
        </div>

        {selectedStage ? (
          <StageInspector
            key={selectedStage.key}
            stage={selectedStage}
            stageIndex={selectedIndex}
            stages={draft.stages}
            attributes={draft.attributes}
            onChange={(patch) => draft.updateStage(selectedStage.key, patch)}
            readOnly={isPublished}
          />
        ) : (
          <p
            className="text-text-faint"
            style={{ font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)" }}
          >
            {t("workflow.inspector.selectStage")}
          </p>
        )}
      </div>
    </section>
  );
}

function ProblemBanner({ title, problems }: { title: string; problems: string[] }) {
  return (
    <div
      role="alert"
      style={{
        marginBottom: "var(--ob-space-16)",
        padding: "var(--ob-space-13)",
        borderRadius: "var(--ob-radius-inner)",
        background: "var(--ob-status-at-risk-bg)",
        color: "var(--ob-status-at-risk-fg)",
      }}
    >
      <p style={{ font: "600 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}>
        {title}
      </p>
      <ul style={{ marginTop: "var(--ob-space-6)", paddingLeft: "var(--ob-space-16)", listStyle: "disc" }}>
        {problems.map((problem, i) => (
          <li key={i} style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}>
            {problem}
          </li>
        ))}
      </ul>
    </div>
  );
}

const alertStyle = {
  color: "var(--ob-status-blocked-fg)",
  marginBottom: "var(--ob-space-13)",
  font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
} as const;
