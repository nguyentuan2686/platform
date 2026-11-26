"use client";

import { useActionState } from "react";
import { requestLogin, type LoginState } from "@/app/vi/dang-nhap/actions";

export function LoginForm({ next, google }: { next: string; google: boolean }) {
  const [state, action, pending] = useActionState<LoginState, FormData>(requestLogin, null);
  const sent = state !== null && "sent" in state;
  const emailError = state !== null && "errors" in state ? state.errors.email : undefined;

  return (
    <>
      <p aria-live="polite" className="login-sent">
        {sent && "Nếu email hợp lệ, ta đã gửi link đăng nhập. Link có hiệu lực 15 phút."}
      </p>
      <form action={action} className="slip" noValidate>
        <input type="hidden" name="next" value={next} />
        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            inputMode="email"
            required
            maxLength={254}
            className="in"
            aria-invalid={emailError ? true : undefined}
            aria-describedby={emailError ? "err-email" : undefined}
          />
          {emailError && (
            <p id="err-email" className="field-error" role="alert">
              {emailError}
            </p>
          )}
        </div>
        <button type="submit" className="btn" disabled={pending}>
          Gửi link đăng nhập
        </button>
      </form>
      {google && (
        <form method="post" action="/api/auth/google" className="google-form">
          <input type="hidden" name="next" value={next} />
          <button type="submit" className="btn btn-ghost">
            Tiếp tục với Google
          </button>
        </form>
      )}
      <p className="fine">Không dùng mật khẩu. Link có hiệu lực 15 phút.</p>
    </>
  );
}
