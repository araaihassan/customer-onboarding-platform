import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CaseSwitcher } from "./CaseSwitcher";
import type { Case } from "@/lib/api/cases";

afterEach(cleanup);

const cases: Case[] = [
  { id: "c-1", currentStageName: "Registration", status: "ACTIVE" },
  { id: "c-2", currentStageName: "Legal Review", status: "ON_HOLD" },
];

describe("CaseSwitcher", () => {
  it("renders one chip per case plus a dashed new-case chip", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate onCreateNew={() => {}} />);

    expect(screen.getByRole("link", { name: /Registration/ })).not.toBeNull();
    expect(screen.getByRole("link", { name: /Legal Review/ })).not.toBeNull();
    expect(screen.getByRole("button", { name: /new case/i })).not.toBeNull();
  });

  it("navigates rather than filtering in place, so the URL carries the case", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate onCreateNew={() => {}} />);

    const link = screen.getByRole("link", { name: /Legal Review/ }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/t/acme/customers/cust-1/cases/c-2");
  });

  it("hides the new-case chip without case.create", () => {
    render(<CaseSwitcher cases={cases} activeCaseId="c-1" slug="acme" customerId="cust-1" canCreate={false} onCreateNew={() => {}} />);
    expect(screen.queryByRole("button", { name: /new case/i })).toBeNull();
  });
});
