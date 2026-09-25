import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { HiddenCountLine } from "./HiddenCountLine";

afterEach(cleanup);

describe("HiddenCountLine", () => {
  it("renders the line when rows are hidden, zero-padded per SCREENS.md's own example", () => {
    render(<HiddenCountLine visible={8} hidden={61} />);
    expect(screen.getByText("08 VISIBLE · 61 HIDDEN BY SCOPE")).not.toBeNull();
  });

  it("Step 1: renders '00 HIDDEN BY SCOPE' rather than disappearing when nothing is hidden", () => {
    render(<HiddenCountLine visible={12} hidden={0} />);
    // The line must still be present -- a scoped view that hides nothing must
    // not read as visually identical to an unscoped one.
    expect(screen.getByText("12 VISIBLE · 00 HIDDEN BY SCOPE")).not.toBeNull();
  });

  it("zero-pads a single-digit count to two digits", () => {
    render(<HiddenCountLine visible={3} hidden={5} />);
    expect(screen.getByText("03 VISIBLE · 05 HIDDEN BY SCOPE")).not.toBeNull();
  });

  it("does not pad a count already two digits or wider", () => {
    render(<HiddenCountLine visible={123} hidden={4567} />);
    expect(screen.getByText("123 VISIBLE · 4567 HIDDEN BY SCOPE")).not.toBeNull();
  });

  it("never renders a negative count even if handed one", () => {
    // A defensive clamp mirroring the backend's own Math.max(0, ...) --
    // this component renders whatever it is handed, so it must not surface
    // "-1" on screen even from a caller that (incorrectly) passed one.
    render(<HiddenCountLine visible={5} hidden={-1} />);
    expect(screen.getByText("05 VISIBLE · 00 HIDDEN BY SCOPE")).not.toBeNull();
  });

  it("renders in the mono/data font, never the human-text font", () => {
    render(<HiddenCountLine visible={1} hidden={2} />);
    const el = screen.getByText("01 VISIBLE · 02 HIDDEN BY SCOPE");
    expect(el.style.font).toContain("var(--ob-font-family-data)");
  });
});
