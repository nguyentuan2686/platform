import { afterEach, describe, expect, it, vi } from "vitest";
import { getBasicReading, getChart, getToday, UpstreamError, UpstreamValidation } from "@/lib/upstream";
import type { Birth } from "@/lib/types";

const birth: Birth = { birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male", calendar: "VN" };

function stubFetch(impl: (url: string, init: RequestInit) => Promise<Response>) {
  const f = vi.fn(impl);
  vi.stubGlobal("fetch", f);
  return f;
}
const json = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": type } });

describe("upstream", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    delete process.env.CHART_SVC_URL;
    delete process.env.READING_URL;
  });

  it("posts the birth to chart-svc with a 5 s timeout signal", async () => {
    const spy = vi.spyOn(AbortSignal, "timeout");
    const f = stubFetch(async () => json(200, { school: "x" }));
    const chart = await getChart(birth);
    expect(chart).toEqual({ school: "x" });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("http://localhost:8082/tuvi/chart");
    expect(JSON.parse(String(init.body))).toEqual(birth);
    expect(spy).toHaveBeenCalledWith(5000);
  });

  it("uses env base URLs and sends date for today", async () => {
    process.env.READING_URL = "http://reading:1";
    const f = stubFetch(async () => json(200, { date: "2026-11-25" }));
    await getToday(birth, "2026-11-25");
    expect(f.mock.calls[0][0]).toBe("http://reading:1/tuvi/readings/today");
    expect(JSON.parse(String(f.mock.calls[0][1].body))).toEqual({ ...birth, date: "2026-11-25" });
    await getBasicReading(birth);
    expect(f.mock.calls[1][0]).toBe("http://reading:1/tuvi/readings/basic");
  });

  it("maps 400 problem+json errors to UpstreamValidation(fields)", async () => {
    stubFetch(async () =>
      json(400, { type: "urn:problem:validation-error", errors: [{ field: "birth", message: "bad" }] }, "application/problem+json"),
    );
    const err = await getChart(birth).catch((e) => e);
    expect(err).toBeInstanceOf(UpstreamValidation);
    expect(err.fields).toEqual({ birth: "bad" });
  });

  it("maps 500, 503, network failure, timeout and bad bodies to UpstreamError", async () => {
    stubFetch(async () => json(500, {}));
    await expect(getChart(birth)).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => json(503, {}));
    await expect(getBasicReading(birth)).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => {
      throw new TypeError("fetch failed");
    });
    await expect(getChart(birth)).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => {
      throw new DOMException("timed out", "TimeoutError");
    });
    await expect(getChart(birth)).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => new Response("not json", { status: 200 }));
    await expect(getChart(birth)).rejects.toBeInstanceOf(UpstreamError);
  });

  it("never writes the birth data to stdout", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    stubFetch(async () => {
      throw new TypeError(`fetch failed for ${birth.birth}`);
    });
    await getChart(birth).catch(() => {});
    stubFetch(async () => json(500, { detail: birth.birth }));
    await getToday(birth, "2026-11-25").catch(() => {});
    expect(write).toHaveBeenCalled();
    const out = write.mock.calls.map((c) => String(c[0])).join("");
    expect(out).not.toContain("1994");
    expect(out).not.toContain("08:30");
    expect(out).not.toContain("male");
    expect(out).not.toContain("Ho_Chi_Minh");
  });
});
