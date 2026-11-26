import { afterEach, describe, expect, it, vi } from "vitest";
import { facebookShareUrl, shareUrl, summaryLine } from "@/lib/shareText";
import type { BasicReading } from "@/lib/types";

const reading = (sections: BasicReading["sections"]): BasicReading => ({
  character: "LaoThienCo",
  school: "tuvi",
  chartHash: "h",
  knowledgeVersion: 1,
  sections,
});

describe("summaryLine", () => {
  it("is the first sentence of the first line of the menh section", () => {
    const r = reading([
      { id: "greeting", lines: ["Chào con."] },
      { id: "menh", lines: ["Mệnh con vững. Sao Tử Vi chiếu vào! Còn nữa?", "Dòng hai."] },
    ]);
    expect(summaryLine(r)).toBe("Mệnh con vững.");
  });

  it("cuts a long sentence on a word boundary with an ellipsis (at most 121 chars)", () => {
    const long = Array.from({ length: 40 }, (_, i) => `chữ${i}`).join(" "); // > 200 chars, no stop
    const out = summaryLine(reading([{ id: "menh", lines: [long] }]));
    expect(out.length).toBeLessThanOrEqual(121);
    expect(out.endsWith("…")).toBe(true);
    expect(long.startsWith(out.slice(0, -1))).toBe(true);
    expect(long[out.length - 1]).toBe(" "); // cut at a word end, not mid-word
  });

  it("keeps a sentence of exactly 120 chars whole", () => {
    const s = "a".repeat(119) + ".";
    expect(summaryLine(reading([{ id: "menh", lines: [s] }]))).toBe(s);
  });

  it("is empty without a menh section or without lines", () => {
    expect(summaryLine(reading([{ id: "greeting", lines: ["Chào."] }]))).toBe("");
    expect(summaryLine(reading([{ id: "menh", lines: [] }]))).toBe("");
  });
});

describe("share URLs", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("shareUrl joins WEB_ORIGIN and the share id", () => {
    vi.stubEnv("WEB_ORIGIN", "https://tuvi.example/");
    expect(shareUrl("abcdefghijklmnopqrstuv")).toBe("https://tuvi.example/vi/chia-se/abcdefghijklmnopqrstuv");
  });

  it("facebookShareUrl encodes the link", () => {
    expect(facebookShareUrl("https://x.test/vi/chia-se/a_b-c?x=1&y=2")).toBe(
      "https://www.facebook.com/sharer/sharer.php?u=https%3A%2F%2Fx.test%2Fvi%2Fchia-se%2Fa_b-c%3Fx%3D1%26y%3D2",
    );
  });
});
