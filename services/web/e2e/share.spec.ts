import { expect, test } from "@playwright/test";
import { castChart, stub } from "./helpers";

const SHARE_URL = /\/vi\/chia-se\/[A-Za-z0-9_-]{22}$/;

test.beforeEach(async () => {
  await stub("/__reset");
});
test.afterEach(async () => {
  await stub("/__up");
});

test("create a public link, view it without a session, fetch the card, revoke it", async ({ page, browser }) => {
  await castChart(page);
  await page.getByRole("button", { name: "Tạo link công khai" }).click();
  const field = page.getByLabel("Link công khai");
  await expect(field).toHaveValue(SHARE_URL);
  const link = await field.inputValue();
  const path = new URL(link).pathname;

  // A visitor with no cookies sees the chart, with no birth date or lunar date.
  const visitor = await browser.newContext({ baseURL: new URL(link).origin });
  const vp = await visitor.newPage();
  const res = await vp.goto(path);
  expect(res?.status()).toBe(200);
  expect(res?.headers()["cache-control"]).toContain("no-store");
  expect(res?.headers()["x-robots-tag"]).toContain("noindex");
  await expect(vp.locator(".board a.cell")).toHaveCount(12);
  const text = await vp.locator("body").innerText();
  expect(text).not.toContain("1990");
  expect(text).not.toContain("08:30");
  expect(text).not.toMatch(/Âm lịch/);
  expect(text).toContain("Nam · giờ Thìn · năm Kỷ Tỵ");
  await expect(vp.locator('meta[property="og:image"]')).toHaveAttribute("content", /\/vi\/chia-se\/[A-Za-z0-9_-]{22}\/the\.png$/);
  await expect(vp.locator('meta[name="robots"]')).toHaveAttribute("content", /noindex/);
  expect((await visitor.cookies()).length).toBe(0);

  // The image card.
  const card = await visitor.request.get(`${path}/the.png`);
  expect(card.status()).toBe(200);
  expect(card.headers()["content-type"]).toBe("image/png");
  const png = await card.body();
  expect(png.readUInt32BE(16)).toBe(1080);
  expect(png.readUInt32BE(20)).toBe(1920);

  // Revoke: the old link no longer shows the chart.
  await page.getByRole("button", { name: "Tắt link" }).click();
  await expect(page.getByRole("button", { name: "Tạo link công khai" })).toBeVisible();
  const gone = await vp.goto(path);
  expect(gone?.status()).toBe(404);
  await expect(vp.getByText("Link này đã tắt")).toBeVisible();
  expect((await visitor.request.get(`${path}/the.png`)).status()).toBe(404);
  await visitor.close();
});

test("the public page is usable at 375 px without horizontal scroll", async ({ page, browser }) => {
  await castChart(page);
  await page.getByRole("button", { name: "Tạo link công khai" }).click();
  const link = await page.getByLabel("Link công khai").inputValue();
  const visitor = await browser.newContext({ viewport: { width: 375, height: 812 } });
  const vp = await visitor.newPage();
  await vp.goto(link);
  await expect(vp.locator(".board a.cell")).toHaveCount(12);
  expect(await vp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await visitor.close();
});
