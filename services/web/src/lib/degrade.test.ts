import { afterEach, describe, expect, it, vi } from "vitest";
import { degradeOrThrow, logRefusalOrThrow } from "@/lib/degrade";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

afterEach(() => vi.restoreAllMocks());

const lines = (write: { mock: { calls: unknown[][] } }) => write.mock.calls.map((c) => JSON.parse(String(c[0])));

describe("degradeOrThrow", () => {
  it("degrades silently on 401 (revoked session) and on UpstreamError (already logged)", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    expect(() => degradeOrThrow(new UpstreamProblem(401, undefined, "identity-svc"), "chip")).not.toThrow();
    expect(() => degradeOrThrow(new UpstreamError(), "chip")).not.toThrow();
    expect(write).not.toHaveBeenCalled();
  });

  it("logs other upstream refusals with name, status and reason only, then degrades", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    expect(() => degradeOrThrow(new UpstreamProblem(403, undefined, "identity-svc"), "balance-chip")).not.toThrow();
    expect(() => degradeOrThrow(new UpstreamProblem(409, "no_profile", "identity-svc"), "saved-chart-link")).not.toThrow();
    expect(() => degradeOrThrow(new UpstreamValidation({ birth: "bad" }, "identity-svc"), "chip")).not.toThrow();
    const [a, b, c] = lines(write);
    expect(a).toMatchObject({ level: "warn", upstream: "identity-svc", status: "403", part: "balance-chip" });
    expect(a).not.toHaveProperty("reason");
    expect(b).toMatchObject({ status: "409", reason: "no_profile", part: "saved-chart-link" });
    expect(c).toMatchObject({ level: "warn", status: "400", fields: "birth" });
    expect(Object.keys(a).sort()).toEqual(["level", "message", "part", "service", "span_id", "status", "timestamp", "trace_id", "upstream"]);
  });

  it("rethrows anything else", () => {
    const bug = new TypeError("x is undefined");
    expect(() => degradeOrThrow(bug, "chip")).toThrow(bug);
  });
});

describe("logRefusalOrThrow", () => {
  it("logs every refusal at error level, a 401 included (a wrong token is a misconfiguration)", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    logRefusalOrThrow(new UpstreamProblem(401, undefined, "payment-svc"), "buy");
    logRefusalOrThrow(new UpstreamProblem(409, "order_not_pending", "payment-svc"), "admin-assign");
    const [a, b] = lines(write);
    expect(a).toMatchObject({ level: "error", message: "upstream refused request", upstream: "payment-svc", status: "401", part: "buy" });
    expect(b).toMatchObject({ status: "409", reason: "order_not_pending" });
  });

  it("logs a 400 with the rejected field names, never their messages", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    logRefusalOrThrow(new UpstreamValidation({ adult_confirmed: "must be true", "bad name!": "x" }, "payment-svc"), "buy");
    logRefusalOrThrow(new UpstreamValidation({}), "buy");
    const [a, b] = lines(write);
    expect(a).toMatchObject({ upstream: "payment-svc", status: "400", part: "buy", fields: "adult_confirmed" });
    expect(JSON.stringify(a)).not.toContain("must be true");
    expect(b).toMatchObject({ upstream: "unknown", status: "400" });
    expect(b).not.toHaveProperty("fields");
  });

  it("stays quiet on UpstreamError (http.ts logged it) and rethrows a bug", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    logRefusalOrThrow(new UpstreamError(), "buy");
    expect(write).not.toHaveBeenCalled();
    const bug = new RangeError("boom");
    expect(() => logRefusalOrThrow(bug, "buy")).toThrow(bug);
  });
});
