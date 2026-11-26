import { afterEach, describe, expect, it, vi } from "vitest";
import { isUuid, newUuid } from "@/lib/ids";

afterEach(() => vi.unstubAllGlobals());

describe("newUuid", () => {
  it("makes distinct v4 UUIDs", () => {
    const a = newUuid();
    expect(isUuid(a)).toBe(true);
    expect(a).not.toBe(newUuid());
  });

  it("falls back to getRandomValues where randomUUID is missing (plain-http pages)", () => {
    vi.stubGlobal("crypto", { getRandomValues: (b: Uint8Array) => b.fill(0xab) });
    const id = newUuid();
    expect(isUuid(id)).toBe(true);
    expect(id).toBe("abababab-abab-4bab-abab-abababababab");
  });
});
