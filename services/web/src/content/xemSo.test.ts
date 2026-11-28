import { describe, expect, it } from "vitest";
import { BANK_WARNING, DAILY_LIMIT_NOTE, HUB_DESCRIPTION, HUB_INTRO, IP_QUOTA_MESSAGE, KINDS, QUOTA_MESSAGE, findKind } from "@/content/xemSo";

// knowledge/tuvi/STYLE.md §2, same list as reading-tuvi SafetyLexicon.BANNED (and tuvi2027.test.ts).
const BANNED = [
  "chết", "tử vong", "tai nạn", "ung thư", "bệnh nặng", "giải hạn", "cúng", "bùa", "yểm", "phá sản",
  "ly hôn", "tuyệt tự", "đoản mệnh", "yểu", "suốt đời", "chắc chắn sẽ", "không tránh khỏi",
];
const ALSO_BANNED = [/nhất định/i, /chắc chắn/i, /số con sẽ/i, /nên (?:mua|bán|đầu tư|vay)\b/i, /\d{2}\s*[-–]\s*\d{2}/, /(đổi|thay) (số|biển)/i];
const VOICE = /(?<!\p{L})con(?!\p{L})/iu;
const CJK = /[㐀-鿿]/;

const paragraphs = (): [string, string][] => [
  ...HUB_INTRO.map((t, i): [string, string] => [`hub ${i}`, t]),
  ...KINDS.flatMap((k) => [...k.intro, ...k.method].map((t, i): [string, string] => [`${k.slug} ${i}`, t])),
  ["limit note", DAILY_LIMIT_NOTE],
  ["quota", QUOTA_MESSAGE],
  ["ip quota", IP_QUOTA_MESSAGE],
];

describe("Xem số content", () => {
  it("has the three kinds in order", () => {
    expect(KINDS.map((k) => k.slug)).toEqual(["sim", "so-tai-khoan", "bien-so-xe"]);
    expect(KINDS.map((k) => k.kind)).toEqual(["sim", "bank", "plate"]);
    expect(findKind("bien-so-xe")?.kind).toBe("plate");
    expect(findKind("nope")).toBeUndefined();
  });

  it.each(paragraphs())("%s follows STYLE.md: voice, length, no banned words, no CJK", (_, text) => {
    const lower = text.normalize("NFC").toLowerCase();
    for (const b of BANNED) expect(lower).not.toContain(b);
    for (const p of ALSO_BANNED) expect(text).not.toMatch(p);
    expect(text).toMatch(VOICE);
    expect(text).not.toMatch(CJK);
    expect(text.length).toBeLessThanOrEqual(320);
    expect(text).toMatch(/[.!?…]$/);
  });

  it.each([["hub", HUB_DESCRIPTION], ...KINDS.map((k): [string, string] => [k.slug, k.description])])(
    "%s description is 41 to 160 characters",
    (_, d) => {
      expect(d.length).toBeGreaterThan(40);
      expect(d.length).toBeLessThanOrEqual(160);
    },
  );

  it("keeps the bank warning wording", () => {
    expect(BANK_WARNING).toBe("Chỉ nhập số tài khoản. Không bao giờ nhập mật khẩu, mã OTP hay số thẻ.");
  });
});
