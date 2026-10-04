import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { SlaClock } from "@/lib/api/sla";
import { SlaChip } from "./SlaChip";

afterEach(cleanup);

describe("SlaChip", () => {
  it.each<[string, SlaClock, string]>([
    ["breached", { state: "BREACHED", elapsedDays: 4.04, targetDays: 2 }, "BREACHED 2.0d"],
    ["running", { state: "RUNNING", remainingDays: 1.25 }, "1.2d LEFT"],
    ["at risk", { state: "RUNNING", atRisk: true, remainingDays: 0.44 }, "0.4d LEFT"],
    ["met", { state: "MET" }, "MET"],
    ["due today", { state: "RUNNING", dueToday: true }, "DUE TODAY"],
  ])("renders %s in the design's exact casing", (_n, clock, text) => {
    render(<SlaChip clock={clock} />);
    expect(screen.getByText(text)).toBeInTheDocument();
  });

  it("prefixes SLA, exact casing", () => {
    render(<SlaChip prefix clock={{ state: "PAUSED", pausedDays: 3.14 }} />);
    expect(screen.getByText("SLA PAUSED 3.1d")).toBeInTheDocument();
  });

  it("does not uppercase the unit and sets the data font", () => {
    render(<SlaChip clock={{ state: "PAUSED", pausedDays: 3.14 }} />);
    const pill = screen.getByText("PAUSED 3.1d");
    expect(pill.style.textTransform).toBe("none");
    expect(pill.getAttribute("style")).toContain("--ob-font-family-data");
  });

  it.each([
    ["risk", { state: "BREACHED" as const }],
    ["info", { state: "PAUSED" as const }],
    ["ok", { state: "MET" as const }],
    ["warn", { state: "RUNNING" as const, dueToday: true }],
    ["ok", { state: "RUNNING" as const, remainingDays: 2 }],
  ])("maps tone %s to its status colours", (role, clock) => {
    render(<SlaChip clock={clock} />);
    const el = screen.getByTestId("sla-chip");
    expect(el.firstElementChild?.getAttribute("style")).toContain(`--ob-${role}-bg`);
  });
});
