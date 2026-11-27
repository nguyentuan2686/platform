import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Breadcrumbs } from "@/components/Breadcrumbs";
import { JsonLd } from "@/components/JsonLd";
import { LUNAR_NOTE, SECTION_ORDER, SECTION_TITLES, YEAR, YEAR_PATH, ZODIAC, birthYears, findZodiac, zodiacTitle } from "@/content/tuvi2027";
import { cfg } from "@/lib/config";
import { articleLd, breadcrumbLd, metaDescription, pageMeta } from "@/lib/seo";

export const dynamic = "force-static";
export const dynamicParams = false;

type Props = { params: Promise<{ slug: string }> };

export function generateStaticParams(): { slug: string }[] {
  return ZODIAC.map((z) => ({ slug: z.slug }));
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const z = findZodiac((await params).slug);
  if (!z) return {};
  return pageMeta({
    origin: cfg().webOrigin,
    path: `${YEAR_PATH}/${z.slug}`,
    title: zodiacTitle(z),
    description: metaDescription(z.sections.tongQuan),
    type: "article",
  });
}

export default async function ZodiacPage({ params }: Props) {
  const z = findZodiac((await params).slug);
  if (!z) notFound();
  const o = cfg().webOrigin;
  const path = `${YEAR_PATH}/${z.slug}`;
  const crumbs = [
    { name: "Trang chủ", path: "/vi" },
    { name: `Tử vi ${YEAR}`, path: YEAR_PATH },
    { name: `Tuổi ${z.chi}`, path },
  ];
  const others = ZODIAC.filter((x) => x.slug !== z.slug);
  return (
    <main className="legal knowledge yearly">
      <JsonLd data={breadcrumbLd(o, crumbs)} />
      <JsonLd data={articleLd(o, { path, headline: zodiacTitle(z), description: metaDescription(z.sections.tongQuan) })} />
      <Breadcrumbs crumbs={crumbs} />
      <h1>{zodiacTitle(z)}</h1>
      <p className="knowledge-voice">
        Tuổi {z.chi} ({z.animal}) · {z.relation} · sinh năm {birthYears(z.index).join(", ")}
      </p>
      {SECTION_ORDER.map((k) => (
        <section key={k}>
          <h2>{SECTION_TITLES[k]}</h2>
          <p>{z.sections[k]}</p>
        </section>
      ))}
      <p className="house-ad">
        <Link href="/vi" className="btn btn-ghost">
          Lập lá số miễn phí để xem năm {YEAR} đi vào cung nào của con
        </Link>
      </p>
      <p className="updated">{LUNAR_NOTE}</p>
      <h2>Tuổi khác</h2>
      <ul className="knowledge-list">
        {others.map((x) => (
          <li key={x.slug}>
            <Link href={`${YEAR_PATH}/${x.slug}`}>Tuổi {x.chi}</Link>
          </li>
        ))}
      </ul>
      <p>
        <Link href={YEAR_PATH}>Về Tử vi {YEAR}</Link>
      </p>
    </main>
  );
}
