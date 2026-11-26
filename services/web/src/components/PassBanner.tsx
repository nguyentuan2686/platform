import Link from "next/link";
import { degradeOrThrow } from "@/lib/degrade";
import { formatDateVn } from "@/lib/format";
import { renewalNotice } from "@/lib/renewal";
import { getViewer, type Viewer } from "@/lib/session";

const KIND = { MONTH: "Thẻ tháng", YEAR: "Thẻ năm" } as const;

/** The renewal reminder, 3 days before an account's pass ends (prepaid: nothing renews by itself). */
export async function PassBanner() {
  let viewer: Viewer;
  try {
    viewer = await getViewer();
  } catch (e) {
    degradeOrThrow(e, "pass-banner");
    return null;
  }
  if (viewer.kind !== "account") return null;
  const n = renewalNotice(viewer.entitlements.pass, new Date());
  if (!n) return null;
  return (
    <p className="pass-banner" role="status">
      {KIND[n.kind]} của con còn {n.daysLeft} ngày (đến {formatDateVn(n.endsAt)}).{" "}
      <Link href="/vi/mua">Gia hạn</Link>
    </p>
  );
}
