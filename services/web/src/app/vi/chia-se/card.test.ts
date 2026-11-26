// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({ resolveShare: vi.fn() }));
vi.mock("@/lib/identity", () => ({ resolveShare: (id: string) => m.resolveShare(id) }));
vi.mock("@/lib/upstream", () => ({
  getChart: vi.fn(async () => (await import("@/test/chart-fixture.json")).default),
  getBasicReading: vi.fn(async () => ({
    sections: [{ id: "menh", lines: ["Mệnh con vững vàng. Sao tốt chiếu vào."] }],
  })),
}));

import { GET, runtime } from "@/app/vi/chia-se/[shareId]/the.png/route";
import { UpstreamError } from "@/lib/http";

const ID = "abcdefghijklmnopqrstuv";
const ctx = (id = ID) => ({ params: Promise.resolve({ shareId: id }) });
const FULL = { birth: { birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male" }, calendar: "VN" };

beforeEach(() => {
  vi.clearAllMocks();
  m.resolveShare.mockResolvedValue(FULL);
});

describe("GET /vi/chia-se/[shareId]/the.png", () => {
  it("runs on node (it reads the fonts from disk)", () => {
    expect(runtime).toBe("nodejs");
  });

  it("answers a 1080x1920 PNG cached for five minutes", async () => {
    const res = await GET(new Request(`http://x.test/vi/chia-se/${ID}/the.png`), ctx());
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("image/png");
    expect(res.headers.get("cache-control")).toBe("public, max-age=300");
    const bytes = new Uint8Array(await res.arrayBuffer());
    expect([...bytes.slice(0, 8)]).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const view = new DataView(bytes.buffer, bytes.byteOffset);
    expect(view.getUint32(16)).toBe(1080); // IHDR width
    expect(view.getUint32(20)).toBe(1920); // IHDR height
  }, 60000);

  it("is 404 for a revoked or unknown share", async () => {
    m.resolveShare.mockResolvedValue(null);
    const res = await GET(new Request("http://x.test/"), ctx());
    expect(res.status).toBe(404);
  });

  it("is 503 when an upstream is down", async () => {
    m.resolveShare.mockRejectedValue(new UpstreamError());
    const res = await GET(new Request("http://x.test/"), ctx());
    expect(res.status).toBe(503);
  });
});
