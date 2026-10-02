"use client";

import { StatusPill } from "@/components/ui/StatusPill";
import type { AgreementSignatory, AgreementSignature } from "@/lib/api/agreements";
import { t } from "@/lib/i18n";
import { formatAgreementDate } from "./AgreementRow";

/** Who has signed and who has not, with the date and method for each recorded signature. */
export function SignatureList({ signatories, signatures }: { signatories: AgreementSignatory[]; signatures: AgreementSignature[] }) {
  if (signatories.length === 0) {
    return <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>{t("agreements.signatures.empty")}</p>;
  }
  const ordered = [...signatories].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  return (
    <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      {ordered.map((s) => {
        const sig = signatures.find((g) => g.signatoryId === s.id);
        const signed = s.signed || Boolean(sig);
        return (
          <li
            key={s.id}
            className="flex items-center border border-line bg-surface"
            style={{ gap: "var(--ob-space-11)", borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-8) var(--ob-space-11)" }}
          >
            <div className="min-w-0 flex-1">
              <p className="truncate text-ink" style={{ fontWeight: 600, fontSize: "13px" }}>{s.displayName}</p>
              <p className="truncate text-text-subtle" style={{ fontSize: "12px" }}>{s.displayRole}</p>
              {signed && sig?.signedOn && (
                <p className="text-text-subtle" style={{ fontFamily: "var(--ob-font-family-data)", fontSize: "11.5px" }}>
                  {t("agreements.signatures.signedOn", { date: formatAgreementDate(sig.signedOn), method: sig.method ?? "—" })}
                </p>
              )}
            </div>
            <StatusPill
              status={signed ? t("agreements.signatures.signed") : t("agreements.signatures.pending")}
              role={signed ? "ok" : "neutral"}
            />
          </li>
        );
      })}
    </ul>
  );
}
