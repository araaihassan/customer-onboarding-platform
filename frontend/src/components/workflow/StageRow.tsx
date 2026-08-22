"use client";

import { ArrowRightIcon, ChevronDownIcon, ChevronUpIcon, XIcon } from "@/components/icons";
import type { StageDraft } from "./draftState";
import { t } from "@/lib/i18n";

/**
 * Workflow stage row (component-specs §12). Reordering is the ▲▼ buttons the
 * prototype draws, not drag-and-drop: keyboard-operable and announceable for
 * free, on a screen an admin touches twice a year. Delete stops propagation
 * so it never also selects the row it just removed.
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
  const milestoneCount = stage.milestones?.length ?? 0;
  const branchRules = stage.branchRules ?? [];
  const stageName = (key: string) => stages.find((s) => s.key === key)?.name || t("workflow.stage.unnamed");

  return (
    <div
      className="flex items-start bg-bg-surface"
      style={{
        borderRadius: "var(--ob-radius-row)",
        padding: "12px 16px",
        gap: "var(--ob-space-11)",
        border: `1px solid var(${selected ? "--ob-accent" : "--ob-border-default"})`,
        boxShadow: selected ? "var(--ob-elevation-ring-accent)" : undefined,
      }}
    >
      <span
        className="text-text-faint"
        style={{ font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)", paddingTop: 2 }}
      >
        {String(index + 1).padStart(2, "0")}
      </span>

      {/* The select affordance is its own button, a sibling of the reorder/delete
          controls rather than a wrapper around them -- a <button> cannot nest
          another <button>, and a div[role=button] wrapping real buttons has the
          same problem for assistive tech even though the DOM allows it. */}
      <button
        type="button"
        aria-pressed={selected}
        onClick={onSelect}
        className="flex-1 min-w-0 text-left bg-transparent border-none cursor-pointer"
      >
        <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
          <span
            className="text-text-primary truncate"
            style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
          >
            {stage.name || t("workflow.stage.unnamed")}
          </span>
          {stage.requiresApproval && <Badge>{t("workflow.stage.approval")}</Badge>}
          {stage.autoAdvance && <Badge>{t("workflow.stage.auto")}</Badge>}
        </div>

        <p
          className="text-text-muted truncate"
          style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-ui)" }}
        >
          {t("workflow.stage.subline", {
            milestones: String(milestoneCount),
            sla: stage.slaDays ? t("workflow.stage.slaDays", { days: String(stage.slaDays) }) : t("workflow.stage.noSla"),
            writeScope: t(`workflow.writeScope.${stage.writeScope ?? "ANY"}`),
          })}
        </p>

        {branchRules.map((rule, i) => (
          <div
            key={i}
            className="flex items-center flex-wrap"
            style={{
              marginTop: "var(--ob-space-6)",
              padding: "var(--ob-space-5) var(--ob-space-8)",
              borderRadius: "var(--ob-radius-inner)",
              background: "var(--ob-accent-tint)",
              border: "1px solid var(--ob-accent-tint-border)",
              gap: "var(--ob-space-5)",
              font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
              color: "var(--ob-accent-ink)",
            }}
          >
            <span>{t("workflow.branch.ifLabel")}</span>
            <ArrowRightIcon size={13} />
            <span>{rule.targetStageKey ? stageName(rule.targetStageKey) : t("workflow.branch.noTarget")}</span>
          </div>
        ))}
      </button>

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
  );
}

function Badge({ children }: { children: string }) {
  return (
    <span
      className="text-text-muted"
      style={{
        font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
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
        borderRadius: "var(--ob-radius-segment)",
        border: "1px solid var(--ob-border-default)",
        background: "var(--ob-bg-surface)",
        color: danger ? "var(--ob-text-muted)" : "var(--ob-text-secondary)",
        cursor: disabled ? "not-allowed" : "pointer",
      }}
      onMouseEnter={(e) => {
        if (danger && !disabled) {
          e.currentTarget.style.background = "var(--ob-status-blocked-bg)";
          e.currentTarget.style.color = "var(--ob-status-blocked-fg)";
        }
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = "var(--ob-bg-surface)";
        e.currentTarget.style.color = danger ? "var(--ob-text-muted)" : "var(--ob-text-secondary)";
      }}
    >
      {children}
    </button>
  );
}
