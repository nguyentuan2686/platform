// reading-tuvi stub: basic and today readings answer the captured fixtures.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const NAME = "reading-tuvi";
const fx = (n) => readFileSync(fileURLToPath(new URL(`../fixtures/${n}.json`, import.meta.url)), "utf8");
const routes = { "/tuvi/readings/basic": fx("basic"), "/tuvi/readings/today": fx("today") };

export function reset() {}

export function handle(req, res, _body, send) {
  const body = req.method === "POST" ? routes[req.url ?? ""] : undefined;
  if (!body) return false;
  send(res, 200, body);
  return true;
}
