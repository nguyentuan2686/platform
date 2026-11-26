import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { cfg } from "@/lib/config";
import { loadPalaces, type KnowledgePage } from "@/lib/knowledge";

export const dynamic = "force-static";
export const dynamicParams = false;

type Props = { params: Promise<{ slug: string }> };

export function generateStaticParams(): { slug: string }[] {
  return loadPalaces().map((p) => ({ slug: p.slug }));
}

function find(slug: string): KnowledgePage | undefined {
  return loadPalaces().find((p) => p.slug === slug);
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = find((await params).slug);
  if (!page) return {};
  return {
    title: `Cung ${page.name} trong tử vi`,
    description: page.content.slice(0, 155),
    alternates: { canonical: `${cfg().webOrigin}/vi/kien-thuc/cung/${page.slug}` },
  };
}

export default async function Page({ params }: Props) {
  const slug = (await params).slug;
  const page = find(slug);
  if (!page) notFound();
  const others = loadPalaces().filter((p) => p.slug !== slug);
  return (
    <main className="legal knowledge">
      <h1>{page.name}</h1>
      <p className="knowledge-voice">Lão Thiên Cơ nói về cung {page.name}</p>
      {page.content.split(/\n{2,}/).map((para) => (
        <p key={para}>{para}</p>
      ))}
      <p className="house-ad">
        <Link href="/vi" className="btn btn-ghost">
          Lập lá số miễn phí để xem {page.name} trong lá số của con
        </Link>
      </p>
      <h2>Các cung khác</h2>
      <ul className="knowledge-list">
        {others.map((p) => (
          <li key={p.key}>
            <Link href={`/vi/kien-thuc/cung/${p.slug}`}>{p.name}</Link>
          </li>
        ))}
      </ul>
      <p>
        <Link href="/vi/kien-thuc">Về trang kiến thức</Link>
      </p>
    </main>
  );
}
