import type { Metadata } from "next";
import Link from "next/link";

// Next.js answers 404 and adds `noindex` to this page itself.
export const metadata: Metadata = { title: "Không tìm thấy trang", robots: { index: false, follow: true } };

export default function NotFound() {
  return (
    <main className="login">
      <span className="stamp stamp-lg" aria-hidden="true">
        命
      </span>
      <h1 className="h-display">Ta không tìm thấy trang này.</h1>
      <p className="lead">Đường dẫn có thể đã đổi hoặc gõ nhầm.</p>
      <Link href="/vi" className="btn">
        Lập lá số miễn phí
      </Link>
      <p>
        <Link href="/vi/kien-thuc">Đọc kiến thức tử vi</Link>
      </p>
    </main>
  );
}
