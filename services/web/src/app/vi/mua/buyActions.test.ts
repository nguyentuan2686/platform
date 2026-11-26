import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  createOrder: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));
vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/payment", () => ({ createOrder: (a: string, p: string) => m.createOrder(a, p) }));

import { buyAction } from "@/app/vi/mua/actions";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";
import * as paymentCache from "@/lib/paymentCache";

const ACC = "11111111-1111-1111-1111-111111111111";
const ORD = "22222222-2222-2222-2222-222222222222";
const ACCOUNT = {
  kind: "account",
  accountId: ACC,
  emailMasked: "a***@e***.com",
  token: "acc-token",
  isAdmin: false,
  entitlements: { credits: 1, pass: null, features: [] },
};
const PAYMENT = {
  kind: "VIETQR",
  bank_code: "TEST BANK",
  account_number: "0000000000",
  account_name: "TEST BANK",
  amount: "29000.00",
  content: "TV7K3M9P",
  qr_image_url: "https://img.example/qr.png",
};

function form(fields: Record<string, string>): FormData {
  const f = new FormData();
  for (const [k, v] of Object.entries(fields)) f.set(k, v);
  return f;
}

beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.getViewer.mockResolvedValue(ACCOUNT);
  m.createOrder.mockResolvedValue({ order_id: ORD, code: "TV7K3M9P", status: "PENDING", payment: PAYMENT });
  paymentCache.clearPaymentCache();
});

describe("buyAction", () => {
  it.each([{ kind: "anon" }, { kind: "guest", guestId: "g", token: "t" }])("sends a %o viewer to login with next=/vi/mua", async (v) => {
    m.getViewer.mockResolvedValue(v);
    await expect(buyAction(null, form({ product: "CREDIT_1", adult: "1" }))).rejects.toThrow(
      "REDIRECT:/vi/dang-nhap?next=/vi/mua",
    );
    expect(m.createOrder).not.toHaveBeenCalled();
  });

  it("requires the 18+ confirmation", async () => {
    expect(await buyAction(null, form({ product: "CREDIT_1" }))).toEqual({
      error: "Con cần xác nhận đã đủ 18 tuổi.",
      field: "adult",
    });
    expect(m.createOrder).not.toHaveBeenCalled();
  });

  it("rejects an unknown product", async () => {
    const s = await buyAction(null, form({ product: "CREDIT_99", adult: "1" }));
    expect(s?.error).toBeTruthy();
    expect(m.createOrder).not.toHaveBeenCalled();
  });

  it("creates the order, caches its payment details and goes to the checkout", async () => {
    await expect(buyAction(null, form({ product: "CREDIT_1", adult: "1" }))).rejects.toThrow(
      `REDIRECT:/vi/thanh-toan/${ORD}`,
    );
    expect(m.createOrder).toHaveBeenCalledWith(ACC, "CREDIT_1");
    expect(paymentCache.get(ORD)).toEqual(PAYMENT);
  });

  it("explains a payment channel that is not ready (503)", async () => {
    m.createOrder.mockRejectedValue(new UpstreamError());
    expect(await buyAction(null, form({ product: "CREDIT_5", adult: "1" }))).toEqual({
      error: "Kênh chuyển khoản chưa sẵn sàng, con thử lại sau.",
    });
    expect(paymentCache.size()).toBe(0);
  });

  it("rethrows a bug", async () => {
    m.createOrder.mockRejectedValue(new TypeError("boom"));
    await expect(buyAction(null, form({ product: "CREDIT_5", adult: "1" }))).rejects.toThrow("boom");
  });
});

describe("buyAction logging", () => {
  const logged = (w: { mock: { calls: unknown[][] } }) => w.mock.calls.map((c) => JSON.parse(String(c[0])));

  it.each([
    ["401 (wrong internal token)", new UpstreamProblem(401, undefined, "payment-svc"), "401"],
    ["403", new UpstreamProblem(403, undefined, "payment-svc"), "403"],
    ["400 (contract mismatch)", new UpstreamValidation({ adult_confirmed: "required" }, "payment-svc"), "400"],
  ])("logs an unexpected %s with upstream/status only, then shows the retry message", async (_n, err, status) => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.createOrder.mockRejectedValue(err);
    expect(await buyAction(null, form({ product: "CREDIT_5", adult: "1" }))).toEqual({
      error: "Kênh chuyển khoản chưa sẵn sàng, con thử lại sau.",
    });
    const lines = logged(write);
    write.mockRestore();
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ level: "error", upstream: "payment-svc", status, part: "buy" });
    expect(JSON.stringify(lines)).not.toMatch(/acc-token|Bearer|@/);
  });

  it("does not log again what http.ts already logged (down, 5xx)", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.createOrder.mockRejectedValue(new UpstreamError());
    await buyAction(null, form({ product: "CREDIT_5", adult: "1" }));
    expect(write).not.toHaveBeenCalled();
    write.mockRestore();
  });

  it("logs an order that came back without VietQR instructions (id only)", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.createOrder.mockResolvedValue({ order_id: ORD, payment: { kind: "PAYPAL", approve_url: "https://x" } });
    expect((await buyAction(null, form({ product: "CREDIT_5", adult: "1" })))?.error).toBeTruthy();
    const lines = logged(write);
    write.mockRestore();
    expect(lines).toEqual([expect.objectContaining({ level: "error", message: "order without VietQR payment", order_id: ORD })]);
  });
});
