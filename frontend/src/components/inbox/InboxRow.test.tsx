import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { InboxRow } from "./InboxRow";
import { TONE_ROLE, iconFor } from "./notificationVisuals";
import type { NotificationItem } from "@/lib/api/notifications";

afterEach(cleanup);

const item = (over: Partial<NotificationItem> = {}): NotificationItem => ({
  id: "n-1",
  type: "ESCALATION",
  title: "Kickoff is overdue",
  body: "Acme onboarding",
  linkPath: "/cases/c-1",
  tone: "RISK",
  read: false,
  createdAt: new Date(Date.now() - 12 * 60_000).toISOString(),
  ...over,
});

describe("InboxRow", () => {
  it("renders title, body and a mono relative time", () => {
    render(<InboxRow item={item()} onOpen={() => {}} />);
    expect(screen.getByText("Kickoff is overdue")).toBeInTheDocument();
    expect(screen.getByText("Acme onboarding")).toBeInTheDocument();
    const time = screen.getByText("12 MIN AGO");
    expect(time.style.font).toContain("var(--ob-font-family-data)");
  });

  it("marks unread rows with the surface background and an Unread label", () => {
    render(<InboxRow item={item()} onOpen={() => {}} />);
    const row = screen.getByRole("button", { name: /Unread: Kickoff is overdue/ });
    expect(row).toHaveAttribute("data-unread", "true");
    expect(row.style.background).toBe("var(--ob-surface)");
  });

  it("leaves read rows transparent and unlabelled as unread", () => {
    render(<InboxRow item={item({ read: true })} onOpen={() => {}} />);
    const row = screen.getByRole("button");
    expect(row).not.toHaveAttribute("data-unread", "true");
    expect(row.style.background).toBe("transparent");
    expect(row).not.toHaveAccessibleName(/Unread/);
  });

  it("gives the tile the tone's colours and its name in text", () => {
    render(<InboxRow item={item({ tone: "WARN" })} onOpen={() => {}} />);
    const tile = screen.getByLabelText("Warning");
    expect(tile.style.background).toBe("var(--ob-warn-bg)");
    expect(tile.style.color).toBe("var(--ob-warn-fg)");
  });

  it("calls onOpen with the item", () => {
    const onOpen = vi.fn();
    const i = item();
    render(<InboxRow item={i} onOpen={onOpen} />);
    fireEvent.click(screen.getByRole("button"));
    expect(onOpen).toHaveBeenCalledWith(i);
  });
});

describe("notificationVisuals", () => {
  it("maps each tone to its role", () => {
    expect(TONE_ROLE).toEqual({ RISK: "risk", WARN: "warn", OK: "ok", INFO: "info" });
  });
  it("has a dedicated icon for every type and a safe default", () => {
    const types = ["ESCALATION", "TASK_ASSIGNED", "TASK_OVERDUE", "NEW_CUSTOMER", "MILESTONE_COMPLETED", "STAGE_CHANGED",
      "DOCUMENT_REQUESTED", "DOCUMENT_UPLOADED", "DOCUMENT_DECIDED", "AGREEMENT_STATUS", "NEW_COMMENT",
      "WORKFLOW_PUBLISHED", "RISK_CHANGED", "DEADLINE_APPROACHING", "EXPIRY_RENEWAL"] as const;
    for (const ty of types) expect(typeof iconFor(ty)).toBe("function");
    expect(typeof iconFor(undefined)).toBe("function");
  });
});
