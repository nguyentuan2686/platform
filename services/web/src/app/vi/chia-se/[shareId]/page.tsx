import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { cache } from "react";
import Board from "@/components/Board";
import Dialogue from "@/components/Dialogue";
import { cfg } from "@/lib/config";
import { GENDER_LABELS, hourLabel } from "@/lib/labels";
import { loadPublicShare } from "@/lib/publicShare";
import { cardPath } from "@/lib/shareLinks";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ shareId: string }> };

// One upstream round per request even though the page and its metadata both need the share.
const load = cache(loadPublicShare);

async function shareOr404(params: Props["params"]) {
  const { shareId } = await params;
  const share = await load(shareId);
  if (!share) notFound(); // unknown, malformed or revoked: the same page
  return { shareId, share };
}

/** Public: shows only gender, giờ and năm can chi; never indexed (also set by the X-Robots-Tag header). */
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { shareId, share } = await shareOr404(params);
  const year = share.chart.yearCanChi.nameVi;
  // The layout template adds " · Tử Vi Đẩu Số" to the tab title; link previews carry it in full.
  const title = `Lá số tử vi · năm ${year}`;
  const shareTitle = `${title} · Tử Vi Đẩu Số`;
  const images = [{ url: cardPath(shareId), width: 1080, height: 1920 }];
  return {
    metadataBase: new URL(cfg().webOrigin),
    title,
    description: share.summary,
    robots: { index: false, follow: false },
    openGraph: { title: shareTitle, description: share.summary, type: "website", locale: "vi_VN", images },
    twitter: { card: "summary_large_image", title: shareTitle, description: share.summary, images: [cardPath(shareId)] },
  };
}

export default async function SharedChartPage({ params }: Props) {
  const { share } = await shareOr404(params);
  return (
    <main className="public-chart">
      <h1 className="h-display">Lá số tử vi</h1>
      <p className="public-meta">
        {GENDER_LABELS[share.gender]} · {hourLabel(share.chart.hourBranch)} · năm {share.chart.yearCanChi.nameVi}
      </p>
      <Board chart={share.chart} variant="public" />
      <Dialogue sections={share.reading.sections} />
      <p className="house-ad">
        <Link href="/vi" className="btn">
          Lập lá số miễn phí
        </Link>
      </p>
    </main>
  );
}
