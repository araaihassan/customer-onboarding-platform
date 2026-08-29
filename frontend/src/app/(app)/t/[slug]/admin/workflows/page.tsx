"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { PlusIcon, WorkflowIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import {
  parseDraftVersionId,
  useCreateDraft,
  useCreateTemplate,
  useDiscardDraft,
  useWorkflows,
} from "@/lib/api/workflows";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * Workflow templates. Editing always goes through a fresh draft
 * (WorkflowService.createDraft): empty for a template with no published
 * version yet, or a deep copy of the current one otherwise -- there is no
 * "list versions" endpoint to link straight to an in-progress draft, so a
 * 409 (one already open) surfaces as an error rather than a link.
 */
export default function WorkflowsPage() {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();

  const [creating, setCreating] = useState(false);
  const [draftIssue, setDraftIssue] = useState<{
    message: string;
    conflict?: { templateId: string; versionId?: string };
  }>();
  const [openingId, setOpeningId] = useState<string>();

  const canManage = useHasPermission("workflow.manage");
  const { data, isLoading, isError, refetch } = useWorkflows();
  const createTemplate = useCreateTemplate();
  const createDraft = useCreateDraft();
  const discardDraft = useDiscardDraft();

  useSetPageHeader(t("workflow.list.title"));

  const templates = data ?? [];

  function openEditor(templateId: string) {
    setDraftIssue(undefined);
    setOpeningId(templateId);
    createDraft.mutate(templateId, {
      onSuccess: (definition) => {
        router.push(`/t/${slug}/admin/workflows/${templateId}/versions/${definition.versionId}`);
      },
      onError: (error) => {
        if (error instanceof ApiError && error.status === 409) {
          setDraftIssue({
            message: t("workflow.list.draftExists"),
            conflict: { templateId, versionId: parseDraftVersionId(error.message) },
          });
        } else {
          setDraftIssue({ message: t("common.error") });
        }
      },
      onSettled: () => setOpeningId(undefined),
    });
  }

  /** Frees the template's one-draft slot, then opens a fresh draft in its place. */
  function discardAndRetry(templateId: string, versionId: string) {
    discardDraft.mutate(
      { templateId, versionId },
      { onSuccess: () => openEditor(templateId) },
    );
  }

  return (
    <section>
      <h2 className="sr-only">{t("workflow.list.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <div className="flex-1" />
        {canManage && (
          <Button
            type="button"
            onClick={() => {
              createTemplate.reset();
              setCreating(true);
            }}
            style={{ gap: "var(--ob-space-6)" }}
          >
            <PlusIcon size={14} />
            {t("workflow.list.create")}
          </Button>
        )}
      </div>

      {draftIssue && (
        <div
          role="alert"
          style={{
            marginBottom: "var(--ob-space-13)",
          }}
        >
          <p
            style={{
              color: "var(--ob-risk-fg)",
              font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            }}
          >
            {draftIssue.message}
          </p>
          {draftIssue.conflict && (
            <div
              className="flex flex-wrap"
              style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-8)" }}
            >
              {draftIssue.conflict.versionId && (
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() =>
                    router.push(
                      `/t/${slug}/admin/workflows/${draftIssue.conflict!.templateId}/versions/${draftIssue.conflict!.versionId}`,
                    )
                  }
                >
                  {t("workflow.list.resumeDraft")}
                </Button>
              )}
              <Button
                type="button"
                variant="secondary"
                disabled={!draftIssue.conflict.versionId || discardDraft.isPending}
                onClick={() =>
                  discardAndRetry(draftIssue.conflict!.templateId, draftIssue.conflict!.versionId!)
                }
              >
                {t("workflow.list.discardDraft")}
              </Button>
            </div>
          )}
        </div>
      )}

      {isLoading ? (
        <SkeletonRows rows={4} height={56} />
      ) : isError ? (
        <EmptyState
          icon={<WorkflowIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : templates.length === 0 ? (
        <EmptyState
          icon={<WorkflowIcon size={28} />}
          title={t("workflow.list.empty")}
          description={t("workflow.list.emptyHint")}
        />
      ) : (
        <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          {templates.map((template) => (
            <li
              key={template.id}
              className="flex items-center justify-between bg-surface border border-line"
              style={{ borderRadius: "var(--ob-radius-11)", padding: "var(--ob-space-13) var(--ob-space-16)" }}
            >
              <div>
                <p
                  className="text-ink"
                  style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
                >
                  {template.name}
                </p>
                <p
                  className="text-text-faint"
                  style={{ font: "var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)" }}
                >
                  {template.currentVersionNo
                    ? t("workflow.version.published", { version: String(template.currentVersionNo) })
                    : t("workflow.list.neverPublished")}
                </p>
              </div>
              {canManage && (
                <Button
                  type="button"
                  variant="secondary"
                  disabled={openingId === template.id}
                  onClick={() => openEditor(template.id!)}
                >
                  {template.currentVersionNo ? t("workflow.list.newDraft") : t("workflow.list.startEditing")}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {creating && (
        <Dialog title={t("workflow.create.title")} onClose={() => setCreating(false)}>
          <NewTemplateForm
            pending={createTemplate.isPending}
            error={createTemplate.isError ? t("common.error") : undefined}
            onCancel={() => setCreating(false)}
            onSubmit={(values) =>
              createTemplate.mutate(values, {
                onSuccess: (created) => {
                  setCreating(false);
                  if (created.id) openEditor(created.id);
                },
              })
            }
          />
        </Dialog>
      )}
    </section>
  );
}

function NewTemplateForm({
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  pending: boolean;
  error?: string;
  onSubmit: (values: { name: string; description: string }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [nameError, setNameError] = useState<string>();

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = name.trim();
        if (!trimmed) {
          setNameError(t("customer.form.required"));
          return;
        }
        onSubmit({ name: trimmed, description: description.trim() });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("workflow.field.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />
        <Field
          label={t("workflow.field.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={pending}>
          {t("workflow.create.submit")}
        </Button>
      </DialogActions>
    </form>
  );
}
