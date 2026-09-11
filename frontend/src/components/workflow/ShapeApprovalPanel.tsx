"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import {
  useDecideShape,
  useSubmitShape,
  type PlanShapeApproval,
  type PlanShapeStage,
} from "@/lib/api/plans";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

const ROLE_BY_STATUS: Record<string, StatusRole> = {
  SUBMITTED: "warn",
  APPROVED: "ok",
  REJECTED: "risk",
};

/**
 * Gate 1 of QA Q22, rendered (sub-project 3A Task 31): the customer-tailored
 * clone's plan SHAPE, submitted once a version is published and decided
 * once -- `PlanShapeService`'s own javadoc. `approval`/`stages` are already
 * fetched by the version editor page (`usePlanShape`, which returns both
 * together in one call, spec's "portal-visible shape and its current
 * approval state"); this panel owns only the two mutations, the same split
 * `ApprovalPanel` already establishes for `journey`'s own approvals.
 *
 * A rejected approval is shown as blocking with the word "Rejected" (never a
 * bare colour, per CLAUDE.md's four decisions) plus the decider's own note,
 * and `workflow.manage` can resubmit from there -- gate 1 resubmission is a
 * new row, never an update to the rejected one (Task 19).
 */
export function ShapeApprovalPanel({
  templateId,
  versionId,
  approval,
  stages = [],
}: {
  templateId: string;
  versionId: string;
  approval?: PlanShapeApproval;
  stages?: PlanShapeStage[];
}) {
  const canManage = useHasPermission("workflow.manage");
  const canDecide = useHasPermission("plan.approve_shape");
  const submit = useSubmitShape();
  const decide = useDecideShape();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();

  function doSubmit() {
    setError(undefined);
    submit.mutate(
      { templateId, versionId },
      { onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")) },
    );
  }

  function doDecide(outcome: "APPROVED" | "REJECTED") {
    setError(undefined);
    decide.mutate(
      { templateId, versionId, outcome, note: note.trim() || undefined },
      { onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")) },
    );
  }

  return (
    <div
      className="flex flex-col bg-surface border border-line"
      style={{ borderRadius: "var(--ob-card-radius)", padding: "var(--ob-space-16)", gap: "var(--ob-space-11)" }}
    >
      <div className="flex items-center justify-between flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
        <h4
          className="text-ink"
          style={{ font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-ui)" }}
        >
          {t("workflow.shape.title")}
        </h4>
        {approval?.status && <StatusPill status={t(`workflow.shape.status.${approval.status}`)} role={ROLE_BY_STATUS[approval.status] ?? "neutral"} />}
      </div>

      <p
        className="text-text-muted"
        style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {t("workflow.shape.description")}
      </p>

      {!approval && (
        <p className="text-text-faint" style={BODY_TEXT}>
          {t("workflow.shape.notSubmitted")}
        </p>
      )}

      {approval?.status === "REJECTED" && (
        <p role="alert" style={{ ...BODY_TEXT, color: "var(--ob-risk-fg)" }}>
          {t("workflow.shape.rejectedBlocking")}
        </p>
      )}

      {approval?.decisionNote && (
        <p className="text-text-muted" style={BODY_TEXT}>
          {approval.decisionNote}
        </p>
      )}

      {(approval?.submittedAt || approval?.decidedAt) && (
        <div className="flex flex-wrap" style={{ gap: "var(--ob-space-16)" }}>
          {approval.submittedAt && (
            <MonoFact label={t("workflow.shape.submittedAt")} value={toDateOnly(approval.submittedAt)} />
          )}
          {approval.decidedAt && (
            <MonoFact label={t("workflow.shape.decidedAt")} value={toDateOnly(approval.decidedAt)} />
          )}
        </div>
      )}

      {canManage && (!approval || approval.status === "REJECTED") && (
        <div className="flex justify-start">
          <Button type="button" variant="secondary" disabled={submit.isPending} onClick={doSubmit}>
            {approval ? t("workflow.shape.resubmit") : t("workflow.shape.submit")}
          </Button>
        </div>
      )}

      {canDecide && approval?.status === "SUBMITTED" && (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          <TextareaField label={t("workflow.shape.decide.note")} value={note} rows={2} onChange={(e) => setNote(e.target.value)} />
          <div className="flex" style={{ gap: "var(--ob-space-8)" }}>
            <Button type="button" disabled={decide.isPending} onClick={() => doDecide("APPROVED")}>
              {t("workflow.shape.decide.approve")}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={decide.isPending}
              style={{ color: "var(--ob-risk-fg)", borderColor: "var(--ob-risk-fg)" }}
              onClick={() => doDecide("REJECTED")}
            >
              {t("workflow.shape.decide.reject")}
            </Button>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" style={{ ...BODY_TEXT, color: "var(--ob-risk-fg)" }}>
          {error}
        </p>
      )}

      <ShapeArtifact stages={stages} />
    </div>
  );
}

/** The portal-visible artifact itself -- what the customer sees when deciding gate 1, already filtered server-side by both a stage's and a milestone's own `portalVisible`. */
function ShapeArtifact({ stages }: { stages: PlanShapeStage[] }) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-8)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("workflow.shape.artifact.title")}
      </h5>

      {stages.length === 0 ? (
        <p className="text-text-faint" style={BODY_TEXT}>
          {t("workflow.shape.artifact.empty")}
        </p>
      ) : (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
          {stages.map((stage) => (
            <div key={stage.id} className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
              <p className="text-ink" style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}>
                {stage.label}
              </p>
              <ul className="flex flex-col" style={{ gap: "var(--ob-space-4)", paddingLeft: "var(--ob-space-16)" }}>
                {(stage.milestones ?? []).map((milestone) => (
                  <li key={milestone.id} className="flex items-center justify-between text-text-2" style={BODY_TEXT}>
                    <span>{milestone.label}</span>
                    {milestone.estimatedDurationDays !== undefined && (
                      <span className="text-text-faint" style={MONO_TEXT}>
                        {t("workflow.shape.artifact.duration", { days: String(milestone.estimatedDurationDays) })}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function MonoFact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-text-faint" style={{ font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)", textTransform: "uppercase", letterSpacing: "var(--ob-type-mono-label-sm-tracking)" }}>
        {label}
      </p>
      <p className="text-ink" style={MONO_TEXT}>{value}</p>
    </div>
  );
}

function toDateOnly(iso: string | undefined): string {
  return iso ? iso.slice(0, 10) : "—";
}

const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;
