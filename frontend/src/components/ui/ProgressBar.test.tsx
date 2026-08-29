import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ProgressBar } from "./ProgressBar";

afterEach(cleanup);

/**
 * The one place this invariant can be proven.
 *
 * Review finding 9: the prototype renders progress as a bare div whose width is
 * a percentage, so a screen reader gets nothing — and the visible number beside
 * it is separate DOM text, meaning the two can disagree. `ProgressBar` binds
 * both to one clamped value so they cannot drift.
 *
 * That is a structural guarantee, and a structural guard nobody has watched fail
 * is a guard nobody can trust. Task 28 asserts the same thing at the screen
 * level, but its assertion is conditional on a progress bar being on screen —
 * and the customer screens have none, because `CustomerView` carries no progress
 * to render. So this is where it gets proven.
 */
function announced(): number {
  return Number(screen.getByRole("progressbar").getAttribute("aria-valuenow"));
}

function visible(): number {
  return Number(screen.getByText(/%$/).textContent!.replace("%", ""));
}

describe("ProgressBar", () => {
  it("announces exactly the percentage it shows", () => {
    render(<ProgressBar value={64} label="Onboarding progress" showPercentage />);

    expect(announced()).toBe(64);
    expect(visible()).toBe(64);
    expect(announced()).toBe(visible());
  });

  it("announces the rounded value it shows, not the raw one", () => {
    render(<ProgressBar value={66.6} label="Onboarding progress" showPercentage />);

    expect(visible()).toBe(67);
    expect(announced()).toBe(visible());
  });

  /**
   * The clamp is where the two can most easily part company: a naive
   * implementation clamps the bar's width and prints the raw number, so a bar
   * pinned at 100% announces 140.
   */
  it("keeps the two in step above the top of the range", () => {
    render(<ProgressBar value={140} label="Onboarding progress" showPercentage />);

    expect(visible()).toBe(100);
    expect(announced()).toBe(100);
  });

  it("keeps the two in step below the bottom of the range", () => {
    render(<ProgressBar value={-20} label="Onboarding progress" showPercentage />);

    expect(visible()).toBe(0);
    expect(announced()).toBe(0);
  });

  it("stays inside the declared range at both ends", () => {
    render(<ProgressBar value={140} label="Onboarding progress" />);

    const bar = screen.getByRole("progressbar");
    expect(bar.getAttribute("aria-valuemin")).toBe("0");
    expect(bar.getAttribute("aria-valuemax")).toBe("100");
  });

  /** A bar with no name announces a bare number, which names nothing. */
  it("carries an accessible name", () => {
    render(<ProgressBar value={40} label="Onboarding progress" />);
    expect(screen.getByRole("progressbar", { name: "Onboarding progress" })).not.toBeNull();
  });

  it.each([
    ["table-cell", "5px", "var(--ob-radius-4)"],
    ["stage-summary", "5px", "var(--ob-radius-4)"],
    ["case-hero", "7px", "var(--ob-radius-4)"],
    ["portal-sidebar", "5px", "var(--ob-radius-4)"],
    ["portal-card", "6px", "var(--ob-radius-4)"],
  ] as const)("context=%s sets the documented track height", (context, height) => {
    render(<ProgressBar value={50} label="Progress" context={context} />);
    expect(screen.getByRole("progressbar").style.height).toBe(height);
  });
});
