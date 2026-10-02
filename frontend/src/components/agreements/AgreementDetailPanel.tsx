"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { XIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { StatusPill } from "@/components/ui/StatusPill";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { recordModeIncludesFile, useAgreement } from "@/lib/api/agreements";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { agreementMeta } from "./AgreementRow";
import { AgreementActions } from "./AgreementActions";
import { DraftEditor } from "./DraftEditor";
import { OpenAgreementFile } from "./OpenAgreementFile";
import { SignatoryEditor } from "./SignatoryEditor";
import { SignatureList } from "./SignatureList";
import { VersionHistory } from "./VersionHistory";
import { statusLabelKey, statusTone, toneRole } from "./statusChip";

/**
 * The agreement detail panel, mounted inline under the case's Agreements list by `?agreement={id}`.
 * It is a region, not a modal: the list stays reachable. Focus moves to its heading when the agreement
 * has loaded, Escape or the close control dismiss it, and focus returns to whatever opened it.
 *
 * Editing is DRAFT-only and needs agreement.manage; the server enforces both independently. Every
 * write carries the agreement's current lockVersion (see DraftEditor), and a stale one (409) shows the
 * reload message and refetches. Lifecycle actions (submit, review, send, record, cancel) live in AgreementActions; cancelling opens the
 * successor draft through `onOpen`.
 */
export function AgreementDetailPanel({ id, onClose, onOpen }: { id: string; onClose: () => void; onOpen?: (id: string) => void }) {
  const detail = useAgreement(id);
  const canManage = useHasPermission("agreement.manage");
  const [message, setMessage] = useState<string>();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const loaded = Boolean(detail.data?.agreement);

  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null;
    const opener = openerRef.current;
    return () => opener?.focus();
  }, []);

  useEffect(() => {
    if (loaded) headingRef.current?.focus();
  }, [loaded]);

  function handleError(err: unknown) {
    if (err instanceof ApiError && err.status === 409) {
      setMessage(t("agreements.detail.conflict"));
      void detail.refetch();
    } else {
      setMessage(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error"));
    }
  }

  const agreement = detail.data?.agreement;
  const status = agreement?.status;
  const editable = status === "DRAFT" && canManage;
  const display = agreement?.displayStatus;

  return (
    <section
      role="region"
      aria-label={t("agreements.tab.detailSlot")}
      data-agreement-id={id}
      onKeyDown={(e) => {
        if (e.key === "Escape") onClose();
      }}
      className="border border-line bg-surface"
      style={{ borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-16)" }}
    >
      {detail.isLoading ? (
        <SkeletonRows rows={4} height={40} />
      ) : detail.isError || !agreement ? (
        <ErrorState message={t("agreements.detail.loadError")} onRetry={() => void detail.refetch()} />
      ) : (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
          <header className="flex items-start" style={{ gap: "var(--ob-space-11)" }}>
            <div className="min-w-0 flex-1">
              <h3
                ref={headingRef}
                tabIndex={-1}
                className="text-ink"
                style={{ font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)", outline: "none" }}
              >
                {agreement.name}
              </h3>
              <p className="text-text-subtle" style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-data)" }}>
                {agreementMeta(agreement)}
              </p>
            </div>
            {display && <StatusPill status={t(statusLabelKey(display))} role={toneRole(statusTone(display))} />}
            <Button type="button" variant="secondary" aria-label={t("agreements.detail.close")} onClick={onClose} style={{ width: 32, padding: 0 }}>
              <XIcon size={14} />
            </Button>
          </header>

          {detail.data && (
            <AgreementActions detail={detail.data} onOpenAgreement={(next) => onOpen?.(next)} onStale={() => void detail.refetch()} />
          )}

          {!editable && (
            <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>
              {status === "DRAFT" ? t("agreements.detail.readOnlyNoManage") : t("agreements.detail.readOnly")}
            </p>
          )}

          {message && (
            <p
              role="alert"
              style={{
                color: "var(--ob-risk-fg)",
                background: "var(--ob-risk-bg)",
                border: "1px solid var(--ob-risk-border)",
                borderRadius: "var(--ob-radius-9)",
                padding: "var(--ob-space-8) var(--ob-space-11)",
                fontSize: "12.5px",
              }}
            >
              {message}
            </p>
          )}

          {/* A fresh lockVersion remounts the forms so local edits never sit on top of a stale baseline. */}
          <div
            className="flex flex-col"
            style={{ gap: "var(--ob-space-16)" }}
            onClickCapture={() => setMessage(undefined)}
            onChangeCapture={() => setMessage(undefined)}
          >
            <Section title={t("agreements.detail.section.terms")}>
              <DraftEditor key={`terms-${agreement.lockVersion}`} agreement={agreement} editable={editable} onError={handleError} />
            </Section>

            {!editable && recordModeIncludesFile(agreement.recordMode ?? "FILE_BACKED") && (
              <Section title={t("agreements.detail.section.file")}>
                <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
                  <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>
                    {agreement.documentId ? t("agreements.file.attachedReadOnly") : t("agreements.file.noneReadOnly")}
                  </p>
                  {agreement.documentId && <OpenAgreementFile documentId={agreement.documentId} />}
                </div>
              </Section>
            )}

            {status === "DRAFT" ? (
              <Section title={t("agreements.detail.section.signatories")}>
                <SignatoryEditor
                  key={`sig-${agreement.lockVersion}`}
                  agreement={agreement}
                  signatories={detail.data?.signatories ?? []}
                  editable={editable}
                  onError={handleError}
                />
              </Section>
            ) : (
              <Section title={t("agreements.detail.section.signatures")}>
                <SignatureList signatories={detail.data?.signatories ?? []} signatures={detail.data?.signatures ?? []} />
              </Section>
            )}

            <Section title={t("agreements.detail.section.versions")}>
              <VersionHistory versions={detail.data?.versions ?? []} />
            </Section>
          </div>
        </div>
      )}
    </section>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <h4 className="text-ink" style={{ fontWeight: 600, fontSize: "13px" }}>{title}</h4>
      {children}
    </div>
  );
}
