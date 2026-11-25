// reading-tuvi stub: basic and today readings answer the captured fixtures; the Q&A guest merge
// and history deletion answer like the service (server.mjs records every call, see GET /__calls).
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const NAME = "reading-tuvi";
const fx = (n) => readFileSync(fileURLToPath(new URL(`../fixtures/${n}.json`, import.meta.url)), "utf8");
const routes = { "/tuvi/readings/basic": fx("basic"), "/tuvi/readings/today": fx("today") };

export function reset() {}

export function handle(req, res, body, send) {
  const path = (req.url ?? "").split("?")[0];
  if (req.method === "POST" && path === "/internal/qa/merge") {
    // Internal token only, never a bearer (contract).
    const want = process.env.STUB_INTERNAL_TOKEN;
    if ((want && req.headers["x-internal-token"] !== want) || req.headers.authorization) {
      send(res, 401, { status: 401 }, "application/problem+json");
      return true;
    }
    if (typeof body?.guest_id !== "string" || typeof body?.account_id !== "string") {
      send(res, 400, { status: 400 }, "application/problem+json");
      return true;
    }
    send(res, 200, { moved: 0 });
    return true;
  }
  if (req.method === "DELETE" && path === "/tuvi/qa/readings") {
    send(res, 204);
    return true;
  }
  const fixture = req.method === "POST" ? routes[path] : undefined;
  if (!fixture) return false;
  send(res, 200, fixture);
  return true;
}
