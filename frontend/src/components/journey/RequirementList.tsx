"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { StatusPill } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, useSatisfy, useWaive, type RequirementRoadmap } from "@/lib/api/cases";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The milestone panel's left column (uispecs §5a). The one deliberate
 * departure from the prototype: its checkbox state is "real and local", but
 * here satisfying a requirement recomputes the milestone, possibly the stage
 * transition and the case percentage, inside one locked server transaction --
 * local optimism would show a milestone completing that write_scope then
 * refuses. Every checkbox waits for the response before it changes.
 *
 * A write_scope 403 renders as an explanation next to the row it refused, not
 * a disappearance -- the status exists precisely so the UI can say "this
 * stage is owner-only" rather than the requirement looking like it vanished,
 * which is what a 404 would have meant here.
 */
export function RequirementList({
  caseId,
  milestoneId,
  requirements,
}: {
  caseId: string;
  milestoneId: string;
  requirements: RequirementRoadmap[];
}) {
  const canWaive = useHasPermission("requirement.waive");
  const satisfy = useSatisfy();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [waiving, setWaiving] = useState<string | undefined>();
  // The parent's roadmap query invalidates and refetches on success, but that
  // round trip is a second request -- this reflects the satisfy response the
  // instant it arrives, so the checkbox does not sit unchecked in between.
  const [locallySatisfied, setLocallySatisfied] = useState<Set<string>>(new Set());

  function toggle(requirementId: string) {
    setErrors((prev) => ({ ...prev, [requirementId]: "" }));
    satisfy.mutate(
      { caseId, requirementId },
      {
        onSuccess: () => setLocallySatisfied((prev) => new Set(prev).add(requirementId)),
        onError: (error) => {
          const message = error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
          setErrors((prev) => ({ ...prev, [requirementId]: message }));
        },
      },
    );
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
        }}
      >
        {t("requirement.sectionTitle")}
      </h5>

      {requirements.map((requirement) => {
        if (requirement.kind === "DOCUMENT") {
          return <DocumentChip key={requirement.id} requirement={requirement} />;
        }

        const settled = requirement.status !== "OPEN" || locallySatisfied.has(requirement.id!);
        const pending = satisfy.isPending && satisfy.variables?.requirementId === requirement.id;

        return (
          <div key={requirement.id} className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
            <div className="flex items-center justify-between" style={{ gap: "var(--ob-space-8)" }}>
              <label className="inline-flex items-center" style={{ gap: "var(--ob-space-8)" }}>
                <input
                  type="checkbox"
                  checked={settled}
                  disabled={settled || pending}
                  onChange={() => toggle(requirement.id!)}
                />
                <span
                  className="text-text-primary"
                  style={{
                    font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
                    textDecoration: settled ? "line-through" : "none",
                    color: settled ? "var(--ob-text-disabled)" : "var(--ob-text-primary)",
                  }}
                >
                  {requirement.label}
                </span>
              </label>

              {canWaive && requirement.status === "OPEN" && !settled && (
                <Button type="button" variant="secondary" onClick={() => setWaiving(requirement.id)}>
                  {t("requirement.waive")}
                </Button>
              )}
            </div>

            {errors[requirement.id!] && (
              <p role="alert" style={{ color: "var(--ob-status-blocked-fg)", font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-ui)" }}>
                {errors[requirement.id!]}
              </p>
            )}
          </div>
        );
      })}

      {waiving && (
        <WaiveDialog
          caseId={caseId}
          requirementId={waiving}
          onClose={() => setWaiving(undefined)}
        />
      )}
    </div>
  );
}

function DocumentChip({ requirement }: { requirement: RequirementRoadmap }) {
  return (
    <div
      className="flex items-center justify-between bg-bg-surface border border-border-default"
      style={{ padding: "var(--ob-space-8) var(--ob-space-11)", borderRadius: "var(--ob-radius-chip)" }}
    >
      <span className="text-text-primary" style={{ font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}>
        {requirement.label}
      </span>
      <StatusPill
        status={requirement.status === "OPEN" ? t("requirement.status.OPEN") : t(`requirement.status.${requirement.status}`)}
        role={requirement.status === "OPEN" ? "neutral" : "ok"}
      />
    </div>
  );
}

function WaiveDialog({ caseId, requirementId, onClose }: { caseId: string; requirementId: string; onClose: () => void }) {
  const waive = useWaive();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  return (
    <Dialog title={t("requirement.waiveDialog.title")} onClose={onClose}>
      <Field
        label={t("requirement.waiveDialog.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />

      {waive.isError && (
        <p role="alert" style={{ color: "var(--ob-status-blocked-fg)", marginTop: "var(--ob-space-11)", font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}>
          {waive.error instanceof ApiError ? parseProblemDetail(waive.error.message) : t("common.error")}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button
          type="button"
          disabled={waive.isPending}
          onClick={() => {
            const trimmed = reason.trim();
            if (!trimmed) {
              setReasonError(t("customer.form.required"));
              return;
            }
            waive.mutate({ caseId, requirementId, reason: trimmed }, { onSuccess: onClose });
          }}
        >
          {t("requirement.waiveDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
