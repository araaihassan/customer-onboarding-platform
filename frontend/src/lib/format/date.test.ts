import { describe, expect, it } from "vitest";
import { formatDayMonth } from "./date";

describe("formatDayMonth", () => {
  it("reads a date-only value and a timestamp in UTC", () => {
    expect(formatDayMonth("2026-09-21")).toBe("21 Sep");
    expect(formatDayMonth("2026-10-03T23:30:00Z")).toBe("3 Oct");
  });
  it("is empty for a missing or unparseable value", () => {
    expect(formatDayMonth(undefined)).toBe("");
    expect(formatDayMonth("nonsense")).toBe("");
  });
});
