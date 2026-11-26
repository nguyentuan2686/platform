import { expect, type Page } from "@playwright/test";

export const IDENTITY = "http://127.0.0.1:18084";
export const READING = "http://127.0.0.1:18083";
/** A test address only (never a real person's). */
export const TEST_EMAIL = "an.test@example.com";
export const TEST_EMAIL_MASKED = "a***@e***.com";

export const stub = (path: "/__down" | "/__up" | "/__reset") => fetch(`http://127.0.0.1:18082${path}`, { method: "POST" });

/** The "METHOD /path" calls a stub port received since the last reset. */
export async function stubCalls(base: string): Promise<string[]> {
  return (await fetch(`${base}/__calls`)).json();
}

/** The token the identity stub put in the last magic link. */
export async function lastMagicToken(): Promise<string> {
  const { token } = (await (await fetch(`${IDENTITY}/__last-magic-token`)).json()) as { token: string | null };
  if (!token) throw new Error("no magic link issued");
  return token;
}

/** Casts a chart as a guest through the home form (lands on /vi/la-so). */
export async function castChart(page: Page) {
  await page.goto("/vi");
  await page.getByLabel("Ngày sinh (dương lịch)").fill("1990-01-15");
  await page.getByLabel("Giờ sinh", { exact: true }).fill("08:30");
  await page.getByLabel("Nam", { exact: true }).check();
  await page.getByRole("checkbox", { name: /Ta được lưu ngày giờ sinh của con/ }).check();
  await page.getByRole("button", { name: /Xin Lão Thiên Cơ xem giúp/ }).click();
  await page.waitForURL((u) => u.pathname === "/vi/la-so");
}

/** Requests a magic link for `email` from /vi/dang-nhap (optionally with ?next=). */
export async function requestLink(page: Page, next?: string, email = TEST_EMAIL) {
  await page.goto(next ? `/vi/dang-nhap?next=${next}` : "/vi/dang-nhap");
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Gửi link đăng nhập" }).click();
  await expect(page.getByText("Nếu email hợp lệ, ta đã gửi link đăng nhập. Link có hiệu lực 15 phút.")).toBeVisible();
}

/** Logs in by magic link (for later suites); lands on `next` (default /vi/la-so). */
export async function login(page: Page, next?: string) {
  await requestLink(page, next);
  await page.goto(`/vi/dang-nhap/xac-nhan?token=${await lastMagicToken()}`);
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => u.pathname === (next ?? "/vi/la-so"));
}
