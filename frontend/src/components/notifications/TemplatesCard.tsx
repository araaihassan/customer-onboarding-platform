"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { DataTable } from "@/components/ui/DataTable";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { useToast } from "@/components/ui/Toast";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useNotificationTemplates, useUpdateNotificationTemplate } from "@/lib/api/notifications";
import type { NotificationTemplate } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";
import { TemplateDialog } from "./TemplateDialog";

/**
 * Administration -> Notifications -> Templates.
 *
 * Active state is a word inside a StatusPill (green "Active", neutral
 * "Inactive" -- inactive is a resting state, not a fault, so it is not risk).
 * Activate/Deactivate is a full PUT: every field travels unchanged but `active`,
 * and an absent exit pair stays null.
 */
const MONO = "var(--ob-font-family-data)";

function problem(error: Error): string {
  return error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
}

function hasExit(tpl: NotificationTemplate): boolean {
  return Boolean(tpl.exitedSubject || tpl.exitedBody);
}

export function TemplatesCard() {
  const templates = useNotificationTemplates();
  const update = useUpdateNotificationTemplate();
  const toast = useToast();
  // `undefined` closed; `null` creating; a template editing.
  const [dialog, setDialog] = useState<NotificationTemplate | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  function toggle(tpl: NotificationTemplate) {
    if (!tpl.id) return;
    setError(null);
    update.mutate(
      {
        id: tpl.id,
        body: {
          key: tpl.key ?? "",
          name: tpl.name ?? "",
          enteredSubject: tpl.enteredSubject ?? "",
          enteredBody: tpl.enteredBody ?? "",
          exitedSubject: tpl.exitedSubject ?? null,
          exitedBody: tpl.exitedBody ?? null,
          active: !tpl.active,
        } as never,
      },
      {
        onSuccess: () => toast.show(t("notifications.templates.saved")),
        onError: (err) => setError(problem(err)),
      },
    );
  }

  function actions(tpl: NotificationTemplate) {
    const label = tpl.name ?? tpl.key ?? "";
    return (
      <span className="flex flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
        <Button
          variant="small-secondary"
          aria-label={t("notifications.templates.editAria", { name: label })}
          onClick={() => setDialog(tpl)}
        >
          {t("notifications.templates.editShort")}
        </Button>
        <Button
          variant="small-secondary"
          disabled={update.isPending}
          aria-label={t(
            tpl.active ? "notifications.templates.deactivateAria" : "notifications.templates.activateAria",
            { name: label },
          )}
          onClick={() => toggle(tpl)}
        >
          {t(tpl.active ? "notifications.templates.deactivate" : "notifications.templates.activate")}
        </Button>
      </span>
    );
  }

  const state = (tpl: NotificationTemplate) => (
    <StatusPill
      status={t(tpl.active ? "notifications.templates.active" : "notifications.templates.inactive")}
      role={tpl.active ? "ok" : "neutral"}
    />
  );
  const keyCell = (tpl: NotificationTemplate) => (
    <span style={{ fontFamily: MONO, fontSize: "12.5px", overflowWrap: "anywhere" }}>{tpl.key}</span>
  );
  const kind = (tpl: NotificationTemplate) =>
    t(hasExit(tpl) ? "notifications.templates.entryAndExit" : "notifications.templates.entry");

  return (
    <Card>
      <CardHeader
        title={t("notifications.templates.title")}
        action={
          <Button variant="small-primary" onClick={() => setDialog(null)}>
            {t("notifications.templates.new")}
          </Button>
        }
      />
      {templates.isLoading ? (
        <SkeletonRows rows={4} height={44} />
      ) : templates.isError || !templates.data ? (
        <ErrorState message={t("common.error")} onRetry={() => void templates.refetch()} />
      ) : templates.data.length === 0 ? (
        <EmptyState
          title={t("notifications.templates.empty")}
          description={t("notifications.templates.emptyHint")}
        />
      ) : (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
          {error && (
            <p role="alert" style={{ color: "var(--ob-risk-fg)", font: "11.5px/1.4 var(--ob-font-family-ui)" }}>
              {error}
            </p>
          )}
          <DataTable
            framed={false}
            rows={templates.data}
            getRowKey={(tpl) => tpl.id ?? tpl.key ?? ""}
            columns={[
              { key: "key", label: t("notifications.templates.key"), width: "minmax(8rem, 1.2fr)", render: keyCell },
              { key: "name", label: t("notifications.templates.name"), width: "minmax(8rem, 1.5fr)", render: (tpl) => tpl.name },
              { key: "kind", label: t("notifications.templates.sends"), width: "minmax(7rem, 1fr)", render: kind },
              { key: "state", label: t("notifications.templates.state"), width: "minmax(6rem, 0.8fr)", render: state },
              { key: "actions", label: t("notifications.templates.actions"), width: "minmax(11rem, 1.2fr)", render: actions },
            ]}
            stackedColumn={(tpl) => (
              <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
                <div className="flex items-center justify-between" style={{ gap: "var(--ob-space-8)" }}>
                  <span className="text-ink" style={{ font: "600 13px/1.3 var(--ob-font-family-ui)" }}>
                    {tpl.name}
                  </span>
                  {state(tpl)}
                </div>
                <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-8)" }}>
                  {keyCell(tpl)}
                  <span className="text-text-subtle" style={{ font: "12px/1.4 var(--ob-font-family-ui)" }}>
                    {kind(tpl)}
                  </span>
                </div>
                {actions(tpl)}
              </div>
            )}
          />
        </div>
      )}
      {dialog !== undefined && (
        <TemplateDialog template={dialog ?? undefined} onClose={() => setDialog(undefined)} />
      )}
    </Card>
  );
}
