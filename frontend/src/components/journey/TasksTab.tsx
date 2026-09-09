"use client";

import { useMemo, useState } from "react";
import { ClipboardCheckIcon, PlusIcon } from "@/components/icons";
import { TaskCard } from "@/components/task/TaskCard";
import { TaskDetail } from "@/components/task/TaskDetail";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field, TextareaField } from "@/components/ui/Field";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { parseProblemDetail, useParticipants, useRoadmap, type StageRoadmap } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import {
  useCaseTasks,
  useCreateTask,
  type MilestoneOption,
  type Task,
  type TaskPriority,
} from "@/lib/api/tasks";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

interface TaskGroup {
  id: string;
  name: string;
  tasks: Task[];
}

/** The roadmap's own stage->milestone order, flattened -- the canonical sequence groups render in, and what a "New task" dialog's milestone picker offers. */
function flattenMilestones(stages: StageRoadmap[]): MilestoneOption[] {
  const milestones: MilestoneOption[] = [];
  for (const stage of stages) {
    for (const milestone of stage.milestones ?? []) {
      if (milestone.id) milestones.push({ id: milestone.id, name: milestone.name ?? "" });
    }
  }
  return milestones;
}

/**
 * Groups a case's flat task list by milestone, in the roadmap's own order --
 * `useCaseTasks` carries no ordering guarantee, and the design's own Tasks
 * tab requirement is "grouped by milestone", not "in list order". A task
 * whose milestoneId matches nothing in the roadmap (a data-integrity edge
 * case; `CreateTaskRequest.milestoneId` is required, so this should not
 * happen in practice) still renders, under a trailing "Other" group, rather
 * than silently disappearing.
 */
function groupByMilestone(tasks: Task[], milestones: MilestoneOption[]): TaskGroup[] {
  const byMilestone = new Map<string, Task[]>();
  for (const task of tasks) {
    const key = task.milestoneId ?? "";
    const list = byMilestone.get(key) ?? [];
    list.push(task);
    byMilestone.set(key, list);
  }

  const groups: TaskGroup[] = [];
  for (const milestone of milestones) {
    const list = byMilestone.get(milestone.id);
    if (list && list.length > 0) {
      groups.push({ id: milestone.id, name: milestone.name, tasks: list });
      byMilestone.delete(milestone.id);
    }
  }

  const leftover = Array.from(byMilestone.values()).flat();
  if (leftover.length > 0) {
    groups.push({ id: "other", name: t("task.list.otherGroup"), tasks: leftover });
  }
  return groups;
}

/**
 * The case workspace's Tasks tab (design spec's Tasks tab: "Task / Status /
 * Priority / Due", grouped by milestone). Owns its own data-fetching,
 * loading/empty/error states and grouping -- the same shape `JourneyPreview`
 * (this file's neighbour in the case workspace page) already establishes.
 *
 * Cards, not the design's literal DataTable rows: the brief leaves the
 * shape open, and `TaskCard` needs to double as Task 28's `WorkBoard` column
 * item either way, which a table row would not.
 */
