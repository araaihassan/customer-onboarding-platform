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
    <div className="flex flex-col">
      <label
        htmlFor={id}
        style={{
          fontSize: "11.5px",
          color: "var(--ob-text-subtle)",
          marginBottom: "5px",
          fontWeight: 500,
        }}
      >
        {label}
      </label>
      <input
        {...props}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        style={{
          border: `1px solid var(${error ? "--ob-risk-fg" : "--ob-line"})`,
          borderRadius: "var(--ob-radius-9)",
          padding: "9px 11px",
          fontSize: "13px",
          background: "var(--ob-surface)",
          color: error ? "var(--ob-risk-fg)" : "var(--ob-ink)",
          fontFamily: "var(--ob-font-family-ui)",
          ...props.style,
        }}
      />
      {error && (
        <p
          id={errorId}
          style={{
            color: "var(--ob-risk-fg)",
            fontSize: "11.5px",
            fontFamily: "var(--ob-font-family-ui)",
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
    <div className="flex flex-col">
      <label
        htmlFor={id}
        style={{
          fontSize: "11.5px",
          color: "var(--ob-text-subtle)",
          marginBottom: "5px",
          fontWeight: 500,
        }}
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
          border: `1px solid var(${error ? "--ob-risk-fg" : "--ob-line"})`,
          borderRadius: "var(--ob-radius-9)",
          padding: "9px 11px",
          fontSize: "13px",
          background: "var(--ob-surface)",
          color: error ? "var(--ob-risk-fg)" : "var(--ob-ink)",
          fontFamily: "var(--ob-font-family-ui)",
          resize: "vertical",
          ...props.style,
        }}
      />
      {error && (
        <p
          id={errorId}
          style={{
            color: "var(--ob-risk-fg)",
            fontSize: "11.5px",
            fontFamily: "var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}
    </div>
  );
}
