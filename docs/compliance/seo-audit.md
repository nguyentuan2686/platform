# SEO audit: `services/web` (tử vi, tiếng Việt)

- Date: 2026-11-26 · Scope: Next.js 15 App Router web, routes under `/vi` · Author: Claude
- Measured against Google Search Central guidance (links below). G6 in the fortune spec says search is the main traffic channel, so the public pages (home, `/vi/kien-thuc/**`, legal) must be crawlable, server-rendered and described well, and every private page must stay out of the index.

## Summary

| Area | Before | Now |
|---|---|---|
| Private pages kept out of the index | `noindex` on each page, **but also `Disallow`ed in robots.txt**, so Google could not see the `noindex`. Share links posted on Zalo/Facebook could be indexed as bare URLs | Fixed: robots.txt disallows only `/api/` and `/vi/dang-nhap/google` (an OAuth redirect). Every private page keeps `noindex`; the test checks no `noindex` page is blocked from crawling |
| Share card image `/vi/chia-se/{id}/the.png` | Could appear in Google Images once crawlable | Fixed: `X-Robots-Tag: noindex` on the PNG |
| Canonical URL | Knowledge pages only | Fixed: home, knowledge index and pages, 3 legal pages (absolute, self-referencing) |
| `hreflang` | None | Fixed: `vi` + `x-default` on every indexable page, from one helper (`pageMeta`), so F3 (en, zh-Hant) only adds entries |
| Home `/vi` metadata | Layout default title "Tử vi - Lão Thiên Cơ" and description "Lá số tử vi và luận giải." | Fixed: own title, a real description, canonical, Open Graph |
| Meta descriptions | `content.slice(0, 155)`, cut mid-word | Fixed: `metaDescription()` cuts at a word boundary with "…", ≤ 155 chars |
| Open Graph / Twitter | Share pages only | Fixed: `og:title/description/url/site_name/locale=vi_VN/type` and Twitter `summary` on indexable pages |
| Structured data | None | Fixed: `Organization` + `WebSite` on `/vi`; `BreadcrumbList` + `Article` on knowledge pages, with a visible breadcrumb that matches |
| 404 | Next.js default English page | Fixed: Vietnamese `app/not-found.tsx` (still HTTP 404, `noindex`) with links to `/vi` and `/vi/kien-thuc` |
| Internal links | Knowledge reachable only from a link on `/vi` | Fixed: "Kiến thức tử vi" in the footer of every page |
| Sitemap | 31 public URLs, no private ones | Unchanged (correct); test now also checks every entry is an indexable page |
| Language tag | `<html lang="vi">` | Unchanged (correct) |
| Rendering | Knowledge pages `force-static` (SSG), legal pages static, `/vi` server-rendered | Unchanged (correct): all text is in the first HTML response |
| Birth data in URLs / share pages | Share URL holds only a random share id; public page shows gender, giờ, năm can chi; lunar date stripped (`toPublicChart`); `noindex, nofollow` + `no-store` | Unchanged (correct) |

## Findings in detail

