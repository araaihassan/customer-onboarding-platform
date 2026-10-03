"use client";

import { t } from "@/lib/i18n";

/**
 * Same native select and real <label htmlFor> as the users page's `DepartmentSelect`. Extracted
 * from the users page when the SLA war room's ReassignDialog became its third consumer.
 *
 * `noneLabel` is optional: omit it and there is no blank option, so a caller that must never
 * clear a value (reassigning an owner) cannot do so by accident. `currentLabel` names the
 * fallback option for a current value outside the fetched page; it defaults to the users page's
 * manager wording, so that screen is unchanged.
 */
export function UserSelect({
  id,
  label,
  noneLabel,
  currentLabel,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  noneLabel?: string;
  currentLabel?: string;
  value: string;
  options: { id?: string; fullName?: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
      <label
        htmlFor={id}
        className="text-text-muted"
        style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="bg-surface border border-line text-ink"
        style={{
          height: "var(--ob-control-height)",
          borderRadius: "var(--ob-radius-9)",
          padding: "0 var(--ob-space-11)",
          font: "13px/1.3 var(--ob-font-family-ui)",
        }}
      >
        {noneLabel !== undefined && <option value="">{noneLabel}</option>}
        {/* A current value outside the fetched page must stay selectable, or saving would silently clear it. */}
        {value && !options.some((option) => option.id === value) && (
          <option value={value}>{currentLabel ?? t("admin.users.field.manager.current")}</option>
        )}
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.fullName}
          </option>
        ))}
      </select>
    </div>
  );
}
