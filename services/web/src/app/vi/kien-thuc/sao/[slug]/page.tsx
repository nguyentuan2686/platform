import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { KnowledgeArticle, knowledgeTitle } from "@/components/KnowledgeArticle";
import { cfg } from "@/lib/config";
import { loadStars, type KnowledgePage } from "@/lib/knowledge";
import { metaDescription, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";
export const dynamicParams = false;

type Props = { params: Promise<{ slug: string }> };

export function generateStaticParams(): { slug: string }[] {
  return loadStars().map((p) => ({ slug: p.slug }));
}

function find(slug: string): KnowledgePage | undefined {
  return loadStars().find((p) => p.slug === slug);
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = find((await params).slug);
  if (!page) return {};
  return pageMeta({
    origin: cfg().webOrigin,
    path: `/vi/kien-thuc/sao/${page.slug}`,
    title: knowledgeTitle("sao", page),
    description: metaDescription(page.content),
    type: "article",
  });
}

export default async function Page({ params }: Props) {
  const slug = (await params).slug;
  const page = find(slug);
  if (!page) notFound();
  const others = loadStars().filter((p) => p.slug !== slug);
  return <KnowledgeArticle origin={cfg().webOrigin} kind="sao" page={page} others={others} />;
}
