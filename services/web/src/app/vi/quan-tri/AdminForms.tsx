"use client";

import { useActionState } from "react";
import { assignAction, completeRefundAction, resolveAction, type AdminState } from "@/app/vi/quan-tri/actions";

function Result({ state }: { state: AdminState }) {
  if (!state) return null;
  return state.ok ? (
    <p className="admin-ok" aria-live="polite">
      {state.ok}
    </p>
  ) : (
    <p className="field-error" role="alert">
      {state.error}
    </p>
  );
}

export function AssignForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState<AdminState, FormData>(assignAction, null);
  return (
    <form action={action} className="admin-form">
      <input type="hidden" name="id" value={id} />
      <div className="field">
        <label htmlFor={`code-${id}`}>Mã đơn</label>
        <input id={`code-${id}`} name="order_code" className="in" required maxLength={32} autoComplete="off" />
      </div>
      <button type="submit" className="tag" disabled={pending}>
        Gán đơn
      </button>
      <Result state={state} />
    </form>
  );
}

export function ResolveForm({ id }: { id: number }) {
  const [state, action, pending] = useActionState<AdminState, FormData>(resolveAction, null);
  return (
    <form action={action} className="admin-form">
      <input type="hidden" name="id" value={id} />
      <div className="field">
        <label htmlFor={`res-${id}`}>Cách đóng</label>
        <select id={`res-${id}`} name="resolution" className="in" defaultValue="REFUNDED_MANUALLY">
          <option value="REFUNDED_MANUALLY">Đã trả lại thủ công</option>
          <option value="IGNORED">Bỏ qua</option>
        </select>
      </div>
      <div className="field">
        <label htmlFor={`note-${id}`}>Ghi chú</label>
        <input id={`note-${id}`} name="note" className="in" maxLength={500} autoComplete="off" />
      </div>
      <button type="submit" className="tag" disabled={pending}>
        Đóng
      </button>
      <Result state={state} />
    </form>
  );
}

export function CompleteRefundForm({ id }: { id: string }) {
  const [state, action, pending] = useActionState<AdminState, FormData>(completeRefundAction, null);
  return (
    <form action={action} className="admin-form">
      <input type="hidden" name="id" value={id} />
      <button type="submit" className="tag" disabled={pending}>
        Đã chuyển trả
      </button>
      <Result state={state} />
    </form>
  );
}
