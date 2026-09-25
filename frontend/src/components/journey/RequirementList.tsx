"use client";

import { useState } from "react";
import { UploadDialog } from "@/components/documents/UploadDialog";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { StatusPill } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, useSatisfy, useWaive, type RequirementRoadmap } from "@/lib/api/cases";
import { downloadDocumentVersion, useDocument, useDocumentRequests, useFulfilRequest, type Document } from "@/lib/api/documents";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The milestone panel's left column (uispecs §5a). The one deliberate
 * departure from the prototype: its checkbox state is "real and local", but
 * here satisfying a requirement recomputes the milestone, possibly the stage
 * transition and the case percentage, inside one locked server transaction --
 * local optimism would show a milestone completing that write_scope then
 * refuses. Every checkbox waits for the response before it changes.
 *
 * A write_scope 403 renders as an explanation next to the row it refused, not
 * a disappearance -- the status exists precisely so the UI can say "this
 * stage is owner-only" rather than the requirement looking like it vanished,
 * which is what a 404 would have meant here.
 */
export function RequirementList({
  caseId,
  milestoneId,
  requirements,
}: {
  caseId: string;
  milestoneId: string;
  requirements: RequirementRoadmap[];
}) {
  const canWaive = useHasPermission("requirement.waive");
  const satisfy = useSatisfy();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [waiving, setWaiving] = useState<string | undefined>();
  // The parent's roadmap query invalidates and refetches on success, but that
  // round trip is a second request -- this reflects the satisfy response the
  // instant it arrives, so the checkbox does not sit unchecked in between.
  const [locallySatisfied, setLocallySatisfied] = useState<Set<string>>(new Set());

  function toggle(requirementId: string) {
    setErrors((prev) => ({ ...prev, [requirementId]: "" }));
    satisfy.mutate(
      { caseId, requirementId },
      {
        onSuccess: () => setLocallySatisfied((prev) => new Set(prev).add(requirementId)),
        onError: (error) => {
          const message = error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
          setErrors((prev) => ({ ...prev, [requirementId]: message }));
        },
      },
    );
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("requirement.sectionTitle")}
      </h5>

      {requirements.map((requirement) => {
        if (requirement.kind === "DOCUMENT") {
          return <DocumentChip key={requirement.id} caseId={caseId} requirement={requirement} />;
        }

        const settled = requirement.status !== "OPEN" || locallySatisfied.has(requirement.id!);
        const pending = satisfy.isPending && satisfy.variables?.requirementId === requirement.id;

        return (
          <div key={requirement.id} className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
            <div className="flex items-center justify-between" style={{ gap: "var(--ob-space-8)" }}>
              <label className="inline-flex items-center" style={{ gap: "var(--ob-space-8)" }}>
                <input
                  type="checkbox"
                  checked={settled}
                  disabled={settled || pending}
                  onChange={() => toggle(requirement.id!)}
                />
                <span
                  className="text-ink"
                  style={{
                    font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
                    textDecoration: settled ? "line-through" : "none",
                    color: settled ? "var(--ob-text-faint)" : "var(--ob-ink)",
                  }}
                >
                  {requirement.label}
                </span>
              </label>

              {canWaive && requirement.status === "OPEN" && !settled && (
                <Button type="button" variant="secondary" onClick={() => setWaiving(requirement.id)}>
                  {t("requirement.waive")}
                </Button>
              )}
            </div>

            {errors[requirement.id!] && (
              <p role="alert" style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-small-print-size)/var(--ob-type-small-print-line) var(--ob-font-family-ui)" }}>
                {errors[requirement.id!]}
              </p>
            )}
          </div>
        );
      })}

      {waiving && (
        <WaiveDialog
          caseId={caseId}
          requirementId={waiving}
          onClose={() => setWaiving(undefined)}
        />
      )}
    </div>
  );
}

