import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { FollowUpBox } from "./FollowUpBox";

const RID = "33333333-3333-4333-8333-333333333333";
const enc = new TextEncoder();
const SSE =
  'event: meta\ndata: {"follow_ups_left":1}\n\nevent: delta\ndata: {"text":"Câu nối tiếp được luận."}\n\nevent: done\ndata: {"reading_id":"x","seq":1,"disclaimer":"Lưu ý của ta."}\n\n';
const sse = () => new Response(enc.encode(SSE), { status: 200, headers: { "content-type": "text/event-stream" } });
const AI = "Nội dung do AI biên soạn từ kho tư liệu.";

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.stubGlobal("matchMedia", (q: string) => ({
    matches: q.includes("reduce"),
    media: q,
    addEventListener() {},
    removeEventListener() {},
  }));
  fetchMock = vi.fn(async () => sse());
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const ask = (text = "Còn chuyện tiền bạc thì sao ạ?") => {
  fireEvent.change(screen.getByLabelText("Câu hỏi nối tiếp"), { target: { value: text } });
  fireEvent.click(screen.getByRole("button", { name: "Hỏi tiếp" }));
};

describe("FollowUpBox", () => {
  it("renders nothing when no follow-up is left", () => {
    const { container } = render(<FollowUpBox readingId={RID} left={0} />);
    expect(container.textContent).toBe("");
  });

  it("announces how many follow-ups are left", () => {
    render(<FollowUpBox readingId={RID} left={2} />);
    expect(screen.getByText("Còn 2 câu hỏi nối tiếp trong chủ đề này.")).toBeTruthy();
  });


  it("announces a follow-up's progress in a polite live region mounted before it is asked", async () => {
    render(<FollowUpBox readingId={RID} left={2} />);
    const live = screen.getByTestId("qa-live");
    expect(live.getAttribute("aria-live")).toBe("polite");
    expect(live.textContent).toBe("");
    ask();
    await waitFor(() => expect(live.textContent).toBe("Lão Thiên Cơ đã luận xong."));
    expect(screen.getByTestId("qa-live")).toBe(live);
  });
  it("posts one message_id on a double click, to the follow-ups route", async () => {
    render(<FollowUpBox readingId={RID} left={2} />);
    fireEvent.change(screen.getByLabelText("Câu hỏi nối tiếp"), { target: { value: "Còn chuyện tiền bạc thì sao ạ?" } });
    const btn = screen.getByRole("button", { name: "Hỏi tiếp" });
    fireEvent.click(btn);
    fireEvent.click(btn);
    await waitFor(() => expect(screen.getByText("Lưu ý của ta.")).toBeTruthy());
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`/api/qa/readings/${RID}/follow-ups`);
    const sent = JSON.parse(String(init.body)) as Record<string, string>;
    expect(Object.keys(sent).sort()).toEqual(["message_id", "question"]);
    expect(sent.message_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(sent.question).toBe("Còn chuyện tiền bạc thì sao ạ?");
  });

  it("shows the answer, the AI label, and the new count from the stream", async () => {
    render(<FollowUpBox readingId={RID} left={2} />);
    ask();
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(screen.getByText("Còn 1 câu hỏi nối tiếp trong chủ đề này.")).toBeTruthy();
    expect(document.body.textContent).toContain("Câu nối tiếp được luận.");
  });

  it("disappears when the last follow-up is used", async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(
          enc.encode(
            'event: meta\ndata: {"follow_ups_left":0}\n\nevent: delta\ndata: {"text":"Xong."}\n\nevent: done\ndata: {"disclaimer":"d"}\n\n',
          ),
          { status: 200 },
        ),
    );
    render(<FollowUpBox readingId={RID} left={1} />);
    ask();
    await waitFor(() => expect(screen.getByText(AI)).toBeTruthy());
    expect(screen.queryByRole("button", { name: "Hỏi tiếp" })).toBeNull();
  });

  it("explains a refusal and keeps the box usable", async () => {
    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ reason: "reading_in_progress" }), { status: 409 }));
    render(<FollowUpBox readingId={RID} left={2} />);
    ask();
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Câu này ta đang luận, con chờ chút."));
    expect((screen.getByRole("button", { name: "Hỏi tiếp" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("refuses a too short question without calling the server", () => {
    render(<FollowUpBox readingId={RID} left={2} />);
    ask("ngắn");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("ít nhất 5");
  });
});
