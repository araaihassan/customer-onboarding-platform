import type { ReactNode } from "react";

/**
 * The shared frame for login, activation and password reset.
 *
 * The prototype has no authentication screens at all — it opens already signed in
 * — so these pages are the one part of the product with no visual reference. This
 * is deliberately assembled from the existing layers rather than invented: a flat
 * card (`--ob-surface`, 1px `--ob-line`, `--ob-radius-13` -- the modal/portal-card
 * radius, the closest analogue to a centred auth card) on the `--ob-canvas` page
 * ground, with the same brand mark as `Rail.tsx` and the same type steps used
 * everywhere else.
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
      style={{ background: "var(--ob-canvas)", padding: "var(--ob-space-22)" }}
    >
      <div
        className="bg-surface border border-line rounded-13 w-full"
        style={{ padding: "var(--ob-space-26) var(--ob-space-22)", maxWidth: "380px" }}
      >
        <div style={{ marginBottom: "var(--ob-space-22)" }}>
          <BrandMark />
          <h1
            className="text-ink"
            style={{
              marginTop: "var(--ob-space-16)",
              font: "600 var(--ob-type-page-title-size)/var(--ob-type-page-title-line) var(--ob-font-family-ui)",
              letterSpacing: "var(--ob-type-page-title-tracking)",
            }}
          >
            {title}
          </h1>
          {description && (
            <p
              className="text-text-muted"
              style={{ marginTop: "var(--ob-space-6)", font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)" }}
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

/** Same markup as `Rail.tsx`'s `BrandMark` -- this is the one other place the brand appears. */
function BrandMark() {
  return (
    <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="9" fill="var(--ob-accent-fg)" />
      <rect x="10.5" y="10.5" width="11" height="11" fill="var(--ob-canvas)" transform="rotate(45 16 16)" />
    </svg>
  );
}
