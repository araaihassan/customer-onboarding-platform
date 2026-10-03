"use client";

import { useId, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { CalendarIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Field } from "@/components/ui/Field";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import {
  useAddHoliday,
  useBusinessCalendar,
  useRemoveHoliday,
  useSlaPolicy,
  useUpdateBusinessCalendar,
  useUpdateSlaPolicy,
} from "@/lib/api/calendar";
import type { BusinessCalendar, Holiday, SlaPolicy } from "@/lib/api/calendar";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { formatDate } from "@/lib/format/date";
import { t } from "@/lib/i18n";

/**
 * Administration -> Business calendar.
 *
 * The design bundle has no screen for this, so it is built from the existing
 * primitives only: three flat cards, mono for every date and number, status
 * colour only on validation messages (always paired with the words).
 *
 * Every bound mirrors the backend (CalendarAdminService and the two request
 * records): working days are a non-empty subset of ISO 1..7; a holiday's year is
 * 2000-2100 and its name at most 120 characters; atRiskDays is 0..999.9 on a 0.1
 * grid; escalateAfterOverdueDays is an integer 1..30 (the ceiling is the product
 * ruling that mandatory escalation cannot be switched off by setting it huge).
 * The client check only saves a round trip -- the server remains the authority and
 * its problem detail is shown whenever it still refuses.
 *
 * Both PUTs are full replaces, so each sends every field it accepts. Dates stay
 * `YYYY-MM-DD` strings end to end, never Date objects.
 */
const DAYS = [1, 2, 3, 4, 5, 6, 7] as const;
const NAME_MAX = 120;
const HOLIDAY_MIN = "2000-01-01";
const HOLIDAY_MAX = "2100-12-31";
const ESCALATE_MAX = 30;

const MONO = "var(--ob-font-family-data)";
const LABEL_FONT = "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)";

export default function BusinessCalendarPage() {
  const calendar = useBusinessCalendar();
  const policy = useSlaPolicy();

  useSetPageHeader(t("calendar.title"));

  return (
    <section>
      <h2 className="sr-only">{t("calendar.title")}</h2>
      {/* One column below lg so the three cards stack in reading order; two columns
          above it, with the holidays list (the tall one) beside the two forms. */}
      <div className="grid items-start lg:grid-cols-2" style={{ gap: "var(--ob-space-20)" }}>
        <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
          <Card>
            <CardHeader title={t("calendar.calendar.title")} />
            {calendar.isLoading ? (
              <SkeletonRows rows={3} height={40} />
            ) : calendar.isError || !calendar.data ? (
              <ErrorState message={t("common.error")} onRetry={() => void calendar.refetch()} />
            ) : (
              <CalendarForm data={calendar.data} />
            )}
          </Card>

          <Card>
            <CardHeader title={t("calendar.policy.title")} />
            {policy.isLoading ? (
              <SkeletonRows rows={3} height={40} />
            ) : policy.isError || !policy.data ? (
              <ErrorState message={t("common.error")} onRetry={() => void policy.refetch()} />
            ) : (
              <PolicyForm data={policy.data} />
            )}
          </Card>
        </div>

        <Card>
          <HolidaysCard calendar={calendar} />
        </Card>
      </div>
    </section>
  );
}

function problem(error: Error): string {
  return error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
}

function Message({ children }: { children: ReactNode }) {
  return (
    <p role="alert" style={{ color: "var(--ob-risk-fg)", font: `11.5px/1.4 var(--ob-font-family-ui)` }}>
      {children}
    </p>
  );
}

/** A native select with the same label and border treatment as `Field`. */
function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: string[];
}) {
  const id = useId();
  return (
    <div className="flex flex-col">
      <label
        htmlFor={id}
        style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}
      >
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        style={{
          border: "1px solid var(--ob-line)",
          borderRadius: "var(--ob-radius-9)",
          padding: "9px 11px",
          fontSize: "13px",
          background: "var(--ob-surface)",
          color: "var(--ob-ink)",
          fontFamily: "var(--ob-font-family-ui)",
        }}
      >
        {options.map((zone) => (
          <option key={zone} value={zone}>
            {zone}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Every IANA id the runtime knows, `UTC` included, with the stored value first if it is missing. */
function timezoneOptions(current: string): string[] {
  let zones: string[] = [];
  try {
    zones = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    zones = [];
  }
  const all = zones.includes("UTC") ? zones : ["UTC", ...zones];
  return current && !all.includes(current) ? [current, ...all] : all;
}

function CalendarForm({ data }: { data: BusinessCalendar }) {
  const toast = useToast();
  const update = useUpdateBusinessCalendar();
  const [name, setName] = useState(data.name ?? "");
  const [timezone, setTimezone] = useState(data.timezone ?? "UTC");
  const [days, setDays] = useState<number[]>(data.workingDays ?? []);
  const [error, setError] = useState<string | null>(null);
  const zones = useMemo(() => timezoneOptions(data.timezone ?? ""), [data.timezone]);

  const noDays = days.length === 0;
  const invalid = noDays || name.trim() === "";

  function toggle(day: number) {
    setDays((current) =>
      current.includes(day) ? current.filter((d) => d !== day) : [...current, day].sort((a, b) => a - b),
    );
  }

  return (
    <form
      className="flex flex-col"
      style={{ gap: "var(--ob-space-16)" }}
      onSubmit={(e) => {
        e.preventDefault();
        if (invalid) return;
        setError(null);
        update.mutate(
          { name: name.trim(), timezone, workingDays: days },
          {
            onSuccess: () => toast.show(t("calendar.saved")),
            onError: (err) => setError(problem(err)),
          },
        );
      }}
    >
      <Field label={t("calendar.name")} value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} />
      <SelectField label={t("calendar.timezone")} value={timezone} onChange={setTimezone} options={zones} />

      <fieldset>
        <legend
          style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}
        >
          {t("calendar.workingDays")}
        </legend>
        {/* flex-wrap: seven pills do not fit a phone-width card in one row. */}
        <div className="flex flex-wrap" style={{ gap: "var(--ob-space-6)" }}>
          {DAYS.map((day) => {
            const on = days.includes(day);
            return (
              <Button
                key={day}
                type="button"
                variant={on ? "filter-active" : "filter-idle"}
                aria-pressed={on}
                onClick={() => toggle(day)}
              >
                {t(`calendar.day.${day}`)}
              </Button>
            );
          })}
        </div>
        {noDays && <Message>{t("calendar.workingDays.none")}</Message>}
      </fieldset>

      {error && <Message>{error}</Message>}
      <div className="flex justify-end">
        <Button type="submit" disabled={invalid || update.isPending}>
          {t("calendar.save")}
        </Button>
      </div>
    </form>
  );
}

function HolidaysCard({ calendar }: { calendar: ReturnType<typeof useBusinessCalendar> }) {
  const toast = useToast();
  const add = useAddHoliday();
  const remove = useRemoveHoliday();
  const [date, setDate] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  // ISO date strings sort lexically, so this is newest first without parsing.
  const holidays = useMemo(
    () => [...(calendar.data?.holidays ?? [])].sort((a, b) => (b.date ?? "").localeCompare(a.date ?? "")),
    [calendar.data?.holidays],
  );

  const dateOutOfRange = date !== "" && (date < HOLIDAY_MIN || date > HOLIDAY_MAX);
  const canAdd = date !== "" && !dateOutOfRange && name.trim() !== "";

  return (
    <>
      <CardHeader title={t("calendar.holidays.title")} count={calendar.isLoading ? undefined : holidays.length} />

      {calendar.isLoading ? (
        <SkeletonRows rows={4} height={40} />
      ) : calendar.isError ? (
        <ErrorState message={t("common.error")} onRetry={() => void calendar.refetch()} />
      ) : holidays.length === 0 ? (
        <EmptyState
          icon={<CalendarIcon size={24} />}
          title={t("calendar.holidays.empty")}
          description={t("calendar.holidays.emptyHint")}
        />
      ) : (
        <ul className="flex flex-col">
          {holidays.map((holiday: Holiday) => (
            <li
              key={holiday.id}
              className="flex items-center border-t border-line-faint first:border-t-0"
              style={{ padding: "var(--ob-space-10) 0", gap: "var(--ob-space-11)" }}
            >
              <span
                className="text-ink shrink-0"
                style={{ font: `500 12.5px/1.3 ${MONO}`, minWidth: "7.5em" }}
              >
                {holiday.date ? formatDate(holiday.date) : ""}
              </span>
              <span
                className="flex-1 min-w-0 truncate text-ink"
                style={{ font: `500 ${LABEL_FONT}` }}
              >
                {holiday.name}
              </span>
              <Button
                type="button"
                variant="small-secondary"
                aria-label={t("calendar.holidays.remove.for", { name: holiday.name ?? "" })}
                disabled={remove.isPending}
                onClick={() =>
                  remove.mutate(holiday.id ?? "", {
                    onSuccess: () => toast.show(t("calendar.holidays.removed")),
                    onError: (err) => setError(problem(err)),
                  })
                }
              >
                {t("calendar.holidays.remove")}
              </Button>
            </li>
          ))}
        </ul>
      )}

      {/* The add row sits under the list, wrapping to a stack on narrow widths. */}
      <form
        className="flex flex-wrap items-end border-t border-line-faint"
        style={{ gap: "var(--ob-space-11)", marginTop: "var(--ob-space-16)", paddingTop: "var(--ob-space-16)" }}
        onSubmit={(e) => {
          e.preventDefault();
          if (!canAdd) return;
          setError(null);
          add.mutate(
            { date, name: name.trim() },
            {
              onSuccess: () => {
                setDate("");
                setName("");
                toast.show(t("calendar.holidays.added"));
              },
              onError: (err) =>
                setError(err instanceof ApiError && err.status === 409 ? t("calendar.holidays.duplicate") : problem(err)),
            },
          );
        }}
      >
        <Field
          label={t("calendar.holidays.date")}
          type="date"
          value={date}
          min={HOLIDAY_MIN}
          max={HOLIDAY_MAX}
          onChange={(e) => setDate(e.target.value)}
          error={dateOutOfRange ? t("calendar.holidays.dateRange") : undefined}
          style={{ fontFamily: MONO }}
        />
        <div className="flex-1" style={{ minWidth: "10em" }}>
          <Field
            label={t("calendar.holidays.name")}
            value={name}
            maxLength={NAME_MAX}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <Button type="submit" variant="secondary" disabled={!canAdd || add.isPending}>
          {t("calendar.holidays.add")}
        </Button>
      </form>
      {error && (
        <div style={{ marginTop: "var(--ob-space-8)" }}>
          <Message>{error}</Message>
        </div>
      )}
    </>
  );
}

/** True when `n` is a multiple of 0.1 -- the backend's grid -- allowing for float noise. */
function onTenthsGrid(n: number): boolean {
  return Math.abs(n * 10 - Math.round(n * 10)) < 1e-6;
}

function PolicyForm({ data }: { data: SlaPolicy }) {
  const toast = useToast();
  const update = useUpdateSlaPolicy();
  const [atRisk, setAtRisk] = useState(String(data.atRiskDays ?? 0));
  const [escalate, setEscalate] = useState(String(data.escalateAfterOverdueDays ?? 1));
  const [error, setError] = useState<string | null>(null);

  const atRiskNumber = atRisk.trim() === "" ? NaN : Number(atRisk);
  const escalateNumber = escalate.trim() === "" ? NaN : Number(escalate);
  const atRiskValid = Number.isFinite(atRiskNumber) && atRiskNumber >= 0 && atRiskNumber <= 999.9 && onTenthsGrid(atRiskNumber);
  const escalateValid = Number.isInteger(escalateNumber) && escalateNumber >= 1 && escalateNumber <= ESCALATE_MAX;

  return (
    <form
      className="flex flex-col"
      style={{ gap: "var(--ob-space-16)" }}
      onSubmit={(e) => {
        e.preventDefault();
        if (!atRiskValid || !escalateValid) return;
        setError(null);
        // Full replace: both fields travel even when only one was edited.
        update.mutate(
          { atRiskDays: atRiskNumber, escalateAfterOverdueDays: escalateNumber },
          {
            onSuccess: () => toast.show(t("calendar.policy.saved")),
            onError: (err) => setError(problem(err)),
          },
        );
      }}
    >
      <p className="text-text-subtle" style={{ font: LABEL_FONT, maxWidth: "60ch" }}>
        {t("calendar.policy.note")}
      </p>
      <Field
        label={t("calendar.policy.atRisk")}
        type="number"
        inputMode="decimal"
        min={0}
        max={999.9}
        step={0.1}
        value={atRisk}
        onChange={(e) => setAtRisk(e.target.value)}
        error={atRiskValid ? undefined : t("calendar.policy.atRisk.invalid")}
        style={{ fontFamily: MONO }}
      />
      <div>
        <Field
          label={t("calendar.policy.escalate")}
          type="number"
          inputMode="numeric"
          min={1}
          max={ESCALATE_MAX}
          step={1}
          value={escalate}
          onChange={(e) => setEscalate(e.target.value)}
          error={escalateValid ? undefined : t("calendar.policy.escalate.invalid")}
          style={{ fontFamily: MONO }}
        />
        {escalateValid && (
          <p className="text-text-subtle" style={{ font: "11.5px/1.4 var(--ob-font-family-ui)", marginTop: 4 }}>
            {t("calendar.policy.escalate.hint")}
          </p>
        )}
      </div>
      {error && <Message>{error}</Message>}
      <div className="flex justify-end">
        <Button type="submit" disabled={!atRiskValid || !escalateValid || update.isPending}>
          {t("calendar.policy.save")}
        </Button>
      </div>
    </form>
  );
}
