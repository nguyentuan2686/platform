const ZONE = "Asia/Ho_Chi_Minh";

/** "119000.00" → "119.000đ" (dot thousands, no decimals). */
export function formatVnd(amount: string): string {
  const m = /^(-?)(\d+)(?:\.\d+)?$/.exec(amount.trim());
  if (!m) return `${amount}đ`;
  const digits = m[2].replace(/^0+(?=\d)/, "");
  return `${m[1]}${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ".")}đ`;
}

function parts(iso: string): Record<string, string> {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone: ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  const out: Record<string, string> = {};
  for (const p of fmt.formatToParts(new Date(iso))) out[p.type] = p.value;
  return out;
}

/** dd/mm/yyyy in Vietnam time. */
export function formatDateVn(iso: string): string {
  const p = parts(iso);
  return `${p.day}/${p.month}/${p.year}`;
}

/** HH:mm dd/mm/yyyy in Vietnam time. */
export function formatDateTimeVn(iso: string): string {
  const p = parts(iso);
  return `${p.hour}:${p.minute} ${p.day}/${p.month}/${p.year}`;
}
