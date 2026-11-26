import { expect, test, type Page } from "@playwright/test";

const MAILPIT = "http://localhost:8025";
const AI = "Nội dung do AI biên soạn từ kho tư liệu.";
const QUESTION = "Năm nay công việc của tôi sẽ ra sao?";
const LOCK = "Mỗi tài khoản một lá số. Ngày giờ sinh đã khóa sau lượt hỏi đầu tiên.";

/** A test address only; unique per run so the newest Mailpit message for it is ours. */
const EMAIL = `e2e-${Date.now()}@example.com`;

/** Reads the magic-link path from Mailpit (newest message to EMAIL), waiting on the end state. */
async function magicLinkPath(): Promise<string> {
  let path = "";
  await expect
    .poll(
      async () => {
        const list = (await (await fetch(`${MAILPIT}/api/v1/messages`)).json()) as {
          messages: { ID: string; To: { Address: string }[] }[];
        };
        const msg = list.messages.find((m) => m.To.some((t) => t.Address === EMAIL));
        if (!msg) return "";
        const full = (await (await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`)).json()) as { Text: string };
        path = /\/vi\/dang-nhap\/xac-nhan\?token=[A-Za-z0-9_-]+/.exec(full.Text)?.[0] ?? "";
        return path;
      },
      { timeout: 30_000 },
    )
    .not.toBe("");
  return path;
}

/** Casts a chart as a guest. A fresh birth minute per run keeps the per-chart free quota unused. */
async function castFreshChart(page: Page) {
  const minute = String(Math.floor(Math.random() * 60)).padStart(2, "0");
  const hour = String(Math.floor(Math.random() * 24)).padStart(2, "0");
  const day = String(1 + Math.floor(Math.random() * 28)).padStart(2, "0");
  await page.goto("/vi");
  await page.getByLabel("Ngày sinh (dương lịch)").fill(`1988-03-${day}`);
  await page.getByLabel("Giờ sinh", { exact: true }).fill(`${hour}:${minute}`);
  await page.getByLabel("Nam", { exact: true }).check();
  await page.getByRole("checkbox", { name: /Ta được lưu ngày giờ sinh của con/ }).check();
  await page.getByRole("button", { name: /Xin Lão Thiên Cơ xem giúp/ }).click();
  await page.waitForURL((u) => u.pathname === "/vi/la-so");
  await expect(page.locator(".board a.cell")).toHaveCount(12);
}

async function ask(page: Page, kind: "free" | "paid") {
  await page.goto("/vi/hoi");
  await page.getByRole("radio", { name: /Sự nghiệp/ }).check();
  await page.getByLabel("Câu hỏi của con").fill(QUESTION);
  const consent = page.getByRole("checkbox", { name: /Con đồng ý gửi lá số ẩn danh/ });
  if (await consent.count()) await consent.check();
  await page.getByRole("button", { name: kind === "paid" ? "Dùng 1 lượt để hỏi" : /^Hỏi miễn phí/ }).click();
  await expect(page.getByText(AI)).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible({ timeout: 60_000 });
}

test("guest chart + free question -> magic link merge -> buy 1 lượt -> paid answer -> lock line -> public share", async ({
  page,
  browser,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" }); // the whole answer at once, no ink animation
  // 1-2. A guest casts a chart and asks one free question.
  await castFreshChart(page);
  await ask(page, "free");

  // 3. Magic link from Mailpit; after login the free question is still in the history (merge).
  await page.goto("/vi/dang-nhap?next=/vi/lich-su");
  await page.getByLabel("Email").fill(EMAIL);
  await page.getByRole("button", { name: "Gửi link đăng nhập" }).click();
  await expect(page.getByText(/Nếu email hợp lệ, ta đã gửi link đăng nhập/)).toBeVisible();
  await page.goto(await magicLinkPath());
  await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => u.pathname === "/vi/lich-su");
  await expect(page.locator(".history-row")).toHaveCount(1);
  await expect(page.locator(".history-row").first()).toContainText("Miễn phí");

  // 4. Buy CREDIT_1 and pay with the dev simulator: the balance goes from 1 to 2.
  await page.goto("/vi/mua");
  await expect(page.locator(".balance-chip")).toHaveText("Còn 1 lượt");
  await page.getByRole("radio", { name: /^1 lượt/ }).check();
  await page.getByLabel("Tôi đủ 18 tuổi").check();
  await page.getByRole("button", { name: "Thanh toán 29.000đ" }).click();
  await page.waitForURL((u) => u.pathname.startsWith("/vi/thanh-toan/"));
  await page.getByRole("button", { name: "Giả lập chuyển khoản (môi trường thử)" }).click();
  await expect(page.getByText("Đã nhận tiền. Lượt của con đã được cộng.")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".balance-chip")).toHaveText("Còn 2 lượt", { timeout: 30_000 });

  // 5. A paid question is answered.
  await ask(page, "paid");

  // 6. The account page shows the lock line.
  await page.goto("/vi/tai-khoan");
  await expect(page.getByText(LOCK)).toBeVisible();

  // 7. Share: the public page opens for a visitor with no cookies.
  await page.goto("/vi/la-so");
  await page.getByRole("button", { name: "Tạo link công khai" }).click();
  const link = await page.getByLabel("Link công khai").inputValue();
  expect(link).toMatch(/\/vi\/chia-se\/[A-Za-z0-9_-]{22}$/);
  const visitor = await browser.newContext({ baseURL: "http://localhost:3001" });
  const vp = await visitor.newPage();
  const res = await vp.goto(new URL(link).pathname);
  expect(res?.status()).toBe(200);
  await expect(vp.locator(".board a.cell")).toHaveCount(12);
  expect((await visitor.cookies()).length).toBe(0);
  await visitor.close();
});
