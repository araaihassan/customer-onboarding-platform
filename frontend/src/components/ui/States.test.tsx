import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ErrorState } from "./States";

describe("ErrorState", () => {
  it("announces the message via role=alert and renders a retry button", async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Couldn't load customers." onRetry={onRetry} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Couldn't load customers.");
    screen.getByRole("button", { name: /retry|try again/i }).click();
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
