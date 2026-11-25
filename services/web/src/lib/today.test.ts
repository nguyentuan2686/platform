import { describe, expect, it } from "vitest";
import { todayInVietnam } from "@/lib/today";

describe("todayInVietnam", () => {
  it("uses Asia/Ho_Chi_Minh, not UTC", () => {
    expect(todayInVietnam(new Date("2026-11-24T18:00:00Z"))).toBe("2026-11-25");
    expect(todayInVietnam(new Date("2026-11-24T16:59:00Z"))).toBe("2026-11-24");
  });
});
