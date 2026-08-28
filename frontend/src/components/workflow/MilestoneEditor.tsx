"use client";

import { useId } from "react";
import { PlusIcon, XIcon } from "@/components/icons";
import { Field } from "@/components/ui/Field";
import type { MilestoneRequest, RequirementRequest } from "@/lib/api/workflows";
import { newDraftKey } from "./draftState";
import { t } from "@/lib/i18n";

const REQUIREMENT_KINDS: NonNullable<RequirementRequest["kind"]>[] = ["MANUAL", "TASK", "DOCUMENT", "APPROVAL"];

/** A stage's milestones, each with its own requirements -- family 10/11's editing half. */
export function MilestoneEditor({
  milestones,
  onChange,
}: {
  milestones: MilestoneRequest[];
  onChange: (milestones: MilestoneRequest[]) => void;
}) {
  function updateMilestone(index: number, patch: Partial<MilestoneRequest>) {
    onChange(milestones.map((m, i) => (i === index ? { ...m, ...patch } : m)));
  }

  function removeMilestone(index: number) {
    onChange(milestones.filter((_, i) => i !== index));
  }

  function addMilestone() {
    onChange([
      ...milestones,
      // dependsOnMilestoneKeys must be a real array, never omitted: the server
      // iterates it directly with no null guard, so a JSON body that leaves
      // this key out crashes the save with a 500 rather than a clean 400 --
      // found by actually driving the builder, not by a unit test.
      { key: newDraftKey("milestone"), name: "", estimatedDurationDays: 1, dependsOnMilestoneKeys: [], requirements: [] },
    ]);
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
      <h4
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-chip-size)/var(--ob-type-mono-chip-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
        }}
      >
        {t("workflow.milestones.title")}
      </h4>

      {milestones.map((milestone, index) => (
        <div
          key={milestone.key ?? index}
          className="bg-surface-sunken"
          style={{ borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-11)" }}
        >
          <div className="flex items-start" style={{ gap: "var(--ob-space-8)" }}>
            <div className="flex-1" style={{ display: "grid", gridTemplateColumns: "1fr 100px", gap: "var(--ob-space-8)" }}>
              <Field
                label={t("workflow.milestone.name")}
                value={milestone.name ?? ""}
                onChange={(e) => updateMilestone(index, { name: e.target.value })}
              />
              <Field
                label={t("workflow.milestone.duration")}
                type="number"
                min={1}
                value={milestone.estimatedDurationDays ?? 1}
                onChange={(e) => updateMilestone(index, { estimatedDurationDays: Number(e.target.value) })}
              />
            </div>
            <button
              type="button"
              aria-label={t("workflow.milestone.remove")}
              onClick={() => removeMilestone(index)}
              className="inline-flex items-center justify-center"
              style={{ width: 26, height: 26, marginTop: 20, color: "var(--ob-text-subtle)" }}
            >
              <XIcon size={14} />
            </button>
          </div>

          <RequirementList
            requirements={milestone.requirements ?? []}
            onChange={(requirements) => updateMilestone(index, { requirements })}
          />
        </div>
      ))}

      <button
        type="button"
        onClick={addMilestone}
        className="inline-flex items-center self-start"
        style={{
          gap: "var(--ob-space-6)",
          padding: "var(--ob-space-8) var(--ob-space-11)",
          borderRadius: "var(--ob-radius-7)",
          border: "1px dashed var(--ob-line-strong)",
          color: "var(--ob-text-muted)",
          font: "12px/1.4 var(--ob-font-family-ui)",
        }}
      >
        <PlusIcon size={13} />
        {t("workflow.milestone.add")}
      </button>
    </div>
  );
}

function RequirementList({
  requirements,
  onChange,
}: {
  requirements: RequirementRequest[];
  onChange: (requirements: RequirementRequest[]) => void;
}) {
  function update(index: number, patch: Partial<RequirementRequest>) {
    onChange(requirements.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  function remove(index: number) {
    onChange(requirements.filter((_, i) => i !== index));
  }

  function add() {
    onChange([...requirements, { kind: "MANUAL", label: "", weight: 1, mandatory: true }]);
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-6)", marginTop: "var(--ob-space-8)" }}>
      {requirements.map((requirement, index) => (
        <div key={index} className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-6)" }}>
          <label className="sr-only" htmlFor={`req-kind-${index}`}>
            {t("workflow.requirement.kind")}
          </label>
          <select
            id={`req-kind-${index}`}
            value={requirement.kind ?? "MANUAL"}
            onChange={(e) => update(index, { kind: e.target.value as RequirementRequest["kind"] })}
            style={{
              height: "var(--ob-control-height-sm)",
              borderRadius: "var(--ob-radius-7)",
              border: "1px solid var(--ob-line)",
              background: "var(--ob-surface)",
              font: "11px/1.4 var(--ob-font-family-ui)",
            }}
          >
            {REQUIREMENT_KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {t(`workflow.requirementKind.${kind}`)}
              </option>
            ))}
          </select>

          <input
            aria-label={t("workflow.requirement.label")}
            value={requirement.label ?? ""}
            onChange={(e) => update(index, { label: e.target.value })}
            className="flex-1"
            style={{
              height: "var(--ob-control-height-sm)",
              borderRadius: "var(--ob-radius-7)",
              border: "1px solid var(--ob-line)",
              background: "var(--ob-surface)",
              padding: "0 var(--ob-space-8)",
              font: "11px/1.4 var(--ob-font-family-ui)",
              minWidth: 120,
            }}
          />

          <MandatoryToggle
            checked={requirement.mandatory ?? true}
            onChange={(checked) => update(index, { mandatory: checked })}
          />

          <button
            type="button"
            aria-label={t("workflow.requirement.remove")}
            onClick={() => remove(index)}
            className="inline-flex items-center justify-center"
            style={{ width: 22, height: 22, color: "var(--ob-text-subtle)" }}
          >
            <XIcon size={12} />
          </button>
        </div>
      ))}

      <button
        type="button"
        onClick={add}
        className="inline-flex items-center self-start text-text-subtle"
        style={{ gap: "var(--ob-space-5)", font: "10.5px/1.4 var(--ob-font-family-ui)" }}
      >
        <PlusIcon size={11} />
        {t("workflow.requirement.add")}
      </button>
    </div>
  );
}

/**
 * A plain settings checkbox, deliberately NOT the shared ui/Checkbox: that
 * component's struck-through label is family 11's task-completion semantics
 * ("this task is done"), which is the wrong reading for "this requirement is
 * mandatory" -- a mandatory requirement, when checked, is not something that
 * happened and is now crossed out.
 */
function MandatoryToggle({ checked, onChange }: { checked: boolean; onChange: (checked: boolean) => void }) {
  const id = useId();
  return (
    <label className="inline-flex items-center" style={{ gap: "var(--ob-space-5)" }}>
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        style={{ width: 14, height: 14 }}
      />
      <span
        className="text-text-muted"
        style={{ font: "10.5px/1.4 var(--ob-font-family-ui)" }}
      >
        {t("workflow.requirement.mandatory")}
      </span>
    </label>
  );
}
