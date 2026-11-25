import { beforeEach, describe, expect, it, vi } from "vitest";

const notFound = vi.hoisted(() =>
  vi.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
);
vi.mock("next/navigation", () => ({ notFound }));
vi.mock("@/lib/upstream", () => ({
  getChart: vi.fn(async () => ({ hourBranch: "Chen", yearCanChi: { nameVi: "Giáp Tuất" } })),
  getBasicReading: vi.fn(async () => ({ sections: [{ id: "greeting", lines: ["Chào con"] }] })),
  getToday: vi.fn(async () => ({ lunar: "15/10", opening: "o", areas: [], closing: "c" })),
}));

import Page from "@/app/vi/la-so/[id]/page";
import { todayInVietnam } from "@/lib/today";
import { birthStore } from "@/lib/birthStore";
import * as upstream from "@/lib/upstream";

describe("result page", () => {
  beforeEach(() => vi.clearAllMocks());

  it("calls notFound() for an unknown id and fetches nothing", async () => {
    await expect(Page({ params: Promise.resolve({ id: "missing" }) })).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFound).toHaveBeenCalled();
    expect(upstream.getChart).not.toHaveBeenCalled();
  });

  it("fetches chart, reading and today for a known id", async () => {
    const id = birthStore.put({ birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male", calendar: "VN" });
    await Page({ params: Promise.resolve({ id }) });
    expect(upstream.getChart).toHaveBeenCalledTimes(1);
    expect(upstream.getBasicReading).toHaveBeenCalledTimes(1);
    expect(upstream.getToday).toHaveBeenCalledWith(expect.anything(), todayInVietnam());
  });
});
