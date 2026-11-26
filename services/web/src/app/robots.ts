import type { MetadataRoute } from "next";
import { cfg } from "@/lib/config";

// Read WEB_ORIGIN at request time, not at build time.
export const dynamic = "force-dynamic";

export default function robots(): MetadataRoute.Robots {
  const o = cfg().webOrigin;
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: [
        "/api/",
        "/vi/la-so",
        "/vi/hoi",
        "/vi/lich-su",
        "/vi/tai-khoan",
        "/vi/thanh-toan",
        "/vi/quan-tri",
        "/vi/dang-nhap",
        "/vi/chia-se",
        "/vi/mua",
      ],
    },
    sitemap: `${o}/sitemap.xml`,
  };
}
