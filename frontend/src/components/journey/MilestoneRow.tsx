"use client";

import { useState } from "react";
import { CheckIcon, ChevronDownIcon, ExclamationIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { EmptyState } from "@/components/ui/States";
import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import type { Approval, MilestoneRoadmap, Participant } from "@/lib/api/cases";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { ApprovalPanel } from "./ApprovalPanel";
import { ForceCompleteDialog } from "./ForceCompleteDialog";
import { RequirementList } from "./RequirementList";

const ROLE_BY_STATUS: Record<string, StatusRole> = {
  DONE: "ok",
  ACTIVE: "accent",
  BLOCKED: "risk",
  PENDING: "neutral",
  SKIPPED: "neutral",
};

/** uispecs §5a's four circle colours -- SKIPPED gets no mention there, so it takes PENDING's neutral treatment. */
const CIRCLE_COLOR: Record<string, string> = {
  DONE: "var(--ob-solid-on-track)",
  ACTIVE: "var(--ob-accent)",
  BLOCKED: "var(--ob-solid-blocked)",
  PENDING: "var(--ob-paper-500)",
  SKIPPED: "var(--ob-paper-500)",
};

/**
 * The expandable unit of the journey tab (uispecs §5a). `participants` and
 * `approvals` are the whole case's, fetched once by `Roadmap` and passed
 * down, rather than one lookup per row -- the roadmap can hold dozens of
 * milestones across nine stages.
 */
export function MilestoneRow({
  caseId,
  milestone,
  participants,
  approvals,
}: {
  caseId: string;
  milestone: MilestoneRoadmap;
  participants: Participant[];
  approvals: Approval[];
}) {
  const [expanded, setExpanded] = useState(false);
  const [forcingComplete, setForcingComplete] = useState(false);
  const canForceComplete = useHasPermission("milestone.force_complete");

  const status = milestone.status ?? "PENDING";
  const owner = participants.find((p) => p.userId === milestone.ownerUserId)?.fullName;
  const blockedBy = milestone.blockedByMilestoneNames ?? [];
  const pendingApproval = approvals.find(
    (a) => a.kind === "FORCE_COMPLETE" && a.milestoneId === milestone.id && a.status === "PENDING",
  );
  const canRequestForceComplete = canForceComplete && status !== "DONE" && status !== "SKIPPED" && !pendingApproval;
  const open = status !== "DONE" && status !== "SKIPPED";
  // ISO dates sort lexically, so a plain string compare avoids a Date() timezone
  // shift landing the boundary on the wrong day (CaseHeader's own toDateOnly note).
  const overdue = open && Boolean(milestone.dueDate) && milestone.dueDate! < todayIso();

  return (
    <div
      data-testid="milestone-row"
      className="bg-bg-surface"
      style={{
        borderRadius: "var(--ob-radius-card)",
        border: `1px solid var(--ob-paper-${expanded ? "450" : "400"})`,
        boxShadow: expanded ? "var(--ob-elevation-raised)" : "var(--ob-elevation-flat)",
      }}
    >
      <button
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((prev) => !prev)}
        className="w-full grid items-center text-left bg-transparent border-none cursor-pointer"
        style={{ gridTemplateColumns: "26px 1fr auto", gap: "var(--ob-space-11)", padding: "14px 18px" }}
      >
        <span
          aria-hidden="true"
          className="inline-flex items-center justify-center"
          style={{ width: 26, height: 26, borderRadius: "50%", background: CIRCLE_COLOR[status] ?? CIRCLE_COLOR.PENDING }}
        >
          {status === "DONE" && <CheckIcon size={14} style={{ color: "var(--ob-text-on-solid)" }} />}
          {status === "BLOCKED" && <ExclamationIcon size={14} style={{ color: "var(--ob-text-on-solid)" }} />}
        </span>

        <div className="min-w-0">
          <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
            <span
              className="text-text-primary truncate"
              style={{ font: "600 var(--ob-type-13-5-size)/var(--ob-type-13-5-line) var(--ob-font-family-ui)" }}
            >
              {milestone.name}
            </span>
            <StatusPill status={t(`milestone.status.${status}`)} role={ROLE_BY_STATUS[status] ?? "neutral"} />
            {status === "BLOCKED" && blockedBy.length > 0 && (
              <span
                style={{
                  color: "var(--ob-status-blocked-fg)",
                  font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
                }}
              >
                {t("milestone.blockedBy", { names: blockedBy.join(", ") })}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center flex-shrink-0" style={{ gap: "var(--ob-space-16)" }}>
          <div className="text-right">
            <p
              style={{
                font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)",
                color: overdue ? "var(--ob-status-blocked-fg)" : "var(--ob-text-primary)",
              }}
            >
              {milestone.dueDate ?? "—"}
              {overdue && ` ${t("milestone.overdue")}`}
            </p>
            <p
              className="text-text-faint"
              style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
            >
              {owner ?? t("milestone.noOwner")}
            </p>
          </div>

          <div style={{ width: 74 }}>
            <ProgressBar value={milestone.progressPercent ?? 0} label={t("milestone.progressLabel", { name: milestone.name ?? "" })} />
          </div>

          <span
            aria-hidden="true"
            className="inline-flex"
            style={{ transform: expanded ? "rotate(180deg)" : "rotate(0deg)", transition: "transform var(--ob-duration-fast) ease" }}
          >
            <ChevronDownIcon size={16} />
          </span>
        </div>
      </button>

      {expanded && (
        // Below 1024px the two columns stack -- review finding 11's other
        // casualty -- rather than staying side by side at a width too narrow
        // for either to read.
        <div
          className="grid grid-cols-1 lg:grid-cols-[1.4fr_1fr]"
          style={{
            borderTop: "1px solid var(--ob-paper-300)",
            padding: "16px 18px 18px 58px",
            gap: "22px",
            animation: "var(--ob-keyframe-enter) var(--ob-duration-instant) ease",
          }}
        >
          <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
            <RequirementList caseId={caseId} milestoneId={milestone.id!} requirements={milestone.requirements ?? []} />

            {pendingApproval && <ApprovalPanel caseId={caseId} approval={pendingApproval} />}

            {canRequestForceComplete && (
              <Button type="button" variant="secondary" onClick={() => setForcingComplete(true)}>
                {t("milestone.forceComplete")}
              </Button>
            )}
          </div>

          <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
            <Section title={t("milestone.dependencies.title")}>
              <p
                className="text-text-muted"
                style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
              >
                {blockedBy.length > 0
                  ? t("milestone.blockedBy", { names: blockedBy.join(", ") })
                  : t("milestone.dependencies.none")}
              </p>
            </Section>

            {/* Comments are sub-project 3 -- this is an honest empty state, not the
                prototype's dashed "write a comment" affordance, which would promise
                a feature that does not exist yet. */}
            <Section title={t("milestone.comments.title")}>
              <EmptyState title={t("milestone.comments.empty")} />
            </Section>
          </div>
        </div>
      )}

      {forcingComplete && (
        <ForceCompleteDialog caseId={caseId} milestoneId={milestone.id!} onClose={() => setForcingComplete(false)} />
      )}
    </div>
  );
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
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
        {title}
      </h5>
      {children}
    </div>
  );
}
