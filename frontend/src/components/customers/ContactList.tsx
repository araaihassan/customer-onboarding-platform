"use client";

import { useState } from "react";
import { ContactForm } from "@/components/customers/ContactForm";
import type { ContactFormValues } from "@/components/customers/ContactForm";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { Dialog } from "@/components/ui/Dialog";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { PlusIcon, UsersIcon } from "@/components/icons";
import { ApiError } from "@/lib/api/client";
import { useCreateContact, useSendInvitation, useUpdateContact } from "@/lib/api/customers";
import type { Contact } from "@/lib/api/customers";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * What a failed write says.
 *
 * 409 is the duplicate address — a foreseeable user error with one field to fix,
 * so "Something went wrong" would send the reader looking for a server fault
 * instead. 404 renders "Not found" and says nothing about access: the backend
 * answers 404 for a record that does not exist and for one outside the caller's
 * scope, deliberately and identically, and naming access here would hand back
 * exactly the fact the 404 exists to withhold.
 */
function writeError(error: unknown): string | undefined {
  if (!error) return undefined;
  if (error instanceof ApiError && error.status === 409) return t("contact.form.duplicateEmail");
  if (error instanceof ApiError && error.status === 404) return t("common.notFound");
  return t("common.error");
}

/**
 * The people at a customer: adding them, and inviting them to the portal.
 *
 * Circular avatars, because these are people — a customer is a company and
 * carries a rounded square. That is the whole of the distinction, and it is the
 * only thing carrying it.
 *
 * TWO different permissions, side by side and easy to conflate. Adding a contact
 * is `contact.manage` (CustomerContactService.create); inviting one is
 * `invitation.send`. Both gates hide a control nobody could use and neither is a
 * control in itself: the endpoints refuse independently, and Task 22's
 * DirectApiAccessTest proves it.
 */