/**
 * `satisfiedRef`/`satisfiedRefType` were added to `RequirementRoadmapView`
 * specifically so this chip could stop being a dead-end status label and
 * link to whatever actually satisfied the requirement -- for now, only
 * `satisfiedRefType === "document"` is rendered as a link (the one kind this
 * chip's own `kind === "DOCUMENT"` guard at the call site ever produces); a
 * future satisfier type (a task, an agreement) would need its own branch
 * here rather than assuming this shape.
 *
 * The upload/fulfil action below closes the gap that left this chip
 * read-only: `DocumentInstantiation` already creates an OPEN
 * `document_request` for every DOCUMENT requirement the moment a case opens
 * (`GET /cases/{caseId}/document-requests`, Task 36), but nothing anywhere
 * in the frontend ever called `fulfil` on it. Uploading here reuses the same
 * `UploadDialog` the Documents tab already has, pre-filled from the matching
 * request's own `category`/`description` (real data, not a guess), and
 * chains `useFulfilRequest` onto its `onUploaded` callback the instant the
 * new document exists. When `requiresReview` is true, `fulfil` moves the
 * request to FULFILLED but deliberately leaves the requirement OPEN
 * (`DocumentRequestService.fulfil`'s own documented branching) -- rendered
 * here as "Pending review" rather than silently looking like nothing
 * happened, since the alternative (still just an OPEN chip with no
 * indication a file was ever submitted) is worse than a chip that cannot
 * yet be re-uploaded to.
 */
function DocumentChip({ caseId, requirement }: { caseId: string; requirement: RequirementRoadmap }) {
  const hasSatisfyingDocument = requirement.satisfiedRefType === "document" && Boolean(requirement.satisfiedRef);
  const document = useDocument(hasSatisfyingDocument ? requirement.satisfiedRef! : "");
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);

  const canUpload = useHasPermission("document.upload");
  const documentRequests = useDocumentRequests(caseId);
  const fulfil = useFulfilRequest();

  const matchingRequest = documentRequests.data?.content?.find((dr) => dr.requirementId === requirement.id);

  async function open() {
    const doc = document.data;
    if (!doc?.id || !doc.currentVersionNumber) return;
    setDownloading(true);
    try {
      await downloadDocumentVersion(doc.id, doc.currentVersionNumber, doc.name ?? doc.id);
    } finally {
      setDownloading(false);
    }
  }

  function onUploaded(created: Document) {
    if (!matchingRequest?.id || !created.id) return;
    fulfil.mutate({ id: matchingRequest.id, documentId: created.id });
  }

  return (
    <div
      className="flex items-center justify-between bg-surface border border-line"
      style={{ padding: "var(--ob-space-8) var(--ob-space-11)", borderRadius: "var(--ob-radius-5)", gap: "var(--ob-space-8)" }}
    >
      <div className="min-w-0 flex-1">
        <span
          className="text-ink truncate"
          style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {requirement.label}
        </span>
        {document.data?.name && (
          <p className="truncate text-text-subtle" style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}>
            {document.data.name}
          </p>
        )}
      </div>

      {document.data?.id && document.data.currentVersionNumber && (
        <Button type="button" variant="secondary" disabled={downloading} onClick={() => void open()}>
          {t("requirement.document.open")}
        </Button>
      )}

      {!hasSatisfyingDocument && matchingRequest?.status === "OPEN" && canUpload && (
        <Button type="button" variant="secondary" onClick={() => setUploading(true)}>
          {t("requirement.document.upload")}
        </Button>
      )}

      {!hasSatisfyingDocument && matchingRequest?.status === "FULFILLED" ? (
        <StatusPill status={t("requirement.document.pendingReview")} role="warn" />
      ) : (
        <StatusPill
          status={requirement.status === "OPEN" ? t("requirement.status.OPEN") : t(`requirement.status.${requirement.status}`)}
          role={requirement.status === "OPEN" ? "neutral" : "ok"}
        />
      )}

      {uploading && (
        <UploadDialog
          caseId={caseId}
          onClose={() => setUploading(false)}
          onUploaded={onUploaded}
          defaultName={matchingRequest?.description ?? ""}
          defaultCategory={matchingRequest?.category ?? "OTHER"}
        />
      )}
    </div>
  );
}

function WaiveDialog({ caseId, requirementId, onClose }: { caseId: string; requirementId: string; onClose: () => void }) {
  const waive = useWaive();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  return (
    <Dialog title={t("requirement.waiveDialog.title")} onClose={onClose}>
      <Field
        label={t("requirement.waiveDialog.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />

      {waive.isError && (
        <p role="alert" style={{ color: "var(--ob-risk-fg)", marginTop: "var(--ob-space-11)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}>
          {waive.error instanceof ApiError ? parseProblemDetail(waive.error.message) : t("common.error")}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button
          type="button"
          disabled={waive.isPending}
          onClick={() => {
            const trimmed = reason.trim();
            if (!trimmed) {
              setReasonError(t("customer.form.required"));
              return;
            }
            waive.mutate({ caseId, requirementId, reason: trimmed }, { onSuccess: onClose });
          }}
        >
          {t("requirement.waiveDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
