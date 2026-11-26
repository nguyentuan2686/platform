import { afterEach, describe, expect, it } from "vitest";
import { assertSameOrigin, forbidden, ForbiddenOrigin } from "@/lib/sameOrigin";

const req = (headers: Record<string, string>) =>
  new Request("http://localhost:3001/api/x", { method: "POST", headers });

afterEach(() => {
  delete process.env.WEB_ORIGIN;
});

describe("assertSameOrigin", () => {
  it("accepts an Origin equal to WEB_ORIGIN", () => {
    expect(() => assertSameOrigin(req({ origin: "http://localhost:3001" }))).not.toThrow();
    process.env.WEB_ORIGIN = "https://tuvi.example";
    expect(() => assertSameOrigin(req({ origin: "https://tuvi.example" }))).not.toThrow();
  });

  it("rejects another Origin, even with Sec-Fetch-Site same-origin", () => {
    expect(() => assertSameOrigin(req({ origin: "https://evil.example" }))).toThrow(ForbiddenOrigin);
    expect(() => assertSameOrigin(req({ origin: "http://localhost:3001.evil.example" }))).toThrow(ForbiddenOrigin);
    expect(() => assertSameOrigin(req({ origin: "null", "sec-fetch-site": "same-origin" }))).toThrow(ForbiddenOrigin);
  });

  it("accepts no Origin with Sec-Fetch-Site: same-origin", () => {
    expect(() => assertSameOrigin(req({ "sec-fetch-site": "same-origin" }))).not.toThrow();
  });

  it("rejects no Origin with Sec-Fetch-Site: cross-site or same-site", () => {
    expect(() => assertSameOrigin(req({ "sec-fetch-site": "cross-site" }))).toThrow(ForbiddenOrigin);
    expect(() => assertSameOrigin(req({ "sec-fetch-site": "same-site" }))).toThrow(ForbiddenOrigin);
  });

  it("rejects a request with neither header", () => {
    expect(() => assertSameOrigin(req({}))).toThrow(ForbiddenOrigin);
  });
});

describe("forbidden", () => {
  it("is a 403 JSON {reason: forbidden}", async () => {
    const res = forbidden();
    expect(res.status).toBe(403);
    expect(res.headers.get("content-type")).toContain("application/json");
    expect(await res.json()).toEqual({ reason: "forbidden" });
  });
});
