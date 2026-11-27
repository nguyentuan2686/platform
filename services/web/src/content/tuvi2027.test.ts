import { describe, expect, it } from "vitest";
import { LUNAR_NOTE, SECTION_ORDER, YEAR_INTRO, ZODIAC, birthYears, findZodiac } from "@/content/tuvi2027";

// knowledge/tuvi/STYLE.md §2, same list as reading-tuvi SafetyLexicon.BANNED.
const BANNED = [
  "chết", "tử vong", "tai nạn", "ung thư", "bệnh nặng", "giải hạn", "cúng", "bùa", "yểm", "phá sản",
  "ly hôn", "tuyệt tự", "đoản mệnh", "yểu", "suốt đời", "chắc chắn sẽ", "không tránh khỏi",
];
// STYLE.md §2: no fate claims; SafetyLexicon.ADVICE: no concrete financial advice, no lottery numbers.
const ALSO_BANNED = [/nhất định/i, /chắc chắn/i, /số con sẽ/i, /nên (?:mua|bán|đầu tư|vay)\b/i, /\d{2}\s*[-–]\s*\d{2}/];
const VOICE = /(?<!\p{L})con(?!\p{L})/iu;
const CJK = /[㐀-鿿]/;

const allTexts = (): [string, string][] => [
  ...YEAR_INTRO.map((t, i): [string, string] => [`intro ${i}`, t]),
  ["lunar note", LUNAR_NOTE],
  ...ZODIAC.flatMap((z) => SECTION_ORDER.map((k): [string, string] => [`${z.slug}.${k}`, z.sections[k]])),
];

describe("Tử vi 2027 content", () => {
  it("has the 12 signs in order with unique slugs", () => {
    expect(ZODIAC.map((z) => z.chi)).toEqual(["Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"]);
    expect(ZODIAC.map((z) => z.index)).toEqual([...Array(12).keys()]);
    expect(new Set(ZODIAC.map((z) => z.slug)).size).toBe(12);
    for (const z of ZODIAC) expect(z.slug).toMatch(/^tuoi-[a-z]+$/);
  });

  it.each(allTexts())("%s follows STYLE.md: voice, length, no banned words, no CJK", (_, text) => {
    const lower = text.normalize("NFC").toLowerCase();
    for (const b of BANNED) expect(lower).not.toContain(b);
    for (const p of ALSO_BANNED) expect(text).not.toMatch(p);
    expect(text).toMatch(VOICE);
    expect(text).not.toMatch(CJK);
    expect(text.length).toBeLessThanOrEqual(320);
    expect(text).toMatch(/[.!?…]$/);
  });

  it("has no duplicated paragraph", () => {
    const texts = allTexts().map(([, t]) => t);
    expect(new Set(texts).size).toBe(texts.length);
  });

  it("lists lunar birth years: Tý 1948 … 2020, Mùi up to 2015, Ngọ up to 2026", () => {
    expect(birthYears(0)).toEqual([1948, 1960, 1972, 1984, 1996, 2008, 2020]);
    expect(birthYears(7).at(-1)).toBe(2015);
    expect(birthYears(6).at(-1)).toBe(2026);
    expect(birthYears(8)[0]).toBe(1944);
  });

  it("finds a sign by slug", () => {
    expect(findZodiac("tuoi-ti")?.chi).toBe("Tỵ");
    expect(findZodiac("tuoi-ty")?.chi).toBe("Tý");
    expect(findZodiac("nope")).toBeUndefined();
  });
});
