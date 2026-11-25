import { describe, expect, it } from "vitest";
import { branchLabel, hourLabel, levelLabel, ZONES } from "@/lib/labels";

describe("labels", () => {
  it("labels levels", () => {
    expect(levelLabel("Thuan")).toBe("Thuận");
    expect(levelLabel("Binh")).toBe("Bình");
    expect(levelLabel("LuuY")).toBe("Lưu ý");
  });
  it("labels branches and hours", () => {
    expect(branchLabel("Zi")).toBe("Tý");
    expect(branchLabel("Hai")).toBe("Hợi");
    expect(hourLabel("Chen")).toBe("giờ Thìn");
  });
  it("offers 14 zones with Việt Nam first", () => {
    expect(ZONES).toHaveLength(14);
    expect(ZONES[0]).toEqual({ value: "Asia/Ho_Chi_Minh", label: "Việt Nam" });
  });
});
