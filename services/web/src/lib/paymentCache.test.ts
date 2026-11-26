import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as cache from "@/lib/paymentCache";
import type { VietQrPayment } from "@/lib/payment";

const pay = (content: string): VietQrPayment => ({
  kind: "VIETQR",
  bank_code: "TEST BANK",
  account_number: "0000000000",
  account_name: "TEST BANK",
  amount: "29000.00",
  content,
  qr_image_url: "https://img.example/qr.png",
});

beforeEach(() => {
  cache.clearPaymentCache();
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-11-25T03:00:00Z"));
});
afterEach(() => vi.useRealTimers());

describe("paymentCache", () => {
  it("returns what was put, then nothing after 35 minutes", () => {
    cache.put("o1", pay("TV1"));
    expect(cache.get("o1")?.content).toBe("TV1");
    vi.advanceTimersByTime(35 * 60_000 - 1);
    expect(cache.get("o1")?.content).toBe("TV1");
    vi.advanceTimersByTime(1);
    expect(cache.get("o1")).toBeUndefined();
  });

  it("misses an unknown order", () => {
    expect(cache.get("nope")).toBeUndefined();
  });

  it("evicts the least recently used entry beyond 1000", () => {
    for (let i = 0; i < cache.MAX_ENTRIES; i++) cache.put(`o${i}`, pay(`TV${i}`));
    expect(cache.get("o0")?.content).toBe("TV0"); // o0 is now the most recent; o1 the oldest
    cache.put("new", pay("TVNEW"));
    expect(cache.get("o1")).toBeUndefined();
    expect(cache.get("o0")?.content).toBe("TV0");
    expect(cache.get("new")?.content).toBe("TVNEW");
    expect(cache.size()).toBe(cache.MAX_ENTRIES);
  });

  it("is one store per process, shared by separate module instances (action vs page layer)", async () => {
    cache.put("shared", pay("TVSHARED"));
    vi.resetModules();
    const other = await import("@/lib/paymentCache");
    expect(other).not.toBe(cache);
    expect(other.get("shared")?.content).toBe("TVSHARED");
  });

  it("replaces an entry put twice", () => {
    cache.put("o1", pay("A"));
    cache.put("o1", pay("B"));
    expect(cache.get("o1")?.content).toBe("B");
    expect(cache.size()).toBe(1);
  });
});
