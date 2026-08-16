import { Button } from "./Button";
import { t } from "@/lib/i18n";

/**
 * Page position and the two moves.
 *
 * Lifted out of the customer list when the user list needed the same thing. One
 * copy, because two copies drift — and a list that reports a total larger than
 * the page it shows, with no way to reach the rest, is the specific failure this
 * exists to prevent.
 *
 * `label` is the caller's: "Customer list pages" and "User list pages" are
 * different landmarks, and two navigation regions with the same accessible name
 * are indistinguishable to anyone listing them.
 */
export function Pagination({
  label,
  page,
  totalPages,
  onChange,
  disabled,
}: {
  label: string;
  /** Zero-based, as the API numbers them. */
  page: number;
  totalPages: number;
  onChange: (next: number) => void;
  disabled: boolean;
}) {
  return (
    <nav
      aria-label={label}
      className="flex items-center justify-end"
      style={{ gap: "var(--ob-space-11)", marginTop: "var(--ob-space-16)" }}
    >
      {/* A page position is a machine-generated value, so it is mono. */}
      <span
        className="text-text-muted"
        style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)" }}
      >
        {t("page.position", { page: String(page + 1), pages: String(totalPages) })}
      </span>
      {/* Text labels, not bare chevrons: an icon-only control with no accessible
          name is unusable to anyone not looking at it. */}
      <Button
        type="button"
        variant="secondary"
        disabled={disabled || page === 0}
        onClick={() => onChange(page - 1)}
      >
        {t("page.previous")}
      </Button>
      <Button
        type="button"
        variant="secondary"
        disabled={disabled || page >= totalPages - 1}
        onClick={() => onChange(page + 1)}
      >
        {t("page.next")}
      </Button>
    </nav>
  );
}
