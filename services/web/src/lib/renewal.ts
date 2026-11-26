import type { Entitlements, PassKind } from "@/lib/identity";

export type RenewalNotice = { kind: PassKind; daysLeft: number; endsAt: string };

const WINDOW_MS = 3 * 86_400_000;
const vnDay = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit" });

/** Days since the epoch of the Vietnam calendar date of `t`. */
function vnDayNumber(t: Date): number {
  return Date.parse(`${vnDay.format(t)}T00:00:00Z`) / 86_400_000;
}

/**
 * The renewal reminder for a pass ending within 3 days (prepaid: VietQR never charges by itself).
 * `daysLeft` counts Vietnam calendar days from today to the end date, at least 1.
 */
export function renewalNotice(pass: Entitlements["pass"], now: Date): RenewalNotice | null {
  if (!pass) return null;
  const ends = Date.parse(pass.ends_at);
  if (!Number.isFinite(ends)) return null;
  const left = ends - now.getTime();
  if (left <= 0 || left > WINDOW_MS) return null;
  const days = vnDayNumber(new Date(ends)) - vnDayNumber(now);
  return { kind: pass.kind, daysLeft: Math.max(1, days), endsAt: pass.ends_at };
}
