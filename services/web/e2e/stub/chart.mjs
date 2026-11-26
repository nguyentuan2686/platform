// chart-svc stub: POST /tuvi/chart answers the captured fixture.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const NAME = "chart-svc";
const chart = readFileSync(fileURLToPath(new URL("../fixtures/chart.json", import.meta.url)), "utf8");

export function reset() {}

export function handle(req, res, _body, send) {
  if (req.method === "POST" && req.url === "/tuvi/chart") {
    send(res, 200, chart);
    return true;
  }
  return false;
}
