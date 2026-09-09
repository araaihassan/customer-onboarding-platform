"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field, TextareaField } from "@/components/ui/Field";
import type { User } from "@/lib/api/admin";
import { shortId } from "@/lib/api/customers";
import type { MilestoneOption, Task, TaskPriority, UpdateTaskRequest } from "@/lib/api/tasks";
import { t } from "@/lib/i18n";

const PRIORITIES: TaskPriority[] = ["LOW", "MEDIUM", "HIGH"];

/**
 * The full gap CLAUDE.md's "no task-edit UI at all" item named: title,
 * description, priority, due date, milestone and (Task 7) assignee, all
 * editable here.
 *
 * `UpdateTaskRequest` is a full replace (`title`/`priority`/`milestoneId` are
 * all non-optional on the wire): every field is seeded from `task` on mount,
 * so a field the user never touches still submits its CURRENT value rather
 * than going missing from the body and being written as null.
 *
 * `users` is the tenant's user list for the assignee picker -- the caller's
 * responsibility to fetch (`TaskDetail` uses `useUsers("", 0, ...)`, the same
 * hook `TeamMembers` already uses for the same purpose) and gate behind
 * `user.view`, since `GET /admin/users` is. Defaulted to `[]` so a caller
 * without that permission, or this component's own pre-Task-7 tests, still
 * render: the picker then offers only "Unassigned" plus a fallback entry for
 * the task's current assignee (see below), never a blank, empty-looking
 * control.
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
  users = [],
  onSubmit,
  onClose,
  pending = false,
  error,
}: {
  task: Task;
  milestones: MilestoneOption[];
  users?: User[];
  onSubmit: (body: UpdateTaskRequest) => void;
  onClose: () => void;
  pending?: boolean;
  error?: string;
}) {
  const milestoneFieldId = useId();
  const priorityFieldId = useId();
  const assigneeFieldId = useId();

  const [title, setTitle] = useState(task.title ?? "");
  const [titleError, setTitleError] = useState<string>();
  const [description, setDescription] = useState(task.description ?? "");
  const [priority, setPriority] = useState<TaskPriority>(task.priority ?? "MEDIUM");
  const [dueDate, setDueDate] = useState(task.dueDate ?? "");
  const [milestoneId, setMilestoneId] = useState(task.milestoneId ?? "");
  const [assigneeId, setAssigneeId] = useState(task.assigneeId ?? "");

  // `users` is a page, not a guaranteed-complete tenant roster (Task 7's own
  // documented concern) -- if the task's current assignee isn't in it, the
  // picker still needs an option for that id so the control shows the real
  // current assignee selected, rather than silently falling back to blank.
  const currentAssigneeMissing =
    Boolean(task.assigneeId) && !users.some((user) => user.id === task.assigneeId);

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
      // An explicit `null`, not an omitted key or an empty string -- Task 7's
      // "Unassigned" option must be reachable, and a full-replace PUT treats
      // "absent" and "null" the same, but only `null` is what this state
      // actually means once the user has picked it.
      assigneeId: assigneeId || null,
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

        <div className="flex flex-col">
          <label htmlFor={assigneeFieldId} style={labelStyle}>
            {t("task.detail.assignee")}
          </label>
          <select
            id={assigneeFieldId}
            value={assigneeId}
            onChange={(event) => setAssigneeId(event.target.value)}
            style={selectStyle}
          >
            {/* Explicit and distinct from the select simply having nothing
                chosen -- selecting this is what submits `assigneeId: null`. */}
            <option value="">{t("task.detail.unassigned")}</option>
            {currentAssigneeMissing && (
              <option value={task.assigneeId}>{shortId(task.assigneeId!)}</option>
            )}
            {users.map((user) => (
              <option key={user.id} value={user.id}>
                {user.fullName ?? user.email ?? shortId(user.id ?? "")}
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
