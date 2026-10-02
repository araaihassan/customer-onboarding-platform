"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { AgreementTable } from "@/components/agreements/AgreementTable";
import { LifecycleCard } from "@/components/agreements/LifecycleCard";
import { statusLabelKey } from "@/components/agreements/statusChip";
import { FileSignatureIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Pagination } from "@/components/ui/Pagination";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { useUsers } from "@/lib/api/admin";
import { useAgreements, useAgreementSummary, type AgreementDisplayStatus } from "@/lib/api/agreements";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

const STATUS_FILTERS: AgreementDisplayStatus[] = [
  "DRAFT",
  "UNDER_REVIEW",
  "APPROVED",
  "SENT",
  "AWAITING_SIGNATURE",
  "SIGNED",
  "EXPIRED",
  "CANCELLED",
];

/**
 * The tenant-wide operator `agreements` screen (`SCREENS.md` §8): the lifecycle card, a status
 * filter row and the table. Owner names resolve through the user list only when the viewer holds
 * `user.view` (the request is not even fired otherwise); an owner that is not on that first page
 * renders blank rather than a raw id. No portal UI exists here -- that is sub-project 7.
 */
export default function AgreementsPage() {
  const { slug } = useParams<{ slug: string }>();
  const [status, setStatus] = useState<AgreementDisplayStatus | undefined>(undefined);
  const [page, setPage] = useState(0);

  const agreements = useAgreements(status, page);
  const summary = useAgreementSummary();
  const canViewUsers = useHasPermission("user.view");
  const users = useUsers("", 0, canViewUsers);

  useSetPageHeader(t("agreements.list.title"));

  const ownerNames = useMemo(() => {
    const map: Record<string, string> = {};
    for (const u of users.data?.content ?? []) if (u.id && u.fullName) map[u.id] = u.fullName;
    return map;
  }, [users.data]);

  const now = useMemo(() => new Date(), []);

  function changeFilter(next: AgreementDisplayStatus | undefined) {
    setStatus(next);
    setPage(0);
  }

  const rows = agreements.data?.content ?? [];
  const totalPages = agreements.data?.totalPages ?? 0;

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <h2 className="sr-only">{t("agreements.list.title")}</h2>

      {summary.data && <LifecycleCard summary={summary.data} />}

      <div
        role="group"
        aria-label={t("agreements.list.filter.label")}
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-6)" }}
      >
        <Button
          type="button"
          variant={status === undefined ? "filter-active" : "filter-idle"}
          aria-pressed={status === undefined}
          onClick={() => changeFilter(undefined)}
        >
          {t("agreements.list.filter.all")}
        </Button>
        {STATUS_FILTERS.map((s) => (
          <Button
            key={s}
            type="button"
            variant={status === s ? "filter-active" : "filter-idle"}
            aria-pressed={status === s}
            onClick={() => changeFilter(s)}
          >
            {t(statusLabelKey(s))}
          </Button>
        ))}
      </div>

      {agreements.isLoading ? (
        <SkeletonRows rows={6} height={56} />
      ) : agreements.isError ? (
        <EmptyState
          icon={<FileSignatureIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void agreements.refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : (
        <>
          <AgreementTable agreements={rows} slug={slug} now={now} ownerNames={ownerNames} />
          {totalPages > 1 && (
            <Pagination
              label={t("agreements.list.page.nav")}
              page={page}
              totalPages={totalPages}
              onChange={setPage}
              disabled={agreements.isFetching}
            />
          )}
        </>
      )}
    </section>
  );
}
