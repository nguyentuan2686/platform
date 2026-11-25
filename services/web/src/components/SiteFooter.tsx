import Link from "next/link";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <p>Nội dung mang tính tham khảo văn hóa và giải trí.</p>
      <nav className="legal-links" aria-label="Pháp lý">
        <Link href="/vi/chinh-sach-rieng-tu">Chính sách riêng tư</Link>
        <Link href="/vi/dieu-khoan">Điều khoản</Link>
        <Link href="/vi/hoan-tien">Hoàn tiền</Link>
      </nav>
    </footer>
  );
}
