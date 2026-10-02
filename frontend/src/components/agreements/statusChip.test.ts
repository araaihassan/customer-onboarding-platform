import { describe, expect, it } from "vitest";
import { statusLabelKey, statusTone, toneRole } from "./statusChip";

describe("statusChip", () => {
  it("maps every display status to its tone", () => {
    expect(statusTone("DRAFT")).toBe("neutral");
    expect(statusTone("UNDER_REVIEW")).toBe("info");
    expect(statusTone("APPROVED")).toBe("info");
    expect(statusTone("SENT")).toBe("warn");
    expect(statusTone("AWAITING_SIGNATURE")).toBe("warn");
    expect(statusTone("SIGNED")).toBe("success");
    expect(statusTone("EXPIRED")).toBe("danger");
    expect(statusTone("CANCELLED")).toBe("neutral");
  });

  it("maps tones onto StatusPill roles and builds label keys", () => {
    expect(toneRole("success")).toBe("ok");
    expect(toneRole("danger")).toBe("risk");
    expect(toneRole("warn")).toBe("warn");
    expect(statusLabelKey("AWAITING_SIGNATURE")).toBe("agreements.status.AWAITING_SIGNATURE");
  });
});