export function TasksTab({ caseId }: { caseId: string }) {
  const tasks = useCaseTasks(caseId);
  const roadmap = useRoadmap(caseId);
  // Fetched once here, not inside TaskDetail -- CommentThread's own author
  // resolution needs it (the MilestoneRow discipline: fetch once, thread
  // down), and this tab had no prior reason to hold it before Task 29.
  const participants = useParticipants(caseId);
  const canCreate = useHasPermission("task.manage");
  const [selectedId, setSelectedId] = useState<string>();
  const [creating, setCreating] = useState(false);

  const milestones = useMemo(() => flattenMilestones(roadmap.data?.stages ?? []), [roadmap.data]);
  const groups = useMemo(() => groupByMilestone(tasks.data ?? [], milestones), [tasks.data, milestones]);
  // Looked up by id from the live query data, rather than kept as a snapshot,
  // so the open detail panel reflects a status change or a checklist edit
  // the instant the case's task list refetches -- never a stale copy.
  const selected = tasks.data?.find((task) => task.id === selectedId);

  if (tasks.isLoading || roadmap.isLoading) return <SkeletonRows rows={4} height={64} />;

  if (tasks.isError || roadmap.isError) {
    return (
      <ErrorState
        message={t("common.error")}
        onRetry={() => {
          void tasks.refetch();
          void roadmap.refetch();
        }}
      />
    );
  }

  const createAction = canCreate ? (
    <Button type="button" variant="secondary" onClick={() => setCreating(true)}>
      <PlusIcon size={14} />
      {t("task.list.create")}
    </Button>
  ) : undefined;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
      {groups.length === 0 ? (
        <EmptyState
          icon={<ClipboardCheckIcon size={28} />}
          title={t("task.list.empty.title")}
          description={t("task.list.empty.description")}
          action={createAction}
        />
      ) : (
        <>
          {createAction && <div className="flex justify-end">{createAction}</div>}

          {groups.map((group) => (
            <div key={group.id} className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
              <h5
                className="text-text-faint"
                style={{
                  font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
                  textTransform: "uppercase",
                  letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                }}
              >
                {group.name || t("task.list.otherGroup")}
              </h5>
              <div className="grid grid-cols-1 md:grid-cols-2" style={{ gap: "var(--ob-space-8)" }}>
                {group.tasks.map((task) => (
                  <TaskCard key={task.id} task={task} onClick={() => setSelectedId(task.id)} />
                ))}
              </div>
            </div>
          ))}
        </>
      )}

      {selected && (
        <Dialog title={selected.title ?? ""} onClose={() => setSelectedId(undefined)} maxWidth={560}>
          <TaskDetail task={selected} participants={participants.data ?? []} milestones={milestones} />
        </Dialog>
      )}

      {creating && (
        <CreateTaskDialog caseId={caseId} milestones={milestones} onClose={() => setCreating(false)} />
      )}
    </div>
  );
}

const PRIORITIES: TaskPriority[] = ["LOW", "MEDIUM", "HIGH"];

/**
 * The ad-hoc creation trigger `useCreateTask`'s own doc comment names as
 * missing ("the requirement-instantiated path has no UI trigger of its
 * own") -- closed here, doubling as the Tasks tab's required empty-state
 * action rather than blank space.
 */
function CreateTaskDialog({
  caseId,
  milestones,
  onClose,
}: {
  caseId: string;
  milestones: MilestoneOption[];
  onClose: () => void;
}) {
  const createTask = useCreateTask();
  const [title, setTitle] = useState("");
  const [titleError, setTitleError] = useState<string>();
  const [description, setDescription] = useState("");
  const [milestoneId, setMilestoneId] = useState(milestones[0]?.id ?? "");
  const [priority, setPriority] = useState<TaskPriority>("MEDIUM");
  const [dueDate, setDueDate] = useState("");

  function submit() {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      setTitleError(t("customer.form.required"));
      return;
    }
    if (!milestoneId) return;

    createTask.mutate(
      {
        caseId,
        milestoneId,
        title: trimmedTitle,
        description: description.trim() || undefined,
        priority,
        dueDate: dueDate || undefined,
      },
      { onSuccess: onClose },
    );
  }

  return (
    <Dialog title={t("task.create.title")} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <Field
          label={t("task.create.titleLabel")}
          value={title}
          error={titleError}
          onChange={(event) => {
            setTitle(event.target.value);
            setTitleError(undefined);
          }}
        />

        <div className="flex flex-col">
          <label
            style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}
          >
            {t("task.create.milestone")}
          </label>
          <select
            value={milestoneId}
            onChange={(event) => setMilestoneId(event.target.value)}
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
            {milestones.map((milestone) => (
              <option key={milestone.id} value={milestone.id}>
                {milestone.name}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col">
          <label
            style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}
          >
            {t("task.create.priority")}
          </label>
          <select
            value={priority}
            onChange={(event) => setPriority(event.target.value as TaskPriority)}
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
            {PRIORITIES.map((option) => (
              <option key={option} value={option}>
                {t(`task.priority.${option}`)}
              </option>
            ))}
          </select>
        </div>

        <Field
          type="date"
          label={t("task.create.dueDate")}
          value={dueDate}
          onChange={(event) => setDueDate(event.target.value)}
        />

        <TextareaField
          label={t("task.create.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />

        {createTask.isError && (
          <p
            role="alert"
            style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {createTask.error instanceof ApiError ? parseProblemDetail(createTask.error.message) : t("common.error")}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={createTask.isPending || !milestoneId} onClick={submit}>
          {t("task.create.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
