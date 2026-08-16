"use client";

import { useId } from "react";
import type { InputHTMLAttributes } from "react";

/**
 * A labelled input.
 *
 * The label is a real <label htmlFor>, not a placeholder. Placeholder-as-label
 * disappears the moment someone types, which is worst for exactly the users who
 * most need it, and it is not an accessible name at all.
 *
 * When `error` is set the input is marked aria-invalid and described by the
 * message, so the error reaches a screen reader rather than only the eye.
 */
export function Field({
  label,
  error,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string }) {
  const id = useId();
  const errorId = `${id}-error`;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
      <label
        htmlFor={id}
        className="text-text-secondary"
        style={{ font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
      >
        {label}
      </label>
      <input
        {...props}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        style={{
          height: "var(--ob-control-height)",
          borderRadius: "var(--ob-radius-control)",
          border: `1px solid var(${error ? "--ob-status-blocked-fg" : "--ob-border-default"})`,
          background: "var(--ob-bg-surface)",
          color: "var(--ob-text-primary)",
          padding: "0 var(--ob-space-11)",
          font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
          ...props.style,
        }}
      />
      {error && (
        <p
          id={errorId}
          style={{
            color: "var(--ob-status-blocked-fg)",
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}
    </div>
  );
}
