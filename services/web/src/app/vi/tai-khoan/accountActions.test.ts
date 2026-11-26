import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  clearSessionCookie: vi.fn(),
  getProfile: vi.fn(),
  putProfile: vi.fn(),
  deleteProfile: vi.fn(),
  logout: vi.fn(),
  deleteReadings: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));

vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("@/lib/session", () => ({
  getViewer: () => m.getViewer(),
  clearSessionCookie: () => m.clearSessionCookie(),
}));
vi.mock("@/lib/identity", () => ({
  getProfile: (b: string) => m.getProfile(b),
  putProfile: (b: string, p: unknown) => m.putProfile(b, p),
  deleteProfile: (b: string) => m.deleteProfile(b),
  logout: (b: string) => m.logout(b),
}));
vi.mock("@/lib/qa", () => ({ deleteReadings: (b: string) => m.deleteReadings(b) }));

import { changeCalendar, deleteProfile, logoutAction } from "@/app/vi/tai-khoan/actions";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

const BIRTH = { birth: "1990-01-15T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" };
const ACCOUNT = {
  kind: "account",
  accountId: "acc-1",
  emailMasked: "a***@e***.com",
  token: "acc-token",
  isAdmin: false,
  entitlements: { credits: 1, pass: null, features: [] },
};
const profile = (calendar: "VN" | "CN") => ({ id: "p", label: "Lá số của tôi", birth: BIRTH, calendar, locked: true });

function confirmed(v = "1"): FormData {
  const f = new FormData();
  f.set("confirm", v);
  return f;
}

beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.getViewer.mockResolvedValue(ACCOUNT);
  m.putProfile.mockResolvedValue({});
  m.deleteProfile.mockResolvedValue(undefined);
  m.deleteReadings.mockResolvedValue(undefined);
  m.logout.mockResolvedValue(undefined);
});

describe("changeCalendar", () => {
  it.each([
    ["VN", "CN"],
    ["CN", "VN"],
  ] as const)("flips %s to %s keeping label and birth", async (from, to) => {
    m.getProfile.mockResolvedValue(profile(from));
    await expect(changeCalendar()).rejects.toThrow("REDIRECT:/vi/tai-khoan");
    expect(m.putProfile).toHaveBeenCalledWith("acc-token", { label: "Lá số của tôi", birth: BIRTH, calendar: to });
  });

  it("sends a guest or anonymous viewer to login", async () => {
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "gt" });
    await expect(changeCalendar()).rejects.toThrow("REDIRECT:/vi/dang-nhap?next=/vi/tai-khoan");
    expect(m.putProfile).not.toHaveBeenCalled();
  });

  it("sends an account without a chart to /vi", async () => {
    m.getProfile.mockResolvedValue(null);
    await expect(changeCalendar()).rejects.toThrow("REDIRECT:/vi");
    expect(m.putProfile).not.toHaveBeenCalled();
  });
});

describe("deleteProfile", () => {
  it("deletes the Q&A history and the chart, then redirects to /vi", async () => {
    await expect(deleteProfile(confirmed())).rejects.toThrow("REDIRECT:/vi");
    expect(m.deleteReadings).toHaveBeenCalledWith("acc-token");
    expect(m.deleteProfile).toHaveBeenCalledWith("acc-token");
    expect(m.redirect).toHaveBeenCalledWith("/vi");
  });

  it("does nothing without the confirmation field", async () => {
    await expect(deleteProfile(new FormData())).rejects.toThrow("REDIRECT:/vi/tai-khoan");
    await expect(deleteProfile(confirmed("yes"))).rejects.toThrow("REDIRECT:/vi/tai-khoan");
    expect(m.deleteProfile).not.toHaveBeenCalled();
    expect(m.deleteReadings).not.toHaveBeenCalled();
  });

  it("keeps the chart when deleting the history fails", async () => {
    m.deleteReadings.mockRejectedValue(new UpstreamError());
    await expect(deleteProfile(confirmed())).rejects.toBeInstanceOf(UpstreamError);
    expect(m.deleteProfile).not.toHaveBeenCalled();
  });

  it("requires an account", async () => {
    m.getViewer.mockResolvedValue({ kind: "anon" });
    await expect(deleteProfile(confirmed())).rejects.toThrow("REDIRECT:/vi/dang-nhap?next=/vi/tai-khoan");
    expect(m.deleteProfile).not.toHaveBeenCalled();
  });
});

describe("logoutAction", () => {
  it("revokes the session, clears the cookie and goes to /vi", async () => {
    await expect(logoutAction()).rejects.toThrow("REDIRECT:/vi");
    expect(m.logout).toHaveBeenCalledWith("acc-token");
    expect(m.clearSessionCookie).toHaveBeenCalled();
  });

  it("ignores a 401 (already revoked) and an unavailable identity", async () => {
    m.logout.mockRejectedValue(new UpstreamProblem(401));
    await expect(logoutAction()).rejects.toThrow("REDIRECT:/vi");
    m.logout.mockRejectedValue(new UpstreamError());
    await expect(logoutAction()).rejects.toThrow("REDIRECT:/vi");
    expect(m.clearSessionCookie).toHaveBeenCalledTimes(2);
  });

  it("clears the cookie of a guest or a viewer identity cannot read", async () => {
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "gt" });
    await expect(logoutAction()).rejects.toThrow("REDIRECT:/vi");
    expect(m.logout).toHaveBeenCalledWith("gt");
    m.getViewer.mockRejectedValue(new UpstreamError());
    await expect(logoutAction()).rejects.toThrow("REDIRECT:/vi");
    expect(m.clearSessionCookie).toHaveBeenCalledTimes(2);
  });

  it("rethrows a bug after clearing the cookie", async () => {
    m.logout.mockRejectedValue(new TypeError("boom"));
    await expect(logoutAction()).rejects.toThrow("boom");
    expect(m.clearSessionCookie).toHaveBeenCalled();
  });
});
