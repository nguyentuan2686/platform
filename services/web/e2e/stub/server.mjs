// Stub upstreams for the e2e suite: chart-svc, reading-tuvi and identity-svc on their own ports.
// Controls (any port, any method): /__down → every stub answers 503, /__up → back, /__reset → up
// and in-memory state cleared. Request bodies are never logged (they carry birth data).
import { createServer } from "node:http";
import * as chart from "./chart.mjs";
import * as reading from "./reading.mjs";
import * as identity from "./identity.mjs";

const state = { down: process.env.STUB_DOWN === "1" };
const modules = [chart, reading, identity];

export function send(res, status, body, type = "application/json") {
  if (body === undefined) {
    res.writeHead(status).end();
    return;
  }
  res.writeHead(status, { "content-type": type }).end(typeof body === "string" ? body : JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      try {
        resolve(raw ? JSON.parse(raw) : undefined);
      } catch {
        resolve(undefined);
      }
    });
  });
}

function serve(port, mod) {
  const server = createServer(async (req, res) => {
    const path = (req.url ?? "/").split("?")[0];
    if (path === "/__down" || path === "/__up" || path === "/__reset") {
      req.resume();
      state.down = path === "/__down";
      if (path === "/__reset") for (const m of modules) m.reset();
      send(res, 204);
      return;
    }
    const body = await readBody(req);
    if (state.down) {
      send(res, 503, { status: 503 }, "application/problem+json");
      return;
    }
    const handled = await mod.handle(req, res, body, send);
    if (!handled) send(res, 404, { status: 404 }, "application/problem+json");
  });
  server.listen(port, "127.0.0.1", () => console.log(`stub ${mod.NAME} listening on ${port}`));
}

serve(Number(process.env.CHART_PORT ?? 18082), chart);
serve(Number(process.env.READING_PORT ?? 18083), reading);
serve(Number(process.env.IDENTITY_PORT ?? 18084), identity);
