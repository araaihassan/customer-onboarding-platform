"use client";

import { useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { WorkflowIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import { useMigrate, useMigrationPreview } from "@/lib/api/workflows";
import { MigrationTable } from "@/components/workflow/MigrationTable";
import { t } from "@/lib/i18n";

/**
 * The migration review screen (Task 25) -- reached only from a published
 * version's PublishPanel, never linked to directly, since there is no
 * "current published version" to review without one. versionId names the
 * TARGET version, mirroring MigrationService.preview's own contract: cases
 * sitting on an OLDER version of the same template move onto this one.
 */
export default function MigrationPage() {
  const { slug } = useParams<{ slug: string }>();
  const versionId = useSearchParams().get("versionId") ?? undefined;

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [announcement, setAnnouncement] = useState("");
  const [migrateError, setMigrateError] = useState<string>();

  const preview = useMigrationPreview(versionId);
  const migrate = useMigrate();

  useSetPageHeader(t("workflow.migration.title"));

  if (!versionId) {
    return <EmptyState icon={<WorkflowIcon size={28} />} title={t("common.error")} />;
  }

  if (preview.isLoading) return <SkeletonRows rows={4} height={48} />;
  if (preview.isError || !preview.data) {
    return (
      <EmptyState
        icon={<WorkflowIcon size={28} />}
        title={t("common.error")}
        action={
          <Button type="button" variant="secondary" onClick={() => void preview.refetch()}>
            {t("common.retry")}
          </Button>
        }
      />
    );
  }

  const candidates = preview.data.candidates ?? [];
  const eligibleIds = candidates.filter((c) => c.eligible && c.caseId).map((c) => c.caseId!);

  function toggle(caseId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(caseId)) next.delete(caseId);
      else next.add(caseId);
      return next;
    });
  }

  function selectAll() {
    setSelected((prev) => (eligibleIds.every((id) => prev.has(id)) ? new Set() : new Set(eligibleIds)));
  }

  function runMigration() {
    setMigrateError(undefined);
    migrate.mutate(
      { versionId: versionId!, caseIds: Array.from(selected) },
      {
        onSuccess: (result) => {
          setSelected(new Set());
          setAnnouncement(t("workflow.migration.migrated", { count: String(result.migrated ?? 0) }));
          void preview.refetch();
        },
        onError: (error) => {
          setMigrateError(error instanceof ApiError ? error.message : t("common.error"));
        },
      },
    );
  }

  return (
    <section>
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <p className="text-text-muted" style={{ font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)" }}>
          {t("workflow.migration.subtitle", {
            onVersion: String(preview.data.onVersion ?? 0),
            eligible: String(preview.data.eligible ?? 0),
          })}
        </p>
        <div className="flex-1" />
        <Button
          type="button"
          onClick={runMigration}
          disabled={selected.size === 0 || migrate.isPending}
        >
          {migrate.isPending ? t("workflow.migration.migrating") : t("workflow.migration.migrate")}
        </Button>
      </div>

      {migrateError && (
        <p
          role="alert"
          style={{
            color: "var(--ob-status-blocked-fg)",
            marginBottom: "var(--ob-space-13)",
            font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
          }}
        >
          {migrateError}
        </p>
      )}

      <div
        className="bg-bg-surface border border-border-default overflow-hidden"
        style={{ borderRadius: "var(--ob-radius-card)" }}
      >
        <MigrationTable candidates={candidates} slug={slug} selected={selected} onToggle={toggle} onSelectAll={selectAll} />
      </div>
    </section>
  );
}
