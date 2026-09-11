"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { ApiError } from "@/lib/api/client";
import { useRefreshFromSource, type WorkflowTemplate } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/**
 * QA Q21's refresh gesture (CustomerTemplateService.refreshFromSource, Task 17):
 * deep-copies the clone's catalogue source's current published version into a
 * fresh DRAFT of this SAME customer template. Spec 5.2 is explicit that this
 * REPLACES rather than merges -- any tailoring made since the clone (or the
 * last refresh) is not carried across, and there is no three-way merge to
 * recover it afterwards. The confirm button is disabled until that is
 * acknowledged, not merely stated, because discovering it after the fact means
 * re-tailoring the whole graph by hand.
 *
 * Unlike a template's first draft (WorkflowsPage's own createDraft flow),
 * refresh's response carries the new draft's own versionId directly -- so a
 * successful refresh routes straight into that draft's editor rather than
 * falling back to the 409-conflict "resume draft" path a bare clone leaves as
 * the only way in.
 */
export function RefreshFromSourceDialog({
  template,
  sourceName,
  open,
  onClose,
}: {
  template: WorkflowTemplate;
  /** The catalogue source's own name, resolved by the caller from the same template list. */
  sourceName?: string;
  open: boolean;
  onClose: () => void;
}) {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();
  const refresh = useRefreshFromSource();
  const [acknowledged, setAcknowledged] = useState(false);

  if (!open) return null;

  function submit() {
    if (!acknowledged || !template.id) return;
    refresh.mutate(template.id, {
      onSuccess: (definition) => {
        onClose();
        router.push(`/t/${slug}/admin/workflows/${template.id}/versions/${definition.versionId}`);
      },
    });
  }

  return (
    <Dialog title={t("workflow.refreshDialog.title")} onClose={onClose}>
      {sourceName && (
        <p
          className="text-text-faint"
          style={{
            font: "var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)",
            marginBottom: "var(--ob-space-11)",
          }}
        >
          {t("workflow.list.clonedFrom", { source: sourceName })}
        </p>
      )}

      {/* Same risk-callout treatment as ForceCompleteDialog's own warning --
          this is the other replacing, unrecoverable action in the builder. */}
      <div
        role="note"
        style={{
          border: "1px solid var(--ob-risk-border)",
          background: "var(--ob-risk-bg)",
          borderRadius: "var(--ob-radius-10)",
          padding: "11px 12px",
          marginBottom: "var(--ob-space-14)",
          font: "12px/1.4 var(--ob-font-family-ui)",
          color: "var(--ob-risk-fg)",
        }}
      >
        {t("workflow.refreshDialog.warning")}
      </div>

      <label className="flex items-start" style={{ gap: "var(--ob-space-8)" }}>
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={(event) => setAcknowledged(event.target.checked)}
          style={{ marginTop: 2 }}
        />
        <span
          className="text-ink"
          style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {t("workflow.refreshDialog.acknowledge")}
        </span>
      </label>

      <p
        role="alert"
        style={{
          color: "var(--ob-risk-fg)",
          marginTop: "var(--ob-space-11)",
          font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
        }}
      >
        {refresh.isError
          ? refresh.error instanceof ApiError && refresh.error.status === 409
            ? t("workflow.refreshDialog.conflict")
            : t("common.error")
          : ""}
      </p>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={!acknowledged || refresh.isPending} onClick={submit}>
          {t("workflow.refreshDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
