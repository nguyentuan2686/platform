import { getViewer } from "@/lib/session";

const PASS_LABEL = { MONTH: "Thẻ tháng", YEAR: "Thẻ năm" } as const;

/** Header balance: an account's credits or active pass. Guests get the login link from Task 3 on. */
export async function BalanceChip() {
  let viewer;
  try {
    viewer = await getViewer();
  } catch {
    return null; // identity down: the header still renders
  }
  if (viewer.kind !== "account") return null;
  const { credits, pass } = viewer.entitlements;
  const activePass = pass && Date.parse(pass.ends_at) > Date.now() ? pass : null;
  return (
    <span className="balance-chip">{activePass ? PASS_LABEL[activePass.kind] : `Còn ${credits} lượt`}</span>
  );
}
