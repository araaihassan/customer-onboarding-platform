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
          font: "500 var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
        }}
      >
        {t("workflow.publish.title")}
      </h4>
      <p
        className="text-text-secondary"
        style={{ font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)" }}
      >
        {t("workflow.publish.freezeExplanation")}
      </p>

      {!isLoading && preview && (
        <div
          className="flex items-center"
          style={{
            gap: "var(--ob-space-10)",
            padding: "var(--ob-space-10) var(--ob-space-11)",
            borderRadius: "var(--ob-radius-inner)",
            background: "var(--ob-amber-50)",
            border: "1px solid var(--ob-amber-200)",
          }}
        >
          <div className="min-w-0 flex-1">
            <p
              className="text-text-primary"
              style={{ font: "500 var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)" }}
            >
              {t("workflow.publish.onVersion", { count: String(preview.onVersion ?? 0), version: String(versionNo) })}
            </p>
            <p
              className="text-text-muted"
              style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-ui)", marginTop: 2 }}
            >
              {t("workflow.publish.eligible", { count: String(preview.eligible ?? 0) })}
            </p>
          </div>
          <Link
            href={`/t/${slug}/admin/workflows/${templateId}/migration?versionId=${versionId}`}
            className="inline-flex items-center justify-center flex-shrink-0"
            style={{
              height: 28,
              padding: "0 var(--ob-space-11)",
              borderRadius: "var(--ob-radius-chip)",
              background: "var(--ob-text-primary)",
              color: "var(--ob-bg-surface)",
              font: "500 var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
            }}
          >
            {t("workflow.publish.reviewMigration")}
          </Link>
        </div>
      )}
    </div>
  );
}
