import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { SlaChip } from "./SlaChip";

afterEach(cleanup);

describe("SlaChip", () => {
  it("always carries the word", () => {
    render(<SlaChip clock={{ state: "BREACHED", elapsedDays: 4.04, targetDays: 2 }} />);
    expect(screen.getByText(/breached 2\.0d/i)).toBeInTheDocument();
  });

  it("prefixes SLA", () => {
    render(<SlaChip prefix clock={{ state: "PAUSED", pausedDays: 3.14 }} />);
    expect(screen.getByText(/^sla paused 3\.1d$/i)).toBeInTheDocument();
  });

  it.each([
    ["risk", { state: "BREACHED" as const }],
    ["info", { state: "PAUSED" as const }],
    ["ok", { state: "MET" as const }],
    ["warn", { state: "RUNNING" as const, dueToday: true }],
    ["neutral", { state: "RUNNING" as const, remainingDays: 2 }],
  ])("maps tone %s to its status colours", (role, clock) => {
    render(<SlaChip clock={clock} />);
    const el = screen.getByTestId("sla-chip");
    expect(el.firstElementChild?.getAttribute("style")).toContain(`--ob-${role}-bg`);
  });
});
