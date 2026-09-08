"use client";

import { useState } from "react";
import { CheckSquareIcon, PlusIcon, SquareIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useAddChecklistItem, useChecklist, useToggleChecklistItem } from "@/lib/api/tasks";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * A task's checklist -- a private aid, never a second progress mechanism
 * (ChecklistService's own doc comment; CLAUDE.md's "what sub-project 3
 * inherits"). Toggling an item here calls ONLY `useToggleChecklistItem`,
 * never `useChangeTaskStatus` or anything status-shaped -- completing a task
 * is `TaskDetail`'s own separate, explicit status control. This is the
 * backend rule from Task 22 (`ChecklistService.toggle` never calls
 * `CaseEngine.reconcile`/`RequirementService`/writes `Task.status`) asserted
 * again at the UI so the two layers cannot drift apart.
 *
 * Self-contained (fetches and mutates its own data) rather than prop-driven
 * like `TaskCard` -- `TaskDetail` only needs to hand it a taskId, leaving
 * room below it for Task 29's `CommentThread` to mount alongside.
 */
export function ChecklistEditor({ taskId }: { taskId: string }) {
  const checklist = useChecklist(taskId);
  const addItem = useAddChecklistItem();
  const toggleItem = useToggleChecklistItem();
  const canManage = useHasPermission("task.manage");
  const canComplete = useHasPermission("task.complete");
  const [label, setLabel] = useState("");
  const [error, setError] = useState<string>();

  function toggle(itemId: string | undefined) {
    if (!itemId) return;
    setError(undefined);
    toggleItem.mutate(itemId, {
      onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")),
    });
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("task.checklist.title")}
      </h5>

      {checklist.isLoading && <SkeletonRows rows={2} height={22} />}

      {checklist.isError && (
        <ErrorState message={t("common.error")} onRetry={() => void checklist.refetch()} />
      )}

      {checklist.isSuccess && (checklist.data?.length ?? 0) === 0 && (
        <p
          className="text-text-faint"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {t("task.checklist.empty")}
        </p>
      )}

      {checklist.data?.map((item) => {
        const pending = toggleItem.isPending && toggleItem.variables === item.id;
        return (
          <div key={item.id} className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
            <button
              type="button"
              aria-pressed={item.done}
              aria-label={item.label}
              disabled={!canComplete || pending}
              onClick={() => toggle(item.id)}
              className="inline-flex items-center bg-transparent border-none"
              style={{
                padding: 0,
                color: item.done ? "var(--ob-ok-fg)" : "var(--ob-text-faint)",
                cursor: canComplete ? "pointer" : "not-allowed",
              }}
            >
              {item.done ? <CheckSquareIcon size={16} /> : <SquareIcon size={16} />}
            </button>
            <span
              className="text-ink"
              style={{
                font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
                textDecoration: item.done ? "line-through" : "none",
                color: item.done ? "var(--ob-text-faint)" : "var(--ob-ink)",
              }}
            >
              {item.label}
            </span>
          </div>
        );
      })}

      {canManage && (
        <form
          className="flex items-end"
          style={{ gap: "var(--ob-space-8)" }}
          onSubmit={(event) => {
            event.preventDefault();
            const trimmed = label.trim();
            if (!trimmed) return;
            setError(undefined);
            addItem.mutate(
              { taskId, label: trimmed },
              {
                onSuccess: () => setLabel(""),
                onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")),
              },
            );
          }}
        >
          <div style={{ flex: 1 }}>
            <Field
              label={t("task.checklist.addLabel")}
              value={label}
              onChange={(event) => setLabel(event.target.value)}
            />
          </div>
          <Button type="submit" variant="small-secondary" disabled={addItem.isPending || !label.trim()}>
            <PlusIcon size={14} />
            {t("common.add")}
          </Button>
        </form>
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
  );
}
