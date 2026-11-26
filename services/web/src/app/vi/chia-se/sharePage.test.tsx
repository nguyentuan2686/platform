import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  resolveShare: vi.fn(),
  notFound: vi.fn(() => {
    throw new Error("NOT_FOUND");
  }),
}));
vi.mock("next/navigation", () => ({ notFound: () => m.notFound() }));
vi.mock("@/lib/identity", () => ({ resolveShare: (id: string) => m.resolveShare(id) }));
vi.mock("@/lib/upstream", () => ({
  getChart: vi.fn(async () => (await import("@/test/chart-fixture.json")).default),
  getBasicReading: vi.fn(async () => ({
    sections: [
      { id: "greeting", lines: ["Chào con."] },
      { id: "menh", lines: ["Mệnh con vững vàng. Sao tốt chiếu vào."] },
    ],
  })),
}));

import { cleanup, render } from "@testing-library/react";
import Page, { dynamic, generateMetadata } from "@/app/vi/chia-se/[shareId]/page";
import * as upstream from "@/lib/upstream";

const ID = "abcdefghijklmnopqrstuv";
const FULL = {
  birth: { birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "female" },
  calendar: "VN",
};
const params = (id = ID) => ({ params: Promise.resolve({ shareId: id }) });

beforeEach(() => {
  cleanup();
  vi.clearAllMocks();
  m.resolveShare.mockResolvedValue(FULL);
});

describe("/vi/chia-se/[shareId]", () => {
  it("is dynamic", () => {
    expect(dynamic).toBe("force-dynamic");
  });

  it("calls notFound for an unknown or revoked id and fetches no chart", async () => {
    m.resolveShare.mockResolvedValue(null);
    await expect(Page(params("zzzzzzzzzzzzzzzzzzzzzz"))).rejects.toThrow("NOT_FOUND");
    expect(upstream.getChart).not.toHaveBeenCalled();
    await expect(generateMetadata(params("zzzzzzzzzzzzzzzzzzzzzz"))).rejects.toThrow("NOT_FOUND");
  });

  it("shows gender, giờ and năm can chi, the 12-palace board and the CTA", async () => {
    const { container, getByRole } = render(await Page(params()));
    expect(container.textContent).toContain("Nữ · giờ Thìn · năm Giáp Tuất");
    expect(container.querySelectorAll("a.cell")).toHaveLength(12);
    expect(getByRole("link", { name: "Lập lá số miễn phí" }).getAttribute("href")).toBe("/vi");
  });

  it("renders none of the full birth: no lunar d/m, no year digits, no time, no zone", async () => {
    const { container } = render(await Page(params()));
    const html = container.innerHTML;
    expect(html).not.toMatch(/Âm lịch/);
    expect(html).not.toMatch(/ngày \d+ tháng \d+/);
    expect(html).not.toMatch(/\b\d{1,2}\/\d{1,2}\b/);
    for (const secret of ["1994", "03-12", "08:30", "Asia/", "Ho_Chi_Minh"]) {
      expect(html).not.toContain(secret);
    }
  });

  it("hands nothing but the reduced chart to the tree (reduced before anything is serialized)", async () => {
    const tree = await Page(params());
    const serialized = JSON.stringify(tree, (k, v) => (k === "default" || typeof v === "function" ? undefined : v));
    expect(serialized).not.toContain('"lunar"');
    expect(serialized).not.toContain("1994");
    expect(serialized).not.toContain("08:30");
    expect(serialized).not.toContain("Asia/");
  });

  it("metadata: title, summary, og:image 1080x1920, noindex", async () => {
    const md = await generateMetadata(params());
    expect(md.title).toBe("Lá số tử vi · năm Giáp Tuất · Tử Vi Đẩu Số");
    expect(md.description).toBe("Mệnh con vững vàng.");
    expect(md.openGraph?.images).toEqual([{ url: `/vi/chia-se/${ID}/the.png`, width: 1080, height: 1920 }]);
    expect(md.robots).toEqual({ index: false, follow: false });
    expect(JSON.stringify(md)).not.toMatch(/1994|08:30|Asia\//);
  });
});
