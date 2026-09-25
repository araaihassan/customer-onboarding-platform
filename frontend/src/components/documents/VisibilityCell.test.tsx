import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { VisibilityCell } from "./VisibilityCell";

afterEach(cleanup);

describe("VisibilityCell", () => {
  it("stacks a Chip (StatusPill) over a mono scope label for COMPANY_SHARED", () => {
    const { container } = render(<VisibilityCell tier="COMPANY_SHARED" />);
    // The word must actually be present in the rendered text, not just a
    // coloured element -- CLAUDE.md's "colour is never the only signal."
    expect(container.textContent).toMatch(/company shared/i);
    expect(container.textContent).toMatch(/COMPANY-SHARED/);
  });

  it("stacks a Chip over a mono scope label for CONTACT_ONLY", () => {
    const { container } = render(<VisibilityCell tier="CONTACT_ONLY" />);
    expect(container.textContent).toMatch(/contact only/i);
    expect(container.textContent).toMatch(/CONTACT ONLY/);
  });

  it("stacks a Chip over a mono scope label for SENSITIVE", () => {
    const { container } = render(<VisibilityCell tier="SENSITIVE" />);
    expect(container.textContent).toMatch(/sensitive/i);
    expect(container.textContent).toMatch(/SENSITIVE/);
  });

  /**
   * Every one of the three tiers gets its own distinct semantic colour
   * (Ruling 2): neutral / warn / risk. A real assertion against the rendered
   * background token, not just presence of some colour.
   */
  it("gives each of the three tiers its own semantic colour, never colour alone", () => {
    const neutral = render(<VisibilityCell tier="COMPANY_SHARED" />).container.querySelector("span");
    const warn = render(<VisibilityCell tier="CONTACT_ONLY" />).container.querySelector("span");
    const risk = render(<VisibilityCell tier="SENSITIVE" />).container.querySelector("span");

    expect(neutral?.getAttribute("style")).toContain("--ob-neutral-bg");
    expect(warn?.getAttribute("style")).toContain("--ob-warn-bg");
    expect(risk?.getAttribute("style")).toContain("--ob-risk-bg");

    // And every one of them still carries the word -- colour is never the
    // only signal, for any of the three, not just the risk-coloured one.
    expect(neutral?.textContent).toMatch(/company shared/i);
    expect(warn?.textContent).toMatch(/contact only/i);
    expect(risk?.textContent).toMatch(/sensitive/i);
  });

  it("renders a neutral em dash when no tier is known, never a guessed one", () => {
    const { container } = render(<VisibilityCell tier={undefined} />);
    expect(container.textContent).toContain("—");
  });
});
