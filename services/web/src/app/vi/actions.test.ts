import { beforeEach, describe, expect, it, vi } from "vitest";

const getChart = vi.fn();
const put = vi.fn();
const redirect = vi.fn((url: string) => {
  throw new Error(`REDIRECT:${url}`);
});

vi.mock("next/navigation", () => ({ redirect: (u: string) => redirect(u) }));
vi.mock("@/lib/birthStore", () => ({ birthStore: { put: (b: unknown) => put(b) } }));
vi.mock("@/lib/upstream", () => {
  class UpstreamError extends Error {
    constructor(readonly kind = "unavailable") {
      super(kind);
    }
  }
  class UpstreamValidation extends Error {
    constructor(readonly fields: Record<string, string>) {
      super("validation");
    }
  }
  return { getChart: (b: unknown) => getChart(b), UpstreamError, UpstreamValidation };
});

import { submitBirth } from "@/app/vi/actions";
import { UpstreamError, UpstreamValidation } from "@/lib/upstream";

function form(over: Record<string, string> = {}): FormData {
  const fd = new FormData();
  const v = { date: "1990-01-15", time: "08:30", gender: "male", zone: "Asia/Ho_Chi_Minh", ...over };
  for (const [k, val] of Object.entries(v)) fd.set(k, val);
  return fd;
}

beforeEach(() => {
  getChart.mockReset();
  put.mockReset();
  redirect.mockClear();
});

describe("submitBirth", () => {
  it("returns field errors and the submitted values for an invalid form, without calling upstream", async () => {
    const state = await submitBirth(null, form({ date: "nope", gender: "" }));
    expect(state?.errors.date).toBeTruthy();
    expect(state?.errors.gender).toBeTruthy();
    expect(state?.values).toEqual({ date: "nope", time: "08:30", gender: "", zone: "Asia/Ho_Chi_Minh" });
    expect(getChart).not.toHaveBeenCalled();
    expect(put).not.toHaveBeenCalled();
  });

  it("maps an upstream 400 to field errors and keeps the values", async () => {
    getChart.mockRejectedValue(new UpstreamValidation({ birth: "nonexistent local time" }));
    const state = await submitBirth(null, form());
    expect(state?.errors.date).toContain("chưa hợp lệ");
    expect(state?.unavailable).toBeUndefined();
    expect(state?.values?.date).toBe("1990-01-15");
    expect(put).not.toHaveBeenCalled();
  });

  it("falls back to a date error for an unknown upstream field", async () => {
    getChart.mockRejectedValue(new UpstreamValidation({ calendar: "bad" }));
    const state = await submitBirth(null, form());
    expect(Object.keys(state?.errors ?? {})).toEqual(["date"]);
  });

  it("shows the 'needs a moment' state when upstream is unavailable, storing nothing", async () => {
    getChart.mockRejectedValue(new UpstreamError());
    const state = await submitBirth(null, form());
    expect(state?.unavailable).toBe(true);
    expect(state?.errors).toEqual({});
    expect(state?.values?.time).toBe("08:30");
    expect(put).not.toHaveBeenCalled();
    expect(redirect).not.toHaveBeenCalled();
  });

  it("rethrows unexpected errors", async () => {
    getChart.mockRejectedValue(new TypeError("boom"));
    await expect(submitBirth(null, form())).rejects.toThrow("boom");
  });

  it("stores the birth and redirects to /vi/la-so/<id> without birth data in the URL", async () => {
    getChart.mockResolvedValue({});
    put.mockReturnValue("0b1c2d3e-aaaa-bbbb-cccc-ddddeeeeffff");
    await expect(submitBirth(null, form())).rejects.toThrow("REDIRECT:");
    expect(put).toHaveBeenCalledTimes(1);
    expect(put.mock.calls[0][0]).toMatchObject({ birth: "1990-01-15T08:30", gender: "male" });
    const url = redirect.mock.calls[0][0];
    expect(url).toBe("/vi/la-so/0b1c2d3e-aaaa-bbbb-cccc-ddddeeeeffff");
    expect(url).not.toMatch(/1990|0830|08:30|male/);
  });
});
