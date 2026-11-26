import { isUuid } from "@/lib/ids";
import { getReading } from "@/lib/qa";
import { reply, upstreamFailure } from "@/lib/qaRoute";
import { readSessionToken } from "@/lib/session";
import { UpstreamProblem } from "@/lib/upstream";

export const dynamic = "force-dynamic";

/**
 * The live status of one of the visitor's readings (polled by ReadingStatusPoller). reading-tuvi
 * scopes the read by the session, so another subject's reading answers 404. Only the status and
 * the follow-ups left leave: never the question or the answer.
 */
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const token = await readSessionToken();
  if (!token) return reply(401, { reason: "unauthenticated" });
  if (!isUuid(id)) return reply(404, { reason: "not_found" });
  try {
    const r = await getReading(token, id);
    return reply(200, { status: r.status, follow_ups_left: r.follow_ups_left });
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) return reply(404, { reason: "not_found" });
    return upstreamFailure(e, "qa-status");
  }
}
