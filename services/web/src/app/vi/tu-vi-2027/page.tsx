import type { Metadata } from "next";
import Link from "next/link";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import { LUNAR_NOTE, YEAR, YEAR_INTRO, YEAR_PATH, ZODIAC } from "@/content/tuvi2027";
import { cfg } from "@/lib/config";
import { articleLd, breadcrumbLd, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";

const TITLE = `Tử vi ${YEAR} Đinh Mùi cho 12 con giáp`;
const DESCRIPTION =
  "Tử vi năm 2027 Đinh Mùi cho 12 con giáp: công việc, tiền bạc, tình cảm, sức khỏe và lời khuyên của Lão Thiên Cơ. Lập lá số miễn phí để xem riêng cho bạn.";

export const metadata: Metadata = pageMeta({ origin: cfg().webOrigin, path: YEAR_PATH, title: TITLE, description: DESCRIPTION, type: "article" });

const CRUMBS = [
  { name: "Trang chủ", path: "/vi" },
  { name: `Tử vi ${YEAR}`, path: YEAR_PATH },
];

export default function Year2027() {
  const o = cfg().webOrigin;
  return (
    <main className="legal knowledge yearly">
      <JsonLd data={breadcrumbLd(o, CRUMBS)} />
      <JsonLd data={articleLd(o, { path: YEAR_PATH, headline: TITLE, description: DESCRIPTION })} />
      <Breadcrumbs crumbs={CRUMBS} />
      <h1>{TITLE}</h1>
      <p className="knowledge-voice">Lão Thiên Cơ nói về năm Đinh Mùi</p>
      {YEAR_INTRO.map((p) => (
        <p key={p}>{p}</p>
      ))}
      <p className="house-ad">
        <Link href="/vi" className="btn btn-ghost">
          Lập lá số miễn phí để xem năm {YEAR} của riêng con
        </Link>
      </p>
      <h2>Chọn tuổi của con</h2>
      <ul className="zodiac-grid">
        {ZODIAC.map((z) => (
          <li key={z.slug}>
            <Link href={`${YEAR_PATH}/${z.slug}`}>
              <strong>Tuổi {z.chi}</strong>
              <span>
                {z.animal} · {z.relation}
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <p className="updated">{LUNAR_NOTE}</p>
    </main>
  );
}
