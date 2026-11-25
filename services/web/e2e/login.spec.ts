import { expect, test } from "@playwright/test";
import {
  castChart,
  IDENTITY,
  lastMagicToken,
  login,
  READING,
  requestLink,
  stub,
  stubCalls,
  TEST_EMAIL_MASKED,
} from "./helpers";

const VERIFY = "POST /auth/magic-links/verify";
const EXPIRED = "Link đã hết hạn hoặc đã dùng. Con xin link mới nhé.";

test.beforeEach(async () => {
  await stub("/__reset");
});

test("guest -> magic link -> account page with merged history; link is single use; logout", async ({ page }) => {
  // 1. A guest casts a chart, then asks for a link.
  await castChart(page);
  await requestLink(page, "/vi/tai-khoan");
  await expect(page.getByRole("button", { name: "Tiếp tục với Google" })).toHaveCount(0);

  // 2. Opening the link only renders a button: nothing redeemed on GET, no Referer, token leaves the URL.
  const token = await lastMagicToken();
  const res = await page.goto(`/vi/dang-nhap/xac-nhan?token=${token}`);
  expect(res?.headers()["referrer-policy"]).toBe("no-referrer");
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  expect(await stubCalls(IDENTITY)).not.toContain(VERIFY);
  await expect.poll(() => new URL(page.url()).search).toBe("");

  // 3. The POST logs in, merges the guest's Q&A and lands on tv_next.
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => u.pathname === "/vi/tai-khoan");
  await expect(page.getByText(TEST_EMAIL_MASKED)).toBeVisible();
  await expect(page.locator(".balance-chip")).toHaveText("Còn 1 lượt");
  await expect(page.getByText("Lá số của tôi")).toBeVisible(); // the guest's chart followed
  expect(await stubCalls(IDENTITY)).toContain(VERIFY);
  expect((await stubCalls(READING)).filter((c) => c === "POST /internal/qa/merge")).toHaveLength(1);

  // 4. The same link again: expired.
  await page.goto(`/vi/dang-nhap/xac-nhan?token=${token}`);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await expect(page.getByText(EXPIRED)).toBeVisible();

  // 5. Logout revokes the session and clears the cookie.
  await page.goto("/vi/tai-khoan");
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await page.waitForURL((u) => u.pathname === "/vi");
  await expect(page.getByRole("link", { name: "Đăng nhập" }).first()).toBeVisible();
  await expect(page.locator(".balance-chip")).toHaveText("Đăng nhập");
  expect(await stubCalls(IDENTITY)).toContain("DELETE /sessions/current");
});

test("login without a guest session does not merge; next cannot leave the site", async ({ page, context }) => {
  await requestLink(page, "//evil.example");
  const next = (await context.cookies()).find((c) => c.name === "tv_next");
  expect(decodeURIComponent(next?.value ?? "")).toBe("/vi/la-so");
  expect(next?.httpOnly).toBe(true);
  await context.clearCookies(); // the link opened in another browser
  await page.goto(`/vi/dang-nhap/xac-nhan?token=${await lastMagicToken()}`);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => u.pathname === "/vi" || u.pathname === "/vi/la-so");
  expect(await stubCalls(READING)).not.toContain("POST /internal/qa/merge");
});

test("an invalid email shows the field error", async ({ page }) => {
  await page.goto("/vi/dang-nhap");
  await page.getByLabel("Email").fill("not-an-email");
  await page.getByRole("button", { name: "Gửi link đăng nhập" }).click();
  await expect(page.getByText("Email chưa đúng.")).toBeVisible();
});

test("account page: calendar switch and chart deletion behind a confirmation", async ({ page }) => {
  await castChart(page);
  await login(page, "/vi/tai-khoan");
  await expect(page.getByText("Lịch Việt", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Đổi lịch/ }).click();
  await expect(page.getByText("Lịch Trung Hoa", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Xóa lá số" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Thôi" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(page.getByText("Lá số của tôi")).toBeVisible();
  await page.getByRole("button", { name: "Xóa lá số" }).click();
  await page.getByRole("button", { name: "Xóa hẳn" }).click();
  await page.waitForURL((u) => u.pathname === "/vi");
  expect(await stubCalls(READING)).toContain("DELETE /tuvi/qa/readings");
  await page.goto("/vi/tai-khoan");
  await expect(page.getByRole("link", { name: "Lập lá số" })).toBeVisible();
});

test("anonymous /vi/tai-khoan invites to log in", async ({ page }) => {
  await page.goto("/vi/tai-khoan");
  await expect(page.getByRole("heading", { name: "Lưu lá số và nạp lượt" })).toBeVisible();
  await expect(page.locator('main a[href="/vi/dang-nhap?next=/vi/tai-khoan"]')).toBeVisible();
});

test("Google endpoints answer 404 while GOOGLE_LOGIN is off", async ({ request, baseURL }) => {
  const start = await request.post("/api/auth/google", { headers: { origin: baseURL ?? "" }, maxRedirects: 0 });
  expect(start.status()).toBe(404);
  const cb = await request.get("/vi/dang-nhap/google?code=x&state=y", { maxRedirects: 0 });
  expect(cb.status()).toBe(404);
});
