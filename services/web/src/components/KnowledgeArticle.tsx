import Link from "next/link";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import type { KnowledgePage } from "@/lib/knowledge";
import { articleLd, breadcrumbLd, metaDescription } from "@/lib/seo";

type Props = Readonly<{
  origin: string;
  /** "cung" or "sao": the URL segment under /vi/kien-thuc. */
  kind: "cung" | "sao";
  page: KnowledgePage;
  others: readonly KnowledgePage[];
}>;

const LABEL = { cung: "Cung", sao: "Sao" } as const;
const OTHERS = { cung: "Các cung khác", sao: "Các sao chính khác" } as const;

/** The public title of a knowledge page, shared by its metadata, JSON-LD headline and breadcrumb. */
export const knowledgeTitle = (kind: Props["kind"], page: KnowledgePage): string =>
  `${LABEL[kind]} ${page.name} trong tử vi`;

/** A server-rendered knowledge page: breadcrumb (+ JSON-LD), the text, the house ad, links to its siblings. */
export function KnowledgeArticle({ origin, kind, page, others }: Props) {
  const path = `/vi/kien-thuc/${kind}/${page.slug}`;
  const crumbs = [
    { name: "Trang chủ", path: "/vi" },
    { name: "Kiến thức tử vi", path: "/vi/kien-thuc" },
    { name: `${LABEL[kind]} ${page.name}`, path },
  ];
  return (
    <main className="legal knowledge">
      <JsonLd data={breadcrumbLd(origin, crumbs)} />
      <JsonLd data={articleLd(origin, { path, headline: knowledgeTitle(kind, page), description: metaDescription(page.content) })} />
      <Breadcrumbs crumbs={crumbs} />
      <h1>{page.name}</h1>
      <p className="knowledge-voice">
        Lão Thiên Cơ nói về {LABEL[kind].toLowerCase()} {page.name}
      </p>
      {page.content.split(/\n{2,}/).map((para) => (
        <p key={para}>{para}</p>
      ))}
      <p className="house-ad">
        <Link href="/vi" className="btn btn-ghost">
          Lập lá số miễn phí để xem {page.name} trong lá số của con
        </Link>
      </p>
      <h2>{OTHERS[kind]}</h2>
      <ul className="knowledge-list">
        {others.map((p) => (
          <li key={p.key}>
            <Link href={`/vi/kien-thuc/${kind}/${p.slug}`}>{p.name}</Link>
          </li>
        ))}
      </ul>
      <p>
        <Link href="/vi/kien-thuc">Về trang kiến thức</Link>
      </p>
    </main>
  );
}
