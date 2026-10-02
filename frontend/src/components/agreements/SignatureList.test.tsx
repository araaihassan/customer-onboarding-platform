import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { SignatureList } from "./SignatureList";

afterEach(cleanup);

describe("SignatureList", () => {
  it("the signature list shows signed and pending signatories with date and method", () => {
    render(
      <SignatureList
        signatories={[
          { id: "s1", kind: "CONTACT", displayName: "Dana Reyes", displayRole: "Client sponsor", sortOrder: 0, signed: true },
          { id: "s2", kind: "INTERNAL", displayName: "Omar Fadel", displayRole: "Account executive", sortOrder: 1, signed: false },
        ]}
        signatures={[{ id: "g1", signatoryId: "s1", signedOn: "2026-08-14", method: "MANUAL" }]}
      />,
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByText("Dana Reyes")).not.toBeNull();
    expect(within(items[0]!).getByText("Signed")).not.toBeNull();
    expect(within(items[0]!).getByText("14 Aug 2026 via Manual record")).not.toBeNull();
    expect(within(items[1]!).getByText("Omar Fadel")).not.toBeNull();
    expect(within(items[1]!).getByText("Pending")).not.toBeNull();
    expect(within(items[1]!).queryByText(/via/)).toBeNull();
  });

  it("shows a free-text method as written and a known method through t()", () => {
    render(
      <SignatureList
        signatories={[{ id: "s1", kind: "CONTACT", displayName: "Dana Reyes", displayRole: "Sponsor", sortOrder: 0, signed: true }]}
        signatures={[{ id: "g1", signatoryId: "s1", signedOn: "2026-08-14", method: "Wet ink" }]}
      />,
    );
    expect(screen.getByText("14 Aug 2026 via Wet ink")).not.toBeNull();
    expect(screen.queryByText(/MANUAL/)).toBeNull();
  });

  it("renders an empty state with no signatories", () => {
    render(<SignatureList signatories={[]} signatures={[]} />);
    expect(screen.getByText("No signatories on this agreement.")).not.toBeNull();
  });
});