export function ContactList({
  customerId,
  contacts,
  isLoading = false,
  isError = false,
  onRetry,
}: {
  customerId: string;
  contacts: Contact[];
  isLoading?: boolean;
  /**
   * A failed read, which is NOT an empty list. Rendering "No contacts yet" over
   * a fetch that failed is a claim about the data that nothing supports — and
   * with an Add contact button beside it, it invites the reader to re-add
   * someone who is already there and collide with the duplicate-address 409.
   */
  isError?: boolean;
  onRetry?: () => void;
}) {
  const canInvite = useHasPermission("invitation.send");
  const canManage = useHasPermission("contact.manage");
  const invite = useSendInvitation();
  const create = useCreateContact();
  const update = useUpdateContact();
  const [invited, setInvited] = useState<Set<string>>(new Set());
  const [failed, setFailed] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Contact | null>(null);

  function submitNew(values: ContactFormValues) {
    // Built field by field rather than passed whole: `status` is part of the
    // form (the edit dialog needs it) but not of CreateContactRequest, and the
    // service sets ACTIVE regardless. Sending it anyway would look like the
    // client choosing a starting status when it cannot.
    create.mutate(
      {
        customerId,
        body: {
          fullName: values.fullName,
          email: values.email,
          title: values.title,
          phone: values.phone,
          primaryContact: values.primaryContact,
        },
      },
      {
        onSuccess: () => {
          setAdding(false);
          setAnnouncement(t("contact.create.added"));
        },
        // No onError: the dialog stays open and ContactForm renders the failure
        // in its own role="alert", where the person who pressed the button is
        // already looking.
      },
    );
  }

  function submitEdit(contact: Contact, values: ContactFormValues) {
    // Every field UpdateContactRequest accepts, listed explicitly. A PUT is a
    // full replace, so one omitted here is one blanked on the record.
    update.mutate(
      {
        customerId,
        contactId: contact.id ?? "",
        body: {
          fullName: values.fullName,
          email: values.email,
          title: values.title,
          phone: values.phone,
          primaryContact: values.primaryContact,
          status: values.status,
        },
      },
      {
        onSuccess: () => {
          setEditing(null);
          setAnnouncement(t("contact.edit.saved"));
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader
        title={t("contact.list.title")}
        // No count while loading, and none after a failure: "0" beside a list
        // that never arrived is a number asserted from no data.
        count={isLoading || isError ? undefined : contacts.length}
        action={
          canManage ? (
            <Button
              type="button"
              variant="secondary"
              // Reset on open, not on close: a dialog reopened after a failed
              // create would otherwise greet the user with the last attempt's
              // error before they have done anything, and an error that outlives
              // its cause trains people to ignore errors.
              onClick={() => {
                create.reset();
                setAdding(true);
              }}
              style={{ height: "var(--ob-control-height-sm)", gap: "var(--ob-space-6)" }}
            >
              <PlusIcon size={14} />
              {t("contact.create.title")}
            </Button>
          ) : undefined
        }
      />

      {/* A persistent live region, present before there is anything to say.
          A role="status" element inserted at the moment of the announcement is
          unreliable — several screen readers only watch regions that already
          existed when the change happened. */}
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {isError ? (
        // An error with no way out of it is a dead end, so it carries the retry
        // the customer list and detail screens already offer.
        <EmptyState
          icon={<UsersIcon size={24} />}
          title={t("common.error")}
          description={t("contact.list.errorHint")}
          action={
            onRetry ? (
              <Button type="button" variant="secondary" onClick={onRetry}>
                {t("common.retry")}
              </Button>
            ) : undefined
          }
        />
      ) : isLoading ? (
        // Not the empty state: "No contacts yet" while the request is still in
        // flight is a statement about the data that is not yet true.
        <SkeletonRows rows={3} height={44} />
      ) : contacts.length === 0 ? (
        <EmptyState
          icon={<UsersIcon size={24} />}
          title={t("contact.list.empty")}
          description={t("contact.list.emptyHint")}
        />
      ) : (
        <ul className="flex flex-col">
          {contacts.map((contact) => {
            const name = contact.fullName ?? "";
            const contactId = contact.id ?? "";
            const sent = invited.has(contactId);
            const sending = invite.isPending && invite.variables?.contactId === contactId;

            return (
              <li
                key={contact.id}
                className="flex items-center border-t border-border-subtle first:border-t-0"
                style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-11) 0" }}
              >
                <Avatar name={name} kind="person" />

                <div className="flex-1 min-w-0">
                  <p
                    className="truncate text-text-primary"
                    style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
                  >
                    {name}
                  </p>
                  {contact.title && (
                    <p
                      className="truncate text-text-muted"
                      style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
                    >
                      {contact.title}
                    </p>
                  )}
                  {/* An address is a machine-readable identifier, so it is mono. */}
                  <p
                    className="truncate text-text-faint"
                    style={{ font: "var(--ob-type-10-5-size)/var(--ob-type-10-5-line) var(--ob-font-family-data)" }}
                  >
                    {contact.email}
                  </p>
                </div>

                <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
                  {/* The primary contact is marked with a word. A dot alone
                      would be colour as the only signal — and the pill is
                      neutral, because "primary" is a role, not a state, and a
                      colour that names no state is decoration. */}
                  {contact.primaryContact && <StatusPill status={t("contact.primary")} role="neutral" />}
                  <StatusPill status={contact.status} />

                  {canManage && (
                    <Button
                      type="button"
                      variant="secondary"
                      // Named for the person, like the invitation button beside
                      // it: two rows both reading "Edit" are indistinguishable to
                      // anyone navigating by control rather than by eye.
                      aria-label={t("contact.edit.for", { name })}
                      // ContactView.id is optional in the generated types, and
                      // `contact.id ?? ""` would PUT to `…/contacts/` — a
                      // different endpoint entirely. The invitation button beside
                      // this one already guards the same way.
                      disabled={!contactId}
                      onClick={() => {
                        update.reset();
                        setEditing(contact);
                      }}
                      style={{ height: "var(--ob-control-height-sm)" }}
                    >
                      {t("contact.edit")}
                    </Button>
                  )}

                  {canInvite &&
                    (sent ? (
                      // The button is spent: an invitation already on its way is
                      // not something to offer again on the same screen.
                      <span
                        className="text-text-muted whitespace-nowrap"
                        style={{ font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
                      >
                        {t("contact.invite.sent")}
                      </span>
                    ) : (
                      <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
                        {failed === contactId && (
                          <span
                            className="whitespace-nowrap"
                            style={{
                              color: "var(--ob-status-blocked-fg)",
                              font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
                            }}
                          >
                            {t("common.error")}
                          </span>
                        )}
                        <Button
                          type="button"
                          variant="secondary"
                          // Every row's button would otherwise read "Send
                          // invitation", which is indistinguishable to anyone
                          // navigating by control rather than by eye.
                          aria-label={t("contact.invite.for", { name })}
                          disabled={sending || !contactId}
                          onClick={() => {
                            setFailed(null);
                            invite.mutate(
                              { customerId, contactId },
                              {
                                onSuccess: () => {
                                  setInvited((previous) => new Set(previous).add(contactId));
                                  setAnnouncement(t("contact.invite.sent"));
                                },
                                // A failed invitation that says nothing leaves
                                // the sender believing it went out.
                                onError: () => {
                                  setFailed(contactId);
                                  setAnnouncement(t("common.error"));
                                },
                              },
                            );
                          }}
                          style={{ height: "var(--ob-control-height-sm)" }}
                        >
                          {t("contact.invite")}
                        </Button>
                      </div>
                    ))}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {adding && (
        <Dialog title={t("contact.create.title")} onClose={() => setAdding(false)}>
          <ContactForm
            submitLabel={t("contact.create.submit")}
            pending={create.isPending}
            error={writeError(create.error)}
            onSubmit={submitNew}
            onCancel={() => setAdding(false)}
          />
        </Dialog>
      )}

      {editing && (
        <Dialog title={t("contact.edit.title")} onClose={() => setEditing(null)}>
          {/* Keyed on the contact so switching rows remounts the form. Without
              it React reuses the instance and its useState initialisers do not
              re-run, so the second row opens showing the first row's values. */}
          <ContactForm
            key={editing.id}
            initial={editing}
            submitLabel={t("common.save")}
            pending={update.isPending}
            error={writeError(update.error)}
            onSubmit={(values) => submitEdit(editing, values)}
            onCancel={() => setEditing(null)}
          />
        </Dialog>
      )}
    </Card>
  );
}
