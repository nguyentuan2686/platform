import { notFound } from "next/navigation";
import { birthStore } from "@/lib/birthStore";
import { getBasicReading, getChart, getToday } from "@/lib/upstream";
import { GENDER_LABELS, hourLabel } from "@/lib/labels";
import { todayInVietnam } from "@/lib/today";
import Board from "@/components/Board";
import Dialogue from "@/components/Dialogue";
import TodayPanel from "@/components/TodayPanel";

export const dynamic = "force-dynamic";

export default async function ResultPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const birth = birthStore.get(id);
  if (!birth) notFound();
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
      <TodayPanel today={today} />
      <Dialogue sections={reading.sections} />
    </main>
  );
}
