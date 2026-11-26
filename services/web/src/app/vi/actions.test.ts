import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getChart: vi.fn(),
  readSessionToken: vi.fn(),
  ensureSession: vi.fn(),
  clearSessionCookie: vi.fn(),
  getProfile: vi.fn(),
  putProfile: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));

vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("@/lib/upstream", async () => {
  const http = await import("@/lib/http");
  return { ...http, getChart: (b: unknown) => m.getChart(b) };
});
vi.mock("@/lib/session", () => ({
  readSessionToken: () => m.readSessionToken(),
  ensureSession: (o?: unknown) => m.ensureSession(o),
  clearSessionCookie: () => m.clearSessionCookie(),
}));
vi.mock("@/lib/identity", () => ({
  getProfile: (b: string) => m.getProfile(b),
  putProfile: (b: string, p: unknown) => m.putProfile(b, p),
}));

import { submitBirth } from "@/app/vi/actions";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

function form(over: Record<string, string | undefined> = {}): FormData {
  const fd = new FormData();
  const v: Record<string, string | undefined> = {
    date: "1990-01-15",
    time: "08:30",
    gender: "male",
    zone: "Asia/Ho_Chi_Minh",
    consent: "1",
    ...over,
  };
  for (const [k, val] of Object.entries(v)) if (val !== undefined) fd.set(k, val);
  return fd;
}

const PROFILE_BIRTH = { birth: "1990-01-15T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" };

beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.readSessionToken.mockResolvedValue(undefined);
  m.ensureSession.mockResolvedValue("sess-token");
  m.getChart.mockResolvedValue({});
  m.putProfile.mockResolvedValue({});
  m.getProfile.mockResolvedValue(null);
});

describe("submitBirth", () => {
  it("returns field errors and the submitted values for an invalid form, without calling upstream", async () => {
    const state = await submitBirth(null, form({ date: "nope", gender: "" }));
    expect(state?.errors.date).toBeTruthy();
    expect(state?.errors.gender).toBeTruthy();
    expect(state?.values).toEqual({ date: "nope", time: "08:30", gender: "", zone: "Asia/Ho_Chi_Minh", consent: "1" });
    expect(m.getChart).not.toHaveBeenCalled();
    expect(m.ensureSession).not.toHaveBeenCalled();
  });

  it("requires consent and creates no session", async () => {
    const state = await submitBirth(null, form({ consent: undefined }));
    expect(state?.errors.consent).toBe("Con cần đồng ý để ta lưu ngày giờ sinh.");
    expect(m.ensureSession).not.toHaveBeenCalled();
    expect(m.putProfile).not.toHaveBeenCalled();
  });

  it("saves a new profile with calendar VN and redirects to /vi/la-so without birth data in the URL", async () => {
    await expect(submitBirth(null, form())).rejects.toThrow("REDIRECT:");
    expect(m.ensureSession).toHaveBeenCalledTimes(1);
    expect(m.putProfile).toHaveBeenCalledTimes(1);
    const [bearer, body] = m.putProfile.mock.calls[0];
    expect(bearer).toBe("sess-token");
    expect(body).toEqual({ label: "Lá số của tôi", birth: PROFILE_BIRTH, calendar: "VN" });
    expect(m.getChart.mock.calls[0][0]).toEqual({ ...PROFILE_BIRTH, calendar: "VN" });
    expect(m.redirect).toHaveBeenCalledWith("/vi/la-so");
  });

  it("keeps the existing profile's calendar and label", async () => {
    m.readSessionToken.mockResolvedValue("old");
    m.ensureSession.mockResolvedValue("old");
    m.getProfile.mockResolvedValue({ id: "p", label: "Mẹ", birth: PROFILE_BIRTH, calendar: "CN", locked: false });
    await expect(submitBirth(null, form())).rejects.toThrow("REDIRECT:/vi/la-so");
    expect(m.getProfile).toHaveBeenCalledWith("old");
    expect(m.putProfile.mock.calls[0][1]).toEqual({ label: "Mẹ", birth: PROFILE_BIRTH, calendar: "CN" });
    expect(m.getChart.mock.calls[0][0].calendar).toBe("CN");
  });

  it("maps profile_locked to a date error", async () => {
    m.putProfile.mockRejectedValue(new UpstreamProblem(409, "profile_locked"));
    const state = await submitBirth(null, form());
    expect(state?.errors.date).toBe("Lá số của con đã khóa sau lượt hỏi đầu tiên, không đổi ngày giờ sinh được nữa.");
    expect(m.redirect).not.toHaveBeenCalled();
  });

  it("renews a stale session once when identity answers 401", async () => {
    m.readSessionToken.mockResolvedValue("stale");
    m.getProfile.mockRejectedValue(new UpstreamProblem(401));
    m.ensureSession.mockResolvedValueOnce("stale").mockResolvedValueOnce("fresh");
    m.putProfile.mockRejectedValueOnce(new UpstreamProblem(401)).mockResolvedValueOnce({});
    await expect(submitBirth(null, form())).rejects.toThrow("REDIRECT:/vi/la-so");
    expect(m.clearSessionCookie).toHaveBeenCalled();
    expect(m.ensureSession).toHaveBeenLastCalledWith({ renew: true });
    expect(m.putProfile.mock.calls[1][0]).toBe("fresh");
  });

  it("maps an upstream 400 to field errors and keeps the values", async () => {
    m.getChart.mockRejectedValue(new UpstreamValidation({ birth: "nonexistent local time" }));
    const state = await submitBirth(null, form());
    expect(state?.errors.date).toContain("chưa hợp lệ");
    expect(state?.unavailable).toBeUndefined();
    expect(state?.values?.date).toBe("1990-01-15");
    expect(m.putProfile).not.toHaveBeenCalled();
    expect(m.ensureSession).not.toHaveBeenCalled();
  });

  it("falls back to a date error for an unknown upstream field", async () => {
    m.putProfile.mockRejectedValue(new UpstreamValidation({ calendar: "bad" }));
    const state = await submitBirth(null, form());
    expect(Object.keys(state?.errors ?? {})).toEqual(["date"]);
  });

  it("shows the 'needs a moment' state when upstream is unavailable, saving nothing", async () => {
    m.getChart.mockRejectedValue(new UpstreamError());
    const state = await submitBirth(null, form());
    expect(state?.unavailable).toBe(true);
    expect(state?.errors).toEqual({});
    expect(state?.values?.time).toBe("08:30");
    expect(m.putProfile).not.toHaveBeenCalled();
    expect(m.redirect).not.toHaveBeenCalled();
  });

  it("shows the unavailable state for an unexpected identity refusal, and logs it (no birth data)", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.putProfile.mockRejectedValue(new UpstreamProblem(403, undefined, "identity-svc"));
    const state = await submitBirth(null, form());
    const out = write.mock.calls.map((c) => String(c[0])).join("");
    write.mockRestore();
    expect(state?.unavailable).toBe(true);
    expect(JSON.parse(out)).toMatchObject({ level: "error", upstream: "identity-svc", status: "403", part: "save-chart" });
    expect(out).not.toMatch(/1990|08:30|Asia\/Ho_Chi_Minh|male/);
  });

  it("rethrows unexpected errors", async () => {
    m.getChart.mockRejectedValue(new TypeError("boom"));
    await expect(submitBirth(null, form())).rejects.toThrow("boom");
  });
});
