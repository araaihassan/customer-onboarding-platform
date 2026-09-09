"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field, TextareaField } from "@/components/ui/Field";
import type { MilestoneOption, Task, TaskPriority, UpdateTaskRequest } from "@/lib/api/tasks";
import { t } from "@/lib/i18n";

const PRIORITIES: TaskPriority[] = ["LOW", "MEDIUM", "HIGH"];

/**
 * The edit half of the gap CLAUDE.md's "no task-edit UI at all" item names:
 * title, description, priority, due date and milestone, all editable here --
 * the assignee is round-tripped but not yet, Task 7 adds that picker on top
 * of this same form.
 *
 * `UpdateTaskRequest` is a full replace (`title`/`priority`/`milestoneId` are
 * all non-optional on the wire): every field is seeded from `task` on mount,
 * so a field the user never touches still submits its CURRENT value rather
 * than going missing from the body and being written as null.
 *
 * Deliberately takes `onSubmit` rather than calling `useUpdateTask()` itself,
 * unlike `CreateTaskDialog` -- the same split `admin/users/page.tsx`'s
 * `EditForm` already uses (form takes `onSubmit`/`pending`/`error`; the
 * caller owns the mutation and the `Dialog` wiring). That split is what
 * makes the full-replace guarantee this component exists for directly
 * assertable: the test can inspect exactly what `onSubmit` was called with,
 * rather than asserting on a mocked network call's serialized body.
 */
export function TaskEditDialog({
  task,
  milestones,
  onSubmit,
  onClose,
  pending = false,
  error,
}: {
  task: Task;
  milestones: MilestoneOption[];
  onSubmit: (body: UpdateTaskRequest) => void;
  onClose: () => void;
  pending?: boolean;
  error?: string;
}) {
  const milestoneFieldId = useId();
  const priorityFieldId = useId();

  const [title, setTitle] = useState(task.title ?? "");
  const [titleError, setTitleError] = useState<string>();
  const [description, setDescription] = useState(task.description ?? "");
  const [priority, setPriority] = useState<TaskPriority>(task.priority ?? "MEDIUM");
  const [dueDate, setDueDate] = useState(task.dueDate ?? "");
  const [milestoneId, setMilestoneId] = useState(task.milestoneId ?? "");

  function submit() {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      setTitleError(t("customer.form.required"));
      return;
    }
    if (!milestoneId) return;

    onSubmit({
      title: trimmedTitle,
      description: description.trim() || undefined,
      priority,
      dueDate: dueDate || undefined,
      milestoneId,
      // Not editable here yet (Task 7) -- carried forward unchanged so a PUT
      // never silently unassigns the task.
      assigneeId: task.assigneeId,
    });
  }

  return (
    <Dialog title={t("task.edit.title")} onClose={onClose}>
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
          <label htmlFor={milestoneFieldId} style={labelStyle}>
            {t("task.create.milestone")}
          </label>
          <select
            id={milestoneFieldId}
            value={milestoneId}
            onChange={(event) => setMilestoneId(event.target.value)}
            style={selectStyle}
          >
            {milestones.map((milestone) => (
              <option key={milestone.id} value={milestone.id}>
                {milestone.name}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col">
          <label htmlFor={priorityFieldId} style={labelStyle}>
            {t("task.create.priority")}
          </label>
          <select
            id={priorityFieldId}
            value={priority}
            onChange={(event) => setPriority(event.target.value as TaskPriority)}
            style={selectStyle}
          >
            {PRIORITIES.map((option) => (
              <option key={option} value={option}>
                {t(`task.priority.${option}`)}
              </option>
            ))}
          </select>
        </div>

        {/* A machine-generated value -- CLAUDE.md's binding rule -- so mono,
            unlike the title/description fields either side of it. */}
        <Field
          type="date"
          label={t("task.create.dueDate")}
          value={dueDate}
          onChange={(event) => setDueDate(event.target.value)}
          style={{ fontFamily: "var(--ob-font-family-data)" }}
        />

        <TextareaField
          label={t("task.create.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />

        {error && (
          <p
            role="alert"
            style={{
              color: "var(--ob-risk-fg)",
              font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
            }}
          >
            {error}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={pending || !milestoneId} onClick={submit}>
          {t("common.save")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const labelStyle = {
  fontSize: "11.5px",
  color: "var(--ob-text-subtle)",
  marginBottom: "5px",
  fontWeight: 500,
} as const;

const selectStyle = {
  height: "var(--ob-control-height)",
  borderRadius: "var(--ob-radius-9)",
  border: "1px solid var(--ob-line)",
  background: "var(--ob-surface)",
  padding: "0 11px",
  fontSize: "13px",
  color: "var(--ob-ink)",
  fontFamily: "var(--ob-font-family-ui)",
} as const;
