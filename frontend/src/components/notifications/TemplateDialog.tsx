"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field, TextareaField } from "@/components/ui/Field";
import { Switch } from "@/components/ui/Switch";
import { useToast } from "@/components/ui/Toast";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useCreateNotificationTemplate, useUpdateNotificationTemplate } from "@/lib/api/notifications";
import type { NotificationTemplate } from "@/lib/api/notifications";
import { t } from "@/lib/i18n";

/**
 * Create or edit a notification template.
 *
 * The placeholder set mirrors backend TemplatePlaceholders.ALLOWED (a closed
 * vocabulary, refused at save, never at send). Lengths mirror the request DTOs:
 * name 120, subjects 200, bodies 2000. The key mirrors the V37 CHECK
 * (`^[a-z0-9][a-z0-9_.-]{0,63}$`) and is immutable after create, because
 * stages refer to it -- edit mode shows it read-only. The exit pair is
 * both-or-neither (a database CHECK): switched off, or left blank, both travel
 * as null. The PUT is a full replace, so edit always sends every field.
 */
const PLACEHOLDERS = ["{case}", "{customer}", "{stage}", "{owner}"] as const;
const KEY_PATTERN = /^[a-z0-9][a-z0-9_.-]{0,63}$/;
const MONO = "var(--ob-font-family-data)";
const UI = "var(--ob-font-family-ui)";

function problem(error: Error): string {
  return error instanceof ApiError ? parseProblemDetail(error.message) : t("common.error");
}

export function TemplateDialog({ template, onClose }: { template?: NotificationTemplate; onClose: () => void }) {
  const toast = useToast();
  const create = useCreateNotificationTemplate();
  const update = useUpdateNotificationTemplate();
  const editing = template !== undefined;

  const [key, setKey] = useState(template?.key ?? "");
  const [name, setName] = useState(template?.name ?? "");
  const [enteredSubject, setEnteredSubject] = useState(template?.enteredSubject ?? "");
  const [enteredBody, setEnteredBody] = useState(template?.enteredBody ?? "");
  const [onExit, setOnExit] = useState(Boolean(template?.exitedSubject || template?.exitedBody));
  const [exitedSubject, setExitedSubject] = useState(template?.exitedSubject ?? "");
  const [exitedBody, setExitedBody] = useState(template?.exitedBody ?? "");
  const [error, setError] = useState<string | null>(null);

  const keyValid = editing || KEY_PATTERN.test(key);
  const valid =
    keyValid && name.trim() !== "" && enteredSubject.trim() !== "" && enteredBody.trim() !== "";
  const pending = create.isPending || update.isPending;

  function submit() {
    if (!valid) return;
    setError(null);
    const xs = onExit ? exitedSubject.trim() : "";
    const xb = onExit ? exitedBody.trim() : "";
    if ((xs === "") !== (xb === "")) {
      setError(t("notifications.templates.exitPair"));
      return;
    }
    const body = {
      key,
      name,
      enteredSubject,
      enteredBody,
      exitedSubject: xs === "" ? null : exitedSubject,
      exitedBody: xb === "" ? null : exitedBody,
      active: template?.active ?? true,
    };
    const opts = {
      onSuccess: () => {
        toast.show(t("notifications.templates.saved"));
        onClose();
      },
      onError: (err: Error) =>
        setError(err instanceof ApiError && err.status === 409 ? t("notifications.templates.duplicate") : problem(err)),
    };
    // The generated types mark the exit pair optional-string; the server reads null as "no exit alert".
    if (template?.id) update.mutate({ id: template.id, body: body as never }, opts);
    else create.mutate(body as never, opts);
  }

  return (
    <Dialog
      title={editing ? t("notifications.templates.edit") : t("notifications.templates.new")}
      onClose={onClose}
      maxWidth={560}
    >
      <form
        className="flex flex-col"
        style={{ gap: "var(--ob-space-13)" }}
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Field
          label={t("notifications.templates.key")}
          value={key}
          readOnly={editing}
          maxLength={64}
          onChange={(e) => setKey(e.target.value)}
          style={{ fontFamily: MONO }}
          error={!editing && key !== "" && !keyValid ? t("notifications.templates.keyInvalid") : undefined}
        />
        {!editing && (
          <p className="text-text-subtle" style={{ font: `11.5px/1.4 ${UI}`, marginTop: -8 }}>
            {t("notifications.templates.keyHint")}
          </p>
        )}
        <Field
          label={t("notifications.templates.name")}
          value={name}
          maxLength={120}
          onChange={(e) => setName(e.target.value)}
        />

        <div className="text-text-subtle" style={{ font: `11.5px/1.5 ${UI}` }}>
          {t("notifications.templates.placeholders")}{" "}
          {PLACEHOLDERS.map((p) => (
            <span
              key={p}
              className="text-ink"
              style={{ fontFamily: MONO, marginRight: 8, display: "inline-block" }}
            >
              {p}
            </span>
          ))}
        </div>

        <Field
          label={t("notifications.templates.enteredSubject")}
          value={enteredSubject}
          maxLength={200}
          onChange={(e) => setEnteredSubject(e.target.value)}
        />
        <TextareaField
          label={t("notifications.templates.enteredBody")}
          value={enteredBody}
          maxLength={2000}
          onChange={(e) => setEnteredBody(e.target.value)}
        />

        <div className="border-t border-line-faint" style={{ paddingTop: "var(--ob-space-13)" }}>
          <Switch checked={onExit} onChange={setOnExit} label={t("notifications.templates.alsoOnExit")} />
        </div>
        {onExit && (
          <>
            <Field
              label={t("notifications.templates.exitedSubject")}
              value={exitedSubject}
              maxLength={200}
              onChange={(e) => setExitedSubject(e.target.value)}
            />
            <TextareaField
              label={t("notifications.templates.exitedBody")}
              value={exitedBody}
              maxLength={2000}
              onChange={(e) => setExitedBody(e.target.value)}
            />
          </>
        )}

        {error && (
          <p role="alert" style={{ color: "var(--ob-risk-fg)", font: `11.5px/1.4 ${UI}` }}>
            {error}
          </p>
        )}
        <DialogActions>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button type="submit" disabled={!valid || pending}>
            {t("common.save")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
