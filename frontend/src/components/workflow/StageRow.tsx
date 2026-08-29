"use client";

import { useState } from "react";
import { ArrowRightIcon, ChevronDownIcon, ChevronRightIcon, ChevronUpIcon, XIcon } from "@/components/icons";
import { BuilderNode } from "@/components/ui/BuilderNode";
import type { StageDraft } from "./draftState";
import { t } from "@/lib/i18n";

/**
 * Workflow stage row (component-specs §12), now composing `BuilderNode`
 * (COMPONENTS.md §21) for its main visual chrome -- name, meta line, selection
 * ring and the branch number-tile fill -- rather than drawing an ad hoc
 * button of its own.
 *
 * There is no drag-and-drop here to preserve: `useDraftState`'s own doc
 * comment already records that this screen deliberately implemented ONLY the
 * ▲▼ button reorder the prototype also draws, never HTML5 drag-and-drop --
 * "keyboard-operable and announceable for free, on a screen an admin touches
 * twice a year." `BuilderNode`'s Task 24 doc comment claiming "StageRow.tsx
 * already implements [drag-and-drop]" does not match this file as it has
 * ever existed; the real, sole reorder mechanism is the `onMoveUp`/
 * `onMoveDown` callbacks below, unchanged, which is exactly the keyboard-
 * accessible affordance README.md's accessibility note asks production code
 * to supply. See the task-33 report for the full reasoning.
 *
 * The select affordance stays `BuilderNode`'s own internal `<button>`, a
 * sibling of the reorder/delete controls rather than a wrapper around them --
 * a `<button>` cannot nest another `<button>`, and BuilderNode already owns
 * that constraint internally.
 */
