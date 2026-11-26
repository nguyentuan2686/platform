import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  createShare: vi.fn(),
  deleteShare: vi.fn(),
  revalidatePath: vi.fn(),
  redirect: vi.fn((u: string) => {
    throw new Error(`REDIRECT:${u}`);
  }),
}));
vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("next/cache", () => ({ revalidatePath: (p: string) => m.revalidatePath(p) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/identity", () => ({
  createShare: (t: string) => m.createShare(t),
  deleteShare: (t: string) => m.deleteShare(t),
}));

import { createShareAction, deleteShareAction } from "@/app/vi/la-so/shareActions";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

beforeEach(() => {
  vi.clearAllMocks();
  m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "tok" });
  m.createShare.mockResolvedValue({ share_id: "x", created_at: "t" });
  m.deleteShare.mockResolvedValue(undefined);
});

describe("shareActions", () => {
  it("create uses the viewer's own token and revalidates the chart page", async () => {
    expect(await createShareAction()).toBeNull();
    expect(m.createShare).toHaveBeenCalledWith("tok");
    expect(m.revalidatePath).toHaveBeenCalledWith("/vi/la-so");
  });

  it("delete uses the viewer's own token and revalidates", async () => {
    expect(await deleteShareAction()).toBeNull();
    expect(m.deleteShare).toHaveBeenCalledWith("tok");
    expect(m.revalidatePath).toHaveBeenCalledWith("/vi/la-so");
  });

  it("an anonymous viewer is sent home and nothing is called", async () => {
    m.getViewer.mockResolvedValue({ kind: "anon" });
    await expect(createShareAction()).rejects.toThrow("REDIRECT:/vi");
    await expect(deleteShareAction()).rejects.toThrow("REDIRECT:/vi");
    expect(m.createShare).not.toHaveBeenCalled();
    expect(m.deleteShare).not.toHaveBeenCalled();
  });

  it("explains a missing profile (409) without revalidating", async () => {
    m.createShare.mockRejectedValue(new UpstreamProblem(409, "no_profile", "identity-svc"));
    const r = await createShareAction();
    expect(r?.error).toMatch(/lập lá số/);
    expect(m.revalidatePath).not.toHaveBeenCalled();
  });

  it("answers a themed message when identity is down, and rethrows a bug", async () => {
    m.deleteShare.mockRejectedValue(new UpstreamError());
    expect((await deleteShareAction())?.error).toMatch(/thử lại/);
    m.deleteShare.mockRejectedValue(new TypeError("bug"));
    await expect(deleteShareAction()).rejects.toThrow("bug");
  });
});
