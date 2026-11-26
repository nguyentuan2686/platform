"use client";

import Link from "next/link";
import { InkStream } from "@/components/InkStream";
import { AnswerText, AskLine } from "@/components/QaMessages";
import type { QaView } from "@/components/useQaStream";
import { AI_LABEL, DROP_TEXT, QUEUED_NOTE, reasonMessage } from "@/lib/qaText";

/**
 * What a screen reader hears for each phase of an asked question ("" while idle; "failed" is a
 * role="alert" of its own). Short summaries, not the visible text: the details follow in reading
 * order, and a copy of a visible sentence would be read twice and match twice in text queries.
 */
export const ANNOUNCE = {
  streaming: "Lão Thiên Cơ bắt đầu luận giải.",
  done: "Lão Thiên Cơ đã luận xong.",
  refused: "Lão Thiên Cơ không luận câu này. Lời nhắn ở ngay sau câu hỏi.",
  queued: "Câu hỏi đã được nhận, bài luận sẽ có sau. Lời nhắn ở ngay sau câu hỏi.",
  busy: "Lão Thiên Cơ chưa luận được lúc này. Lời nhắn ở ngay sau câu hỏi.",
  dropped: "Mất kết nối khi đang luận. Lời nhắn ở ngay sau câu hỏi.",
} as const;

export function qaAnnouncement(view: QaView): string {
  switch (view.phase) {
    case "streaming":
    case "done":
    case "refused":
    case "queued":
    case "busy":
    case "dropped":
      return ANNOUNCE[view.phase];
    case "error":
      return ANNOUNCE.busy;
    default:
      return "";
  }
}

/**
 * The polite live region for a question's progress. Render it from the first render (before
 * anything is asked) and keep it mounted: screen readers announce a live region whose text
 * changes, not one inserted already holding its text.
 */
export function QaLive({ view }: { view: QaView }) {
  return (
    <p className="sr-only" aria-live="polite" data-testid="qa-live">
      {qaAnnouncement(view)}
    </p>
  );
}

/**
 * What the visitor sees of one asked question: their words, the answer as it inks in, and the
 * outcome (done, refused, queued, busy, error, dropped, or a refusal before streaming) in Lão
 * Thiên Cơ's voice. `readingId` links back to the stored reading. Status changes are announced by
 * the caller's persistent `QaLive`, not here.
 */
export function QaResult({ view, question, readingId }: { view: QaView; question: string; readingId: string }) {
  const { phase } = view;
  if (phase === "idle") return null;
  const reading = `/vi/hoi/${readingId}`;

  if (phase === "failed") {
    const m = reasonMessage(view.reason);
    return (
      <p className="form-alert" role="alert">
        {m.text}
        {m.href && (
          <>
            {" "}
            <Link href={m.href}>{m.cta}</Link>
          </>
        )}
      </p>
    );
  }

  const partial = view.sentences.join("");
  return (
    <div className="qa-result">
      <AskLine text={question} />
      {(phase === "streaming" || phase === "done") && view.sentences.length > 0 && (
        <InkStream sentences={view.sentences} done={phase === "done"} />
      )}
      {phase === "streaming" && view.sentences.length === 0 && <p className="qa-wait">Lão Thiên Cơ đang luận…</p>}
      {phase !== "streaming" && phase !== "done" && partial && <AnswerText text={partial} />}

      {phase === "done" && (
        <div className="qa-after">
          <section className="disclaimer">
            {view.disclaimer && <p>{view.disclaimer}</p>}
            <p>{AI_LABEL}</p>
          </section>
          <Link href={reading} className="tag">
            Xem lại
          </Link>
        </div>
      )}
      {phase === "refused" && (
        <section className="qa-refused">
          <p>{view.text}</p>
          <p className="disclaimer-line">{view.disclaimer}</p>
        </section>
      )}
      {phase === "queued" && (
        <section className="qa-queued">
          <p>{view.text}</p>
          <p>{QUEUED_NOTE}</p>
          <Link href={reading} className="tag">
            Xem trạng thái
          </Link>
        </section>
      )}
      {phase === "busy" && <p className="qa-busy">{view.text || reasonMessage("").text}</p>}
      {phase === "error" && <p className="qa-busy">{reasonMessage("").text}</p>}
      {phase === "dropped" && (
        <p className="qa-busy">
          {DROP_TEXT}{" "}
          <Link href={reading} className="tag">
            Xem lại
          </Link>
        </p>
      )}
    </div>
  );
}
