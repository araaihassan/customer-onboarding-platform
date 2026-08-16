"use client";

import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import type { FormEvent } from "react";
import { AuthCard } from "@/components/auth/AuthCard";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/useAuth";
import { loginErrorMessage } from "@/lib/auth/loginError";
import { t } from "@/lib/i18n";

export default function LoginPage() {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.replace(`/t/${slug}/dashboard`);
    } catch (caught) {
      // One message for every 401 cause — see loginErrorMessage.
      setError(loginErrorMessage(caught instanceof ApiError ? caught.status : undefined));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.login.title")}>
      <form onSubmit={onSubmit} className="flex flex-col" style={{ gap: "var(--ob-space-14)" }}>
        <Field
          label={t("auth.login.email")}
          type="email"
          name="email"
          autoComplete="username"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Field
          label={t("auth.login.password")}
          type="password"
          name="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />

        {/* role="alert" so the failure is announced, not only shown. The message is
            attached to the form rather than to a field, because it deliberately
            does not say which input was wrong. */}
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

        <Button type="submit" disabled={submitting}>
          {t("auth.login.submit")}
        </Button>
      </form>

      <p
        className="text-text-muted"
        style={{ marginTop: "var(--ob-space-16)", font: "var(--ob-type-12-size)/var(--ob-type-12-line) var(--ob-font-family-ui)" }}
      >
        <a href={`/t/${slug}/reset-password`} style={{ color: "var(--ob-accent)" }}>
          {t("auth.reset.title")}
        </a>
      </p>
    </AuthCard>
  );
}
