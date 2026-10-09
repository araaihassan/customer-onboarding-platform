"use client";

import { Switch } from "@/components/ui/Switch";
import { Tabs } from "@/components/ui/Tabs";
import { SkeletonRows, ErrorState } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import { usePreferences, useUpdatePreferences } from "@/lib/api/notifications";
import type { Preferences, TypePreference, UpdatePreferencesRequest } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";

const CADENCES = ["IMMEDIATE", "DAILY", "WEEKLY"] as const;

/**
 * Every change sends the whole preference set: the PUT is a full replace (plan amendment 11).
 * Locked types (Escalation) are omitted -- the backend accepts that and stores nothing for them,
 * and listing one with a channel off is a 422.
 */
function body(
  prefs: Preferences,
  patch: { emailCadence?: UpdatePreferencesRequest["emailCadence"]; type?: TypePreference },
): UpdatePreferencesRequest {
  return {
    emailCadence: patch.emailCadence ?? prefs.emailCadence ?? "IMMEDIATE",
    types: (prefs.types ?? [])
      .filter((tp) => !tp.locked)
      .map((tp) => (patch.type && tp.type === patch.type.type ? patch.type : tp))
      .map((tp) => ({ type: tp.type!, inApp: tp.inApp ?? true, email: tp.email ?? true })),
  };
}

export function PreferencesPane() {
  const prefs = usePreferences(true);
  const update = useUpdatePreferences();
  const toast = useToast();

  if (prefs.isLoading) return <SkeletonRows rows={5} height={36} />;
  if (prefs.isError || !prefs.data) {
    return <ErrorState message={t("common.error")} onRetry={() => void prefs.refetch()} />;
  }
  const data = prefs.data;
  const save = (next: UpdatePreferencesRequest) =>
    update.mutate(next, { onError: () => toast.show(t("notifications.prefs.saveFailed")) });

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-14)", padding: "14px 16px" }}>
      <p className="text-text-muted" style={{ font: "11.5px/1.5 var(--ob-font-family-ui)", margin: 0 }}>
        {t("notifications.prefs.intro")}
      </p>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
        <span className="text-text-subtle" style={{ font: "500 11.5px/1.3 var(--ob-font-family-ui)" }}>
          {t("notifications.prefs.delivery")}
        </span>
        <Tabs
          items={CADENCES.map((c) => ({ id: c, label: t(`notifications.cadence.${c}`) }))}
          value={data.emailCadence ?? "IMMEDIATE"}
          onChange={(id) => save(body(data, { emailCadence: id as UpdatePreferencesRequest["emailCadence"] }))}
        />
        <span className="text-text-faint" style={{ font: "10.5px/1.4 var(--ob-font-family-ui)" }}>
          {t("notifications.prefs.digestCaption")}
        </span>
      </div>
      <ul className="flex flex-col" style={{ listStyle: "none", margin: 0, padding: 0 }}>
        {(data.types ?? []).map((tp) => (
          <li key={tp.type} className="flex flex-col border-b border-line-soft" style={{ padding: "10px 0", gap: "6px" }}>
            <span style={{ font: "600 12.5px/1.35 var(--ob-font-family-ui)" }}>{tp.label}</span>
            {tp.locked && (
              <span className="text-text-faint" style={{ font: "10.5px/1.4 var(--ob-font-family-ui)" }}>
                {t("notifications.prefs.required")}
              </span>
            )}
            <div className="grid grid-cols-2" style={{ gap: "var(--ob-space-12)" }}>
              <Switch
                checked={tp.inApp ?? true}
                disabled={tp.locked}
                label={t("notifications.prefs.inApp")}
                ariaLabel={t("notifications.prefs.inAppFor", { type: tp.label ?? "" })}
                onChange={(v) => save(body(data, { type: { ...tp, inApp: v } }))}
              />
              <Switch
                checked={tp.email ?? true}
                disabled={tp.locked}
                label={t("notifications.prefs.email")}
                ariaLabel={t("notifications.prefs.emailFor", { type: tp.label ?? "" })}
                onChange={(v) => save(body(data, { type: { ...tp, email: v } }))}
              />
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
