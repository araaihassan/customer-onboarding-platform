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
});
