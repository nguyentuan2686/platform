import type { MetadataRoute } from "next";
import { cfg } from "@/lib/config";
import { loadPalaces, loadStars } from "@/lib/knowledge";

/** Public pages only: home, knowledge, legal. Private routes never appear. */
// Read WEB_ORIGIN at request time, not at build time.
export const dynamic = "force-dynamic";

export default function sitemap(): MetadataRoute.Sitemap {
  const o = cfg().webOrigin;
  const paths = [
    "/vi",
    "/vi/kien-thuc",
    ...loadPalaces().map((p) => `/vi/kien-thuc/cung/${p.slug}`),
    ...loadStars().map((s) => `/vi/kien-thuc/sao/${s.slug}`),
    "/vi/dieu-khoan",
    "/vi/chinh-sach-rieng-tu",
    "/vi/hoan-tien",
  ];
  return paths.map((p) => ({ url: `${o}${p}` }));
}
