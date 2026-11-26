import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { loadPalaces, loadStars, slugify } from "@/lib/knowledge";

afterEach(() => vi.unstubAllEnvs());

describe("knowledge (real repo files)", () => {
  it("loads 12 palaces in contract order and 14 stars", () => {
    const palaces = loadPalaces();
    expect(palaces.map((p) => p.name)).toEqual([
      "Mệnh", "Phụ Mẫu", "Phúc Đức", "Điền Trạch", "Quan Lộc", "Nô Bộc",
      "Thiên Di", "Tật Ách", "Tài Bạch", "Tử Tức", "Phu Thê", "Huynh Đệ",
    ]);
    expect(palaces[0]).toMatchObject({ key: "Menh", slug: "menh" });
    const stars = loadStars();
    expect(stars).toHaveLength(14);
    expect(stars[1]).toMatchObject({ key: "ThienCo", slug: "thien-co", name: "Thiên Cơ" });
    for (const p of [...palaces, ...stars]) expect(p.content.length).toBeGreaterThan(40);
  });

  it("slugs are unique and ascii kebab-case", () => {
    const slugs = [...loadPalaces(), ...loadStars()].map((p) => p.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const s of slugs) expect(s).toMatch(/^[a-z]+(-[a-z]+)*$/);
  });

  it("slugify strips marks and maps đ", () => {
    expect(slugify("Điền Trạch")).toBe("dien-trach");
    expect(slugify("Thiên Cơ")).toBe("thien-co");
  });
});

describe("knowledge (temp dir)", () => {
  function tmpKb(lines: object[], file = "palace.vi.jsonl"): string {
    const dir = mkdtempSync(join(tmpdir(), "kb-"));
    mkdirSync(join(dir, "doctrine"));
    writeFileSync(join(dir, "doctrine", file), lines.map((l) => JSON.stringify(l)).join("\n") + "\n");
    return dir;
  }

  it("excludes non-approved records and keeps records with no status", () => {
    const dir = tmpKb([
      { key: "tuvi:palace=Menh", content: "Một.", status: "approved" },
      { key: "tuvi:palace=PhuMau", content: "Hai." },
      { key: "tuvi:palace=PhucDuc", content: "Ba.", status: "draft" },
    ]);
    try {
      vi.stubEnv("KNOWLEDGE_DIR", dir);
      expect(loadPalaces().map((p) => p.key)).toEqual(["Menh", "PhuMau"]);
    } finally {
      rmSync(dir, { recursive: true });
    }
  });

  it("ignores keys outside the known set", () => {
    const dir = tmpKb([{ key: "tuvi:star=Nope", content: "x" }, { key: "tuvi:star=TuVi", content: "Tử Vi." }], "star.vi.jsonl");
    try {
      vi.stubEnv("KNOWLEDGE_DIR", dir);
      expect(loadStars().map((p) => p.key)).toEqual(["TuVi"]);
    } finally {
      rmSync(dir, { recursive: true });
    }
  });
});
