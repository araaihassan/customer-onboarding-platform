import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const setHeader = vi.fn();
vi.mock("@/components/shell/PageHeader", () => ({ useSetPageHeader: (...a: unknown[]) => setHeader(...a) }));
vi.mock("@/components/notifications/HorizonsCard", () => ({ HorizonsCard: () => <div>horizons-card</div> }));

const { default: NotificationsAdminPage } = await import("./page");

afterEach(cleanup);

describe("Administration > Notifications page", () => {
  it("sets the Notifications header and renders the horizons card", () => {
    render(<NotificationsAdminPage />);
    expect(setHeader).toHaveBeenCalledWith("Notifications");
    expect(screen.getByText("horizons-card")).toBeTruthy();
  });
});
