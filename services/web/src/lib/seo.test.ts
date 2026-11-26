import { describe, expect, it } from "vitest";
import { articleLd, breadcrumbLd, jsonLdText, metaDescription, pageMeta, siteLd, SITE_NAME } from "@/lib/seo";

const O = "https://tuvi.example";

describe("metaDescription", () => {
  it("keeps a short text, collapsing whitespace", () => {
    expect(metaDescription("  Cung Mệnh\n\nlà  gốc. ")).toBe("Cung Mệnh là gốc.");
  });

  it("cuts a long text at a word boundary under the limit, with an ellipsis", () => {
    const text = "Cung Mệnh là cung gốc của lá số ".repeat(10);
    const d = metaDescription(text, 60);
    expect(d.length).toBeLessThanOrEqual(60);
    expect(d.endsWith("…")).toBe(true);
    expect(text.replace(/\s+/g, " ")).toContain(d.slice(0, -1)); // whole words only
    expect(d.slice(0, -1).endsWith(" ")).toBe(false);
  });

  it("hard-cuts one very long word", () => {
    expect(metaDescription("x".repeat(300), 20)).toBe(`${"x".repeat(19)}…`);
  });
});

describe("pageMeta", () => {
  const m = pageMeta({ origin: O, path: "/vi/kien-thuc", title: "Kiến thức tử vi", description: "d" });

  it("is indexable with an absolute canonical and vi + x-default hreflang", () => {
    expect(m.robots).toEqual({ index: true, follow: true });
    expect(m.alternates).toEqual({
      canonical: `${O}/vi/kien-thuc`,
      languages: { vi: `${O}/vi/kien-thuc`, "x-default": `${O}/vi/kien-thuc` },
    });
  });

  it("keeps the title bare (the layout template adds the site name) but suffixes OG and Twitter titles", () => {
    expect(m.title).toBe("Kiến thức tử vi");
    expect(m.openGraph).toMatchObject({
      title: `Kiến thức tử vi · ${SITE_NAME}`,
      url: `${O}/vi/kien-thuc`,
      siteName: SITE_NAME,
      locale: "vi_VN",
      type: "website",
    });
    expect(m.twitter).toMatchObject({ card: "summary", title: `Kiến thức tử vi · ${SITE_NAME}` });
  });

  it("marks articles", () => {
    expect(pageMeta({ origin: O, path: "/a", title: "t", description: "d", type: "article" }).openGraph).toMatchObject({
      type: "article",
    });
  });
});

describe("JSON-LD", () => {
  it("builds a BreadcrumbList with 1-based absolute items", () => {
    const ld = breadcrumbLd(O, [
      { name: "Trang chủ", path: "/vi" },
      { name: "Kiến thức tử vi", path: "/vi/kien-thuc" },
    ]);
    expect(ld).toEqual({
      "@context": "https://schema.org",
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "Trang chủ", item: `${O}/vi` },
        { "@type": "ListItem", position: 2, name: "Kiến thức tử vi", item: `${O}/vi/kien-thuc` },
      ],
    });
  });

  it("describes the site as an Organization and a WebSite without a SearchAction", () => {
    const [org, site] = siteLd(O);
    expect(org).toMatchObject({ "@type": "Organization", name: SITE_NAME, url: `${O}/vi` });
    expect(site).toMatchObject({ "@type": "WebSite", url: `${O}/vi`, inLanguage: "vi" });
    expect(JSON.stringify(site)).not.toContain("SearchAction");
  });

  it("builds an Article without invented dates", () => {
    const a = articleLd(O, { path: "/vi/kien-thuc/sao/thien-co", headline: "h", description: "d" });
    expect(a).toMatchObject({ "@type": "Article", headline: "h", inLanguage: "vi", mainEntityOfPage: `${O}/vi/kien-thuc/sao/thien-co` });
    expect(a).not.toHaveProperty("datePublished");
  });

  it("escapes characters that could close the script element", () => {
    const s = jsonLdText({ x: "</script><b>&" });
    expect(s).not.toMatch(/[<>&]/);
    expect(JSON.parse(s)).toEqual({ x: "</script><b>&" });
  });
});
