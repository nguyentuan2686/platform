"use server";

import { redirect } from "next/navigation";
import { createOrder } from "@/lib/payment";
import * as paymentCache from "@/lib/paymentCache";
import { isProductCode } from "@/lib/products";
import { getViewer } from "@/lib/session";
import { logRefusalOrThrow } from "@/lib/degrade";
import { log } from "@/lib/log";

export type BuyState = { error: string; field?: "adult" | "product" } | null;

const NOT_READY = "Kênh chuyển khoản chưa sẵn sàng, con thử lại sau.";

/**
 * "Thanh toán": an account only, the 18+ confirmation ticked, a known pack. Opens a SePay order,
 * keeps its VietQR instructions server-side (paymentCache) and goes to the checkout page.
 */
export async function buyAction(_prev: BuyState, fd: FormData): Promise<BuyState> {
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect("/vi/dang-nhap?next=/vi/mua");
  const product = fd.get("product");
  if (!isProductCode(product)) return { error: "Con chọn một gói nhé.", field: "product" };
  if (fd.get("adult") !== "1") return { error: "Con cần xác nhận đã đủ 18 tuổi.", field: "adult" };

  let orderId: string;
  try {
    const order = await createOrder(viewer.accountId, product);
    if (order.payment.kind !== "VIETQR") {
      log("error", "order without VietQR payment", { order_id: order.order_id });
      return { error: NOT_READY };
    }
    paymentCache.put(order.order_id, order.payment);
    orderId = order.order_id;
  } catch (e) {
    // 503 (no receiving account), down or timeout: logged by http.ts. Any 4xx is unexpected here
    // (a wrong internal token, a contract mismatch): logged, then the same message. A bug rethrows.
    logRefusalOrThrow(e, "buy");
    return { error: NOT_READY };
  }
  redirect(`/vi/thanh-toan/${orderId}`);
}
