"use client";

import { useId, useRef, useState } from "react";
import { AnswerText, AskLine } from "@/components/QaMessages";
import { QaResult } from "@/components/QaResult";
import { useQaStream } from "@/components/useQaStream";
import { newUuid } from "@/lib/ids";

type Turn = { question: string; text: string };

/**
 * "Hỏi tiếp": up to two follow-ups on an answered paid reading, not charged. The answer streams
 * in like the first one; earlier follow-ups of this visit stay above as plain text.
 */
export function FollowUpBox({ readingId, left }: { readingId: string; left: number }) {
  const { view, start } = useQaStream(left);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [asked, setAsked] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const messageId = useRef<{ key: string; id: string } | null>(null);
  const fieldId = useId();

  const streaming = view.phase === "streaming";
  const remaining = view.followUpsLeft;

  async function submit() {
    const q = text.trim();
    if (q.length < 5) {
      setError("Câu hỏi cần ít nhất 5 ký tự.");
      return;
    }
    setError(null);
    // One id per distinct question: a retry after a drop replays the stored answer, never a second one.
    if (messageId.current?.key !== q) messageId.current = { key: q, id: newUuid() };
    const id = messageId.current.id;
    const previous = view.phase === "done" ? { question: asked, text: view.sentences.join("") } : null;
    setAsked(q);
    const res = await start(`/api/qa/readings/${encodeURIComponent(readingId)}/follow-ups`, {
      message_id: id,
      question: q,
    });
    if (res === null) return; // already sending
    if (previous) setTurns((t) => [...t, previous]);
    if (res.phase === "done") {
      setText("");
      messageId.current = null;
    } else if (res.phase === "busy") {
      messageId.current = null;
    }
  }

  if (remaining === 0 && view.phase === "idle" && turns.length === 0) return null;

  return (
    <section className="follow-up" aria-label="Hỏi tiếp">
      {turns.map((t, i) => (
        <div key={i} className="qa-turn">
          <AskLine text={t.question} />
          <AnswerText text={t.text} />
        </div>
      ))}
      <QaResult view={view} question={asked} readingId={readingId} />
      {remaining > 0 && <p className="fine">Còn {remaining} câu hỏi nối tiếp trong chủ đề này.</p>}
      {remaining > 0 && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
          noValidate
        >
          <div className="field">
            <label htmlFor={fieldId}>Câu hỏi nối tiếp</label>
            <textarea
              id={fieldId}
              className="in"
              rows={3}
              maxLength={500}
              value={text}
              disabled={streaming}
              aria-invalid={error ? true : undefined}
              onChange={(e) => setText(e.target.value)}
            />
            {error && (
              <p className="field-error" role="alert">
                {error}
              </p>
            )}
          </div>
          <button type="submit" className="btn" disabled={streaming}>
            Hỏi tiếp
          </button>
        </form>
      )}
    </section>
  );
}
