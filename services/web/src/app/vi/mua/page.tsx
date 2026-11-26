import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { PackPicker, type Pack } from "@/components/PackPicker";
import { formatVnd } from "@/lib/format";
import { listProducts } from "@/lib/payment";
import { isProductCode, PRODUCT_ORDER, productCopy } from "@/lib/products";
import { getViewer } from "@/lib/session";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Nạp lượt - Lão Thiên Cơ", robots: { index: false, follow: false } };

export default async function BuyPage() {
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect("/vi/dang-nhap?next=/vi/mua");
  // Prices come from payment-svc, never from this code.
  const products = await listProducts();
  const packs: Pack[] = PRODUCT_ORDER.flatMap((code) => {
    const p = products.find((x) => x.code === code && isProductCode(x.code));
    return p ? [{ code, ...productCopy(code), price: formatVnd(p.amount) }] : [];
  });
  return (
    <main className="buy-page">
      <h1 className="h-display">Nạp lượt</h1>
      <p className="lead">Lượt lẻ không hết hạn. Thẻ tháng, thẻ năm mở thêm câu hỏi trong thời hạn của thẻ.</p>
      <PackPicker packs={packs} />
    </main>
  );
}
