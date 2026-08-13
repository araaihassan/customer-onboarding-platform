import type { ReactNode } from "react";

/**
 * The shared frame for login, activation and password reset.
 *
 * The prototype has no authentication screens at all — it opens already signed in
 * — so these pages are the one part of the product with no visual reference. This
 * is deliberately assembled from the existing layers rather than invented: it is
 * the §3 Card (flat, bg-surface, 1px border-default, card radius) centred on the
 * page ground, with the same type steps used everywhere else.
 *
 * The one thing not to do here is start a second visual language because there was
 * nothing to copy.
 */
export function AuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <main
      className="flex min-h-screen items-center justify-center"
      style={{ background: "var(--ob-bg-page)", padding: "var(--ob-space-24)" }}
    >
      <div
        className="bg-bg-surface border border-border-default rounded-card w-full"
        style={{ maxWidth: "380px", padding: "var(--ob-space-28) var(--ob-space-24)" }}
      >
        <div style={{ marginBottom: "var(--ob-space-22)" }}>
          <h1
            className="text-text-primary"
            style={{ font: "600 var(--ob-type-17-size)/var(--ob-type-17-line) var(--ob-font-family-ui)", letterSpacing: "-0.02em" }}
          >
            {title}
          </h1>
          {description && (
            <p
              className="text-text-muted"
              style={{ marginTop: "var(--ob-space-6)", font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
            >
              {description}
            </p>
          )}
        </div>

        {children}

        {footer && <div style={{ marginTop: "var(--ob-space-18)" }}>{footer}</div>}
      </div>
    </main>
  );
}
