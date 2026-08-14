import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ user: { fullName: "Maria Kessler" } }) }));

const { default: DashboardPage } = await import("./page");
const { PageHeaderProvider } = await import("@/components/shell/PageHeader");

afterEach(cleanup);

function renderPage() {
  return render(
    <PageHeaderProvider>
      <DashboardPage />
    </PageHeaderProvider>,
  );
}

describe("DashboardPage", () => {
  /**
   * A placeholder that names what is coming reads as deliberate; a blank screen
   * reads as broken. This asserts the naming, which is the whole point of it.
   */
  it("names the release that fills the dashboard", () => {
    renderPage();
    expect(screen.getByText(/role-based dashboards/i)).not.toBeNull();
    expect(screen.getByText(/pipeline health/i)).not.toBeNull();
  });

  it("points the user at the part of the product that does exist", () => {
    renderPage();
    expect(screen.getByText(/customers/i)).not.toBeNull();
  });
});
