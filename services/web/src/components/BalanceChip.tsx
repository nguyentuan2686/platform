import Link from "next/link";
import { degradeOrThrow } from "@/lib/degrade";
import { getViewer, type Viewer } from "@/lib/session";

const PASS_LABEL = { MONTH: "Thẻ tháng", YEAR: "Thẻ năm" } as const;

/** Header balance: an account's credits or active pass; guests and anonymous viewers get the login link. */
export async function BalanceChip() {
  let viewer: Viewer;
  try {
    viewer = await getViewer();
  } catch (e) {
    degradeOrThrow(e, "balance-chip"); // a bug rethrows; identity trouble is logged and hides the chip
    return null;
  }
  if (viewer.kind !== "account") {
    return (
      <Link href="/vi/dang-nhap" className="balance-chip">
        Đăng nhập
      </Link>
    );
  }
  const { credits, pass } = viewer.entitlements;
  const activePass = pass && Date.parse(pass.ends_at) > Date.now() ? pass : null;
  return (
    <span className="balance-chip">{activePass ? PASS_LABEL[activePass.kind] : `Còn ${credits} lượt`}</span>
  );
}
