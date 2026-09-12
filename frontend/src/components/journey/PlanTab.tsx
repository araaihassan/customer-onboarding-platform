"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { useIssueRevision, usePlanRevisions } from "@/lib/api/plans";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { PlanRevisionDiff } from "./PlanRevisionDiff";
import { PlanRevisionList } from "./PlanRevisionList";

/** A milestone as the Plan tab needs to know it: enough to preview what a new revision will snapshot, nothing else. `portalVisible` already folds in both the milestone's own flag and its stage's (a hidden stage hides every milestone under it too, `PlanRevisionService.issue`'s own rule) -- computed once by whoever assembles this list, not re-derived here. */
export interface PlanPreviewMilestone {
  id: string;
  name: string;
  portalVisible?: boolean;
}

/**
 * Gate 2's own tab in the case workspace (sub-project 3A Task 31, Q22/Q23) --
 * mounted only for a case on a customer-owned template. Owns the revision
 * list's data (`usePlanRevisions`) and the issue flow; `milestones` is the
 * one piece of data this tab cannot fetch for itself (no roadmap or case
 * endpoint carries a milestone's authoring-time `portalVisible` flag), so
 * the case workspace page passes it down from the pinned version's own
 * definition.
 *
 * The diff always compares the two most recent revisions when at least two
 * exist -- no revision-picker UI, since nothing in the brief calls for
 * comparing an arbitrary pair and the common case (what changed since the
 * last issue) is what an approver actually needs.
 */
export function PlanTab({ caseId, milestones }: { caseId: string; milestones: PlanPreviewMilestone[] }) {
  const revisions = usePlanRevisions(caseId);
  const canIssue = useHasPermission("plan.issue");
  const canDecide = useHasPermission("plan.approve_schedule");
  const [issuing, setIssuing] = useState(false);

  if (revisions.isLoading) return <SkeletonRows rows={3} height={72} />;
  if (revisions.isError) {
    return <ErrorState message={t("common.error")} onRetry={() => void revisions.refetch()} />;
  }

  const list = revisions.data ?? [];
  const [latest, previous] = list;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
      {canIssue && (
        <div className="flex justify-end">
          <Button type="button" variant="secondary" onClick={() => setIssuing(true)}>
            {t("plan.revision.issue")}
          </Button>
        </div>
      )}

      <PlanRevisionList caseId={caseId} revisions={list} canDecide={canDecide} />

      {latest?.id && previous?.id && (
        <PlanRevisionDiff caseId={caseId} revisionId={latest.id} againstId={previous.id} />
      )}

      {issuing && (
        <IssueRevisionDialog caseId={caseId} milestones={milestones} onClose={() => setIssuing(false)} />
      )}
    </div>
  );
}

/**
 * The issue preview (brief's own emphasis: "previews exactly what will be
 * snapshotted before issuing"). Shows only the portal-visible milestones --
 * the same filter `PlanRevisionService.issue` applies server-side -- so a
 * caller never signs off on a list broader than what the customer will
 * actually be asked to approve.
 */
function IssueRevisionDialog({
  caseId,
  milestones,
  onClose,
}: {
  caseId: string;
  milestones: PlanPreviewMilestone[];
  onClose: () => void;
}) {
  const issue = useIssueRevision();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();

  const visible = milestones.filter((m) => m.portalVisible !== false);

  function submit() {
    setError(undefined);
    issue.mutate(
      { caseId, note: note.trim() || undefined },
      {
        onSuccess: onClose,
        onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")),
      },
    );
  }

  return (
    <Dialog title={t("plan.revision.issueDialog.title")} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <p className="text-text-muted" style={BODY_TEXT}>
          {t("plan.revision.issueDialog.description")}
        </p>

        {visible.length === 0 ? (
          <p className="text-text-faint" style={BODY_TEXT}>
            {t("plan.revision.issueDialog.empty")}
          </p>
        ) : (
          <ul className="flex flex-col" style={{ gap: "var(--ob-space-4)", paddingLeft: "var(--ob-space-16)" }}>
            {visible.map((milestone) => (
              <li key={milestone.id} className="text-ink" style={{ ...BODY_TEXT, listStyle: "disc" }}>
                {milestone.name}
              </li>
            ))}
          </ul>
        )}

        <TextareaField
          label={t("plan.revision.issueDialog.note")}
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />

        {error && (
          <p role="alert" style={{ ...BODY_TEXT, color: "var(--ob-risk-fg)" }}>
            {error}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={issue.isPending} onClick={submit}>
          {t("plan.revision.issueDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;
