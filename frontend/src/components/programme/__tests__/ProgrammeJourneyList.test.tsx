import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { ProgrammeJourneyList } from "../ProgrammeJourneyList";
import type { ProgrammeJourney } from "@/lib/api/programmes";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

afterEach(cleanup);

const j: ProgrammeJourney = {
  caseId: "case-1",
  name: "Enterprise onboarding",
  status: "ACTIVE",
  progressPercent: 40,
};

describe("ProgrammeJourneyList", () => {
  it("pairs every journey status colour with a word", () => {
    render(<ProgrammeJourneyList journeys={[{ ...j, status: "ON_HOLD" }]} />);
    expect(screen.getByText("On hold")).toBeInTheDocument();
  });

  it("renders an empty state when the programme has no visible journeys", () => {
    render(<ProgrammeJourneyList journeys={[]} />);
    expect(screen.getByText("No journeys linked yet")).toBeInTheDocument();
  });

  it("renders the journey name as plain text with no routing context", () => {
    render(<ProgrammeJourneyList journeys={[j]} />);
    expect(screen.queryByRole("link", { name: /enterprise onboarding/i })).toBeNull();
    expect(screen.getByText("Enterprise onboarding")).toBeInTheDocument();
  });

  it("links the journey name into the case workspace once slug and customerId are known", () => {
    render(<ProgrammeJourneyList journeys={[j]} slug="acme" customerId="cust-1" />);
    const link = screen.getByRole("link", { name: /enterprise onboarding/i });
    expect(link).toHaveAttribute("href", "/t/acme/customers/cust-1/cases/case-1");
  });

  it("shows each journey's own progress percentage", () => {
    render(<ProgrammeJourneyList journeys={[j]} />);
    expect(screen.getByText("40%")).toBeInTheDocument();
  });
});
