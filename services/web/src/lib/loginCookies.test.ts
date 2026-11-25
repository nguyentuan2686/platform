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
      jar.values.delete(typeof arg === "string" ? arg : (arg as { name: string }).name);
    },
  }),
}));

import { rememberNext, setOidcState, takeNext, takeOidcState } from "@/lib/loginCookies";

beforeEach(() => {
  jar.values.clear();
  jar.sets.length = 0;
  jar.deletes.length = 0;
});
afterEach(() => {
  delete process.env.COOKIE_SECURE;
});

describe("tv_next", () => {
  it("stores only a safe path, HttpOnly, Lax, 15 minutes", async () => {
    await rememberNext("/vi/tai-khoan");
    expect(jar.sets[0]).toEqual({
      name: "tv_next",
      value: "/vi/tai-khoan",
      opts: { httpOnly: true, sameSite: "lax", path: "/", secure: false, maxAge: 900 },
    });
    await rememberNext("//evil.com");
    expect(jar.sets[1].value).toBe("/vi/la-so");
  });

  it("is Secure when COOKIE_SECURE=1", async () => {
    process.env.COOKIE_SECURE = "1";
    await rememberNext("/vi/mua");
    expect(jar.sets[0].opts.secure).toBe(true);
  });

  it("is taken once and re-validated", async () => {
    jar.values.set("tv_next", "/vi/mua");
    expect(await takeNext()).toBe("/vi/mua");
    expect(jar.values.has("tv_next")).toBe(false);
    expect(await takeNext()).toBe("/vi/la-so");
    jar.values.set("tv_next", "https://evil.com");
    expect(await takeNext()).toBe("/vi/la-so");
  });
});

describe("tv_oidc", () => {
  it("stores the state for 10 minutes on the callback path only", async () => {
    await setOidcState("st-1");
    expect(jar.sets[0]).toEqual({
      name: "tv_oidc",
      value: "st-1",
      opts: { httpOnly: true, sameSite: "lax", path: "/vi/dang-nhap/google", secure: false, maxAge: 600 },
    });
  });

  it("is taken once", async () => {
    expect(await takeOidcState()).toBeUndefined();
    jar.values.set("tv_oidc", "st-2");
    expect(await takeOidcState()).toBe("st-2");
    expect(jar.values.has("tv_oidc")).toBe(false);
    expect(jar.deletes[0]).toMatchObject({ name: "tv_oidc", path: "/vi/dang-nhap/google" });
  });
});
