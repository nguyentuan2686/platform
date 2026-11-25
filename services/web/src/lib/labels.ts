import type { Branch, Level } from "@/lib/types";

const LEVELS: Record<Level, string> = { Thuan: "Thuận", Binh: "Bình", LuuY: "Lưu ý" };
export function levelLabel(l: Level): string {
  return LEVELS[l];
}

const BRANCHES: Record<Branch, string> = {
  Zi: "Tý",
  Chou: "Sửu",
  Yin: "Dần",
  Mao: "Mão",
  Chen: "Thìn",
  Si: "Tỵ",
  Wu: "Ngọ",
  Wei: "Mùi",
  Shen: "Thân",
  You: "Dậu",
  Xu: "Tuất",
  Hai: "Hợi",
};
export function branchLabel(b: Branch): string {
  return BRANCHES[b];
}
export function hourLabel(b: Branch): string {
  return `giờ ${BRANCHES[b]}`;
}

export const GENDER_LABELS = { male: "Nam", female: "Nữ" } as const;

export const ZONES: { value: string; label: string }[] = [
  { value: "Asia/Ho_Chi_Minh", label: "Việt Nam" },
  { value: "Asia/Bangkok", label: "Thái Lan (Bangkok)" },
  { value: "Asia/Tokyo", label: "Nhật Bản (Tokyo)" },
  { value: "Asia/Seoul", label: "Hàn Quốc (Seoul)" },
  { value: "Asia/Shanghai", label: "Trung Quốc (Thượng Hải)" },
  { value: "Asia/Taipei", label: "Đài Loan (Đài Bắc)" },
  { value: "Asia/Singapore", label: "Singapore" },
  { value: "Australia/Sydney", label: "Úc (Sydney)" },
  { value: "Europe/Paris", label: "Pháp (Paris)" },
  { value: "Europe/Berlin", label: "Đức (Berlin)" },
  { value: "Europe/London", label: "Anh (London)" },
  { value: "America/New_York", label: "Mỹ (New York)" },
  { value: "America/Chicago", label: "Mỹ (Chicago)" },
  { value: "America/Los_Angeles", label: "Mỹ (Los Angeles)" },
];

const STEMS: Record<string, string> = {
  Jia: "Giáp",
  Yi: "Ất",
  Bing: "Bính",
  Ding: "Đinh",
  Wu: "Mậu",
  Ji: "Kỷ",
  Geng: "Canh",
  Xin: "Tân",
  Ren: "Nhâm",
  Gui: "Quý",
};
export function stemLabel(s: string): string {
  return STEMS[s] ?? s;
}
/** "Kỷ Tỵ" for a palace's stem + branch. */
export function canChi(stem: string, branch: Branch): string {
  return `${stemLabel(stem)} ${BRANCHES[branch]}`;
}

const BRIGHTNESS: Record<string, string> = { Mieu: "M", Vuong: "V", Dac: "Đ", Binh: "B", Ham: "H" };
export function brightnessLetter(b: string): string {
  return BRIGHTNESS[b] ?? "";
}

const MUTAGENS: Record<string, string> = { Loc: "Lộc", Quyen: "Quyền", Khoa: "Khoa", Ky: "Kỵ" };
export function mutagenLabel(m: string): string {
  return MUTAGENS[m] ?? m;
}
