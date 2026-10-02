import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { VersionHistory, shortHash } from "./VersionHistory";

afterEach(cleanup);

const FULL = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

describe("VersionHistory", () => {
  it("version history shows each version's short hash in mono and its review outcome", () => {
    render(
      <VersionHistory
        versions={[
          { id: "v1", versionNumber: 1, contentSha256: FULL, reviewDecision: "REJECT", reviewReason: "Clause 4 is wrong", submittedAt: "2026-08-10T09:00:00Z" },
          { id: "v2", versionNumber: 2, contentSha256: "ffffffffffffffffffff", reviewDecision: "APPROVE" },
          { id: "v3", versionNumber: 3, contentSha256: "0123456789abcdef0123" },
        ]}
      />,
    );
    const hash = screen.getByText("a1b2c3d4e5f6");
    expect(hash.getAttribute("title")).toBe(FULL);
    expect(hash.style.fontFamily).toContain("--ob-font-family-data");
    expect(screen.getByText("Rejected")).not.toBeNull();
    expect(screen.getByText("Clause 4 is wrong")).not.toBeNull();
    expect(screen.getByText("Approved")).not.toBeNull();
    expect(screen.getByText("Awaiting review")).not.toBeNull();
    expect(screen.getByText("v2")).not.toBeNull();
    expect(screen.getByText("submitted 10 Aug 2026")).not.toBeNull();
  });

  it("shortHash is the first 12 hex characters", () => {
    expect(shortHash(FULL)).toBe("a1b2c3d4e5f6");
    expect(shortHash(undefined)).toBe("—");
  });

  it("renders an empty state when there are no versions", () => {
    render(<VersionHistory versions={[]} />);
    expect(screen.getByText(/No versions yet/)).not.toBeNull();
  });
});
