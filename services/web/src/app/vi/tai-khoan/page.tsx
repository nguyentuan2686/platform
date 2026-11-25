import type { Metadata } from "next";
import Link from "next/link";
import { DeleteChart } from "@/components/DeleteChart";
import { ProfileCard, type ChartSummary } from "@/components/ProfileCard";
import { degradeOrThrow } from "@/lib/degrade";
import { getProfile, type Profile } from "@/lib/identity";
import { hourLabel } from "@/lib/labels";
import { getViewer } from "@/lib/session";
import { getChart } from "@/lib/upstream";
import { changeCalendar, logoutAction } from "./actions";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Tài khoản - Lão Thiên Cơ", robots: { index: false, follow: false } };

/** giờ and năm can chi from chart-svc; chart trouble hides them only (logged by http.ts). */
async function summary(p: Profile): Promise<ChartSummary> {
  try {
    const c = await getChart({ ...p.birth, calendar: p.calendar });
    return { hour: hourLabel(c.hourBranch), year: c.yearCanChi.nameVi };
  } catch (e) {
    degradeOrThrow(e, "account-chart");
    return null;
  }
}

function LoginInvite() {
  return (
    <main className="login">
      <span className="stamp stamp-lg" aria-hidden="true">
        命
      </span>
      <h1 className="h-display">Lưu lá số và nạp lượt</h1>
      <p className="lead">Tạo tài khoản để giữ lá số, lịch sử bài luận và số lượt. Tặng 1 lượt cho lần đầu.</p>
      <Link href="/vi/dang-nhap?next=/vi/tai-khoan" className="btn">
        Đăng nhập
      </Link>
    </main>
  );
}

export default async function AccountPage() {
  const viewer = await getViewer();
  if (viewer.kind !== "account") return <LoginInvite />;
  const profile = await getProfile(viewer.token);
  const chart = profile ? await summary(profile) : null;
  const { credits } = viewer.entitlements;

  return (
    <main className="account">
      <h1 className="h-display">Tài khoản</h1>
      <p className="account-email">{viewer.emailMasked}</p>
      {profile ? (
        <>
          <ProfileCard profile={profile} chart={chart} />
          <div className="account-actions">
            <form action={changeCalendar}>
              <button type="submit" className="tag">
                Đổi lịch ({profile.calendar === "VN" ? "sang lịch Trung Hoa" : "sang lịch Việt"})
              </button>
            </form>
            <DeleteChart />
          </div>
        </>
      ) : (
        <p className="saved-chart">
          <Link href="/vi" className="btn btn-ghost">
            Lập lá số
          </Link>
        </p>
      )}
      <div className="list-item">
        <div>
          <div className="t">Số lượt còn lại</div>
          <div className="s">Lượt không hết hạn</div>
        </div>
        <span className="r">{credits} lượt</span>
      </div>
      <form action={logoutAction} className="logout">
        <button type="submit" className="btn btn-ghost">
          Đăng xuất
        </button>
      </form>
    </main>
  );
}
