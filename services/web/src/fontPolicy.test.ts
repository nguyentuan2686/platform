import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Hard check (lesson docs/lessons/2026-11-25-registry-and-font-fetch-flakes.md): `next/font/google`
 * downloads fonts during `next build` and failed CI twice on a Google Fonts outage. Fonts are
 * self-hosted (src/fonts + src/styles/fonts.css), so no source file may import it.
 */
const SRC = __dirname;

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const p = join(dir, d.name);
    if (d.isDirectory()) return sources(p);
    return /\.(tsx?|jsx?|mjs|css)$/.test(d.name) && d.name !== "fontPolicy.test.ts" ? [p] : [];
  });
}

const FORBIDDEN = /next\/font\/google|fonts\.(googleapis|gstatic)\.com/;

describe("font policy", () => {
  it("matches the forbidden patterns", () => {
    expect(FORBIDDEN.test('import { Charm } from "next/font/google";')).toBe(true);
    expect(FORBIDDEN.test("@import url(https://fonts.googleapis.com/css2?family=X);")).toBe(true);
    expect(FORBIDDEN.test('import localFont from "next/font/local";')).toBe(false);
  });

  it("has no next/font/google or Google Fonts URL in src/**", () => {
    const offenders = sources(SRC).filter((f) => FORBIDDEN.test(readFileSync(f, "utf8"))).map((f) => relative(SRC, f));
    expect(offenders).toEqual([]);
  });
});
