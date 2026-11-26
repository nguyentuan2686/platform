import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  deleteReadings: vi.fn(),
  revalidatePath: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));
vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("next/cache", () => ({ revalidatePath: (p: string) => m.revalidatePath(p) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/qa", () => ({ deleteReadings: (t: string) => m.deleteReadings(t) }));

import { deleteHistoryAction } from "@/app/vi/lich-su/actions";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

const GUEST = { kind: "guest", guestId: "g", token: "guest-token" };
const ACCOUNT = { kind: "account", accountId: "a", emailMasked: "", token: "acc-token", isAdmin: false, entitlements: { credits: 1, pass: null, features: [] } };
const form = (confirm?: string) => {
  const f = new FormData();
  if (confirm) f.set("confirm", confirm);
  return f;
};

let out: ReturnType<typeof vi.spyOn>;
const logged = () => out.mock.calls.map((c: unknown[]) => String(c[0])).join("");
beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.getViewer.mockResolvedValue(ACCOUNT);
  m.deleteReadings.mockResolvedValue(undefined);
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => out.mockRestore());

describe("deleteHistoryAction", () => {
  it("sends an anonymous visitor to /vi/hoi without deleting", async () => {
    m.getViewer.mockResolvedValue({ kind: "anon" });
    await expect(deleteHistoryAction(null, form("1"))).rejects.toThrow("REDIRECT:/vi/hoi");
    expect(m.deleteReadings).not.toHaveBeenCalled();
  });

  it("deletes nothing without the dialog's confirmation", async () => {
    expect(await deleteHistoryAction(null, form())).toBeNull();
    expect(m.deleteReadings).not.toHaveBeenCalled();
  });

  it.each([ACCOUNT, GUEST])("deletes the whole history of a $kind and refreshes the list", async (viewer) => {
    m.getViewer.mockResolvedValue(viewer);
    expect(await deleteHistoryAction(null, form("1"))).toEqual({ ok: true });
    expect(m.deleteReadings).toHaveBeenCalledWith(viewer.token);
    expect(m.revalidatePath).toHaveBeenCalledWith("/vi/lich-su");
  });

  it("explains a 409 (an answer or refund still on its way) without logging it as an error", async () => {
    m.deleteReadings.mockRejectedValue(new UpstreamProblem(409, "reading_in_progress", "reading-tuvi"));
    expect(await deleteHistoryAction(null, form("1"))).toEqual({ error: "Còn câu đang soạn, con chờ xong rồi xóa nhé." });
    expect(logged()).toBe("");
    expect(m.revalidatePath).not.toHaveBeenCalled();
  });

  it("logs an unexpected refusal (wrong token, contract mismatch) before answering generically", async () => {
    m.deleteReadings.mockRejectedValue(new UpstreamProblem(401, undefined, "reading-tuvi"));
    expect(await deleteHistoryAction(null, form("1"))).toEqual({ error: "Lão Thiên Cơ cần một lát. Con thử lại sau nhé." });
    expect(logged()).toContain("upstream refused request");
    m.deleteReadings.mockRejectedValue(new UpstreamValidation({}, "reading-tuvi"));
    await deleteHistoryAction(null, form("1"));
    expect(logged()).toContain('"status":"400"');
  });

  it("answers generically when reading-tuvi is down, and rethrows a bug", async () => {
    m.deleteReadings.mockRejectedValue(new UpstreamError());
    expect(await deleteHistoryAction(null, form("1"))).toEqual({ error: "Lão Thiên Cơ cần một lát. Con thử lại sau nhé." });
    m.deleteReadings.mockRejectedValue(new TypeError("bug"));
    await expect(deleteHistoryAction(null, form("1"))).rejects.toThrow("bug");
  });
});
