import { expect, test } from "@playwright/test";

test("star knowledge page: h1, canonical, CTA to /vi, no horizontal scroll at 375px", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  const res = await page.goto("/vi/kien-thuc/sao/thien-co");
  expect(res?.status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Thiên Cơ");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/vi\/kien-thuc\/sao\/thien-co$/);
  await expect(page).toHaveTitle("Sao Thiên Cơ trong tử vi · Tử Vi Đẩu Số");
  const cta = page.getByRole("link", { name: "Lập lá số miễn phí để xem Thiên Cơ trong lá số của con" });
  await expect(cta).toHaveAttribute("href", "/vi");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("unknown slug is a 404", async ({ page }) => {
  const res = await page.goto("/vi/kien-thuc/cung/khong-co");
  expect(res?.status()).toBe(404);
});

test("sitemap and robots", async ({ request }) => {
  const sm = await (await request.get("/sitemap.xml")).text();
  expect(sm).toContain("/vi/kien-thuc/cung/menh");
  expect(sm).not.toContain("/vi/la-so");
  const robots = await (await request.get("/robots.txt")).text();
  expect(robots).toContain("Disallow: /api/");
  // noindex pages stay crawlable so Google sees their noindex
  expect(robots).not.toContain("Disallow: /vi/la-so");
  expect(robots).not.toContain("Disallow: /vi/chia-se");
  expect(robots).toContain("Sitemap:");
});

test("legal page title carries the site suffix exactly once", async ({ page }) => {
  await page.goto("/vi/dieu-khoan");
  await expect(page).toHaveTitle("Điều khoản sử dụng · Tử Vi Đẩu Số");
});

test("sitemap lists the 48 public URLs", async ({ request }) => {
  const sm = await (await request.get("/sitemap.xml")).text();
  expect((sm.match(/<loc>/g) ?? []).length).toBe(48);
});

test("knowledge page ships hreflang, Open Graph and BreadcrumbList + Article JSON-LD in the HTML", async ({ page, request }) => {
  const html = await (await request.get("/vi/kien-thuc/cung/menh")).text();
  expect(html).toContain('property="og:locale" content="vi_VN"');
  await page.goto("/vi/kien-thuc/cung/menh");
  await expect(page.locator('link[rel="alternate"][hreflang="vi"]')).toHaveAttribute("href", /\/vi\/kien-thuc\/cung\/menh$/);
  const blocks = [...html.matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/g)].map((m) => JSON.parse(m[1]!));
  expect(blocks.map((b) => b["@type"])).toEqual(["BreadcrumbList", "Article"]);
});

test("home page carries a canonical and WebSite JSON-LD", async ({ page }) => {
  await page.goto("/vi");
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/vi$/);
  const types = await page.locator('script[type="application/ld+json"]').allTextContents();
  expect(types.map((t) => JSON.parse(t)["@type"])).toEqual(["Organization", "WebSite"]);
});

test("unknown page is a Vietnamese 404 with links back", async ({ page }) => {
  const res = await page.goto("/vi/khong-ton-tai");
  expect(res?.status()).toBe(404);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Ta không tìm thấy trang này.");
  await expect(page.getByRole("link", { name: "Đọc kiến thức tử vi" })).toHaveAttribute("href", "/vi/kien-thuc");
});
