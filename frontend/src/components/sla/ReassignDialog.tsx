"use client";

import { useState } from "react";
import { UserSelect } from "@/components/admin/UserSelect";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { SkeletonRows } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import { useUsers } from "@/lib/api/admin";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, toUpdateCaseRequest, useCase, useUpdateCase, type Case } from "@/lib/api/cases";
import { toUpdateTaskRequest, useCaseTasks, useUpdateTask, type Task } from "@/lib/api/tasks";
import { t } from "@/lib/i18n";
import { InlineError, SlaEyebrow } from "./dialogParts";

const TERMINAL = new Set(["COMPLETED", "CANCELLED"]);

function errorText(err: unknown): string {
  return err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error");
}

/**
 * War-room reassignment (spec decision 8): the case owner and each open task's assignee. Both
 * writes are full-replace PUTs, so each starts from the current record and changes one field
 * (`toUpdateCaseRequest` / `toUpdateTaskRequest`). There is no "none" option: a reassign cannot
 * clear a person. `useUsers` is paged at 25, so a current owner or assignee outside the page stays
 * selectable (UserSelect's fallback option) and saving can never replace it unintentionally.
 */
export function ReassignDialog({ caseId, onClose }: { caseId: string; onClose: () => void }) {
  const theCase = useCase(caseId);
  const tasks = useCaseTasks(caseId);
  const users = useUsers("", 0);
  const updateCase = useUpdateCase();
  const updateTask = useUpdateTask();
  const toast = useToast();

  const [owner, setOwner] = useState<string>();
  const [assignees, setAssignees] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<{ key: string; message: string }>();

  const options = (users.data?.content ?? []).filter((u) => u.status === "ACTIVE" && u.userType === "INTERNAL");
  const openTasks = (tasks.data ?? []).filter((task) => task.assigneeId && !TERMINAL.has(task.status ?? ""));

  function done() {
    toast.show(t("sla.reassign.done"));
    onClose();
  }

  function saveOwner(current: Case, ownerUserId: string) {
    setFailure(undefined);
    updateCase.mutate(
      { caseId, body: toUpdateCaseRequest(current, { ownerUserId }) },
      { onSuccess: done, onError: (e) => setFailure({ key: "owner", message: errorText(e) }) },
    );
  }

  function saveTask(task: Task, assigneeId: string) {
    setFailure(undefined);
    updateTask.mutate(
      { taskId: task.id ?? "", body: toUpdateTaskRequest(task, { assigneeId }) },
      { onSuccess: done, onError: (e) => setFailure({ key: task.id ?? "", message: errorText(e) }) },
    );
  }

  let body;
  if (theCase.isError) {
    body = <InlineError>{t("sla.reassign.error")}</InlineError>;
  } else if (theCase.isLoading || !theCase.data) {
    body = <SkeletonRows rows={3} height={36} />;
  } else {
    const current = theCase.data;
    const ownerValue = owner ?? current.ownerUserId ?? "";
    body = (
      <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
        <div>
          <UserSelect
            id="reassign-owner"
            label={t("sla.reassign.owner")}
            currentLabel={t("sla.reassign.currentOwner")}
            value={ownerValue}
            options={options}
            onChange={setOwner}
          />
          <div className="flex justify-end" style={{ marginTop: "var(--ob-space-8)" }}>
            <Button
              type="button"
              variant="small-primary"
              disabled={updateCase.isPending || ownerValue === (current.ownerUserId ?? "")}
              onClick={() => saveOwner(current, ownerValue)}
            >
              {t("sla.reassign.saveOwner")}
            </Button>
          </div>
          {failure?.key === "owner" && <InlineError>{failure.message}</InlineError>}
        </div>

        <section aria-labelledby="reassign-tasks">
          <h3
            id="reassign-tasks"
            className="text-text-muted"
            style={{ font: "600 12px/1.4 var(--ob-font-family-ui)", marginBottom: "var(--ob-space-8)" }}
          >
            {t("sla.reassign.tasks")}
          </h3>
          {tasks.isLoading ? (
            <SkeletonRows rows={2} height={36} />
          ) : tasks.isError ? (
            <InlineError>{t("common.error")}</InlineError>
          ) : openTasks.length === 0 ? (
            <p className="text-text-subtle" style={{ font: "12px/1.4 var(--ob-font-family-ui)" }}>
              {t("sla.reassign.noTasks")}
            </p>
          ) : (
            <ul className="flex flex-col" style={{ listStyle: "none", padding: 0, margin: 0, gap: "var(--ob-space-12)" }}>
              {openTasks.map((task) => {
                const id = task.id ?? "";
                const value = assignees[id] ?? task.assigneeId ?? "";
                return (
                  <li key={id}>
                    <p className="text-ink" style={{ font: "500 13px/1.4 var(--ob-font-family-ui)", marginBottom: "var(--ob-space-4)" }}>
                      {task.title}
                    </p>
                    <UserSelect
                      id={`reassign-task-${id}`}
                      label={t("sla.reassign.assignee")}
                      currentLabel={t("sla.reassign.currentAssignee")}
                      value={value}
                      options={options}
                      onChange={(next) => setAssignees((prev) => ({ ...prev, [id]: next }))}
                    />
                    <div className="flex justify-end" style={{ marginTop: "var(--ob-space-8)" }}>
                      <Button
                        type="button"
                        variant="small-secondary"
                        aria-label={t("sla.reassign.saveTask", { title: task.title ?? "" })}
                        disabled={updateTask.isPending || value === task.assigneeId}
                        onClick={() => saveTask(task, value)}
                      >
                        {t("common.save")}
                      </Button>
                    </div>
                    {failure?.key === id && <InlineError>{failure.message}</InlineError>}
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </div>
    );
  }

  return (
    <Dialog title={t("sla.reassign.title")} eyebrow={<SlaEyebrow />} onClose={onClose} maxWidth={480}>
      {body}
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
