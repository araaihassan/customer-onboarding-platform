"use client";

import { useEffect, useId, useRef, useState } from "react";
import { XIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Chip } from "@/components/ui/Chip";
import { ErrorState, SkeletonRows } from "@/components/ui/States";
import { Switch } from "@/components/ui/Switch";
import { useToast } from "@/components/ui/Toast";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useNotificationPolicy, useUpdateNotificationPolicy } from "@/lib/api/notifications";
import type { NotificationPolicy } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";

/**
 * Administration -> Notifications -> Deadlines and reminders.
 *
 * Built from existing primitives only (the design bundle has no screen for it):
 * a flat card, one row per deadline kind, each lead time a mono chip that is
 * itself the remove button, status colour only on validation text (always with
 * words). One Save sends the whole policy because the PUT is a full replace --
 * a missing kind or a null list is a 400 server-side, an empty list means "off".
 *
 * Bounds mirror NotificationAdminService.replacePolicy and AutoRemindView:
 * lead times are integers 1..90, at most five per kind, no repeats; auto-remind
 * interval 1..30 days, max 1..10. The client check only saves a round trip; the
 * server's problem detail is shown whenever it still refuses.
 */
type Kind =
  | "TASK_DUE"
  | "MILESTONE_DUE"
  | "DOCUMENT_REQUEST_DUE"
  | "DOCUMENT_EXPIRY"
  | "AGREEMENT_EXPIRY"
  | "AGREEMENT_RENEWAL";

/** Mirrors backend HorizonKind.businessDays: due dates count business days, expiries calendar days. */
const UNIT: Record<Kind, "business" | "calendar"> = {
  TASK_DUE: "business",
  MILESTONE_DUE: "business",
  DOCUMENT_REQUEST_DUE: "business",
  DOCUMENT_EXPIRY: "calendar",
  AGREEMENT_EXPIRY: "calendar",
  AGREEMENT_RENEWAL: "calendar",
};
const KINDS = Object.keys(UNIT) as Kind[];

const LEAD_MIN = 1;
const LEAD_MAX = 90;
const LEADS_PER_KIND = 5;
const INTERVAL_MAX = 30;
const REMIND_MAX = 10;

const MONO = "var(--ob-font-family-data)";
const UI = "var(--ob-font-family-ui)";

type Draft = Record<Kind, number[]>;

function toDraft(policy: NotificationPolicy): Draft {
  const out = {} as Draft;
  for (const k of KINDS) out[k] = [...(policy.horizons?.[k] ?? [])].sort((a, b) => a - b);
  return out;
}

function problem(error: Error): string {
  return error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
}

function Message({ children }: { children: React.ReactNode }) {
  return (
    <p role="alert" style={{ color: "var(--ob-risk-fg)", font: `11.5px/1.4 ${UI}` }}>
      {children}
    </p>
  );
}

export function HorizonsCard() {
  const policy = useNotificationPolicy();
  return (
    <Card>
      <CardHeader title={t("notifications.policy.title")} />
      {policy.isLoading ? (
        <SkeletonRows rows={6} height={44} />
      ) : policy.isError || !policy.data ? (
        <ErrorState message={t("common.error")} onRetry={() => void policy.refetch()} />
      ) : (
        <PolicyForm data={policy.data} />
      )}
    </Card>
  );
}

function PolicyForm({ data }: { data: NotificationPolicy }) {
  const toast = useToast();
  const update = useUpdateNotificationPolicy();
  const [draft, setDraft] = useState<Draft>(() => toDraft(data));
  const [enabled, setEnabled] = useState(data.autoRemind?.enabled ?? false);
  const [interval, setIntervalText] = useState(String(data.autoRemind?.intervalDays ?? 3));
  const [max, setMax] = useState(String(data.autoRemind?.max ?? 3));
  const [error, setError] = useState<string | null>(null);
  // Unsaved edits: a background refetch (window focus) must not overwrite them.
  const dirty = useRef(false);
  const edited = <T,>(set: (value: T) => void) => (value: T) => {
    dirty.current = true;
    set(value);
  };

  // Re-seed from the server only while the form is pristine -- after a successful save (which clears
  // the dirty flag and invalidates the query) that shows what the server kept, sorted.
  useEffect(() => {
    if (dirty.current) return;
    setDraft(toDraft(data));
    setEnabled(data.autoRemind?.enabled ?? false);
    setIntervalText(String(data.autoRemind?.intervalDays ?? 3));
    setMax(String(data.autoRemind?.max ?? 3));
  }, [data]);

  const intervalNumber = interval.trim() === "" ? NaN : Number(interval);
  const maxNumber = max.trim() === "" ? NaN : Number(max);
  const intervalValid = Number.isInteger(intervalNumber) && intervalNumber >= 1 && intervalNumber <= INTERVAL_MAX;
  const maxValid = Number.isInteger(maxNumber) && maxNumber >= 1 && maxNumber <= REMIND_MAX;
  const valid = intervalValid && maxValid;

  return (
    <form
      className="flex flex-col"
      style={{ gap: "var(--ob-space-16)" }}
      onSubmit={(e) => {
        e.preventDefault();
        if (!valid) return;
        setError(null);
        // Full replace: all six kinds travel, an empty list switching that kind off.
        update.mutate(
          { autoRemind: { enabled, intervalDays: intervalNumber, max: maxNumber }, horizons: { ...draft } },
          {
            onSuccess: () => {
              dirty.current = false;   // the refetch the save triggers re-seeds the form
              toast.show(t("notifications.policy.saved"));
            },
            onError: (err) => setError(problem(err)),
          },
        );
      }}
    >
      <div className="flex flex-col">
        {KINDS.map((kind) => (
          <KindRow
            key={kind}
            kind={kind}
            leads={draft[kind]}
            onChange={edited((next: number[]) => setDraft((d) => ({ ...d, [kind]: next })))}
          />
        ))}
      </div>

      <div
        className="flex flex-col border-t border-line-faint"
        style={{ gap: "var(--ob-space-11)", paddingTop: "var(--ob-space-16)" }}
      >
        <div style={{ maxWidth: "26rem" }}>
          <Switch checked={enabled} onChange={edited(setEnabled)} label={t("notifications.policy.autoRemind")} />
        </div>
        <div className="flex flex-wrap items-start" style={{ gap: "var(--ob-space-16)" }}>
          <NumberInput
            label={t("notifications.policy.interval")}
            value={interval}
            onChange={edited(setIntervalText)}
            max={INTERVAL_MAX}
            invalid={!intervalValid}
          />
          <NumberInput
            label={t("notifications.policy.max")}
            value={max}
            onChange={edited(setMax)}
            max={REMIND_MAX}
            invalid={!maxValid}
          />
        </div>
        {!valid && <Message>{t("notifications.policy.autoRemind.invalid")}</Message>}
      </div>

      {error && <Message>{error}</Message>}
      <div className="flex justify-end">
        <Button type="submit" disabled={!valid || update.isPending}>
          {t("common.save")}
        </Button>
      </div>
    </form>
  );
}

