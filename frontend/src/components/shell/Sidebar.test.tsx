import type { AnchorHTMLAttributes, ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
// Registers toHaveAttribute() etc. on vitest's expect. Nothing else in this
// suite has needed a jest-dom matcher yet, so it isn't wired into the global
// vitest config -- imported locally here rather than widening that config for
// a single test file.
import "@testing-library/jest-dom/vitest";

/**
 * The rail is the one place where a permission the user does not hold becomes an
 * absent link rather than a 403. That makes it worth testing directly: a
 * regression here is silent — the entry simply stops appearing, or appears for
 * someone who cannot use it and sends them to a 404.
 *
 * useHasPermission is deliberately NOT mocked; the real scope algebra runs, and
 * only the permission map underneath it is substituted.
 */
let pathname = "/t/acme/dashboard";
let permissions: Record<string, string[]> = {};

/**
 * jsdom has no real `matchMedia` implementation at all (calling it throws),
 * so every test needs one stubbed in, defaulting to "desktop" (matches the
 * component's own SSR-safe default) unless a test explicitly narrows it.
 */
let mediaMatches = true;

function stubMatchMedia() {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: mediaMatches,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
}

vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    ...rest
  }: { href: string; children: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { Sidebar } = await import("./Sidebar");

function linkNamed(name: RegExp) {
  return screen.queryByRole("link", { name });
}

beforeEach(() => {
  pathname = "/t/acme/dashboard";
  permissions = {};
  mediaMatches = true;
  stubMatchMedia();
});

afterEach(cleanup);

describe("Sidebar", () => {
  /**
   * The rail's edge is a border on this component, not a difference between two
   * background tokens.
   *
   * bg-rail is pinned to slate-950 in BOTH themes — that is what keeps
   * navigation stable when the theme flips — and in dark the page ground sits
   * just below it, so the palette caps the rail/page contrast at 1.17:1 even
   * with a pure black page. Task R1 moved the page from 1.00:1 to 1.10:1, which
   * is measurable and still invisible. Only a border fixes it, and it has to be
   * one value in both themes for the same reason the rail is.
   */
  it("draws its own right edge, because no pair of surfaces can", () => {
    render(<Sidebar slug="acme" />);
    const rail = screen.getByRole("navigation").closest("aside");

    expect(rail?.style.borderRight).toBe("1px solid var(--ob-line)");
  });

  it("renders a navigation landmark containing a list", () => {
    render(<Sidebar slug="acme" />);
    const nav = screen.getByRole("navigation");
    expect(within(nav).getByRole("list")).not.toBeNull();
  });

  it("always offers Dashboard, which needs no permission", () => {
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/dashboard/i)?.getAttribute("href")).toBe("/t/acme/dashboard");
  });

  it("omits Customers without customer.view", () => {
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/customers/i)).toBeNull();
  });

  it("offers Customers with customer.view at any scope", () => {
    permissions = { "customer.view": ["ASSIGNED"] };
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/customers/i)?.getAttribute("href")).toBe("/t/acme/customers");
  });

  it("omits Administration without role.view or user.view", () => {
    permissions = { "customer.view": ["ALL"] };
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/administration/i)).toBeNull();
  });

  it("offers Administration with user.view alone, pointing at users", () => {
    permissions = { "user.view": ["ALL"] };
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/administration/i)?.getAttribute("href")).toBe("/t/acme/admin/users");
  });

  /**
   * role.view alone must not link to the users screen — the user would land on a
   * page they cannot read. The entry points at the one they can.
   */
  it("offers Administration with role.view alone, pointing at roles", () => {
    permissions = { "role.view": ["ALL"] };
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/administration/i)?.getAttribute("href")).toBe("/t/acme/admin/roles");
  });

  it("carries the tenant slug through every href", () => {
    permissions = { "customer.view": ["ALL"], "user.view": ["ALL"] };
    render(<Sidebar slug="northwind" />);
    for (const link of screen.getAllByRole("link")) {
      expect(link.getAttribute("href")).toMatch(/^\/t\/northwind\//);
    }
  });

  it("marks exactly the active entry with aria-current=page", () => {
    permissions = { "customer.view": ["ALL"] };
    pathname = "/t/acme/customers";
    render(<Sidebar slug="acme" />);
    const current = screen.getAllByRole("link").filter((l) => l.getAttribute("aria-current") === "page");
    expect(current).toHaveLength(1);
    expect(current[0]?.textContent).toMatch(/customers/i);
  });

  /** A record page under /customers/ is still "Customers" as far as the rail goes. */
  it("keeps the section active on a nested route", () => {
    permissions = { "user.view": ["ALL"] };
    pathname = "/t/acme/admin/roles";
    render(<Sidebar slug="acme" />);
    expect(linkNamed(/administration/i)?.getAttribute("aria-current")).toBe("page");
  });

  it("marks no entry current on a route outside the navigation", () => {
    pathname = "/t/acme/settings/profile";
    render(<Sidebar slug="acme" />);
    expect(screen.getAllByRole("link").some((l) => l.hasAttribute("aria-current"))).toBe(false);
  });

  /** The label is already there; announcing the glyph too is noise. */
  it("hides nav icons from assistive technology", () => {
    permissions = { "customer.view": ["ALL"] };
    const { container } = render(<Sidebar slug="acme" />);
    const icons = container.querySelectorAll("nav svg");
    expect(icons.length).toBeGreaterThan(0);
    for (const icon of icons) expect(icon.getAttribute("aria-hidden")).toBe("true");
  });

  /**
   * Below 1024px the sidebar is a drawer: hidden from the accessibility tree
   * until isOpen flips true (SCREENS.md's RESPONSIVE table). At 1024px and
   * above it stays inline regardless of isOpen -- Task 6 wires the toggle.
   */
  it("renders as a drawer below 1024px, hidden until isOpen", () => {
    mediaMatches = false; // matchMedia("(min-width: 1024px)") -- below it
    stubMatchMedia();
    // getByRole excludes aria-hidden elements from its default accessibility-
    // tree filter, so finding the closed drawer's nav needs { hidden: true } --
    // that's the point under test, not a workaround for it.
    const { rerender } = render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
    expect(screen.getByRole("navigation", { hidden: true })).toHaveAttribute("aria-hidden", "true");
    rerender(<Sidebar slug="acme" isOpen={true} onClose={vi.fn()} />);
    expect(screen.getByRole("navigation")).not.toHaveAttribute("aria-hidden");
  });

  /**
   * Regression: the real layout (TenantLayout) always passes onClose (so
   * isDrawer is always true) and starts with isOpen false -- exactly the
   * state every authenticated screen renders in by default. Before this fix,
   * aria-hidden was set from `isDrawer && !isOpen` alone, with no regard for
   * the actual viewport -- so on a genuine >=1024px screen, where the CSS
   * keeps the aside inline and visible regardless of isOpen, the <nav> was
   * STILL marked aria-hidden="true", removing the whole navigation landmark
   * from the accessibility tree on every authenticated page by default. This
   * is invisible to jsdom-based assertions on desktop width alone (jsdom
   * never evaluates the `max-lg:` media query either way), which is exactly
   * why e2e/auth.spec.ts -- a real browser -- is what actually caught it.
   */
  it("does not hide the drawer's nav at >=1024px, even while isOpen is false", () => {
    mediaMatches = true; // matchMedia("(min-width: 1024px)") -- at or above it
    stubMatchMedia();
    render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
    expect(screen.getByRole("navigation")).not.toHaveAttribute("aria-hidden");
  });

  /**
   * axe's aria-hidden-focus rule: an aria-hidden element must not contain a
   * focusable descendant, because a screen reader hides it while Tab still
   * lands on it -- a real, sighted-keyboard-user-only trap. aria-hidden alone
   * never removes an element from the tab order, so the closed drawer's links
   * stayed Tab-reachable while invisible and announced as hidden -- caught
   * live by axe in e2e/accessibility.spec.ts's <768px sweep, not by this
   * suite, since jsdom's `render` never evaluates focus order either way.
   * `inert` is the one HTML primitive that removes both the accessibility
   * tree presence AND focusability/hit-testing in one step, which is why the
   * fix uses it instead of a hand-rolled tabIndex sweep over every NavLink.
   */
  it("also gates the closed drawer with inert, not aria-hidden alone", () => {
    mediaMatches = false; // below 1024px
    stubMatchMedia();
    const { container, rerender } = render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
    expect(container.querySelector("aside")).toHaveAttribute("inert");

    rerender(<Sidebar slug="acme" isOpen={true} onClose={vi.fn()} />);
    expect(container.querySelector("aside")).not.toHaveAttribute("inert");
  });

  it("never applies inert at >=1024px, even while isOpen is false", () => {
    mediaMatches = true; // at or above 1024px
    stubMatchMedia();
    const { container } = render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
    expect(container.querySelector("aside")).not.toHaveAttribute("inert");
  });

  it("calls onClose on Escape when open as a drawer", () => {
    const onClose = vi.fn();
    render(<Sidebar slug="acme" isOpen={true} onClose={onClose} />);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });

  /**
   * Regression for the bug found wiring Task 6's tenant layout: the aside's
   * off-screen positioning previously came from an unconditional inline
   * `transform: translateX(-100%)` whenever isDrawer && !isOpen -- true on
   * every real render, since the real layout always passes onClose and starts
   * closed -- so the persistent desktop sidebar was translated off-screen at
   * EVERY width, including >=1024px, until the drawer was toggled once.
   *
   * jsdom never evaluates CSS media queries, so setting window.innerWidth to
   * a desktop value and asserting on getComputedStyle would pass even with
   * the bug still in place -- jsdom would just apply every media-gated rule
   * (or none) regardless of the width it reports. The only assertion that
   * actually distinguishes "always off-screen" from "off-screen only below
   * 1024px" in this environment is a structural one: prove the fix removed
   * the *unconditional* inline transform entirely, and that the off-screen
   * positioning is now expressed solely through a className carrying the
   * `max-lg:` prefix (Tailwind's `lg` is 1024px, the breakpoint this always
   * meant). That prefix is what a real browser's media
   * query engine (which this test cannot invoke) uses to withhold the
   * translation at >=1024px; a className search proves the gate exists at
   * all, which the old code -- correct display classes, ungated inline style
   * -- would fail.
   */
  it("gates the closed drawer's off-screen position below 1024px, never inline/unconditionally", () => {
    const { container } = render(<Sidebar slug="acme" isOpen={false} onClose={vi.fn()} />);
    const aside = container.querySelector("aside");
    expect(aside).not.toBeNull();

    // The bug: an inline `transform` applied with no breakpoint condition at
    // all. Asserting its absence is what catches a regression back to it.
    expect(aside?.style.transform).toBe("");

    // The fix: the off-screen state is a className, scoped under a breakpoint
    // -- never bare, which would reapply at every width.
    expect(aside?.className).toContain("max-lg:-translate-x-full");
    expect(aside?.className).not.toMatch(/(?<!max-lg:)-translate-x-full/);

    // A second, later bug this same line has to guard against. The gate was
    // once written `max-[1023px]:flex hidden min-[1024px]:flex`, whose two
    // arbitrary variants were meant to override the `hidden` at every width.
    // Neither generated any CSS -- Tailwind extracts class names from source
    // text, and `min-[1024px]:flex${` written flush against an interpolation
    // boundary is not a token it can see -- so only `hidden` survived and the
    // sidebar was `display:none` at every width, leaving the application with
    // no navigation at all. jsdom cannot evaluate media queries, so this
    // asserts the structural invariant instead: the drawer is never `hidden`,
    // and its visibility never depends on an arbitrary variant that might
    // silently fail to compile.
    expect(aside?.className).toContain("flex");
    expect(aside?.className).not.toContain("hidden");
    expect(aside?.className).not.toMatch(/(?:min|max)-\[\d+px\]:/);

    // Opening the drawer removes the off-screen class again, same as before.
    const { container: openContainer } = render(<Sidebar slug="acme" isOpen={true} onClose={vi.fn()} />);
    expect(openContainer.querySelector("aside")?.className).not.toContain("-translate-x-full");
  });
});
