import { expect, test, type Page } from "@playwright/test";

const RESULT_URL = /\/vi\/la-so\/[0-9a-f-]{36}$/;

async function submit(page: Page) {
  await page.goto("/vi");
  await page.getByLabel("Ngày sinh (dương lịch)").fill("1990-01-15");
  await page.getByLabel("Giờ sinh").fill("08:30");
  await page.getByLabel("Nam", { exact: true }).check();
  await page.getByRole("button", { name: /Xin Lão Thiên Cơ xem giúp/ }).click();
  await page.waitForURL(RESULT_URL);
}

const stub = (path: "/__down" | "/__up") => fetch(`http://127.0.0.1:18082${path}`, { method: "POST" });

test.afterEach(async () => {
  await stub("/__up");
});

test("submit -> result URL carries no birth data, board has 12 palaces", async ({ page }) => {
  await submit(page);
  const url = page.url();
  expect(url).toMatch(RESULT_URL);
  expect(url).not.toMatch(/1990|0115|0830/);
  await expect(page.locator(".board a.cell")).toHaveCount(12);
});

test("dialogue reaches the seal after Hiện hết", async ({ page }) => {
  await submit(page);
  await page.getByRole("button", { name: "Hiện hết" }).click();
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible();
});

test("today shows 4 areas", async ({ page }) => {
  await submit(page);
  await expect(page.locator(".today-areas li")).toHaveCount(4);
});

for (const path of ["home", "result"]) {
  test(`375px has no horizontal scroll on ${path}`, async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 1500 });
    if (path === "home") await page.goto("/vi");
    else await submit(page);
    await page.evaluate(() => document.fonts.ready);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.screenshot({ path: `e2e/out/${path}-375.png`, fullPage: false });
  });
}

test("upstream down -> themed page, no stack trace", async ({ page }) => {
  await submit(page);
  await stub("/__down");
  const res = await page.goto(page.url());
  console.log(`status during outage: ${res?.status()}`);
  await expect(page.getByText("Lão Thiên Cơ cần một lát")).toBeVisible();
  const body = await page.locator("body").innerText();
  expect(body).not.toMatch(/UpstreamError|at \w+ \(|node_modules|Error:/);
});
