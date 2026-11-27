import type { Metadata } from "next";
import Link from "next/link";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import { cfg } from "@/lib/config";
import { loadPalaces, loadStars } from "@/lib/knowledge";
import { breadcrumbLd, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";

export const metadata: Metadata = pageMeta({
  origin: cfg().webOrigin,
  path: "/vi/kien-thuc",
  title: "Kiến thức tử vi",
  description: "12 cung và 14 chính tinh trong tử vi đẩu số, do Lão Thiên Cơ biên soạn từ kinh sách cổ.",
});

const CRUMBS = [
  { name: "Trang chủ", path: "/vi" },
  { name: "Kiến thức tử vi", path: "/vi/kien-thuc" },
];

export default function KnowledgeIndex() {
  const palaces = loadPalaces();
  const stars = loadStars();
  return (
    <main className="legal knowledge">
      <JsonLd data={breadcrumbLd(cfg().webOrigin, CRUMBS)} />
      <Breadcrumbs crumbs={CRUMBS} />
      <h1>Kiến thức tử vi</h1>
      <p>Ta ghi lại đây ý chính của từng cung và từng sao, để con đọc trước khi xem lá số của mình.</p>
      <h2>12 cung</h2>
      <ul className="knowledge-list">
        {palaces.map((p) => (
          <li key={p.key}>
            <Link href={`/vi/kien-thuc/cung/${p.slug}`}>Cung {p.name}</Link>
          </li>
        ))}
      </ul>
      <h2>14 chính tinh</h2>
      <ul className="knowledge-list">
        {stars.map((s) => (
          <li key={s.key}>
            <Link href={`/vi/kien-thuc/sao/${s.slug}`}>Sao {s.name}</Link>
          </li>
        ))}
      </ul>
    </main>
  );
}
