import { cfg } from "@/lib/config";
import { isUuid } from "@/lib/ids";
import { log } from "@/lib/log";
import { getOrder, postSepayWebhook } from "@/lib/payment";
import { assertSameOrigin, forbidden, ForbiddenOrigin } from "@/lib/sameOrigin";
import { buildSepayWebhook } from "@/lib/sepaySim";
import { getViewer } from "@/lib/session";
import { logRefusalOrThrow } from "@/lib/degrade";
import { UpstreamProblem } from "@/lib/upstream";

export const dynamic = "force-dynamic";

const reply = (status: number, body?: unknown) =>
  body === undefined
    ? new Response(null, { status, headers: { "cache-control": "no-store" } })
    : Response.json(body, { status, headers: { "cache-control": "no-store" } });

/**
 * Dev-only "giả lập chuyển khoản": posts a SePay webhook for one of the viewer's payable orders,
 * as the bank would. Exists only when DEV_PAYMENT_SIMULATOR=1 and DEPLOY_ENV is not prod (checked
 * on every request); otherwise 404. Logs carry the order id only: no content, amount or secret.
 */
export async function POST(req: Request): Promise<Response> {
  if (!cfg().simulatorEnabled) return reply(404);
  try {
    assertSameOrigin(req);
  } catch (e) {
    if (e instanceof ForbiddenOrigin) return forbidden();
    throw e;
  }
  let orderId: unknown;
  try {
    orderId = ((await req.json()) as { order_id?: unknown } | null)?.order_id;
  } catch {
    // catch-ok: an unparsable body has no order id, answered 404 below
    orderId = undefined;
  }
  try {
    const viewer = await getViewer();
    if (viewer.kind !== "account") return reply(401, { reason: "unauthenticated" });
    if (!isUuid(orderId)) return reply(404, { reason: "not_found" });
    const order = await getOrder(orderId, viewer.accountId);
    if (order.status !== "PENDING" && order.status !== "EXPIRED") return reply(409, { reason: "not_payable" });
    const { headers, body } = buildSepayWebhook({ code: order.code, amount: Number(order.amount), now: new Date() });
    await postSepayWebhook(body, headers);
    log("info", "simulated transfer sent", { order_id: order.order_id });
    return reply(204);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) return reply(404, { reason: "not_found" });
    // Anything else (e.g. 401 from /webhooks/sepay: the secret differs from payment-svc) is logged.
    logRefusalOrThrow(e, "dev-sepay");
    return reply(503, { reason: "unavailable" });
  }
}
