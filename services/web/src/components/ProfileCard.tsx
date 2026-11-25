import Link from "next/link";
import type { Profile } from "@/lib/identity";
import { GENDER_LABELS } from "@/lib/labels";

const CALENDAR_LABELS = { VN: "Lịch Việt", CN: "Lịch Trung Hoa" } as const;

/** Chart summary from chart-svc; null when it could not be computed (the rest still shows). */
export type ChartSummary = { hour: string; year: string } | null;

/** The account's chart: no solar date, time or zone, only what the chart itself says. */
export function ProfileCard({ profile, chart }: { profile: Profile; chart: ChartSummary }) {
  return (
    <section className="profile" aria-labelledby="profile-name">
      <h2 id="profile-name" className="nm">
        {profile.label}
      </h2>
      <dl className="kv">
        <dt>Giới tính</dt>
        <dd>{GENDER_LABELS[profile.birth.gender]}</dd>
        {chart && (
          <>
            <dt>Giờ sinh</dt>
            <dd>{chart.hour}</dd>
            <dt>Năm sinh</dt>
            <dd>{chart.year}</dd>
          </>
        )}
        <dt>Lịch</dt>
        <dd>{CALENDAR_LABELS[profile.calendar]}</dd>
      </dl>
      {profile.locked ? (
        <p className="lock">Mỗi tài khoản một lá số. Ngày giờ sinh đã khóa sau lượt hỏi đầu tiên.</p>
      ) : (
        <p className="profile-actions">
          <Link href="/vi" className="tag">
            Sửa thông tin
          </Link>
        </p>
      )}
    </section>
  );
}
