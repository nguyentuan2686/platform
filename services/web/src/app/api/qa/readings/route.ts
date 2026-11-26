import { cookies } from "next/headers";
import { cfg } from "@/lib/config";
import { isUuid } from "@/lib/ids";
import { askStream, type QaKind } from "@/lib/qa";
import { AI_CONSENT_COOKIE, csrfRefusal, isQuestion, readObject, reply, sseResponse, upstreamFailure } from "@/lib/qaRoute";
import { ensureSession } from "@/lib/session";
import { TOPICS } from "@/lib/topics";

export const dynamic = "force-dynamic";

const CONSENT_MAX_AGE_S = 365 * 86400;

const invalid = (field: string) => reply(400, { reason: "invalid", field });

/**
 * "Hỏi thầy": proxies one question to reading-tuvi and streams its SSE answer back unchanged. The
 * service URL, internal token and session bearer stay on the server; the question travels only in
 * the body and is never logged. A client that disconnects aborts the upstream call.
 */
export async function POST(req: Request): Promise<Response> {
  const refusal = csrfRefusal(req);
  if (refusal) return refusal;
  const b = await readObject(req);
  if (!b) return invalid("body");
  if (!isUuid(b.reading_id)) return invalid("reading_id");
  if (b.kind !== "PAID" && b.kind !== "FREE") return invalid("kind");
  const topic = TOPICS.find((t) => t.code === b.topic)?.code;
  if (!topic) return invalid("topic");
  if (!isQuestion(b.question)) return invalid("question");

  // The first question needs the AI-provider consent; accepting it is remembered for a year.
  const jar = await cookies();
  if (jar.get(AI_CONSENT_COOKIE)?.value !== "1") {
    if (b.ai_consent !== true) return reply(400, { reason: "ai_consent_required" });
    jar.set(AI_CONSENT_COOKIE, "1", {
      httpOnly: true,
      sameSite: "lax",
      path: "/",
      secure: cfg().cookieSecure,
      maxAge: CONSENT_MAX_AGE_S,
    });
  }

  try {
    // A free question may come from a visitor without a session yet: a guest is created lazily.
    const token = await ensureSession();
    const upstream = await askStream(
      token,
      { reading_id: b.reading_id, kind: b.kind as QaKind, topic, question: b.question, lang: "vi" },
      req.signal,
    );
    return sseResponse(upstream);
  } catch (e) {
    return upstreamFailure(e, "qa-ask");
  }
}
