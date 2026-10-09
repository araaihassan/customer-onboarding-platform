import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const setHeader = vi.fn();
vi.mock("@/components/shell/PageHeader", () => ({ useSetPageHeader: (...a: unknown[]) => setHeader(...a) }));
vi.mock("@/components/notifications/HorizonsCard", () => ({ HorizonsCard: () => <div>horizons-card</div> }));
vi.mock("@/components/notifications/TemplatesCard", () => ({ TemplatesCard: () => <div>templates-card</div> }));

const { default: NotificationsAdminPage } = await import("./page");

afterEach(cleanup);

describe("Administration > Notifications page", () => {
  it("sets the Notifications header and renders the horizons card then the templates card", () => {
    render(<NotificationsAdminPage />);
    expect(setHeader).toHaveBeenCalledWith("Notifications");
    const horizons = screen.getByText("horizons-card");
    const templates = screen.getByText("templates-card");
    expect(horizons.compareDocumentPosition(templates) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
