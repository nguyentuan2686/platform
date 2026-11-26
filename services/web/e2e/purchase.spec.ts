import { expect, test, type Page } from "@playwright/test";
import { login, stub } from "./helpers";

const PAID = "Đã nhận tiền. Lượt của con đã được cộng.";

test.beforeEach(async () => {
  await stub("/__reset");
});

/** From /vi/mua: pick a pack, confirm 18+, pay → the checkout page. Returns the order id. */
async function buy(page: Page, pack: RegExp, price: string): Promise<string> {
  await page.goto("/vi/mua");
  await page.getByRole("radio", { name: pack }).check();
  await page.getByLabel("Tôi đủ 18 tuổi").check();
  await page.getByRole("button", { name: `Thanh toán ${price}` }).click();
  await page.waitForURL((u) => u.pathname.startsWith("/vi/thanh-toan/"));
  return new URL(page.url()).pathname.split("/").pop() ?? "";
}

async function noHorizontalScroll(page: Page) {
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(scroll).toBeLessThanOrEqual(client);
}

test("buy 1 lượt by VietQR, simulated transfer → paid live, listed, refund acknowledged", async ({ page }) => {
  // 1. Logged in, the 18+ box is required, the button follows the selection.
  await login(page, "/vi/mua");
  await expect(page.locator(".balance-chip")).toHaveText("Còn 1 lượt");
  await expect(page.getByRole("radio", { name: /^5 lượt/ })).toBeChecked(); // preselected
  await expect(page.getByRole("button", { name: "Thanh toán 119.000đ" })).toBeVisible();
  await page.getByRole("radio", { name: /^1 lượt/ }).check();
  await page.getByRole("button", { name: "Thanh toán 29.000đ" }).click();
  await expect(page.locator("#err-adult")).toHaveText("Con cần xác nhận đã đủ 18 tuổi.");
  // The refused submit must not drop the chosen pack (React resets the form after an action).
  await expect(page.getByRole("radio", { name: /^1 lượt/ })).toBeChecked();
  await page.getByLabel("Tôi đủ 18 tuổi").check();
  await page.getByRole("button", { name: "Thanh toán 29.000đ" }).click();
  await page.waitForURL((u) => u.pathname.startsWith("/vi/thanh-toan/"));

  // 2. The QR panel shows the code; the simulator pays it and the page updates by itself.
  await expect(page.getByRole("img", { name: "Mã VietQR" })).toBeVisible();
  const code = (await page.locator(".qr-row", { hasText: "Nội dung" }).locator("b").textContent()) ?? "";
  expect(code).toMatch(/^TV[A-Z0-9]{6}$/);
  await expect(page.locator(".qr-row", { hasText: "Số tiền" })).toContainText("29.000đ");
  await page.getByRole("button", { name: "Giả lập chuyển khoản (môi trường thử)" }).click();
  await expect(page.getByText(PAID)).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".balance-chip")).toHaveText("Còn 2 lượt", { timeout: 10_000 });
  await expect(page.getByRole("link", { name: "Hỏi thầy ngay" })).toHaveAttribute("href", "/vi/hoi");

  // 3. Transactions: the order is paid; a refund behind the dialog is acknowledged.
  await page.goto("/vi/tai-khoan/giao-dich");
  const row = page.locator("tr", { hasText: code });
  await expect(row).toContainText("Đã thanh toán");
  await row.getByRole("button", { name: "Yêu cầu hoàn tiền" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Hoàn tiền", exact: true }).click();
  await expect(page.getByText("Ta đã ghi nhận. Tiền sẽ được chuyển lại trong vài ngày làm việc.")).toBeVisible();
  await expect(row).toContainText("Đang hoàn tiền");
});

test("anonymous /vi/mua goes to login; /vi/quan-tri is 404 for anon and non-admin", async ({ page }) => {
  await page.goto("/vi/mua");
  await page.waitForURL((u) => u.pathname === "/vi/dang-nhap");
  expect(new URL(page.url()).searchParams.get("next")).toBe("/vi/mua");

  expect((await page.goto("/vi/quan-tri"))?.status()).toBe(404);
  await login(page, "/vi/tai-khoan");
  expect((await page.goto("/vi/quan-tri"))?.status()).toBe(404);
});

test("the simulator refuses a cross-site request", async ({ page, request }) => {
  await login(page, "/vi/mua");
  const orderId = await buy(page, /^1 lượt/, "29.000đ");
  const res = await request.post("/api/dev/sepay", {
    headers: { origin: "https://evil.example", cookie: (await page.context().cookies()).map((c) => `${c.name}=${c.value}`).join("; ") },
    data: { order_id: orderId },
  });
  expect(res.status()).toBe(403);
});

test("375 px: no horizontal scroll on /vi/mua and the checkout page", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await login(page, "/vi/mua");
  await noHorizontalScroll(page);
  await buy(page, /^5 lượt/, "119.000đ");
  await expect(page.getByRole("img", { name: "Mã VietQR" })).toBeVisible();
  await noHorizontalScroll(page);
});
