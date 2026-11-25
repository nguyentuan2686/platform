import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getProfile } from "@/lib/identity";
import { getViewer } from "@/lib/session";
import { getBasicReading, getChart, getToday } from "@/lib/upstream";
import { GENDER_LABELS, hourLabel } from "@/lib/labels";
import { todayInVietnam } from "@/lib/today";
import type { Birth } from "@/lib/types";
import Board from "@/components/Board";
import Dialogue from "@/components/Dialogue";
import TodayPanel from "@/components/TodayPanel";

export const dynamic = "force-dynamic";
// The owner's chart: personal, never indexed.
export const metadata: Metadata = { title: "Lá số của con - Lão Thiên Cơ", robots: { index: false, follow: false } };

export default async function ChartPage() {
  const viewer = await getViewer();
  if (viewer.kind === "anon") redirect("/vi");
  const profile = await getProfile(viewer.token);
  if (!profile) redirect("/vi");
  const birth: Birth = { ...profile.birth, calendar: profile.calendar };
  const [chart, reading, today] = await Promise.all([
    getChart(birth),
    getBasicReading(birth),
    getToday(birth, todayInVietnam()),
  ]);
  return (
    <main>
      <h1>Lá số tử vi</h1>
      <p>
        {GENDER_LABELS[birth.gender]} · {hourLabel(chart.hourBranch)} · năm {chart.yearCanChi.nameVi}
      </p>
      <Board chart={chart} />
      <div id="hom-nay" className="anchor">
        <TodayPanel today={today} />
      </div>
      <Dialogue sections={reading.sections} />
    </main>
  );
}
