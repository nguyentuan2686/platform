"use client";

import { useActionState, useRef } from "react";
import { deleteHistoryAction, type DeleteHistoryState } from "@/app/vi/lich-su/actions";

/** "Xóa toàn bộ lịch sử hỏi" behind a confirmation dialog; only its form carries `confirm=1`. */
export function DeleteHistory() {
  const dialog = useRef<HTMLDialogElement>(null);
  const [state, action, pending] = useActionState<DeleteHistoryState, FormData>(async (prev, fd) => {
    dialog.current?.close();
    return deleteHistoryAction(prev, fd);
  }, null);
  return (
    <div className="delete-history">
      <button type="button" className="link-button" onClick={() => dialog.current?.showModal()} disabled={pending}>
        Xóa toàn bộ lịch sử hỏi
      </button>
      {state?.error && (
        <p className="field-error" role="alert">
          {state.error}
        </p>
      )}
      <dialog ref={dialog} className="confirm-dialog" aria-labelledby="delete-history-title">
        <form action={action}>
          <h2 id="delete-history-title" className="h-display">
            Xóa lịch sử hỏi?
          </h2>
          <p>Mọi câu hỏi và bài luận của con sẽ bị xóa hẳn, không lấy lại được. Số lượt đã dùng không được hoàn.</p>
          <input type="hidden" name="confirm" value="1" />
          <div className="dialog-actions">
            <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()}>
              Thôi
            </button>
            <button type="submit" className="btn">
              Xóa hẳn
            </button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
