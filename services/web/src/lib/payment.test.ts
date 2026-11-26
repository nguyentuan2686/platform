import { createHmac } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as pay from "@/lib/payment";

type Call = { url: string; method: string; headers: Headers; raw?: string };
let calls: Call[];
let next: () => Response;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

beforeEach(() => {
  calls = [];
  next = () => json(200, {});
  process.env.ADMIN_API_TOKEN = "local-test-admin";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: String(init.method), headers: new Headers(init.headers), raw: init.body ? String(init.body) : undefined });
      return next();
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  for (const k of ["ADMIN_API_TOKEN", "SEPAY_WEBHOOK_AUTH", "SEPAY_WEBHOOK_SECRET"]) delete process.env[k];
});

const last = () => calls[calls.length - 1];
const body = () => JSON.parse(last().raw ?? "null");
const BASE = "http://localhost:8085";
const ACC = "11111111-1111-1111-1111-111111111111";
const ORD = "22222222-2222-2222-2222-222222222222";

describe("payment client", () => {
  it("lists tuvi VND products", async () => {
    next = () => json(200, [{ code: "CREDIT_1", amount: "29000.00", currency: "VND" }]);
    expect(await pay.listProducts()).toHaveLength(1);
    expect(last()).toMatchObject({ url: `${BASE}/products?site=tuvi&currency=VND`, method: "GET" });
  });

  it("creates a SEPAY order with adult confirmation", async () => {
    next = () => json(201, { order_id: ORD });
    await pay.createOrder(ACC, "CREDIT_5");
    expect(last()).toMatchObject({ url: `${BASE}/orders`, method: "POST" });
    expect(body()).toEqual({
      account_id: ACC,
      site: "tuvi",
      product_code: "CREDIT_5",
      currency: "VND",
      provider: "SEPAY",
      adult_confirmed: true,
    });
  });

  it("reads orders of the account and requests refunds", async () => {
    await pay.getOrder(ORD, ACC);
    expect(last().url).toBe(`${BASE}/orders/${ORD}?account_id=${ACC}`);
    next = () => json(200, []);
    await pay.listOrders(ACC);
    expect(last().url).toBe(`${BASE}/orders?account_id=${ACC}`);
    next = () => json(201, { id: "r" });
    await pay.requestRefund(ORD, ACC);
    expect(last()).toMatchObject({ url: `${BASE}/orders/${ORD}/refund-requests`, method: "POST" });
    expect(body()).toEqual({ account_id: ACC });
  });

  it("encodes path ids", async () => {
    await pay.getOrder("../admin", ACC);
    expect(last().url).toBe(`${BASE}/orders/..%2Fadmin?account_id=${ACC}`);
  });

  it("sends the admin token on admin calls", async () => {
    next = () => json(200, []);
    await pay.listUnmatched();
    expect(last()).toMatchObject({ url: `${BASE}/admin/unmatched-transactions`, method: "GET" });
    expect(last().headers.get("x-admin-token")).toBe("local-test-admin");
    next = () => json(200, { ok: true });
    await pay.assignUnmatched(7, "TV7K3M9P");
    expect(last()).toMatchObject({ url: `${BASE}/admin/unmatched-transactions/7/assign`, method: "POST" });
    expect(body()).toEqual({ order_code: "TV7K3M9P" });
    await pay.resolveUnmatched(7, "IGNORED", "dup");
    expect(last().url).toBe(`${BASE}/admin/unmatched-transactions/7/resolve`);
    expect(body()).toEqual({ resolution: "IGNORED", note: "dup" });
    await pay.resolveUnmatched(8, "REFUNDED_MANUALLY");
    expect(body()).toEqual({ resolution: "REFUNDED_MANUALLY", note: null });
    next = () => json(200, []);
    await pay.listRefundRequests();
    expect(last().url).toBe(`${BASE}/admin/refund-requests`);
    expect(last().headers.get("x-admin-token")).toBe("local-test-admin");
    next = () => json(200, { id: "r" });
    await pay.completeRefundRequest("r-1");
    expect(last()).toMatchObject({ url: `${BASE}/admin/refund-requests/r-1/complete`, method: "POST" });
    expect(last().headers.get("x-admin-token")).toBe("local-test-admin");
  });

  it("posts the SePay webhook with Apikey auth by default", async () => {
    process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay";
    next = () => json(200, { success: true });
    await pay.postSepayWebhook({ id: 1, transferType: "in" });
    expect(last()).toMatchObject({ url: `${BASE}/webhooks/sepay`, method: "POST" });
    expect(last().headers.get("authorization")).toBe("Apikey local-test-sepay");
    expect(last().headers.has("x-sepay-signature")).toBe(false);
  });

  it("signs the SePay webhook body with HMAC-SHA256 when configured", async () => {
    process.env.SEPAY_WEBHOOK_AUTH = "hmac-sha256";
    process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay";
    next = () => json(200, { success: true });
    await pay.postSepayWebhook({ id: 2, transferType: "in" });
    const raw = last().raw ?? "";
    expect(last().headers.get("x-sepay-signature")).toBe(createHmac("sha256", "local-test-sepay").update(raw).digest("hex"));
    expect(last().headers.has("authorization")).toBe(false);
  });
});
