import "server-only";
import { resolveShare } from "@/lib/identity";
import { summaryLine } from "@/lib/shareText";
import { getBasicReading, getChart } from "@/lib/upstream";
import type { BasicReading, Chart, Gender } from "@/lib/types";

/** A chart without its lunar date: the only chart shape a public page may hold. */
export type PublicChart = Omit<Chart, "lunar">;
export type PublicShare = { gender: Gender; chart: PublicChart; reading: BasicReading; summary: string };

/** Drops the lunar day/month/year; everything left (palaces, stars, can chi, giờ) is the chart itself. */
export function toPublicChart(chart: Chart): PublicChart {
  const { lunar, ...rest } = chart;
  void lunar;
  return rest;
}

/**
 * The public view of a share: identity returns the full birth, which is used here only to ask
 * chart-svc and reading-tuvi, then reduced to gender + a chart without lunar date. The birth
 * itself is dropped on return. null for an unknown or revoked id.
 */
export async function loadPublicShare(shareId: string): Promise<PublicShare | null> {
  const shared = await resolveShare(shareId);
  if (!shared) return null;
  const birth = { ...shared.birth, calendar: shared.calendar };
  const [chart, reading] = await Promise.all([getChart(birth), getBasicReading(birth)]);
  return { gender: shared.birth.gender, chart: toPublicChart(chart), reading, summary: summaryLine(reading) };
}
