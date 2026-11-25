import { defineConfig, devices } from "@playwright/test";

const WEB_PORT = 3100;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: `http://127.0.0.1:${WEB_PORT}`, ...devices["Desktop Chrome"] },
  webServer: [
    {
      command: "node e2e/stub-upstream.mjs",
      url: "http://127.0.0.1:18082/__up",
      // The stub answers 404 to GET; any HTTP response means it is listening.
      ignoreHTTPSErrors: true,
      reuseExistingServer: false,
    },
    {
      command: "npm run start",
      url: `http://127.0.0.1:${WEB_PORT}/health/ready`,
      env: {
        PORT: String(WEB_PORT),
        CHART_SVC_URL: "http://127.0.0.1:18082",
        READING_URL: "http://127.0.0.1:18083",
      },
      reuseExistingServer: false,
    },
  ],
});
