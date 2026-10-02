import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { LifecycleCard } from "./LifecycleCard";

afterEach(cleanup);

const summary = { draft: 6, underReview: 9, sent: 4, awaitingSignature: 7, signed: 52, expiringWithin30Days: 3 };

describe("LifecycleCard", () => {
  it("the lifecycle card shows six counts with their labels", () => {
    render(<LifecycleCard summary={summary} />);
    for (const [label, n] of ([
      ["Draft", "6"],
      ["Under review", "9"],
      ["Sent", "4"],
      ["Awaiting signature", "7"],
      ["Signed", "52"],
      ["Expiring ≤30d", "3"],
    ] as [string, string][])) {
      const cell = screen.getByText(label).closest("[data-testid^='lifecycle-']") as HTMLElement;
      expect(cell).not.toBeNull();
      expect(cell).toHaveTextContent(n);
    }
  });

  it("each count's bar width is min(100, n × 1.9)%", () => {
    render(<LifecycleCard summary={{ ...summary, signed: 60 }} />);
    expect(screen.getByTestId("lifecycle-bar-draft").style.width).toBe("11.4%");
    expect(screen.getByTestId("lifecycle-bar-sent").style.width).toBe("7.6%");
    expect(screen.getByTestId("lifecycle-bar-signed").style.width).toBe("100%");
  });

  it("the eyebrow says AGREEMENT LIFECYCLE · MANUAL SIGNING", () => {
    render(<LifecycleCard summary={summary} />);
    expect(screen.getByText("AGREEMENT LIFECYCLE · MANUAL SIGNING")).toBeInTheDocument();
  });

  it("treats missing counts as zero", () => {
    render(<LifecycleCard summary={{}} />);
    expect(screen.getByTestId("lifecycle-bar-draft").style.width).toBe("0%");
  });
});
