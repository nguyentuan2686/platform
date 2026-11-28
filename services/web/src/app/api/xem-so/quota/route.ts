import { numberQuota } from "@/lib/numberCheck";
import { reply, upstreamFailure } from "@/lib/qaRoute";
import { readSessionToken } from "@/lib/session";

export const dynamic = "force-dynamic";

/**
 * Today's number checks of the caller. Without a session there is nothing counted yet: 204 (no
 * guest is created just for reading the quota), and the form shows the default line.
 */
export async function GET(): Promise<Response> {
  const token = await readSessionToken();
  if (!token) return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
  try {
    return reply(200, await numberQuota(token));
  } catch (e) {
    return upstreamFailure(e, "xem-so-quota");
  }
}
