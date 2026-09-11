import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { MilestoneRow } from "../MilestoneRow";
import type { MilestoneRoadmap } from "@/lib/api/cases";

afterEach(cleanup);

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({
  useAuth: () => ({ permissions }),
}));

const base: MilestoneRoadmap = {
  id: "test-milestone",
  key: "m1",
  name: "Test Milestone",
  status: "PENDING",
  progressPercent: 0,
  ownerUserId: "user-1",
  portalVisible: true,
};

describe("MilestoneRow", () => {
  it("badges an internal-only milestone with a word, not only a colour", () => {
    render(<MilestoneRow caseId="case-1" milestone={{ ...base, portalVisible: false }} participants={[]} approvals={[]} />);
    expect(screen.getByText("Internal")).toBeInTheDocument();
  });

  it("adds no badge to a shared milestone", () => {
    render(<MilestoneRow caseId="case-1" milestone={{ ...base, portalVisible: true }} participants={[]} approvals={[]} />);
    expect(screen.queryByText("Internal")).not.toBeInTheDocument();
  });
});
