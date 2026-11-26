"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { InkLine } from "@/components/InkLine";
import { Seal } from "@/components/Seal";

const TICK_MS = 60; // ~60 ms per word, as the F1.5a dialogue

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-reduced-motion: reduce)").matches
    : false;
}

/**
 * The streamed answer: sentences arrive while the model writes, their words ink in one by one.
 * "Hiện hết" (or reduced motion) shows everything at once; the seal is stamped when the stream is
 * done and every word is visible.
 */
export function InkStream({ sentences, done }: { sentences: string[]; done: boolean }) {
  const parts = useMemo(() => sentences.map((s) => s.split(/\s+/).filter(Boolean)), [sentences]);
  const total = useMemo(() => parts.reduce((n, w) => n + w.length, 0), [parts]);
  const [shown, setShown] = useState(0);
  const [all, setAll] = useState(false);
  const reduced = useMemo(() => prefersReducedMotion(), []);
  const everything = all || reduced;
  const visible = everything ? total : Math.min(shown, total);
  const caughtUp = visible >= total;

  useEffect(() => {
    if (everything || shown >= total) return;
    const id = setInterval(() => setShown((n) => Math.min(n + 1, total)), TICK_MS);
    return () => clearInterval(id);
  }, [everything, shown, total]);

  // Follow the newest words, until the reader scrolls away.
  const current = useRef<HTMLSpanElement | null>(null);
  const follow = useRef(true);
  useEffect(() => {
    const stop = () => {
      follow.current = false;
    };
    window.addEventListener("wheel", stop, { passive: true });
    window.addEventListener("touchmove", stop, { passive: true });
    return () => {
      window.removeEventListener("wheel", stop);
      window.removeEventListener("touchmove", stop);
    };
  }, []);
  useEffect(() => {
    if (everything || caughtUp || !follow.current) return;
    current.current?.scrollIntoView?.({ block: "nearest" });
  }, [visible, everything, caughtUp]);

  let offset = 0;
  return (
    <div className="ink-stream">
      <div className="bubble">
        <p>
          {parts.map((words, i) => {
            const start = offset;
            offset += words.length;
            const count = Math.max(0, Math.min(words.length, visible - start));
            const isCurrent = visible >= start && visible < start + words.length;
            return (
              <span key={i} ref={isCurrent ? current : undefined}>
                {i > 0 && " "}
                <InkLine words={words} count={count} />
              </span>
            );
          })}
        </p>
        {done && caughtUp && <Seal />}
      </div>
      {!everything && (!caughtUp || !done) && (
        <button type="button" className="btn show-all" onClick={() => setAll(true)}>
          Hiện hết
        </button>
      )}
    </div>
  );
}
