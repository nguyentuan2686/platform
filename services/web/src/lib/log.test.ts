import { afterEach, describe, expect, it, vi } from "vitest";
import { log } from "@/lib/log";

describe("log", () => {
  afterEach(() => vi.restoreAllMocks());

  it("writes one JSON line with the mandatory fields", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    log("info", "hello");
    expect(write).toHaveBeenCalledTimes(1);
    const line = String(write.mock.calls[0][0]);
    expect(line.endsWith("\n")).toBe(true);
    expect(line.trimEnd().split("\n")).toHaveLength(1);
    const obj = JSON.parse(line);
    for (const k of ["timestamp", "level", "service", "trace_id", "span_id", "message"]) {
      expect(obj).toHaveProperty(k);
    }
    expect(obj.service).toBe("web");
    expect(obj.level).toBe("info");
    expect(obj.message).toBe("hello");
    expect(obj.trace_id).toBe("");
    expect(obj.span_id).toBe("");
    expect(Number.isNaN(Date.parse(obj.timestamp))).toBe(false);
  });

  it("merges extra fields but never overrides the mandatory ones", () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    log("error", "boom", { upstream: "chart", service: "evil" });
    const obj = JSON.parse(String(write.mock.calls[0][0]));
    expect(obj.upstream).toBe("chart");
    expect(obj.service).toBe("web");
    expect(obj.level).toBe("error");
  });
});
