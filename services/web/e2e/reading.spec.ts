import { expect, test, type Page } from "@playwright/test";

const CHART_PATH = "/vi/la-so";

async function submit(page: Page) {
  await page.goto("/vi");
  await page.getByLabel("Ngày sinh (dương lịch)").fill("1990-01-15");
  await page.getByLabel("Giờ sinh", { exact: true }).fill("08:30");
  await page.getByLabel("Nam", { exact: true }).check();
  await page.getByRole("checkbox", { name: /Ta được lưu ngày giờ sinh của con/ }).check();
  await page.getByRole("button", { name: /Xin Lão Thiên Cơ xem giúp/ }).click();
  await page.waitForURL((u) => u.pathname === CHART_PATH);
}

const stub = (path: "/__down" | "/__up" | "/__reset") => fetch(`http://127.0.0.1:18082${path}`, { method: "POST" });

test.beforeEach(async () => {
  await stub("/__reset");
});
test.afterEach(async () => {
  await stub("/__up");
});

test("submit with consent -> URL is exactly /vi/la-so, board has 12 palaces", async ({ page, baseURL }) => {
  await submit(page);
  const url = page.url();
  expect(url).toBe(`${baseURL}${CHART_PATH}`);
  expect(url).not.toMatch(/1990|0115|0830|08:30/);
  await expect(page.locator(".board a.cell")).toHaveCount(12);
});

test("submit without consent -> consent error, no session", async ({ page, context }) => {
  await page.goto("/vi");
  await page.getByLabel("Ngày sinh (dương lịch)").fill("1990-01-15");
  await page.getByLabel("Giờ sinh", { exact: true }).fill("08:30");
  await page.getByLabel("Nam", { exact: true }).check();
  await page.getByRole("button", { name: /Xin Lão Thiên Cơ xem giúp/ }).click();
  await expect(page.getByText("Con cần đồng ý để ta lưu ngày giờ sinh.")).toBeVisible();
  expect((await context.cookies()).some((c) => c.name === "tv_sid")).toBe(false);
});

test("dialogue reaches the seal after Hiện hết", async ({ page }) => {
  await submit(page);
  await page.getByRole("button", { name: "Hiện hết" }).click();
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible();
});

test("today shows 4 areas under #hom-nay", async ({ page }) => {
  await submit(page);
  await expect(page.locator("#hom-nay .today-areas li")).toHaveCount(4);
});

test("the saved chart survives a reload and is linked from /vi", async ({ page }) => {
  await submit(page);
  await page.goto("/vi");
  await page.getByRole("link", { name: "Xem lá số của con" }).click();
  await page.waitForURL((u) => u.pathname === CHART_PATH);
  await expect(page.locator(".board a.cell")).toHaveCount(12);
});

test("/vi/la-so without a session redirects to /vi", async ({ page }) => {
  await page.goto(CHART_PATH);
  await page.waitForURL((u) => u.pathname === "/vi");
});

test("session cookie is httpOnly", async ({ page, context }) => {
  await submit(page);
  const visible = await page.evaluate(() => document.cookie);
  expect(visible).not.toContain("tv_sid");
  const sid = (await context.cookies()).find((c) => c.name === "tv_sid");
  expect(sid).toBeDefined();
  expect(sid?.httpOnly).toBe(true);
  expect(sid?.sameSite).toBe("Lax");
  expect(sid?.path).toBe("/");
});

test("tab bar marks Lá số current on /vi/la-so", async ({ page }) => {
  await submit(page);
  await expect(page.getByRole("navigation", { name: "Điều hướng chính" }).getByRole("link", { name: "Lá số" })).toHaveAttribute(
    "aria-current",
    "page",
  );
});

for (const path of ["/vi", CHART_PATH, "/vi/chinh-sach-rieng-tu"]) {
  test(`375px has no horizontal scroll on ${path}`, async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 1500 });
    if (path === CHART_PATH) await submit(page);
    else await page.goto(path);
    await page.evaluate(() => document.fonts.ready);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.screenshot({ path: `e2e/out/${path.replaceAll("/", "_")}-375.png`, fullPage: false });
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
