"use client";

import Link from "next/link";
import { useActionState, useEffect, useRef, useState } from "react";
import { buyAction, type BuyState } from "@/app/vi/mua/actions";
import type { ProductCode } from "@/lib/payment";

export type Pack = { code: ProductCode; title: string; hint: string; price: string };

/** Radio cards (CREDIT_5 preselected), the 18+ confirmation and "Thanh toán {price}". */
export function PackPicker({ packs }: { packs: Pack[] }) {
  const [state, action, pending] = useActionState<BuyState, FormData>(buyAction, null);
  const [chosen, setChosen] = useState<ProductCode>(
    packs.some((p) => p.code === "CREDIT_5") ? "CREDIT_5" : (packs[0]?.code ?? "CREDIT_5"),
  );
  const price = packs.find((p) => p.code === chosen)?.price ?? "";
  const adultError = state?.field === "adult" ? state.error : undefined;
  const formError = state && state.field !== "adult" ? state.error : undefined;
  const fieldset = useRef<HTMLFieldSetElement>(null);

  // React resets a form after its action runs: the radios would fall back to their initial
  // checked state while the button still shows the chosen price. Re-check the chosen card.
  useEffect(() => {
    const radio = fieldset.current?.querySelector<HTMLInputElement>(`input[value="${chosen}"]`);
    if (radio) radio.checked = true;
  }, [state, chosen]);

  return (
    <form action={action} className="buy" noValidate>
      {formError && (
        <p className="form-alert" role="alert">
          {formError}
        </p>
      )}
      {/* The submitted pack is always the one the button names. */}
      <input type="hidden" name="product" value={chosen} />
      <fieldset className="packs" ref={fieldset}>
        <legend className="sr-only">Chọn gói</legend>
        {packs.map((p) => (
          <label key={p.code} className="pack" data-on={p.code === chosen ? "true" : undefined}>
            <input
              type="radio"
              name="pack"
              value={p.code}
              checked={p.code === chosen}
              onChange={() => setChosen(p.code)}
            />
            <span className="pack-text">
              <span className="pl">{p.title}</span>
              <span className="ps">{p.hint}</span>
            </span>
            <span className="pp">{p.price}</span>
          </label>
        ))}
      </fieldset>
      <p className="fine">Thẻ trả trước, không tự trừ tiền. Nhắc gia hạn trước 3 ngày. Lượt không hết hạn.</p>
      <div className="field consent">
        <input
          id="adult"
          name="adult"
          type="checkbox"
          value="1"
          aria-invalid={adultError ? true : undefined}
          aria-describedby={adultError ? "err-adult" : undefined}
        />
        <label htmlFor="adult">Tôi đủ 18 tuổi</label>
        <p className="consent-links">
          <Link href="/vi/dieu-khoan">Điều khoản</Link> · <Link href="/vi/hoan-tien">Chính sách hoàn tiền</Link>
        </p>
        {adultError && (
          <p id="err-adult" className="field-error" role="alert">
            {adultError}
          </p>
        )}
      </div>
      <button type="submit" className="btn" disabled={pending}>
        Thanh toán {price}
      </button>
    </form>
  );
}
