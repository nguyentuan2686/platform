import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
const refresh = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

import { QaAsk } from "./QaAsk";

const AI = "Nội dung do AI biên soạn từ kho tư liệu.";
const DROP = "Mất kết nối. Bài luận vẫn được lưu, con xem lại trong Lịch sử nhé.";
const QUESTION = "Năm nay công việc của tôi thế nào?";
const enc = new TextEncoder();

const ev = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const META = ev("meta", { reading_id: "x", kind: "FREE", topic: "SuNghiep", follow_ups_left: 0 });
const DONE = ev("done", { reading_id: "x", seq: 0, disclaimer: "Lời ta chỉ để tham khảo." });
const sentence = (t: string) => ev("delta", { text: t });

/** A 200 SSE response that delivers `chunks`, then ends or errors. */
function streamOf(chunks: (string | Uint8Array)[], end: "close" | "error" = "close"): Response {
  let i = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(c) {
      if (i < chunks.length) {
        const ch = chunks[i++];
        c.enqueue(typeof ch === "string" ? enc.encode(ch) : ch);
      } else if (end === "error") c.error(new TypeError("network"));
      else c.close();
    },
  });
  return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
}
const problem = (status: number, reason: string) =>
  new Response(JSON.stringify({ reason }), { status, headers: { "content-type": "application/json" } });

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  refresh.mockReset();
  vi.stubGlobal("matchMedia", (q: string) => ({
    matches: q.includes("prefers-reduced-motion"),
    media: q,
    addEventListener() {},
    removeEventListener() {},
  }));
  fetchMock = vi.fn(async () => streamOf([META, sentence("Quan Lộc của con sáng. "), sentence("Hãy kiên nhẫn. "), DONE]));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

type Props = Partial<React.ComponentProps<typeof QaAsk>>;
const view = (p: Props = {}) =>
  render(<QaAsk canPay={false} freeLeft={3} needsConsent isGuest {...p} />);

function fill(opts: { topic?: string; question?: string; consent?: boolean } = {}) {
  fireEvent.click(screen.getByRole("radio", { name: new RegExp(opts.topic ?? "Sự nghiệp") }));
  fireEvent.change(screen.getByLabelText("Câu hỏi của con"), { target: { value: opts.question ?? QUESTION } });
  const consent = screen.queryByRole("checkbox");
  if (consent && opts.consent !== false) fireEvent.click(consent);
}
const freeBtn = () => screen.getByRole("button", { name: /Hỏi miễn phí/ });
const sent = (n = 0) => JSON.parse(String((fetchMock.mock.calls[n][1] as RequestInit).body)) as Record<string, unknown>;

