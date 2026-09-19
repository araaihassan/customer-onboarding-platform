"use client";

import { useState } from "react";
import { FileTextIcon, LockIcon, UploadIcon } from "@/components/icons";
import { UploadDialog } from "@/components/documents/UploadDialog";
import { Button } from "@/components/ui/Button";
import { StatusPill, humanise } from "@/components/ui/StatusPill";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { downloadDocumentVersion, useCaseDocuments, type Document } from "@/lib/api/documents";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The case workspace's Documents tab (Task 33), replacing `page.tsx`'s
 * `EmptyState` placeholder -- `TasksTab.tsx` is this file's structural model
 * (same "owns its own data-fetching, loading/empty/error states" shape), and
 * `SCREENS.md` §3's "Other tabs" subsection is the real row spec: "rows with
 * a 30x34 radius-5 file-type tile ... name, meta line stating visibility and
 * version, a Chip, and an Open button" (NOT §6, a stale citation the design
 * spec itself carries for an unrelated Gantt screen -- see this task's own
 * brief).
 *
 * The Chip renders `document.status` (ACTIVE/RETIRED) -- the one status-like
 * field `DocumentView` actually carries at row-list scope. The version's own
 * `reviewStatus` (PENDING/APPROVED/REJECTED) would arguably be a more
 * interesting chip, but it lives on `DocumentVersion`, a separate fetch this
 * tab's one hook (`useCaseDocuments`) never makes -- the same "render what
 * the list endpoint actually returns" discipline `DocumentTable.tsx`'s own
 * doc comment already establishes for its file-type tile.
 */
export function DocumentsTab({ caseId }: { caseId: string }) {
  const documents = useCaseDocuments(caseId);
  const canUpload = useHasPermission("document.upload");
  const [uploading, setUploading] = useState(false);

  if (documents.isLoading) return <SkeletonRows rows={4} height={56} />;

  if (documents.isError) {
    return <ErrorState message={t("common.error")} onRetry={() => void documents.refetch()} />;
  }

  const rows = documents.data?.content ?? [];

  const uploadAction = canUpload ? (
    <Button type="button" variant="secondary" onClick={() => setUploading(true)}>
      <UploadIcon size={14} />
      {t("documents.tab.upload")}
    </Button>
  ) : undefined;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      {rows.length === 0 ? (
        <EmptyState
          icon={<FileTextIcon size={28} />}
          title={t("documents.tab.empty.title")}
          description={t("documents.tab.empty.description")}
          action={uploadAction}
        />
      ) : (
        <>
          {uploadAction && <div className="flex justify-end">{uploadAction}</div>}

          <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
            {rows.map((document) => (
              <DocumentRow key={document.id} document={document} />
            ))}
          </div>
        </>
      )}

      {uploading && <UploadDialog caseId={caseId} onClose={() => setUploading(false)} />}
    </div>
  );
}

/**
 * One row -- `SCREENS.md` §3's own shape. The 30x34 tile is a plain,
 * neutral `FileTextIcon` for every row, the same deliberate substitution
 * `DocumentTable.tsx`'s own doc comment already makes and explains: a real
 * per-type tile (`PDF`/`XLS`/`REQ`) needs `DocumentVersion.contentType`,
 * which this row's own data (`DocumentView`) does not carry.
 */
function DocumentRow({ document }: { document: Document }) {
  const [downloading, setDownloading] = useState(false);
  const canOpen = Boolean(document.id && document.currentVersionNumber);

  async function open() {
    if (!document.id || !document.currentVersionNumber) return;
    setDownloading(true);
    try {
      await downloadDocumentVersion(document.id, document.currentVersionNumber, document.name ?? document.id);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div
      className="flex items-center bg-surface border border-line"
      style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-8) var(--ob-space-11)", borderRadius: "var(--ob-radius-9)" }}
    >
      <span
        aria-hidden
        className="inline-flex items-center justify-center bg-surface-sunken text-text-subtle"
        style={{ width: 30, height: 34, borderRadius: "var(--ob-radius-5)", flexShrink: 0 }}
      >
        <FileTextIcon size={16} />
      </span>

      <div className="min-w-0 flex-1">
        <p className="inline-flex items-center truncate text-ink" style={{ gap: "var(--ob-space-6)", ...NAME_TEXT }}>
          {document.visibilityTier === "SENSITIVE" && <LockIcon size={13} />}
          <span className="truncate">{document.name || EMPTY}</span>
        </p>
        <p className="truncate text-text-subtle" style={META_TEXT}>
          {document.currentVersionNumber
            ? t("documents.tab.meta", { tier: humanise(document.visibilityTier ?? ""), version: String(document.currentVersionNumber) })
            : t("documents.tab.meta.noVersion", { tier: humanise(document.visibilityTier ?? "") })}
        </p>
      </div>

      <StatusPill status={document.status} role={document.status === "ACTIVE" ? "ok" : "neutral"} />

      <Button type="button" variant="secondary" disabled={!canOpen || downloading} onClick={() => void open()}>
        {t("documents.tab.open")}
      </Button>
    </div>
  );
}

const EMPTY = "—";

/** The identifying column: 12.5-13px/600 (`COMPONENTS.md` §12). Human text (a filename), so Instrument Sans, not mono -- same convention as `DocumentTable.tsx`. */
const NAME_TEXT = {
  font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

/** The meta line: visibility + version, 11.5px prose (not mono -- it is a sentence, not a bare machine value). */
const META_TEXT = {
  font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
} as const;
