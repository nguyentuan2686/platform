import "server-only";
import { createHmac, randomInt } from "node:crypto";
import { cfg } from "@/lib/config";

/** The SePay "money in" body the dev transfer simulator sends (contract SepayWebhookBody). */
export type SepayWebhookBody = {
  id: number;
  gateway: string;
  transactionDate: string;
  accountNumber: string;
  content: string;
  transferType: "in";
  transferAmount: number;
  referenceCode: string;
};

const vnParts = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Asia/Ho_Chi_Minh",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
});

/** `yyyy-MM-dd HH:mm:ss` in Vietnam time, as SePay sends it. */
export function vnDateTime(d: Date): string {
  const p: Record<string, string> = {};
  for (const x of vnParts.formatToParts(d)) p[x.type] = x.value;
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/**
 * SePay's webhook authentication for `raw` (the exact body sent): `Authorization: Apikey <secret>`
 * or `X-SePay-Signature: hex(HMAC-SHA256(secret, raw))`. The secret is server-only; never logged.
 */
export function sepayAuthHeaders(raw: string): Record<string, string> {
  const c = cfg();
  return c.sepayWebhookAuth === "hmac-sha256"
    ? { "x-sepay-signature": createHmac("sha256", c.sepayWebhookSecret).update(raw).digest("hex") }
    : { authorization: `Apikey ${c.sepayWebhookSecret}` };
}

/** A simulated incoming transfer for an order: its code in the content, a fresh transaction id. */
export function buildSepayWebhook(opts: { code: string; amount: number; now: Date }): {
  headers: Record<string, string>;
  body: SepayWebhookBody;
} {
  const id = randomInt(1, 2 ** 47);
  const body: SepayWebhookBody = {
    id,
    gateway: "TEST BANK",
    transactionDate: vnDateTime(opts.now),
    accountNumber: "0000000000",
    content: `${opts.code} CHUYEN KHOAN`,
    transferType: "in",
    transferAmount: opts.amount,
    referenceCode: `SIM-${id}`,
  };
  return { headers: sepayAuthHeaders(JSON.stringify(body)), body };
}
