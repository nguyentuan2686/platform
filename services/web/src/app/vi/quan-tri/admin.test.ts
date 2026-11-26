import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  notFound: vi.fn(() => {
    throw new Error("NOT_FOUND");
  }),
  revalidatePath: vi.fn(),
}));
vi.mock("next/navigation", () => ({ notFound: () => m.notFound() }));
vi.mock("next/cache", () => ({ revalidatePath: (p: string) => m.revalidatePath(p) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));

import AdminPage from "@/app/vi/quan-tri/page";
import { assignAction, completeRefundAction, resolveAction } from "@/app/vi/quan-tri/actions";

const TOKEN = "local-test-admin-token";
const admin = (isAdmin: boolean) => ({
  kind: "account",
  accountId: "11111111-1111-1111-1111-111111111111",
  emailMasked: "a***@e***.com",
  token: "acc-token",
  isAdmin,
  entitlements: { credits: 1, pass: null, features: [] },
});

type Call = { url: string; method: string; headers: Headers; raw?: string };
let calls: Call[];
let next: (url: string) => Response;
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function form(fields: Record<string, string>): FormData {
  const f = new FormData();
  for (const [k, v] of Object.entries(fields)) f.set(k, v);
  return f;
}

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  calls = [];
  next = () => json(200, { ok: true });
  process.env.ADMIN_API_TOKEN = TOKEN;
  for (const f of Object.values(m)) f.mockClear();
  m.getViewer.mockResolvedValue(admin(true));
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: String(init.method), headers: new Headers(init.headers), raw: init.body ? String(init.body) : undefined });
      return next(url);
    }),
  );
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  out.mockRestore();
  delete process.env.ADMIN_API_TOKEN;
});

describe("/vi/quan-tri page", () => {
  it.each([
    ["a non-admin account", admin(false)],
    ["a guest", { kind: "guest", guestId: "g", token: "t" }],
    ["an anonymous viewer", { kind: "anon" }],
  ])("is not found for %s", async (_n, viewer) => {
    m.getViewer.mockResolvedValue(viewer);
    await expect(AdminPage()).rejects.toThrow("NOT_FOUND");
    expect(calls).toEqual([]);
  });

  it("is not found for an admin when ADMIN_API_TOKEN is empty", async () => {
    delete process.env.ADMIN_API_TOKEN;
    await expect(AdminPage()).rejects.toThrow("NOT_FOUND");
    expect(calls).toEqual([]);
  });

  it("loads the queues for an admin", async () => {
    next = () => json(200, []);
    await AdminPage();
    expect(calls.map((c) => c.url)).toEqual([
      "http://localhost:8085/admin/unmatched-transactions",
      "http://localhost:8085/admin/refund-requests",
    ]);
  });
});

describe("admin actions", () => {
  it("re-check isAdmin first: a non-admin gets notFound and nothing is called", async () => {
    m.getViewer.mockResolvedValue(admin(false));
    await expect(assignAction(null, form({ id: "7", order_code: "TV7K3M9P" }))).rejects.toThrow("NOT_FOUND");
    await expect(resolveAction(null, form({ id: "7", resolution: "IGNORED" }))).rejects.toThrow("NOT_FOUND");
    await expect(completeRefundAction(null, form({ id: "r-1" }))).rejects.toThrow("NOT_FOUND");
    expect(calls).toEqual([]);
  });

  it("assign posts X-Admin-Token and never returns it", async () => {
    const state = await assignAction(null, form({ id: "7", order_code: " tv7k3m9p " }));
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://localhost:8085/admin/unmatched-transactions/7/assign");
    expect(calls[0].headers.get("x-admin-token")).toBe(TOKEN);
    expect(JSON.parse(calls[0].raw ?? "{}")).toEqual({ order_code: "TV7K3M9P" });
    expect(state).toEqual({ ok: "Đã gán giao dịch #7 cho đơn TV7K3M9P." });
    expect(JSON.stringify(state)).not.toContain(TOKEN);
    expect(m.revalidatePath).toHaveBeenCalledWith("/vi/quan-tri");
  });

  it("maps assign refusals to labels", async () => {
    next = () => json(409, { status: 409, reason: "amount_low" });
    expect(await assignAction(null, form({ id: "7", order_code: "TV7K3M9P" }))).toEqual({ error: "Thiếu tiền" });
    next = () => json(409, { status: 409, reason: "order_not_pending" });
    expect(await assignAction(null, form({ id: "7", order_code: "TV7K3M9P" }))).toEqual({
      error: "Đơn không còn chờ thanh toán",
    });
    next = () => json(404, { status: 404 });
    expect(await assignAction(null, form({ id: "7", order_code: "TV7K3M9P" }))).toEqual({ error: "Không thấy đơn" });
    next = () => json(503, { status: 503 });
    const down = await assignAction(null, form({ id: "7", order_code: "TV7K3M9P" }));
    expect(down?.error).toBeTruthy();
    expect(JSON.stringify(down)).not.toContain(TOKEN);
  });

  it("validates the form before calling payment-svc", async () => {
    expect((await assignAction(null, form({ id: "x", order_code: "TV7K3M9P" })))?.error).toBeTruthy();
    expect((await assignAction(null, form({ id: "7", order_code: "" })))?.error).toBeTruthy();
    expect((await resolveAction(null, form({ id: "7", resolution: "DELETE" })))?.error).toBeTruthy();
    expect(calls).toEqual([]);
  });

  it("resolves with a resolution and note", async () => {
    const state = await resolveAction(null, form({ id: "8", resolution: "REFUNDED_MANUALLY", note: "trả lại qua ngân hàng" }));
    expect(calls[0].url).toBe("http://localhost:8085/admin/unmatched-transactions/8/resolve");
    expect(calls[0].headers.get("x-admin-token")).toBe(TOKEN);
    expect(JSON.parse(calls[0].raw ?? "{}")).toEqual({ resolution: "REFUNDED_MANUALLY", note: "trả lại qua ngân hàng" });
    expect(state?.ok).toBeTruthy();
  });

  it("completes a refund request", async () => {
    next = () => json(200, { id: "r-1", status: "DONE" });
    const state = await completeRefundAction(null, form({ id: "r-1" }));
    expect(calls[0].url).toBe("http://localhost:8085/admin/refund-requests/r-1/complete");
    expect(calls[0].headers.get("x-admin-token")).toBe(TOKEN);
    expect(state?.ok).toBeTruthy();
  });
});
