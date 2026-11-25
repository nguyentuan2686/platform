import { describe, expect, it } from "vitest";
import { DEFAULT_NEXT, safeNext } from "@/lib/safeNext";

describe("safeNext", () => {
  it.each(["/vi/mua", "/vi/hoi", "/vi", "/vi/", "/vi/tai-khoan", "/vi/la-so/"])("accepts %s", (p) => {
    expect(safeNext(p)).toBe(p);
  });

  it.each([
    "//evil.com",
    "https://x",
    "/vi/../admin",
    "/vi\\x",
    "/vi/mua?x=1",
    "/vi/mua#x",
    "/vi//evil.com",
    "/vietnam",
    "/VI/mua",
    "/admin",
    "",
    " /vi",
  ])("rejects %j", (p) => {
    expect(safeNext(p)).toBe("/vi/la-so");
  });

  it("rejects null and undefined", () => {
    expect(safeNext(null)).toBe(DEFAULT_NEXT);
    expect(safeNext(undefined)).toBe(DEFAULT_NEXT);
    expect(DEFAULT_NEXT).toBe("/vi/la-so");
  });
});
