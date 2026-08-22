import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StageGroupHeader } from "./StageGroupHeader";

describe("StageGroupHeader", () => {
  it("renders the stage name as a heading", () => {
    render(<StageGroupHeader name="Registration" />);
    expect(screen.getByRole("heading", { name: "Registration" })).not.toBeNull();
  });
});
