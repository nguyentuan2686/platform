import Link from "next/link";

export function SiteHeader() {
  return (
    <header className="site-header">
      <Link href="/vi" className="brand">
        Tử Vi Đẩu Số
      </Link>
    </header>
  );
}
