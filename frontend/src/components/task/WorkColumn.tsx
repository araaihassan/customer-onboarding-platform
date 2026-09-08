import type { StatusRole } from "@/components/ui/StatusPill";
import type { Task } from "@/lib/api/tasks";
import { TaskCard } from "./TaskCard";

/**
 * One bucket of the cross-case "My work" board (`WorkBoard`, Task 28;
 * SCREENS.md §5: "Same column and card treatment as the stage board").
 *
 * Column-level colour is a SEPARATE signal from `TaskCard`'s own per-task
 * status chip -- `role` here is the BUCKET's semantic colour (risk/accent/
 * info/ok, one per column, per §5), never derived from the tasks inside it.
 * Paired with the title text on every render (CLAUDE.md invariant #4:
 * colour is never the only signal), never relied on alone.
 *
 * Each column owns its own empty-state copy rather than sharing one board-
 * level `EmptyState` -- the brief is explicit that an empty "Do now" reads as
 * good news and an empty "Waiting" reads as merely neutral, so a single
 * generic "No tasks" string would flatten a distinction the design wants
 * kept. `EmptyState` itself (ui/States.tsx) is still built for a whole
 * screen having nothing in it -- this is the smaller, inline version that
 * fits inside one column of several.
 */
export function WorkColumn({
  id,
  title,
  role,
  tasks,
  emptyTitle,
  emptyDescription,
  contextFor,
}: {
  /** The bucket id (`do_now`, etc.) -- attached only as a `data-testid` seam, never rendered. */
  id: string;
  title: string;
  role: StatusRole;
  tasks: Task[];
  emptyTitle: string;
  emptyDescription: string;
  contextFor: (task: Task) => { caseName?: string; customerName?: string };
}) {
  return (
    <div
      data-testid={`work-column-${id}`}
      className="bg-surface border border-line flex flex-col"
      style={{
        borderRadius: "var(--ob-card-radius)",
        boxShadow: "var(--ob-shadow-card)",
        overflow: "hidden",
        minWidth: 0,
      }}
    >
      <div
        className="flex items-center justify-between"
        style={{
          padding: "12px 14px 11px",
          borderBottom: "1px solid var(--ob-line-soft)",
          borderTop: `3px solid var(--ob-${role}-fg)`,
        }}
      >
        <h3
          className="text-ink"
          style={{ font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-ui)" }}
        >
          {title}
        </h3>
        <span
          data-testid="work-column-count"
          style={{
            font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
            color: `var(--ob-${role}-fg)`,
          }}
        >
          {tasks.length}
        </span>
      </div>

      <div className="flex flex-col" style={{ padding: "10px", gap: "var(--ob-space-8)", flex: 1 }}>
        {tasks.length === 0 ? (
          <div className="text-center" style={{ padding: "var(--ob-space-20) var(--ob-space-11)" }}>
            <p
              className="text-text-subtle"
              style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
            >
              {emptyTitle}
            </p>
            <p
              className="text-text-faint"
              style={{
                marginTop: "var(--ob-space-4)",
                font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
              }}
            >
              {emptyDescription}
            </p>
          </div>
        ) : (
          tasks.map((task) => {
            const ctx = contextFor(task);
            return <TaskCard key={task.id} task={task} context={ctx.caseName} customerName={ctx.customerName} />;
          })
        )}
      </div>
    </div>
  );
}
