import type { MetadataRoute } from "next";
import { cfg } from "@/lib/config";

// Read WEB_ORIGIN at request time, not at build time.
export const dynamic = "force-dynamic";

/**
 * Private pages (lá số, hỏi, tài khoản, chia sẻ…) are kept out of the index by `noindex`
 * (meta tag or X-Robots-Tag), NOT by Disallow: Google must crawl a page to see its noindex, and
 * a disallowed URL that is linked from elsewhere (a share link on Facebook/Zalo) can still be
 * indexed without its content. Only non-page endpoints are disallowed.
 */
const DISALLOWED = ["/api/", "/vi/dang-nhap/google"];

export default function robots(): MetadataRoute.Robots {
  const o = cfg().webOrigin;
  return {
    rules: { userAgent: "*", allow: "/", disallow: DISALLOWED },
    sitemap: `${o}/sitemap.xml`,
  };
}
