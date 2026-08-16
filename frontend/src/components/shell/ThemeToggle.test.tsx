import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

/**
 * The toggle drives next-themes, which writes data-theme on <html>. Asserting the
 * setTheme call rather than the attribute keeps the test on this component's own
 * contract; the attribute is next-themes' job and ThemeProvider already fixes the
 * one setting that matters (attribute="data-theme", not "class").
 */
let resolvedTheme: string | undefined;
const setTheme = vi.fn();

vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme, setTheme }) }));

const { ThemeToggle } = await import("./ThemeToggle");

beforeEach(() => {
  resolvedTheme = "light";
  setTheme.mockClear();
});

afterEach(cleanup);

describe("ThemeToggle", () => {
  it("offers dark when the resolved theme is light", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button", { name: /switch to dark theme/i });
    button.click();
    expect(setTheme).toHaveBeenCalledWith("dark");
  });

  it("offers light when the resolved theme is dark", () => {
    resolvedTheme = "dark";
    render(<ThemeToggle />);
    const button = screen.getByRole("button", { name: /switch to light theme/i });
    button.click();
    expect(setTheme).toHaveBeenCalledWith("light");
  });

  /**
   * The current theme is named in text, not signalled by colour alone — the
   * fourth design rule, and the only rule a two-state colour swatch would break.
   */
  it("names the theme it will switch to in visible text", () => {
    render(<ThemeToggle />);
    expect(screen.getByRole("button").textContent).toMatch(/dark/i);
  });
});
