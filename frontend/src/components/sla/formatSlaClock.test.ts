import { describe, expect, it } from "vitest";
import type { SlaClock } from "@/lib/api/sla";
import { days, formatSlaClock } from "./formatSlaClock";

const rows: Array<[string, SlaClock, string, string]> = [
  ["breached", { state: "BREACHED", elapsedDays: 4.04, targetDays: 2 }, "BREACHED 2.0d", "risk"],
  ["paused", { state: "PAUSED", pausedDays: 3.14 }, "PAUSED 3.1d", "info"],
  ["met", { state: "MET" }, "MET", "ok"],
  ["due today", { state: "RUNNING", dueToday: true }, "DUE TODAY", "warn"],
  ["at risk", { state: "RUNNING", atRisk: true, remainingDays: 0.44 }, "0.4d LEFT", "warn"],
  ["running", { state: "RUNNING", remainingDays: 1.25 }, "1.2d LEFT", "neutral"],
  ["never rounds up", { state: "RUNNING", remainingDays: 0.96 }, "0.9d LEFT", "neutral"],
  ["paused wins over due today", { state: "PAUSED", pausedDays: 1, dueToday: true, atRisk: true }, "PAUSED 1.0d", "info"],
  ["breached never negative", { state: "BREACHED", elapsedDays: 1, targetDays: 2 }, "BREACHED 0.0d", "risk"],
  ["absent numbers do not NaN", { state: "RUNNING" }, "0.0d LEFT", "neutral"],
];

describe("formatSlaClock", () => {
  it.each(rows)("%s", (_name, clock, label, tone) => {
    expect(formatSlaClock(clock)).toEqual({ label, tone });
  });

  it("truncates float noise correctly", () => {
    expect(days(0.3 - 0.1 + 1.7)).toBe("1.9");
    expect(days(Number.NaN)).toBe("0.0");
  });
});
