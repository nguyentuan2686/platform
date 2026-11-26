import type { Metadata } from "next";
import Link from "next/link";
import { DeleteHistory } from "@/components/DeleteHistory";
import { formatDateVn } from "@/lib/format";
import { listReadings, type QaReadingSummary } from "@/lib/qa";
import { qaStatusLabel } from "@/lib/qaText";
import { getViewer } from "@/lib/session";
import { TOPICS } from "@/lib/topics";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Bài đã hỏi - Lão Thiên Cơ", robots: { index: false, follow: false } };

export default async function HistoryPage() {
  const viewer = await getViewer();
  // Without a session there is nothing yet: the empty state, not a login wall (a guest has history too).
  const readings: QaReadingSummary[] = viewer.kind === "anon" ? [] : (await listReadings(viewer.token)).readings;
  return (
    <main className="history">
      <h1 className="h-display">Bài đã hỏi</h1>
      {readings.length === 0 ? (
        <>
          <p className="lead">Con chưa hỏi câu nào.</p>
          <Link href="/vi/hoi" className="btn">
            Hỏi thầy
          </Link>
        </>
      ) : (
        <>
          <ul className="history-list">
            {readings.map((r) => {
              const topic = TOPICS.find((t) => t.code === r.topic);
              return (
                <li key={r.reading_id}>
                  <Link href={`/vi/hoi/${r.reading_id}`} className="history-row">
                    <span className="topic-glyph" aria-hidden="true">
                      {topic?.glyph}
                    </span>
                    <span className="history-text">
                      <span className="t">{topic?.label}</span>
                      <span className="s">
                        {formatDateVn(r.created_at)} · {r.kind === "PAID" ? "Lượt" : "Miễn phí"}
                        {r.kind === "PAID" && r.follow_ups_left > 0 && ` · Còn ${r.follow_ups_left} câu nối tiếp`}
                      </span>
                    </span>
                    <span className="r">{qaStatusLabel(r.status)}</span>
                  </Link>
                </li>
              );
            })}
          </ul>
          <DeleteHistory />
        </>
      )}
    </main>
  );
}
