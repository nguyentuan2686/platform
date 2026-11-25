import { afterEach, describe, expect, it, vi } from "vitest";
import { degradeOrThrow } from "@/lib/degrade";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

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
    const [a, b] = lines(write);
    expect(a).toMatchObject({ level: "warn", upstream: "identity-svc", status: "403", part: "balance-chip" });
    expect(a).not.toHaveProperty("reason");
    expect(b).toMatchObject({ status: "409", reason: "no_profile", part: "saved-chart-link" });
    expect(Object.keys(a).sort()).toEqual(["level", "message", "part", "service", "span_id", "status", "timestamp", "trace_id", "upstream"]);
  });

  it("rethrows anything else", () => {
    const bug = new TypeError("x is undefined");
    expect(() => degradeOrThrow(bug, "chip")).toThrow(bug);
  });
});