### 1. robots.txt blocked the pages it meant to hide (fixed, highest impact)
Google: "For the noindex rule to be effective, the page or resource must not be blocked by a robots.txt file" ([Block indexing with noindex](https://developers.google.com/search/docs/crawling-indexing/block-indexing)). A blocked URL can still be indexed without its content when other sites link to it ([robots.txt intro](https://developers.google.com/search/docs/crawling-indexing/robots/intro)). Share links (`/vi/chia-se/{id}`) are made to be posted publicly, so they were the most exposed. The page content holds no full birth date, but an indexed share URL is still unwanted.

Change: `src/app/robots.ts` now disallows `/api/` (JSON, no pages) and `/vi/dang-nhap/google` (starts an OAuth redirect). The page-level `noindex` (metadata `robots: { index: false }` on every private page, and the `X-Robots-Tag` header on share pages) does the hiding. Tests: `src/app/seo.test.ts` ("does not block the noindex page …"), `e2e/seo.spec.ts`.

### 2. Canonical + hreflang (fixed)
Canonical: [Consolidate duplicate URLs](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls). hreflang: [Localized versions](https://developers.google.com/search/docs/specialty/international/localized-versions). With one language `hreflang` is optional; we emit `vi` and `x-default` now so adding `en`/`zh-Hant` in F3 is a one-line change in `pageMeta` and cannot be forgotten per page. Canonicals use `WEB_ORIGIN` at build time for static pages (same as the sitemap), so **the prod image must be built with the real origin** (`--build-arg WEB_ORIGIN=https://…`, Plan 4a).

### 3. Titles and descriptions (fixed)
[Title links](https://developers.google.com/search/docs/appearance/title-link), [Snippets / meta description](https://developers.google.com/search/docs/appearance/snippet). Every indexable page has a unique title; the layout template adds " · Tử Vi Đẩu Số" once (existing test). Descriptions are now whole words and ≤ 155 characters.

### 4. Structured data (fixed, honest subset)
[Intro to structured data](https://developers.google.com/search/docs/appearance/structured-data/intro-structured-data), [Breadcrumb](https://developers.google.com/search/docs/appearance/structured-data/breadcrumb), [Article](https://developers.google.com/search/docs/appearance/structured-data/article), [Organization](https://developers.google.com/search/docs/appearance/structured-data/organization), [Site names (WebSite)](https://developers.google.com/search/docs/appearance/site-names).
- Added: `Organization` + `WebSite` (home), `BreadcrumbList` + `Article` (knowledge pages). The breadcrumb JSON-LD mirrors a visible breadcrumb, as Google requires markup to describe visible content.
- Not added, on purpose: `FAQPage` (Google shows FAQ rich results only for well-known government and health sites since 2023; [FAQ docs](https://developers.google.com/search/docs/appearance/structured-data/faqpage)); `SearchAction` (the site has no search, and the sitelinks search box was retired in 2024); `datePublished` (the corpus has no honest dates); `logo` (no brand artwork yet).
- JSON-LD is serialised with `<`, `>`, `&` escaped (`jsonLdText`), per the [Next.js JSON-LD guide](https://nextjs.org/docs/app/guides/json-ld).

### 5. Open Graph / social previews (fixed, image pending)
Indexable pages now carry OG and Twitter tags. **Gap left:** no default `og:image`. A site preview image needs brand artwork (the Lão Thiên Cơ illustration is still a placeholder, spec §14.2). When it exists, add `app/vi/opengraph-image.png` (Next.js file convention). Share pages already have their own 1080×1920 card.

### 6. Core Web Vitals risks (reviewed, no change here)
[Core Web Vitals](https://developers.google.com/search/docs/appearance/core-web-vitals).
- Fonts: self-hosted since the fonts PR (#31): `layout.tsx` imports `src/styles/fonts.css`, with no runtime call to Google Fonts and no build-time download; `src/fontPolicy.test.ts` guards it. The fonts carry no preload hints now, so measure the LCP text's font swap on the real host. Risk to watch: the CJK font's size; subset it to the glyphs used (紫微斗數, 命, 今, 問, 我 …).
- Images: no raster `<img>` on content pages (SVG board and character), so no LCP image to optimise. The VietQR image is on a private page.
- JS: knowledge and legal pages are server components; the only client JS on them is the layout's `TabBar`, `BalanceChip` and `PassBanner`. `npm run check:bundle` guards secrets, not size. Measure with Lighthouse/PageSpeed on the real host after Plan 4a.

### 7. Rendering (OK)
[JavaScript SEO basics](https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics). Knowledge pages are `force-static` with `dynamicParams = false` (unknown slugs are a real 404), so Googlebot gets full text without running JS.

### 8. Sitemap (OK, one optional improvement)
[Build a sitemap](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap). 31 URLs: `/vi`, the knowledge index, 12 palaces, 14 stars, 3 legal pages. No private URL. `lastmod` is omitted on purpose: Google uses it only when it is consistently accurate, and a build timestamp is not. Add it when the knowledge corpus records a per-record `updated_at`.

### 9. Redirects and 404 (fixed 404; redirect left as is)
- `/` → `/vi` is a 307 (`redirect()` in `app/page.tsx`). Kept temporary on purpose: in F3 the root will pick a language from `Accept-Language`, and a cached 308 would pin users to `/vi`. Google follows 307 and treats `/vi` as the canonical through the canonical tag.
- Unknown pages return 404 with a Vietnamese page; unknown knowledge slugs return 404 (e2e).

### 10. Privacy in URLs and share pages (OK)
Birth data never appears in URLs: forms post to server actions; the share URL is `/vi/chia-se/{random id}`; the public share view drops the lunar date and shows only gender, giờ, năm can chi (spec §13.4); share page is `noindex, nofollow`, `Cache-Control: no-store`; the magic-link page sends `Referrer-Policy: no-referrer`.

## Not covered / needs the owner

1. **Google Search Console**: verify the domain (DNS TXT) once it is bought, submit `/sitemap.xml`, watch "Pages" for "Indexed, though blocked by robots.txt" (should go to zero after this fix).
2. **Domain and `WEB_ORIGIN`**: canonicals and the sitemap depend on the build-time origin (Plan 4a).
3. **Brand image** for `og:image` and the `Organization` logo.
4. **Content depth**: 26 knowledge pages is a thin site. More honest pages (sao phụ, Tứ Hóa, cách cục, glossary) help more than any tag. Avoid mass-generated pages: Google's [spam policies](https://developers.google.com/search/docs/essentials/spam-policies) (scaled content abuse) apply to AI-written text.
5. **E-E-A-T for a "Your Money or Your Life"-adjacent topic**: an "Về chúng tôi" page with who runs the site and how content is compiled (from public-domain classics, spec §11) builds trust ([Helpful content](https://developers.google.com/search/docs/fundamentals/creating-helpful-content)). Needs the owner's decision on what to publish (legal entity, spec §15).

## Files changed

- `services/web/src/lib/seo.ts` (+ test): `pageMeta`, `metaDescription`, JSON-LD builders, `jsonLdText`.
- `services/web/src/components/{JsonLd,Breadcrumbs,KnowledgeArticle}.tsx` (+ test).
- `services/web/src/app/robots.ts`, `app/not-found.tsx`, `app/vi/page.tsx`, `app/vi/kien-thuc/**`, legal pages, `components/SiteFooter.tsx`, share card route.
- Tests: `src/app/seo.test.ts`, `src/lib/seo.test.ts`, `src/components/KnowledgeArticle.test.tsx`, `src/components/SiteFooter.test.tsx`, `src/app/vi/chia-se/card.test.ts`, `e2e/seo.spec.ts`.
