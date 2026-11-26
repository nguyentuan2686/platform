import { createHmac } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import { buildSepayWebhook, sepayAuthHeaders, vnDateTime } from "@/lib/sepaySim";

afterEach(() => {
  delete process.env.SEPAY_WEBHOOK_AUTH;
  delete process.env.SEPAY_WEBHOOK_SECRET;
});

const NOW = new Date("2026-11-24T17:30:00Z");

describe("sepaySim", () => {
  it("formats the transaction date in Vietnam time", () => {
    expect(vnDateTime(NOW)).toBe("2026-11-25 00:30:00");
  });

  it("builds a SePay money-in body for the order code", () => {
    process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay-secret";
    const { body } = buildSepayWebhook({ code: "TV7K3M9P", amount: 29000, now: NOW });
    expect(Number.isInteger(body.id)).toBe(true);
    expect(body.id).toBeGreaterThan(0);
    expect(body).toEqual({
      id: body.id,
      gateway: "TEST BANK",
      transactionDate: "2026-11-25 00:30:00",
      accountNumber: "0000000000",
      content: "TV7K3M9P CHUYEN KHOAN",
      transferType: "in",
      transferAmount: 29000,
      referenceCode: `SIM-${body.id}`,
    });
  });

  it("uses a fresh transaction id every time", () => {
    const a = buildSepayWebhook({ code: "TV1", amount: 1, now: NOW }).body.id;
    const b = buildSepayWebhook({ code: "TV1", amount: 1, now: NOW }).body.id;
    expect(a).not.toBe(b);
  });

  it("authenticates with the exact Apikey header by default", () => {
    process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay-secret";
    const { headers } = buildSepayWebhook({ code: "TV1", amount: 1, now: NOW });
    expect(headers).toEqual({ authorization: "Apikey local-test-sepay-secret" });
  });

  it("signs the raw body with HMAC-SHA256 when configured", () => {
    process.env.SEPAY_WEBHOOK_AUTH = "hmac-sha256";
    process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay-secret";
    const raw = '{"id":1,"content":"TV7K3M9P CHUYEN KHOAN"}';
    // Precomputed: printf '%s' "$raw" | openssl dgst -sha256 -hmac local-test-sepay-secret
    expect(sepayAuthHeaders(raw)).toEqual({
      "x-sepay-signature": "b8765b0e4e17f3b74a8893391e893484ec6d1949a290b0be3362def5db541482",
    });
    const { headers, body } = buildSepayWebhook({ code: "TV1", amount: 1, now: NOW });
    const want = createHmac("sha256", "local-test-sepay-secret").update(JSON.stringify(body)).digest("hex");
    expect(headers).toEqual({ "x-sepay-signature": want });
  });
});
