"use client";

import { useMemo } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { FileSignatureIcon } from "@/components/icons";
import { AgreementDetailPanel } from "@/components/agreements/AgreementDetailPanel";
import { AgreementRow } from "@/components/agreements/AgreementRow";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { useCaseAgreements } from "@/lib/api/agreements";
import { t } from "@/lib/i18n";

/**
 * The case workspace's Agreements tab (Task 24): live agreements as rows, cancelled ones collapsed
 * under "Replaced (n)". A cancelled agreement's successor draft is a separate, live row. `?agreement={id}`
 * (also the roadmap chip's deep link) mounts the detail panel. `now` is injectable so
 * the "Expires in Nd" cue is testable.
 */
export function AgreementsTab({ caseId, now }: { caseId: string; now?: Date }) {
  const agreements = useCaseAgreements(caseId);
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const openId = params.get("agreement");
  const clock = useMemo(() => now ?? new Date(), [now]);

  const rows = agreements.data ?? [];
  const live = rows.filter((a) => a.displayStatus !== "CANCELLED");
  const replaced = rows.filter((a) => a.displayStatus === "CANCELLED");

  function open(id: string) {
    const next = new URLSearchParams(params.toString());
    next.set("tab", "agreements");
    next.set("agreement", id);
    router.replace(`${pathname}?${next.toString()}`);
  }

  function close() {
    const next = new URLSearchParams(params.toString());
    next.delete("agreement");
    next.set("tab", "agreements");
    router.replace(`${pathname}?${next.toString()}`);
  }

  if (agreements.isLoading) return <SkeletonRows rows={3} height={56} />;
  if (agreements.isError) return <ErrorState message={t("common.error")} onRetry={() => void agreements.refetch()} />;

  if (rows.length === 0) {
    return (
      <EmptyState
        icon={<FileSignatureIcon size={28} />}
        title={t("agreements.tab.empty.title")}
        description={t("agreements.tab.empty.description")}
      />
    );
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
        {live.map((a) => (
          <AgreementRow key={a.id} agreement={a} now={clock} onOpen={open} />
        ))}
      </div>

      {replaced.length > 0 && (
        <details>
          <summary className="cursor-pointer text-text-subtle" style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}>
            {t("agreements.tab.replaced", { count: String(replaced.length) })}
          </summary>
          <div className="flex flex-col" style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-8)" }}>
            {replaced.map((a) => (
              <AgreementRow key={a.id} agreement={a} now={clock} onOpen={open} />
            ))}
          </div>
        </details>
      )}

      {openId && <AgreementDetailPanel key={openId} id={openId} onClose={close} onOpen={open} />}
    </div>
  );
}
