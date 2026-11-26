import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { LoginForm } from "@/components/LoginForm";
import { cfg } from "@/lib/config";
import { degradeOrThrow } from "@/lib/degrade";
import { safeNext } from "@/lib/safeNext";
import { getViewer } from "@/lib/session";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Đăng nhập", robots: { index: false, follow: false } };

type Search = Promise<{ next?: string | string[]; loi?: string | string[] }>;
const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : undefined);

async function isAccount(): Promise<boolean> {
  try {
    return (await getViewer()).kind === "account";
  } catch (e) {
    degradeOrThrow(e, "login-page");
    return false;
  }
}

export default async function LoginPage({ searchParams }: { searchParams: Search }) {
  const sp = await searchParams;
  const next = safeNext(one(sp.next));
  if (await isAccount()) redirect(next);
  return (
    <main className="login">
      <span className="stamp stamp-lg" aria-hidden="true">
        命
      </span>
      <h1 className="h-display">Lưu lá số và nạp lượt</h1>
      <p className="lead">Tạo tài khoản để giữ lá số, lịch sử bài luận và số lượt. Tặng 1 lượt cho lần đầu.</p>
      {one(sp.loi) === "google" && (
        <p role="alert" className="form-alert">
          Đăng nhập Google chưa thành, con thử lại hoặc dùng email.
        </p>
      )}
      <LoginForm next={next} google={cfg().googleLogin} />
    </main>
  );
}
