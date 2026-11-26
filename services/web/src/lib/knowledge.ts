import "server-only";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { cfg } from "@/lib/config";

/** One approved doctrine record, ready for a public knowledge page. */
export type KnowledgePage = Readonly<{ key: string; slug: string; name: string; content: string }>;

// Contract keys (contracts/knowledge) -> display names. Only these keys are public.
const PALACE_NAMES: Readonly<Record<string, string>> = {
  Menh: "Mệnh",
  PhuMau: "Phụ Mẫu",
  PhucDuc: "Phúc Đức",
  DienTrach: "Điền Trạch",
  QuanLoc: "Quan Lộc",
  NoBoc: "Nô Bộc",
  ThienDi: "Thiên Di",
  TatAch: "Tật Ách",
  TaiBach: "Tài Bạch",
  TuTuc: "Tử Tức",
  PhuThe: "Phu Thê",
  HuynhDe: "Huynh Đệ",
};

const STAR_NAMES: Readonly<Record<string, string>> = {
  TuVi: "Tử Vi",
  ThienCo: "Thiên Cơ",
  ThaiDuong: "Thái Dương",
  VuKhuc: "Vũ Khúc",
  ThienDong: "Thiên Đồng",
  LiemTrinh: "Liêm Trinh",
  ThienPhu: "Thiên Phủ",
  ThaiAm: "Thái Âm",
  ThamLang: "Tham Lang",
  CuMon: "Cự Môn",
  ThienTuong: "Thiên Tướng",
  ThienLuong: "Thiên Lương",
  ThatSat: "Thất Sát",
  PhaQuan: "Phá Quân",
};

/** "Điền Trạch" -> "dien-trach". */
export function slugify(name: string): string {
  return name
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D")
    .toLowerCase()
    .trim()
    .replace(/\s+/g, "-");
}

type Raw = { key?: unknown; content?: unknown; status?: unknown };

function load(file: string, prefix: string, names: Readonly<Record<string, string>>): KnowledgePage[] {
  const path = join(resolve(cfg().knowledgeDir), "doctrine", file);
  const out: KnowledgePage[] = [];
  for (const line of readFileSync(path, "utf8").split("\n")) {
    if (!line.trim()) continue;
    const rec = JSON.parse(line) as Raw;
    if (rec.status !== undefined && rec.status !== "approved") continue;
    if (typeof rec.key !== "string" || typeof rec.content !== "string" || !rec.key.startsWith(prefix)) continue;
    const key = rec.key.slice(prefix.length);
    const name = names[key];
    if (name === undefined) continue;
    out.push({ key, slug: slugify(name), name, content: rec.content });
  }
  return out;
}

/** The 12 palaces (Mệnh … Huynh Đệ), in file order. Read at build time. */
export function loadPalaces(): KnowledgePage[] {
  return load("palace.vi.jsonl", "tuvi:palace=", PALACE_NAMES);
}

/** The 14 main stars, in file order. Read at build time. */
export function loadStars(): KnowledgePage[] {
  return load("star.vi.jsonl", "tuvi:star=", STAR_NAMES);
}
