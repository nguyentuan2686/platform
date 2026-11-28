import type { Metadata } from "next";
import Link from "next/link";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import { DAILY_LIMIT_NOTE, HUB_DESCRIPTION, HUB_INTRO, HUB_TITLE, KINDS, XEM_SO_PATH } from "@/content/xemSo";
import { cfg } from "@/lib/config";
import { breadcrumbLd, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";

export const metadata: Metadata = pageMeta({ origin: cfg().webOrigin, path: XEM_SO_PATH, title: HUB_TITLE, description: HUB_DESCRIPTION });

const CRUMBS = [
  { name: "Trang chủ", path: "/vi" },
  { name: "Xem số hợp mệnh", path: XEM_SO_PATH },
];

export default function XemSoHub() {
  return (
    <main className="legal knowledge xem-so">
      <JsonLd data={breadcrumbLd(cfg().webOrigin, CRUMBS)} />
      <Breadcrumbs crumbs={CRUMBS} />
      <h1>{HUB_TITLE}</h1>
      <p className="knowledge-voice">Lão Thiên Cơ xem số giúp con</p>
      {HUB_INTRO.map((p) => (
        <p key={p}>{p}</p>
      ))}
      <ul className="zodiac-grid">
        {KINDS.map((k) => (
          <li key={k.slug}>
            <Link href={`${XEM_SO_PATH}/${k.slug}`}>
              <strong>{k.name}</strong>
              <span>{k.title}</span>
            </Link>
          </li>
        ))}
      </ul>
      <p className="updated">{DAILY_LIMIT_NOTE}</p>
      <p className="house-ad">
        <Link href="/vi" className="btn btn-ghost">
          Xem lá số tử vi của con
        </Link>
      </p>
    </main>
  );
}
