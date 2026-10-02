"use client";

import { StatusPill } from "@/components/ui/StatusPill";
import type { AgreementVersion } from "@/lib/api/agreements";
import { t } from "@/lib/i18n";
import { formatAgreementDate } from "./AgreementRow";

const MONO = "var(--ob-font-family-data)";

/** The first 12 hex characters of a content digest; the full value stays available as a title. */
export function shortHash(sha: string | undefined): string {
  return sha ? sha.slice(0, 12) : "—";
}

function outcome(v: AgreementVersion): { label: string; role: "ok" | "risk" | "neutral" } {
  if (v.reviewDecision === "APPROVE") return { label: t("agreements.versions.approved"), role: "ok" };
  if (v.reviewDecision === "REJECT") return { label: t("agreements.versions.rejected"), role: "risk" };
  return { label: t("agreements.versions.pending"), role: "neutral" };
}

/** Immutable version rows, newest first: version number, short content hash, and the review outcome as a word. */
export function VersionHistory({ versions }: { versions: AgreementVersion[] }) {
  if (versions.length === 0) {
    return <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>{t("agreements.versions.empty")}</p>;
  }
  const ordered = [...versions].sort((a, b) => (b.versionNumber ?? 0) - (a.versionNumber ?? 0));
  return (
    <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      {ordered.map((v) => {
        const o = outcome(v);
        return (
          <li
            key={v.id ?? v.versionNumber}
            className="border border-line bg-surface"
            style={{ borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-8) var(--ob-space-11)" }}
          >
            <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
              <span className="text-ink" style={{ fontFamily: MONO, fontWeight: 600, fontSize: "12.5px" }}>
                {t("agreements.versions.number", { version: String(v.versionNumber ?? "") })}
              </span>
              <span title={v.contentSha256} className="text-text-subtle" style={{ fontFamily: MONO, fontSize: "12px" }}>
                {shortHash(v.contentSha256)}
              </span>
              {v.submittedAt && (
                <span className="text-text-faint" style={{ fontFamily: MONO, fontSize: "11.5px" }}>
                  {t("agreements.versions.submitted", { date: formatAgreementDate(v.submittedAt) })}
                </span>
              )}
              <span className="ml-auto">
                <StatusPill status={o.label} role={o.role} />
              </span>
            </div>
            {v.reviewReason && (
              <p className="text-text-subtle" style={{ fontSize: "12.5px", marginTop: "var(--ob-space-4)" }}>
                {v.reviewReason}
              </p>
            )}
          </li>
        );
      })}
    </ul>
  );
}
