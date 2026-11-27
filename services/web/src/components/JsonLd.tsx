import { jsonLdText } from "@/lib/seo";

/** One schema.org block as JSON-LD, rendered on the server with the page. */
export function JsonLd({ data }: { data: unknown }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: jsonLdText(data) }} />;
}
