import { describe, expect, it } from "vitest";
import type { EscalationNote } from "@/lib/api/sla";
import { formatEscalationNote } from "./escalationNote";

const base: EscalationNote = {
  route: "MANAGER",
  escalatedToName: "Sam Lee",
  latePersonName: "Priya Shah",
  dueDate: "2026-08-20",
  escalatedAt: "2026-08-21T23:30:00Z",
  overdueDays: 1,
};

describe("formatEscalationNote", () => {
  it("words a manager escalation with the late person's possessive", () => {
    expect(formatEscalationNote(base)).toBe(
      "Escalated to Sam Lee (Priya Shah's manager) on 21 Aug (automatic, day 1 overdue)",
    );
  });
  it("words a department-head escalation", () => {
    expect(formatEscalationNote({ ...base, route: "DEPARTMENT_HEAD" })).toBe(
      "Escalated to Sam Lee (department head) on 21 Aug (automatic, day 1 overdue)",
    );
  });
  it("words an administrators escalation without a name", () => {
    expect(formatEscalationNote({ ...base, route: "ADMINISTRATORS", escalatedToName: undefined })).toBe(
      "Escalated to administrators on 21 Aug (automatic, day 1 overdue)",
    );
  });
  it("drops the possessive clause when the late person is unknown", () => {
    expect(formatEscalationNote({ ...base, latePersonName: undefined })).toBe(
      "Escalated to Sam Lee on 21 Aug (automatic, day 1 overdue)",
    );
  });
  it("writes September as Sep, whatever the ICU version", () => {
    expect(formatEscalationNote({ ...base, escalatedAt: "2026-09-21T10:00:00Z" })).toContain("on 21 Sep (");
  });
  it("reads the date in UTC so it never shifts", () => {
    expect(formatEscalationNote({ ...base, escalatedAt: "2026-08-21T00:00:00Z" })).toContain("on 21 Aug");
  });
});
