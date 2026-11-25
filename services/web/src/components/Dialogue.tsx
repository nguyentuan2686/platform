"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { InkLine } from "@/components/InkLine";
import { Seal } from "@/components/Seal";
import { Sage } from "@/components/Sage";
import type { Section } from "@/lib/types";

const TICK_MS = 60; // ~60 ms per word
const GAP_TICKS = 6; // pause between lines

type Item = { key: string; kind: "title" | "line"; words: string[]; start: number };
type Block = { id: string; domId: string; items: Item[] };

function domIdOf(id: string): string {
  return id === "menh" || id === "than" || id === "cuc" || id === "greeting" || id === "closing" ? id : `cung-${id}`;
}

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-reduced-motion: reduce)").matches
    : false;
}

function build(sections: Section[]): { blocks: Block[]; flat: Item[]; total: number; disclaimer?: Section } {
  const disclaimer = sections.find((s) => s.id === "disclaimer");
  const blocks: Block[] = [];
  const flat: Item[] = [];
  let cursor = 0;
  const add = (items: Item[], key: string, kind: Item["kind"], text: string) => {
    const words = text.split(/\s+/).filter(Boolean);
    const item = { key, kind, words, start: cursor };
    cursor += words.length + GAP_TICKS;
    items.push(item);
    flat.push(item);
  };
  for (const s of sections) {
    if (s.id === "disclaimer") continue;
    const items: Item[] = [];
    if (s.title) add(items, `${s.id}-t`, "title", s.title);
    s.lines.forEach((l, i) => add(items, `${s.id}-${i}`, "line", l));
    blocks.push({ id: s.id, domId: domIdOf(s.id), items });
  }
  return { blocks, flat, total: cursor, disclaimer };
}

export default function Dialogue({ sections }: { sections: Section[] }) {
  const { blocks, flat, total, disclaimer } = useMemo(() => build(sections), [sections]);
  const [tick, setTick] = useState(0);
  const done = tick >= total;

  useEffect(() => {
    if (prefersReducedMotion()) setTick(total);
  }, [total]);

  useEffect(() => {
    if (done) return;
    const id = setInterval(() => setTick((t) => t + 1), TICK_MS);
    return () => clearInterval(id);
  }, [done]);

  // index of the line being revealed (-1 before start)
  let current = -1;
  for (let i = 0; i < flat.length; i++) if (flat[i].start <= tick) current = i;
  const currentRef = useRef<HTMLElement | null>(null);
  const follow = useRef(true);

  // Stop following the line as soon as the reader takes over: scrolling, or using a link to a section.
  useEffect(() => {
    const stop = () => {
      follow.current = false;
    };
    const reveal = () => {
      follow.current = false;
      setTick(total);
    };
    const onKey = (e: KeyboardEvent) => {
      if (["PageUp", "PageDown", "ArrowUp", "ArrowDown", "Home", "End", " "].includes(e.key)) stop();
    };
    const onClick = (e: MouseEvent) => {
      if (e.target instanceof Element && e.target.closest('.board a[href^="#"]')) reveal();
    };
    window.addEventListener("wheel", stop, { passive: true });
    window.addEventListener("touchmove", stop, { passive: true });
    window.addEventListener("keydown", onKey, { passive: true });
    window.addEventListener("hashchange", reveal);
    document.addEventListener("click", onClick);
    return () => {
      window.removeEventListener("wheel", stop);
      window.removeEventListener("touchmove", stop);
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("hashchange", reveal);
      document.removeEventListener("click", onClick);
    };
  }, [total]);

  useEffect(() => {
    if (done || current < 1 || !follow.current) return;
    currentRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [current, done]);

  const count = (it: Item) => Math.max(0, Math.min(it.words.length, tick - it.start));

  return (
    <div className="reading">
      <Sage mood="read" className="reading-sage" />
      <div className="reading-body">
        <div className="bubble">
          {blocks.map((b) => (
            <section key={b.id} id={b.domId} className="say">
              {b.items.map((it) => {
                const idx = flat.indexOf(it);
                const ref = (el: HTMLElement | null) => {
                  if (idx === current) currentRef.current = el;
                };
                return it.kind === "title" ? (
                  <h2 key={it.key} ref={ref} className="say-title">
                    <InkLine words={it.words} count={count(it)} />
                  </h2>
                ) : (
                  <p key={it.key} ref={ref}>
                    <InkLine words={it.words} count={count(it)} />
                  </p>
                );
              })}
            </section>
          ))}
          {done && <Seal />}
        </div>
        {!done && (
          <button type="button" className="btn show-all" onClick={() => setTick(total)}>
            Hiện hết
          </button>
        )}
        {done && disclaimer && (
          <section id="disclaimer" className="disclaimer">
            {disclaimer.lines.map((l, i) => (
              <p key={i}>{l}</p>
            ))}
          </section>
        )}
      </div>
    </div>
  );
}
