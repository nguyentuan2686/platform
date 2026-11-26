import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { RefundButton } from "@/components/RefundButton";
import { formatDateVn, formatVnd } from "@/lib/format";
import { ledger } from "@/lib/identity";
import { listOrders } from "@/lib/payment";
import { isRefundable, ledgerReasonLabel, productCopy, statusLabel } from "@/lib/products";
import { getViewer } from "@/lib/session";
import { topicLabel } from "@/lib/topics";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Giao dịch - Lão Thiên Cơ", robots: { index: false, follow: false } };

export default async function TransactionsPage() {
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect("/vi/dang-nhap?next=/vi/tai-khoan/giao-dich");
  const [orders, entries] = await Promise.all([listOrders(viewer.accountId), ledger(viewer.token)]);

  return (
    <main className="account transactions">
      <h1 className="h-display">Giao dịch</h1>
      <p className="account-email">
        <Link href="/vi/tai-khoan">Tài khoản</Link> · <Link href="/vi/mua">Nạp lượt</Link>
      </p>

      <h2 className="h-display tx-h">Đơn hàng</h2>
      {orders.length === 0 ? (
        <p className="fine">Chưa có đơn nào.</p>
      ) : (
        <div className="tx-wrap">
          <table className="tx-table">
            <thead>
              <tr>
                <th scope="col">Mã</th>
                <th scope="col">Gói</th>
                <th scope="col">Số tiền</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">Ngày</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.order_id}>
                  <td>
                    {o.status === "PENDING" || o.status === "EXPIRED" ? (
                      <Link href={`/vi/thanh-toan/${o.order_id}`}>{o.code}</Link>
                    ) : (
                      o.code
                    )}
                  </td>
                  <td>{productCopy(o.product_code).title}</td>
                  <td>{formatVnd(o.amount)}</td>
                  <td>
                    {statusLabel(o.status)}
                    {o.product_code.startsWith("CREDIT_") && (
                      <RefundButton orderId={o.order_id} code={o.code} refundable={isRefundable(o)} />
                    )}
                  </td>
                  <td>{formatDateVn(o.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="fine">
        Chỉ gói lượt chưa dùng được hoàn tự động. <Link href="/vi/hoan-tien">Chính sách hoàn tiền</Link>
      </p>

      <h2 className="h-display tx-h">Lượt</h2>
      {entries.length === 0 ? (
        <p className="fine">Chưa có biến động lượt.</p>
      ) : (
        <ul className="ledger">
          {entries.map((e, i) => (
            <li key={`${e.created_at}-${i}`} className="list-item">
              <div>
                <div className="t">
                  {ledgerReasonLabel(e.reason)}
                  {e.topic ? ` · ${topicLabel(e.topic)}` : ""}
                </div>
                <div className="s">{formatDateVn(e.created_at)}</div>
              </div>
              <span className="r">{e.delta > 0 ? `+${e.delta}` : `${e.delta}`}</span>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
