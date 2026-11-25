import { defineConfig, devices } from "@playwright/test";

const WEB_PORT = 3100;
const ORIGIN = `http://127.0.0.1:${WEB_PORT}`;
// Test-only value, shared by the web server and the identity stub (which rejects calls without it).
const INTERNAL_TOKEN = "local-test-internal-token";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: ORIGIN, ...devices["Desktop Chrome"] },
  webServer: [
    {
      // chart-svc :18082, reading-tuvi :18083, identity-svc :18084 (payment-svc :18085 joins in Task 5).
      command: "node e2e/stub/server.mjs",
      url: "http://127.0.0.1:18082/__up",
      env: { STUB_INTERNAL_TOKEN: INTERNAL_TOKEN },
      reuseExistingServer: false,
    },
    {
      command: "npm run start",
      url: `${ORIGIN}/health/ready`,
      env: {
        PORT: String(WEB_PORT),
        CHART_SVC_URL: "http://127.0.0.1:18082",
        READING_URL: "http://127.0.0.1:18083",
        IDENTITY_SVC_URL: "http://127.0.0.1:18084",
        PAYMENT_SVC_URL: "http://127.0.0.1:18085",
        INTERNAL_API_TOKEN: INTERNAL_TOKEN,
        WEB_ORIGIN: ORIGIN,
        COOKIE_SECURE: "0",
      },
      reuseExistingServer: false,
    },
  ],
});
