import Link from "next/link";
import { DataTable } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { FileSignatureIcon } from "@/components/icons";
import type { Agreement } from "@/lib/api/agreements";
import { t } from "@/lib/i18n";
import { daysUntilExpiry, formatAgreementDate } from "./AgreementRow";
import { statusLabelKey, statusTone, toneRole } from "./statusChip";

const EMPTY = "—";

const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;
const NAME_TEXT = {
  font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;
/** 11px mono, text-subtle: the status cell's date phrase (`SCREENS.md` §8). */
const PHRASE_TEXT = {
  font: "11px/1.3 var(--ob-font-family-data)",
} as const;

/** `File-backed · v3` / `Structured + file · v2` / `Structured record only` (QA Q13). */
export function recordModeCell(a: Agreement): string {
  if (!a.recordMode) return EMPTY;
  const mode = t(`agreement.recordMode.${a.recordMode}`);
  if (a.recordMode === "STRUCTURED_ONLY" || !a.latestVersionNumber) return mode;
  return t("agreements.table.recordModeVersion", { mode, version: String(a.latestVersionNumber) });
}

/** "signed 14 Aug" / "expires in 12d"; the backend sends no sent-at, so SENT carries no phrase. */
export function datePhrase(a: Agreement, now: Date): string | null {
  const expiresIn = daysUntilExpiry(a, now);
  if (expiresIn !== null) return t("agreements.table.expiresIn", { days: String(expiresIn) });
  if ((a.displayStatus === "SIGNED" || a.displayStatus === "EXPIRED") && a.signedAt) {
    return t("agreements.table.signedOn", { date: formatAgreementDate(a.signedAt).replace(/ \d{4}$/, "") });
  }
  return null;
}

function hrefFor(a: Agreement, slug: string): string | null {
  if (!a.customerId || !a.caseId || !a.id) return null;
  return `/t/${slug}/customers/${a.customerId}/cases/${a.caseId}?tab=agreements&agreement=${a.id}`;
}

function StatusCell({ agreement, now }: { agreement: Agreement; now: Date }) {
  const status = agreement.displayStatus;
  const phrase = datePhrase(agreement, now);
  return (
    <span className="inline-flex flex-wrap items-center" style={{ gap: "var(--ob-space-6)" }}>
      {status && <StatusPill status={t(statusLabelKey(status))} role={toneRole(statusTone(status))} />}
      {phrase && (
        <span className="text-text-subtle" style={PHRASE_TEXT}>
          {phrase}
        </span>
      )}
    </span>
  );
}

function NameLink({ agreement, slug }: { agreement: Agreement; slug: string }) {
  const href = hrefFor(agreement, slug);
  const label = agreement.name || EMPTY;
  return href ? (
    <Link href={href} className="text-ink hover:underline truncate" style={NAME_TEXT}>
      {label}
    </Link>
  ) : (
    <span className="text-ink truncate" style={NAME_TEXT}>
      {label}
    </span>
  );
}

/**
 * The `agreements` table (`SCREENS.md` §8), grid `1.5fr 1fr 1.2fr .8fr 1.5fr`, composed on
 * `DataTable` the way `DocumentTable` is (stacked cards below 900px). `ownerNames` is the
 * caller's id -> name map: an owner it could not resolve renders nothing, never the raw id.
 */
export function AgreementTable({
  agreements,
  slug,
  now,
  ownerNames,
}: {
  agreements: Agreement[];
  slug: string;
  now: Date;
  ownerNames: Record<string, string>;
}) {
  if (agreements.length === 0) {
    return (
      <EmptyState
        icon={<FileSignatureIcon size={28} />}
        title={t("agreements.list.empty.title")}
        description={t("agreements.list.empty.description")}
      />
    );
  }

  const ownerOf = (a: Agreement) => (a.ownerUserId ? ownerNames[a.ownerUserId] : undefined);

  const columns = [
    {
      key: "agreement",
      label: t("agreements.table.agreement"),
      width: "1.5fr",
      render: (a: Agreement) => <NameLink agreement={a} slug={slug} />,
    },
    {
      key: "customer",
      label: t("agreements.table.customer"),
      width: "1fr",
      render: (a: Agreement) => (
        <span className="text-text-2" style={BODY_TEXT}>
          {a.customerName || EMPTY}
        </span>
      ),
    },
    {
      key: "recordMode",
      label: t("agreements.table.recordMode"),
      width: "1.2fr",
      render: (a: Agreement) => (
        <span className="text-text-2" style={BODY_TEXT}>
          {recordModeCell(a)}
        </span>
      ),
    },
    {
      key: "owner",
      label: t("agreements.table.owner"),
      width: ".8fr",
      render: (a: Agreement) => (
        <span className="text-text-2" style={BODY_TEXT}>
          {ownerOf(a) ?? ""}
        </span>
      ),
    },
    {
      key: "status",
      label: t("agreements.table.status"),
      width: "1.5fr",
      render: (a: Agreement) => <StatusCell agreement={a} now={now} />,
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={agreements}
      getRowKey={(a) => a.id ?? ""}
      stackedColumn={(a) => (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
          <NameLink agreement={a} slug={slug} />
          <p className="text-text-subtle truncate" style={BODY_TEXT}>
            {[a.customerName, ownerOf(a)].filter(Boolean).join(" · ") || EMPTY}
          </p>
          <p className="text-text-subtle" style={PHRASE_TEXT}>
            {recordModeCell(a)}
          </p>
          <StatusCell agreement={a} now={now} />
        </div>
      )}
    />
  );
}
