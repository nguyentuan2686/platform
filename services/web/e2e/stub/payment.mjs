// payment-svc stub: products, SePay orders with VietQR instructions, order status, refund requests,
// the SePay webhook (Apikey auth; a matching transfer marks the order PAID and grants the credits or
// pass at the identity stub, like PaymentSucceeded) and an empty admin queue. In memory.
// When STUB_INTERNAL_TOKEN is set, every call but the webhook must carry it (else 401).
import { randomUUID } from "node:crypto";
import * as identity from "./identity.mjs";

export const NAME = "payment-svc";
const WINDOW_MS = 30 * 60_000;
const PRODUCTS = [
  { code: "CREDIT_1", amount: "29000.00", currency: "VND" },
  { code: "CREDIT_5", amount: "119000.00", currency: "VND" },
  { code: "PASS_MONTH", amount: "49000.00", currency: "VND" },
  { code: "PASS_YEAR", amount: "399000.00", currency: "VND" },
];
// A tiny inline image stands in for the VietQR service (no network in e2e).
const QR =
  "data:image/svg+xml;utf8," +
  encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4"><rect width="2" height="2"/><rect x="2" y="2" width="2" height="2"/></svg>');

/** order_id -> order (+ account_id) */
const orders = new Map();
const seenTx = new Set();

export function reset() {
  orders.clear();
  seenTx.clear();
}

const problem = (status, extra = {}) => ({ type: "urn:problem:stub", title: "stub", status, trace_id: "", ...extra });
const newCode = () => `TV${randomUUID().replace(/-/g, "").slice(0, 6).toUpperCase()}`;

function view(o) {
  const { account_id, ...rest } = o;
  void account_id;
  const status = rest.status === "PENDING" && Date.parse(rest.expires_at) <= Date.now() ? "EXPIRED" : rest.status;
  return { ...rest, status };
}

function webhook(req, res, body, send) {
  const want = process.env.STUB_SEPAY_SECRET ?? "";
  if (!want || req.headers.authorization !== `Apikey ${want}`) {
    send(res, 401, problem(401), "application/problem+json");
    return;
  }
  if (!body || body.id === undefined || typeof body.transferAmount !== "number") {
    send(res, 400, problem(400, { errors: [{ field: "body", message: "invalid" }] }), "application/problem+json");
    return;
  }
  if (body.transferType === "in" && !seenTx.has(String(body.id))) {
    seenTx.add(String(body.id));
    const content = String(body.content ?? "").toUpperCase();
    const o = [...orders.values()].find((x) => content.includes(x.code));
    if (o && (o.status === "PENDING") && body.transferAmount >= Number(o.amount)) {
      o.status = "PAID";
      o.paid_at = new Date().toISOString();
      identity.grant(o.account_id, o.product_code);
    }
  }
  send(res, 200, { success: true });
}

export function handle(req, res, body, send) {
  const url = new URL(req.url ?? "/", "http://stub");
  const route = `${req.method} ${url.pathname}`;
  if (route === "POST /webhooks/sepay") {
    webhook(req, res, body, send);
    return true;
  }
  const want = process.env.STUB_INTERNAL_TOKEN;
  if (want && req.headers["x-internal-token"] !== want) {
    send(res, 401, problem(401), "application/problem+json");
    return true;
  }
  if (route === "GET /products") {
    send(res, 200, PRODUCTS);
    return true;
  }
  if (route === "POST /orders") {
    const product = PRODUCTS.find((p) => p.code === body?.product_code);
    if (!product || body?.adult_confirmed !== true || typeof body?.account_id !== "string") {
      send(res, 400, problem(400, { errors: [{ field: product ? "adult_confirmed" : "product_code", message: "invalid" }] }), "application/problem+json");
      return true;
    }
    const now = Date.now();
    const o = {
      order_id: randomUUID(),
      code: newCode(),
      status: "PENDING",
      amount: product.amount,
      currency: "VND",
      provider: "SEPAY",
      product_code: product.code,
      created_at: new Date(now).toISOString(),
      expires_at: new Date(now + WINDOW_MS).toISOString(),
      account_id: body.account_id,
    };
    orders.set(o.order_id, o);
    send(res, 201, {
      ...view(o),
      payment: {
        kind: "VIETQR",
        bank_code: "TEST BANK",
        account_number: "0000000000",
        account_name: "TEST BANK",
        amount: o.amount,
        content: o.code,
        qr_image_url: QR,
      },
    });
    return true;
  }
  const accountId = url.searchParams.get("account_id");
  if (route === "GET /orders") {
    const mine = [...orders.values()].filter((o) => o.account_id === accountId).reverse();
    send(res, 200, mine.map(view));
    return true;
  }
  const one = /^\/orders\/([^/]+)(\/refund-requests)?$/.exec(url.pathname);
  if (one) {
    const o = orders.get(decodeURIComponent(one[1]));
    const owner = one[2] ? body?.account_id : accountId;
    if (!o || o.account_id !== owner) {
      send(res, 404, problem(404), "application/problem+json");
      return true;
    }
    if (req.method === "GET" && !one[2]) {
      send(res, 200, view(o));
      return true;
    }
    if (req.method === "POST" && one[2]) {
      if (o.status !== "PAID" || !o.product_code.startsWith("CREDIT_")) {
        send(res, 409, problem(409, { reason: "not_refundable" }), "application/problem+json");
        return true;
      }
      if (!identity.reverse(o.account_id, o.product_code)) {
        send(res, 409, problem(409, { reason: "credits_used" }), "application/problem+json");
        return true;
      }
      o.status = "REFUND_REQUESTED";
      send(res, 201, {
        id: randomUUID(),
        order_id: o.order_id,
        provider: "SEPAY",
        amount: o.amount,
        currency: "VND",
        status: "OPEN",
        created_at: new Date().toISOString(),
      });
      return true;
    }
    return false;
  }
  if (url.pathname.startsWith("/admin/")) {
    if (req.headers["x-admin-token"] !== process.env.STUB_ADMIN_TOKEN) {
      send(res, 401, problem(401), "application/problem+json");
      return true;
    }
    if (req.method === "GET") {
      send(res, 200, []);
      return true;
    }
  }
  return false;
}
