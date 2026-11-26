import type { Metadata } from "next";
import Link from "next/link";
import { ConfirmLogin } from "@/components/ConfirmLogin";

export const dynamic = "force-dynamic";
// The URL carries the one-time token: never sent as a Referer, never indexed (next.config also
// sends the Referrer-Policy header). Nothing is redeemed on GET.
export const metadata: Metadata = {
  title: "Xác nhận đăng nhập",
  referrer: "no-referrer",
  robots: { index: false, follow: false },
};

type Search = Promise<{ token?: string | string[] }>;

export default async function ConfirmPage({ searchParams }: { searchParams: Search }) {
  const { token } = await searchParams;
  return (
    <main className="login">
      <span className="stamp stamp-lg" aria-hidden="true">
        命
      </span>
      <h1 className="h-display">Xác nhận đăng nhập</h1>
      {typeof token === "string" && token ? (
        <>
          <p className="lead">Con bấm nút dưới đây để vào tài khoản.</p>
          <ConfirmLogin token={token} />
        </>
      ) : (
        <p role="alert" className="form-alert">
          Link đã hết hạn hoặc đã dùng. Con xin link mới nhé. <Link href="/vi/dang-nhap">Xin link mới</Link>
        </p>
      )}
    </main>
  );
}
