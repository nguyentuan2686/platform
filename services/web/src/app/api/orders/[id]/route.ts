import { isUuid } from "@/lib/ids";
import { getOrder } from "@/lib/payment";
import { getViewer } from "@/lib/session";
import { UpstreamError, UpstreamProblem } from "@/lib/upstream";

export const dynamic = "force-dynamic";

const reply = (status: number, body: unknown) =>
  Response.json(body, { status, headers: { "cache-control": "no-store" } });

/**
 * The live status of one of the viewer's orders (polled by OrderStatus). payment-svc scopes the
 * read by the session's account_id, so another account's order answers 404 (no IDOR). Only the
 * status and paid_at leave: never the code, amount or payment details.
 */
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  let viewer;
  try {
    viewer = await getViewer();
  } catch (e) {
    if (e instanceof UpstreamError) return reply(503, { reason: "unavailable" });
    throw e;
  }
  if (viewer.kind !== "account") return reply(401, { reason: "unauthenticated" });
  if (!isUuid(id)) return reply(404, { reason: "not_found" });
  try {
    const order = await getOrder(id, viewer.accountId);
    return reply(200, order.paid_at ? { status: order.status, paid_at: order.paid_at } : { status: order.status });
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) return reply(404, { reason: "not_found" });
    if (e instanceof UpstreamError) return reply(503, { reason: "unavailable" });
    throw e;
  }
}
