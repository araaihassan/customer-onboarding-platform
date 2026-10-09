"use client";

import { HorizonsCard } from "@/components/notifications/HorizonsCard";
import { TemplatesCard } from "@/components/notifications/TemplatesCard";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { t } from "@/lib/i18n";

/** Administration -> Notifications. Deadlines and reminders, then message templates. */
export default function NotificationsAdminPage() {
  useSetPageHeader(t("notifications.admin.title"));

  return (
    <section>
      <h2 className="sr-only">{t("notifications.admin.title")}</h2>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
        <HorizonsCard />
        <TemplatesCard />
      </div>
    </section>
  );
}
