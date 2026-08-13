"use client";

import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import type { FormEvent } from "react";
import { AuthCard } from "@/components/auth/AuthCard";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { apiFetch } from "@/lib/api/client";
import { t } from "@/lib/i18n";

/** Matches the backend's @Size(min = 12) on the activation request. */
const MINIMUM_PASSWORD_LENGTH = 12;

function ActivateForm() {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();
  const token = useSearchParams().get("token") ?? "";

  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const tooShort = password.length > 0 && password.length < MINIMUM_PASSWORD_LENGTH;
  const mismatch = confirmation.length > 0 && confirmation !== password;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await apiFetch<void>("/auth/activate", {
        method: "POST",
        body: JSON.stringify({ token, password }),
      });
      // Straight to login rather than signing them in here: activation returns 204
      // and issues no session, so there is no token to adopt.
      router.replace(`/t/${slug}/login`);
    } catch {
      // The backend answers one bare 400 for unknown, expired, already-used,
      // revoked and wrong-purpose alike, so there is nothing more specific to say
      // — and saying "expired" would confirm the token was once real.
      setError(t("auth.activate.invalid"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.activate.title")}>
      <form onSubmit={onSubmit} className="flex flex-col" style={{ gap: "var(--ob-space-14)" }}>
        <Field
          label={t("auth.activate.password")}
          type="password"
          autoComplete="new-password"
          required
          minLength={MINIMUM_PASSWORD_LENGTH}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={tooShort ? t("auth.activate.tooShort") : undefined}
        />
        <Field
          label={t("auth.activate.confirm")}
          type="password"
          autoComplete="new-password"
          required
          value={confirmation}
          onChange={(e) => setConfirmation(e.target.value)}
          error={mismatch ? t("auth.activate.mismatch") : undefined}
        />

        {error && (
          <p
            role="alert"
            style={{
              color: "var(--ob-status-blocked-fg)",
              font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)",
            }}
          >
            {error}
          </p>
        )}

        <Button
          type="submit"
          disabled={submitting || !token || tooShort || mismatch || password.length === 0}
        >
          {t("auth.activate.submit")}
        </Button>
      </form>
    </AuthCard>
  );
}

export default function ActivatePage() {
  // useSearchParams needs a Suspense boundary, or the whole route opts out of
  // static rendering and the build warns.
  return (
    <Suspense fallback={null}>
      <ActivateForm />
    </Suspense>
  );
}
