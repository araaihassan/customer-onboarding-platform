import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Button } from "./Button";

afterEach(cleanup);

describe("Button", () => {
  it.each([
    ["primary", "var(--ob-ink)", "var(--ob-canvas)"],
    ["secondary", "var(--ob-surface)", "var(--ob-ink)"],
    ["small-primary", "var(--ob-ink)", "var(--ob-canvas)"],
    ["small-secondary", "var(--ob-surface)", "var(--ob-ink)"],
    ["portal-primary", "var(--ob-ink)", "var(--ob-canvas)"],
  ] as const)("variant=%s sets the documented background/text tokens", (variant, bg, color) => {
    render(<Button variant={variant}>Go</Button>);
    const el = screen.getByRole("button", { name: "Go" });
    expect(el.style.background).toBe(bg);
    expect(el.style.color).toBe(color);
  });

  it("danger-outline is transparent-on-surface with a risk border and risk text", () => {
    render(<Button variant="danger-outline">Force-complete</Button>);
    const el = screen.getByRole("button", { name: "Force-complete" });
    expect(el.style.background).toBe("var(--ob-surface)");
    expect(el.style.color).toBe("var(--ob-risk-fg)");
    expect(el.style.border).toContain("var(--ob-risk-border)");
  });

  it("text-link has no background or border", () => {
    render(<Button variant="text-link">Mark all read</Button>);
    const el = screen.getByRole("button", { name: "Mark all read" });
    expect(el.style.background).toBe("transparent");
    expect(el.style.border).toBe("");
  });

  it("disabled state uses the documented line/text-faint pair regardless of variant", () => {
    render(<Button variant="primary" disabled>Migrate</Button>);
    const el = screen.getByRole("button", { name: "Migrate" });
    expect(el.style.background).toBe("var(--ob-line)");
    expect(el.style.color).toBe("var(--ob-text-faint)");
  });
});
