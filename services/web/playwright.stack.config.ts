import { defineConfig, devices } from "@playwright/test";

// Full-stack run against the real compose stack (web on :3001, Mailpit on :8025). Local only, not in CI:
//   docker run --rm --network host -v "$PWD/services/web:/w" -w /w mcr.microsoft.com/playwright:v1.65.1-noble \
//     sh -c "npm ci && npx playwright test -c playwright.stack.config.ts"
export default defineConfig({
  testDir: "./e2e-stack",
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  reporter: [["list"]],
  use: { baseURL: "http://localhost:3001", ...devices["Desktop Chrome"] },
});
