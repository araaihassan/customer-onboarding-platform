import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { CaseHeader } from "./CaseHeader";
import type { Case } from "@/lib/api/cases";
import type { Customer } from "@/lib/api/customers";

afterEach(cleanup);

const caseData: Case = {
  id: "01a0000e-0000-7000-8000-000000000001",
  customerId: "cust-1",
  versionNo: 4,
  status: "ACTIVE",
  currentStageName: "Legal Review",
  progressPercent: 42,
  targetCompletionDate: "2026-09-30",
  startedAt: "2026-08-01T00:00:00Z",
  totalHoldDays: 0,
};

const customer: Customer = {
  id: "cust-1",
  displayName: "Acme Corp",
  legalName: "Acme Corporation Ltd",
  status: "ACTIVE",
};

describe("CaseHeader", () => {
  it("composes case facts with the customer name from the existing customer query", () => {
    render(<CaseHeader caseData={caseData} customer={customer} />);
    expect(screen.getByText("Acme Corp")).not.toBeNull();
    expect(screen.getByText("Legal Review")).not.toBeNull();
  });

  it("renders machine values in mono and human text in Archivo", () => {
    render(<CaseHeader caseData={caseData} customer={customer} />);

    const stageValue = screen.getByText("Legal Review");
    expect(stageValue.style.fontFamily || stageValue.style.font).not.toContain("var(--ob-font-family-data)");

    const startedValue = screen.getByText("2026-08-01");
    expect(startedValue.style.font).toContain("var(--ob-font-family-data)");
  });

  it("shows the frozen version as 'workflow v4 (frozen)'", () => {
    render(<CaseHeader caseData={caseData} customer={customer} />);
    expect(screen.getByText(/workflow v4 \(frozen\)/)).not.toBeNull();
  });

  it("reflows the five fact columns to two rows below 1280px", () => {
    render(<CaseHeader caseData={caseData} customer={customer} />);
    const grid = screen.getByTestId("case-fact-grid");
    expect(grid.className).toContain("grid-cols-3");
    expect(grid.className).toContain("xl:grid-cols-5");
  });
});
