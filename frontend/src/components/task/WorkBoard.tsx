"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { useMyWork, useWorkContexts, type MyWorkBucket, type Task, type TaskStatus } from "@/lib/api/tasks";
import type { StatusRole } from "@/components/ui/StatusPill";
import { t } from "@/lib/i18n";
import { WorkColumn } from "./WorkColumn";

/**
 * The four columns, in the fixed order SCREENS.md §5 lists them, each paired
 * with the `TaskStatus` it groups and the semantic role its column takes.
 *
 * `status` is what does the grouping -- NOT a second call to `useMyWork` per
 * bucket. `TaskService.myWork(null)` (Task 24's own backend, read directly
 * before writing this) already returns exactly these four statuses in one
 * response, with COMPLETED pre-filtered server-side to the trailing 7 days
 * against the SERVER's clock, so grouping the one response by `task.status`
 * is the primary mechanism and the source of truth stays server-side.
 *
 * `tasksForBucket` below adds ONE deliberate exception: a redundant,
 * client-side re-check of that same 7-day window for COMPLETED only. This
 * is defense in depth, not a second source of truth -- a correctly-behaving
 * `myWork(null)` response never has anything for it to catch, and a small
 * client/server clock skew only ever matters at the boundary day either way.
 * It exists so a caller of `useMyWork` that changes shape later (a raw
 * `bucket` passed through without the server's own filtering, a mock in a
 * test, a future caller that forgets `myWork`'s own contract) fails toward
 * "hide a stale completion" rather than "show one indefinitely" -- the
 * safer of the two given the headline this screen makes ("Only what you can
 * act on"). `WorkBoard.test.tsx` proves this against a hand-built COMPLETED
 * task 10 days old that a real backend would never actually send.
 */
const BUCKETS: ReadonlyArray<{
  id: MyWorkBucket;
  status: TaskStatus;
  role: StatusRole;
  titleKey: string;
  emptyTitleKey: string;
  emptyDescriptionKey: string;
}> = [
  {
    id: "do_now",
    status: "PENDING",
    role: "risk",
    titleKey: "work.column.doNow.title",
    emptyTitleKey: "work.column.doNow.empty.title",
    emptyDescriptionKey: "work.column.doNow.empty.description",
  },
  {
    id: "in_progress",
    status: "IN_PROGRESS",
    role: "accent",
    titleKey: "work.column.inProgress.title",
    emptyTitleKey: "work.column.inProgress.empty.title",
    emptyDescriptionKey: "work.column.inProgress.empty.description",
  },
  {
    id: "waiting",
    status: "WAITING",
    role: "info",
    titleKey: "work.column.waiting.title",
    emptyTitleKey: "work.column.waiting.empty.title",
    emptyDescriptionKey: "work.column.waiting.empty.description",
  },
  {
    id: "done_this_week",
    status: "COMPLETED",
    role: "ok",
    titleKey: "work.column.doneThisWeek.title",
    emptyTitleKey: "work.column.doneThisWeek.empty.title",
    emptyDescriptionKey: "work.column.doneThisWeek.empty.description",
  },
];

function isBucket(value: string | null): value is MyWorkBucket {
  return BUCKETS.some((bucket) => bucket.id === value);
}

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

/** See the `BUCKETS` doc comment above -- the one deliberate, redundant re-check. */
function isRecentlyCompleted(task: Task): boolean {
  if (!task.completedAt) return false;
  return Date.now() - new Date(task.completedAt).getTime() <= SEVEN_DAYS_MS;
}

function tasksForBucket(tasks: Task[], status: TaskStatus): Task[] {
  const inStatus = tasks.filter((task) => task.status === status);
  return status === "COMPLETED" ? inStatus.filter(isRecentlyCompleted) : inStatus;
}

/**
 * The cross-case "My work" board (Task 28, SCREENS.md §5). Four columns,
 * always the caller's own tasks (`useMyWork`'s own `assignee=me`), CANCELLED
 * excluded everywhere -- "Only what you can act on."
 *
 * The `bucket` search param is this screen's filter: absent, it shows all
 * four columns (one `useMyWork({})` call, grouped client-side -- see the
 * `BUCKETS` doc comment); set to one of the four bucket ids, it narrows to
 * that single column via a second, server-filtered `useMyWork({ bucket })`
 * call and the query itself changes, not just what's rendered. Same
 * `useSearchParams`/`router.replace` convention the case workspace page
 * uses for its own `tab` param, so a filtered queue is a shareable URL and
 * the back button returns to the unfiltered board.
 *
 * Cards render read-only here (no `onClick`, unlike `TasksTab`'s use of the
 * same `TaskCard`): `useChangeTaskStatus`'s own cache invalidation only
 * covers the task's case list and roadmap, not this board's `taskKeys.mine`
 * query, so wiring a status-change control in from here would leave a task
 * showing in a column it just left until something else happened to
 * refetch this screen. Fixing that cache gap is a `tasks.ts` change outside
 * this task's own scope, not a `WorkBoard` concern -- worth revisiting
 * before this board grows a status control of its own.
 */
