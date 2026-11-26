"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

const POLL_MS = 15_000;

/**
 * While an answer or a refund is still on its way (PENDING, RETRYING, REFUND_PENDING), asks
 * /api/qa/readings/{id} every 15 s, never while the tab is hidden, and refreshes the page once
 * the status changes: the in-app notice for a queued answer and for a refund. A failed check is
 * only retried at the next tick (the status route logs upstream trouble).
 */
export function ReadingStatusPoller({ readingId, status }: { readingId: string; status: string }) {
  const router = useRouter();
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      if (!cancelled) timer = setTimeout(tick, POLL_MS);
    };
    const tick = async () => {
      if (cancelled) return;
      if (document.visibilityState === "hidden") return schedule();
      try {
        const res = await fetch(`/api/qa/readings/${encodeURIComponent(readingId)}`, { cache: "no-store" });
        if (res.ok) {
          const body = (await res.json()) as { status?: unknown };
          if (!cancelled && typeof body.status === "string" && body.status !== status) {
            router.refresh();
            return;
          }
        }
      } catch {
        /* offline or interrupted: the next tick looks again */
      }
      schedule();
    };
    schedule();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [readingId, status, router]);

  return (
    <p className="fine" role="status" aria-live="polite">
      Trang tự cập nhật khi ta luận xong.
    </p>
  );
}
