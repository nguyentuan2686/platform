import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { OrderStatus } from "@/components/OrderStatus";
import { VietQrPanel } from "@/components/VietQrPanel";
import { cfg } from "@/lib/config";
import { formatVnd } from "@/lib/format";
import { isUuid } from "@/lib/ids";
import { getOrder, type Order } from "@/lib/payment";
import * as paymentCache from "@/lib/paymentCache";
import { productCopy } from "@/lib/products";
import { getViewer } from "@/lib/session";
import { UpstreamProblem } from "@/lib/upstream";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Thanh toán", robots: { index: false, follow: false } };

export default async function CheckoutPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params;
  if (!isUuid(orderId)) notFound();
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect(`/vi/dang-nhap?next=/vi/thanh-toan/${orderId.toLowerCase()}`);

  let order: Order;
  try {
    order = await getOrder(orderId, viewer.accountId); // scoped by account: another's order is 404
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) notFound();
    throw e;
  }
  const payable = order.status === "PENDING" || order.status === "EXPIRED";
  const payment = payable ? paymentCache.get(order.order_id) : undefined;

  return (
    <main className="checkout">
      <h1 className="h-display">Thanh toán</h1>
      <p className="lead">
        {productCopy(order.product_code).title} · {formatVnd(order.amount)}
      </p>
      {payable &&
        (payment ? (
          <VietQrPanel payment={payment} />
        ) : (
          <section className="qr-panel">
            <dl className="qr-fields">
              <div className="qr-row">
                <dt>Nội dung</dt>
                <dd>
                  <b>{order.code}</b>
                </dd>
              </div>
              <div className="qr-row">
                <dt>Số tiền</dt>
                <dd>
                  <b>{formatVnd(order.amount)}</b>
                </dd>
              </div>
            </dl>
            <p className="fine">Mã QR của đơn này không còn hiển thị được.</p>
            <Link href="/vi/mua" className="btn btn-ghost">
              Tạo đơn mới
            </Link>
          </section>
        ))}
      <OrderStatus
        orderId={order.order_id}
        initial={order.status}
        expiresAt={order.expires_at}
        simulator={cfg().simulatorEnabled}
      />
    </main>
  );
}