export function WorkBoard() {
  const router = useRouter();
  const pathname = usePathname();
  const bucketParam = useSearchParams().get("bucket");
  const activeBucket = isBucket(bucketParam) ? bucketParam : null;

  const work = useMyWork(activeBucket ? { bucket: activeBucket } : {});
  const tasks = work.data ?? [];
  const contexts = useWorkContexts(tasks);

  function setBucket(next: MyWorkBucket | null) {
    router.replace(next ? `${pathname}?bucket=${next}` : pathname);
  }

  const visibleColumns = activeBucket ? BUCKETS.filter((bucket) => bucket.id === activeBucket) : BUCKETS;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
      <div>
        <p
          className="text-ink"
          style={{
            font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)",
            letterSpacing: "var(--ob-type-section-heading-tracking)",
          }}
        >
          {t("work.headline")}
        </p>
        <p
          className="text-text-subtle"
          style={{
            marginTop: "var(--ob-space-4)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {t("work.sub")}
        </p>
      </div>

      <div
        role="group"
        aria-label={t("work.filter.label")}
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-6)" }}
      >
        <Button
          type="button"
          variant={activeBucket === null ? "filter-active" : "filter-idle"}
          aria-pressed={activeBucket === null}
          onClick={() => setBucket(null)}
        >
          {t("work.filter.all")}
        </Button>
        {BUCKETS.map((bucket) => (
          <Button
            key={bucket.id}
            type="button"
            variant={activeBucket === bucket.id ? "filter-active" : "filter-idle"}
            aria-pressed={activeBucket === bucket.id}
            onClick={() => setBucket(bucket.id)}
          >
            {t(bucket.titleKey)}
          </Button>
        ))}
      </div>

      {work.isLoading ? (
        <SkeletonRows rows={4} height={120} />
      ) : work.isError ? (
        <ErrorState message={t("common.error")} onRetry={() => void work.refetch()} />
      ) : (
        <div
          data-testid="work-board-columns"
          // Named breakpoints only -- never an arbitrary `min-[Npx]:`/`max-[Npx]:`
          // bracket (see Sidebar.tsx's own doc comment for the exact failure mode:
          // a class built by interpolating a variable INTO an arbitrary bracket is
          // invisible to Tailwind's static source scan and compiles to nothing).
          // Below `lg` (1024px) the board is a single flex column -- one column
          // rendered after another, in order, never a horizontally-scrolling row.
          // At `lg` and above it becomes a 2-column grid, and at `xl` (1280px) the
          // full 4 columns SCREENS.md §5 specifies. A single filtered bucket
          // (`visibleColumns.length === 1`) stays one column at every width --
          // there is nothing to collapse when there is only one column to begin
          // with, and forcing it into a 2- or 4-track grid would leave the other
          // tracks empty for no reason.
          //
          // `items-start` is scoped to `lg:` only -- it is what stops the grid's
          // columns being stretched to match each other's height once there are
          // real tracks to align, but the same rule applied below `lg`, where the
          // layout is a plain flex column, would shrink every column to its own
          // content width instead of letting it stretch to fill the single track.
          className={
            visibleColumns.length > 1
              ? "flex flex-col lg:grid lg:grid-cols-2 lg:items-start xl:grid-cols-4"
              : "flex flex-col"
          }
          style={{ gap: "var(--ob-space-11)" }}
        >
          {visibleColumns.map((bucket) => (
            <WorkColumn
              key={bucket.id}
              id={bucket.id}
              title={t(bucket.titleKey)}
              role={bucket.role}
              tasks={tasksForBucket(tasks, bucket.status)}
              emptyTitle={t(bucket.emptyTitleKey)}
              emptyDescription={t(bucket.emptyDescriptionKey)}
              contextFor={(task) => contexts.get(task.caseId ?? "") ?? {}}
            />
          ))}
        </div>
      )}
    </div>
  );
}
