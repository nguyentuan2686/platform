import { describe, expect, it } from "vitest";
import { TOPICS, topicLabel } from "@/lib/topics";

describe("topics", () => {
  it("lists the six topics in prototype order", () => {
    expect(TOPICS.map((t) => t.code)).toEqual(["TinhDuyen", "SuNghiep", "TaiLoc", "SucKhoe", "GiaDao", "VanNam"]);
    expect(TOPICS[0]).toEqual({ code: "TinhDuyen", label: "Tình duyên", glyph: "緣", hint: "Cung Phu Thê, đào hoa, thời điểm gặp gỡ" });
  });

  it("labels a topic code", () => {
    expect(topicLabel("TaiLoc")).toBe("Tài lộc");
    expect(topicLabel(null)).toBe("");
    expect(topicLabel("Nope")).toBe("");
  });
});
