import { describe, expect, it } from "vitest";
import { formatDayMonth, formatRelative } from "./date";

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

describe("formatRelative", () => {
  const now = new Date("2026-10-09T12:00:00Z");
  const ago = (ms: number) => new Date(now.getTime() - ms).toISOString();
  const MIN = 60_000;
  const HOUR = 60 * MIN;
  it("buckets by age", () => {
    expect(formatRelative(ago(20_000), now)).toBe("JUST NOW");
    expect(formatRelative(ago(12 * MIN), now)).toBe("12 MIN AGO");
    expect(formatRelative(ago(2 * HOUR), now)).toBe("2 HOURS AGO");
    expect(formatRelative(ago(30 * HOUR), now)).toBe("YESTERDAY");
    expect(formatRelative(ago(3 * 24 * HOUR), now)).toBe("3 DAYS AGO");
  });
  it("falls back to the date after a week, and treats the future as just now", () => {
    expect(formatRelative("2026-09-01T10:00:00Z", now)).toBe("1 Sep 2026");
    expect(formatRelative(ago(-5 * MIN), now)).toBe("JUST NOW");
  });
});
