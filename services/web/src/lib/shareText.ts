import "server-only";
import { cfg } from "@/lib/config";
import { sharePath } from "@/lib/shareLinks";
import type { BasicReading } from "@/lib/types";

export { facebookShareUrl } from "@/lib/shareLinks";

const MAX = 120;

/** The first sentence of the "menh" section's first line, at most 120 chars cut on a word with "…". */
export function summaryLine(reading: BasicReading): string {
  const line = reading.sections.find((s) => s.id === "menh")?.lines[0]?.trim();
  if (!line) return "";
  const sentence = line.split(/(?<=[.!?])\s+/)[0];
  if (sentence.length <= MAX) return sentence;
  const cut = sentence.slice(0, MAX);
  const lastSpace = cut.lastIndexOf(" ");
  // A space right after the cut means the cut is already a word end.
  const head = sentence[MAX] === " " ? cut : lastSpace > 0 ? cut.slice(0, lastSpace) : cut;
  return head.trimEnd() + "…";
}

export function shareUrl(shareId: string): string {
  return cfg().webOrigin + sharePath(shareId);
}
