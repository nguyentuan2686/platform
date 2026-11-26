import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
  getViewer: vi.fn(),
  getProfile: vi.fn(),
}));
vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/identity", () => ({ getProfile: (b: string) => m.getProfile(b) }));
vi.mock("@/lib/upstream", () => ({
  getChart: vi.fn(async () => (await import("@/test/chart-fixture.json")).default),
  getBasicReading: vi.fn(async () => ({ sections: [{ id: "greeting", lines: ["Chào con"] }] })),
  getToday: vi.fn(async () => ({ lunar: "15/10", opening: "o", areas: [], closing: "c" })),
}));

import { cleanup, render } from "@testing-library/react";
import Page from "@/app/vi/la-so/page";
import { todayInVietnam } from "@/lib/today";
import * as upstream from "@/lib/upstream";

const PROFILE = {
  id: "p",
  label: "Lá số của tôi",
  birth: { birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" },
  calendar: "VN",
  locked: false,
};

describe("/vi/la-so", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "tok" });
    m.getProfile.mockResolvedValue(PROFILE);
  });

  it("redirects an anonymous viewer to /vi and fetches nothing", async () => {
    m.getViewer.mockResolvedValue({ kind: "anon" });
    await expect(Page()).rejects.toThrow("REDIRECT:/vi");
    expect(m.getProfile).not.toHaveBeenCalled();
    expect(upstream.getChart).not.toHaveBeenCalled();
  });

  it("redirects a viewer without a profile to /vi", async () => {
    m.getProfile.mockResolvedValue(null);
    await expect(Page()).rejects.toThrow("REDIRECT:/vi");
    expect(m.getProfile).toHaveBeenCalledWith("tok");
    expect(upstream.getChart).not.toHaveBeenCalled();
  });

  it("fetches chart, reading and today from the profile's birth", async () => {
    await Page();
    const birth = { ...PROFILE.birth, calendar: "VN" };
    expect(upstream.getChart).toHaveBeenCalledWith(birth);
    expect(upstream.getBasicReading).toHaveBeenCalledWith(birth);
    expect(upstream.getToday).toHaveBeenCalledWith(birth, todayInVietnam());
  });

  it("renders the 12-palace board and the today panel anchor", async () => {
    const { container } = render(await Page());
    expect(container.querySelectorAll("a.cell")).toHaveLength(12);
    expect(container.querySelector("#hom-nay")).not.toBeNull();
  });
});

describe("/vi/la-so house ad", () => {
  afterEach(cleanup);
  const AD = "Hỏi sâu hơn mỗi ngày với thẻ năm, hỏi không giới hạn.";
  const account = (pass: unknown) => ({
    kind: "account",
    accountId: "a",
    emailMasked: "",
    token: "tok",
    isAdmin: false,
    entitlements: { credits: 1, pass, features: [] },
  });
  beforeEach(() => {
    cleanup(); // the suite above leaves its last render mounted
    vi.clearAllMocks();
    m.getProfile.mockResolvedValue(PROFILE);
  });

  it("links to /vi/mua under the today panel for a viewer without a pass", async () => {
    m.getViewer.mockResolvedValue(account(null));
    const { getByRole } = render(await Page());
    expect(getByRole("link", { name: AD }).getAttribute("href")).toBe("/vi/mua");
  });

  it("is hidden for a viewer with an active pass, shown again once it ended", async () => {
    const future = new Date(Date.now() + 86400_000).toISOString();
    m.getViewer.mockResolvedValue(account({ kind: "YEAR", ends_at: future, questions_left: null }));
    const first = render(await Page());
    expect(first.queryByRole("link", { name: AD })).toBeNull();
    first.unmount();
    const past = new Date(Date.now() - 86400_000).toISOString();
    m.getViewer.mockResolvedValue(account({ kind: "MONTH", ends_at: past, questions_left: 0 }));
    const second = render(await Page());
    expect(second.getByRole("link", { name: AD })).toBeTruthy();
  });
});
