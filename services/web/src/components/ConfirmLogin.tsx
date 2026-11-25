"use client";

import Link from "next/link";
import { useActionState, useEffect } from "react";
import { confirmLogin, type ConfirmState } from "@/app/vi/dang-nhap/actions";

/**
 * The emailed link only renders this button: nothing is redeemed on GET, so a mail scanner that
 * opens the link cannot burn it. The token leaves the address bar (and history) at once.
 */
export function ConfirmLogin({ token }: { token: string }) {
  const [state, action, pending] = useActionState<ConfirmState, FormData>(confirmLogin, null);
  useEffect(() => {
    if (window.location.search) window.history.replaceState(window.history.state, "", window.location.pathname);
  }, []);

  return (
    <form action={action} className="slip">
      <input type="hidden" name="token" value={token} />
      {state?.error && (
        <p role="alert" className="form-alert">
          {state.error} <Link href="/vi/dang-nhap">Xin link mới</Link>
        </p>
      )}
      <button type="submit" className="btn" disabled={pending}>
        Đăng nhập
      </button>
    </form>
  );
}
