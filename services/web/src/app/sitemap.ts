// Static on purpose: generated at build time from the knowledge corpus (the runtime image has no knowledge files).
// The origin is therefore the build-time WEB_ORIGIN, same as the canonical URLs.
import type { MetadataRoute } from "next";
import { cfg } from "@/lib/config";
import { YEAR_PATH, ZODIAC } from "@/content/tuvi2027";
import { KINDS, XEM_SO_PATH } from "@/content/xemSo";
import { loadPalaces, loadStars } from "@/lib/knowledge";

/** Public pages only: home, knowledge, Tử vi 2027, xem số, legal. Private routes never appear. */
export default function sitemap(): MetadataRoute.Sitemap {
  const o = cfg().webOrigin;
  const paths = [
    "/vi",
    "/vi/kien-thuc",
    ...loadPalaces().map((p) => `/vi/kien-thuc/cung/${p.slug}`),
    ...loadStars().map((s) => `/vi/kien-thuc/sao/${s.slug}`),
    YEAR_PATH,
    ...ZODIAC.map((z) => `${YEAR_PATH}/${z.slug}`),
    XEM_SO_PATH,
    ...KINDS.map((k) => `${XEM_SO_PATH}/${k.slug}`),
    "/vi/dieu-khoan",
    "/vi/chinh-sach-rieng-tu",
    "/vi/hoan-tien",
  ];
  return paths.map((p) => ({ url: `${o}${p}` }));
}
