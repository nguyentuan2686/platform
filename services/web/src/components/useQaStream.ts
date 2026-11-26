"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { postQaStream, type QaEvent } from "@/lib/qaStream";

export type QaPhase =
  | "idle"
  | "streaming"
  | "done"
  | "refused"
  | "queued"
  | "busy"
  | "error"
  | "dropped"
  | "failed";

export type QaView = {
  phase: QaPhase;
  /** The sentences received so far. */
  sentences: string[];
  followUpsLeft: number;
  /** The server's disclaimer (done, refused). */
  disclaimer: string;
  /** The server's words (refused, queued, busy). */
  text: string;
  /** The refusing reason when phase is "failed". */
  reason: string;
};

/** A phase after which the question is settled: asking again needs a new question. */
export const SETTLED: ReadonlySet<QaPhase> = new Set<QaPhase>(["done", "refused", "queued"]);

const IDLE: QaView = { phase: "idle", sentences: [], followUpsLeft: 0, disclaimer: "", text: "", reason: "" };

/**
 * Runs one Q&A stream at a time and exposes what arrived. `start` returns false (and does
 * nothing) while a stream is in flight, so a double click can never send twice. A stream still
 * running when the component goes away is aborted (the BFF then aborts the upstream call).
 */
export function useQaStream(initialLeft = 0) {
  const [view, setView] = useState<QaView>({ ...IDLE, followUpsLeft: initialLeft });
  const inFlight = useRef(false);
  const ctrl = useRef<AbortController | null>(null);

  useEffect(() => () => ctrl.current?.abort(), []);

  const start = useCallback(async (url: string, body: unknown): Promise<{ phase: QaPhase; reason: string } | null> => {
    if (inFlight.current) return null;
    inFlight.current = true;
    const c = new AbortController();
    ctrl.current = c;
    setView((v) => ({ ...IDLE, phase: "streaming", followUpsLeft: v.followUpsLeft }));
    const apply = (e: QaEvent) => {
      setView((v) => {
        switch (e.type) {
          case "meta":
            return e.followUpsLeft === null ? v : { ...v, followUpsLeft: e.followUpsLeft };
          case "delta":
            return { ...v, sentences: [...v.sentences, e.text] };
          case "done":
            return { ...v, phase: "done", disclaimer: e.disclaimer };
          case "refused":
            return { ...v, phase: "refused", text: e.text, disclaimer: e.disclaimer };
          case "queued":
            return { ...v, phase: "queued", text: e.text };
          case "busy":
            return { ...v, phase: "busy", text: e.text };
          default:
            return { ...v, phase: "error" };
        }
      });
    };
    let phase: QaPhase = "streaming";
    let reason = "";
    const track = (e: QaEvent) => {
      if (e.type === "done" || e.type === "refused" || e.type === "queued" || e.type === "busy") phase = e.type;
      else if (e.type === "error") phase = "error";
      apply(e);
    };
    try {
      const outcome = await postQaStream(url, body, track, c.signal);
      if (outcome.kind === "problem") {
        phase = "failed";
        reason = outcome.reason;
        setView((v) => ({ ...v, phase: "failed", reason: outcome.reason }));
      } else if (outcome.kind === "dropped") {
        phase = "dropped";
        setView((v) => ({ ...v, phase: "dropped" }));
      }
      return { phase, reason };
    } finally {
      inFlight.current = false;
    }
  }, []);

  const reset = useCallback(() => {
    setView((v) => ({ ...IDLE, followUpsLeft: v.followUpsLeft }));
  }, []);

  return { view, start, reset };
}
