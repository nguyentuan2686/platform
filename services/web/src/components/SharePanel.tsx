"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { createShareAction, deleteShareAction, type ShareState } from "@/app/vi/la-so/shareActions";
import { cardPath, facebookShareUrl } from "@/lib/shareLinks";

type Props = { share: { share_id: string } | null; summary: string; url: string };

const NOTE = "Người xem thấy lá số và luận giải cơ bản. Ngày dương lịch được ẩn.";

/** Public link of the owner's chart: create, send (native menu, Facebook, copy, image), revoke. */
export function SharePanel({ share, summary, url }: Props) {
  const [createState, create, creating] = useActionState<ShareState>(createShareAction, null);
  const [deleteState, remove, removing] = useActionState<ShareState>(deleteShareAction, null);
  const [canShare, setCanShare] = useState(false);
  const [status, setStatus] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // The Web Share API is known only in the browser: detect after mount so the server HTML matches.
  useEffect(() => {
    setCanShare(typeof navigator.share === "function");
    return () => clearTimeout(timer.current);
  }, []);

  function say(text: string) {
    setStatus(text);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setStatus(""), 4000);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      say("Đã chép");
    } catch {
      say("Không chép được, con chép tay từ ô link nhé.");
    }
  }

  async function nativeShare() {
    try {
      await navigator.share({ title: "Lá số tử vi của tôi", text: summary, url });
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return; // the user closed the menu
      say("Không mở được menu chia sẻ, con dùng nút sao chép link nhé.");
    }
  }

  const error = createState?.error ?? deleteState?.error;

  if (!share) {
    return (
      <section className="share-panel" aria-labelledby="share-title">
        <h2 id="share-title" className="h-display">
          Chia sẻ lá số
        </h2>
        <p className="fine">{NOTE}</p>
        <form action={create}>
          <button type="submit" className="btn" disabled={creating}>
            Tạo link công khai
          </button>
        </form>
        {error && (
          <p role="alert" className="form-alert">
            {error}
          </p>
        )}
      </section>
    );
  }

  return (
    <section className="share-panel" aria-labelledby="share-title">
      <h2 id="share-title" className="h-display">
        Chia sẻ lá số
      </h2>
      <p className="fine">{NOTE}</p>
      <label className="share-field">
        <span>Link công khai</span>
        <input type="text" readOnly value={url} onFocus={(e) => e.currentTarget.select()} />
      </label>
      <div className="share-actions">
        {canShare && (
          <button type="button" className="btn" onClick={nativeShare}>
            Gửi qua Zalo, Messenger…
          </button>
        )}
        <a className="tag" href={facebookShareUrl(url)} target="_blank" rel="noopener noreferrer">
          Facebook
        </a>
        <button type="button" className="tag" onClick={copy}>
          Sao chép link
        </button>
        <a className="tag" href={cardPath(share.share_id)} download="la-so-tu-vi.png">
          Tải ảnh
        </a>
      </div>
      <p className="share-status" aria-live="polite">
        {status}
      </p>
      <form action={remove}>
        <button type="submit" className="link-button" disabled={removing}>
          Tắt link
        </button>
      </form>
      {error && (
        <p role="alert" className="form-alert">
          {error}
        </p>
      )}
    </section>
  );
}
