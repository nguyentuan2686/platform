import type { Topic } from "@/lib/identity";

/** Q&A topics (contract enum → label · glyph · hint, from the prototype). Client-safe. */
export const TOPICS: readonly { code: Topic; label: string; glyph: string; hint: string }[] = [
  { code: "TinhDuyen", label: "Tình duyên", glyph: "緣", hint: "Cung Phu Thê, đào hoa, thời điểm gặp gỡ" },
  { code: "SuNghiep", label: "Sự nghiệp", glyph: "業", hint: "Cung Quan Lộc, hướng đi hợp mệnh" },
  { code: "TaiLoc", label: "Tài lộc", glyph: "財", hint: "Cung Tài Bạch, cách giữ và sinh tiền" },
  { code: "SucKhoe", label: "Sức khỏe", glyph: "康", hint: "Cung Tật Ách, điều cần lưu ý" },
  { code: "GiaDao", label: "Gia đạo", glyph: "家", hint: "Cung Điền Trạch, cha mẹ, con cái" },
  { code: "VanNam", label: "Vận năm", glyph: "運", hint: "Lưu niên, tháng tốt xấu" },
];

/** The label of a topic code; empty for none or an unknown code. */
export function topicLabel(code: string | null | undefined): string {
  return TOPICS.find((t) => t.code === code)?.label ?? "";
}
