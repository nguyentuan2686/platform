import { notFound } from "next/navigation";
import { birthStore } from "@/lib/birthStore";
import { getBasicReading, getChart, getToday } from "@/lib/upstream";
import { GENDER_LABELS, hourLabel } from "@/lib/labels";
import { todayInVietnam } from "@/lib/today";

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
      <section>
        <h2>Hôm nay ({today.lunar})</h2>
        <p>{today.opening}</p>
        <ul>
          {today.areas.map((a) => (
            <li key={a.area}>
              {a.label}: {a.line}
            </li>
          ))}
        </ul>
        <p>{today.closing}</p>
      </section>
      {reading.sections.map((s) => (
        <section key={s.id}>
          {s.title && <h2>{s.title}</h2>}
          {s.lines.map((l, i) => (
            <p key={i}>{l}</p>
          ))}
        </section>
      ))}
    </main>
  );
}
