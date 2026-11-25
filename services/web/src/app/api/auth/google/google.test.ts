import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  loginGuest: vi.fn(),
  readSessionToken: vi.fn(),
  bindLoginToSession: vi.fn(),
  takeLoginBinding: vi.fn(),
  setSessionCookie: vi.fn(),
  rememberNext: vi.fn(),
  takeNext: vi.fn(),
  setOidcState: vi.fn(),
  takeOidcState: vi.fn(),
  googleAuthorization: vi.fn(),
  googleCallback: vi.fn(),
  mergeGuest: vi.fn(),
}));

vi.mock("@/lib/session", () => ({
  loginGuest: (o: unknown) => m.loginGuest(o),
  readSessionToken: () => m.readSessionToken(),
  setSessionCookie: (t: string, e: string) => m.setSessionCookie(t, e),
}));
vi.mock("@/lib/loginCookies", () => ({
  rememberNext: (n: unknown) => m.rememberNext(n),
  takeNext: () => m.takeNext(),
  setOidcState: (s: string) => m.setOidcState(s),
  takeOidcState: () => m.takeOidcState(),
  bindLoginToSession: (s: string) => m.bindLoginToSession(s),
  takeLoginBinding: (s?: string) => m.takeLoginBinding(s),
}));
vi.mock("@/lib/identity", () => ({
  googleAuthorization: (r: string, b?: string) => m.googleAuthorization(r, b),
  googleCallback: (c: string, s: string, b?: string) => m.googleCallback(c, s, b),
}));
vi.mock("@/lib/qa", () => ({ mergeGuest: (g: string, a: string) => m.mergeGuest(g, a) }));

import { POST } from "@/app/api/auth/google/route";
import { GET } from "@/app/vi/dang-nhap/google/route";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

const ORIGIN = "http://localhost:3001";
const STATE = "s".repeat(43);
const GOOGLE_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=" + STATE;
const LOGIN = { session_token: "acc-session", account_id: "acc-1", expires_at: "2099-01-01T00:00:00Z", created: false };

function start(headers: Record<string, string> = { origin: ORIGIN }, next = "/vi/mua"): Request {
  const body = new URLSearchParams({ next });
  return new Request(`${ORIGIN}/api/auth/google`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", ...headers },
    body,
  });
}
const callback = (qs: string) => new Request(`${ORIGIN}/vi/dang-nhap/google?${qs}`);

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  process.env.GOOGLE_LOGIN = "1";
  m.loginGuest.mockResolvedValue({ guestId: "guest-1", token: "guest-token" });
  m.googleAuthorization.mockResolvedValue({ authorization_url: GOOGLE_URL, state: STATE });
  m.googleCallback.mockResolvedValue(LOGIN);
  m.mergeGuest.mockResolvedValue({ moved: 0 });
  m.takeNext.mockResolvedValue("/vi/mua");
  m.takeOidcState.mockResolvedValue(STATE);
  m.readSessionToken.mockResolvedValue("guest-token");
  m.takeLoginBinding.mockResolvedValue(true);
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => {
  delete process.env.GOOGLE_LOGIN;
  out.mockRestore();
});

describe("POST /api/auth/google", () => {
  it("answers 404 when GOOGLE_LOGIN is off", async () => {
    delete process.env.GOOGLE_LOGIN;
    const res = await POST(start());
    expect(res.status).toBe(404);
    expect(m.googleAuthorization).not.toHaveBeenCalled();
    expect(m.loginGuest).not.toHaveBeenCalled();
  });

  it("answers 403 for a foreign Origin", async () => {
    const res = await POST(start({ origin: "https://evil.example" }));
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ reason: "forbidden" });
    expect(m.googleAuthorization).not.toHaveBeenCalled();
  });

  it("answers 403 without Origin unless Sec-Fetch-Site is same-origin", async () => {
    expect((await POST(start({}))).status).toBe(403);
    expect((await POST(start({ "sec-fetch-site": "same-origin" }))).status).toBe(303);
  });

  it("stores the state in tv_oidc and redirects (303) to the Google URL", async () => {
    const res = await POST(start());
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(GOOGLE_URL);
    expect(m.loginGuest).toHaveBeenCalledWith({ create: true });
    expect(m.googleAuthorization).toHaveBeenCalledWith(`${ORIGIN}/vi/dang-nhap/google`, "guest-token");
    expect(m.setOidcState).toHaveBeenCalledWith(STATE);
    expect(m.rememberNext).toHaveBeenCalledWith("/vi/mua");
    expect(m.bindLoginToSession).toHaveBeenCalledWith("guest-token");
  });

  it("sends the user back to the login page when identity fails", async () => {
    m.googleAuthorization.mockRejectedValue(new UpstreamError());
    const res = await POST(start());
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/dang-nhap?loi=google`);
    expect(m.setOidcState).not.toHaveBeenCalled();
  });

  it("refuses a non-https authorization URL", async () => {
    m.googleAuthorization.mockResolvedValue({ authorization_url: "javascript:alert(1)", state: STATE });
    const res = await POST(start());
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/dang-nhap?loi=google`);
  });
});