describe("QaAsk", () => {
  it("streams the answer, then shows the seal, the AI label, the disclaimer and the link back", async () => {
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(document.body.textContent).toContain("Quan Lộc của con sáng. Hãy kiên nhẫn.");
    expect(screen.getByRole("img", { name: "Ấn son" })).toBeTruthy();
    expect(screen.getByText("Lời ta chỉ để tham khảo.")).toBeTruthy();
    const body = sent();
    expect(fetchMock.mock.calls[0][0]).toBe("/api/qa/readings");
    expect(body).toMatchObject({ kind: "FREE", topic: "SuNghiep", question: QUESTION, ai_consent: true });
    expect(body.reading_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(screen.getByRole("link", { name: "Xem lại" }).getAttribute("href")).toBe(`/vi/hoi/${String(body.reading_id)}`);
    expect(screen.queryByLabelText("Câu hỏi nối tiếp")).toBeNull(); // FREE has no follow-ups
  });


  it("announces progress through one polite live region that exists before anything is asked", async () => {
    let release: (r: Response) => void = () => {};
    fetchMock.mockImplementation(() => new Promise<Response>((r) => (release = r)));
    view();
    const live = screen.getByTestId("qa-live");
    expect(live.getAttribute("aria-live")).toBe("polite");
    expect(live.textContent).toBe("");
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(live.textContent).toBe("Lão Thiên Cơ bắt đầu luận giải."));
    release(streamOf([META, sentence("Xong. "), DONE]));
    await waitFor(() => expect(live.textContent).toBe("Lão Thiên Cơ đã luận xong."));
    expect(screen.getByTestId("qa-live")).toBe(live); // the same node: a text change, not an insertion
    expect(document.querySelectorAll('[role="status"]')).toHaveLength(0);
  });

  it("announces a drop in the same live region", async () => {
    fetchMock.mockImplementation(async () => streamOf([META], "error"));
    view();
    const live = screen.getByTestId("qa-live");
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(live.textContent).toBe("Mất kết nối khi đang luận. Lời nhắn ở ngay sau câu hỏi."));
    expect(screen.getByText(DROP)).toBeTruthy(); // the visible text exists once
  });
  it("a double click sends one request", async () => {
    let release: (r: Response) => void = () => {};
    fetchMock.mockImplementation(() => new Promise<Response>((r) => (release = r)));
    view();
    fill();
    fireEvent.click(freeBtn());
    fireEvent.click(freeBtn());
    fireEvent.click(freeBtn());
    expect(fetchMock).toHaveBeenCalledTimes(1);
    release(streamOf([META, sentence("Xong. "), DONE]));
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("a stream that errors shows the drop message with a link to the reading, keeping what arrived", async () => {
    fetchMock.mockImplementation(async () => streamOf([META, sentence("Quan Lộc của con sáng. ")], "error"));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(DROP)).toBeTruthy());
    const id = String(sent().reading_id);
    expect(screen.getByRole("link", { name: "Xem lại" }).getAttribute("href")).toBe(`/vi/hoi/${id}`);
    expect(screen.queryByText(AI)).toBeNull();
    expect(screen.queryByRole("img", { name: "Ấn son" })).toBeNull();
  });

  it("a stream that ends without a terminal event is a drop too", async () => {
    fetchMock.mockImplementation(async () => streamOf([META, sentence("Quan Lộc. ")]));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(DROP)).toBeTruthy());
  });

  it("a retry after a drop reuses the reading id; a changed question gets a new one", async () => {
    fetchMock.mockImplementation(async () => streamOf([META], "error"));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(DROP)).toBeTruthy());
    fireEvent.click(freeBtn());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByText(DROP)).toBeTruthy());
    expect(sent(1).reading_id).toBe(sent(0).reading_id);
    fireEvent.change(screen.getByLabelText("Câu hỏi của con"), { target: { value: "Một câu hỏi hoàn toàn khác nhé?" } });
    fireEvent.click(freeBtn());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(sent(2).reading_id).not.toBe(sent(0).reading_id);
  });

  it("keeps Vietnamese intact when a chunk boundary falls inside a multibyte character", async () => {
    const bytes = enc.encode(META + sentence("Lão Thiên Cơ luận ấn 印 rất rõ. ") + DONE);
    const chunks: Uint8Array[] = [];
    for (let i = 0; i < bytes.length; i += 5) chunks.push(bytes.slice(i, i + 5));
    fetchMock.mockImplementation(async () => streamOf(chunks));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(document.body.textContent).toContain("Lão Thiên Cơ luận ấn 印 rất rõ.");
  });

  it("409 free_quota shows the message with a link to buy", async () => {
    fetchMock.mockImplementation(async () => problem(409, "free_quota"));
    view();
    fill();
    fireEvent.click(freeBtn());
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Hôm nay con đã hỏi đủ câu miễn phí.");
    expect(screen.getByRole("link", { name: "Nạp lượt" }).getAttribute("href")).toBe("/vi/mua");
    expect((freeBtn() as HTMLButtonElement).disabled).toBe(false); // the form is usable again
  });

  it("409 no_credits on a paid question says so, with the link to buy", async () => {
    fetchMock.mockImplementation(async () => problem(409, "no_credits"));
    view({ canPay: true, isGuest: false });
    fill();
    fireEvent.click(screen.getByRole("button", { name: "Dùng 1 lượt để hỏi" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Con đã hết lượt.");
    expect(sent()).toMatchObject({ kind: "PAID" });
  });

  it("an unreadable error body falls back to the default message", async () => {
    fetchMock.mockImplementation(async () => new Response("<html>", { status: 503 }));
    view();
    fill();
    fireEvent.click(freeBtn());
    expect((await screen.findByRole("alert")).textContent).toContain("Lão Thiên Cơ cần một lát.");
  });

  it("a guest sees the free button and a login link, never the paid button", () => {
    view({ isGuest: true, canPay: false });
    expect(screen.queryByRole("button", { name: "Dùng 1 lượt để hỏi" })).toBeNull();
    expect(screen.getByRole("link", { name: "Đăng nhập để nạp lượt" }).getAttribute("href")).toBe("/vi/dang-nhap?next=/vi/hoi");
    expect(freeBtn().textContent).toBe("Hỏi miễn phí (còn 3 câu hôm nay)");
    expect(screen.queryByText("Kèm 2 câu hỏi nối tiếp trong cùng chủ đề.")).toBeNull();
  });

  it("an account with credits sees both buttons and the follow-up note", () => {
    view({ isGuest: false, canPay: true });
    expect(screen.getByRole("button", { name: "Dùng 1 lượt để hỏi" })).toBeTruthy();
    expect(screen.getByText("Kèm 2 câu hỏi nối tiếp trong cùng chủ đề.")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Đăng nhập để nạp lượt" })).toBeNull();
  });

  it("an account without credits is pointed to buy", () => {
    view({ isGuest: false, canPay: false });
    expect(screen.getByRole("link", { name: "Nạp lượt" }).getAttribute("href")).toBe("/vi/mua");
  });

  it("disables the free button at 0 left", () => {
    view({ freeLeft: 0 });
    expect((freeBtn() as HTMLButtonElement).disabled).toBe(true);
  });

  it("needs the AI consent box when asked to, and sends nothing without it", () => {
    view();
    fill({ consent: false });
    fireEvent.click(freeBtn());
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("Con cần đồng ý");
    expect(screen.getByLabelText(/Con đồng ý gửi lá số ẩn danh và câu hỏi tới nhà cung cấp AI ở nước ngoài/)).toBeTruthy();
  });

  it("shows no consent box once consent is on record, and sends no ai_consent", async () => {
    view({ needsConsent: false });
    expect(screen.queryByRole("checkbox")).toBeNull();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(sent()).not.toHaveProperty("ai_consent");
  });

  it("needs a topic and at least 5 characters, and counts them", () => {
    view();
    expect(screen.getByText("0/500")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Câu hỏi của con"), { target: { value: "abc" } });
    expect(screen.getByText("3/500")).toBeTruthy();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(freeBtn());
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getAllByRole("alert").map((a) => a.textContent).join("|")).toContain("Con chọn một chủ đề");
    fireEvent.click(screen.getByRole("radio", { name: /Tài lộc/ }));
    fireEvent.click(freeBtn());
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getAllByRole("alert").map((a) => a.textContent).join("|")).toContain("ít nhất 5");
  });

  it("shows a refusal's text and disclaimer, no seal and no AI label", async () => {
    fetchMock.mockImplementation(async () =>
      streamOf([META, ev("refused", { reading_id: "x", reason: "medical", text: "Chuyện bệnh tật ta không luận.", disclaimer: "Hãy hỏi thầy thuốc." })]),
    );
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText("Chuyện bệnh tật ta không luận.")).toBeTruthy());
    expect(screen.getByText("Hãy hỏi thầy thuốc.")).toBeTruthy();
    expect(screen.queryByRole("img", { name: "Ấn son" })).toBeNull();
  });

  it("shows queued text with the promise of a notice in the history and a link to the reading", async () => {
    fetchMock.mockImplementation(async () =>
      streamOf([META, ev("queued", { reading_id: "x", retry_until: null, text: "Thầy đang bận, bài luận sẽ có sau." })]),
    );
    view({ canPay: true, isGuest: false });
    fill();
    fireEvent.click(screen.getByRole("button", { name: "Dùng 1 lượt để hỏi" }));
    await waitFor(() => expect(screen.getByText("Thầy đang bận, bài luận sẽ có sau.")).toBeTruthy());
    expect(screen.getByText("Ta sẽ báo trong Lịch sử khi bài luận xong.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Xem trạng thái" }).getAttribute("href")).toBe(`/vi/hoi/${String(sent().reading_id)}`);
  });

  it("shows busy text, and the default message for an error event", async () => {
    fetchMock.mockImplementation(async () => streamOf([META, ev("busy", { text: "Thầy đang bận, con thử lại sau." })]));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText("Thầy đang bận, con thử lại sau.")).toBeTruthy());
    cleanup();
    fetchMock.mockImplementation(async () => streamOf([META, ev("error", { reason: "internal" })]));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText("Lão Thiên Cơ cần một lát.")).toBeTruthy());
  });

  it("offers follow-ups after a paid answer when some are left", async () => {
    fetchMock.mockImplementation(async () =>
      streamOf([ev("meta", { reading_id: "x", kind: "PAID", topic: "SuNghiep", follow_ups_left: 2 }), sentence("Con ơi. "), DONE]),
    );
    view({ canPay: true, isGuest: false });
    fill();
    fireEvent.click(screen.getByRole("button", { name: "Dùng 1 lượt để hỏi" }));
    await waitFor(() => expect(screen.getByLabelText("Câu hỏi nối tiếp")).toBeTruthy());
    expect(screen.getByText("Còn 2 câu hỏi nối tiếp trong chủ đề này.")).toBeTruthy();
  });

  it("lets the visitor ask another question after an answer", async () => {
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Hỏi câu khác" }));
    expect(screen.queryByText(AI)).toBeNull();
    fireEvent.change(screen.getByLabelText("Câu hỏi của con"), { target: { value: "Câu hỏi thứ hai của con nhé." } });
    fireEvent.click(freeBtn());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(sent(1).reading_id).not.toBe(sent(0).reading_id);
  });
});

describe("QaAsk: what the server must re-read", () => {
  it("refreshes the page once an answer is done (header balance, free count)", async () => {
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("refreshes when a paid question is queued (the credit is taken)", async () => {
    fetchMock.mockImplementation(async () => streamOf([META, ev("queued", { reading_id: "x", retry_until: null, text: "Thầy đang bận." })]));
    view({ canPay: true, isGuest: false });
    fill();
    fireEvent.click(screen.getByRole("button", { name: "Dùng 1 lượt để hỏi" }));
    await waitFor(() => expect(screen.getByText("Thầy đang bận.")).toBeTruthy());
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("does not refresh for a refusal or a failure (nothing was used)", async () => {
    fetchMock.mockImplementation(async () => streamOf([META, ev("refused", { reading_id: "x", reason: "medical", text: "Ta không luận.", disclaimer: "d" })]));
    view();
    fill();
    fireEvent.click(freeBtn());
    await waitFor(() => expect(screen.getByText("Ta không luận.")).toBeTruthy());
    expect(refresh).not.toHaveBeenCalled();
  });
});
