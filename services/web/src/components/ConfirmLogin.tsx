"use client";

import Link from "next/link";
import { useActionState, useEffect } from "react";
import {
  acceptPendingLogin,
  cancelPendingLogin,
  confirmLogin,
  type ConfirmState,
} from "@/app/vi/dang-nhap/actions";

/**
 * The emailed link only renders this button: nothing is redeemed on GET, so a mail scanner that
 * opens the link cannot burn it. The token leaves the address bar (and history) at once.
 * A link this browser did not ask for ends on an interstitial naming the account it opens.
 */
export function ConfirmLogin({ token }: { token: string }) {
  const [state, action, pending] = useActionState<ConfirmState, FormData>(confirmLogin, null);
  useEffect(() => {
    if (window.location.search) window.history.replaceState(window.history.state, "", window.location.pathname);
  }, []);
  const interstitial = state && "pending" in state ? state.pending : null;

  return (
    <>
      {/* Mounted from the first render: the interstitial is announced as a change of its text. */}
      <p className="sr-only" aria-live="polite">
        {interstitial ? `Link này mở tài khoản ${interstitial.emailMasked}. Con xác nhận bên dưới.` : ""}
      </p>
      {interstitial ? (
        <div className="slip interstitial">
          <p className="interstitial-text">
            Con đang đăng nhập vào tài khoản <strong>{interstitial.emailMasked}</strong>. Lá số và câu hỏi đang
            có trên máy này sẽ không được chuyển.
          </p>
          <form action={acceptPendingLogin}>
            <button type="submit" className="btn">
              Vào tài khoản này
            </button>
          </form>
          <form action={cancelPendingLogin} className="interstitial-cancel">
            <button type="submit" className="btn btn-ghost">
              Không, hủy
            </button>
          </form>
        </div>
      ) : (
        <form action={action} className="slip">
          <input type="hidden" name="token" value={token} />
          {state && "error" in state && (
            <p role="alert" className="form-alert">
              {state.error} <Link href="/vi/dang-nhap">Xin link mới</Link>
            </p>
          )}
          <button type="submit" className="btn" disabled={pending}>
            Đăng nhập
          </button>
        </form>
      )}
    </>
  );
}
