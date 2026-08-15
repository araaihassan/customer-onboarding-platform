"use client";

import { useState } from "react";
import { PlusIcon, SearchIcon, UsersIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import {
  useAssignRole,
  useDeactivateUser,
  useInviteUser,
  useRoles,
  useUnassignRole,
  useUsers,
} from "@/lib/api/admin";
import type { Role, User } from "@/lib/api/admin";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { useDebounced } from "@/lib/useDebounced";
import { t } from "@/lib/i18n";

/**
 * The people who work here: who they are, what they hold, and whether they are
 * still active.
 *
 * Inviting a colleague and creating one are the same operation — the account is
 * created INVITED with no password hash and the activation email goes out in the
 * same transaction — so there is one button, and it says "Invite user".
 *
 * The screen title belongs to the shell header, which owns the <h1>; everything
 * here starts at <h2>.
 */
export default function UsersPage() {
  const canView = useHasPermission("user.view");
  const canManage = useHasPermission("user.manage");
  const canViewRoles = useHasPermission("role.view");

  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 250);
  const [inviting, setInviting] = useState(false);
  const [managingRolesFor, setManagingRolesFor] = useState<User | null>(null);
  const [deactivating, setDeactivating] = useState<User | null>(null);

  const users = useUsers(search, canView);
  // Only fetched when the screen can actually do something with them: role
  // membership is rendered by name, and that needs the catalog.
  const roles = useRoles(canViewRoles);
  const invite = useInviteUser();
  const deactivate = useDeactivateUser();

  useSetPageHeader(t("admin.users.title"));

  const list = users.data?.content ?? [];
  const total = users.data?.totalElements ?? 0;
  const searching = Boolean(search.trim());

  // Re-read from the freshly invalidated list, so the dialog reflects the
  // assignment that just landed rather than the snapshot it opened with.
  const managing = managingRolesFor
    ? (list.find((user) => user.id === managingRolesFor.id) ?? managingRolesFor)
    : null;

  return (
    <section>
      <h2 className="sr-only">{t("admin.users.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <div className="relative">
          <span
            aria-hidden="true"
            className="absolute text-text-faint"
            style={{ left: "var(--ob-space-10)", top: "50%", transform: "translateY(-50%)" }}
          >
            <SearchIcon size={14} />
          </span>
          {/* A real <label>, visually hidden. A placeholder is not an accessible
              name and disappears the moment someone types. */}
          <label className="sr-only" htmlFor="user-search">
            {t("admin.users.search")}
          </label>
          <input
            id="user-search"
            type="search"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={t("admin.users.search")}
            className="bg-bg-surface border border-border-default text-text-primary"
            style={{
              height: "var(--ob-control-height)",
              width: 260,
              borderRadius: "var(--ob-radius-control)",
              padding: "0 var(--ob-space-11) 0 var(--ob-space-28)",
              font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
            }}
          />
        </div>

        <div className="flex-1" />

        {/* A count is a machine-generated value, so it is mono. */}
        {!users.isLoading && (
          <span
            className="text-text-muted whitespace-nowrap"
            style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)" }}
          >
            {t("admin.users.count", { count: String(total) })}
          </span>
        )}

        {canManage && (
          <Button
            type="button"
            onClick={() => {
              invite.reset();
              setInviting(true);
            }}
            style={{ gap: "var(--ob-space-6)" }}
          >
            <PlusIcon size={14} />
            {t("admin.users.invite")}
          </Button>
        )}
      </div>

      {users.isLoading ? (
        <SkeletonRows rows={6} height={56} />
      ) : users.isError ? (
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void users.refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={searching ? t("admin.users.noMatch") : t("admin.users.empty")}
          description={searching ? t("admin.users.noMatchHint") : t("admin.users.emptyHint")}
        />
      ) : (
        <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          {list.map((user) => (
            <UserRow
              key={user.id}
              user={user}
              roles={roles.data ?? []}
              canManage={canManage}
              canViewRoles={canViewRoles}
              onManageRoles={() => setManagingRolesFor(user)}
              onDeactivate={() => {
                deactivate.reset();
                setDeactivating(user);
              }}
            />
          ))}
        </ul>
      )}

      {inviting && (
        <Dialog title={t("admin.users.invite")} onClose={() => setInviting(false)}>
          <InviteForm
            pending={invite.isPending}
            error={invite.isError ? t("common.error") : undefined}
            onCancel={() => setInviting(false)}
            onSubmit={(values) =>
              invite.mutate(values, { onSuccess: () => setInviting(false) })
            }
          />
        </Dialog>
      )}

      {managing && (
        <Dialog
          title={t("admin.users.roles.for", { name: managing.fullName ?? "" })}
          onClose={() => setManagingRolesFor(null)}
        >
          <RoleAssignment user={managing} roles={roles.data ?? []} />
          <DialogActions>
            <Button type="button" variant="secondary" onClick={() => setManagingRolesFor(null)}>
              {t("common.close")}
            </Button>
          </DialogActions>
        </Dialog>
      )}

      {deactivating && (
        <Dialog title={t("admin.users.deactivate.title")} onClose={() => setDeactivating(null)}>
          <p
            className="text-text-secondary"
            style={{ font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
          >
            {t("admin.users.deactivate.confirm", { name: deactivating.fullName ?? "" })}
          </p>
          {deactivate.isError && (
            <p
              role="alert"
              style={{
                color: "var(--ob-status-blocked-fg)",
                marginTop: "var(--ob-space-11)",
                font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
              }}
            >
              {t("common.error")}
            </p>
          )}
          <DialogActions>
            <Button type="button" variant="secondary" onClick={() => setDeactivating(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={deactivate.isPending}
              onClick={() =>
                deactivate.mutate(deactivating.id ?? "", {
                  onSuccess: () => setDeactivating(null),
                })
              }
            >
              {t("common.confirm")}
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </section>
  );
}

/**
 * One user, as a card row rather than a table.
 *
 * A table would need a roles column of unbounded width — a user can hold any
 * number of roles — and the design's table cells are fixed-share columns. The
 * two-line card list is the same shape the customer table falls back to below
 * 1024px, so it is a pattern the product already has.
 */
function UserRow({
  user,
  roles,
  canManage,
  canViewRoles,
  onManageRoles,
  onDeactivate,
}: {
  user: User;
  roles: Role[];
  canManage: boolean;
  canViewRoles: boolean;
  onManageRoles: () => void;
  onDeactivate: () => void;
}) {
  const name = user.fullName ?? "";
  const held = user.roleIds ?? [];
  const heldNames = held
    .map((id) => roles.find((role) => role.id === id)?.name)
    .filter((roleName): roleName is string => Boolean(roleName));

  return (
    <li
      className="bg-bg-surface border border-border-default rounded-card"
      style={{ padding: "var(--ob-space-13) var(--ob-space-16)" }}
    >
      <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-11)" }}>
        {/* Circular, because this is a person. A company is a rounded square. */}
        <Avatar name={name} kind="person" />

        <div className="flex-1 min-w-0">
          <p
            className="truncate text-text-primary"
            style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
          >
            {name}
          </p>
          {/* An address is a machine-readable identifier, so it is mono. */}
          <p
            className="truncate text-text-faint"
            style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
          >
            {user.email}
          </p>
        </div>

        <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-8)" }}>
          {canViewRoles &&
            (heldNames.length > 0 ? (
              heldNames.map((roleName) => (
                // Neutral: a role is not a state, and a colour that names no
                // state is decoration.
                <StatusPill key={roleName} status={roleName} role="neutral" />
              ))
            ) : (
              <span
                className="text-text-muted"
                style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
              >
                {t("admin.users.noRoles")}
              </span>
            ))}

          <StatusPill status={user.status} />

          {canManage && canViewRoles && (
            <Button
              type="button"
              variant="secondary"
              // Every row's button would otherwise read "Manage roles", which is
              // indistinguishable to anyone navigating by control rather than eye.
              aria-label={t("admin.users.roles.for", { name })}
              onClick={onManageRoles}
              style={{ height: "var(--ob-control-height-sm)" }}
            >
              {t("admin.users.roles")}
            </Button>
          )}

          {/* Deactivation, never deletion — there is no delete on a user. */}
          {canManage && user.status !== "DEACTIVATED" && (
            <Button
              type="button"
              variant="secondary"
              aria-label={t("admin.users.deactivate.for", { name })}
              onClick={onDeactivate}
              style={{ height: "var(--ob-control-height-sm)" }}
            >
              {t("admin.users.deactivate")}
            </Button>
          )}
        </div>
      </div>
    </li>
  );
}

/**
 * Assign and unassign, one role at a time.
 *
 * Not a set of switches applied on save: each is its own gated, audited endpoint
 * call, and a switch that flips before the server agrees would show authority the
 * user does not have. PORTAL users are refused internal roles server-side, which
 * is why the failure message is shown rather than assumed away.
 */
function RoleAssignment({ user, roles }: { user: User; roles: Role[] }) {
  const assign = useAssignRole();
  const unassign = useUnassignRole();
  const held = new Set(user.roleIds ?? []);
  const userId = user.id ?? "";
  const failed = assign.isError || unassign.isError;

  if (roles.length === 0) {
    return (
      <p
        className="text-text-muted"
        style={{ font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
      >
        {t("admin.roles.empty")}
      </p>
    );
  }

  return (
    <>
      <ul className="flex flex-col" style={{ gap: "var(--ob-space-2)", maxHeight: 320, overflowY: "auto" }}>
        {roles.map((role) => {
          const roleId = role.id ?? "";
          const assigned = held.has(roleId);
          const pending =
            (assign.isPending && assign.variables?.roleId === roleId) ||
            (unassign.isPending && unassign.variables?.roleId === roleId);

          return (
            <li
              key={roleId}
              className="flex items-center border-t border-border-subtle first:border-t-0"
              style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-8) 0" }}
            >
              <span
                className="flex-1 min-w-0 truncate text-text-primary"
                style={{ font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
              >
                {role.name}
              </span>
              <Button
                type="button"
                variant="secondary"
                aria-label={
                  assigned
                    ? t("admin.users.roles.remove.for", { role: role.name ?? "" })
                    : t("admin.users.roles.assign.for", { role: role.name ?? "" })
                }
                disabled={pending}
                onClick={() => {
                  assign.reset();
                  unassign.reset();
                  if (assigned) unassign.mutate({ userId, roleId });
                  else assign.mutate({ userId, roleId });
                }}
                style={{ height: "var(--ob-control-height-sm)" }}
              >
                {assigned ? t("admin.users.roles.remove") : t("admin.users.roles.assign")}
              </Button>
            </li>
          );
        })}
      </ul>

      {failed && (
        <p
          role="alert"
          style={{
            color: "var(--ob-status-blocked-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
          }}
        >
          {t("admin.users.roles.failed")}
        </p>
      )}
    </>
  );
}

function InviteForm({
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  pending: boolean;
  error?: string;
  onSubmit: (values: { email: string; fullName: string }) => void;
  onCancel: () => void;
}) {
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [errors, setErrors] = useState<{ email?: string; fullName?: string }>({});

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const values = { email: email.trim(), fullName: fullName.trim() };
        const next: { email?: string; fullName?: string } = {};
        if (!values.email) next.email = t("customer.form.required");
        if (!values.fullName) next.fullName = t("customer.form.required");
        if (Object.keys(next).length > 0) {
          setErrors(next);
          return;
        }
        onSubmit(values);
      }}
    >
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("admin.users.field.email")}
          type="email"
          value={email}
          error={errors.email}
          onChange={(event) => {
            setEmail(event.target.value);
            setErrors((previous) => ({ ...previous, email: undefined }));
          }}
        />
        <Field
          label={t("admin.users.field.fullName")}
          value={fullName}
          error={errors.fullName}
          onChange={(event) => {
            setFullName(event.target.value);
            setErrors((previous) => ({ ...previous, fullName: undefined }));
          }}
        />
      </div>

      <p
        className="text-text-muted"
        style={{
          marginTop: "var(--ob-space-11)",
          font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
        }}
      >
        {t("admin.users.invite.hint")}
      </p>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-status-blocked-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
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
          {t("admin.users.invite.submit")}
        </Button>
      </DialogActions>
    </form>
  );
}
