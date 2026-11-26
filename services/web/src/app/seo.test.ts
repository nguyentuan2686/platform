import { describe, expect, it } from "vitest";
import sitemap from "@/app/sitemap";
import robots from "@/app/robots";
import { cfg } from "@/lib/config";

const PRIVATE_PAGES = [
  "@/app/vi/la-so/page",
  "@/app/vi/hoi/page",
  "@/app/vi/hoi/[readingId]/page",
  "@/app/vi/lich-su/page",
  "@/app/vi/tai-khoan/page",
  "@/app/vi/tai-khoan/giao-dich/page",
  "@/app/vi/thanh-toan/[orderId]/page",
  "@/app/vi/quan-tri/page",
  "@/app/vi/dang-nhap/page",
  "@/app/vi/dang-nhap/xac-nhan/page",
  "@/app/vi/mua/page",
];

describe("sitemap", () => {
  it("lists exactly the 31 public absolute URLs", () => {
    const urls = sitemap().map((e) => e.url);
    expect(urls).toHaveLength(31);
    expect(new Set(urls).size).toBe(31);
    for (const u of urls) expect(u.startsWith(cfg().webOrigin)).toBe(true);
    expect(urls).toContain(`${cfg().webOrigin}/vi/kien-thuc/cung/menh`);
    expect(urls).toContain(`${cfg().webOrigin}/vi/kien-thuc/sao/thien-co`);
    expect(urls).toContain(`${cfg().webOrigin}/vi/dieu-khoan`);
    expect(urls.some((u) => /la-so|hoi\b|tai-khoan|chia-se|quan-tri/.test(u))).toBe(false);
  });
});

describe("robots", () => {
  it("disallows private routes and points at the sitemap", () => {
    const r = robots();
    const rule = Array.isArray(r.rules) ? r.rules[0]! : r.rules;
    expect(rule.allow).toBe("/");
    const dis = ([] as string[]).concat(rule.disallow ?? []);
    for (const p of ["/api/", "/vi/la-so", "/vi/hoi", "/vi/lich-su", "/vi/tai-khoan", "/vi/thanh-toan", "/vi/quan-tri", "/vi/dang-nhap", "/vi/chia-se", "/vi/mua"]) {
      expect(dis).toContain(p);
    }
    expect(r.sitemap).toBe(`${cfg().webOrigin}/sitemap.xml`);
  });
});

describe("private pages", () => {
  it.each(PRIVATE_PAGES)("%s is noindex", async (path) => {
    const mod = (await import(/* @vite-ignore */ path)) as { metadata?: { robots?: { index?: boolean } } };
    expect(mod.metadata?.robots?.index).toBe(false);
  });
});