export function StageRow({
  stage,
  stages,
  index,
  isFirst,
  isLast,
  selected,
  onSelect,
  onMoveUp,
  onMoveDown,
  onDelete,
  readOnly,
}: {
  stage: StageDraft;
  stages: StageDraft[];
  index: number;
  isFirst: boolean;
  isLast: boolean;
  selected: boolean;
  onSelect: () => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onDelete: () => void;
  /** A published version is frozen: browsing stages must stay possible, reordering or deleting them must not. */
  readOnly?: boolean;
}) {
  const milestones = stage.milestones ?? [];
  const milestoneCount = milestones.length;
  const branchRules = stage.branchRules ?? [];
  const stageName = (key: string) => stages.find((s) => s.key === key)?.name || t("workflow.stage.unnamed");
  const [expanded, setExpanded] = useState(false);

  const teamMeta = t("workflow.stage.subline", {
    milestones: String(milestoneCount),
    sla: stage.slaDays ? t("workflow.stage.slaDays", { days: String(stage.slaDays) }) : t("workflow.stage.noSla"),
    writeScope: t(`workflow.writeScope.${stage.writeScope ?? "ANY"}`),
  });

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
      <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
        <span
          className="text-text-faint flex-shrink-0"
          style={{
            font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
            width: 16,
            textAlign: "right",
          }}
        >
          {String(index + 1).padStart(2, "0")}
        </span>

        {/* A stage with no milestones has nothing to expand into, so no toggle is
            drawn -- an affordance that always opens onto an empty list is worse
            than no affordance at all. */}
        {milestoneCount > 0 ? (
          <RowButton
            label={t(expanded ? "workflow.stage.collapse" : "workflow.stage.expand")}
            onClick={() => setExpanded((prev) => !prev)}
          >
            {expanded ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
          </RowButton>
        ) : (
          <span style={{ width: 26, height: 26, flexShrink: 0 }} />
        )}

        <div style={{ flex: 1, minWidth: 0 }}>
          <BuilderNode
            name={stage.name || t("workflow.stage.unnamed")}
            teamMeta={teamMeta}
            milestonePills={[]}
            isBranch={branchRules.length > 0}
            isSelected={selected}
            conditionalChip={Boolean(stage.entryCondition)}
            onClick={onSelect}
          />
        </div>

        {!readOnly && (
          <div className="flex" style={{ gap: "var(--ob-space-5)", flexShrink: 0 }}>
            <RowButton label={t("workflow.stage.moveUp")} disabled={isFirst} onClick={onMoveUp}>
              <ChevronUpIcon size={13} />
            </RowButton>
            <RowButton label={t("workflow.stage.moveDown")} disabled={isLast} onClick={onMoveDown}>
              <ChevronDownIcon size={13} />
            </RowButton>
            <RowButton label={t("workflow.stage.delete")} danger onClick={onDelete}>
              <XIcon size={13} />
            </RowButton>
          </div>
        )}
      </div>

      {(stage.requiresApproval || stage.autoAdvance || branchRules.length > 0) && (
        <div className="flex items-center flex-wrap" style={{ marginLeft: 50, gap: "var(--ob-space-6)" }}>
          {stage.requiresApproval && <Badge>{t("workflow.stage.approval")}</Badge>}
          {stage.autoAdvance && <Badge>{t("workflow.stage.auto")}</Badge>}

          {branchRules.map((rule, i) => (
            <div
              key={i}
              className="flex items-center"
              style={{
                padding: "var(--ob-space-5) var(--ob-space-8)",
                borderRadius: "var(--ob-radius-6)",
                background: "var(--ob-automation-bg)",
                gap: "var(--ob-space-5)",
                font: "var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)",
                color: "var(--ob-automation-fg)",
              }}
            >
              <span>{t("workflow.branch.ifLabel")}</span>
              <ArrowRightIcon size={13} />
              <span>{rule.targetStageKey ? stageName(rule.targetStageKey) : t("workflow.branch.noTarget")}</span>
            </div>
          ))}
        </div>
      )}

      {expanded && milestoneCount > 0 && (
        <ul
          className="flex flex-col"
          style={{
            gap: "var(--ob-space-6)",
            padding: "var(--ob-space-11) 16px",
            marginLeft: 50,
            borderRadius: "var(--ob-radius-9)",
            border: "1px solid var(--ob-line)",
            background: "var(--ob-surface-sunken)",
          }}
        >
          {milestones.map((milestone, i) => {
            const requirements = milestone.requirements ?? [];
            const mandatoryCount = requirements.filter((r) => r.mandatory).length;
            return (
              <li
                key={milestone.key ?? i}
                className="flex items-center justify-between"
                style={{ gap: "var(--ob-space-8)" }}
              >
                <span
                  className="text-text-muted truncate"
                  style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                >
                  {milestone.name || t("workflow.stage.unnamed")}
                </span>
                <span
                  className="text-text-faint flex-shrink-0"
                  style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
                >
                  {t("workflow.milestone.durationSummary", { days: String(milestone.estimatedDurationDays ?? 0) })}
                  {" · "}
                  {requirements.length > 0
                    ? t("workflow.milestone.requirementCount", {
                        count: String(requirements.length),
                        mandatory: String(mandatoryCount),
                      })
                    : t("workflow.milestone.noRequirements")}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function Badge({ children }: { children: string }) {
  return (
    <span
      className="text-text-subtle"
      style={{
        font: "var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)",
        textTransform: "uppercase",
        letterSpacing: "0.05em",
      }}
    >
      {children}
    </span>
  );
}

function RowButton({
  label,
  disabled,
  danger,
  onClick,
  children,
}: {
  label: string;
  disabled?: boolean;
  danger?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex items-center justify-center disabled:cursor-not-allowed disabled:opacity-40"
      style={{
        width: 26,
        height: 26,
        borderRadius: "var(--ob-radius-6)",
        border: "1px solid var(--ob-line)",
        background: "var(--ob-surface)",
        color: danger ? "var(--ob-text-subtle)" : "var(--ob-text-muted)",
        cursor: disabled ? "not-allowed" : "pointer",
      }}
      onMouseEnter={(e) => {
        if (danger && !disabled) {
          e.currentTarget.style.background = "var(--ob-risk-bg)";
          e.currentTarget.style.color = "var(--ob-risk-fg)";
        }
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = "var(--ob-surface)";
        e.currentTarget.style.color = danger ? "var(--ob-text-subtle)" : "var(--ob-text-muted)";
      }}
    >
      {children}
    </button>
  );
}
