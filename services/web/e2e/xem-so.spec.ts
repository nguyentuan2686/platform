import { expect, test, type Page } from "@playwright/test";
import { READING, stub, stubCalls } from "./helpers";

test.beforeEach(async () => {
  await stub("/__reset");
});

async function check(page: Page, number: string) {
  await page.getByLabel("Số điện thoại").fill(number);
  await page.getByLabel("Năm sinh (dương lịch)").fill("1990");
  await page.getByRole("button", { name: "Xem số" }).click();
}

test("SIM check shows the score on the page; the number never reaches a URL", async ({ page }) => {
  const urls: string[] = [];
  page.on("request", (r) => urls.push(r.url()));
  await page.goto("/vi/xem-so/sim");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Xem số điện thoại hợp mệnh, hợp tuổi");
  await check(page, "0912 345 678");
  await expect(page.getByRole("heading", { name: /Điểm hợp mệnh: 7 \/ 10/ })).toBeVisible();
  await expect(page.getByRole("row")).toHaveCount(6);
  await expect(page).toHaveURL(/\/vi\/xem-so\/sim$/);
  expect(urls.some((u) => u.includes("0912") || u.includes("345678"))).toBe(false);
  expect(await stubCalls(READING)).toContain("POST /tuvi/number-check");
});

test("a third distinct number shows the daily-limit message; the first again still works", async ({ page }) => {
  await page.goto("/vi/xem-so/sim");
  await check(page, "0912345678");
  await expect(page.getByRole("heading", { name: /Điểm hợp mệnh/ })).toBeVisible();
  await check(page, "0987654321");
  await expect(page.getByText("Hôm nay con còn 0 lượt xem.")).toBeVisible();
  await check(page, "0911222333");
  await expect(page.getByText("Hôm nay con xem đủ 2 số rồi, mai quay lại với ta nhé.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem lá số tử vi của con" }).first()).toHaveAttribute("href", "/vi");
  await check(page, "0912345678");
  await expect(page.getByRole("heading", { name: /Điểm hợp mệnh/ })).toBeVisible();
});

test("bank page warns about passwords and OTP; plate page asks for the vehicle; hub links all three", async ({ page }) => {
  await page.goto("/vi/xem-so/so-tai-khoan");
  await expect(page.getByText("Chỉ nhập số tài khoản. Không bao giờ nhập mật khẩu, mã OTP hay số thẻ.")).toBeVisible();
  await page.goto("/vi/xem-so/bien-so-xe");
  await expect(page.getByLabel("Loại xe")).toBeVisible();
  await page.goto("/vi/xem-so");
  for (const slug of ["sim", "so-tai-khoan", "bien-so-xe"]) {
    await expect(page.locator(`a[href="/vi/xem-so/${slug}"]`).first()).toBeVisible();
  }
});

test("no horizontal scroll at 375px", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto("/vi/xem-so/bien-so-xe");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
