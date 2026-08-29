import { readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Structural guard over the design-token layer, the frontend counterpart to the
 * backend's `architecture/` tests.
 *
 * A CSS custom property that does not resolve fails silently: the browser drops
 * the whole declaration, so a misspelled or retired token name leaves the element
 * with no padding, no colour, no radius at all -- and neither `vitest` nor
 * `next build` says a word. The same is true of a Tailwind utility with no
 * `@theme` entry behind it: the class simply generates no CSS.
 *
 * That exact bug shipped four times during the 2026-08 visual refactor
 * (`Checkbox`, `ProgressBar`, `Switch`, `Card`), each time in a file whose own
 * task had already been reviewed clean -- because each review verified only the
 * lines its task touched. This test derives the check instead of enumerating it,
 * which is CLAUDE.md's own "prefer a derivable list to a typed one": it grows
 * with the code rather than being widened by hand after each miss.
 */

// vitest runs with `frontend/` as the working directory (its config lives there),
// and `import.meta.url` is not a file:// URL once the file is transformed.
const SRC = join(process.cwd(), "src");
const TOKENS = join(SRC, "app/tokens.css");
const THEME = join(SRC, "app/tailwind-theme.css");

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) sourceFiles(path, out);
    else if ([".ts", ".tsx", ".css"].includes(extname(path))) out.push(path);
  }
  return out;
}

/** Every `--name:` declared in a file. Several may share one line. */
function declared(file: string, prefix: string): Set<string> {
  const css = readFileSync(file, "utf8");
  const pattern = new RegExp(`--${prefix}-([\\w-]+)\\s*:`, "g");
  return new Set([...css.matchAll(pattern)].map((m) => m[1]!));
}

const at = (file: string, line: number) => `${relative(SRC, file).replace(/\\/g, "/")}:${line}`;

/**
 * One reviewed exclusion, in the spirit of `RlsCoverageTest`'s: adding a second
 * is a deliberate act, not a way to green a build.
 *
 * `app/page.tsx` is untouched `create-next-app` output -- the Next.js logo, a
 * "Deploy now" button pointing at vercel.com, `dark:` variants that outlived the
 * dark theme's removal. It uses `bg-foreground`/`text-background`, which have no
 * `@theme` entry and so generate nothing. Styling them correctly would polish a
 * page that should not ship at all; what `/` ought to be is a product decision
 * nobody has made. **Delete this exclusion the moment that route gets a real
 * page.**
 */
const EXCLUDED = new Set(["app/page.tsx"]);

const files = sourceFiles(SRC).filter(
  (f) => !EXCLUDED.has(relative(SRC, f).replace(/\\/g, "/")),
);

describe("design tokens", () => {
  it("resolves every var(--ob-*) against tokens.css", () => {
    const defined = declared(TOKENS, "ob");
    const unresolved: string[] = [];

    for (const file of files) {
      if (file === TOKENS) continue;
      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, i) => {
          // Only real usages. A bare `--ob-foo` inside prose or a comment is not
          // a reference, and flagging one would punish the comments that explain
          // which token replaced which.
          for (const match of line.matchAll(/var\(\s*(--ob-([\w-]+))/g)) {
            if (!defined.has(match[2]!)) unresolved.push(`${at(file, i + 1)}  ${match[1]}`);
          }
        });
    }

    expect(unresolved).toEqual([]);
  });

  it("resolves every theme-backed utility class against tailwind-theme.css", () => {
    const colors = declared(THEME, "color");
    const radii = declared(THEME, "radius");
    const spacing = declared(THEME, "spacing");

    /**
     * Tailwind's own vocabulary for these prefixes, which resolves without any
     * `@theme` entry of ours.
     *
     * This list is the one enumeration here, and it is deliberate. `text-` is
     * badly overloaded -- it sets colour, font-size, alignment, overflow and
     * wrapping -- so without it every `text-left` reads as a missing colour.
     * `border-` is the same story for sides, widths and styles.
     *
     * It is safe to be incomplete. A Tailwind name missing from this list
     * produces a *false positive*: the test fails loudly and someone adds the
     * name. The dangerous direction -- a genuinely broken class slipping
     * through -- needs that class to collide with a built-in name, which the
     * failures this guard exists to catch (`text-faint` for `text-text-faint`)
     * do not.
     */
    const BUILT_IN = new Set([
      // shared
      "transparent", "current", "inherit", "black", "white", "auto", "none", "px",
      // font-size
      "xs", "sm", "base", "lg", "xl", "2xl", "3xl", "4xl", "5xl", "6xl", "7xl", "8xl", "9xl",
      // text-align / overflow / wrap
      "left", "center", "right", "justify", "start", "end",
      "ellipsis", "clip", "wrap", "nowrap", "balance", "pretty",
      // border sides and widths (`border-t`, `border-t-0`, `border-2`)
      "t", "r", "b", "l", "x", "y", "s", "e", "0", "2", "4", "8",
      // border styles
      "solid", "dashed", "dotted", "double", "hidden", "collapse", "separate",
      // background attachment / clip / origin / repeat / size / position
      "fixed", "local", "scroll", "cover", "contain", "repeat", "no-repeat", "bottom", "top",
      // radius
      "full", "md",
    ]);

    const unresolved: string[] = [];

    const check = (name: string, set: Set<string>, cls: string, file: string, line: number) => {
      // Arbitrary values (`bg-[#fff]`), numeric scale steps, and opacity
      // modifiers (`bg-black/[.05]`) are Tailwind's own, not ours to resolve.
      if (BUILT_IN.has(name) || name.startsWith("[") || /^\d/.test(name) || name.includes("/")) return;
      if (!set.has(name)) unresolved.push(`${at(file, line)}  ${cls}`);
    };

    for (const file of files) {
      if (extname(file) !== ".tsx") continue;
      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, i) => {
          for (const match of line.matchAll(/className\s*=\s*(?:"([^"]*)"|\{`([^`]*)`\})/g)) {
            for (const token of (match[1] ?? match[2] ?? "").split(/\s+/)) {
              // Strip variants: `hover:`, `xl:`, `min-[1024px]:`, `dark:`.
              const cls = token.replace(/^(?:[\w-]+|min-\[[^\]]+\]|max-\[[^\]]+\]):/g, "");
              let m: RegExpMatchArray | null;
              if ((m = cls.match(/^(?:text|bg|border|ring|fill|stroke|outline|divide)-(.+)$/))) {
                // `border-t-0` is a side plus a width, not a colour named "t-0".
                check(m[1]!.replace(/^[trblxyse]-(?=\d)/, ""), colors, cls, file, i + 1);
              } else if ((m = cls.match(/^rounded(?:-[trbl]{1,2})?-(.+)$/))) {
                check(m[1]!, radii, cls, file, i + 1);
              } else if ((m = cls.match(/^(?:p|m|gap|space)[trblxy]?-(.+)$/))) {
                check(m[1]!, spacing, cls, file, i + 1);
              }
            }
          }
        });
    }

    expect([...new Set(unresolved)]).toEqual([]);
  });
});
