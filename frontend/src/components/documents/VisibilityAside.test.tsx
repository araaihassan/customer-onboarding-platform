import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { VisibilityAside } from "./VisibilityAside";

afterEach(cleanup);

describe("VisibilityAside", () => {
  /**
   * `SCREENS.md` §7's own aside content is always present -- this component
   * has no hide/reveal interaction, so all three tiers' one-sentence
   * explanations (`DOMAIN_RULES.md` Q9) render regardless of which one is
   * emphasised.
   */
  it("always shows all three tiers' explanations, whichever one is emphasised", () => {
    const { container } = render(<VisibilityAside emphasize="SENSITIVE" />);
    expect(container.textContent).toMatch(/company shared/i);
    expect(container.textContent).toMatch(/contact only/i);
    expect(container.textContent).toMatch(/sensitive/i);
    // Q9's own defining sentence for CONTACT_ONLY.
    expect(container.textContent).toMatch(/other contacts at the same company cannot see it/i);
  });

  it("still shows all three explanations with no tier emphasised at all", () => {
    const { container } = render(<VisibilityAside />);
    expect(container.textContent).toMatch(/company shared/i);
    expect(container.textContent).toMatch(/contact only/i);
    expect(container.textContent).toMatch(/sensitive/i);
    expect(container.querySelector("[data-emphasized]")).toBeNull();
  });

  /**
   * The brief's own Step 1 test: a SENSITIVE selection must surface the
   * explanation, not silently restrict anything -- proven here via a real
   * attribute/style difference, not merely that the word "sensitive" is
   * present (it always is, per the test above).
   */
  it("marks only the emphasised tier's row distinctly from the other two", () => {
    const { container } = render(<VisibilityAside emphasize="SENSITIVE" />);
    const sensitive = container.querySelector('[data-tier="SENSITIVE"]');
    const companyShared = container.querySelector('[data-tier="COMPANY_SHARED"]');
    const contactOnly = container.querySelector('[data-tier="CONTACT_ONLY"]');

    expect(sensitive?.getAttribute("data-emphasized")).toBe("true");
    expect(companyShared?.getAttribute("data-emphasized")).toBeNull();
    expect(contactOnly?.getAttribute("data-emphasized")).toBeNull();

    // A real style difference backs the attribute -- not decoration-only.
    expect(sensitive?.getAttribute("style")).not.toEqual(companyShared?.getAttribute("style"));
  });

  it("emphasises a different row when a different tier is selected", () => {
    const { container } = render(<VisibilityAside emphasize="COMPANY_SHARED" />);
    expect(container.querySelector('[data-tier="COMPANY_SHARED"]')?.getAttribute("data-emphasized")).toBe("true");
    expect(container.querySelector('[data-tier="SENSITIVE"]')?.getAttribute("data-emphasized")).toBeNull();
  });
});
