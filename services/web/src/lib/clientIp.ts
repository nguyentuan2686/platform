import "server-only";
import { headers } from "next/headers";

const SHAPE = /^[0-9A-Fa-f:.]{2,45}$/;

/**
 * The end user's IP as Cloudflare saw it (`Cf-Connecting-IP`: Cloudflare's edge always sets it and
 * only cloudflared reaches the cluster, so a client cannot spoof it in prod), for identity-svc's
 * per-client mail cap. Undefined when absent (local runs) or not shaped like an IP literal. Never
 * logged.
 */
export async function clientIp(): Promise<string | undefined> {
  const v = (await headers()).get("cf-connecting-ip")?.trim();
  return v && SHAPE.test(v) ? v : undefined;
}
