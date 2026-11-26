"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useRef, useState } from "react";
import { FollowUpBox } from "@/components/FollowUpBox";
import { QaLive, QaResult } from "@/components/QaResult";
import { TopicGrid } from "@/components/TopicGrid";
import { SETTLED, useQaStream } from "@/components/useQaStream";
import type { Topic } from "@/lib/identity";
import { newUuid } from "@/lib/ids";
import type { QaKind } from "@/lib/qa";

const MIN = 5;
const MAX = 500;
const CONSENT_TEXT = "Con đồng ý gửi lá số ẩn danh và câu hỏi tới nhà cung cấp AI ở nước ngoài để luận giải.";

type Errors = { topic?: string; question?: string; consent?: string };

/**
 * The ask form of /vi/hoi: topic, question, the first-question AI consent, and the two ways to
 * ask (a credit, or a free chat). The answer streams from /api/qa/readings. One reading id per
 * distinct question: a double click or a retry after a dropped stream never charges twice.
 */
export function QaAsk({
  canPay,
  freeLeft,
  needsConsent,
  isGuest,
}: {
  canPay: boolean;
  freeLeft: number;
  needsConsent: boolean;
  isGuest: boolean;
}) {
  const [topic, setTopic] = useState<Topic | "">("");
  const [question, setQuestion] = useState("");
  const [consent, setConsent] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [asked, setAsked] = useState<{ question: string; kind: QaKind; id: string } | null>(null);
  const { view, start, reset } = useQaStream();
  const reading = useRef<{ key: string; id: string } | null>(null);
  const router = useRouter();
  const uid = useId();
  const qId = `${uid}-q`;
  const locked = view.phase === "streaming" || SETTLED.has(view.phase);

  async function submit(kind: QaKind) {
    const q = question.trim();
    const next: Errors = {};
    if (!topic) next.topic = "Con chọn một chủ đề.";
    if (q.length < MIN) next.question = `Câu hỏi cần ít nhất ${MIN} ký tự.`;
    if (needsConsent && !consent) next.consent = "Con cần đồng ý trước khi hỏi.";
    setErrors(next);
    if (Object.keys(next).length > 0 || !topic) return;

    // One id per distinct question: a retry replays the stored outcome, never a second charge.
    const key = `${kind}|${topic}|${q}`;
    if (reading.current?.key !== key) reading.current = { key, id: newUuid() };
    const id = reading.current.id;
    setAsked({ question: q, kind, id });
    const res = await start("/api/qa/readings", {
      reading_id: id,
      kind,
      topic,
      question: q,
      ...(needsConsent ? { ai_consent: true } : {}),
    });
    if (res === null) return; // already sending
    // A credit or a free chat was used: the header balance and the free count are read on the server.
    if (res.phase === "done" || res.phase === "queued") router.refresh();
    // The id is spent when the server will not replay a usable answer for it.
    if (res.phase === "busy" || (res.phase === "failed" && /^reading_(conflict|refunded)$/.test(res.reason))) {
      reading.current = null;
    }
  }

  function another() {
    reset();
    reading.current = null;
    setAsked(null);
    setQuestion("");
    setErrors({});
  }

  const freeDisabled = freeLeft <= 0;
  return (
    <div className="qa-ask">
      <form
        onSubmit={(e) => {
          e.preventDefault();
        }}
        noValidate
      >
        <fieldset className="qa-fields" disabled={locked}>
          <TopicGrid value={topic} onChange={setTopic} describedBy={errors.topic ? `${uid}-et` : undefined} />
          {errors.topic && (
            <p id={`${uid}-et`} className="field-error" role="alert">
              {errors.topic}
            </p>
          )}
          <div className="field">
            <label htmlFor={qId}>Câu hỏi của con</label>
            <textarea
              id={qId}
              className="in"
              rows={4}
              maxLength={MAX}
              value={question}
              aria-invalid={errors.question ? true : undefined}
              aria-describedby={`${uid}-count${errors.question ? ` ${uid}-eq` : ""}`}
              onChange={(e) => setQuestion(e.target.value)}
            />
            <span id={`${uid}-count`} className="counter">
              {question.length}/{MAX}
            </span>
            {errors.question && (
              <p id={`${uid}-eq`} className="field-error" role="alert">
                {errors.question}
              </p>
            )}
          </div>
          {needsConsent && (
            <div className="field consent">
              <input
                id={`${uid}-consent`}
                type="checkbox"
                checked={consent}
                aria-invalid={errors.consent ? true : undefined}
                onChange={(e) => setConsent(e.target.checked)}
              />
              <label htmlFor={`${uid}-consent`}>{CONSENT_TEXT}</label>
              {errors.consent && (
                <p className="field-error" role="alert">
                  {errors.consent}
                </p>
              )}
            </div>
          )}
          <div className="qa-buttons">
            {canPay && (
              <button type="button" className="btn" onClick={() => void submit("PAID")}>
                Dùng 1 lượt để hỏi
              </button>
            )}
            <button
              type="button"
              className={canPay ? "btn btn-ghost" : "btn"}
              disabled={freeDisabled}
              onClick={() => void submit("FREE")}
            >
              Hỏi miễn phí (còn {freeLeft} câu hôm nay)
            </button>
          </div>
          {canPay && <p className="fine">Kèm 2 câu hỏi nối tiếp trong cùng chủ đề.</p>}
        </fieldset>
      </form>
      {isGuest && (
        <p className="qa-login">
          <Link href="/vi/dang-nhap?next=/vi/hoi" className="tag">
            Đăng nhập để nạp lượt
          </Link>
        </p>
      )}
      {!isGuest && !canPay && (
        <p className="qa-login">
          <Link href="/vi/mua" className="tag">
            Nạp lượt
          </Link>
        </p>
      )}

      <QaLive view={view} />
      {asked && <QaResult view={view} question={asked.question} readingId={asked.id} />}
      {asked && view.phase === "done" && asked.kind === "PAID" && view.followUpsLeft > 0 && (
        <FollowUpBox key={asked.id} readingId={asked.id} left={view.followUpsLeft} />
      )}
      {asked && SETTLED.has(view.phase) && (
        <button type="button" className="btn btn-ghost qa-another" onClick={another}>
          Hỏi câu khác
        </button>
      )}
    </div>
  );
}
