import { afterEach, describe, expect, it } from "vitest";
import { cfg } from "@/lib/config";

const KEYS = [
  "CHART_SVC_URL",
  "READING_URL",
  "IDENTITY_SVC_URL",
  "PAYMENT_SVC_URL",
  "INTERNAL_API_TOKEN",
  "ADMIN_API_TOKEN",
  "ADMIN_ACCOUNT_IDS",
  "WEB_ORIGIN",
  "COOKIE_SECURE",
  "GOOGLE_LOGIN",
  "DEV_PAYMENT_SIMULATOR",
  "DEPLOY_ENV",
  "SEPAY_WEBHOOK_AUTH",
  "SEPAY_WEBHOOK_SECRET",
  "QA_STREAM_TIMEOUT_MS",
  "KNOWLEDGE_DIR",
];

afterEach(() => {
  for (const k of KEYS) delete process.env[k];
});

describe("cfg", () => {
  it("has the documented defaults", () => {
    for (const k of KEYS) delete process.env[k];
    const c = cfg();
    expect(c.chartUrl).toBe("http://localhost:8082");
    expect(c.readingUrl).toBe("http://localhost:8083");
    expect(c.identityUrl).toBe("http://localhost:8084");
    expect(c.paymentUrl).toBe("http://localhost:8085");
    expect(c.internalToken).toBe("");
    expect(c.adminToken).toBe("");
    expect(c.adminIds.size).toBe(0);
    expect(c.webOrigin).toBe("http://localhost:3001");
    expect(c.cookieSecure).toBe(false);
    expect(c.googleLogin).toBe(false);
    expect(c.simulatorEnabled).toBe(false);
    expect(c.deployEnv).toBe("local");
    expect(c.sepayWebhookAuth).toBe("apikey");
    expect(c.sepayWebhookSecret).toBe("");
    expect(c.qaStreamTimeoutMs).toBe(95000);
    expect(c.knowledgeDir).toBe("../../knowledge/tuvi");
    expect(Object.isFrozen(c)).toBe(true);
  });

  it("reads the environment at call time", () => {
    process.env.IDENTITY_SVC_URL = "http://identity:1";
    process.env.INTERNAL_API_TOKEN = "local-test-x";
    process.env.ADMIN_ACCOUNT_IDS = " a-1 , b-2,,";
    process.env.COOKIE_SECURE = "1";
    process.env.GOOGLE_LOGIN = "1";
    process.env.QA_STREAM_TIMEOUT_MS = "1234";
    process.env.WEB_ORIGIN = "https://tuvi.example/";
    const c = cfg();
    expect(c.identityUrl).toBe("http://identity:1");
    expect(c.internalToken).toBe("local-test-x");
    expect([...c.adminIds]).toEqual(["a-1", "b-2"]);
    expect(c.cookieSecure).toBe(true);
    expect(c.googleLogin).toBe(true);
    expect(c.qaStreamTimeoutMs).toBe(1234);
    expect(c.webOrigin).toBe("https://tuvi.example");
  });

  it("enables the payment simulator only outside prod", () => {
    process.env.DEV_PAYMENT_SIMULATOR = "1";
    expect(cfg().simulatorEnabled).toBe(true);
    process.env.DEPLOY_ENV = "prod";
    expect(cfg().simulatorEnabled).toBe(false);
    process.env.DEPLOY_ENV = "local";
    process.env.DEV_PAYMENT_SIMULATOR = "0";
    expect(cfg().simulatorEnabled).toBe(false);
  });

  it("falls back to the default stream timeout for a bad number", () => {
    process.env.QA_STREAM_TIMEOUT_MS = "abc";
    expect(cfg().qaStreamTimeoutMs).toBe(95000);
  });
});
