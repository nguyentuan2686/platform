"use client";

import { useActionState, useRef } from "react";
import { refundAction, type RefundState } from "@/app/vi/tai-khoan/giao-dich/actions";

/**
 * "Yêu cầu hoàn tiền" behind a confirmation dialog. Rendered for every credit-pack order (button
 * only when refundable) so the acknowledgement stays on screen after the row's status changes.
 */
export function RefundButton({ orderId, code, refundable }: { orderId: string; code: string; refundable: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [state, action, pending] = useActionState<RefundState, FormData>(async (prev, fd) => {
    dialog.current?.close();
    return refundAction(prev, fd);
  }, null);
  const titleId = `refund-title-${orderId}`;
  return (
    <>
      {refundable && (
        <button type="button" className="link-button" onClick={() => dialog.current?.showModal()} disabled={pending}>
          Yêu cầu hoàn tiền
        </button>
      )}
      {state?.ok && (
        <p className="refund-ok" aria-live="polite">
          {state.ok}
        </p>
      )}
      {state?.error && (
        <p className="field-error" role="alert">
          {state.error}
        </p>
      )}
      {refundable && (
        <dialog ref={dialog} className="confirm-dialog" aria-labelledby={titleId}>
          <form action={action}>
            <h2 id={titleId} className="h-display">
              Hoàn tiền đơn {code}?
            </h2>
            <p>Lượt của gói này sẽ được thu lại, tiền chuyển trả về tài khoản đã chuyển.</p>
            <input type="hidden" name="order_id" value={orderId} />
            <div className="dialog-actions">
              <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()}>
                Thôi
              </button>
              <button type="submit" className="btn">
                Hoàn tiền
              </button>
            </div>
          </form>
        </dialog>
      )}
    </>
  );
}
