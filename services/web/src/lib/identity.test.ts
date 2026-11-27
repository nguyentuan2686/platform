import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as id from "@/lib/identity";
import { UpstreamProblem } from "@/lib/http";

type Call = { url: string; method: string; headers: Headers; body: unknown };
let calls: Call[];
let next: () => Response;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

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
      });
      return next();
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.INTERNAL_API_TOKEN;
});

const last = () => calls[calls.length - 1];
const BASE = "http://localhost:8084";

describe("identity client", () => {
  it("creates a guest without a bearer", async () => {
    next = () => json(201, { session_token: "t", guest_id: "g", expires_at: "2027-01-01T00:00:00Z" });
    expect((await id.createGuest()).guest_id).toBe("g");
    expect(last()).toMatchObject({ url: `${BASE}/guests`, method: "POST" });
    expect(last().headers.get("x-internal-token")).toBe("local-test-internal");
    expect(last().headers.has("authorization")).toBe(false);
  });

  it("calls /me and the profile endpoints with the bearer", async () => {
    next = () => json(200, { kind: "guest", guest_id: "g" });
    await id.me("b1");
    expect(last()).toMatchObject({ url: `${BASE}/me`, method: "GET" });
    expect(last().headers.get("authorization")).toBe("Bearer b1");

    const profile = { id: "p", label: "L", birth: { birth: "1990-01-15T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" }, calendar: "VN", locked: false };
    next = () => json(200, profile);
    expect(await id.getProfile("b1")).toEqual(profile);
    expect(last()).toMatchObject({ url: `${BASE}/me/profile`, method: "GET" });

    await id.putProfile("b1", { label: "L", birth: profile.birth as id.ProfileBirth, calendar: "VN" });
    expect(last()).toMatchObject({ url: `${BASE}/me/profile`, method: "PUT", body: { label: "L", birth: profile.birth, calendar: "VN" } });

    next = () => new Response(null, { status: 204 });
    await id.deleteProfile("b1");
    expect(last()).toMatchObject({ url: `${BASE}/me/profile`, method: "DELETE" });
  });

  it("maps a missing profile or share to null", async () => {
    next = () => json(404, { status: 404 });
    expect(await id.getProfile("b")).toBeNull();
    expect(await id.getShare("b")).toBeNull();
    expect(await id.resolveShare("AAAAAAAAAAAAAAAAAAAAAA")).toBeNull();
  });

  it("does not call upstream for a malformed share id", async () => {
    expect(await id.resolveShare("../me/profile")).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it("propagates other problems", async () => {
    next = () => json(401, { status: 401 });
    await expect(id.getProfile("b")).rejects.toBeInstanceOf(UpstreamProblem);
  });

  it("reads entitlements and ledger", async () => {
    next = () => json(200, { credits: 1, pass: null, features: [] });
    expect((await id.entitlements("b")).credits).toBe(1);
    expect(last().url).toBe(`${BASE}/me/entitlements`);
    next = () => json(200, []);
    expect(await id.ledger("b")).toEqual([]);
    expect(last().url).toBe(`${BASE}/me/ledger`);
  });

  it("requests and verifies magic links, with an optional guest bearer", async () => {
    next = () => json(202, { status: "sent_if_allowed" });
    await id.requestMagicLink("a@test.invalid");
    expect(last()).toMatchObject({ url: `${BASE}/auth/magic-links`, method: "POST", body: { email: "a@test.invalid", lang: "vi" } });
    expect(last().headers.has("authorization")).toBe(false);
    await id.requestMagicLink("a@test.invalid", "guest-b");
    expect(last().headers.get("authorization")).toBe("Bearer guest-b");
    expect(last().headers.has("x-client-ip")).toBe(false);
    await id.requestMagicLink("a@test.invalid", undefined, "203.0.113.7");
    expect(last().headers.get("x-client-ip")).toBe("203.0.113.7");

    next = () => json(200, { session_token: "s", account_id: "a", expires_at: "2027-01-01T00:00:00Z", created: true });
    expect((await id.verifyMagicLink("tok", "guest-b")).account_id).toBe("a");
    expect(last()).toMatchObject({ url: `${BASE}/auth/magic-links/verify`, method: "POST", body: { token: "tok" } });
    expect(last().headers.get("authorization")).toBe("Bearer guest-b");
  });

  it("starts and completes Google login", async () => {
    next = () => json(200, { authorization_url: "https://accounts.example/x", state: "s" });
    await id.googleAuthorization("http://localhost:3001/vi/dang-nhap/google?x=1&y");
    expect(last().url).toBe(
      `${BASE}/auth/google/authorization?redirect_uri=${encodeURIComponent("http://localhost:3001/vi/dang-nhap/google?x=1&y")}`,
    );
    expect(last().method).toBe("GET");
    next = () => json(200, { session_token: "s", account_id: "a", expires_at: "2027-01-01T00:00:00Z", created: false });
    await id.googleCallback("code", "state", "g");
    expect(last()).toMatchObject({ url: `${BASE}/auth/google/callback`, method: "POST", body: { code: "code", state: "state" } });
    expect(last().headers.get("authorization")).toBe("Bearer g");
  });

  it("logs out and manages share links", async () => {
    next = () => new Response(null, { status: 204 });
    await id.logout("b");
    expect(last()).toMatchObject({ url: `${BASE}/sessions/current`, method: "DELETE" });
    next = () => json(200, { share_id: "AAAAAAAAAAAAAAAAAAAAAA", created_at: "2026-11-25T00:00:00Z" });
    await id.createShare("b");
    expect(last()).toMatchObject({ url: `${BASE}/me/profile/share`, method: "PUT" });
    await id.getShare("b");
    expect(last()).toMatchObject({ url: `${BASE}/me/profile/share`, method: "GET" });
    next = () => new Response(null, { status: 204 });
    await id.deleteShare("b");
    expect(last()).toMatchObject({ url: `${BASE}/me/profile/share`, method: "DELETE" });
    next = () => json(200, { birth: { birth: "1990-01-15T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" }, calendar: "VN" });
    await id.resolveShare("AbCdEfGhIjKlMnOpQrSt_-");
    expect(last()).toMatchObject({ url: `${BASE}/shares/AbCdEfGhIjKlMnOpQrSt_-`, method: "GET" });
    expect(last().headers.has("authorization")).toBe(false);
  });
});
