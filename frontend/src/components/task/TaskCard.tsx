import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import type { Task, TaskPriority, TaskStatus } from "@/lib/api/tasks";
import { t } from "@/lib/i18n";

/**
 * Status -> semantic role (DESIGN_TOKENS.md's five state pairs). The design's
 * own example list (BLOCKED, WAITING, IN PROGRESS, PENDING, COMPLETED) is
 * illustrative of the chip STYLE, not literal -- our real `TaskStatus` enum
 * has no BLOCKED (a milestone-only concept) and adds CANCELLED, which the
 * example never enumerates at all.
 *
 * CANCELLED maps to `neutral`, the same role PENDING gets: "not started,
 * archived, no state" is the closest fit for a task nobody is doing anymore
 * and nothing further will happen to -- `risk` was the other candidate, but
 * risk is reserved here for a task that is overdue (see `isOverdue` below),
 * and a cancelled task is never overdue (it is no longer open), so the two
 * meanings would otherwise collide on the same colour for different reasons.
 */
export const ROLE_BY_TASK_STATUS: Record<TaskStatus, StatusRole> = {
  PENDING: "neutral",
  IN_PROGRESS: "accent",
  WAITING: "warn",
  COMPLETED: "ok",
  CANCELLED: "neutral",
};

/**
 * Priority has no dedicated token pair of its own (DESIGN_TOKENS.md's five
 * roles are all state-shaped, not urgency-shaped) -- so this is a judgment
 * call, not a spec lookup. `risk` is deliberately withheld from HIGH: risk is
 * reserved for an actually-overdue due date (CLAUDE.md's "colour always means
 * status," and the brief's own "Overdue is risk-coloured AND labelled") --
 * reusing it for "high priority, not yet late" would make the same red mean
 * two different things on the same card. `warn` reads as "pay attention," a
 * reasonable fit for HIGH without overloading risk's one meaning.
 */
export const ROLE_BY_TASK_PRIORITY: Record<TaskPriority, StatusRole> = {
  LOW: "neutral",
  MEDIUM: "info",
  HIGH: "warn",
};

/** Open (not COMPLETED/CANCELLED) with a due date in the past -- MilestoneRow's own overdue calculation, applied to a task instead of a milestone. */
export function isTaskOverdue(task: Pick<Task, "status" | "dueDate">): boolean {
  const open = task.status !== "COMPLETED" && task.status !== "CANCELLED";
  return open && Boolean(task.dueDate) && task.dueDate! < todayIso();
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * A compact task summary (design spec's Tasks tab DataTable row, reshaped as
 * a card so it also works as Task 28's `WorkBoard` column item). Props in,
 * no data-fetching of its own -- it must not assume it only ever renders
 * inside a Tasks-tab list.
 *
 * `context` and `customerName` are Task 28's own narrow addition (SCREENS.md
 * §5: "title 12.5px/600, context 11.5px, then a chip + customer name") --
 * both optional and both undefined in every existing call site (`TasksTab`),
 * so a task already inside its own case's tab, where the case is implied by
 * the screen, renders exactly as it did before this task. `WorkBoard` is the
 * only caller that supplies them, since it is the only view spanning more
 * than one case.
 */
export function TaskCard({
  task,
  context,
  customerName,
  onClick,
}: {
  task: Task;
  context?: string;
  customerName?: string;
  onClick?: () => void;
}) {
  const status = task.status ?? "PENDING";
  const overdue = isTaskOverdue(task);
  const interactive = Boolean(onClick);

  return (
    <div
      data-testid="task-card"
      role={interactive ? "button" : undefined}
      tabIndex={interactive ? 0 : undefined}
      onClick={onClick}
      onKeyDown={
        interactive
          ? (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onClick?.();
              }
            }
          : undefined
      }
      className="bg-surface border border-line flex flex-col"
      style={{
        borderRadius: "var(--ob-card-radius)",
        boxShadow: "var(--ob-shadow-card)",
        padding: "12px 14px",
        gap: "var(--ob-space-8)",
        cursor: interactive ? "pointer" : "default",
      }}
    >
      <div className="flex items-start justify-between" style={{ gap: "var(--ob-space-8)" }}>
        {/* A person wrote this title -- Instrument Sans, per CLAUDE.md's human-text rule. */}
        <span
          className="text-ink min-w-0"
          style={{ font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-ui)" }}
        >
          {task.title}
        </span>
        <StatusPill status={t(`task.status.${status}`)} role={ROLE_BY_TASK_STATUS[status]} />
      </div>

      {/* Which case this task lives in -- only meaningful once a card can appear
          alongside cards from OTHER cases, i.e. only on WorkBoard. */}
      {context && (
        <span
          className="text-text-subtle truncate"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {context}
        </span>
      )}

      <div className="flex items-center justify-between flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
        <div className="flex items-center min-w-0" style={{ gap: "var(--ob-space-8)" }}>
          {task.priority && (
            <StatusPill status={t(`task.priority.${task.priority}`)} role={ROLE_BY_TASK_PRIORITY[task.priority]} />
          )}
          {customerName && (
            <span
              className="text-text-subtle truncate"
              style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
            >
              {customerName}
            </span>
          )}
        </div>

        {/* Due dates are machine-generated values -- Spline Sans Mono, per CLAUDE.md's data-font rule -- unlike the title above. */}
        <span
          style={{
            font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
            color: overdue ? "var(--ob-risk-fg)" : "var(--ob-text-faint)",
          }}
        >
          {task.dueDate ?? "—"}
          {overdue && ` ${t("task.overdue")}`}
        </span>
      </div>
    </div>
  );
}
