"use client";

import { useId } from "react";
import type { InputHTMLAttributes, TextareaHTMLAttributes } from "react";

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

/**
 * A labelled textarea -- Field's sibling for a reason that needs more than one
 * line (force-complete, hold). Same label/error contract; a fixed height
 * rather than `--ob-control-height`, which is sized for a single-line input.
 */
export function TextareaField({
  label,
  error,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string }) {
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
      <textarea
        {...props}
        id={id}
        rows={props.rows ?? 3}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        style={{
          borderRadius: "var(--ob-radius-control)",
          border: `1px solid var(${error ? "--ob-status-blocked-fg" : "--ob-border-default"})`,
          background: "var(--ob-bg-surface)",
          color: "var(--ob-text-primary)",
          padding: "var(--ob-space-8) var(--ob-space-11)",
          font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)",
          resize: "vertical",
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
