import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as qa from "@/lib/qa";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

type Call = { url: string; method: string; headers: Headers; body: unknown; signal?: AbortSignal | null };
let calls: Call[];
let next: () => Response | Promise<Response>;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const sse = () => new Response("event: meta\ndata: {}\n\n", { status: 200, headers: { "content-type": "text/event-stream" } });

beforeEach(() => {
  calls = [];
  next = () => json(200, {});
  process.env.INTERNAL_API_TOKEN = "local-test-internal";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({
        url,
        method: String(init.method),
        headers: new Headers(init.headers),
        body: init.body ? JSON.parse(String(init.body)) : undefined,
        signal: init.signal,
      });
      return next();
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete process.env.INTERNAL_API_TOKEN;
  delete process.env.QA_STREAM_TIMEOUT_MS;
});

const last = () => calls[calls.length - 1];
const BASE = "http://localhost:8083";
const RID = "33333333-3333-3333-3333-333333333333";
const ask = { reading_id: RID, kind: "FREE" as const, topic: "TinhDuyen" as const, question: "Khi nào tôi gặp duyên?", lang: "vi" as const };

describe("qa streams", () => {
  it("returns the raw SSE response of an ask, with the stream timeout", async () => {
    process.env.QA_STREAM_TIMEOUT_MS = "777";
    const spy = vi.spyOn(AbortSignal, "timeout");
    next = sse;
    const ctrl = new AbortController();
    const res = await qa.askStream("b", ask, ctrl.signal);
    expect(res.status).toBe(200);
    expect(await res.text()).toContain("event: meta");
    expect(last()).toMatchObject({ url: `${BASE}/tuvi/qa/readings`, method: "POST", body: ask });
    expect(last().headers.get("authorization")).toBe("Bearer b");
    expect(last().headers.get("x-internal-token")).toBe("local-test-internal");
    expect(last().headers.get("accept")).toContain("text/event-stream");
    expect(spy).toHaveBeenCalledWith(777);
    ctrl.abort();
    expect(last().signal?.aborted).toBe(true);
  });

  it("posts follow-ups", async () => {
    next = sse;
    await qa.followUpStream("b", RID, { message_id: RID, question: "Còn gì nữa không ạ?" }, new AbortController().signal);
    expect(last()).toMatchObject({ url: `${BASE}/tuvi/qa/readings/${RID}/follow-ups`, method: "POST" });
  });

  it("throws UpstreamProblem / UpstreamValidation / UpstreamError on non-200", async () => {
    next = () => json(409, { reason: "free_quota" });
    const e1 = await qa.askStream("b", ask, new AbortController().signal).catch((e) => e);
    expect(e1).toBeInstanceOf(UpstreamProblem);
    expect(e1.reason).toBe("free_quota");
    next = () => json(400, { errors: [{ field: "question", message: "short" }] });
    const e2 = await qa.askStream("b", ask, new AbortController().signal).catch((e) => e);
    expect(e2).toBeInstanceOf(UpstreamValidation);
    expect(e2.fields).toEqual({ question: "short" });
    next = () => json(503, {});
    await expect(qa.askStream("b", ask, new AbortController().signal)).rejects.toBeInstanceOf(UpstreamError);
    next = () => {
      throw new TypeError("down");
    };
    await expect(qa.followUpStream("b", RID, { message_id: RID, question: "xxxxx" }, new AbortController().signal)).rejects.toBeInstanceOf(
      UpstreamError,
    );
  });
});

describe("qa json", () => {
  it("lists, reads and deletes readings, reads the free quota", async () => {
    next = () => json(200, { readings: [] });
    await qa.listReadings("b");
    expect(last()).toMatchObject({ url: `${BASE}/tuvi/qa/readings`, method: "GET" });
    next = () => json(200, { reading_id: RID });
    await qa.getReading("b", RID);
    expect(last().url).toBe(`${BASE}/tuvi/qa/readings/${RID}`);
    next = () => new Response(null, { status: 204 });
    await qa.deleteReadings("b");
    expect(last()).toMatchObject({ url: `${BASE}/tuvi/qa/readings`, method: "DELETE" });
    next = () => json(200, { limit: 3, used: 1, remaining: 2 });
    expect((await qa.freeQuota("b")).remaining).toBe(2);
    expect(last().url).toBe(`${BASE}/tuvi/qa/free-quota`);
  });

  it("merges a guest's history without a bearer", async () => {
    next = () => json(200, { moved: 2 });
    expect(await qa.mergeGuest("g", "a")).toEqual({ moved: 2 });
    expect(last()).toMatchObject({ url: `${BASE}/internal/qa/merge`, method: "POST", body: { guest_id: "g", account_id: "a" } });
    expect(last().headers.has("authorization")).toBe(false);
    expect(last().headers.get("x-internal-token")).toBe("local-test-internal");
  });
});
