import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { FollowUpBox } from "@/components/FollowUpBox";
import { QaMessages } from "@/components/QaMessages";
import { ReadingStatusPoller } from "@/components/ReadingStatusPoller";
import { formatDateTimeVn } from "@/lib/format";
import { isUuid } from "@/lib/ids";
import { getReading, type QaReading } from "@/lib/qa";
import { isWaiting, qaStatusLabel } from "@/lib/qaText";
import { getViewer } from "@/lib/session";
import { TOPICS } from "@/lib/topics";
import { UpstreamProblem } from "@/lib/upstream";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Bài đã hỏi", robots: { index: false, follow: false } };

export default async function ReadingPage({ params }: { params: Promise<{ readingId: string }> }) {
  const { readingId } = await params;
  if (!isUuid(readingId)) notFound();
  const viewer = await getViewer();
  if (viewer.kind === "anon") notFound(); // no session, nothing of its own to show
  let reading: QaReading;
  try {
    reading = await getReading(viewer.token, readingId);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) notFound(); // unknown, or another subject's
    throw e;
  }
  const topic = TOPICS.find((t) => t.code === reading.topic);
  return (
    <main className="reading-page">
      <h1 className="h-display">
        <span className="topic-glyph" aria-hidden="true">
          {topic?.glyph}
        </span>{" "}
        {topic?.label}
      </h1>
      <p className="qa-meta">
        {formatDateTimeVn(reading.created_at)} · {reading.kind === "PAID" ? "Lượt" : "Miễn phí"} ·{" "}
        <span className="qa-status">{qaStatusLabel(reading.status)}</span>
      </p>
      <QaMessages
        messages={reading.messages}
        status={reading.status}
        notice={reading.notice}
        disclaimer={reading.disclaimer}
      />
      {isWaiting(reading.status) && <ReadingStatusPoller readingId={reading.reading_id} status={reading.status} />}
      {reading.kind === "PAID" && reading.status === "ANSWERED" && reading.follow_ups_left > 0 && (
        <FollowUpBox readingId={reading.reading_id} left={reading.follow_ups_left} />
      )}
      <p className="qa-history">
        <Link href="/vi/hoi" className="tag">
          Hỏi câu khác
        </Link>{" "}
        <Link href="/vi/lich-su" className="tag">
          Bài đã hỏi
        </Link>
      </p>
    </main>
  );
}
