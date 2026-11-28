import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import { NumberCheckForm } from "@/components/NumberCheckForm";
import { KINDS, XEM_SO_PATH, findKind } from "@/content/xemSo";
import { cfg } from "@/lib/config";
import { breadcrumbLd, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";
export const dynamicParams = false;

type Props = { params: Promise<{ loai: string }> };

export function generateStaticParams(): { loai: string }[] {
  return KINDS.map((k) => ({ loai: k.slug }));
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const k = findKind((await params).loai);
  if (!k) return {};
  return pageMeta({ origin: cfg().webOrigin, path: `${XEM_SO_PATH}/${k.slug}`, title: k.title, description: k.description });
}

/** One form (posted by JS, the number never in a URL) and the static method text for search engines. */
export default async function XemSoKindPage({ params }: Props) {
  const k = findKind((await params).loai);
  if (!k) notFound();
  const path = `${XEM_SO_PATH}/${k.slug}`;
  const crumbs = [
    { name: "Trang chủ", path: "/vi" },
    { name: "Xem số hợp mệnh", path: XEM_SO_PATH },
    { name: k.name, path },
  ];
  return (
    <main className="legal knowledge xem-so">
      <JsonLd data={breadcrumbLd(cfg().webOrigin, crumbs)} />
      <Breadcrumbs crumbs={crumbs} />
      <h1>{k.title}</h1>
      {k.intro.map((p) => (
        <p key={p}>{p}</p>
      ))}
      <NumberCheckForm kind={k.kind} numberLabel={k.numberLabel} placeholder={k.placeholder} />
      <h2>Ta xem số thế nào</h2>
      {k.method.map((p) => (
        <p key={p}>{p}</p>
      ))}
      <h2>Xem loại số khác</h2>
      <ul className="knowledge-list">
        {KINDS.filter((x) => x.slug !== k.slug).map((x) => (
          <li key={x.slug}>
            <Link href={`${XEM_SO_PATH}/${x.slug}`}>{x.title}</Link>
          </li>
        ))}
      </ul>
    </main>
  );
}
