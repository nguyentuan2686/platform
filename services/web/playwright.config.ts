import { defineConfig, devices } from "@playwright/test";

const WEB_PORT = 3100;
const ORIGIN = `http://127.0.0.1:${WEB_PORT}`;
// Test-only values, shared by the web server and the stubs (which reject calls without them).
const INTERNAL_TOKEN = "local-test-internal-token";
const ADMIN_TOKEN = "local-test-admin-token";
const SEPAY_SECRET = "local-test-sepay-secret";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: ORIGIN, ...devices["Desktop Chrome"] },
  webServer: [
    {
      // chart-svc :18082, reading-tuvi :18083, identity-svc :18084, payment-svc :18085.
      command: "node e2e/stub/server.mjs",
      url: "http://127.0.0.1:18082/__up",
      env: { STUB_INTERNAL_TOKEN: INTERNAL_TOKEN, STUB_ADMIN_TOKEN: ADMIN_TOKEN, STUB_SEPAY_SECRET: SEPAY_SECRET },
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
        ADMIN_API_TOKEN: ADMIN_TOKEN,
        ADMIN_ACCOUNT_IDS: "",
        WEB_ORIGIN: ORIGIN,
        COOKIE_SECURE: "0",
        DEV_PAYMENT_SIMULATOR: "1",
        DEPLOY_ENV: "local",
        SEPAY_WEBHOOK_AUTH: "apikey",
        SEPAY_WEBHOOK_SECRET: SEPAY_SECRET,
      },
      reuseExistingServer: false,
    },
  ],
});
