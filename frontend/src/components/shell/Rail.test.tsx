import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Rail } from "./Rail";

vi.mock("@/lib/auth/useAuth", () => ({
  useAuth: () => ({
    user: { fullName: "Jordan Diaz", email: "jordan@acme.test" },
    logout: vi.fn(),
  }),
}));

afterEach(cleanup);

describe("Rail", () => {
  it("renders the brand mark and the account trigger, and opens the account popover on click", () => {
    render(<Rail />);
    const trigger = screen.getByRole("button", { name: /jordan diaz/i });
    expect(trigger).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.getByText("Jordan Diaz")).toBeInTheDocument();
    expect(screen.getByText("jordan@acme.test")).toBeInTheDocument();
  });

  it("does not render a sidebar-toggle button when onToggleSidebar is omitted", () => {
    render(<Rail />);
    expect(screen.queryByRole("button", { name: /toggle navigation/i })).not.toBeInTheDocument();
  });

  it("renders and calls onToggleSidebar when provided", () => {
    const onToggle = vi.fn();
    render(<Rail onToggleSidebar={onToggle} />);
    fireEvent.click(screen.getByRole("button", { name: /toggle navigation/i }));
    expect(onToggle).toHaveBeenCalledOnce();
  });
});
