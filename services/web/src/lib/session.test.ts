import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type SetCall = { name: string; value: string; opts: Record<string, unknown> };
const jar = vi.hoisted(() => ({ values: new Map<string, string>(), sets: [] as SetCall[], deletes: [] as unknown[] }));

vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (jar.values.has(name) ? { name, value: jar.values.get(name) } : undefined),
    set: (name: string, value: string, opts: Record<string, unknown>) => {
      jar.sets.push({ name, value, opts });
      jar.values.set(name, value);
    },
    delete: (arg: unknown) => {
      jar.deletes.push(arg);
      const name = typeof arg === "string" ? arg : (arg as { name: string }).name;
      jar.values.delete(name);
    },
  }),
}));

const identity = vi.hoisted(() => ({
  createGuest: vi.fn(),
  me: vi.fn(),
  entitlements: vi.fn(),
}));
vi.mock("@/lib/identity", () => identity);

import {
  clearSessionCookie,
  ensureSession,
  getViewer,
  readSessionToken,
  sessionCookieName,
  setSessionCookie,
} from "@/lib/session";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

const ENT = { credits: 3, pass: null, features: [] };

beforeEach(() => {
  jar.values.clear();
  jar.sets.length = 0;
  jar.deletes.length = 0;
  identity.createGuest.mockReset();
  identity.me.mockReset();
  identity.entitlements.mockReset();
});
afterEach(() => {
  delete process.env.COOKIE_SECURE;
  delete process.env.ADMIN_ACCOUNT_IDS;
  vi.useRealTimers();
});

describe("session cookie", () => {
  it("is __Host-tv_sid with Secure when COOKIE_SECURE=1", async () => {
    process.env.COOKIE_SECURE = "1";
    expect(sessionCookieName()).toBe("__Host-tv_sid");
    await setSessionCookie("tok", new Date(Date.now() + 3600_000).toISOString());
    expect(jar.sets[0].name).toBe("__Host-tv_sid");
    expect(jar.sets[0].opts.secure).toBe(true);
  });

  it("is tv_sid without Secure otherwise", async () => {
    expect(sessionCookieName()).toBe("tv_sid");
    await setSessionCookie("tok", new Date(Date.now() + 3600_000).toISOString());
    expect(jar.sets[0].name).toBe("tv_sid");
    expect(jar.sets[0].opts.secure).toBe(false);
  });

  it("is HttpOnly, SameSite=Lax, Path=/ and lives until expires_at", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-25T00:00:00Z"));
    await setSessionCookie("tok", "2026-11-26T00:00:00Z");
    const { opts, value } = jar.sets[0];
    expect(value).toBe("tok");
    expect(opts.httpOnly).toBe(true);
    expect(opts.sameSite).toBe("lax");
    expect(opts.path).toBe("/");
    expect(opts.maxAge).toBe(86400);
    expect(opts).not.toHaveProperty("domain");
  });

  it("never sets a negative max-age", async () => {
    await setSessionCookie("tok", "2000-01-01T00:00:00Z");
    expect(jar.sets[0].opts.maxAge).toBe(0);
  });

  it("falls back to one day for an unparsable expires_at", async () => {
    await setSessionCookie("tok", "not-a-date");
    expect(jar.sets[0].opts.maxAge).toBe(86400);
  });

  it("reads and clears the token", async () => {
    expect(await readSessionToken()).toBeUndefined();
    jar.values.set("tv_sid", "abc");
    expect(await readSessionToken()).toBe("abc");
    await clearSessionCookie();
    expect(await readSessionToken()).toBeUndefined();
    expect(jar.deletes).toHaveLength(1);
  });
});

describe("ensureSession", () => {
  it("returns the existing cookie without creating a guest", async () => {
    jar.values.set("tv_sid", "existing");
    expect(await ensureSession()).toBe("existing");
    expect(identity.createGuest).not.toHaveBeenCalled();
  });

  it("creates a guest and sets the cookie when there is none", async () => {
    identity.createGuest.mockResolvedValue({
      session_token: "new-token",
      guest_id: "g-1",
      expires_at: new Date(Date.now() + 180 * 86400_000).toISOString(),
    });
    expect(await ensureSession()).toBe("new-token");
    expect(jar.sets[0].name).toBe("tv_sid");
    expect(jar.sets[0].value).toBe("new-token");
  });

  it("creates a fresh guest when asked to renew a stale cookie", async () => {
    jar.values.set("tv_sid", "stale");
    identity.createGuest.mockResolvedValue({ session_token: "fresh", guest_id: "g", expires_at: "2099-01-01T00:00:00Z" });
    expect(await ensureSession({ renew: true })).toBe("fresh");
  });
});

describe("getViewer", () => {
  it("is anon without a cookie and calls nothing", async () => {
    expect(await getViewer()).toEqual({ kind: "anon" });
    expect(identity.me).not.toHaveBeenCalled();
  });

  it("maps a guest", async () => {
    jar.values.set("tv_sid", "gt");
    identity.me.mockResolvedValue({ kind: "guest", guest_id: "g-1" });
    expect(await getViewer()).toEqual({ kind: "guest", guestId: "g-1", token: "gt" });
    expect(identity.me).toHaveBeenCalledWith("gt");
    expect(identity.entitlements).not.toHaveBeenCalled();
  });

  it("maps an account with entitlements; isAdmin only for listed ids", async () => {
    jar.values.set("tv_sid", "at");
    process.env.ADMIN_ACCOUNT_IDS = "other-id";
    identity.me.mockResolvedValue({ kind: "account", account_id: "acc-1", email_masked: "t***@e***.com" });
    identity.entitlements.mockResolvedValue(ENT);
    expect(await getViewer()).toEqual({
      kind: "account",
      accountId: "acc-1",
      emailMasked: "t***@e***.com",
      token: "at",
      entitlements: ENT,
      isAdmin: false,
    });
    process.env.ADMIN_ACCOUNT_IDS = "other-id,acc-1";
    expect((await getViewer()).kind === "account" && (await getViewer())).toMatchObject({ isAdmin: true });
  });

  it("is anon when /me answers 401", async () => {
    jar.values.set("tv_sid", "revoked");
    identity.me.mockRejectedValue(new UpstreamProblem(401));
    expect(await getViewer()).toEqual({ kind: "anon" });
  });

  it("propagates an unavailable identity service", async () => {
    jar.values.set("tv_sid", "t");
    identity.me.mockRejectedValue(new UpstreamError());
    await expect(getViewer()).rejects.toBeInstanceOf(UpstreamError);
  });
});
