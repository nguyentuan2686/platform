import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  loginGuest: vi.fn(),
  setSessionCookie: vi.fn(),
  rememberNext: vi.fn(),
  takeNext: vi.fn(),
  requestMagicLink: vi.fn(),
  verifyMagicLink: vi.fn(),
  mergeGuest: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));

vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("@/lib/session", () => ({
  loginGuest: (o: unknown) => m.loginGuest(o),
  setSessionCookie: (t: string, e: string) => m.setSessionCookie(t, e),
}));
vi.mock("@/lib/loginCookies", () => ({
  rememberNext: (n: unknown) => m.rememberNext(n),
  takeNext: () => m.takeNext(),
}));
vi.mock("@/lib/identity", () => ({
  requestMagicLink: (e: string, b?: string) => m.requestMagicLink(e, b),
  verifyMagicLink: (t: string, b?: string) => m.verifyMagicLink(t, b),
}));
vi.mock("@/lib/qa", () => ({ mergeGuest: (g: string, a: string) => m.mergeGuest(g, a) }));

import { confirmLogin, requestLogin } from "@/app/vi/dang-nhap/actions";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

const EXPIRED = "Link đã hết hạn hoặc đã dùng. Con xin link mới nhé.";
const LOGIN = { session_token: "acc-session", account_id: "acc-1", expires_at: "2099-01-01T00:00:00Z", created: true };

function fd(v: Record<string, string>): FormData {
  const f = new FormData();
  for (const [k, val] of Object.entries(v)) f.set(k, val);
  return f;
}

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.loginGuest.mockResolvedValue({ guestId: "guest-1", token: "guest-token" });
  m.requestMagicLink.mockResolvedValue({ status: "sent_if_allowed" });
  m.verifyMagicLink.mockResolvedValue(LOGIN);
  m.mergeGuest.mockResolvedValue({ moved: 2 });
  m.takeNext.mockResolvedValue("/vi/la-so");
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => out.mockRestore());

const logged = () => out.mock.calls.map((c: unknown[]) => String(c[0])).join("");

describe("requestLogin", () => {
  it.each(["an@example.com", "nobody@unknown.test", "Rate.Limited@example.com"])(
    "answers {sent:true} for a 202 whatever the email (%s)",
    async (email) => {
      expect(await requestLogin(null, fd({ email, next: "/vi/tai-khoan" }))).toEqual({ sent: true });
      expect(m.loginGuest).toHaveBeenCalledWith({ create: true });
      expect(m.requestMagicLink).toHaveBeenCalledWith(email, "guest-token");
      expect(m.rememberNext).toHaveBeenCalledWith("/vi/tai-khoan");
    },
  );

  it("sends no guest bearer when the viewer is already an account", async () => {
    m.loginGuest.mockResolvedValue(null);
    expect(await requestLogin(null, fd({ email: "an@example.com" }))).toEqual({ sent: true });
    expect(m.requestMagicLink).toHaveBeenCalledWith("an@example.com", undefined);
    expect(m.rememberNext).toHaveBeenCalledWith(null);
  });

  it("rejects an obviously bad email without creating a session", async () => {
    expect(await requestLogin(null, fd({ email: "  " }))).toEqual({ errors: { email: "Email chưa đúng." } });
    expect(await requestLogin(null, fd({ email: "no-at-sign" }))).toEqual({ errors: { email: "Email chưa đúng." } });
    expect(m.loginGuest).not.toHaveBeenCalled();
    expect(m.requestMagicLink).not.toHaveBeenCalled();
  });

  it("maps 400 to 'Email chưa đúng.'", async () => {
    m.requestMagicLink.mockRejectedValue(new UpstreamValidation({ email: "invalid" }));
    expect(await requestLogin(null, fd({ email: "a@b" }))).toEqual({ errors: { email: "Email chưa đúng." } });
  });

  it("maps 503 (mail server) to the retry message", async () => {
    m.requestMagicLink.mockRejectedValue(new UpstreamError());
    expect(await requestLogin(null, fd({ email: "an@example.com" }))).toEqual({
      errors: { email: "Chưa gửi được thư, con thử lại sau ít phút." },
    });
  });

  it("never logs the email", async () => {
    m.requestMagicLink.mockRejectedValue(new UpstreamProblem(403, undefined, "identity-svc"));
    await requestLogin(null, fd({ email: "secret.person@example.com" }));
    expect(logged()).not.toContain("secret.person");
  });

  it("rethrows a bug", async () => {
    m.requestMagicLink.mockRejectedValue(new TypeError("boom"));
    await expect(requestLogin(null, fd({ email: "an@example.com" }))).rejects.toThrow("boom");
  });
});

