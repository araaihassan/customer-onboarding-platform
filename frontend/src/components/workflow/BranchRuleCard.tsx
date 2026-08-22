"use client";

import type { CSSProperties } from "react";
import { ArrowRightIcon, XIcon } from "@/components/icons";
import type { AttributeDraft, StageDraft } from "./draftState";
import type { BranchRuleRequest, ConditionRequest } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/** Customer fields a branch condition may read when its source is CUSTOMER, rather than a declared attribute. */
const CUSTOMER_FIELDS = ["industry", "country", "status"];

const OPERATORS: NonNullable<ConditionRequest["operator"]>[] = ["EQ", "NEQ", "GT", "GTE", "LT", "LTE", "IN", "IS_SET"];

/**
 * One branch rule (component-specs §12's inline rule strip, in editable form).
 *
 * The target dropdown offers only stages AFTER this one: PublishService's own
 * rule 2 requires every branch target to have a higher ordinal than the stage
 * that names it, so offering a backward target here is offering a 422 the
 * server will refuse anyway.
 *
 * Condition operands are a closed list -- declared attributes, or a small set
 * of known customer fields -- never free text, so a typo cannot silently
 * compile into a condition that never matches.
 */
export function BranchRuleCard({
  stageIndex,
  stages,
  attributes,
  rule,
  onChange,
  onRemove,
}: {
  stageIndex: number;
  stages: StageDraft[];
  attributes: AttributeDraft[];
  rule: BranchRuleRequest;
  onChange: (patch: Partial<BranchRuleRequest>) => void;
  onRemove: () => void;
}) {
  const forwardStages = stages.slice(stageIndex + 1);
  const condition = rule.condition ?? {};

  return (
    <div
      className="flex flex-col"
      style={{
        gap: "var(--ob-space-8)",
        padding: "var(--ob-space-11)",
        borderRadius: "var(--ob-radius-inner)",
        background: "var(--ob-accent-tint)",
        border: "1px solid var(--ob-accent-tint-border)",
      }}
    >
      <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
        <span
          style={{
            font: "600 var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
            color: "var(--ob-accent-ink)",
          }}
        >
          {t("workflow.branch.ifLabel")}
        </span>

        <ConditionEditor
          condition={condition}
          attributes={attributes}
          onChange={(next) => onChange({ condition: next })}
        />

        <ArrowRightIcon size={13} />

        <label className="sr-only" htmlFor={`branch-target-${stageIndex}`}>
          {t("workflow.branch.target")}
        </label>
        <select
          id={`branch-target-${stageIndex}`}
          aria-label={t("workflow.branch.target")}
          value={rule.targetStageKey ?? ""}
          onChange={(e) => onChange({ targetStageKey: e.target.value })}
          style={selectStyle}
        >
          <option value="" disabled>
            {t("workflow.branch.selectTarget")}
          </option>
          {forwardStages.map((stage) => (
            <option key={stage.key} value={stage.key}>
              {stage.name || stage.key}
            </option>
          ))}
        </select>

        <button
          type="button"
          aria-label={t("workflow.branch.remove")}
          onClick={onRemove}
          className="inline-flex items-center justify-center"
          style={{ width: 22, height: 22, color: "var(--ob-accent-ink)" }}
        >
          <XIcon size={13} />
        </button>
      </div>
    </div>
  );
}

function ConditionEditor({
  condition,
  attributes,
  onChange,
}: {
  condition: ConditionRequest;
  attributes: AttributeDraft[];
  onChange: (condition: ConditionRequest) => void;
}) {
  const source = condition.source ?? "ATTRIBUTE";
  const options = source === "ATTRIBUTE" ? attributes.map((a) => a.key ?? "") : CUSTOMER_FIELDS;

  return (
    <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-6)" }}>
      <label className="sr-only" htmlFor="condition-source">
        {t("workflow.branch.source")}
      </label>
      <select
        id="condition-source"
        aria-label={t("workflow.branch.source")}
        value={source}
        onChange={(e) => onChange({ ...condition, source: e.target.value as ConditionRequest["source"], key: "" })}
        style={selectStyle}
      >
        <option value="ATTRIBUTE">{t("workflow.branch.source.ATTRIBUTE")}</option>
        <option value="CUSTOMER">{t("workflow.branch.source.CUSTOMER")}</option>
      </select>

      <label className="sr-only" htmlFor="condition-field">
        {t("workflow.branch.field")}
      </label>
      <select
        id="condition-field"
        aria-label={t("workflow.branch.field")}
        value={condition.key ?? ""}
        onChange={(e) => onChange({ ...condition, key: e.target.value })}
        style={selectStyle}
      >
        <option value="" disabled>
          {t("common.select")}
        </option>
        {options.map((key) => (
          <option key={key} value={key}>
            {key}
          </option>
        ))}
      </select>

      <label className="sr-only" htmlFor="condition-operator">
        {t("workflow.branch.operator")}
      </label>
      <select
        id="condition-operator"
        aria-label={t("workflow.branch.operator")}
        value={condition.operator ?? "EQ"}
        onChange={(e) => onChange({ ...condition, operator: e.target.value as ConditionRequest["operator"] })}
        style={selectStyle}
      >
        {OPERATORS.map((op) => (
          <option key={op} value={op}>
            {t(`workflow.branch.operator.${op}`)}
          </option>
        ))}
      </select>

      {condition.operator !== "IS_SET" && (
        <input
          aria-label={t("workflow.branch.value")}
          value={condition.value ?? ""}
          onChange={(e) => onChange({ ...condition, value: e.target.value })}
          style={{ ...selectStyle, width: 90, padding: "0 var(--ob-space-8)" }}
        />
      )}
    </div>
  );
}

const selectStyle: CSSProperties = {
  height: "var(--ob-control-height-sm)",
  borderRadius: "var(--ob-radius-chip)",
  border: "1px solid var(--ob-border-default)",
  background: "var(--ob-bg-surface)",
  font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-ui)",
};
