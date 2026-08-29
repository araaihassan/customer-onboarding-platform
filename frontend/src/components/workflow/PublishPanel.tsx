import Link from "next/link";
import type { MigrationPreview } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/**
 * The publishing section's migration summary (uispecs README §6, "Publishing"):
 * an explanation of freeze-by-default, then an amber panel with the two
 * headline counts and a "Review migration" button -- exactly the prototype's
 * "31 cases on v4 / 18 eligible to migrate".
 *
 * Presentational only: the Builder page owns the `useMigrationPreview` call,
 * the same split StageInspector and StageRow already follow for their own
 * data.
 */
export function PublishPanel({
  versionNo,
  slug,
  templateId,
  versionId,
  preview,
  isLoading,
}: {
  versionNo: number;
  slug: string;
  templateId: string;
  versionId: string;
  preview: MigrationPreview | undefined;
  isLoading: boolean;
}) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      <h4
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
        }}
      >
        {t("workflow.publish.title")}
      </h4>
      <p
        className="text-text-2"
        style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {t("workflow.publish.freezeExplanation")}
      </p>

      {!isLoading && preview && (
        <div
          className="flex items-center"
          style={{
            gap: "var(--ob-space-10)",
            padding: "var(--ob-space-10) var(--ob-space-11)",
            borderRadius: "var(--ob-radius-10)",
            background: "var(--ob-warn-bg)",
            border: "1px solid var(--ob-warn-border)",
          }}
        >
          <div className="min-w-0 flex-1">
            <p
              className="text-ink"
              style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
            >
              {t("workflow.publish.onVersion", { count: String(preview.onVersion ?? 0), version: String(versionNo) })}
            </p>
            <p
              className="text-text-muted"
              style={{ font: "var(--ob-type-small-print-size)/var(--ob-type-small-print-line) var(--ob-font-family-ui)", marginTop: 2 }}
            >
              {t("workflow.publish.eligible", { count: String(preview.eligible ?? 0) })}
            </p>
          </div>
          {/*
            Next's `Link` can't literally be a `<button>` when it needs
            client-side navigation, so this stays a styled anchor -- restyled
            to `Button`'s `primary` variant token values directly (height,
            padding, radius, background/color, font) rather than the
            previous hardcoded style object built from since-renamed tokens.
          */}
          <Link
            href={`/t/${slug}/admin/workflows/${templateId}/migration?versionId=${versionId}`}
            className="inline-flex items-center justify-center flex-shrink-0"
            style={{
              height: "var(--ob-control-height)",
              padding: "0 13px",
              borderRadius: "var(--ob-radius-8)",
              background: "var(--ob-ink)",
              color: "var(--ob-canvas)",
              font: "600 12.5px/1.2 var(--ob-font-family-ui)",
            }}
          >
            {t("workflow.publish.reviewMigration")}
          </Link>
        </div>
      )}
    </div>
  );
}
