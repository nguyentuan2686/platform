import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Link đã tắt - Tử Vi Đẩu Số", robots: { index: false, follow: false } };

export default function ShareNotFound() {
  return (
    <main className="login">
      <span className="stamp stamp-lg" aria-hidden="true">
        命
      </span>
      <h1 className="h-display">Link này đã tắt hoặc không còn.</h1>
      <p className="lead">Người chia sẻ đã tắt link, hoặc lá số không còn nữa.</p>
      <Link href="/vi" className="btn">
        Lập lá số miễn phí
      </Link>
    </main>
  );
}
