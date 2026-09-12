import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
// Registers toBeInTheDocument() etc. on vitest's expect -- imported locally,
// matching this suite's existing per-file convention (Sidebar.test.tsx et al.)
// rather than a global vitest-config change.
import "@testing-library/jest-dom/vitest";
import { AwaitingApprovalBanner } from "./AwaitingApprovalBanner";

afterEach(cleanup);

describe("AwaitingApprovalBanner", () => {
  it("tells a held journey WHY it is inert and offers the action", () => {
    render(<AwaitingApprovalBanner caseId="c1" hasOutstandingRevision={false} />);

    // A case that silently refuses every checkbox is the worst thing this
    // sub-project could ship.
    expect(screen.getByText(/waiting for the customer to approve the schedule/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Issue a schedule revision" })).toBeInTheDocument();
  });

  it("offers a different action once a revision is already outstanding, not a duplicate issue button", () => {
    render(<AwaitingApprovalBanner caseId="c1" hasOutstandingRevision={true} />);

    expect(screen.getByText(/waiting for the customer to approve the schedule/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Issue a schedule revision" })).toBeNull();
    expect(screen.getByRole("button", { name: "Review the schedule revision" })).toBeInTheDocument();
  });

  it("calls the supplied action when its button is clicked", () => {
    const onAction = vi.fn();
    render(<AwaitingApprovalBanner caseId="c1" hasOutstandingRevision={false} onAction={onAction} />);

    fireEvent.click(screen.getByRole("button", { name: "Issue a schedule revision" }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });
});
