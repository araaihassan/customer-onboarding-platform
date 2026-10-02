"use client";

import { useState, type ReactNode } from "react";
import { ChevronDownIcon, ChevronUpIcon, XIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { useUsers } from "@/lib/api/admin";
import { useReplaceSignatories, type Agreement, type AgreementSignatory, type SignatoryRequest } from "@/lib/api/agreements";
import { useContacts } from "@/lib/api/customers";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { useDebounced } from "@/lib/useDebounced";

type Kind = "CONTACT" | "INTERNAL";

interface Row {
  key: string;
  kind: Kind;
  personId: string;
  name: string;
  role: string;
}

const SELECT_STYLE = {
  border: "1px solid var(--ob-line)",
  borderRadius: "var(--ob-radius-9)",
  padding: "9px 11px",
  fontSize: "13px",
  background: "var(--ob-surface)",
  color: "var(--ob-ink)",
  fontFamily: "var(--ob-font-family-ui)",
} as const;

function fromView(s: AgreementSignatory, i: number): Row {
  const kind: Kind = s.kind === "INTERNAL" ? "INTERNAL" : "CONTACT";
  return { key: s.id ?? `s-${i}`, kind, personId: (kind === "CONTACT" ? s.contactId : s.userId) ?? "", name: s.displayName ?? "", role: s.displayRole ?? "" };
}

function toRequest(r: Row): SignatoryRequest {
  return r.kind === "CONTACT"
    ? { kind: "CONTACT", contactId: r.personId, displayRole: r.role }
    : { kind: "INTERNAL", userId: r.personId, displayRole: r.role };
}

/**
 * The ordered signatory list. Editable (add, reorder, remove) only while the panel says so; saving PUTs the
 * WHOLE list -- the endpoint is a full replace -- with the agreement's current lockVersion. Contact signatories
 * come from THIS agreement's customer (`agreement.customerId`), active contacts only; the internal option needs
 * `user.view` (GET /admin/users 404s without it) so it is not offered otherwise.
 */
export function SignatoryEditor({
  agreement,
  signatories,
  editable,
  onError,
}: {
  agreement: Agreement;
  signatories: AgreementSignatory[];
  editable: boolean;
  onError: (err: unknown) => void;
}) {
  const replace = useReplaceSignatories();
  const canViewContacts = useHasPermission("contact.view");
  const canViewUsers = useHasPermission("user.view");
  const [rows, setRows] = useState<Row[]>(() => [...signatories].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)).map(fromView));
  const initial = JSON.stringify(
    [...signatories].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)).map((s, i) => toRequest(fromView(s, i))),
  );
  const kinds: Kind[] = [...(canViewContacts ? (["CONTACT"] as Kind[]) : []), ...(canViewUsers ? (["INTERNAL"] as Kind[]) : [])];
  const [kind, setKind] = useState<Kind>(canViewContacts ? "CONTACT" : "INTERNAL");
  const [personId, setPersonId] = useState("");
  const [role, setRole] = useState("");
  // /admin/users pages at 25 and has no status filter, so a name search is the only way to reach
  // an internal user beyond page one.
  const [userSearch, setUserSearch] = useState("");
  const search = useDebounced(userSearch, 250);

  const contacts = useContacts(agreement.customerId ?? "", editable && canViewContacts);
  const users = useUsers(search, 0, editable && canViewUsers && kind === "INTERNAL");

  if (!editable) {
    if (rows.length === 0) return <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>{t("agreements.signatories.empty")}</p>;
    return (
      <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
        {rows.map((r) => (
          <li key={r.key} className="border border-line bg-surface" style={{ borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-8) var(--ob-space-11)" }}>
            <p className="text-ink" style={{ fontWeight: 600, fontSize: "13px" }}>{r.name}</p>
            <p className="text-text-subtle" style={{ fontSize: "12px" }}>{r.role}</p>
          </li>
        ))}
      </ul>
    );
  }

  const taken = new Set(rows.map((r) => takenKey(r.kind, r.personId)));
  const options: { id: string; name: string }[] =
    kind === "CONTACT"
      ? (contacts.data ?? []).filter((c) => c.status === "ACTIVE" && c.id && !taken.has(takenKey("CONTACT", c.id))).map((c) => ({ id: c.id!, name: c.fullName ?? c.email ?? "" }))
      : (users.data?.content ?? []).filter((u) => u.status === "ACTIVE" && u.userType !== "PORTAL" && u.id && !taken.has(takenKey("INTERNAL", u.id))).map((u) => ({ id: u.id!, name: u.fullName ?? u.email ?? "" }));

  const dirty = JSON.stringify(rows.map(toRequest)) !== initial;

  function move(index: number, delta: number) {
    setRows((prev) => {
      const next = [...prev];
      const target = index + delta;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target]!, next[index]!];
      return next;
    });
  }

  function add() {
    const person = options.find((o) => o.id === personId);
    if (!person || !role.trim()) return;
    setRows((prev) => [...prev, { key: `new-${kind}-${person.id}`, kind, personId: person.id, name: person.name, role: role.trim() }]);
    setPersonId("");
    setRole("");
  }

  function save() {
    if (!agreement.id) return;
    replace.mutate({ id: agreement.id, signatories: rows.map(toRequest), lockVersion: agreement.lockVersion ?? 0 }, { onError });
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      {rows.length === 0 ? (
        <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>{t("agreements.signatories.empty")}</p>
      ) : (
        <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
          {rows.map((r, i) => (
            <li
              key={r.key}
              className="flex items-center border border-line bg-surface"
              style={{ gap: "var(--ob-space-8)", borderRadius: "var(--ob-radius-9)", padding: "var(--ob-space-8) var(--ob-space-11)" }}
            >
              <div className="min-w-0 flex-1">
                <p data-testid="signatory-name" className="truncate text-ink" style={{ fontWeight: 600, fontSize: "13px" }}>{r.name}</p>
                <p className="truncate text-text-subtle" style={{ fontSize: "12px" }}>{r.role}</p>
              </div>
              <IconButton label={t("agreements.signatories.moveUp", { name: r.name })} disabled={i === 0} onClick={() => move(i, -1)}>
                <ChevronUpIcon size={14} />
              </IconButton>
              <IconButton label={t("agreements.signatories.moveDown", { name: r.name })} disabled={i === rows.length - 1} onClick={() => move(i, 1)}>
                <ChevronDownIcon size={14} />
              </IconButton>
              <IconButton label={t("agreements.signatories.remove", { name: r.name })} onClick={() => setRows((prev) => prev.filter((x) => x.key !== r.key))}>
                <XIcon size={14} />
              </IconButton>
            </li>
          ))}
        </ul>
      )}

      <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: "var(--ob-space-8)", alignItems: "end" }}>
        {kinds.length > 1 && (
          <SelectField label={t("agreements.signatories.kind")} value={kind} onChange={(v) => { setKind(v as Kind); setPersonId(""); setUserSearch(""); }}>
            {kinds.map((k) => (
              <option key={k} value={k}>{t(`agreements.signatories.kind.${k}`)}</option>
            ))}
          </SelectField>
        )}
        {kind === "INTERNAL" && (
          <Field label={t("agreements.signatories.search")} type="search" value={userSearch} onChange={(e) => setUserSearch(e.target.value)} />
        )}
        <SelectField label={t("agreements.signatories.person")} value={personId} onChange={setPersonId}>
          <option value="">{t("agreements.signatories.pick")}</option>
          {options.map((o) => (
            <option key={o.id} value={o.id}>{o.name}</option>
          ))}
        </SelectField>
        <Field label={t("agreements.signatories.role")} value={role} maxLength={120} onChange={(e) => setRole(e.target.value)} />
        <Button type="button" variant="secondary" disabled={!personId || !role.trim()} onClick={add}>
          {t("agreements.signatories.add")}
        </Button>
      </div>

      <div>
        <Button type="button" disabled={!dirty || replace.isPending} onClick={save}>
          {t("agreements.signatories.save")}
        </Button>
      </div>
    </div>
  );
}

function IconButton({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex items-center justify-center text-text-subtle"
      style={{ width: 28, height: 28, borderRadius: "var(--ob-radius-7)", border: "1px solid var(--ob-line)", background: "var(--ob-surface)", opacity: disabled ? 0.4 : 1, cursor: disabled ? "not-allowed" : "pointer" }}
    >
      {children}
    </button>
  );
}

function SelectField({ label, value, onChange, children }: { label: string; value: string; onChange: (v: string) => void; children: ReactNode }) {
  const id = `sel-${label.replace(/\s+/g, "-")}`;
  return (
    <div className="flex flex-col">
      <label htmlFor={id} style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}>{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} style={SELECT_STYLE}>
        {children}
      </select>
    </div>
  );
}

const takenKey = (kind: Kind, id: string) => [kind, id].join(":");