describe("confirmLogin", () => {
  it("with a guest: verifies with the guest bearer, sets the cookie and merges once", async () => {
    await expect(confirmLogin(null, fd({ token: "link-token" }))).rejects.toThrow("REDIRECT:/vi/la-so");
    expect(m.loginGuest).toHaveBeenCalledWith({ create: false });
    expect(m.verifyMagicLink).toHaveBeenCalledWith("link-token", "guest-token");
    expect(m.setSessionCookie).toHaveBeenCalledWith("acc-session", "2099-01-01T00:00:00Z");
    expect(m.mergeGuest).toHaveBeenCalledTimes(1);
    expect(m.mergeGuest).toHaveBeenCalledWith("guest-1", "acc-1");
  });

  it("without a session: logs in without a merge", async () => {
    m.loginGuest.mockResolvedValue(null);
    await expect(confirmLogin(null, fd({ token: "link-token" }))).rejects.toThrow("REDIRECT:");
    expect(m.verifyMagicLink).toHaveBeenCalledWith("link-token", undefined);
    expect(m.setSessionCookie).toHaveBeenCalled();
    expect(m.mergeGuest).not.toHaveBeenCalled();
  });

  it("still logs in when the merge fails, logging the ids only", async () => {
    m.mergeGuest.mockRejectedValue(new UpstreamError());
    await expect(confirmLogin(null, fd({ token: "link-token" }))).rejects.toThrow("REDIRECT:/vi/la-so");
    expect(m.setSessionCookie).toHaveBeenCalled();
    const line = out.mock.calls.map((c: unknown[]) => String(c[0])).find((s: string) => s.includes("qa merge failed"));
    expect(line).toBeDefined();
    expect(JSON.parse(line as string)).toMatchObject({ guest_id: "guest-1", account_id: "acc-1", message: "qa merge failed" });
    expect(logged()).not.toMatch(/link-token|acc-session|guest-token/);
  });

  it("answers the expired message on 401 and sets no cookie", async () => {
    m.verifyMagicLink.mockRejectedValue(new UpstreamProblem(401, undefined, "identity-svc"));
    expect(await confirmLogin(null, fd({ token: "used" }))).toEqual({ error: EXPIRED });
    expect(m.setSessionCookie).not.toHaveBeenCalled();
    expect(m.mergeGuest).not.toHaveBeenCalled();
    expect(m.redirect).not.toHaveBeenCalled();
  });

  it("answers the expired message for a missing token without calling identity", async () => {
    expect(await confirmLogin(null, fd({}))).toEqual({ error: EXPIRED });
    expect(m.verifyMagicLink).not.toHaveBeenCalled();
  });

  it("asks to retry when identity is unavailable", async () => {
    m.verifyMagicLink.mockRejectedValue(new UpstreamError());
    const s = await confirmLogin(null, fd({ token: "t" }));
    expect(s?.error).toContain("thử lại");
    expect(s?.error).not.toBe(EXPIRED);
  });

  it("redirects to the stored tv_next", async () => {
    m.takeNext.mockResolvedValue("/vi/tai-khoan");
    await expect(confirmLogin(null, fd({ token: "t" }))).rejects.toThrow("REDIRECT:/vi/tai-khoan");
    expect(m.takeNext).toHaveBeenCalledTimes(1);
  });

  it("rethrows a bug", async () => {
    m.verifyMagicLink.mockRejectedValue(new TypeError("boom"));
    await expect(confirmLogin(null, fd({ token: "t" }))).rejects.toThrow("boom");
  });
});
