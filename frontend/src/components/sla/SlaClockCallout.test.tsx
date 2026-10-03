import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

/** Numbers and dates sit in their own data-font spans, so match the whole line's text. */
const line = (text: string) => screen.getByText((_, el) => el?.tagName === "P" && el.textContent === text);
import { SlaClockCallout } from "./SlaClockCallout";

afterEach(cleanup);

describe("SlaClockCallout", () => {
  it("renders nothing without a clock", () => {
    const { container } = render(<SlaClockCallout clock={null} />);
    expect(container).toBeEmptyDOMElement();
    const again = render(<SlaClockCallout clock={undefined} />);
    expect(again.container).toBeEmptyDOMElement();
  });

  it("shows a loading skeleton", () => {
    render(<SlaClockCallout clock={undefined} loading />);
    expect(screen.getByLabelText("Loading")).toBeInTheDocument();
  });

  it("explains a paused clock with its reason and calendar", () => {
    render(
      <SlaClockCallout
        clock={{ state: "PAUSED", pausedDays: 3.14, pauseReason: "OPEN_DOCUMENT_REQUEST", pauseEligible: true, calendarName: "UK working days" }}
      />,
    );
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByText("SLA CLOCK")).toBeInTheDocument();
    expect(line("Paused · 3.1 business days")).toBeInTheDocument();
    expect(line("Waiting on the customer's documents")).toBeInTheDocument();
    expect(line("UK working days · business days")).toBeInTheDocument();
  });

  it("shows the case-hold reason", () => {
    render(<SlaClockCallout clock={{ state: "PAUSED", pausedDays: 1, pauseReason: "CASE_HOLD" }} />);
    expect(line("Case on hold")).toBeInTheDocument();
  });

  it("flags an ineligible pause", () => {
    render(<SlaClockCallout clock={{ state: "RUNNING", remainingDays: 1, targetDays: 3, pauseEligible: false }} />);
    expect(line("NOT ELIGIBLE FOR PAUSE — INTERNAL REVIEW")).toBeInTheDocument();
  });

  it("shows time left when running", () => {
    render(<SlaClockCallout clock={{ state: "RUNNING", remainingDays: 1.25, targetDays: 3 }} />);
    expect(line("1.2 of 3 business days left")).toBeInTheDocument();
  });

  it("shows the breach and who it went to", () => {
    render(
      <SlaClockCallout
        clock={{
          state: "BREACHED",
          elapsedDays: 4.04,
          targetDays: 2,
          escalatedTo: { route: "MANAGER", name: "Dana Lee", at: "2026-10-02T09:00:00Z" },
        }}
      />,
    );
    expect(line("Breached by 2.0 business days")).toBeInTheDocument();
    expect(line("Escalated to Dana Lee on 2 Oct 2026")).toBeInTheDocument();
  });

  it("names administrators when no one person was routed", () => {
    render(
      <SlaClockCallout
        clock={{ state: "BREACHED", elapsedDays: 3, targetDays: 2, escalatedTo: { route: "ADMINISTRATORS", at: "2026-10-02T09:00:00Z" } }}
      />,
    );
    expect(line("Escalated to administrators on 2 Oct 2026")).toBeInTheDocument();
  });

  it("shows a met clock", () => {
    render(<SlaClockCallout clock={{ state: "MET", elapsedDays: 1.96 }} />);
    expect(line("Met in 1.9 business days")).toBeInTheDocument();
  });
});
