import { describe, expect, it } from "vitest";
import { renewalNotice } from "@/lib/renewal";

// 20:00 in Vietnam: a pass ending 2 d 5 h later ends at 01:00, three Vietnam calendar days on.
const NOW = new Date("2026-11-24T13:00:00Z");
const H = 3600_000;
const at = (ms: number) => new Date(NOW.getTime() + ms).toISOString();
const month = (ends_at: string) => ({ kind: "MONTH" as const, ends_at, questions_left: 2 });

describe("renewalNotice", () => {
  it("counts Vietnam calendar days to the end (2 d 5 h → 3)", () => {
    const ends = at(53 * H);
    expect(renewalNotice(month(ends), NOW)).toEqual({ kind: "MONTH", daysLeft: 3, endsAt: ends });
  });

  it("is quiet for a pass ending in 4 days", () => {
    expect(renewalNotice(month(at(96 * H)), NOW)).toBeNull();
  });

  it("speaks at exactly 3 days", () => {
    expect(renewalNotice(month(at(72 * H)), NOW)?.daysLeft).toBe(3);
  });

  it("is quiet for a pass already ended (or ending now)", () => {
    expect(renewalNotice(month(at(-H)), NOW)).toBeNull();
    expect(renewalNotice(month(at(0)), NOW)).toBeNull();
  });

  it("says at least 1 day on the last day", () => {
    expect(renewalNotice(month(at(H)), NOW)?.daysLeft).toBe(1);
  });

  it("keeps the kind of a YEAR pass", () => {
    const n = renewalNotice({ kind: "YEAR", ends_at: at(24 * H), questions_left: null }, NOW);
    expect(n?.kind).toBe("YEAR");
  });

  it("is quiet without a pass or with an unreadable date", () => {
    expect(renewalNotice(null, NOW)).toBeNull();
    expect(renewalNotice(month("not a date"), NOW)).toBeNull();
  });
});
