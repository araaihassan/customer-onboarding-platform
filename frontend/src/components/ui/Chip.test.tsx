import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Chip } from "./Chip";

afterEach(cleanup);

describe("Chip", () => {
  it("is a real button carrying aria-pressed for its active state", () => {
    render(<Chip active={false} onClick={vi.fn()}>90 days</Chip>);
    const chip = screen.getByRole("button", { name: "90 days" });
    expect(chip.getAttribute("aria-pressed")).toBe("false");

    cleanup();
    render(<Chip active={true} onClick={vi.fn()}>90 days</Chip>);
    expect(screen.getByRole("button").getAttribute("aria-pressed")).toBe("true");
  });

  it("calls onClick when activated", () => {
    const onClick = vi.fn();
    render(<Chip active={false} onClick={onClick}>All</Chip>);

    fireEvent.click(screen.getByRole("button"));

    expect(onClick).toHaveBeenCalled();
  });

  it("renders a dot and a mono trailing id, e.g. the case chip", () => {
    render(
      <Chip active dot={<span data-testid="dot" />} mono="CASE-0142">
        Acme Corp
      </Chip>,
    );

    expect(screen.getByTestId("dot")).not.toBeNull();
    expect(screen.getByText("CASE-0142")).not.toBeNull();
    expect(screen.getByText("Acme Corp")).not.toBeNull();
  });

  it("uses the standard mono chip sizing", () => {
    render(<Chip>COMPLETE</Chip>);
    const el = screen.getByRole("button");
    expect(el.style.font).toContain("var(--ob-type-mono-chip-size)");
    expect(el.style.borderRadius).toBe("var(--ob-radius-5)");
  });
});
