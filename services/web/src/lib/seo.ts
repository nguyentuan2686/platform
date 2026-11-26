import type { Metadata } from "next";

/** Pure SEO helpers (no env reads): callers pass the site origin from `cfg().webOrigin`. */

/** The one site name; the root layout's title template appends it to every page title. */
export const SITE_NAME = "Tử Vi Đẩu Số";
const LOCALE = "vi_VN";

/**
 * A meta description: whitespace collapsed, cut at a word boundary under `max` characters with "…".
 * Google shows about 150-160 characters; a cut mid-word reads as broken in the result snippet.
 */
export function metaDescription(text: string, max = 155): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat.length <= max) return flat;
  const cut = flat.slice(0, max - 1);
  const space = cut.lastIndexOf(" ");
  const head = (space > max / 2 ? cut.slice(0, space) : cut).replace(/[\s,.;:!?-]+$/, "");
  return `${head}…`;
}

type PageMetaInput = Readonly<{
  origin: string;
  /** Path of the page, starting with "/". */
  path: string;
  /** Page title without the site suffix (the layout template adds it). */
  title: string;
  description: string;
  type?: "website" | "article";
}>;

/**
 * Metadata for an indexable public page: canonical, hreflang (vi + x-default until en/zh-Hant
 * exist, F3), Open Graph and Twitter card. Open Graph titles are not templated, so they carry
 * the site name themselves.
 */
export function pageMeta({ origin, path, title, description, type = "website" }: PageMetaInput): Metadata {
  const url = `${origin}${path}`;
  const fullTitle = `${title} · ${SITE_NAME}`;
  return {
    title,
    description,
    robots: { index: true, follow: true },
    alternates: { canonical: url, languages: { vi: url, "x-default": url } },
    openGraph: { title: fullTitle, description, url, siteName: SITE_NAME, locale: LOCALE, type },
    twitter: { card: "summary", title: fullTitle, description },
  };
}

type Crumb = Readonly<{ name: string; path: string }>;

/** schema.org BreadcrumbList; must match the breadcrumb shown on the page. */
export function breadcrumbLd(origin: string, crumbs: readonly Crumb[]): Record<string, unknown> {
  return {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: crumbs.map((c, i) => ({
      "@type": "ListItem",
      position: i + 1,
      name: c.name,
      item: `${origin}${c.path}`,
    })),
  };
}

/** schema.org WebSite + Organization for the home page (no SearchAction: the site has no search). */
export function siteLd(origin: string): Record<string, unknown>[] {
  const url = `${origin}/vi`;
  const org = { "@type": "Organization", "@id": `${url}#organization`, name: SITE_NAME, url };
  return [
    { "@context": "https://schema.org", ...org },
    {
      "@context": "https://schema.org",
      "@type": "WebSite",
      "@id": `${url}#website`,
      name: SITE_NAME,
      url,
      inLanguage: "vi",
      publisher: { "@id": org["@id"] },
    },
  ];
}

/** schema.org Article for a knowledge page (no dates: the corpus carries none we could state honestly). */
export function articleLd(origin: string, a: Readonly<{ path: string; headline: string; description: string }>) {
  const url = `${origin}${a.path}`;
  const org = { "@type": "Organization", name: SITE_NAME, url: `${origin}/vi` };
  return {
    "@context": "https://schema.org",
    "@type": "Article",
    headline: a.headline,
    description: a.description,
    inLanguage: "vi",
    mainEntityOfPage: url,
    url,
    author: org,
    publisher: org,
  };
}

/**
 * JSON for a `<script type="application/ld+json">`: `<`, `>` and `&` escaped so page text can
 * never close the script element (Next.js JSON-LD guidance).
 */
export function jsonLdText(data: unknown): string {
  return JSON.stringify(data).replace(/</g, "\\u003c").replace(/>/g, "\\u003e").replace(/&/g, "\\u0026");
}
