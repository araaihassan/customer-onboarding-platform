"use client";

import { useMemo, useState } from "react";
import { FileTextIcon, LockIcon, UploadIcon } from "@/components/icons";
import { UploadDialog } from "@/components/documents/UploadDialog";
import { Button } from "@/components/ui/Button";
import { StatusPill, humanise } from "@/components/ui/StatusPill";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { useRoadmap, type Roadmap } from "@/lib/api/cases";
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
  // The same roadmap query `JourneyPreview` (the Journey tab) already fetches
  // -- react-query dedupes on `caseKeys.roadmap(caseId)`, so viewing Journey
  // before Documents costs this tab nothing extra. Grouping is a pure bonus
  // on top of the document list, never a precondition for it: a still-loading
  // or failed roadmap fetch degrades to the flat, ungrouped list rather than
  // blocking or erroring this tab (see `groupByMilestone`'s own default).
  const roadmap = useRoadmap(caseId);
  const canUpload = useHasPermission("document.upload");
  const [uploading, setUploading] = useState(false);

  const rows = useMemo(() => documents.data?.content ?? [], [documents.data]);
  const groups = useMemo(() => groupByMilestone(rows, roadmap.data), [rows, roadmap.data]);

  if (documents.isLoading) return <SkeletonRows rows={4} height={56} />;

  if (documents.isError) {
    return <ErrorState message={t("common.error")} onRetry={() => void documents.refetch()} />;
  }

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

          <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
            {groups.map((group) => (
              <div key={group.key} className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
                {/* A single ungrouped section renders with no heading at all --
                    identical to this tab's own pre-grouping shape, so a case
                    with no document requirement ever satisfied (every case
                    before this feature existed, and every ad-hoc-only case
                    after it) looks exactly as it always has. */}
                {group.heading && (
                  <h5
                    className="text-text-faint"
                    style={{
                      font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
                      textTransform: "uppercase",
                      letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                    }}
                  >
                    {group.heading}
                  </h5>
                )}
                {group.documents.map((document) => (
                  <DocumentRow key={document.id} document={document} />
                ))}
              </div>
            ))}
          </div>
        </>
      )}

      {uploading && <UploadDialog caseId={caseId} onClose={() => setUploading(false)} />}
    </div>
  );
}

interface DocumentGroup {
  key: string;
  /** `undefined` renders no heading at all -- the flat-list, pre-grouping shape. */
  heading?: string;
  documents: Document[];
}

/**
 * Groups a case's documents by the milestone whose DOCUMENT-kind requirement
 * they satisfied, in roadmap order (stage, then milestone). A document only
 * ends up in a group when a real `Requirement.satisfiedRef` points at it --
 * an ad-hoc upload, or one that fulfilled a request whose requirement still
 * requires review, has no such reference and always lands in "Other
 * documents". When NOTHING is traceable to a milestone (no roadmap data yet,
 * or a case with only ad-hoc documents), this returns a single ungrouped
 * section with `heading: undefined`, matching this tab's exact pre-grouping
 * rendering -- grouping is additive, never a regression for the common case.
 */
function groupByMilestone(documents: Document[], roadmap: Roadmap | undefined): DocumentGroup[] {
  const milestoneByDocumentId = new Map<string, { key: string; heading: string }>();

  for (const stage of roadmap?.stages ?? []) {
    for (const milestone of stage.milestones ?? []) {
      for (const requirement of milestone.requirements ?? []) {
        if (requirement.kind !== "DOCUMENT") continue;
        if (requirement.satisfiedRefType !== "document" || !requirement.satisfiedRef) continue;
        if (!milestone.id) continue;
        milestoneByDocumentId.set(requirement.satisfiedRef, {
          key: milestone.id,
          heading: [stage.name, milestone.name].filter(Boolean).join(" · "),
        });
      }
    }
  }

  if (milestoneByDocumentId.size === 0) {
    return [{ key: "all", documents }];
  }

  const grouped = new Map<string, DocumentGroup>();
  const ungrouped: Document[] = [];

  for (const document of documents) {
    const milestone = document.id ? milestoneByDocumentId.get(document.id) : undefined;
    if (!milestone) {
      ungrouped.push(document);
      continue;
    }
    const group = grouped.get(milestone.key) ?? { key: milestone.key, heading: milestone.heading, documents: [] };
    group.documents.push(document);
    grouped.set(milestone.key, group);
  }

  const groups = Array.from(grouped.values());
  if (ungrouped.length > 0) {
    groups.push({ key: "ungrouped", heading: t("documents.tab.group.other"), documents: ungrouped });
  }
  return groups;
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
