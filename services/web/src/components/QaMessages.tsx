import { Seal } from "@/components/Seal";
import { AI_LABEL } from "@/lib/qaText";
import type { QaMessage, QaStatus } from "@/lib/qa";

/** The visitor's question, in the prototype's "ask" style. */
export function AskLine({ text }: { text: string }) {
  return <p className="ask">{text}</p>;
}

/** A finished answer, static (no ink animation); paragraphs split on blank lines. */
export function AnswerText({ text, seal = false }: { text: string; seal?: boolean }) {
  const paragraphs = text.split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);
  return (
    <div className="bubble">
      {paragraphs.map((p, i) => (
        <p key={i}>{p}</p>
      ))}
      {seal && <Seal />}
    </div>
  );
}

/** One reading's conversation: question, answer (seal when answered), notice, disclaimer and AI label. */
export function QaMessages({
  messages,
  status,
  notice,
  disclaimer,
}: {
  messages: QaMessage[];
  status: QaStatus;
  notice: string | null;
  disclaimer: string;
}) {
  const lastAnswered = messages.reduce((n, m, i) => (m.answer ? i : n), -1);
  return (
    <div className="qa-messages">
      {messages.map((m, i) => (
        <section key={m.seq} className="qa-turn">
          <AskLine text={m.question} />
          {m.answer && <AnswerText text={m.answer} seal={status === "ANSWERED" && i === lastAnswered} />}
        </section>
      ))}
      {notice && (
        <p className="qa-notice" role="status">
          {notice}
        </p>
      )}
      <section className="disclaimer">
        <p>{disclaimer}</p>
        <p>{AI_LABEL}</p>
      </section>
    </div>
  );
}
