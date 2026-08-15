"use client";

import { useEffect, useId, useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { StatusPill } from "@/components/ui/StatusPill";
import { groupByCategory, useSetRoleEnabled, useUpdateGrants } from "@/lib/api/admin";
import type { Grants, Permission, Role, Scope } from "@/lib/api/admin";
import { t } from "@/lib/i18n";

/**
 * The role's grants, edited one permission at a time.
 *
 * The scope options for every permission come from the catalog
 * (`GET /admin/permissions`) and are never hardcoded here. That is the whole
 * point of the endpoint: `role.manage` and `customer.create` are ALL-only, so
 * offering them a choice of four would present three options the API rejects on
 * save. An ALL-only permission therefore shows its single scope as a value, not
 * as a control — a dropdown with one option, or a disabled one, is a control that
 * lies about being a choice.
 *
 * The layout follows component-specs §12's stage inspector, which is the closest
 * analogue the design has: a sticky panel at top 76px carrying the record's
 * identity and its actions, beside a scrollable list of the things being
 * configured.
 */
export function RoleEditor({
  role,
  permissions,
  canManage,
}: {
  role: Role;
  permissions: Permission[];
  canManage: boolean;
}) {
  const roleId = role.id ?? "";
  const saved = useMemo<Grants>(() => (role.grants ?? {}) as Grants, [role.grants]);

  const [draft, setDraft] = useState<Grants>(saved);
  const [announcement, setAnnouncement] = useState("");

  const updateGrants = useUpdateGrants();
  const setEnabled = useSetRoleEnabled();

  // Selecting a different role, or a save landing, replaces the draft. Keyed on
  // the id as well as the grants because two roles can grant exactly the same
  // set, and `saved` alone would then not change identity between them.
  useEffect(() => {
    setDraft(saved);
  }, [roleId, saved]);

  const dirty = !sameGrants(draft, saved);
  const grantCount = Object.keys(draft).length;
  const groups = groupByCategory(permissions);

  function toggle(permission: Permission, on: boolean) {
    const key = permission.key;
    if (!key) return;

    setDraft((previous) => {
      const next = { ...previous };
      if (!on) delete next[key];
      // Granting picks the permission's first allowed scope, which is the
      // narrowest the API sorts them into — never a default of ALL, which would
      // quietly hand out the widest authority available on a single click.
      else next[key] = narrowest(permission);
      return next;
    });
  }

  function rescope(key: string, scope: Scope) {
    setDraft((previous) => ({ ...previous, [key]: scope }));
  }

  function save() {
    // The panel shows one alert for two mutations, so each clears the other's
    // stale message. An error that outlives its cause trains people to ignore
    // errors.
    setEnabled.reset();
    updateGrants.mutate(
      { roleId, grants: draft },
      { onSuccess: () => setAnnouncement(t("role.saved")) },
    );
  }

  return (
    <>
      {/* A persistent live region, present before there is anything to say — an
          element inserted at the moment of the announcement is unreliable. */}
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      <div
        className="grid items-start xl:grid-cols-[minmax(0,1fr)_288px]"
        style={{ gap: "var(--ob-space-20)" }}
      >
        <section aria-labelledby={`${roleId}-permissions`}>
          <h3
            id={`${roleId}-permissions`}
            className="text-text-primary"
            style={{
              font: "600 var(--ob-type-13-5-size)/var(--ob-type-13-5-line) var(--ob-font-family-ui)",
              marginBottom: "var(--ob-space-12)",
            }}
          >
            {t("role.permissions")}
          </h3>

          <div className="flex flex-col" style={{ gap: "var(--ob-space-18)" }}>
            {groups.map(([category, group]) => (
              <section key={category} aria-labelledby={`${roleId}-cat-${category}`}>
                <h4
                  id={`${roleId}-cat-${category}`}
                  className="text-text-faint"
                  style={{
                    font: "500 var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
                    letterSpacing: "0.08em",
                    textTransform: "uppercase",
                    marginBottom: "var(--ob-space-8)",
                  }}
                >
                  {category}
                </h4>

                <ul className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
                  {group.map((permission) => (
                    <PermissionRow
                      key={permission.key}
                      permission={permission}
                      scope={permission.key ? draft[permission.key] : undefined}
                      canManage={canManage}
                      onToggle={(on) => toggle(permission, on)}
                      onRescope={(next) => permission.key && rescope(permission.key, next)}
                    />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        </section>

        <aside
          className="xl:sticky bg-bg-surface border border-border-default"
          // 76px is the design's own figure for the inspector: the header is 64px
          // and the gap above the panel is 12px.
          style={{
            top: 76,
            borderRadius: "var(--ob-radius-card)",
            padding: "var(--ob-space-16)",
          }}
        >
          <p
            className="text-text-faint"
            style={{
              font: "500 var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
              letterSpacing: "0.08em",
              textTransform: "uppercase",
            }}
          >
            {t("role.configuration")}
          </p>

          <div
            className="flex items-center flex-wrap"
            style={{ gap: "var(--ob-space-8)", margin: "var(--ob-space-6) 0 var(--ob-space-16)" }}
          >
            <h3
              className="text-text-primary"
              style={{ font: "600 var(--ob-type-15-size)/var(--ob-type-15-line) var(--ob-font-family-ui)" }}
            >
              {role.name}
            </h3>
            {/* The word carries the state; the colour only reinforces it. */}
            <StatusPill
              status={role.enabled ? t("role.enabled") : t("role.disabled")}
              role={role.enabled ? "on-track" : "blocked"}
            />
          </div>

          <div className="flex flex-col" style={{ gap: "var(--ob-space-10)" }}>
            {/* readOnly, not disabled and not a styled div: the API has no rename
                and no re-description, so a field that looked editable would lie.
                readOnly keeps it readable, selectable and in the tab order. */}
            <InspectorField label={t("role.field.name")} value={role.name ?? ""} />
            <InspectorField
              label={t("role.field.description")}
              value={role.description ?? ""}
            />
            <InspectorField label={t("role.field.id")} value={roleId} mono />
            <InspectorField
              label={t("role.field.grants")}
              value={String(grantCount)}
              mono
            />
          </div>

          {role.systemTemplate && (
            <p
              className="text-text-muted"
              style={{
                font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
                marginTop: "var(--ob-space-12)",
              }}
            >
              {t("role.systemTemplate")}
            </p>
          )}

          {canManage && (
            <>
              <div
                className="flex items-center justify-between"
                style={{ gap: "var(--ob-space-10)", marginTop: "var(--ob-space-16)" }}
              >
                <span
                  id={`${roleId}-enabled-label`}
                  className="text-text-secondary"
                  style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
                >
                  {t("role.enabledToggle")}
                </span>
                <Switch
                  checked={role.enabled ?? false}
                  labelledBy={`${roleId}-enabled-label`}
                  disabled={setEnabled.isPending}
                  onChange={(next) => {
                    updateGrants.reset();
                    setEnabled.mutate({ roleId, enabled: next });
                  }}
                />
              </div>

              {/* Both mutations on this panel report failure, not just the one
                  with a Save button. A rejected disable otherwise leaves the
                  switch snapping back to its old position with no explanation —
                  the user sees an interface that ignored them, which is worse
                  than an error. */}
              {(updateGrants.isError || setEnabled.isError) && (
                <p
                  role="alert"
                  style={{
                    color: "var(--ob-status-blocked-fg)",
                    marginTop: "var(--ob-space-11)",
                    font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
                  }}
                >
                  {updateGrants.isError ? t("role.saveFailed") : t("role.enableFailed")}
                </p>
              )}

              <div
                className="flex justify-end"
                style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-16)" }}
              >
                <Button
                  type="button"
                  variant="secondary"
                  disabled={!dirty || updateGrants.isPending}
                  onClick={() => setDraft(saved)}
                >
                  {t("common.cancel")}
                </Button>
                <Button type="button" disabled={!dirty || updateGrants.isPending} onClick={save}>
                  {t("role.save")}
                </Button>
              </div>
            </>
          )}
        </aside>
      </div>
    </>
  );
}

/**
 * One permission: its description, its key, whether the role holds it, and — only
 * when it does — at what scope.
 *
 * The scope control appears on grant rather than sitting there disabled. A
 * disabled select is removed from the accessibility tree in some browsers and
 * reads as "broken" in the rest; absent is honest, because a scope on a
 * permission the role does not hold means nothing.
 */
function PermissionRow({
  permission,
  scope,
  canManage,
  onToggle,
  onRescope,
}: {
  permission: Permission;
  scope: Scope | undefined;
  canManage: boolean;
  onToggle: (on: boolean) => void;
  onRescope: (scope: Scope) => void;
}) {
  const id = useId();
  const allowed = (permission.allowedScopes ?? []) as Scope[];
  const granted = scope !== undefined;

  return (
    <li
      className="flex items-start bg-bg-surface border border-border-default"
      style={{
        gap: "var(--ob-space-11)",
        padding: "var(--ob-space-10) var(--ob-space-13)",
        borderRadius: "var(--ob-radius-row)",
      }}
    >
      <div className="flex-1 min-w-0">
        {/* Human text, so Archivo. */}
        <p
          id={`${id}-label`}
          className="text-text-primary"
          style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
        >
          {permission.description}
        </p>
        {/* A permission key is a machine-generated identifier, so it is mono. */}
        <p
          className="text-text-faint truncate"
          style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
        >
          {permission.key}
        </p>
      </div>

      {granted && (
        <div className="flex flex-col items-end" style={{ gap: "var(--ob-space-2)" }}>
          {allowed.length > 1 ? (
            <>
              <label
                htmlFor={`${id}-scope`}
                className="text-text-faint"
                style={{ font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-ui)" }}
              >
                {t("role.scope")}
              </label>
              <select
                id={`${id}-scope`}
                value={scope}
                disabled={!canManage}
                onChange={(event) => onRescope(event.target.value as Scope)}
                className="bg-bg-surface-sunken border border-border-default text-text-primary"
                style={{
                  height: "var(--ob-control-height-sm)",
                  borderRadius: "var(--ob-radius-chip)",
                  padding: "0 var(--ob-space-8)",
                  font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)",
                }}
              >
                {allowed.map((option) => (
                  <option key={option} value={option}>
                    {t(`role.scope.${option}`)}
                  </option>
                ))}
              </select>
            </>
          ) : (
            // One allowed scope is a fact about the permission, not a choice.
            // Rendered as a value, never as a control with a single option.
            <>
              <span
                className="text-text-faint"
                style={{ font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-ui)" }}
              >
                {t("role.scope")}
              </span>
              <span
                className="text-text-secondary"
                style={{
                  height: "var(--ob-control-height-sm)",
                  display: "inline-flex",
                  alignItems: "center",
                  font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)",
                }}
              >
                {t(`role.scope.${scope}`)}
              </span>
            </>
          )}
        </div>
      )}

      <div style={{ paddingTop: "var(--ob-space-4)" }}>
        <Switch
          checked={granted}
          labelledBy={`${id}-label`}
          disabled={!canManage}
          onChange={onToggle}
        />
      </div>
    </li>
  );
}

/**
 * The 34×20px track from component-specs §12.
 *
 * `role="switch"` with `aria-checked`, named by the text it sits beside rather
 * than by a duplicate aria-label — a label that repeats visible text is one more
 * string to drift out of sync. The knob's position carries the state as well as
 * the fill does, so this is not colour as the only signal.
 */
function Switch({
  checked,
  labelledBy,
  disabled = false,
  onChange,
}: {
  checked: boolean;
  labelledBy: string;
  disabled?: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-labelledby={labelledBy}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      style={{
        width: 34,
        height: 20,
        flex: "0 0 34px",
        borderRadius: "var(--ob-radius-pill)",
        background: checked ? "var(--ob-accent)" : "var(--ob-bg-inset-strong)",
        border: `1px solid ${checked ? "var(--ob-accent)" : "var(--ob-border-default)"}`,
        position: "relative",
        opacity: disabled ? 0.55 : 1,
        cursor: disabled ? "not-allowed" : "pointer",
        transition: "background 120ms ease",
      }}
    >
      <span
        aria-hidden="true"
        style={{
          position: "absolute",
          top: 2,
          left: checked ? 16 : 2,
          width: 14,
          height: 14,
          borderRadius: "var(--ob-radius-pill)",
          background: "var(--ob-bg-surface)",
          transition: "left 120ms ease",
        }}
      />
    </button>
  );
}

/** A read-only inspector field: 32px, radius-chip, on bg-surface-sunken (§12). */
function InspectorField({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  const id = useId();

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
      <label
        htmlFor={id}
        className="text-text-secondary"
        style={{ font: "500 var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
      >
        {label}
      </label>
      <input
        id={id}
        readOnly
        value={value}
        className="bg-bg-surface-sunken border border-border-default text-text-primary"
        style={{
          height: 32,
          borderRadius: "var(--ob-radius-chip)",
          padding: "0 var(--ob-space-10)",
          font: mono
            ? "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)"
            : "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
        }}
      />
    </div>
  );
}

/**
 * The narrowest scope the permission allows.
 *
 * The catalog sorts allowedScopes alphabetically — ALL, ASSIGNED, DEPARTMENT,
 * TEAM — so "first" is not "narrowest" and cannot be relied on. Ordering here
 * explicitly is what stops a single click on a record-scoped permission handing
 * out tenant-wide authority.
 */
const NARROWEST_FIRST: Scope[] = ["ASSIGNED", "TEAM", "DEPARTMENT", "ALL"];

function narrowest(permission: Permission): Scope {
  const allowed = (permission.allowedScopes ?? []) as Scope[];
  return NARROWEST_FIRST.find((scope) => allowed.includes(scope)) ?? "ALL";
}

function sameGrants(a: Grants, b: Grants): boolean {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  return keys.every((key) => a[key] === b[key]);
}
