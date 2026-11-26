import { expect, test, type Page } from "@playwright/test";
import { castChart, login, READING, stub, stubCalls } from "./helpers";

const AI = "Nội dung do AI biên soạn từ kho tư liệu.";
const QUESTION = "Năm nay công việc của tôi sẽ ra sao?";
const ASK_CALL = "POST /tuvi/qa/readings";

test.beforeEach(async () => {
  await stub("/__reset");
});

type Kind = "free" | "paid";

/** On /vi/hoi: pick a topic, type the question, tick the consent box when shown, press the button. */
async function ask(page: Page, kind: Kind, question = QUESTION, topic = /Sự nghiệp/) {
  await page.getByRole("radio", { name: topic }).check();
  await page.getByLabel("Câu hỏi của con").fill(question);
  const consent = page.getByRole("checkbox", { name: /Con đồng ý gửi lá số ẩn danh/ });
  if (await consent.count()) await consent.check();
  await page
    .getByRole("button", { name: kind === "paid" ? "Dùng 1 lượt để hỏi" : /^Hỏi miễn phí/ })
    .click();
}

/** The whole answer is shown (the ink effect skipped), sealed and labelled. */
async function finishAnswer(page: Page) {
  await expect(page.getByText(AI)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible();
  await expect(page.locator('.ink-stream .w[data-on="false"]')).toHaveCount(0);
}

async function noHorizontalScroll(page: Page) {
  const { scroll, client, wide } = await page.evaluate(() => {
    const client = document.documentElement.clientWidth;
    const wide = Array.from(document.querySelectorAll("body *"))
      .filter((e) => e.getBoundingClientRect().right > client + 0.5)
      .slice(0, 6)
      .map((e) => `${e.tagName.toLowerCase()}.${String(e.className)}`);
    return { scroll: document.documentElement.scrollWidth, client, wide };
  });
  expect(scroll, `wider than the viewport: ${wide.join(", ")}`).toBeLessThanOrEqual(client);
}

test("a guest asks a free question: the answer inks in sentence by sentence, 'Hiện hết' seals it", async ({ page }) => {
  const hosts: string[] = [];
  page.on("request", (r) => hosts.push(new URL(r.url()).host));
  await castChart(page);
  await page.goto("/vi/hoi");
  await expect(page.getByRole("heading", { name: "Con muốn hỏi về điều gì?" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Đăng nhập để nạp lượt" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Dùng 1 lượt để hỏi" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Hỏi miễn phí (còn 3 câu hôm nay)" })).toBeVisible();

  await ask(page, "free");
  // Words ink in one by one: the first is on while the rest of the answer is still coming.
  await expect(page.locator('.ink-stream .w[data-on="true"]').first()).toBeAttached({ timeout: 10_000 });
  await expect(page.locator('.ink-stream .w[data-on="false"]').first()).toBeAttached();
  await expect(page.getByText(AI)).toHaveCount(0);
  await page.getByRole("button", { name: "Hiện hết" }).click();
  await finishAnswer(page);
  await expect(page.locator(".ink-stream")).toContainText("Cung Quan Lộc của con có Tử Vi");
  await expect(page.locator(".ink-stream")).toContainText("thời vận sẽ mở ra");
  await expect(page.getByText("Lời luận chỉ để tham khảo, con tự cân nhắc quyết định của mình.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem lại" })).toHaveAttribute("href", /^\/vi\/hoi\/[0-9a-f-]{36}$/);
  await expect(page.getByLabel("Câu hỏi nối tiếp")).toHaveCount(0); // a free chat has no follow-ups

  // The browser only ever talked to the web origin; the AI consent cookie is HttpOnly.
  expect(hosts.filter((h) => !h.endsWith(":3100"))).toEqual([]);
  const consent = (await page.context().cookies()).find((c) => c.name === "tv_ai_ok");
  expect(consent?.httpOnly).toBe(true);
  expect(await page.evaluate(() => document.cookie)).not.toContain("tv_ai_ok");
  expect(await stubCalls(READING)).toContain(ASK_CALL);

  // The free quota went down, and the consent box is gone for the next question.
  await page.reload();
  await expect(page.getByRole("button", { name: "Hỏi miễn phí (còn 2 câu hôm nay)" })).toBeVisible();
  await expect(page.getByRole("checkbox")).toHaveCount(0);
});

test("reduced motion shows the whole answer at once", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await castChart(page);
  await page.goto("/vi/hoi");
  await ask(page, "free");
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.ink-stream .w[data-on="false"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Hiện hết" })).toHaveCount(0);
});

test("the AI consent box is required before the first question, and nothing is sent without it", async ({ page }) => {
  await castChart(page);
  await page.goto("/vi/hoi");
  await page.getByRole("radio", { name: /Tài lộc/ }).check();
  await page.getByLabel("Câu hỏi của con").fill(QUESTION);
  await page.getByRole("button", { name: /^Hỏi miễn phí/ }).click();
  await expect(page.locator("p[role=alert]")).toHaveText("Con cần đồng ý trước khi hỏi.");
  expect(await stubCalls(READING)).not.toContain(ASK_CALL);
  // A question that is too short, and no topic, are refused in the form too.
  await page.getByLabel("Câu hỏi của con").fill("ngắn");
  await page.getByRole("button", { name: /^Hỏi miễn phí/ }).click();
  await expect(page.getByText("Câu hỏi cần ít nhất 5 ký tự.")).toBeVisible();
  expect(await stubCalls(READING)).not.toContain(ASK_CALL);
});

test("a paid question: answer, then a follow-up; the reading page counts what is left", async ({ page }) => {
  await castChart(page);
  await login(page, "/vi/hoi"); // the guest's chart follows into the account (1 free credit)
  await expect(page.locator(".balance-chip")).toHaveText("Còn 1 lượt");
  await expect(page.getByText("Kèm 2 câu hỏi nối tiếp trong cùng chủ đề.")).toBeVisible();

  await ask(page, "paid");
  await page.getByRole("button", { name: "Hiện hết" }).click();
  await finishAnswer(page);
  await expect(page.getByText("Còn 2 câu hỏi nối tiếp trong chủ đề này.")).toBeVisible();

  await page.getByLabel("Câu hỏi nối tiếp").fill("Còn chuyện tiền bạc thì sao ạ?");
  await page.getByRole("button", { name: "Hỏi tiếp" }).click();
  await expect(page.getByText("Còn 1 câu hỏi nối tiếp trong chủ đề này.")).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".follow-up")).toContainText("Về chuyện con hỏi thêm");

  await page.getByRole("link", { name: "Xem lại" }).first().click();
  await page.waitForURL((u) => /^\/vi\/hoi\/[0-9a-f-]{36}$/.test(u.pathname));
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Sự nghiệp");
  await expect(page.locator(".ask")).toHaveText([QUESTION, "Còn chuyện tiền bạc thì sao ạ?"]);
  await expect(page.getByText("Còn 1 câu hỏi nối tiếp trong chủ đề này.")).toBeVisible();
  await expect(page.getByText(AI)).toBeVisible();
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible();
  // No animation on the stored answer: every word is already on.
  await expect(page.locator(".w")).toHaveCount(0);
  await expect(page.locator(".balance-chip")).toHaveText("Còn 0 lượt");

  // Out of credits: the paid button is gone, the way to buy is offered.
  await page.goto("/vi/hoi");
  await expect(page.getByRole("button", { name: "Dùng 1 lượt để hỏi" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Nạp lượt" }).first()).toHaveAttribute("href", "/vi/mua");
});

test("a question the model cannot answer now is queued; the history says Đang soạn and keeps it from deletion", async ({ page }) => {
  await castChart(page);
  await login(page, "/vi/hoi");
  await ask(page, "paid", "Hệ thống báo lỗi thì ta hỏi lại sau nhé?");
  await expect(page.getByText("Thầy đang bận, bài luận sẽ có trong Lịch sử khi xong.")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("Ta sẽ báo trong Lịch sử khi bài luận xong.")).toBeVisible();

  await page.goto("/vi/lich-su");
  const row = page.getByRole("link", { name: /Sự nghiệp/ });
  await expect(row).toContainText("Đang soạn");
  await expect(row).toContainText("Lượt");
  await row.click();
  await expect(page.getByText("Thầy đang bận, bài luận sẽ có trong Lịch sử khi xong.")).toBeVisible();
  await expect(page.getByText("Trang tự cập nhật khi ta luận xong.")).toBeVisible();
  await expect(page.getByRole("img", { name: "Ấn son" })).toHaveCount(0);

  await page.goto("/vi/lich-su");
  await page.getByRole("button", { name: "Xóa toàn bộ lịch sử hỏi" }).click();
  await page.getByRole("button", { name: "Xóa hẳn" }).click();
  await expect(page.locator("p[role=alert]")).toHaveText("Còn câu đang soạn, con chờ xong rồi xóa nhé.");
  await expect(page.getByRole("link", { name: /Sự nghiệp/ })).toBeVisible();
});

test("the history lists a free and a paid question, newest first; delete all empties it", async ({ page }) => {
  await castChart(page);
  await page.goto("/vi/hoi");
  await ask(page, "free", QUESTION, /Tình duyên/);
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible({ timeout: 15_000 });
  await login(page, "/vi/hoi"); // the guest's chart and its free question move into the account
  await ask(page, "paid", QUESTION, /Tài lộc/);
  await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible({ timeout: 15_000 });

  await page.goto("/vi/lich-su");
  const rows = page.locator(".history-row");
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText("Tài lộc");
  await expect(rows.nth(0)).toContainText("Lượt");
  await expect(rows.nth(0)).toContainText("Còn 2 câu nối tiếp");
  await expect(rows.nth(0)).toContainText("Đã luận");
  await expect(rows.nth(1)).toContainText("Tình duyên");
  await expect(rows.nth(1)).toContainText("Miễn phí");
  await expect(rows.nth(0)).toContainText(/\d{2}\/\d{2}\/\d{4}/);

  await page.getByRole("button", { name: "Xóa toàn bộ lịch sử hỏi" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Thôi" }).click();
  await expect(rows).toHaveCount(2);
  await page.getByRole("button", { name: "Xóa toàn bộ lịch sử hỏi" }).click();
  await page.getByRole("button", { name: "Xóa hẳn" }).click();
  await expect(page.getByText("Con chưa hỏi câu nào.")).toBeVisible();
  await expect(rows).toHaveCount(0);
});

test("a sensitive question is refused in Lão Thiên Cơ's words, without a seal", async ({ page }) => {
  await castChart(page);
  await page.goto("/vi/hoi");
  await ask(page, "free", "Tôi bị bệnh nặng, bao giờ khỏi hẳn?");
  await expect(page.getByText("Chuyện bệnh tật ta không luận, con hãy hỏi thầy thuốc.")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("Lời luận chỉ để tham khảo, con tự cân nhắc quyết định của mình.")).toBeVisible();
  await expect(page.getByRole("img", { name: "Ấn son" })).toHaveCount(0);
  // Refused is never counted against the free quota.
  await page.reload();
  await expect(page.getByRole("button", { name: "Hỏi miễn phí (còn 3 câu hôm nay)" })).toBeVisible();
});

test("a broken connection keeps the reading: message, link back, and a retry that reuses the id", async ({ page }) => {
  await castChart(page);
  await page.goto("/vi/hoi");
  const ids: string[] = [];
  await page.route("**/api/qa/readings", async (route) => {
    ids.push((route.request().postDataJSON() as { reading_id: string }).reading_id);
    await route.abort("connectionreset");
  });
  await ask(page, "free");
  await expect(page.getByText("Mất kết nối. Bài luận vẫn được lưu, con xem lại trong Lịch sử nhé.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem lại" })).toHaveAttribute("href", `/vi/hoi/${ids[0]}`);
  await page.getByRole("button", { name: /^Hỏi miễn phí/ }).click();
  await expect.poll(() => ids.length).toBe(2);
  expect(ids[1]).toBe(ids[0]);
});

test("the free button is disabled once the day's three free questions are used", async ({ page }) => {
  await castChart(page);
  await page.goto("/vi/hoi");
  for (let i = 0; i < 3; i++) {
    await ask(page, "free", `Câu hỏi số ${i + 1} của con đây nhé?`);
    await expect(page.getByRole("img", { name: "Ấn son" })).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "Hỏi câu khác" }).click();
  }
  await expect(page.getByRole("button", { name: "Hỏi miễn phí (còn 0 câu hôm nay)" })).toBeDisabled();
});

test("the ask route refuses a cross-site request and a foreign Origin", async ({ page, request }) => {
  await castChart(page);
  const cookie = (await page.context().cookies()).map((c) => `${c.name}=${c.value}`).join("; ");
  const body = {
    reading_id: "33333333-3333-4333-8333-333333333333",
    kind: "FREE",
    topic: "SuNghiep",
    question: QUESTION,
    ai_consent: true,
  };
  const res = await request.post("/api/qa/readings", { headers: { origin: "https://evil.example", cookie }, data: body });
  expect(res.status()).toBe(403);
  expect(await stubCalls(READING)).not.toContain(ASK_CALL);
});

test("without a chart /vi/hoi sends the visitor to make one; the tab bar has Hỏi thầy", async ({ page }) => {
  await page.goto("/vi/hoi");
  await expect(page.getByText("Con lập lá số trước đã.").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "Lập lá số" })).toHaveAttribute("href", "/vi");
  const tab = page.getByRole("navigation", { name: "Điều hướng chính" }).getByRole("link", { name: /Hỏi thầy/ });
  await expect(tab).toHaveAttribute("href", "/vi/hoi");
  await expect(tab).toHaveAttribute("aria-current", "page");
  // A reading id that is not the visitor's is a 404.
  expect((await page.goto("/vi/hoi/33333333-3333-4333-8333-333333333333"))?.status()).toBe(404);
});

test("at 375 px /vi/hoi has no horizontal scroll, before and after an answer", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await castChart(page);
  await page.goto("/vi/hoi");
  await noHorizontalScroll(page);
  await ask(page, "free");
  await page.getByRole("button", { name: "Hiện hết" }).click();
  await finishAnswer(page);
  await noHorizontalScroll(page);
  await page.goto("/vi/lich-su");
  await noHorizontalScroll(page);
  await page.screenshot({ path: "e2e/out/_vi_lich-su-375.png", fullPage: true });
});
