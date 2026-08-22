"use client";

import { PlusIcon } from "@/components/icons";
import { Field } from "@/components/ui/Field";
import { Switch } from "@/components/ui/Switch";
import { useDepartments } from "@/lib/api/admin";
import type { AttributeDraft, StageDraft } from "./draftState";
import { BranchRuleCard } from "./BranchRuleCard";
import { MilestoneEditor } from "./MilestoneEditor";
import { t } from "@/lib/i18n";
import type { BranchRuleRequest, StageRequest } from "@/lib/api/workflows";

type WriteScope = NonNullable<StageRequest["writeScope"]>;
const WRITE_SCOPES: WriteScope[] = ["ANY", "DEPARTMENT", "TEAM", "OWNER_ONLY"];

/**
 * The stage inspector (component-specs §12). Sticky at top: 76px, per the
 * spec's own geometry -- but only at the width the two-column layout the page
 * puts it in actually exists (`xl`, ≥1280px). Below that the page's own grid
 * already stacks it under the stage list; staying sticky there would pin a
 * panel that no longer has a second column to sit beside, so it unpins
 * (Task 28, review finding 11).
 *
 * The prototype's fields here were "read-only-styled" because it never saved
 * anything; these are real inputs and selects at the same geometry, with the
 * design system's own focus ring -- a field that looks editable but is not
 * lies about being a control.
 */
