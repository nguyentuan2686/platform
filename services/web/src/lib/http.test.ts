import { afterEach, describe, expect, it, vi } from "vitest";
import { callJson, UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

function stubFetch(impl: (url: string, init: RequestInit) => Promise<Response>) {
  const f = vi.fn(impl);
  vi.stubGlobal("fetch", f);
  return f;
}
const json = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": type } });
const headersOf = (init: RequestInit) => new Headers(init.headers);

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete process.env.INTERNAL_API_TOKEN;
  delete process.env.ADMIN_API_TOKEN;
  delete process.env.IDENTITY_SVC_URL;
});

describe("callJson", () => {
  it("omits the internal token header when not configured", async () => {
    delete process.env.INTERNAL_API_TOKEN;
    const f = stubFetch(async () => json(200, { ok: true }));
    const out = await callJson<{ ok: boolean }>("identity", "/me");
    expect(out).toEqual({ ok: true });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("http://localhost:8084/me");
    expect(init.method).toBe("GET");
    expect(headersOf(init).has("x-internal-token")).toBe(false);
    expect(headersOf(init).has("authorization")).toBe(false);
    expect(init.body).toBeUndefined();
  });

  it("adds the internal token, bearer, admin token and JSON body when asked", async () => {
    process.env.INTERNAL_API_TOKEN = "local-test-internal";
    process.env.ADMIN_API_TOKEN = "local-test-admin";
    process.env.IDENTITY_SVC_URL = "http://identity:9";
    const f = stubFetch(async () => json(200, {}));
    await callJson("identity", "/me/profile", { method: "PUT", bearer: "tok", body: { a: 1 } });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("http://identity:9/me/profile");
    const h = headersOf(init);
    expect(h.get("x-internal-token")).toBe("local-test-internal");
    expect(h.get("authorization")).toBe("Bearer tok");
    expect(h.get("content-type")).toBe("application/json");
    expect(h.has("x-admin-token")).toBe(false);
    expect(JSON.parse(String(init.body))).toEqual({ a: 1 });
    expect(init.method).toBe("PUT");

    await callJson("payment", "/admin/refund-requests", { admin: true });
    expect(headersOf(f.mock.calls[1][1]).get("x-admin-token")).toBe("local-test-admin");
  });

  it("uses a 5 s timeout by default and honours timeoutMs", async () => {
    const spy = vi.spyOn(AbortSignal, "timeout");
    stubFetch(async () => json(200, {}));
    await callJson("chart", "/x");
    expect(spy).toHaveBeenLastCalledWith(5000);
    await callJson("chart", "/x", { timeoutMs: 50 });
    expect(spy).toHaveBeenLastCalledWith(50);
  });

  it("maps 204 to undefined", async () => {
    stubFetch(async () => new Response(null, { status: 204 }));
    expect(await callJson("identity", "/me/profile", { method: "DELETE", bearer: "t" })).toBeUndefined();
  });

  it("maps 400 to UpstreamValidation with field messages", async () => {
    stubFetch(async () =>
      json(400, { errors: [{ field: "email", message: "bad" }, { field: "email", message: "x" }] }, "application/problem+json"),
    );
    const err = await callJson<never>("identity", "/auth/magic-links", { method: "POST", body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(UpstreamValidation);
    expect(err.fields).toEqual({ email: "bad" });
  });

  it("maps 400 with an unreadable body to UpstreamValidation without fields", async () => {
    stubFetch(async () => new Response("nope", { status: 400 }));
    const err = await callJson<never>("identity", "/x", { method: "POST", body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(UpstreamValidation);
    expect(err.fields).toEqual({});
  });

  it("maps 409 {reason} to UpstreamProblem(409, reason)", async () => {
    stubFetch(async () => json(409, { status: 409, reason: "no_credits" }, "application/problem+json"));
    const err = await callJson<never>("reading", "/tuvi/qa/readings", { method: "POST", body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(UpstreamProblem);
    expect(err.status).toBe(409);
    expect(err.reason).toBe("no_credits");
  });

  it("maps 401, 403 and 404 to UpstreamProblem, with no reason when absent", async () => {
    for (const status of [401, 403, 404] as const) {
      stubFetch(async () => new Response("", { status }));
      const err = await callJson<never>("identity", "/me").catch((e) => e);
      expect(err).toBeInstanceOf(UpstreamProblem);
      expect(err.status).toBe(status);
      expect(err.reason).toBeUndefined();
    }
  });

  it("maps 5xx, other 4xx, network errors, timeouts and bad JSON to UpstreamError", async () => {
    stubFetch(async () => json(500, {}));
    await expect(callJson("identity", "/me")).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => json(503, {}));
    await expect(callJson("identity", "/me")).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => json(429, {}));
    await expect(callJson("identity", "/me")).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => {
      throw new TypeError("fetch failed");
    });
    await expect(callJson("identity", "/me")).rejects.toBeInstanceOf(UpstreamError);
    stubFetch(async () => new Response("not json", { status: 200 }));
    await expect(callJson("identity", "/me")).rejects.toBeInstanceOf(UpstreamError);
  });

  it("maps a real timeout to UpstreamError", async () => {
    stubFetch(
      (_u, init) =>
        new Promise((_res, rej) => {
          init.signal?.addEventListener("abort", () => rej(init.signal?.reason));
        }),
    );
    await expect(callJson("identity", "/me", { timeoutMs: 20 })).rejects.toBeInstanceOf(UpstreamError);
  });

  it("logs only the upstream name and status: no bearer, body, query or headers", async () => {
    process.env.INTERNAL_API_TOKEN = "local-test-secret-token";
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    stubFetch(async () => json(500, { detail: "1994-03-12T08:30" }));
    await callJson("payment", "/orders?account_id=acc-777", {
      method: "POST",
      bearer: "bearer-secret-xyz",
      body: { birth: "1994-03-12T08:30", email: "someone@test.invalid" },
    }).catch(() => {});
    stubFetch(async () => {
      throw new TypeError("fetch failed bearer-secret-xyz 1994-03-12T08:30");
    });
    await callJson("identity", "/me", { bearer: "bearer-secret-xyz", body: { birth: "1994-03-12T08:30" }, method: "POST" }).catch(
      () => {},
    );
    expect(write).toHaveBeenCalled();
    const out = write.mock.calls.map((c) => String(c[0])).join("");
    for (const bad of ["bearer-secret-xyz", "1994", "08:30", "someone@", "acc-777", "local-test-secret-token", "/orders", "/me"]) {
      expect(out).not.toContain(bad);
    }
    const first = JSON.parse(out.split("\n")[0]);
    expect(first.upstream).toBe("payment-svc");
    expect(first.status).toBe("500");
  });
});
