import Link from "next/link";

export type Crumb = Readonly<{ name: string; path: string }>;

/** The visible breadcrumb; the last crumb is the current page (not a link). Mirrors `breadcrumbLd`. */
export function Breadcrumbs({ crumbs }: { crumbs: readonly Crumb[] }) {
  return (
    <nav aria-label="Đường dẫn" className="breadcrumbs">
      <ol>
        {crumbs.map((c, i) => (
          <li key={c.path}>
            {i < crumbs.length - 1 ? <Link href={c.path}>{c.name}</Link> : <span aria-current="page">{c.name}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}
