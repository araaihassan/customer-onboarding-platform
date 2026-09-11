import { PauseCircleIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { t } from "@/lib/i18n";

/**
 * Q22/Q23's gate-2 hold, explained (sub-project 3A Task 31). A case on a
 * customer-owned template starts (and can sit) `ON_HOLD` purely because its
 * first schedule revision has never been approved -- `CaseOnHoldException`
 * blocks every requirement check underneath, and a held case that says
 * nothing about why reads as broken, not paused. `warn` (the same role
 * `StatusPill` already gives `ON_HOLD`) plus the word "on hold" is the
 * colour-and-word pairing CLAUDE.md's four decisions require.
 *
 * Two states, not one: no revision has ever been issued yet (an action is
 * missing, so this offers it) versus one is already issued and only the
 * customer's decision is outstanding (nothing left for an internal actor to
 * start, only to check on) -- `hasOutstandingRevision` is the caller's own
 * derived fact (`usePlanRevisions` data, `some(r => r.status === "ISSUED")`),
 * not refetched here.
 */
export function AwaitingApprovalBanner({
  caseId,
  hasOutstandingRevision,
  onAction,
}: {
  caseId: string;
  hasOutstandingRevision: boolean;
  onAction?: () => void;
}) {
  return (
    <div
      data-testid={`awaiting-approval-banner-${caseId}`}
      role="status"
      className="flex items-start flex-wrap"
      style={{
        gap: "var(--ob-space-13)",
        padding: "var(--ob-space-13) var(--ob-space-16)",
        borderRadius: "var(--ob-radius-10)",
        background: "var(--ob-warn-bg)",
        border: "1px solid var(--ob-warn-border)",
      }}
    >
      <span aria-hidden="true" style={{ color: "var(--ob-warn-fg)", flexShrink: 0 }}>
        <PauseCircleIcon size={20} />
      </span>

      <div className="min-w-0 flex-1">
        <p
          className="text-ink"
          style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {t("case.awaitingApproval.title")}
        </p>
        <p
          className="text-text-muted"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)", marginTop: 2 }}
        >
          {hasOutstandingRevision
            ? t("case.awaitingApproval.pendingDecision")
            : t("case.awaitingApproval.needsRevision")}
        </p>
      </div>

      <Button type="button" variant="secondary" onClick={onAction} style={{ flexShrink: 0 }}>
        {hasOutstandingRevision ? t("case.awaitingApproval.reviewAction") : t("plan.revision.issue")}
      </Button>
    </div>
  );
}
