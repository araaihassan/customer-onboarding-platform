import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ScopeFilterRow } from "./ScopeFilterRow";

afterEach(cleanup);

describe("ScopeFilterRow", () => {
  it("renders all five filters named by SCREENS.md §7, in order", () => {
    render(<ScopeFilterRow active="ALL" onChange={vi.fn()} />);
    const group = screen.getByRole("group");
    const buttons = Array.from(group.querySelectorAll("button")).map((b) => b.textContent);
    expect(buttons).toEqual(["All in scope", "Company-shared", "Contact-only", "Sensitive", "Open requests"]);
  });

  it("marks the active filter with aria-pressed and ink fill, the rest idle", () => {
    render(<ScopeFilterRow active="SENSITIVE" onChange={vi.fn()} />);

    const active = screen.getByRole("button", { name: "Sensitive" });
    expect(active.getAttribute("aria-pressed")).toBe("true");
    expect(active.style.background).toBe("var(--ob-ink)");

    const idle = screen.getByRole("button", { name: "Company-shared" });
    expect(idle.getAttribute("aria-pressed")).toBe("false");
    expect(idle.style.background).toBe("var(--ob-surface)");
  });

  it("calls onChange with the tier when a filter is clicked", () => {
    const onChange = vi.fn();
    render(<ScopeFilterRow active="ALL" onChange={onChange} />);

    screen.getByRole("button", { name: "Contact-only" }).click();

    expect(onChange).toHaveBeenCalledWith("CONTACT_ONLY");
  });

  it("calls onChange with ALL for the first filter", () => {
    const onChange = vi.fn();
    render(<ScopeFilterRow active="SENSITIVE" onChange={onChange} />);

    screen.getByRole("button", { name: "All in scope" }).click();

    expect(onChange).toHaveBeenCalledWith("ALL");
  });

  /** Ruling 4: rendered, per SCREENS.md §7, but disabled -- no backing list endpoint exists yet. */
  it("renders 'Open requests' disabled, and it never fires onChange", () => {
    const onChange = vi.fn();
    render(<ScopeFilterRow active="ALL" onChange={onChange} />);

    const openRequests = screen.getByRole("button", { name: "Open requests" }) as HTMLButtonElement;
    expect(openRequests.disabled).toBe(true);

    openRequests.click();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("is a real button group with an accessible name, per the spec's own accessibility note", () => {
    render(<ScopeFilterRow active="ALL" onChange={vi.fn()} />);
    expect(screen.getByRole("group", { name: "Filter documents by scope" })).not.toBeNull();
  });
});
