import type { Birth } from "@/lib/types";
import { ZONES } from "@/lib/labels";

export type FormErrors = Record<"date" | "time" | "gender" | "zone" | "consent", string>;
export type ParsedForm = { ok: true; birth: Birth } | { ok: false; errors: Partial<FormErrors> };

const MIN_YEAR = 1900;
const MAX_YEAR = 2100;

function parseDate(raw: string): { y: number; m: number; d: number } | undefined {
  let y: number;
  let m: number;
  let d: number;
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(raw);
  if (iso) {
    [y, m, d] = [Number(iso[1]), Number(iso[2]), Number(iso[3])];
  } else {
    const vn = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(raw);
    if (!vn) return undefined;
    [d, m, y] = [Number(vn[1]), Number(vn[2]), Number(vn[3])];
  }
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return undefined;
  return { y, m, d };
}

const pad = (n: number, w = 2) => String(n).padStart(w, "0");

export function parseBirthForm(fd: FormData): ParsedForm {
  const errors: Partial<FormErrors> = {};
  const str = (k: string) => {
    const v = fd.get(k);
    return typeof v === "string" ? v.trim() : "";
  };

  const date = parseDate(str("date"));
  if (!date) errors.date = "Con nhập ngày sinh dương lịch hợp lệ (ngày/tháng/năm).";
  else if (date.y < MIN_YEAR || date.y > MAX_YEAR)
    errors.date = `Ta chỉ xem được các năm từ ${MIN_YEAR} đến ${MAX_YEAR}.`;

  const t = /^(\d{1,2}):(\d{2})$/.exec(str("time"));
  const hh = t ? Number(t[1]) : -1;
  const mm = t ? Number(t[2]) : -1;
  if (!t || hh > 23 || mm > 59) errors.time = "Con nhập giờ sinh hợp lệ (giờ:phút).";

  const gender = str("gender");
  if (gender !== "male" && gender !== "female") errors.gender = "Con hãy chọn giới tính.";

  const zone = str("zone") || "Asia/Ho_Chi_Minh";
  if (!ZONES.some((z) => z.value === zone)) errors.zone = "Con hãy chọn nơi sinh trong danh sách.";

  const consent = str("consent");
  if (consent !== "1" && consent !== "on") errors.consent = "Con cần đồng ý để ta lưu ngày giờ sinh.";

  if (Object.keys(errors).length > 0 || !date) return { ok: false, errors };
  return {
    ok: true,
    birth: {
      birth: `${pad(date.y, 4)}-${pad(date.m)}-${pad(date.d)}T${pad(hh)}:${pad(mm)}`,
      zone,
      gender: gender as Birth["gender"],
      calendar: "VN",
    },
  };
}
