"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { StatusPill } from "@/components/ui/StatusPill";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { shortId } from "@/lib/api/customers";
import { useChangeTaskStatus, type Task, type TaskStatus } from "@/lib/api/tasks";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { ChecklistEditor } from "./ChecklistEditor";
import { isTaskOverdue, ROLE_BY_TASK_STATUS } from "./TaskCard";

const STATUS_OPTIONS: TaskStatus[] = ["PENDING", "IN_PROGRESS", "WAITING", "COMPLETED", "CANCELLED"];

/**
 * The fuller single-task view (design spec's Tasks tab detail panel): title,
 * description, a status control, the checklist, due date and assignee.
 * `task` in as a prop -- no data-fetching of its own for the task record
 * itself, matching `TaskCard`'s own discipline, though `ChecklistEditor`
 * (mounted below) does fetch and mutate its own checklist data.
 *
 * The status control is deliberately separate from `ChecklistEditor`: ticking
 * every checklist item never completes the task (see that component's own
 * doc comment) -- only this explicit control, calling `useChangeTaskStatus`,
 * ever changes `Task.status`. There is room below the checklist for Task 29's
 * `CommentThread` to mount alongside it -- this is not a fixed-height panel.
 */
export function TaskDetail({ task }: { task: Task }) {
  const changeStatus = useChangeTaskStatus();
  const canComplete = useHasPermission("task.complete");
  const status = task.status ?? "PENDING";
  const overdue = isTaskOverdue(task);

  const [nextStatus, setNextStatus] = useState<TaskStatus>(status);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string>();

  function submit() {
    if (nextStatus === status || !task.id) return;
    const trimmedReason = reason.trim();
    if (nextStatus === "CANCELLED" && !trimmedReason) {
      setError(t("customer.form.required"));
      return;
    }
    setError(undefined);
    changeStatus.mutate(
      { taskId: task.id, status: nextStatus, reason: nextStatus === "CANCELLED" ? trimmedReason : undefined },
      {
        onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")),
      },
    );
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <div>
        <div className="flex items-start justify-between" style={{ gap: "var(--ob-space-8)" }}>
          {/* A person wrote this title -- Instrument Sans, per CLAUDE.md's human-text rule. */}
          <h4
            className="text-ink"
            style={{ font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)" }}
          >
            {task.title}
          </h4>
          <StatusPill status={t(`task.status.${status}`)} role={ROLE_BY_TASK_STATUS[status]} />
        </div>

        {task.description && (
          <p
            className="text-text-muted"
            style={{
              marginTop: "var(--ob-space-8)",
              font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
            }}
          >
            {task.description}
          </p>
        )}
      </div>

      <div className="grid grid-cols-2" style={{ gap: "var(--ob-space-16)" }}>
        <Fact
          label={t("task.detail.due")}
          value={task.dueDate ?? "—"}
          mono
          appended={overdue ? ` ${t("task.overdue")}` : ""}
          color={overdue ? "var(--ob-risk-fg)" : undefined}
        />
        <Fact
          label={t("task.detail.assignee")}
          value={task.assigneeId ? shortId(task.assigneeId) : t("task.detail.unassigned")}
          mono={Boolean(task.assigneeId)}
        />
      </div>

      {canComplete && (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          <h5
            className="text-text-faint"
            style={{
              font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
              textTransform: "uppercase",
              letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
            }}
          >
            {t("task.statusControl.title")}
          </h5>

          <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
            <select
              aria-label={t("task.statusControl.title")}
              value={nextStatus}
              onChange={(event) => {
                setNextStatus(event.target.value as TaskStatus);
                setError(undefined);
              }}
              style={{
                border: "1px solid var(--ob-line)",
                borderRadius: "var(--ob-radius-9)",
                padding: "9px 11px",
                fontSize: "13px",
                background: "var(--ob-surface)",
                color: "var(--ob-ink)",
                fontFamily: "var(--ob-font-family-ui)",
              }}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {t(`task.status.${option}`)}
                </option>
              ))}
            </select>
            <Button
              type="button"
              variant="secondary"
              disabled={changeStatus.isPending || nextStatus === status}
              onClick={submit}
            >
              {t("task.statusControl.submit")}
            </Button>
          </div>

          {nextStatus === "CANCELLED" && (
            <TextareaField
              label={t("task.statusControl.cancelReason")}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value);
                setError(undefined);
              }}
            />
          )}

          {error && (
            <p
              role="alert"
              style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-small-print-size)/var(--ob-type-small-print-line) var(--ob-font-family-ui)" }}
            >
              {error}
            </p>
          )}
        </div>
      )}

      {task.id && <ChecklistEditor taskId={task.id} />}
    </div>
  );
}

function Fact({
  label,
  value,
  mono = false,
  appended = "",
  color,
}: {
  label: string;
  value: string;
  mono?: boolean;
  appended?: string;
  color?: string;
}) {
  return (
    <div className="min-w-0">
      <p
        className="text-text-faint"
        style={{
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
          font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
        }}
      >
        {label}
      </p>
      <p
        className="text-ink truncate"
        style={{
          marginTop: "var(--ob-space-4)",
          color: color ?? "var(--ob-ink)",
          font: mono
            ? "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-data)"
            : "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
        }}
      >
        {value}
        {appended}
      </p>
    </div>
  );
}