function NumberInput({
  label,
  value,
  onChange,
  max,
  invalid,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  max: number;
  invalid: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col">
      <label htmlFor={id} style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: 5, fontWeight: 500 }}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={1}
        max={max}
        step={1}
        value={value}
        aria-invalid={invalid ? true : undefined}
        onChange={(e) => onChange(e.target.value)}
        style={inputStyle(invalid, 96)}
      />
    </div>
  );
}

function inputStyle(invalid: boolean, width: number) {
  return {
    width,
    border: `1px solid ${invalid ? "var(--ob-risk-border)" : "var(--ob-line)"}`,
    borderRadius: "var(--ob-radius-9)",
    padding: "9px 11px",
    fontSize: "13px",
    background: "var(--ob-surface)",
    color: "var(--ob-ink)",
    fontFamily: MONO,
  } as const;
}

function KindRow({ kind, leads, onChange }: { kind: Kind; leads: number[]; onChange: (next: number[]) => void }) {
  const labelId = useId();
  const inputId = useId();
  const [text, setText] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  function add() {
    const n = text.trim() === "" ? NaN : Number(text);
    if (!Number.isInteger(n) || n < LEAD_MIN || n > LEAD_MAX || leads.length >= LEADS_PER_KIND) {
      setMessage(t("notifications.policy.range"));
      return;
    }
    if (leads.includes(n)) {
      setMessage(t("notifications.policy.duplicate"));
      return;
    }
    setMessage(null);
    setText("");
    onChange([...leads, n].sort((a, b) => a - b));
  }

  return (
    <div
      role="group"
      aria-labelledby={labelId}
      className="flex flex-wrap items-start border-t border-line-faint first:border-t-0"
      style={{ padding: "var(--ob-space-11) 0", gap: "var(--ob-space-11) var(--ob-space-16)" }}
    >
      <div style={{ flex: "1 1 13rem", minWidth: 0 }}>
        <div
          id={labelId}
          className="text-ink"
          style={{ font: `500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) ${UI}` }}
        >
          {t(`notifications.policy.kind.${kind}`)}
        </div>
        <div className="text-text-subtle" style={{ font: `11.5px/1.4 ${UI}` }}>
          {t(`notifications.policy.unit.${UNIT[kind]}`)}
        </div>
      </div>

      <div className="flex flex-col" style={{ flex: "2 1 18rem", gap: "var(--ob-space-8)" }}>
        <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-6)", minHeight: 24 }}>
          {leads.length === 0 ? (
            <span className="text-text-subtle" style={{ font: `12px/1.4 ${UI}` }}>
              {t("notifications.policy.none")}
            </span>
          ) : (
            leads.map((lead) => (
              <Chip
                key={lead}
                aria-label={t("notifications.policy.remove", { n: String(lead) })}
                mono={<XIcon size={10} />}
                onClick={() => {
                  setMessage(null);
                  onChange(leads.filter((l) => l !== lead));
                }}
                style={{ cursor: "pointer" }}
              >
                <span style={{ font: `500 12px/1 ${MONO}` }}>{lead}</span>
              </Chip>
            ))
          )}
        </div>
        <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
          <input
            id={inputId}
            type="number"
            inputMode="numeric"
            min={LEAD_MIN}
            max={LEAD_MAX}
            step={1}
            aria-label={t("notifications.policy.leadInput")}
            aria-invalid={message ? true : undefined}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setMessage(null);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                add();
              }
            }}
            style={inputStyle(Boolean(message), 80)}
          />
          <Button type="button" variant="secondary" aria-label={t("notifications.policy.add")} onClick={add}>
            {t("notifications.policy.addShort")}
          </Button>
        </div>
        {message && <Message>{message}</Message>}
      </div>
    </div>
  );
}
