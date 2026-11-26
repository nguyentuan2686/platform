import "server-only";

/** Server configuration, read from the environment at call time (12-factor). Never sent to the browser. */
export type Config = Readonly<{
  chartUrl: string;
  readingUrl: string;
  identityUrl: string;
  paymentUrl: string;
  /** Empty: the X-Internal-Token header is omitted. */
  internalToken: string;
  /** Empty: the admin page answers 404. */
  adminToken: string;
  adminIds: ReadonlySet<string>;
  /** Scheme + host (+ port) of the site, without a trailing slash. */
  webOrigin: string;
  cookieSecure: boolean;
  googleLogin: boolean;
  deployEnv: string;
  /** DEV_PAYMENT_SIMULATOR=1, never in prod. */
  simulatorEnabled: boolean;
  sepayWebhookAuth: "apikey" | "hmac-sha256";
  sepayWebhookSecret: string;
  qaStreamTimeoutMs: number;
  knowledgeDir: string;
}>;

const env = (k: string, dflt = ""): string => {
  const v = process.env[k];
  return v === undefined || v === "" ? dflt : v;
};

function positiveInt(raw: string, dflt: number): number {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : dflt;
}

export function cfg(): Config {
  const deployEnv = env("DEPLOY_ENV", "local");
  return Object.freeze({
    chartUrl: env("CHART_SVC_URL", "http://localhost:8082"),
    readingUrl: env("READING_URL", "http://localhost:8083"),
    identityUrl: env("IDENTITY_SVC_URL", "http://localhost:8084"),
    paymentUrl: env("PAYMENT_SVC_URL", "http://localhost:8085"),
    internalToken: env("INTERNAL_API_TOKEN"),
    adminToken: env("ADMIN_API_TOKEN"),
    adminIds: new Set(
      env("ADMIN_ACCOUNT_IDS")
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
    ),
    webOrigin: env("WEB_ORIGIN", "http://localhost:3001").replace(/\/+$/, ""),
    cookieSecure: env("COOKIE_SECURE", "0") === "1",
    googleLogin: env("GOOGLE_LOGIN", "0") === "1",
    deployEnv,
    simulatorEnabled: env("DEV_PAYMENT_SIMULATOR", "0") === "1" && deployEnv !== "prod",
    sepayWebhookAuth: env("SEPAY_WEBHOOK_AUTH", "apikey") === "hmac-sha256" ? "hmac-sha256" : "apikey",
    sepayWebhookSecret: env("SEPAY_WEBHOOK_SECRET"),
    qaStreamTimeoutMs: positiveInt(env("QA_STREAM_TIMEOUT_MS"), 95000),
    knowledgeDir: env("KNOWLEDGE_DIR", "../../knowledge/tuvi"),
  });
}
