/// <reference types="vite/client" />
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
  const disallowed = () => {
    const r = robots();
    const rule = Array.isArray(r.rules) ? r.rules[0]! : r.rules;
    expect(rule.allow).toBe("/");
    return ([] as string[]).concat(rule.disallow ?? []);
  };

  it("disallows only non-page endpoints and points at the sitemap", () => {
    expect(disallowed().sort()).toEqual(["/api/", "/vi/dang-nhap/google"]);
    expect(robots().sitemap).toBe(`${cfg().webOrigin}/sitemap.xml`);
  });

  // Google must crawl a page to see its noindex; a disallowed URL linked from elsewhere (a share
  // link on Zalo/Facebook) can be indexed without its content.
  it.each(["/vi/la-so", "/vi/hoi", "/vi/hoi/r1", "/vi/lich-su", "/vi/tai-khoan", "/vi/tai-khoan/giao-dich", "/vi/thanh-toan/o1", "/vi/quan-tri", "/vi/dang-nhap", "/vi/dang-nhap/xac-nhan", "/vi/chia-se/s1", "/vi/mua"])(
    "does not block the noindex page %s from crawling",
    (page) => {
      for (const d of disallowed()) expect(page.startsWith(d)).toBe(false);
    },
  );
});

describe("indexable pages", () => {
  type Meta = {
    robots?: { index?: boolean };
    description?: string;
    alternates?: { canonical?: string; languages?: Record<string, string> };
    openGraph?: { url?: string; locale?: string };
  };
  const o = cfg().webOrigin;
  const cases: [string, () => Promise<Meta>][] = [
    ["/vi", async () => (await import("@/app/vi/page")).metadata as Meta],
    ["/vi/kien-thuc", async () => (await import("@/app/vi/kien-thuc/page")).metadata as Meta],
    ["/vi/dieu-khoan", async () => (await import("@/app/vi/dieu-khoan/page")).metadata as Meta],
    ["/vi/chinh-sach-rieng-tu", async () => (await import("@/app/vi/chinh-sach-rieng-tu/page")).metadata as Meta],
    ["/vi/hoan-tien", async () => (await import("@/app/vi/hoan-tien/page")).metadata as Meta],
    [
      "/vi/kien-thuc/cung/menh",
      async () =>
        (await (await import("@/app/vi/kien-thuc/cung/[slug]/page")).generateMetadata({
          params: Promise.resolve({ slug: "menh" }),
        })) as Meta,
    ],
    [
      "/vi/kien-thuc/sao/thien-co",
      async () =>
        (await (await import("@/app/vi/kien-thuc/sao/[slug]/page")).generateMetadata({
          params: Promise.resolve({ slug: "thien-co" }),
        })) as Meta,
    ],
  ];

  it.each(cases)("%s: indexable, self-canonical, vi hreflang, description <= 160 chars", async (path, load) => {
    const m = await load();
    expect(m.robots?.index).toBe(true);
    expect(m.alternates?.canonical).toBe(`${o}${path}`);
    expect(m.alternates?.languages).toEqual({ vi: `${o}${path}`, "x-default": `${o}${path}` });
    expect(m.openGraph).toMatchObject({ url: `${o}${path}`, locale: "vi_VN" });
    expect(m.description?.length).toBeGreaterThan(40);
    expect(m.description!.length).toBeLessThanOrEqual(160);
  });

  it("every sitemap URL is one of the indexable pages' canonicals or a knowledge page", () => {
    for (const { url } of sitemap()) expect(url).toMatch(new RegExp(`^${o}/vi(/kien-thuc(/(cung|sao)/[a-z-]+)?|/dieu-khoan|/chinh-sach-rieng-tu|/hoan-tien)?$`));
  });
});

describe("private pages", () => {
  it.each(PRIVATE_PAGES)("%s is noindex", async (path) => {
    const mod = (await import(/* @vite-ignore */ path)) as { metadata?: { robots?: { index?: boolean } } };
    expect(mod.metadata?.robots?.index).toBe(false);
  });
});

describe("static sitemap", () => {
  it("is not force-dynamic: the runtime image has no knowledge files", async () => {
    const mod = (await import("@/app/sitemap")) as { dynamic?: string };
    expect(mod.dynamic).toBeUndefined();
  });
});

describe("page titles", () => {
  // The root layout's template ("%s · Tử Vi Đẩu Số") adds the one site suffix to every page title.
  const SUFFIX = /Lão Thiên Cơ|Tử Vi Đẩu Số/;
  type PageModule = {
    metadata?: { title?: unknown };
    generateMetadata?: (p: { params: Promise<Record<string, string>> }) => Promise<{ title?: unknown }>;
    generateStaticParams?: () => Record<string, string>[];
  };
  const modules = import.meta.glob<PageModule>("./vi/**/{page,not-found}.tsx");
  const paths = Object.keys(modules);

  it("finds the pages", () => {
    expect(paths.length).toBeGreaterThanOrEqual(20);
  });

  it.each(paths)("%s carries no site suffix of its own", async (path) => {
    const mod = await modules[path]!();
    const titles: unknown[] = [];
    if (mod.metadata && "title" in mod.metadata) titles.push(mod.metadata.title);
    if (mod.generateMetadata && mod.generateStaticParams) {
      const first = mod.generateStaticParams()[0];
      if (first) titles.push((await mod.generateMetadata({ params: Promise.resolve(first) })).title);
    }
    for (const t of titles) expect(typeof t === "string" ? t : JSON.stringify(t ?? "")).not.toMatch(SUFFIX);
  });
});
