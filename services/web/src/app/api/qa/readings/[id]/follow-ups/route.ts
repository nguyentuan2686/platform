import { isUuid } from "@/lib/ids";
import { followUpStream } from "@/lib/qa";
import { csrfRefusal, isQuestion, readObject, reply, sseResponse, upstreamFailure } from "@/lib/qaRoute";
import { getViewer } from "@/lib/session";

export const dynamic = "force-dynamic";

/**
 * A follow-up on one of the account's answered paid readings (not charged), streamed through
 * like the first answer. The first paid question already carried the AI-provider consent.
 */
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const refusal = csrfRefusal(req);
  if (refusal) return refusal;
  const { id } = await ctx.params;
  try {
    const viewer = await getViewer();
    if (viewer.kind === "anon") return reply(401, { reason: "unauthenticated" });
    if (viewer.kind === "guest") return reply(403, { reason: "account_required" });
    if (!isUuid(id)) return reply(404, { reason: "not_found" });
    const b = await readObject(req);
    if (!b) return reply(400, { reason: "invalid", field: "body" });
    if (!isUuid(b.message_id)) return reply(400, { reason: "invalid", field: "message_id" });
    if (!isQuestion(b.question)) return reply(400, { reason: "invalid", field: "question" });
    const upstream = await followUpStream(viewer.token, id, { message_id: b.message_id, question: b.question }, req.signal);
    return sseResponse(upstream);
  } catch (e) {
    return upstreamFailure(e, "qa-follow-up");
  }
}
