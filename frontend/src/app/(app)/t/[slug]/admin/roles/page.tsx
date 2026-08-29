"use client";

import { useEffect, useMemo, useState } from "react";
import { RoleEditor } from "@/components/admin/RoleEditor";
import { PlusIcon, ShieldCheckIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { useCreateRole, usePermissions, useRoles } from "@/lib/api/admin";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * Roles and their grants.
 *
 * The screen title belongs to the shell header, which owns the <h1>; everything
 * here starts at <h2>.
 */
export default function RolesPage() {
  const canView = useHasPermission("role.view");
  const canManage = useHasPermission("role.manage");

  const roles = useRoles(canView);
  const permissions = usePermissions(canView);
  const create = useCreateRole();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useSetPageHeader(t("admin.roles.title"));

  // useMemo, not a bare `?? []`: a fresh array literal on every render would make
  // the effect below re-run every render and re-select on each one.
  const list = useMemo(() => roles.data ?? [], [roles.data]);
  // Selecting the first role rather than showing an empty right-hand side: the
  // editor with nothing in it is a dead panel, and "pick one" is a step nobody
  // wants on a screen with one obvious starting point.
  useEffect(() => {
    if (list.length === 0) return;
    if (selectedId && list.some((role) => role.id === selectedId)) return;
    setSelectedId(list[0]?.id ?? null);
  }, [list, selectedId]);

  const selected = list.find((role) => role.id === selectedId);
  const isLoading = roles.isLoading || permissions.isLoading;
  const isError = roles.isError || permissions.isError;

  return (
    <section>
      <h2 className="sr-only">{t("admin.roles.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <div className="flex-1" />
        {!isLoading && (
          <span
            className="text-text-subtle whitespace-nowrap"
            style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
          >
            {t("admin.roles.count", { count: String(list.length) })}
          </span>
        )}
        {canManage && (
          <Button
            type="button"
            onClick={() => {
              create.reset();
              setCreating(true);
            }}
            style={{ gap: "var(--ob-space-6)" }}
          >
            <PlusIcon size={14} />
            {t("role.create.title")}
          </Button>
        )}
      </div>

      {isLoading ? (
        <SkeletonRows rows={6} height={56} />
      ) : isError ? (
        <EmptyState
          icon={<ShieldCheckIcon size={28} />}
          title={t("common.error")}
          action={
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                void roles.refetch();
                void permissions.refetch();
              }}
            >
              {t("common.retry")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<ShieldCheckIcon size={28} />}
          title={t("admin.roles.empty")}
          description={t("admin.roles.emptyHint")}
        />
      ) : (
        <div
          className="grid items-start lg:grid-cols-[248px_minmax(0,1fr)]"
          style={{ gap: "var(--ob-space-20)" }}
        >
          <nav aria-label={t("admin.roles.select")}>
            <ul className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
              {list.map((role) => {
                const active = role.id === selectedId;
                return (
                  <li key={role.id}>
                    <button
                      type="button"
                      aria-pressed={active}
                      onClick={() => setSelectedId(role.id ?? null)}
                      className="w-full text-left bg-surface"
                      // Selected is the design's stage-row treatment (§12): a 1px
                      // accent border plus the accent ring. The border is what
                      // carries it — the ring alone is a tint, and a tint is not a
                      // signal anybody can name.
                      style={{
                        padding: "var(--ob-space-10) var(--ob-space-13)",
                        borderRadius: "var(--ob-radius-11)",
                        border: `1px solid var(${active ? "--ob-accent-fg" : "--ob-line"})`,
                        boxShadow: active ? "var(--ob-shadow-ring-selected)" : undefined,
                        cursor: "pointer",
                      }}
                    >
                      <span
                        className="block truncate text-ink"
                        style={{ font: `${active ? 500 : 400} var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)` }}
                      >
                        {role.name}
                      </span>
                      <span
                        className="block text-text-faint"
                        style={{ font: "var(--ob-type-breadcrumb-size)/var(--ob-type-breadcrumb-line) var(--ob-font-family-data)" }}
                      >
                        {t("admin.roles.grantCount", {
                          count: String(Object.keys(role.grants ?? {}).length),
                        })}
                        {role.enabled === false ? ` · ${t("role.disabled")}` : ""}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </nav>

          {selected && (
            <RoleEditor
              // Remounting on selection is deliberate: the editor holds a draft of
              // the grants, and carrying one role's unsaved edits into another
              // role's form is how the wrong role gets saved.
              key={selected.id}
              role={selected}
              permissions={permissions.data ?? []}
              canManage={canManage}
            />
          )}
        </div>
      )}

      {creating && (
        <Dialog title={t("role.create.title")} onClose={() => setCreating(false)}>
          <NewRoleForm
            pending={create.isPending}
            error={create.isError ? t("common.error") : undefined}
            onCancel={() => setCreating(false)}
            onSubmit={(values) =>
              create.mutate(
                // Created with no grants at all. Absence of a grant is the
                // denial, so a new role starts able to do nothing and is then
                // given exactly what it needs.
                { name: values.name, description: values.description, grants: {} },
                {
                  onSuccess: (created) => {
                    setCreating(false);
                    setSelectedId(created.id);
                  },
                },
              )
            }
          />
        </Dialog>
      )}
    </section>
  );
}

function NewRoleForm({
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  pending: boolean;
  error?: string;
  onSubmit: (values: { name: string; description: string }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [nameError, setNameError] = useState<string>();

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = name.trim();
        if (!trimmed) {
          setNameError(t("customer.form.required"));
          return;
        }
        onSubmit({ name: trimmed, description: description.trim() });
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("role.field.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />
        <Field
          label={t("role.field.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={pending}>
          {t("role.create.submit")}
        </Button>
      </DialogActions>
    </form>
  );
}
