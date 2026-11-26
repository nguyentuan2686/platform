import "server-only";
import type { VietQrPayment } from "@/lib/payment";

/**
 * VietQR instructions are returned only by createOrder, so the checkout page keeps them here:
 * in process, LRU, at most MAX_ENTRIES, each for TTL_MS (a little over the 30-minute payment
 * window). Read only after getOrder has confirmed the order belongs to the viewer.
 */
export const MAX_ENTRIES = 1000;
export const TTL_MS = 35 * 60_000;

type Entry = { payment: VietQrPayment; until: number };
// One map per process, on globalThis: Next bundles server actions and pages in separate webpack
// layers, so a module-level Map written by buyAction would not be the one the checkout page reads.
const KEY = Symbol.for("tuvi.web.paymentCache");
const g = globalThis as typeof globalThis & { [KEY]?: Map<string, Entry> };
const entries: Map<string, Entry> = (g[KEY] ??= new Map<string, Entry>());

export function put(orderId: string, payment: VietQrPayment): void {
  entries.delete(orderId);
  entries.set(orderId, { payment, until: Date.now() + TTL_MS });
  while (entries.size > MAX_ENTRIES) {
    const oldest = entries.keys().next().value as string;
    entries.delete(oldest);
  }
}

export function get(orderId: string): VietQrPayment | undefined {
  const e = entries.get(orderId);
  if (!e) return undefined;
  entries.delete(orderId);
  if (Date.now() >= e.until) return undefined;
  entries.set(orderId, e); // most recently used
  return e.payment;
}

export function size(): number {
  return entries.size;
}

export function clearPaymentCache(): void {
  entries.clear();
}