export function StageInspector({
  stage,
  stageIndex,
  stages,
  attributes,
  onChange,
  readOnly,
}: {
  stage: StageDraft;
  stageIndex: number;
  /** Every stage in the draft, in order -- BranchRuleCard filters this down to forward-only targets. */
  stages: StageDraft[];
  attributes: AttributeDraft[];
  onChange: (patch: Partial<StageDraft>) => void;
  /** A published version is frozen: browsing its stages must stay possible, editing them must not. */
  readOnly?: boolean;
}) {
  const departments = useDepartments();
  const forwardStagesExist = stageIndex < stages.length - 1;

  function updateBranchRule(index: number, patch: Partial<BranchRuleRequest>) {
    const rules = [...(stage.branchRules ?? [])];
    rules[index] = { ...rules[index], ...patch };
    onChange({ branchRules: rules });
  }

  function removeBranchRule(index: number) {
    onChange({ branchRules: (stage.branchRules ?? []).filter((_, i) => i !== index) });
  }

  function addBranchRule() {
    // ConditionEditor's operator <select> shows "equals" as its unselected
    // default, but a value only a <select> displays is never written into
    // this object -- leaving operator undefined here submits a NULL the
    // database's NOT NULL constraint on branch_rule.operator rejects. Same
    // shape as the milestone dependsOnMilestoneKeys fix: a client-visual
    // default must also be a real one.
    onChange({
      branchRules: [...(stage.branchRules ?? []), { condition: { source: "ATTRIBUTE", operator: "EQ" } }],
    });
  }

  return (
    <div className="xl:sticky xl:top-[76px]">
      <h3
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
          marginBottom: "var(--ob-space-8)",
        }}
      >
        {t("workflow.inspector.title")}
      </h3>
      <p
        className="text-text-primary"
        style={{ font: "600 var(--ob-type-15-size)/var(--ob-type-15-line) var(--ob-font-family-ui)", marginBottom: "var(--ob-space-13)" }}
      >
        {stage.name || t("workflow.stage.unnamed")}
      </p>

      {/* A native fieldset[disabled] cascades to every descendant form control --
          input, select, button -- however deeply nested through MilestoneEditor,
          BranchRuleCard and Switch, without threading a readOnly prop through
          each of them individually. */}
      <fieldset disabled={readOnly} className="flex flex-col" style={{ gap: "var(--ob-space-13)", border: "none", padding: 0, margin: 0 }}>
        <Field
          label={t("workflow.inspector.name")}
          value={stage.name ?? ""}
          onChange={(e) => onChange({ name: e.target.value })}
        />

        <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
          <label
            htmlFor="stage-department"
            className="text-text-secondary"
            style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
          >
            {t("workflow.inspector.department")}
          </label>
          <select
            id="stage-department"
            value={stage.responsibleDepartmentId ?? ""}
            onChange={(e) => onChange({ responsibleDepartmentId: e.target.value || undefined })}
            style={selectStyle}
          >
            <option value="">{t("workflow.inspector.department.none")}</option>
            {(departments.data ?? []).map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </div>

        <Field
          label={t("workflow.inspector.sla")}
          type="number"
          min={0}
          value={stage.slaDays ?? ""}
          onChange={(e) => onChange({ slaDays: e.target.value ? Number(e.target.value) : undefined })}
        />

        <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
          <label
            htmlFor="stage-write-scope"
            className="text-text-secondary"
            style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
          >
            {t("workflow.inspector.writeScope")}
          </label>
          <select
            id="stage-write-scope"
            value={stage.writeScope ?? "ANY"}
            onChange={(e) => onChange({ writeScope: e.target.value as WriteScope })}
            style={selectStyle}
          >
            {WRITE_SCOPES.map((scope) => (
              <option key={scope} value={scope}>
                {t(`workflow.writeScope.${scope}`)}
              </option>
            ))}
          </select>
        </div>

        <Switch
          checked={stage.requiresApproval ?? false}
          onChange={(checked) => onChange({ requiresApproval: checked })}
          label={t("workflow.inspector.requiresApproval")}
        />
        <Switch
          checked={stage.autoAdvance ?? true}
          onChange={(checked) => onChange({ autoAdvance: checked })}
          label={t("workflow.inspector.autoAdvance")}
        />
        <Switch
          checked={stage.portalVisible ?? true}
          onChange={(checked) => onChange({ portalVisible: checked })}
          label={t("workflow.inspector.portalVisible")}
        />

        {/* Authored here, acted on by nothing until sub-project 6 -- a field that
            silently does nothing is worse than one that says so. */}
        <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
          <label
            htmlFor="stage-notification-template"
            className="text-text-secondary"
            style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
          >
            {t("workflow.inspector.notificationTemplate")}
          </label>
          <input id="stage-notification-template" disabled value={stage.notificationTemplateKey ?? ""} style={selectStyle} />
          <p className="text-text-faint" style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-ui)" }}>
            {t("workflow.inspector.notificationTemplate.hint")}
          </p>
        </div>

        <MilestoneEditor
          milestones={stage.milestones ?? []}
          onChange={(milestones) => onChange({ milestones })}
        />

        <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          <h4
            className="text-text-faint"
            style={{
              font: "500 var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
              textTransform: "uppercase",
              letterSpacing: "0.08em",
            }}
          >
            {t("workflow.branch.rulesTitle")}
          </h4>
          {(stage.branchRules ?? []).map((rule, index) => (
            <BranchRuleCard
              key={index}
              stageIndex={stageIndex}
              stages={stages}
              attributes={attributes}
              rule={rule}
              onChange={(patch) => updateBranchRule(index, patch)}
              onRemove={() => removeBranchRule(index)}
            />
          ))}
          {forwardStagesExist && (
            <button
              type="button"
              onClick={addBranchRule}
              className="inline-flex items-center self-start"
              style={{
                gap: "var(--ob-space-6)",
                padding: "var(--ob-space-8) var(--ob-space-11)",
                borderRadius: "var(--ob-radius-chip)",
                border: "1px dashed var(--ob-border-dashed)",
                color: "var(--ob-text-secondary)",
                font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
              }}
            >
              <PlusIcon size={13} />
              {t("workflow.branch.add")}
            </button>
          )}
        </div>
      </fieldset>
    </div>
  );
}

const selectStyle = {
  height: "var(--ob-control-height)",
  borderRadius: "var(--ob-radius-chip)",
  border: "1px solid var(--ob-border-default)",
  background: "var(--ob-bg-surface)",
  padding: "0 var(--ob-space-11)",
  font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
} as const;
