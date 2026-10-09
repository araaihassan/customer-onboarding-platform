"use client";

import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { t } from "@/lib/i18n";
import { useInbox, useMarkRead, type NotificationItem } from "@/lib/api/notifications";
import { InboxRow } from "./InboxRow";

/** The inbox's scrolling list: newest first, cursor-paged. Opening a row marks it read and follows its link. */
export function InboxList({ onNavigate }: { onNavigate: () => void }) {
  const router = useRouter();
  const inbox = useInbox(true);
  const markRead = useMarkRead();

  const open = (item: NotificationItem) => {
    if (!item.read && item.id) markRead.mutate(item.id);
    if (item.linkPath) router.push(item.linkPath);
    onNavigate();
  };

  if (inbox.isLoading) {
    return (
      <div style={{ padding: "12px 16px" }}>
        <SkeletonRows rows={3} height={52} />
      </div>
    );
  }
  if (inbox.isError) {
    return (
      <div style={{ padding: "12px 16px" }}>
        <ErrorState message={t("inbox.error")} onRetry={() => void inbox.refetch()} />
      </div>
    );
  }

  const items = (inbox.data?.pages ?? []).flatMap((p) => p.items ?? []);
  if (items.length === 0) {
    return (
      <p className="text-text-subtle" style={{ padding: "32px 16px", textAlign: "center", font: "12.5px/1.4 var(--ob-font-family-ui)" }}>
        {t("inbox.empty")}
      </p>
    );
  }

  return (
    <div>
      {items.map((item) => (
        <InboxRow key={item.id} item={item} onOpen={open} />
      ))}
      {inbox.hasNextPage && (
        <div style={{ padding: "10px 16px", textAlign: "center" }}>
          <Button variant="text-link" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>
            {t("inbox.loadMore")}
          </Button>
        </div>
      )}
    </div>
  );
}
