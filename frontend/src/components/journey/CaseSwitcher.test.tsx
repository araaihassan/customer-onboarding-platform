import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CaseSwitcher } from "./CaseSwitcher";
import type { Case } from "@/lib/api/cases";

afterEach(cleanup);

const cases: Case[] = [
  { id: "c-1", name: "Enterprise onboarding", currentStageName: "Registration", status: "ACTIVE" },
  { id: "c-2", name: "EU expansion", currentStageName: "Legal Review", status: "ON_HOLD" },
];

describe("CaseSwitcher", () => {
  it("renders one chip per case plus a dashed new-case chip", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate onCreateNew={() => {}} />);

    expect(screen.getByRole("link", { name: /Enterprise onboarding/ })).not.toBeNull();
    expect(screen.getByRole("link", { name: /EU expansion/ })).not.toBeNull();
    expect(screen.getByRole("button", { name: /new case/i })).not.toBeNull();
  });

  it("navigates rather than filtering in place, so the URL carries the case", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate onCreateNew={() => {}} />);

    const link = screen.getByRole("link", { name: /EU expansion/ }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/t/acme/customers/cust-1/cases/c-2");
  });

  /** Q18: the switcher's primary label is the case's own name, not a stage-plus-id fake. */
  it("renders the case name rather than the current stage name", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate onCreateNew={() => {}} />);

    expect(screen.queryByText("Registration")).toBeNull();
    expect(screen.queryByText("Legal Review")).toBeNull();
  });

  it("hides the new-case chip without case.create", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate={false} onCreateNew={() => {}} />);
    expect(screen.queryByRole("button", { name: /new case/i })).toBeNull();
  });
});
