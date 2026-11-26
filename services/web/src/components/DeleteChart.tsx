"use client";

import { useRef } from "react";
import { deleteProfile } from "@/app/vi/tai-khoan/actions";

/** "Xóa lá số" asks first, in a modal dialog; only its form carries `confirm=1`. */
export function DeleteChart() {
  const dialog = useRef<HTMLDialogElement>(null);
  return (
    <>
      <button type="button" className="link-button" onClick={() => dialog.current?.showModal()}>
        Xóa lá số
      </button>
      <dialog ref={dialog} className="confirm-dialog" aria-labelledby="delete-title">
        <form action={deleteProfile}>
          <h2 id="delete-title" className="h-display">
            Xóa lá số?
          </h2>
          <p>Ngày giờ sinh, lá số và lịch sử bài luận sẽ bị xóa hẳn, không lấy lại được. Số lượt vẫn giữ.</p>
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
    </>
  );
}
