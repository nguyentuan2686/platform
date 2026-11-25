// Stub chart-svc (:CHART_PORT) and reading-tuvi (:READING_PORT) serving captured fixtures.
// Control: POST /__down makes both return 503, POST /__up restores them (used by the outage e2e).
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const fx = (n) => readFileSync(fileURLToPath(new URL(`./fixtures/${n}.json`, import.meta.url)), "utf8");
const chart = fx("chart");
const basic = fx("basic");
const today = fx("today");
let down = process.env.STUB_DOWN === "1";

function serve(port, routes) {
  const server = createServer((req, res) => {
    // Never log request bodies (birth data).
    if (req.url === "/__down" || req.url === "/__up") {
      down = req.url === "/__down";
      res.writeHead(204).end();
      return;
    }
    req.resume();
    if (down) {
      res.writeHead(503, { "content-type": "application/problem+json" }).end('{"status":503}');
      return;
    }
    const body = req.method === "POST" ? routes[req.url ?? ""] : undefined;
    if (!body) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { "content-type": "application/json" }).end(body);
  });
  server.listen(port, "127.0.0.1", () => console.log(`stub listening on ${port}`));
}

serve(Number(process.env.CHART_PORT ?? 18082), { "/tuvi/chart": chart });
serve(Number(process.env.READING_PORT ?? 18083), { "/tuvi/readings/basic": basic, "/tuvi/readings/today": today });
