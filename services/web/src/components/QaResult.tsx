"use client";

import Link from "next/link";
import { InkStream } from "@/components/InkStream";
import { AnswerText, AskLine } from "@/components/QaMessages";
import type { QaView } from "@/components/useQaStream";
import { AI_LABEL, DROP_TEXT, QUEUED_NOTE, reasonMessage } from "@/lib/qaText";

/**
 * What the visitor sees of one asked question: their words, the answer as it inks in, and the
 * outcome (done, refused, queued, busy, error, dropped, or a refusal before streaming) in Lão
 * Thiên Cơ's voice. `readingId` links back to the stored reading.
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
      {phase === "streaming" && view.sentences.length === 0 && (
        <p className="qa-wait" role="status">
          Lão Thiên Cơ đang luận…
        </p>
      )}
      {phase !== "streaming" && phase !== "done" && partial && <AnswerText text={partial} />}

      {phase === "done" && (
        <div className="qa-after">
          <p className="sr-only" role="status">
            Lão Thiên Cơ đã luận xong.
          </p>
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
        <section className="qa-refused" role="status">
          <p>{view.text}</p>
          <p className="disclaimer-line">{view.disclaimer}</p>
        </section>
      )}
      {phase === "queued" && (
        <section className="qa-queued" role="status">
          <p>{view.text}</p>
          <p>{QUEUED_NOTE}</p>
          <Link href={reading} className="tag">
            Xem trạng thái
          </Link>
        </section>
      )}
      {phase === "busy" && (
        <p className="qa-busy" role="status">
          {view.text || reasonMessage("").text}
        </p>
      )}
      {phase === "error" && (
        <p className="qa-busy" role="status">
          {reasonMessage("").text}
        </p>
      )}
      {phase === "dropped" && (
        <p className="qa-busy" role="status">
          {DROP_TEXT}{" "}
          <Link href={reading} className="tag">
            Xem lại
          </Link>
        </p>
      )}
    </div>
  );
}