describe("GET /vi/dang-nhap/google (callback)", () => {
  it("answers 404 when GOOGLE_LOGIN is off", async () => {
    delete process.env.GOOGLE_LOGIN;
    expect((await GET(callback(`code=c&state=${STATE}`))).status).toBe(404);
    expect(m.googleCallback).not.toHaveBeenCalled();
  });

  it("redirects to ?loi=google on a state mismatch, calling no upstream", async () => {
    const res = await GET(callback("code=c&state=other"));
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/dang-nhap?loi=google`);
    expect(m.googleCallback).not.toHaveBeenCalled();
    expect(m.loginGuest).not.toHaveBeenCalled();
    expect(m.setSessionCookie).not.toHaveBeenCalled();
  });

  it("redirects to ?loi=google without a state cookie or without a code", async () => {
    m.takeOidcState.mockResolvedValue(undefined);
    expect((await GET(callback(`code=c&state=${STATE}`))).headers.get("location")).toContain("loi=google");
    m.takeOidcState.mockResolvedValue(STATE);
    expect((await GET(callback(`error=access_denied&state=${STATE}`))).headers.get("location")).toContain("loi=google");
    expect(m.googleCallback).not.toHaveBeenCalled();
  });

  it("logs in: cookie, merge of the guest, 303 to tv_next", async () => {
    const res = await GET(callback(`code=the-code&state=${STATE}`));
    expect(m.googleCallback).toHaveBeenCalledWith("the-code", STATE, "guest-token");
    expect(m.loginGuest).toHaveBeenCalledWith({ create: false });
    expect(m.setSessionCookie).toHaveBeenCalledWith("acc-session", "2099-01-01T00:00:00Z");
    expect(m.mergeGuest).toHaveBeenCalledWith("guest-1", "acc-1");
    expect(m.takeLoginBinding).toHaveBeenCalledWith("guest-token");
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/mua`);
  });

  it("a login not started with this session (binding missing or mismatched): no guest bearer, no merge", async () => {
    m.takeLoginBinding.mockResolvedValue(false);
    const res = await GET(callback(`code=the-code&state=${STATE}`));
    expect(m.googleCallback).toHaveBeenCalledWith("the-code", STATE, undefined);
    expect(m.mergeGuest).not.toHaveBeenCalled();
    expect(m.setSessionCookie).toHaveBeenCalledWith("acc-session", "2099-01-01T00:00:00Z");
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/mua`);
  });

  it("without the start cookie: no upstream call and no merge", async () => {
    m.takeOidcState.mockResolvedValue(undefined);
    const res = await GET(callback(`code=c&state=${STATE}`));
    expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/dang-nhap?loi=google`);
    expect(m.googleCallback).not.toHaveBeenCalled();
    expect(m.mergeGuest).not.toHaveBeenCalled();
  });

  it.each([new UpstreamProblem(401, undefined, "identity-svc"), new UpstreamError()])(
    "maps an upstream failure (%s) to ?loi=google",
    async (err) => {
      m.googleCallback.mockRejectedValue(err);
      const res = await GET(callback(`code=c&state=${STATE}`));
      expect(res.headers.get("location")).toBe(`${ORIGIN}/vi/dang-nhap?loi=google`);
      expect(m.setSessionCookie).not.toHaveBeenCalled();
    },
  );

  it("never logs the code or the state", async () => {
    m.googleCallback.mockRejectedValue(new UpstreamProblem(401, undefined, "identity-svc"));
    await GET(callback(`code=secret-code&state=${STATE}`));
    const logged = out.mock.calls.map((c: unknown[]) => String(c[0])).join("");
    expect(logged).not.toContain("secret-code");
    expect(logged).not.toContain(STATE);
  });
});
