"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { downloadDocumentVersion, useDocument } from "@/lib/api/documents";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * "Open file" for an agreement's document. Reuses the shared authenticated download; the version it
 * fetches is the document's current one (the countersigned copy, once appended, is the newest).
 * Downloading is gated by document.view on the server, so without it the button is not rendered at
 * all rather than shown dead.
 */
export function OpenAgreementFile({ documentId }: { documentId: string }) {
  const canView = useHasPermission("document.view");
  if (!canView) return null;
  return <OpenButton documentId={documentId} />;
}

function OpenButton({ documentId }: { documentId: string }) {
  const doc = useDocument(documentId);
  const [downloading, setDownloading] = useState(false);
  const [failed, setFailed] = useState(false);
  const version = doc.data?.currentVersionNumber;

  async function open() {
    if (!version) return;
    setDownloading(true);
    setFailed(false);
    try {
      await downloadDocumentVersion(documentId, version, doc.data?.name ?? documentId);
    } catch {
      setFailed(true);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <span className="inline-flex items-center" style={{ gap: "var(--ob-space-8)" }}>
      <Button type="button" variant="secondary" disabled={!version || downloading} onClick={() => void open()}>
        {t("agreements.file.open")}
      </Button>
      {failed && (
        <span role="alert" style={{ color: "var(--ob-risk-fg)", fontSize: "12.5px" }}>
          {t("agreements.file.openError")}
        </span>
      )}
    </span>
  );
}
