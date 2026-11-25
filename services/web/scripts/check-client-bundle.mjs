// Fails (exit 1) when the client bundle (.next/static/**) carries a server secret or a service URL.
// Run after `npm run build`:  npm run check:bundle
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const FORBIDDEN = [
  "X-Internal-Token",
  "X-Admin-Token",
  "local-test-",
  ":8082",
  ":8083",
  ":8084",
  ":8085",
  "CHART_SVC_URL",
  "READING_URL",
  "IDENTITY_SVC_URL",
  "PAYMENT_SVC_URL",
  "INTERNAL_API_TOKEN",
  "ADMIN_API_TOKEN",
  "SEPAY_WEBHOOK_SECRET",
];

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* files(p);
    else yield p;
  }
}

/** Every (file, marker) pair where a forbidden marker (case-insensitive for header names) occurs. */
export function findLeaks(dir, extra = []) {
  const markers = [...FORBIDDEN, ...extra.filter((s) => s && s.length >= 8)];
  const leaks = [];
  for (const file of files(dir)) {
    const text = readFileSync(file, "latin1");
    const lower = text.toLowerCase();
    for (const marker of markers) {
      const hit = marker.startsWith("X-") ? lower.includes(marker.toLowerCase()) : text.includes(marker);
      if (hit) leaks.push({ file, marker: FORBIDDEN.includes(marker) ? marker : "(configured secret or service URL)" });
    }
  }
  return leaks;
}

function main() {
  const root = resolve(fileURLToPath(new URL("..", import.meta.url)), ".next", "static");
  let leaks;
  try {
    // An empty or partial build must not pass as "clean".
    if (![...files(root)].some((f) => f.endsWith(".js"))) throw Object.assign(new Error("no .js chunks"), { code: "EMPTY" });
    // The configured secret values themselves, when present in the build environment.
    // Also the configured service URLs (a prod URL may carry no :808x port).
    const env = process.env;
    leaks = findLeaks(root, [
      env.INTERNAL_API_TOKEN,
      env.ADMIN_API_TOKEN,
      env.SEPAY_WEBHOOK_SECRET,
      env.CHART_SVC_URL,
      env.READING_URL,
      env.IDENTITY_SVC_URL,
      env.PAYMENT_SVC_URL,
    ]);
  } catch (e) {
    console.error(`check-client-bundle: cannot read ${root} (run npm run build first): ${e.code ?? e}`);
    process.exit(1);
  }
  if (leaks.length > 0) {
    for (const l of leaks) console.error(`LEAK ${l.marker} in ${l.file}`);
    process.exit(1);
  }
  console.log("OK");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
