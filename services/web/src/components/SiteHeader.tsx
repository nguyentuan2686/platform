import Link from "next/link";
import { BalanceChip } from "@/components/BalanceChip";

export function SiteHeader() {
  return (
    <header className="site-header">
      <Link href="/vi" className="brand">
        Tử Vi Đẩu Số
      </Link>
      <BalanceChip />
    </header>
  );
}
