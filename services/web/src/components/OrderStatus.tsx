"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { OrderStatus as Status } from "@/lib/payment";
import { statusLabel } from "@/lib/products";

const PENDING_POLL_MS = 3000;
/** A late transfer still matches an EXPIRED order: keep looking, slower, for a day. */
const LATE_POLL_MS = 30_000;
const LATE_WINDOW_MS = 24 * 3600_000;

const REFRESH_AFTER_PAID_MS = [2000, 5000, 10_000];

const PAID_TEXT = "Đã nhận tiền. Lượt của con đã được cộng.";
const LATE_TEXT =
  "Đơn đã quá 30 phút. Nếu con đã chuyển đúng nội dung, lượt vẫn được cộng khi ngân hàng báo về; quá 24 giờ chưa thấy, ta sẽ đối soát thủ công.";

function pollInterval(status: Status, expiresAt: string): number {
  if (status === "PENDING") return PENDING_POLL_MS;
  if (status === "EXPIRED" && Date.now() < Date.parse(expiresAt) + LATE_WINDOW_MS) return LATE_POLL_MS;
  return 0;
}

/**
 * Live status of the viewer's order: polls /api/orders/{id} every 3 s while PENDING (every 30 s for
 * 24 h once EXPIRED), never while the tab is hidden, and stops on any other status. On PAID it
 * announces the payment and refreshes the page once (header balance).
 */
export function OrderStatus({
  orderId,
  initial,
  expiresAt,
  simulator = false,
}: {
  orderId: string;
  initial: Status;
  expiresAt: string;
  simulator?: boolean;
}) {
  const router = useRouter();
  const [status, setStatus] = useState<Status>(initial);
  const [simState, setSimState] = useState<"idle" | "busy" | "failed">("idle");
  const checkNow = useRef<() => void>(() => {});
  const refreshed = useRef(false);

  useEffect(() => {
    const interval = pollInterval(status, expiresAt);
    if (!interval) return;
    let cancelled = false;
    let parked = false; // a check fell due while hidden: run it when visible again
    let timer: ReturnType<typeof setTimeout> | undefined;

    const schedule = () => {
      if (!cancelled) timer = setTimeout(tick, pollInterval(status, expiresAt) || interval);
    };
    const tick = async () => {
      if (cancelled) return;
      if (status === "EXPIRED" && !pollInterval(status, expiresAt)) return;
      if (document.visibilityState === "hidden") {
        parked = true;
        return;
      }
      try {
        const res = await fetch(`/api/orders/${encodeURIComponent(orderId)}`, { cache: "no-store" });
        if (res.ok) {
          const body = (await res.json()) as { status?: Status };
          if (!cancelled && body.status && body.status !== status) {
            setStatus(body.status); // the effect restarts with the new status (or stops)
            return;
          }
        }
      } catch {
        /* catch-ok: browser side; offline or a blip, tried again at the next interval */
      }
      schedule();
    };
    const onVisible = () => {
      if (document.visibilityState === "visible" && parked) {
        parked = false;
        clearTimeout(timer);
        void tick();
      }
    };
    checkNow.current = () => {
      parked = false;
      clearTimeout(timer);
      void tick();
    };
    document.addEventListener("visibilitychange", onVisible);
    schedule();
    return () => {
      cancelled = true;
      clearTimeout(timer);
      checkNow.current = () => {};
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [status, orderId, expiresAt]);

  useEffect(() => {
    if (status === "PAID" && initial !== "PAID" && !refreshed.current) {
      refreshed.current = true;
      router.refresh(); // header balance chip
      // The credits are granted asynchronously (PaymentSucceeded over Kafka), a moment after the order is PAID.
      // Not cleared on cleanup: a refresh after unmount is harmless.
      for (const ms of REFRESH_AFTER_PAID_MS) setTimeout(() => router.refresh(), ms);
    }
  }, [status, initial, router]);

  async function simulate() {
    setSimState("busy");
    try {
      const res = await fetch("/api/dev/sepay", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ order_id: orderId }),
      });
      if (!res.ok) {
        setSimState("failed");
        return;
      }
      setSimState("idle");
      checkNow.current();
    } catch {
      // catch-ok: browser side; the failure is shown on the simulator button
      setSimState("failed");
    }
  }

  const payable = status === "PENDING" || status === "EXPIRED";
  return (
    <div className="order-status">
      <div aria-live="polite" className="order-status-text">
        {status === "PENDING" && <p>Đang chờ thanh toán…</p>}
        {status === "PAID" && <p className="order-paid">{PAID_TEXT}</p>}
        {status === "EXPIRED" && <p>{LATE_TEXT}</p>}
        {(status === "REFUND_REQUESTED" || status === "REFUNDED") && <p>{statusLabel(status)}</p>}
      </div>
      {status === "PAID" && (
        <Link href="/vi/hoi" className="btn">
          Hỏi thầy ngay
        </Link>
      )}
      {simulator && payable && (
        <>
          <button type="button" className="btn btn-ghost sim-button" onClick={simulate} disabled={simState === "busy"}>
            Giả lập chuyển khoản (môi trường thử)
          </button>
          {simState === "failed" && (
            <p className="field-error" role="alert">
              Chưa giả lập được, con thử lại.
            </p>
          )}
        </>
      )}
    </div>
  );
}
