import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { ExceptionCard } from "@/lib/api/sla";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: { userType: "INTERNAL" } }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { WarRoomCard } = await import("./WarRoomCard");

const card: ExceptionCard = {
  caseId: "case-1",
  caseName: "Acme onboarding",
  customerId: "cust-1",
  customerName: "Acme Foods",
  stageName: "Technical setup",
  ownerName: "Priya Shah",
  hasOpenRequests: true,
  clock: { state: "BREACHED", elapsedDays: 4, targetDays: 2, pauseEligible: true },
  escalations: [
    { route: "MANAGER", escalatedToName: "Sam Lee", latePersonName: "Priya Shah", escalatedAt: "2026-08-21T10:00:00Z", overdueDays: 1 },
  ],
};

function renderCard(c: ExceptionCard, cbs = {}) {
  return render(
    <WarRoomCard card={c} slug="acme" onReassign={vi.fn()} onForceComplete={vi.fn()} onRemind={vi.fn()} {...cbs} />,
  );
}

afterEach(() => {
  cleanup();
  permissions = {};
});

describe("WarRoomCard", () => {
  it("shows the case link, customer, stage, elapsed/target, chip, owner and newest note", () => {
    renderCard(card);
    expect(screen.getByRole("link", { name: "Acme onboarding" })).toHaveAttribute("href", "/t/acme/customers/cust-1/cases/case-1");
    expect(screen.getByText(/Acme Foods/)).toBeInTheDocument();
    expect(screen.getByText(/Technical setup/)).toBeInTheDocument();
    const elapsed = screen.getByText("4.0 / 2");
    expect(elapsed.style.fontFamily).toBe("var(--ob-font-family-data)");
    expect(screen.getByTestId("sla-chip")).toHaveTextContent("BREACHED 2.0d");
    expect(screen.getByText("Priya Shah")).toBeInTheDocument();
    expect(screen.getByTestId("war-room-note")).toHaveTextContent(
      "Escalated to Sam Lee (Priya Shah's manager) on 21 Aug (automatic, day 1 overdue)",
    );
  });

  it("is flat: no shadow", () => {
    renderCard(card);
    expect(screen.getByTestId("war-room-card").style.boxShadow).toBe("");
  });

  it("survives null names: no customer, stage or owner, Unassigned, no raw ids, plain-text case name", () => {
    renderCard({ ...card, customerId: undefined, customerName: undefined, stageName: undefined, ownerName: undefined, ownerUserId: "u-9", escalations: [] });
    expect(screen.queryByText(/Acme Foods|Technical setup/)).toBeNull();
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(screen.queryByText(/u-9|cust-1/)).toBeNull();
    expect(screen.queryByRole("link", { name: "Acme onboarding" })).toBeNull();
    expect(screen.getByText("Acme onboarding")).toBeInTheDocument();
  });

  it("shows the pause reason for a paused clock with no escalations", () => {
    renderCard({ ...card, escalations: [], clock: { state: "PAUSED", pausedDays: 3.1, pauseReason: "OPEN_DOCUMENT_REQUEST" } });
    expect(screen.getByTestId("war-room-note")).toHaveTextContent("Waiting on the customer's documents");
  });

  it("shows the ineligibility line when the clock cannot pause", () => {
    renderCard({ ...card, escalations: [], clock: { state: "RUNNING", dueToday: true, pauseEligible: false } });
    expect(screen.getByTestId("war-room-note")).toHaveTextContent("NOT ELIGIBLE FOR PAUSE");
  });

  it("offers Remind customer only with open requests, Reassign always", () => {
    renderCard({ ...card, hasOpenRequests: false });
    expect(screen.queryByRole("button", { name: "Remind customer" })).toBeNull();
    expect(screen.getByRole("button", { name: "Reassign" })).toBeInTheDocument();
    cleanup();
    renderCard(card);
    expect(screen.getByRole("button", { name: "Remind customer" })).toBeInTheDocument();
  });

  it("offers Force-complete only with the permission the case workspace checks", () => {
    renderCard(card);
    expect(screen.queryByRole("button", { name: "Force-complete" })).toBeNull();
    cleanup();
    permissions = { "milestone.force_complete": ["ALL"] };
    renderCard(card);
    expect(screen.getByRole("button", { name: "Force-complete" })).toBeInTheDocument();
  });

  it("calls each callback with the card", () => {
    permissions = { "milestone.force_complete": ["ALL"] };
    const onReassign = vi.fn();
    const onForceComplete = vi.fn();
    const onRemind = vi.fn();
    renderCard(card, { onReassign, onForceComplete, onRemind });
    fireEvent.click(screen.getByRole("button", { name: "Reassign" }));
    fireEvent.click(screen.getByRole("button", { name: "Force-complete" }));
    fireEvent.click(screen.getByRole("button", { name: "Remind customer" }));
    expect(onReassign).toHaveBeenCalledWith(card);
    expect(onForceComplete).toHaveBeenCalledWith(card);
    expect(onRemind).toHaveBeenCalledWith(card);
  });
});
