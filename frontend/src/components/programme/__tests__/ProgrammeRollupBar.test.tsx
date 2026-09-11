import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
// Registers toBeInTheDocument() etc. -- not wired into the global vitest config,
// so it is imported locally per file, the same convention Sidebar.test.tsx uses.
import "@testing-library/jest-dom/vitest";
import { ProgrammeRollupBar } from "../ProgrammeRollupBar";

afterEach(cleanup);

describe("ProgrammeRollupBar", () => {
  it("states how many journeys the rollup covers, in words", () => {
    render(<ProgrammeRollupBar percent={62} journeysCovered={3} />);
    // A scope-limited view must read as partial rather than as wrong (spec §6.4).
    expect(screen.getByText("62%")).toBeInTheDocument();
    expect(screen.getByText("across 3 journeys")).toBeInTheDocument();
  });

  it("renders an empty state, not a zero bar, when no journey is visible", () => {
    render(<ProgrammeRollupBar percent={0} journeysCovered={0} />);
    expect(screen.getByText("No journeys you can see")).toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  /**
   * `journeysCovered === 0` is the guard, not `percent === 0` -- a programme
   * genuinely at 0% progress with one real visible journey must still show the
   * bar, not the empty state.
   */
  it("shows the bar at 0% when a journey is visible but has not started", () => {
    render(<ProgrammeRollupBar percent={0} journeysCovered={1} />);
    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(screen.getByText("0%")).toBeInTheDocument();
    expect(screen.getByText("across 1 journeys")).toBeInTheDocument();
  });

  it("clamps an out-of-range percent defensively", () => {
    render(<ProgrammeRollupBar percent={140} journeysCovered={2} />);
    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
  });

  it("gives the progress bar an accessible name", () => {
    render(<ProgrammeRollupBar percent={40} journeysCovered={2} />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-label", "Programme progress");
  });
});
