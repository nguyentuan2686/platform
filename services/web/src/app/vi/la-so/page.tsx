import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { degradeOrThrow } from "@/lib/degrade";
import { getProfile, getShare, type ShareLink } from "@/lib/identity";
import { shareUrl, summaryLine } from "@/lib/shareText";
import { getViewer } from "@/lib/session";
import { getBasicReading, getChart, getToday } from "@/lib/upstream";
import { GENDER_LABELS, hourLabel } from "@/lib/labels";
import { todayInVietnam } from "@/lib/today";
import type { Birth } from "@/lib/types";
import Board from "@/components/Board";
import Dialogue from "@/components/Dialogue";
import { SharePanel } from "@/components/SharePanel";
import TodayPanel from "@/components/TodayPanel";

export const dynamic = "force-dynamic";
// The owner's chart: personal, never indexed.
export const metadata: Metadata = { title: "Lá số của con", robots: { index: false, follow: false } };

/** The active public link; trouble with identity hides the panel state (shows "create"), never the chart. */
async function activeShare(token: string): Promise<ShareLink | null> {
  try {
    return await getShare(token);
  } catch (e) {
    degradeOrThrow(e, "share-state");
    return null;
  }
}

export default async function ChartPage() {
  const viewer = await getViewer();
  if (viewer.kind === "anon") redirect("/vi");
  const profile = await getProfile(viewer.token);
  if (!profile) redirect("/vi");
  const birth: Birth = { ...profile.birth, calendar: profile.calendar };
  const pass = viewer.kind === "account" ? viewer.entitlements.pass : null;
  const hasPass = pass !== null && Date.parse(pass.ends_at) > Date.now();
  const [chart, reading, today] = await Promise.all([
    getChart(birth),
    getBasicReading(birth),
    getToday(birth, todayInVietnam()),
  ]);
  const share = await activeShare(viewer.token);
  return (
    <main>
      <h1>Lá số tử vi</h1>
      <p>
        {GENDER_LABELS[birth.gender]} · {hourLabel(chart.hourBranch)} · năm {chart.yearCanChi.nameVi}
      </p>
      <Board chart={chart} />
      <SharePanel share={share} summary={summaryLine(reading)} url={share ? shareUrl(share.share_id) : ""} />
      <div id="hom-nay" className="anchor">
        <TodayPanel today={today} />
      </div>
      {!hasPass && (
        <p className="house-ad">
          <Link href="/vi/mua">Hỏi sâu hơn mỗi ngày với thẻ năm, hỏi không giới hạn.</Link>
        </p>
      )}
      <Dialogue sections={reading.sections} />
    </main>
  );
}
