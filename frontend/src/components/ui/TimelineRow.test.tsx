import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { TimelineRow } from "./TimelineRow";

afterEach(cleanup);

describe("TimelineRow", () => {
  it("renders as a list item, never an editable row", () => {
    render(
      <ul>
        <TimelineRow timestamp="09:41" summary="Case opened" />
      </ul>,
    );
    expect(screen.getByRole("listitem")).not.toBeNull();
  });

  it("shows the timestamp and summary", () => {
    render(
      <ul>
        <TimelineRow timestamp="09:41" summary="Entered stage Technical Setup" />
      </ul>,
    );
    expect(screen.getByText("09:41")).not.toBeNull();
    expect(screen.getByText("Entered stage Technical Setup")).not.toBeNull();
  });

  it("joins actor and meta with the mono separator when both are present", () => {
    render(
      <ul>
        <TimelineRow timestamp="09:41" summary="Milestone completed" actor="J. Rivera" meta="KYC Pack" />
      </ul>,
    );
    expect(screen.getByText("J. Rivera · KYC Pack")).not.toBeNull();
  });

  it("omits the meta line entirely when neither actor nor meta is given", () => {
    render(
      <ul>
        <TimelineRow timestamp="09:41" summary="Case opened" />
      </ul>,
    );
    expect(screen.getByText("Case opened").nextElementSibling).toBeNull();
  });
});
