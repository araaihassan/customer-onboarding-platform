"use client";

import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import type { FormEvent } from "react";
import { AuthCard } from "@/components/auth/AuthCard";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { apiFetch } from "@/lib/api/client";
import { t } from "@/lib/i18n";

const MINIMUM_PASSWORD_LENGTH = 12;

/**
 * Asking for a link. Always shows the same confirmation, whether or not the
 * address has an account — the backend answers 204 either way precisely so this
 * endpoint cannot be used to enumerate accounts, and the UI must not undo that by
 * saying "no account found".
 */
function RequestForm() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    try {
      await apiFetch<void>("/auth/password-reset/request", {
        method: "POST",
        body: JSON.stringify({ email }),
      });
    } catch {
      // Swallowed on purpose. A visible failure here would distinguish addresses
      // the server accepted from ones it did not, which is the leak this whole
      // flow is shaped to avoid.
    } finally {
      setSent(true);
      setSubmitting(false);
    }
  }

  if (sent) {
    return (
      <AuthCard title={t("auth.reset.title")} description={t("auth.reset.sent")}>
        <span />
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t("auth.reset.title")}>
      <form onSubmit={onSubmit} className="flex flex-col" style={{ gap: "var(--ob-space-14)" }}>
        <Field
          label={t("auth.login.email")}
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Button type="submit" disabled={submitting}>
          {t("auth.reset.request")}
        </Button>
      </form>
    </AuthCard>
  );
}

/** Redeeming a link. Same shape and same password rule as activation. */
function ConfirmForm({ token }: { token: string }) {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();

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
      await apiFetch<void>("/auth/password-reset/confirm", {
        method: "POST",
        body: JSON.stringify({ token, password }),
      });
      // Every existing session was revoked server-side, so signing in again is the
      // only way forward — which is the intended outcome of a reset.
      router.replace(`/t/${slug}/login`);
    } catch {
      setError(t("auth.reset.invalid"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.reset.title")}>
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

        <Button type="submit" disabled={submitting || tooShort || mismatch || password.length === 0}>
          {t("auth.activate.submit")}
        </Button>
      </form>
    </AuthCard>
  );
}

function ResetPassword() {
  // One route, two forms. A ?token= means the user followed the emailed link;
  // without one they are asking for it. Two routes would duplicate the password
  // rules and the card.
  const token = useSearchParams().get("token");
  return token ? <ConfirmForm token={token} /> : <RequestForm />;
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPassword />
    </Suspense>
  );
}
