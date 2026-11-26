import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { cfg } from "@/lib/config";
import { formatDateTimeVn, formatVnd } from "@/lib/format";
import { listRefundRequests, listUnmatched } from "@/lib/payment";
import { unmatchedReasonLabel } from "@/lib/products";
import { getViewer } from "@/lib/session";
import { AssignForm, CompleteRefundForm, ResolveForm } from "./AdminForms";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Quản trị", robots: { index: false, follow: false } };

/** Operator queues. 404 (not 403) for everyone but ADMIN_ACCOUNT_IDS, and when no admin token is set. */
export default async function AdminPage() {
  const viewer = await getViewer();
  if (viewer.kind !== "account" || !viewer.isAdmin || !cfg().adminToken) notFound();
  const [unmatched, refunds] = await Promise.all([listUnmatched(), listRefundRequests()]);

  return (
    <main className="admin">
      <h1 className="h-display">Quản trị</h1>

      <h2 className="h-display tx-h">Chưa khớp</h2>
      {unmatched.length === 0 ? (
        <p className="fine">Không có giao dịch chưa khớp.</p>
      ) : (
        <ul className="admin-list">
          {unmatched.map((t) => (
            <li key={t.id} className="admin-item">
              <p className="t">
                #{t.id} · {formatVnd(t.amount)} · {formatDateTimeVn(t.occurred_at)} ·{" "}
                <b>{unmatchedReasonLabel(t.unmatched_reason)}</b>
              </p>
              {t.candidate_codes.length > 0 && <p className="s">Mã: {t.candidate_codes.join(", ")}</p>}
              {t.content && <p className="s admin-content">{t.content}</p>}
              <AssignForm id={t.id} />
              <ResolveForm id={t.id} />
            </li>
          ))}
        </ul>
      )}

      <h2 className="h-display tx-h">Yêu cầu hoàn tiền</h2>
      {refunds.length === 0 ? (
        <p className="fine">Không có yêu cầu đang mở.</p>
      ) : (
        <ul className="admin-list">
          {refunds.map((r) => (
            <li key={r.id} className="admin-item">
              <p className="t">
                {formatVnd(r.amount)} · {formatDateTimeVn(r.created_at)} · đơn {r.order_id}
              </p>
              <CompleteRefundForm id={r.id} />
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
