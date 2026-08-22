import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Switch } from "./Switch";

afterEach(cleanup);

describe("Switch", () => {
  it("is a button with role switch and aria-checked, not a styled div", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Auto-advance" />);

    const el = screen.getByRole("switch", { name: "Auto-advance" });
    expect(el.tagName).toBe("BUTTON");
    expect(el.getAttribute("aria-checked")).toBe("false");
  });

  it("reflects checked in aria-checked", () => {
    render(<Switch checked={true} onChange={vi.fn()} label="Auto-advance" />);
    expect(screen.getByRole("switch").getAttribute("aria-checked")).toBe("true");
  });

  it("calls onChange with the flipped value on click", () => {
    const onChange = vi.fn();
    render(<Switch checked={false} onChange={onChange} label="Auto-advance" />);

    fireEvent.click(screen.getByRole("switch"));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  /**
   * A switch with an accessible name but no visible text is a control a
   * sighted user cannot identify -- caught by actually looking at the
   * rendered Inspector, not by a unit test that only checked aria-label.
   */
  it("renders the label as real, visible text", () => {
    render(<Switch checked={false} onChange={vi.fn()} label="Auto-advance" />);
    expect(screen.getByText("Auto-advance")).not.toBeNull();
  });
});
