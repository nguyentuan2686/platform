import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BirthStore } from "@/lib/birthStore";
import type { Birth } from "@/lib/types";

const b = (n = 0): Birth => ({ birth: `1994-03-12T08:${String(n % 60).padStart(2, "0")}`, zone: "Asia/Ho_Chi_Minh", gender: "male", calendar: "VN" });

describe("BirthStore", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("round-trips and issues unguessable ids", () => {
    const s = new BirthStore();
    const id = s.put(b());
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    expect(s.get(id)).toEqual(b());
    expect(s.put(b())).not.toBe(id);
    expect(s.get("nope")).toBeUndefined();
  });

  it("expires after 2 hours", () => {
    const s = new BirthStore();
    const id = s.put(b());
    vi.advanceTimersByTime(2 * 60 * 60 * 1000 - 1);
    expect(s.get(id)).toBeDefined();
    vi.advanceTimersByTime(2);
    expect(s.get(id)).toBeUndefined();
  });

  it("evicts the oldest entry past 1000", () => {
    const s = new BirthStore();
    const first = s.put(b(1));
    const ids = Array.from({ length: 1000 }, (_, i) => s.put(b(i)));
    expect(s.get(first)).toBeUndefined();
    expect(s.get(ids[0])).toBeDefined();
  });

  it("a read keeps an entry fresh in LRU order", () => {
    const s = new BirthStore(1000, 2);
    const a = s.put(b(1));
    const c = s.put(b(2));
    s.get(a);
    s.put(b(3));
    expect(s.get(a)).toBeDefined();
    expect(s.get(c)).toBeUndefined();
  });
});
